package com.lumi.player.ui

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Behavioral coverage for the scan-first add-song workflow: the tag-to-content
 * screen shows the scanned (or picked) tag's current song list and offers an
 * "Add song to this tag" button whose pick stores the file and appends it to
 * that tag's list. The button only makes sense for a selected tag, so with no
 * registered tags the pane asks for a tag instead. The song list is
 * associated by the tag's UID in the database, so no tag write is involved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TagAddSongUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private fun app(): LumiApp = ApplicationProvider.getApplicationContext()

    private fun openLinkPane(): LumiViewModel {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Tag to content").performClick()
        compose.waitForIdle()
        val field = MainActivity::class.java.getDeclaredField("viewModel")
        field.isAccessible = true
        return field.get(activity) as LumiViewModel
    }

    /** Runs a suspend block on the app's database and returns its result. */
    private fun <T> dbAndGet(block: suspend () -> T): T {
        var result: T? = null
        Thread { result = runBlocking { block() } }.also { it.start() }.apply { join() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun idleUntil(cond: () -> Boolean, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
    }

    /** Pumps the main looper until a node with [text] is laid out or fails. */
    private fun waitForText(text: String, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            compose.waitForIdle()
            if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(20)
        }
        compose.onNodeWithText(text).assertExists()
    }

    @Test
    fun addSongButton_appearsForASelectedTag_withItsCurrentList() {
        val tagId = dbAndGet { app().repository.addTag(UID, "Porch")?.id ?: 0L }
        val existingId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "First song", "content://first").id }
        dbAndGet { app().repository.updateTagEntries(tagId) { listOf(existingId) }; true }

        val viewModel = openLinkPane()
        idleUntil({ viewModel.effectiveSelectedTagId.value == tagId })
        // The + button sits at the bottom right of the section card, below
        // the scan button, the chip row and the song list: on the small
        // test screen it may be outside the viewport, so existence is the
        // layout assertion (same clipped-bottom pattern as the front
        // screen). The tag's current list must be shown for the selection.
        compose.onNodeWithContentDescription("Add song to this tag").assertExists()
        compose.onNodeWithText("First song").assertExists()
    }

    @Test
    fun addSongButton_hiddenWithoutRegisteredTags() {
        openLinkPane()
        compose.onNodeWithContentDescription("Add song to this tag").assertDoesNotExist()
        compose.onNodeWithText("Register a tag first, then link content to it.").assertIsDisplayed()
    }

    @Test
    fun addSongToTag_appendsTheNewSongAfterTheExistingList() {
        val tagId = dbAndGet { app().repository.addTag(UID, "Porch")?.id ?: 0L }
        val existingId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "First song", "content://first").id }
        dbAndGet { app().repository.updateTagEntries(tagId) { listOf(existingId) }; true }

        val viewModel = openLinkPane()
        idleUntil({ viewModel.effectiveSelectedTagId.value == tagId })
        compose.onNodeWithContentDescription("Add song to this tag").assertExists()

        // The pick lands: the view model persists the grant, stores the
        // entry, and appends it to the tag's list in one transaction.
        viewModel.addSongToTag(Uri.parse("content://fake/third-song"), tagId, "Third song")
        // The Room write is async: pump the main looper until the tag's
        // song list shows the new song.
        waitForText("Third song")

        val after = dbAndGet { app().repository.entriesForTag(tagId).map { it.label } }
        assertEquals(
            "the tag's list must gain the new song after the existing ones",
            listOf("First song", "Third song"),
            after,
        )
        // The new song is a first-class content item, so it also appears
        // among the "Add to this tag" shortcuts.
        compose.onNodeWithText("+ Third song").assertExists()
    }

    @Test
    fun addSongToTag_onAnEmptyTag_replacesTheNothingLinkedHint() {
        val tagId = dbAndGet { app().repository.addTag(UID, "Porch")?.id ?: 0L }

        val viewModel = openLinkPane()
        idleUntil({ viewModel.effectiveSelectedTagId.value == tagId })
        compose.onNodeWithText("This tag plays nothing yet.").assertExists()

        viewModel.addSongToTag(Uri.parse("content://fake/only-song"), tagId, "Only song")
        waitForText("Only song")
        compose.onNodeWithText("This tag plays nothing yet.").assertDoesNotExist()
        compose.onNodeWithText("+ Only song").assertExists()
    }

    companion object {
        const val UID = "04CAFE00"
    }
}
