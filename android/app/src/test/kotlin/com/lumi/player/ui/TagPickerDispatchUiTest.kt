package com.lumi.player.ui

import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryEntity
import com.lumi.player.data.EntryType
import com.lumi.player.data.TagSongRow
import kotlinx.coroutines.flow.first
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
 * The Content pane's "Add content" is the ONLY file-picker launch in the
 * settings screen; the tag detail page's "Add song" opens the in-app
 * content list instead (a pick links an already-stored entry, no picker),
 * so a pick that lands after a detail-page visit must still reach the plain
 * content path. The real SAF picker cannot run under Robolectric, so the
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
        markSetupCompleteForTests()
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
    private fun idleUntil(cond: () -> Boolean, timeoutMs: Long = 5_000, message: String = "the pick to land") {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            compose.waitForIdle()
            Thread.sleep(10)
        }
        check(cond()) { "timed out waiting for $message" }
    }

    /** The current content count, read off the database. */
    private fun entryCount(): Int =
        dbAndGet { app().repository.observeEntries().first().size }

    /** The activity-result launch keys (the settings screen's pickers are the activity's only launchers). */
    private fun launchedPickerKeys(activity: MainActivity): List<String> {
        val registry = activity.activityResultRegistry
        val field = ActivityResultRegistry::class.java.getDeclaredField("launchedKeys")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (field.get(registry) as MutableList<String>).toList()
    }

    /** The tag's current song list, read off the database. */
    private fun tagLinks(tagId: Long): List<TagSongRow> =
        dbAndGet { app().repository.entriesForTag(tagId) }

    /**
     * Delivers the document picker's result for [uri] through the same
     * activity-result registry slot the real picker would use. The settings
     * screen registers two document launchers (the content picker and the
     * folder picker, different contracts); the click above launched exactly
     * one of them and [ActivityResultRegistry.launch] records its key in the
     * registry's launched set, so the launched key is the slot to deliver
     * the pick to.
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
    fun contentPick_afterVisitingTheTagDetailPage_storesPlainContentWithoutLinking() {
        val tagId = dbAndGet { app().repository.addTag(UID, "Porch")?.id ?: 0L }

        // Compose the tag detail page (its "Add song" opens the in-app
        // content list - it touches the file picker no more) and leave it
        // for the Content pane.
        val activity = openMainActivity()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("NFC tags").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Porch").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Add song").assertExists()
        // The detail page's back unwinds one level to the NFC tags list...
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Scan NFC").assertExists()
        // ...and the tags list's back reaches the menu, whose Content row
        // is the next hop.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Content").performClick()
        compose.waitForIdle()

        // Tap the "Add content" + button: the content picker launches.
        compose.onNodeWithContentDescription("Add content").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Add content").performClick()
        compose.waitForIdle()

        deliverPick(activity, Uri.parse("content://fake/plain-song"))

        // The entry must land as plain content (visible in the Content
        // pane) and must NOT be linked to the seeded tag - the pick was
        // for the content pane, not for a tag.
        idleUntil({ entryCount() == 1 })
        // The row renders through the Room round trip (the write's commit
        // precedes the list flow's re-emission on the main looper): pump
        // until the content pane has settled on it.
        idleUntil(
            { compose.onAllNodesWithText("Untitled").fetchSemanticsNodes().isNotEmpty() },
            message = "the content pane's new row",
        )
        val linked = tagLinks(tagId).map { it.entryId }
        assertEquals(
            "a Content-pane pick must not link the entry to any tag",
            emptyList<Long>(),
            linked,
        )
    }

    @Test
    fun tagDetailPageAddSongLaunchesNoPicker_andTheInAppListLinksTheStoredEntry() {
        val tagId = dbAndGet { app().repository.addTag(UID, "Porch")?.id ?: 0L }
        val existingId = dbAndGet {
            app().repository.addEntry(EntryType.AUDIO, "First song", "content://first").id
        }
        val newId = dbAndGet {
            app().repository.addEntry(EntryType.AUDIO, "Second song", "content://second").id
        }
        dbAndGet { listOf(existingId).forEach { app().repository.linkEntryToTag(tagId, it) }; true }

        val activity = openMainActivity()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("NFC tags").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Porch").performClick()
        compose.waitForIdle()
        val field = MainActivity::class.java.getDeclaredField("viewModel")
        field.isAccessible = true
        val viewModel = field.get(activity) as LumiViewModel
        idleUntil({ viewModel.effectiveSelectedTagId.value == tagId })

        val launchedBefore = launchedPickerKeys(activity)
        compose.onNodeWithContentDescription("Add song").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Add song").performClick()
        compose.waitForIdle()

        // The + button opens the in-app content list - the file picker is
        // NOT launched (the save links, it does not store).
        compose.onNodeWithText("Add a song").assertExists()
        assertEquals(
            "the in-app add-song list must not launch the file picker",
            launchedBefore,
            launchedPickerKeys(activity),
        )

        // Checking the app's existing content marks it; the Save button
        // commits the selection, linking the stored entry AFTER the tag's
        // existing song - nothing is stored twice.
        compose.onNodeWithText("Second song").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        idleUntil({ tagLinks(tagId).size == 2 })
        val labels: List<String> = tagLinks(tagId).mapNotNull { it.entry?.label }
        assertEquals(
            "the checked song must be appended after the tag's existing song",
            listOf("First song", "Second song"),
            labels,
        )
        assertEquals(
            "the content list is unchanged - the stored row was linked",
            2,
            entryCount(),
        )
        assertEquals(listOf(existingId, newId), tagLinks(tagId).map { it.entryId })
    }

    companion object {
        const val UID = "04CAFE00"
    }
}
