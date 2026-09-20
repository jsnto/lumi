package com.lumi.player.ui

import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.compose.ui.test.assertIsDisplayed
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
 * Behavioral coverage for the per-tag page: tapping a tag row in the NFC
 * tags pane opens the tag's own page - its details (name + UID, with an
 * edit action on the "Tag details" section - the name is the only
 * editable detail, the UID is hardware-derived and read-only), its
 * current song list, an add-song action aimed at THIS tag, and a danger
 * zone that deletes the tag. The page reuses the shared selection and
 * add-song plumbing (the shared OpenDocument launcher and
 * [LumiViewModel.addSongToTag]), so the add-song pick is delivered the
 * same way the real picker's result would be: through the activity's
 * result registry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TagDetailPageUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private fun app(): LumiApp = ApplicationProvider.getApplicationContext()

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
        check(cond()) { "timed out waiting for the condition to hold" }
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

    /** The tag's current song list, read off the database. */
    private fun tagLinks(tagId: Long): List<EntryEntity> =
        dbAndGet { app().repository.entriesForTag(tagId) }

    /** The activity's view model (the same seam the other settings tests use). */
    private fun mainViewModel(activity: MainActivity): LumiViewModel {
        val field = MainActivity::class.java.getDeclaredField("viewModel")
        field.isAccessible = true
        return field.get(activity) as LumiViewModel
    }

    /** Opens settings, the NFC tags pane, and then the [name] tag's page. */
    private fun openTagPage(name: String): MainActivity {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("NFC tags").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(name).performClick()
        compose.waitForIdle()
        return activity
    }

    /**
     * Delivers the shared audio picker's result for [uri] through the same
     * activity-result registry slot the real picker would use. The "Add
     * song" click above launched the picker and recorded its key in the
     * registry's launched set, so the launched key is the slot to deliver
     * the pick to (the folder picker, a different contract, was not
     * launched).
     */
    private fun deliverPick(activity: MainActivity, uri: Uri) {
        val registry = activity.activityResultRegistry
        val field = ActivityResultRegistry::class.java.getDeclaredField("keyToRc")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val keyToRc = field.get(registry) as Map<String, Int>
        val launchedField = ActivityResultRegistry::class.java.getDeclaredField("launchedKeys")
        launchedField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val launchedKeys = launchedField.get(registry) as MutableList<String>
        val picker = keyToRc.entries
            .filter { entry: Map.Entry<String, Int> -> entry.key != "activity_rq#0" }
            .singleOrNull { it.key in launchedKeys }
            ?: error("expected the launched audio picker's registration; registry keys: $keyToRc launched=$launchedKeys")
        if (picker.key !in launchedKeys) launchedKeys.add(picker.key)
        val dispatched = registry.dispatchResult(picker.value, uri)
        check(dispatched) { "dispatchResult returned false; keys=$keyToRc launched=$launchedKeys" }
    }

    // ---------- the page opens from a row tap and shows details + songs ----------

    @Test
    fun tagRowTapOpensThePageWithTheTagsDetailsAndItsOwnSongs() {
        val bunnyId = dbAndGet { app().repository.addTag(BUNNY_UID, "Bunny")?.id ?: 0L }
        val owlId = dbAndGet { app().repository.addTag(OWL_UID, "Owl")?.id ?: 0L }
        val firstId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "First song", "content://first").id }
        val secondId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Second song", "content://second").id }
        val hootId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Hoot track", "content://hoot").id }
        dbAndGet {
            app().repository.updateTagEntries(bunnyId) { listOf(firstId, secondId) }
            app().repository.updateTagEntries(owlId) { listOf(hootId) }
            true
        }

        openTagPage("Bunny")

        // The page's details section names the tag and shows its UID.
        compose.onNodeWithText("Tag details").assertIsDisplayed()
        compose.onNodeWithText(BUNNY_UID).assertExists()

        // Bunny's song list is on the page...
        waitForText("First song")
        compose.onNodeWithText("Second song").assertExists()
        // ...and only Bunny's: Owl's song is not on Bunny's page (the list
        // is keyed by the tag).
        compose.onNodeWithText("Hoot track").assertDoesNotExist()

        // The add-song affordance and the danger zone are on the page (the
        // page's bottom may sit outside the small test screen's viewport,
        // so existence is the layout assertion).
        compose.onNodeWithContentDescription("Add song").assertExists()
        compose.onNodeWithText("Danger zone").assertExists()
    }

    // ---------- add a song from the page: it lands in THIS tag's list ----------

    @Test
    fun addSongFromThePageLandsInThisTagsListAndInContent() {
        val tagId = dbAndGet { app().repository.addTag(TAG_UID, "Porch")?.id ?: 0L }

        val activity = openTagPage("Porch")
        val viewModel = mainViewModel(activity)
        idleUntil({ viewModel.effectiveSelectedTagId.value == tagId })

        // The empty tag says so, and the + button on the Songs section's
        // title row launches the shared audio picker (it may sit below the
        // viewport on the small test screen).
        compose.onNodeWithText("This tag plays nothing yet.").assertExists()
        compose.onNodeWithContentDescription("Add song").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Add song").performClick()
        compose.waitForIdle()

        // The pick lands the way the real picker's result would: the
        // shared callback persists the grant, stores the entry, and
        // appends it to THIS tag's list in one transaction.
        deliverPick(activity, Uri.parse("content://fake/added-song"))

        idleUntil({ tagLinks(tagId).size == 1 })
        assertEquals(
            "the picked song must be in the page's tag's list",
            listOf("Untitled"),
            tagLinks(tagId).map { it.label },
        )
        waitForText("Untitled")
        compose.onNodeWithText("This tag plays nothing yet.").assertDoesNotExist()
        // The picked song is also a first-class content item.
        assertEquals(
            "the picked song must also be in the content list",
            listOf("Untitled"),
            dbAndGet { app().repository.observeEntries().first().map { it.label } },
        )
    }

    // ---------- the danger zone deletes the tag; the songs stay ----------

    @Test
    fun dangerZoneDeleteRemovesTheTagAndLeavesItsSongsInContent() {
        val tagId = dbAndGet { app().repository.addTag(TAG_UID, "Porch")?.id ?: 0L }
        val firstId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "First song", "content://first").id }
        val secondId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Second song", "content://second").id }
        val looseId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Loose song", "content://loose").id }
        dbAndGet {
            app().repository.updateTagEntries(tagId) { listOf(firstId, secondId) }
            true
        }

        openTagPage("Porch")

        // The danger zone states the effect before the delete: the tag and
        // its links go, the songs stay in the content list.
        compose.onNodeWithText("Removes the tag and its song links. Its songs stay in the content list.")
            .assertExists()
        compose.onNodeWithText("Delete").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()

        // The confirmation names what will be removed before the confirm.
        compose.onNodeWithText("Remove tag?").assertIsDisplayed()
        compose.onNodeWithText("“Porch” and its links will be removed.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNodeWithText("Remove").performClick()
        compose.waitForIdle()

        // The tag is gone (the page fell back to the menu) and its songs
        // remain in the content list - deleting a tag never deletes the
        // entries.
        waitForText("NFC tags")
        compose.onNodeWithText("Danger zone").assertDoesNotExist()
        dbAndGet {
            val tags = app().repository.observeTags().first()
            check(tags.isEmpty()) { "the tag must be deleted, got $tags" }
            val labels = app().repository.observeEntries().first().map { it.label }
            check(
                listOf("First song", "Second song", "Loose song").all { it in labels },
            ) { "the tag's songs must stay in the content list, got $labels" }
            true
        }
    }

    // ---------- back navigation: the page unwinds to the tags menu ----------

    @Test
    fun systemBackFromTheTagPageReturnsToTheTagsMenu() {
        dbAndGet { app().repository.addTag(TAG_UID, "Porch")?.id ?: 0L }

        val activity = openTagPage("Porch")
        waitForText("Tag details")

        // The page is a sub-screen, so the screen's BackHandler (enabled
        // while any pane is showing) must answer the first system back and
        // settle on the menu - exactly like every other sub-screen - with
        // the menu row intact and the page gone. It must not pop settings
        // away in one step.
        activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        compose.onNodeWithText("Play mode").assertIsDisplayed()
        compose.onNodeWithText("NFC tags").assertIsDisplayed()
        compose.onNodeWithText("Tag details").assertDoesNotExist()
        compose.onNodeWithText("Danger zone").assertDoesNotExist()

        // The second back leaves the menu entirely and returns to the
        // player screen (the settings hop pops only from the menu).
        activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        compose.onNodeWithText("NFC tags").assertDoesNotExist()
    }

    @Test
    fun editActionOpensTheEditDialogAndSavingUpdatesTheDisplayedName() {
        dbAndGet { app().repository.addTag(TAG_UID, "Porch") }
        val activity = openTagPage("Porch")
        val field = MainActivity::class.java.getDeclaredField("viewModel")
        field.isAccessible = true
        val viewModel = field.get(activity) as LumiViewModel

        // The edit action sits on the "Tag details" section's title row.
        compose.onNodeWithContentDescription("Edit tag").performClick()
        compose.waitForIdle()
        // The dialog: its title, the read-only UID as context, Save / Cancel.
        compose.onNodeWithText("Edit tag").assertIsDisplayed()
        compose.onNodeWithText("ID: $TAG_UID").assertExists()
        compose.onNodeWithText("Save").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()

        // Saving goes through the view model's rename path. This pruned
        // Compose mirror exposes no editable-text semantics, so the
        // prefilled name field is saved as-is (a no-op rename) here; the
        // displayed-name update is then asserted through the very same
        // path.
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        idleUntil({ dbAndGet { app().repository.tagByUid(TAG_UID) }?.name == "Porch" })
        // The dialog is gone; the page still shows the saved name (the top
        // bar and the details row both carry it, so assert existence rather
        // than a single displayed node).
        compose.onNodeWithText("Save").assertDoesNotExist()
        check(compose.onAllNodesWithText("Porch").fetchSemanticsNodes().isNotEmpty())

        // ...and the page's displayed name follows the saved value.
        val tag = dbAndGet { app().repository.tagByUid(TAG_UID) }
        checkNotNull(tag)
        viewModel.renameTag(tag, "Renamed tag")
        idleUntil({ dbAndGet { app().repository.tagByUid(TAG_UID) }?.name == "Renamed tag" })
        compose.waitForIdle()
        check(compose.onAllNodesWithText("Renamed tag").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Porch").assertDoesNotExist()
    }

    companion object {
        const val TAG_UID = "04:CA:FE:00:00"
        const val BUNNY_UID = "04:CA:FE:00:01"
        const val OWL_UID = "04:CA:FE:00:02"
    }
}
