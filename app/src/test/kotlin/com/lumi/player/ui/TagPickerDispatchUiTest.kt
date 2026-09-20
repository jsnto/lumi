package com.lumi.player.ui

import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryEntity
import com.lumi.player.data.EntryType
import kotlinx.coroutines.flow.first
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
 * The Content pane ("Add content") and the Link pane ("Add song to this tag")
 * share ONE OpenDocument launcher: whichever button was tapped stores the
 * pending target right before the launch, and the shared callback dispatches
 * the picked URI to that target. Visiting the Link pane replaces the shared
 * callback entry while the pane is composed and removes it on dispose, so a
 * pick that lands after a Link-pane visit must still reach the Content path
 * (a second, same-contract launcher in the Link pane used to drop that
 * result entirely). The real SAF picker cannot run under Robolectric, so the
 * pick is delivered straight through the activity's result registry - the
 * same callback slot the system's picker result would use.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TagPickerDispatchUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private fun app(): LumiApp = ApplicationProvider.getApplicationContext()

    private fun openMainActivity(): MainActivity {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        compose.waitForIdle()
        return activity
    }

    /** Runs a suspend block on the app's database and returns its result. */
    private fun <T> dbAndGet(block: suspend () -> T): T {
        var result: T? = null
        Thread { result = runBlocking { block() } }.also { it.start() }.apply { join() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /** Pumps the main looper until [cond] holds or fails. */
    private fun idleUntil(cond: () -> Boolean, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            compose.waitForIdle()
            Thread.sleep(10)
        }
        check(cond()) { "timed out waiting for the pick to land" }
    }

    /** The current content count, read off the database. */
    private fun entryCount(): Int =
        dbAndGet { app().repository.observeEntries().first().size }

    /** The tag's current song list, read off the database. */
    private fun tagLinks(tagId: Long): List<EntryEntity> =
        dbAndGet { app().repository.entriesForTag(tagId) }

    /**
     * Delivers the document picker's result for [uri] through the same
     * activity-result registry slot the real picker would use. The settings
     * screen registers two document launchers (the shared audio picker and
     * the folder picker, different contracts); the click above launched
     * exactly one of them and [ActivityResultRegistry.launch] records its
     * key in the registry's launched set, so the launched key is the slot to
     * deliver the pick to.
     */
    private fun deliverPick(activity: MainActivity, uri: Uri): String {
        val registry = activity.activityResultRegistry
        val field = ActivityResultRegistry::class.java
            .getDeclaredField("keyToRc")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val keyToRc = field.get(registry) as Map<String, Int>
        val launchedField = ActivityResultRegistry::class.java
            .getDeclaredField("launchedKeys")
        launchedField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val launchedKeys = launchedField.get(registry) as MutableList<String>
        val picker = keyToRc.entries
            .filter { entry: Map.Entry<String, Int> -> entry.key != "activity_rq#0" }
            .singleOrNull { it.key in launchedKeys }
            ?: error("expected the launched audio picker's registration; registry keys: $keyToRc launched=$launchedKeys")
        // The click above already called launch(); record the launch the
        // way the framework does when it has not recorded it yet.
        if (picker.key !in launchedKeys) launchedKeys.add(picker.key)
        val dispatched = registry.dispatchResult(picker.value, uri)
        check(dispatched) { "dispatchResult returned false; keys=$keyToRc launched=$launchedKeys" }
        return "keys=$keyToRc launched=$launchedKeys"
    }

    @Test
    fun contentPick_afterVisitingTheLinkPane_storesPlainContentWithoutLinking() {
        val tagId = dbAndGet { app().repository.addTag(UID, "Porch")?.id ?: 0L }

        // Compose (and dispose) the Link pane's launcher registration first,
        // the exact sequence that used to leave the shared request key with
        // no callback.
        val activity = openMainActivity()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Tag to content").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Content").performClick()
        compose.waitForIdle()

        // Tap the "Add content" + button: the pending target resets to null
        // and the shared picker launches.
        compose.onNodeWithContentDescription("Add content").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Add content").performClick()
        compose.waitForIdle()

        val diag = deliverPick(activity, Uri.parse("content://fake/plain-song"))

        // The entry must land as plain content (visible in the Content pane)
        // and must NOT be linked to the seeded tag - the pick was not for a
        // tag, and no callback may be missing.
        val deadline = System.currentTimeMillis() + 5_000
        var ok = false
        while (System.currentTimeMillis() < deadline && !ok) {
            ShadowLooper.idleMainLooper()
            compose.waitForIdle()
            ok = entryCount() == 1
            Thread.sleep(10)
        }
        val toast = org.robolectric.shadows.ShadowToast.getTextOfLatestToast()
        check(ok) { "the pick never reached the Content path; toast=$toast; $diag" }
        compose.waitForIdle()
        compose.onNodeWithText("Untitled").assertExists()
        val linked = tagLinks(tagId).map { it.id }
        assertEquals(
            "a Content-pane pick must not link the entry to any tag",
            emptyList<Long>(),
            linked,
        )
    }

    @Test
    fun linkPanePick_appendsTheSongToThePickedTagsList() {
        val tagId = dbAndGet { app().repository.addTag(UID, "Porch")?.id ?: 0L }
        val existingId = dbAndGet {
            app().repository.addEntry(EntryType.AUDIO, "First song", "content://first").id
        }
        dbAndGet { app().repository.updateTagEntries(tagId) { listOf(existingId) }; true }

        val activity = openMainActivity()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Tag to content").performClick()
        compose.waitForIdle()
        val field = MainActivity::class.java.getDeclaredField("viewModel")
        field.isAccessible = true
        val viewModel = field.get(activity) as LumiViewModel
        idleUntil({ viewModel.effectiveSelectedTagId.value == tagId })

        compose.onNodeWithContentDescription("Add song to this tag").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Add song to this tag").performClick()
        compose.waitForIdle()

        deliverPick(activity, Uri.parse("content://fake/second-song"))

        idleUntil({ tagLinks(tagId).size == 2 })
        val labels: List<String> = tagLinks(tagId).map { it.label }
        assertEquals(
            "the picked song must be appended after the tag's existing song",
            listOf("First song", "Untitled"),
            labels,
        )
    }

    companion object {
        const val UID = "04CAFE00"
    }
}
