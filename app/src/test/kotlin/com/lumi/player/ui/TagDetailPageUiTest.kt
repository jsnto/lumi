package com.lumi.player.ui

import androidx.activity.result.ActivityResultRegistry
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryEntity
import com.lumi.player.data.EntryType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
 * Behavioral coverage for the per-tag page: tapping a tag row in the NFC
 * tags pane opens the tag's own page - its details (name + UID, with an
 * edit action on the "Tag details" section - the name is the only
 * editable detail, the UID is hardware-derived and read-only), its
 * current song list - in the order the songs were added, reorderable by
 * dragging a row's drag handle (a completed drag persists as the tag's
 * link order - the play order - and survives a navigation round trip; a
 * drag that ends where it started persists nothing; the held handle
 * consumes the move so the page's scroll never runs; sub-pitch moves
 * settle where the pointer says; a multi-move drag that crosses the swap
 * boundary mid-drag settles where the pointer releases; a handle that
 * finished a drag starts the next one; a reorder order that is no longer
 * a permutation of the current song set - a list change in flight - is
 * rejected and keeps the current order, firing no save event) - an
 * add-song action aimed at THIS tag, and a danger zone that deletes the
 * tag. The add-song action opens the in-app content
 * list (the app's existing content, individual or folder-imported): a
 * pick links the stored entry to the tag ([LumiViewModel.linkEntryToTag]) -
 * it never launches the file picker, so nothing is stored twice.
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
     * The activity-result launch keys through the settings screen's
     * pickers (the activity's only launchers): the in-app add-song flow
     * must keep this set unchanged - a pick links existing content, it
     * never launches the file picker.
     */
    private fun launchedPickerKeys(activity: MainActivity): List<String> {
        val registry = activity.activityResultRegistry
        val field = ActivityResultRegistry::class.java.getDeclaredField("launchedKeys")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (field.get(registry) as MutableList<String>).toList()
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

    // ---------- add a song from the page: the in-app content list links the stored row ----------

    @Test
    fun addSongFromThePageLinksAnExistingContentEntryToTheTag() {
        val tagId = dbAndGet { app().repository.addTag(TAG_UID, "Porch")?.id ?: 0L }
        val contentId = dbAndGet {
            app().repository.addEntry(EntryType.AUDIO, "First song", "content://first").id
        }

        val activity = openTagPage("Porch")
        val viewModel = mainViewModel(activity)
        idleUntil({ viewModel.effectiveSelectedTagId.value == tagId })

        // The empty tag says so, and the + button on the Songs section's
        // title row opens the in-app content list (it may sit below the
        // viewport on the small test screen).
        compose.onNodeWithText("This tag plays nothing yet.").assertExists()
        val launchedBefore = launchedPickerKeys(activity)
        compose.onNodeWithContentDescription("Add song").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Add song").performClick()
        compose.waitForIdle()

        // The in-app list offers the app's existing content, and the file
        // picker is never launched (the pick links, it does not store).
        compose.onNodeWithText("Add a song").assertExists()
        waitForText("First song")
        assertEquals(
            "the add-song action must not launch the file picker",
            launchedBefore,
            launchedPickerKeys(activity),
        )

        // Picking the song links the STORED row: the tag's list gains it
        // and the content list grows by nothing.
        compose.onNodeWithText("First song").performClick()
        compose.waitForIdle()
        idleUntil({ tagLinks(tagId).size == 1 })
        assertEquals(
            "the picked row must link the stored entry",
            listOf(contentId),
            tagLinks(tagId).map { it.id },
        )
        waitForText("First song")
        compose.onNodeWithText("This tag plays nothing yet.").assertDoesNotExist()
        assertEquals(
            "picking links the stored row - no new content row is stored",
            1,
            dbAndGet { app().repository.observeEntries().first().size },
        )
    }

    @Test
    fun addingASongTheTagAlreadyPlaysIsReportedWithoutDuplicatingTheLink() {
        val tagId = dbAndGet { app().repository.addTag(TAG_UID, "Porch")?.id ?: 0L }
        val firstId = dbAndGet {
            app().repository.addEntry(EntryType.AUDIO, "First song", "content://first").id
        }
        val secondId = dbAndGet {
            app().repository.addEntry(EntryType.AUDIO, "Second song", "content://second").id
        }
        dbAndGet { app().repository.updateTagEntries(tagId) { listOf(firstId) }; true }

        val activity = openTagPage("Porch")
        val viewModel = mainViewModel(activity)
        idleUntil({ viewModel.effectiveSelectedTagId.value == tagId })

        compose.onNodeWithContentDescription("Add song").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Add song").performClick()
        compose.waitForIdle()

        // The list offers the song but flags it: the tag already plays it
        // (the flagged row's detail is the unique way to find it - the
        // song's own row sits on the page below as well).
        assertTrue(compose.onAllNodesWithText("First song").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Already on this tag").assertExists()

        // Picking the flagged song is REPORTED - the tag's list keeps the
        // song exactly once and the list stays open for another pick.
        compose.onNodeWithText("Already on this tag").performClick()
        compose.waitForIdle()
        idleUntil({ tagLinks(tagId).size == 1 })
        assertEquals(
            "a second pick of the same song must not duplicate the link",
            listOf(firstId),
            tagLinks(tagId).map { it.id },
        )
        compose.onNodeWithText("Add a song").assertExists()
        compose.onNodeWithText("Second song").assertExists()
    }

    @Test
    fun theAddSongListCollapsesDuplicateContentLabelsIntoOneRow() {
        val tagId = dbAndGet { app().repository.addTag(TAG_UID, "Porch")?.id ?: 0L }
        val firstCopy = dbAndGet {
            app().repository.addEntry(EntryType.AUDIO, "First song", "content://first").id
        }
        dbAndGet {
            app().repository.addEntry(EntryType.AUDIO, "First song", "content://first-dup").id
        }

        val activity = openTagPage("Porch")
        val viewModel = mainViewModel(activity)
        idleUntil({ viewModel.effectiveSelectedTagId.value == tagId })

        compose.onNodeWithContentDescription("Add song").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Add song").performClick()
        compose.waitForIdle()

        // Duplicate content (the same label stored twice) displays ONCE -
        // one row per song, the first stored copy behind it.
        waitForText("First song")
        assertEquals(
            "the collapsed list offers the duplicated song exactly once",
            1,
            compose.onAllNodesWithText("First song").fetchSemanticsNodes().size,
        )

        compose.onNodeWithText("First song").performClick()
        compose.waitForIdle()
        idleUntil({ tagLinks(tagId).size == 1 })
        assertEquals(
            "picking the collapsed row links the FIRST stored copy",
            listOf(firstCopy),
            tagLinks(tagId).map { it.id },
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

        // The tag is gone (the page fell back to the NFC tags list, where
        // the tag's row lived) and its songs remain in the content list -
        // deleting a tag never deletes the entries.
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

    // ---------- remove one song from the tag: the rows' remove action ----------

    @Test
    fun theSongRowsExposeARemoveActionAndCancellingKeepsTheSong() {
        val tagId = dbAndGet { app().repository.addTag(TAG_UID, "Porch")?.id ?: 0L }
        val firstId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "First song", "content://first").id }
        val secondId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Second song", "content://second").id }
        dbAndGet { app().repository.updateTagEntries(tagId) { listOf(firstId, secondId) }; true }

        val activity = openTagPage("Porch")
        val viewModel = mainViewModel(activity)
        idleUntil({ viewModel.effectiveSelectedTagId.value == tagId })
        waitForText("Second song")
        compose.waitForIdle()

        // Every song row exposes its own remove action, and the drag handle
        // (the row's reorder region) is still there - the two are separate
        // affordances on the row.
        compose.onNodeWithContentDescription("Remove First song from the tag").assertExists()
        compose.onNodeWithContentDescription("Remove Second song from the tag").assertExists()
        compose.onNodeWithContentDescription("Reorder First song").assertExists()
        compose.onNodeWithContentDescription("Reorder Second song").assertExists()

        // The remove action confirms before it bites: the dialog states the
        // effect - only this song's link to the tag goes; the song stays in
        // the content list.
        compose.onNodeWithContentDescription("Remove First song from the tag").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Remove First song from the tag").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Remove song from the tag?").assertIsDisplayed()
        compose.onNodeWithText("“First song” will be removed from Porch. It stays in the content list.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()

        // Cancelling keeps the song on the tag and closes the dialog.
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Remove song from the tag?").assertDoesNotExist()
        assertEquals(
            "a cancelled removal changes nothing",
            listOf(firstId, secondId),
            tagLinks(tagId).map { it.id },
        )
        compose.onNodeWithText("First song").assertExists()
        compose.onNodeWithText("Second song").assertExists()
    }

    @Test
    fun removingASongDropsItFromTheTagsListKeepingTheRestInOrder() {
        val tagId = dbAndGet { app().repository.addTag(TAG_UID, "Porch")?.id ?: 0L }
        val owlId = dbAndGet { app().repository.addTag(OWL_UID, "Owl")?.id ?: 0L }
        val firstId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "First song", "content://first").id }
        val secondId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Second song", "content://second").id }
        val thirdId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Third song", "content://third").id }
        dbAndGet {
            app().repository.updateTagEntries(tagId) { listOf(firstId, secondId, thirdId) }
            // The second tag plays the same middle song too (the removal must
            // only touch THIS tag's link).
            app().repository.updateTagEntries(owlId) { listOf(secondId) }
            true
        }

        val activity = openTagPage("Porch")
        val viewModel = mainViewModel(activity)
        val events = mutableListOf<String>()
        viewModel.viewModelScope.launch { viewModel.uiEvents.collect { e -> events += e } }
        idleUntil({ viewModel.effectiveSelectedTagId.value == tagId })
        waitForText("Third song")
        compose.waitForIdle()

        // Remove the MIDDLE song: the page confirms, then the link goes.
        compose.onNodeWithContentDescription("Remove Second song from the tag").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Remove Second song from the tag").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Remove song from the tag?").assertIsDisplayed()
        compose.onNodeWithText("“Second song” will be removed from Porch. It stays in the content list.").assertIsDisplayed()
        compose.onNodeWithText("Remove").performClick()
        compose.waitForIdle()

        // The tag's list loses exactly the removed song - the rest keep
        // their order (the middle one is out, the order of what stays is
        // unchanged).
        idleUntil({ tagLinks(tagId).map { it.id } == listOf(firstId, thirdId) })
        assertEquals(
            "the remaining songs keep their persisted order",
            listOf(firstId, thirdId),
            tagLinks(tagId).map { it.id },
        )
        assertTrue(
            "the removal is reported as an event",
            events.contains("Song removed from the tag"),
        )

        // The other tag's link to the same song is untouched.
        assertEquals(
            "the other tag still plays the removed song",
            listOf(secondId),
            tagLinks(owlId).map { it.id },
        )

        // The song row leaves the page (the dialog's copy carried the label
        // while open, so wait for the node to actually go away).
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            compose.waitForIdle()
            if (compose.onAllNodesWithText("Second song").fetchSemanticsNodes().isEmpty()) break
            Thread.sleep(20)
        }
        compose.onNodeWithText("Second song").assertDoesNotExist()
        // The remaining rows are on the page in their order.
        val firstTop = compose.onNodeWithText("First song").fetchSemanticsNode().boundsInRoot.top
        val thirdTop = compose.onNodeWithText("Third song").fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "the remaining rows keep their order on the page",
            firstTop > 0f && firstTop < thirdTop,
        )

        // The removed song stays in the app's content list - removing a
        // song from a tag never deletes the entry.
        dbAndGet {
            check(app().repository.observeEntries().first().any { it.id == secondId }) {
                "the removed song must stay in the content list"
            }
            true
        }
    }

    @Test
    fun removingTheLastSongFallsBackToTheEmptyListText() {
        val tagId = dbAndGet { app().repository.addTag(TAG_UID, "Porch")?.id ?: 0L }
        val onlyId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Only song", "content://only").id }
        dbAndGet { app().repository.updateTagEntries(tagId) { listOf(onlyId) }; true }

        val activity = openTagPage("Porch")
        val viewModel = mainViewModel(activity)
        idleUntil({ viewModel.effectiveSelectedTagId.value == tagId })
        waitForText("Only song")
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Remove Only song from the tag").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Remove Only song from the tag").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Remove").performClick()
        compose.waitForIdle()

        idleUntil({ tagLinks(tagId).isEmpty() })
        // An empty list falls back to the existing empty state (the observed
        // list drives it).
        waitForText("This tag plays nothing yet.")
    }

    // ---------- back navigation: the page unwinds one level to the tags list ----------

    @Test
    fun systemBackFromTheTagPageReturnsToTheTagsListAndOnlyThenTheMenu() {
        dbAndGet { app().repository.addTag(TAG_UID, "Porch")?.id ?: 0L }

        val activity = openTagPage("Porch")
        waitForText("Tag details")

        // The page is a child of the NFC tags pane, so the screen's
        // BackHandler must answer the first system back by unwinding ONE
        // level - to the NFC tags list, not the menu - with the list's scan
        // action and the tag's row back and the page gone. It must not pop
        // settings away in one step.
        activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        compose.onNodeWithText("Scan NFC").assertExists()
        compose.onNodeWithText(TAG_UID).assertExists()
        compose.onNodeWithText("Tag details").assertDoesNotExist()
        compose.onNodeWithText("Danger zone").assertDoesNotExist()

        // The second back leaves the tags list for the settings menu.
        activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        compose.onNodeWithText("Play mode").assertIsDisplayed()
        compose.onNodeWithText("Scan NFC").assertDoesNotExist()

        // The third back leaves the menu entirely and returns to the
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

    // ---------- drag reorder: the list shows in added order; a drag persists across a round trip ----------

    @Test
    fun theSongListShowsInAddedOrder() {
        val tagId = dbAndGet { app().repository.addTag(TAG_UID, "Porch")?.id ?: 0L }
        val tag = checkNotNull(dbAndGet { app().repository.tagByUid(TAG_UID) })
        val first = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "First song", "content://first") }
        val second = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Second song", "content://second") }

        val activity = openTagPage("Porch")
        val viewModel = mainViewModel(activity)
        idleUntil({ viewModel.effectiveSelectedTagId.value == tagId })

        // The page's own add-song path appends one link at a time, so the
        // stored order is the order the songs were added.
        viewModel.linkEntryToTag(tag, first)
        idleUntil({ tagLinks(tagId).size == 1 })
        viewModel.linkEntryToTag(tag, second)
        idleUntil({ tagLinks(tagId).size == 2 })
        assertEquals(
            "the stored order is the order the songs were added",
            listOf(first.id, second.id),
            tagLinks(tagId).map { it.id },
        )

        // The page shows the songs in that order (top to bottom).
        compose.waitForIdle()
        val firstTop = compose.onNodeWithText("First song").fetchSemanticsNode().boundsInRoot.top
        val secondTop = compose.onNodeWithText("Second song").fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "the song list shows in the added order",
            firstTop > 0f && firstTop < secondTop,
        )
    }

    @Test
    fun aDragReorderPersistsAcrossANavigationRoundTrip() {
        val tagId = dbAndGet { app().repository.addTag(TAG_UID, "Porch")?.id ?: 0L }
        val firstId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "First song", "content://first").id }
        val secondId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Second song", "content://second").id }
        val thirdId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Third song", "content://third").id }
        dbAndGet { app().repository.updateTagEntries(tagId) { listOf(firstId, secondId, thirdId) }; true }

        val activity = openTagPage("Porch")
        val viewModel = mainViewModel(activity)
        idleUntil({ viewModel.effectiveSelectedTagId.value == tagId })
        waitForText("Third song")
        compose.waitForIdle()

        // The row pitch, measured from the laid-out rows: the gap between
        // the tops of the first two rows.
        compose.onNodeWithContentDescription("Reorder Second song").performScrollTo()
        compose.waitForIdle()
        val firstTop = compose.onNodeWithText("First song").fetchSemanticsNode().boundsInRoot.top
        val secondTop = compose.onNodeWithText("Second song").fetchSemanticsNode().boundsInRoot.top
        assertTrue("the rows must be laid out", firstTop > 0f && secondTop > firstTop)
        val pitch = (secondTop - firstTop).toLong()

        // Drag the second song's handle up by one pitch: the list previews
        // the swap and the release persists it.
        compose.onNodeWithContentDescription("Reorder Second song").performTouchInput {
            down(center)
            moveBy(Offset(0f, -pitch.toFloat()))
            up()
        }
        compose.waitForIdle()

        // The reordered list is the persisted link order (the play order).
        idleUntil({ tagLinks(tagId).map { it.id } == listOf(secondId, firstId, thirdId) })

        // The page shows it: the dragged row now leads (the last row may
        // sit at the edge of the small test screen's viewport, where a
        // clipped node reports empty bounds - so its position is asserted
        // by existence; the persisted order above is the authoritative pin).
        compose.waitForIdle()
        val bTop = compose.onNodeWithText("Second song").fetchSemanticsNode().boundsInRoot.top
        val aTop = compose.onNodeWithText("First song").fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "the page shows the dragged row first",
            bTop > 0f && bTop < aTop,
        )
        compose.onNodeWithText("Third song").assertExists()

        // A navigation round trip re-reads the persisted order: back to the
        // tags list, the tag's page again.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Scan NFC").assertExists()
        compose.onNodeWithText("Porch").performClick()
        compose.waitForIdle()
        waitForText("Second song")
        compose.waitForIdle()
        assertEquals(
            "the re-read order is the dragged order",
            listOf(secondId, firstId, thirdId),
            tagLinks(tagId).map { it.id },
        )
        val b2 = compose.onNodeWithText("Second song").fetchSemanticsNode().boundsInRoot.top
        val a2 = compose.onNodeWithText("First song").fetchSemanticsNode().boundsInRoot.top
        assertTrue("the re-opened page keeps the dragged order", b2 > 0f && b2 < a2)
        compose.onNodeWithText("Third song").assertExists()
    }

    @Test
    fun aDragThatEndsWhereItStartedPersistsNothing() {
        val tagId = dbAndGet { app().repository.addTag(TAG_UID, "Porch")?.id ?: 0L }
        val firstId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "First song", "content://first").id }
        val secondId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Second song", "content://second").id }
        dbAndGet { app().repository.updateTagEntries(tagId) { listOf(firstId, secondId) }; true }

        val activity = openTagPage("Porch")
        val viewModel = mainViewModel(activity)
        idleUntil({ viewModel.effectiveSelectedTagId.value == tagId })
        waitForText("Second song")
        compose.waitForIdle()

        val firstTop = compose.onNodeWithText("First song").fetchSemanticsNode().boundsInRoot.top
        val secondTop = compose.onNodeWithText("Second song").fetchSemanticsNode().boundsInRoot.top
        assertTrue("the rows must be laid out", firstTop > 0f && secondTop > firstTop)
        val pitch = (secondTop - firstTop).toLong()

        // A quarter-pitch nudge crosses no row boundary, so the drag ends
        // where it started.
        compose.onNodeWithContentDescription("Reorder First song").performTouchInput {
            down(center)
            moveBy(Offset(0f, (pitch / 4).toFloat()))
            up()
        }
        compose.waitForIdle()

        // Nothing is persisted and the list keeps its order.
        assertEquals(
            "a drag that ends where it started writes nothing",
            listOf(firstId, secondId),
            tagLinks(tagId).map { it.id },
        )
        val aTop = compose.onNodeWithText("First song").fetchSemanticsNode().boundsInRoot.top
        val bTop = compose.onNodeWithText("Second song").fetchSemanticsNode().boundsInRoot.top
        assertTrue("the list keeps its order", aTop > 0f && aTop < bTop)
    }

    @Test
    fun aHeldHandleDoesNotScrollThePage() {
        val tagId = dbAndGet { app().repository.addTag(TAG_UID, "Porch")?.id ?: 0L }
        val firstId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "First song", "content://first").id }
        val secondId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Second song", "content://second").id }
        val thirdId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Third song", "content://third").id }
        dbAndGet { app().repository.updateTagEntries(tagId) { listOf(firstId, secondId, thirdId) }; true }

        val activity = openTagPage("Porch")
        idleUntil({ mainViewModel(activity).effectiveSelectedTagId.value == tagId })
        waitForText("Third song")
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Reorder First song").performScrollTo()
        compose.waitForIdle()
        val detailsTopBefore =
            compose.onNodeWithText("Tag details").fetchSemanticsNode().boundsInRoot.top
        val firstTop = compose.onNodeWithText("First song").fetchSemanticsNode().boundsInRoot.top
        val secondTop = compose.onNodeWithText("Second song").fetchSemanticsNode().boundsInRoot.top
        assertTrue("the rows must be laid out", firstTop > 0f && secondTop > firstTop)
        val pitch = (secondTop - firstTop).toLong()

        // A full-pitch move: a distance the page would travel if the
        // handle did not consume it.
        compose.onNodeWithContentDescription("Reorder First song").performTouchInput {
            down(center)
            moveBy(Offset(0f, pitch.toFloat()))
            up()
        }
        compose.waitForIdle()

        // The move was a real drag: it persisted the swap.
        idleUntil({ tagLinks(tagId).map { it.id } == listOf(secondId, firstId, thirdId) })

        // ... and the page never scrolled while the handle was held.
        compose.waitForIdle()
        val detailsTopAfter =
            compose.onNodeWithText("Tag details").fetchSemanticsNode().boundsInRoot.top
        assertEquals(
            "the page does not scroll while the handle is held",
            detailsTopBefore,
            detailsTopAfter,
            0.5f,
        )
    }

    @Test
    fun aSubPitchMultiMoveDragSettlesWhereThePointerSays() {
        val tagId = dbAndGet { app().repository.addTag(TAG_UID, "Porch")?.id ?: 0L }
        val firstId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "First song", "content://first").id }
        val secondId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Second song", "content://second").id }
        val thirdId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Third song", "content://third").id }
        dbAndGet { app().repository.updateTagEntries(tagId) { listOf(firstId, secondId, thirdId) }; true }

        val activity = openTagPage("Porch")
        idleUntil({ mainViewModel(activity).effectiveSelectedTagId.value == tagId })
        waitForText("Third song")
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Reorder First song").performScrollTo()
        compose.waitForIdle()
        val firstTop = compose.onNodeWithText("First song").fetchSemanticsNode().boundsInRoot.top
        val secondTop = compose.onNodeWithText("Second song").fetchSemanticsNode().boundsInRoot.top
        assertTrue("the rows must be laid out", firstTop > 0f && secondTop > firstTop)
        val pitch = (secondTop - firstTop).toLong()

        // Sub-pitch moves that cross the first boundary in small steps
        // and release past half a pitch (0.70 of one): the pointer
        // position says one row down.
        compose.onNodeWithContentDescription("Reorder First song").performTouchInput {
            down(center)
            moveBy(Offset(0f, pitch * 0.45f))
            moveBy(Offset(0f, pitch * 0.10f))
            moveBy(Offset(0f, pitch * 0.10f))
            moveBy(Offset(0f, pitch * 0.05f))
            up()
        }
        compose.waitForIdle()

        // The drag settled where the pointer said: one row down.
        idleUntil({ tagLinks(tagId).map { it.id } == listOf(secondId, firstId, thirdId) })
        compose.waitForIdle()
        val aTop = compose.onNodeWithText("First song").fetchSemanticsNode().boundsInRoot.top
        val bTop = compose.onNodeWithText("Second song").fetchSemanticsNode().boundsInRoot.top
        assertTrue("the page shows the settled order", bTop > 0f && bTop < aTop)
    }

    @Test
    fun aMultiMoveDragReleasingOnePitchDownSettlesOneRowDown() {
        val tagId = dbAndGet { app().repository.addTag(TAG_UID, "Porch")?.id ?: 0L }
        val firstId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "First song", "content://first").id }
        val secondId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Second song", "content://second").id }
        val thirdId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Third song", "content://third").id }
        dbAndGet { app().repository.updateTagEntries(tagId) { listOf(firstId, secondId, thirdId) }; true }

        val activity = openTagPage("Porch")
        idleUntil({ mainViewModel(activity).effectiveSelectedTagId.value == tagId })
        waitForText("Third song")
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Reorder First song").performScrollTo()
        compose.waitForIdle()
        val firstTop = compose.onNodeWithText("First song").fetchSemanticsNode().boundsInRoot.top
        val secondTop = compose.onNodeWithText("Second song").fetchSemanticsNode().boundsInRoot.top
        assertTrue("the rows must be laid out", firstTop > 0f && secondTop > firstTop)
        val pitch = (secondTop - firstTop).toLong()

        // A multi-move drag that crosses the swap boundary mid-drag and
        // releases exactly one pitch down: the pointer position says one
        // row down even though the row re-laid itself there mid-drag.
        compose.onNodeWithContentDescription("Reorder First song").performTouchInput {
            down(center)
            moveBy(Offset(0f, pitch * 0.6f))
            moveBy(Offset(0f, pitch * 0.4f))
            up()
        }
        compose.waitForIdle()

        // The drag settles where the pointer released: one row down.
        idleUntil({ tagLinks(tagId).map { it.id } == listOf(secondId, firstId, thirdId) })
        compose.waitForIdle()
        val aTop = compose.onNodeWithText("First song").fetchSemanticsNode().boundsInRoot.top
        val bTop = compose.onNodeWithText("Second song").fetchSemanticsNode().boundsInRoot.top
        assertTrue("the page shows the settled order", bTop > 0f && bTop < aTop)
    }

    @Test
    fun aSecondDragOnTheSameHandleReordersAgain() {
        val tagId = dbAndGet { app().repository.addTag(TAG_UID, "Porch")?.id ?: 0L }
        val firstId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "First song", "content://first").id }
        val secondId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Second song", "content://second").id }
        val thirdId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Third song", "content://third").id }
        dbAndGet { app().repository.updateTagEntries(tagId) { listOf(firstId, secondId, thirdId) }; true }

        val activity = openTagPage("Porch")
        val viewModel = mainViewModel(activity)
        idleUntil({ viewModel.effectiveSelectedTagId.value == tagId })
        waitForText("Third song")
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Reorder First song").performScrollTo()
        compose.waitForIdle()
        val firstTop = compose.onNodeWithText("First song").fetchSemanticsNode().boundsInRoot.top
        val secondTop = compose.onNodeWithText("Second song").fetchSemanticsNode().boundsInRoot.top
        assertTrue("the rows must be laid out", firstTop > 0f && secondTop > firstTop)
        val pitch = (secondTop - firstTop).toLong()

        // First drag: the leading song down one pitch - the gesture's
        // handle node is the row that keeps the top position.
        compose.onNodeWithContentDescription("Reorder First song").performTouchInput {
            down(center)
            moveBy(Offset(0f, pitch.toFloat()))
            up()
        }
        idleUntil({ tagLinks(tagId).map { it.id } == listOf(secondId, firstId, thirdId) })
        compose.waitForIdle()

        // Second drag on the SAME node: the song now leading (the second
        // song, on the first song's old handle node) goes down one pitch.
        compose.onNodeWithContentDescription("Reorder Second song").performTouchInput {
            down(center)
            moveBy(Offset(0f, pitch.toFloat()))
            up()
        }
        idleUntil({ tagLinks(tagId).map { it.id } == listOf(firstId, secondId, thirdId) })
        compose.waitForIdle()

        // The page shows the order the second drag left: the first song
        // leads again.
        val firstRowTop = compose.onNodeWithText("First song").fetchSemanticsNode().boundsInRoot.top
        val secondRowTop = compose.onNodeWithText("Second song").fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "the page shows the order the second drag left",
            firstRowTop > 0f && firstRowTop < secondRowTop,
        )
    }

    // ---------- the reorder write's set-equality guard: a list change in flight keeps the current order ----------

    @Test
    fun aStaleReorderIsRejectedWhenTheListChangesInFlight() {
        val tagId = dbAndGet { app().repository.addTag(TAG_UID, "Porch")?.id ?: 0L }
        val tag = checkNotNull(dbAndGet { app().repository.tagByUid(TAG_UID) })
        val first = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "First song", "content://first") }
        val second = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Second song", "content://second") }
        val third = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Third song", "content://third") }
        dbAndGet { app().repository.updateTagEntries(tagId) { listOf(first.id, second.id) }; true }

        val activity = openTagPage("Porch")
        val viewModel = mainViewModel(activity)
        val events = mutableListOf<String>()
        viewModel.viewModelScope.launch { viewModel.uiEvents.collect { e -> events += e } }
        idleUntil({ viewModel.effectiveSelectedTagId.value == tagId })

        // An accepted reorder writes the order and fires the save event -
        // the baseline the rejection below is measured against.
        viewModel.reorderTagSongs(tag, listOf(second, first))
        idleUntil({ tagLinks(tagId).map { it.id } == listOf(second.id, first.id) })
        idleUntil({ events.contains("Order saved.") })

        // A song is added while a second drag is still in flight: the
        // current set is now {first, second, third}, while the in-flight
        // order can only be a permutation of the old {first, second}.
        viewModel.linkEntryToTag(tag, third)
        idleUntil({ tagLinks(tagId).map { it.id } == listOf(second.id, first.id, third.id) })

        // The stale in-flight order is rejected: the current order is
        // kept and the save event fires only for the accepted write.
        viewModel.reorderTagSongs(tag, listOf(first, second))
        pumpMainLooperSettled()
        assertEquals(
            "the stale order is not written",
            listOf(second.id, first.id, third.id),
            tagLinks(tagId).map { it.id },
        )
        assertEquals(
            "a rejected write fires no save event",
            1,
            events.count { it == "Order saved." },
        )

        // An unchanged order (a drag that ends where it started) writes
        // nothing and fires nothing.
        viewModel.reorderTagSongs(tag, listOf(second, first, third))
        pumpMainLooperSettled()
        assertEquals(
            "an unchanged order fires no save event",
            1,
            events.count { it == "Order saved." },
        )
        assertEquals(
            "the order is unchanged",
            listOf(second.id, first.id, third.id),
            tagLinks(tagId).map { it.id },
        )
    }

    /**
     * Pumps the main looper long enough for a short view-model launch (a
     * Room read plus a transaction) to have settled, so a "nothing fired"
     * assertion is not racing the write.
     */
    private fun pumpMainLooperSettled(rounds: Int = 10, pauseMs: Long = 50) {
        repeat(rounds) {
            ShadowLooper.idleMainLooper()
            compose.waitForIdle()
            Thread.sleep(pauseMs)
        }
    }

    companion object {
        const val TAG_UID = "04:CA:FE:00:00"
        const val BUNNY_UID = "04:CA:FE:00:01"
        const val OWL_UID = "04:CA:FE:00:02"
    }
}
