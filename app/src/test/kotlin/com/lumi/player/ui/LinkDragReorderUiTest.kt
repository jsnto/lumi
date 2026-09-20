package com.lumi.player.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * Behavioral coverage for the tag-to-content drag reorder: the link editor's
 * song list shows a "Drag a song to reorder." hint for two or more entries,
 * and a drag release persists the new order through the same
 * updateTagEntries path as the up/down buttons - so the reordered list is the
 * persisted order, untouched entries keep their relative order, and a
 * single-item list is a no-op. The drag itself is a platform view-drag
 * (foundation's dragAndDropSource/dragAndDropTarget) Robolectric cannot
 * faithfully run, so the drop is driven through [LumiViewModel.reorderLinked]
 * the way the pickers are driven through the view model in
 * [TagAddSongUiTest]; the list's UI asserts (rows compose with their
 * drag source/target wiring, hint, rendered order, existing buttons)
 * cover the row wiring this feature touches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    qualifiers = "w320dp-h800dp",
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LinkDragReorderUiTest {

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

    /** The tag's current list as stored (the single source of truth). */
    private fun tagsEntries(tagId: Long): List<String> =
        dbAndGet { app().repository.entriesForTag(tagId).map { it.label } }

    /** Pumps the main looper until the tag's list matches [labels] or fails. */
    private fun waitForList(tagId: Long, labels: List<String>, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            compose.waitForIdle()
            if (tagsEntries(tagId) == labels) return
            Thread.sleep(20)
        }
        assertEquals(labels, tagsEntries(tagId))
    }

    /** The y position of a list row's label, so row order can be asserted. */
    private fun yOf(text: String): Float {
        compose.waitForIdle()
        return compose.onAllNodesWithText(text)
            .fetchSemanticsNodes()
            .first()
            .boundsInRoot
            .top
    }

    /** The topmost clickable "Move up" button (the first row's one is
     *  disabled; with N rows there are N-1 clickable ones, top to bottom). */
    private fun clickableMoveUp(): SemanticsNodeInteraction =
        compose.onAllNodes(
            hasContentDescription("Move up") and hasClickAction() and isNotEnabled().not(),
        ).get(0)

    @Test
    fun dragHint_shownForTwoOrMoreLinkedEntries() {
        val tagId = dbAndGet { app().repository.addTag(UID, "Porch")?.id ?: 0L }
        val aId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Song A", "content://a").id }
        val bId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Song B", "content://b").id }
        dbAndGet { app().repository.updateTagEntries(tagId) { listOf(aId, bId) }; true }

        val viewModel = openLinkPane()
        idleUntil({ viewModel.effectiveSelectedTagId.value == tagId })
        waitForList(tagId, listOf("Song A", "Song B"))

        compose.onNodeWithText("Drag a song to reorder.").assertExists()
    }

    @Test
    fun dragHint_hiddenForASingleLinkedEntry() {
        val tagId = dbAndGet { app().repository.addTag(UID, "Porch")?.id ?: 0L }
        val aId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Song A", "content://a").id }
        dbAndGet { app().repository.updateTagEntries(tagId) { listOf(aId) }; true }

        val viewModel = openLinkPane()
        idleUntil({ viewModel.effectiveSelectedTagId.value == tagId })
        waitForList(tagId, listOf("Song A"))

        compose.onNodeWithText("Drag a song to reorder.").assertDoesNotExist()
    }

    @Test
    fun reorderLinked_persistsTheNewOrder_andUntouchedEntriesKeepTheirOrder() {
        val tagId = dbAndGet { app().repository.addTag(UID, "Porch")?.id ?: 0L }
        val aId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Song A", "content://a").id }
        val bId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Song B", "content://b").id }
        val cId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Song C", "content://c").id }
        dbAndGet { app().repository.updateTagEntries(tagId) { listOf(aId, bId, cId) }; true }

        val viewModel = openLinkPane()
        idleUntil({ viewModel.effectiveSelectedTagId.value == tagId })
        waitForList(tagId, listOf("Song A", "Song B", "Song C"))

        // A drag of the first row onto the last position: the drop commits the
        // new index through the view model, same as the up/down buttons do.
        viewModel.reorderLinked(tagId, 0, 2)
        waitForList(tagId, listOf("Song B", "Song C", "Song A"))

        // The persisted order is the reordered one...
        assertEquals(listOf("Song B", "Song C", "Song A"), tagsEntries(tagId))
        // ...the rows render in that order, and the untouched entries kept
        // their relative order (B before C).
        assertTrue("Song B above Song C", yOf("Song B") < yOf("Song C"))
        assertTrue("Song C above Song A", yOf("Song C") < yOf("Song A"))
        compose.onNodeWithText("Drag a song to reorder.").assertExists()
    }

    @Test
    fun reorderLinked_isANoOpForASingleItem() {
        val tagId = dbAndGet { app().repository.addTag(UID, "Porch")?.id ?: 0L }
        val aId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Song A", "content://a").id }
        dbAndGet { app().repository.updateTagEntries(tagId) { listOf(aId) }; true }

        val viewModel = openLinkPane()
        idleUntil({ viewModel.effectiveSelectedTagId.value == tagId })
        waitForList(tagId, listOf("Song A"))

        viewModel.reorderLinked(tagId, 0, 1)
        viewModel.reorderLinked(tagId, 0, 0)
        Thread.sleep(100)
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("Song A"), tagsEntries(tagId))
        compose.onNodeWithText("Song A").assertExists()
    }

    @Test
    fun moveUpButton_stillWorksAfterDragSupport() {
        val tagId = dbAndGet { app().repository.addTag(UID, "Porch")?.id ?: 0L }
        val aId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Song A", "content://a").id }
        val bId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Song B", "content://b").id }
        val cId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Song C", "content://c").id }
        dbAndGet { app().repository.updateTagEntries(tagId) { listOf(aId, bId, cId) }; true }

        val viewModel = openLinkPane()
        idleUntil({ viewModel.effectiveSelectedTagId.value == tagId })
        waitForList(tagId, listOf("Song A", "Song B", "Song C"))

        // The first row's Move up is disabled, so the topmost clickable one
        // belongs to the second row: tapping it swaps B above A.
        clickableMoveUp().performClick()
        waitForList(tagId, listOf("Song B", "Song A", "Song C"))
        assertEquals(listOf("Song B", "Song A", "Song C"), tagsEntries(tagId))
    }

    companion object {
        const val UID = "04DBAD00"
    }
}
