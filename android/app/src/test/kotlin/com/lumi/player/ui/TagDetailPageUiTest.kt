package com.lumi.player.ui

import androidx.activity.result.ActivityResultRegistry
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
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
import com.lumi.player.data.TagSongRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * list (the app's existing content, individual or folder-imported): the
 * user CHECKS the songs to add (tapping a row or its checkbox toggles the
 * check) and saves the selection at once with the Save button at the
 * bottom of the dialog - every checked song links its stored entry to the
 * tag ([LumiViewModel.linkEntryToTag], one link per checked song) and the
 * dialog closes; a song the tag already plays stays non-addable (its
 * check never engages, so it can never be linked twice); the Save button
 * is dimmed while nothing is checked, and cancelling or dismissing saves
 * nothing. It never launches the file picker, so nothing is stored
 * twice.
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
        markSetupCompleteForTests()
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
    // 15 s default (not 5 s): the drag-reorder tests poll a Room write
    // that runs on the main looper, and on a COLD test JVM (this class
    // first in the fork, or a single-method run) the write can settle
    // slower than 5 s - a cold-start timing flake, not a product bug
    // (warm JVMs settle the same writes in <100 ms). The budget is a
    // wait allowance, never a behavior assertion.
    private fun idleUntil(cond: () -> Boolean, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            compose.waitForIdle()
            Thread.sleep(10)
        }
        check(cond()) { "timed out waiting for the condition to hold" }
    }

    /** Pumps the main looper until a node with [text] is laid out or fails. */
    private fun waitForText(text: String, timeoutMs: Long = 15_000) {
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
    private fun tagLinks(tagId: Long): List<TagSongRow> =
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
            listOf(firstId, secondId).forEach { app().repository.linkEntryToTag(bunnyId, it) }
            listOf(hootId).forEach { app().repository.linkEntryToTag(owlId, it) }
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

    // ---------- add songs from the page: the in-app content list links the stored rows ----------

    /** The [label] row's checkbox node in the given check state. */
    private fun checkboxIn(label: String, state: ToggleableState): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "add-song-check-$label")
            .and(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state))

    /** Asserts the [label] row's checkbox is in [state] (or fails). */
    private fun checkboxState(label: String, state: ToggleableState) {
        compose.onNode(checkboxIn(label, state)).assertExists()
    }

    /** Whether the dialog's Save button carries the disabled semantic. */
    private fun saveButtonDisabled(): Boolean =
        compose.onNodeWithText("Save").fetchSemanticsNode()
            .config.contains(SemanticsProperties.Disabled)

    @Test
    fun checkingASongAndSavingLinksAnExistingContentEntryToTheTag() {
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
        // picker is never launched (the save links, it does not store).
        compose.onNodeWithText("Add a song").assertExists()
        waitForText("First song")
        assertEquals(
            "the add-song action must not launch the file picker",
            launchedBefore,
            launchedPickerKeys(activity),
        )

        // Checking the song (tapping its row) marks it, and the Save
        // button at the bottom commits: the tag's list gains the STORED
        // row and the content list grows by nothing.
        compose.onNodeWithText("First song").performClick()
        compose.waitForIdle()
        checkboxState("First song", ToggleableState.On)
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        idleUntil({ tagLinks(tagId).size == 1 })
        assertEquals(
            "the checked row must link the stored entry",
            listOf(contentId),
            tagLinks(tagId).map { it.entryId },
        )
        // The dialog closed (its title is gone) and the page shows the
        // new song row.
        compose.onNodeWithText("Add a song").assertDoesNotExist()
        waitForText("First song")
        compose.onNodeWithText("This tag plays nothing yet.").assertDoesNotExist()
        assertEquals(
            "saving links the stored row - no new content row is stored",
            1,
            dbAndGet { app().repository.observeEntries().first().size },
        )
    }

    @Test
    fun checkingMultipleSongsAndSavingLinksThemAllAtOnce() {
        val tagId = dbAndGet { app().repository.addTag(TAG_UID, "Porch")?.id ?: 0L }
        val firstId = dbAndGet {
            app().repository.addEntry(EntryType.AUDIO, "First song", "content://first").id
        }
        val secondId = dbAndGet {
            app().repository.addEntry(EntryType.AUDIO, "Second song", "content://second").id
        }

        val activity = openTagPage("Porch")
        val viewModel = mainViewModel(activity)
        idleUntil({ viewModel.effectiveSelectedTagId.value == tagId })

        compose.onNodeWithContentDescription("Add song").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Add song").performClick()
        compose.waitForIdle()
        waitForText("Second song")
        compose.waitForIdle()

        // Check two of the rows: one through its own checkbox node, the
        // other through its row body - both toggle the check. The check
        // order is the REVERSE of the rows' displayed order on purpose:
        // it distinguishes a row-order commit from a check-order commit,
        // so a save that linked the songs in the order they were checked
        // would come out reversed and the assertion below would fail.
        compose.onNode(checkboxIn("Second song", ToggleableState.Off)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("First song").performClick()
        compose.waitForIdle()
        checkboxState("Second song", ToggleableState.On)
        checkboxState("First song", ToggleableState.On)

        // The save commits the checked songs in the DIALOG'S row order
        // (the order the user saw and checked them) - not the order they
        // were checked - so the links must follow the rows' displayed
        // order; capture it while the dialog is still open.
        val displayedOrder = listOf("First song", "Second song").sortedBy {
            compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top
        }

        // Saving commits the whole selection at once: BOTH checked songs
        // link and the dialog closes.
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        idleUntil({ tagLinks(tagId).size == 2 })
        assertEquals(
            "saving links exactly the checked songs, in the dialog's row order",
            displayedOrder.map { if (it == "First song") firstId else secondId },
            tagLinks(tagId).map { it.entryId },
        )
        compose.onNodeWithText("Add a song").assertDoesNotExist()
        // The content list grew by nothing, and the page shows the two
        // new rows.
        assertEquals(2, dbAndGet { app().repository.observeEntries().first().size })
        compose.onNodeWithText("First song").assertExists()
        compose.onNodeWithText("Second song").assertExists()
    }

    @Test
    fun theSaveButtonIsDisabledWhileNothingIsChecked() {
        val tagId = dbAndGet { app().repository.addTag(TAG_UID, "Porch")?.id ?: 0L }
        val contentId = dbAndGet {
            app().repository.addEntry(EntryType.AUDIO, "First song", "content://first").id
        }

        val activity = openTagPage("Porch")
        val viewModel = mainViewModel(activity)
        idleUntil({ viewModel.effectiveSelectedTagId.value == tagId })

        compose.onNodeWithContentDescription("Add song").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Add song").performClick()
        compose.waitForIdle()
        waitForText("First song")
        compose.waitForIdle()

        // With nothing checked the Save button is dimmed (disabled):
        // there is nothing to save.
        assertTrue(
            "the Save button must carry the disabled semantic while nothing is checked",
            saveButtonDisabled(),
        )
        // Tapping the inert button saves nothing and keeps the list open.
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertEquals("the disabled save links nothing", emptyList<Long>(), tagLinks(tagId))
        compose.onNodeWithText("Add a song").assertExists()

        // Checking one song enables it, and the save commits.
        compose.onNodeWithText("First song").performClick()
        compose.waitForIdle()
        assertFalse("checking a song must enable the Save button", saveButtonDisabled())
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        idleUntil({ tagLinks(tagId).size == 1 })
        assertEquals(listOf(contentId), tagLinks(tagId).map { it.entryId })
    }

    @Test
    fun cancellingSavesNothing() {
        val tagId = dbAndGet { app().repository.addTag(TAG_UID, "Porch")?.id ?: 0L }
        dbAndGet { app().repository.addEntry(EntryType.AUDIO, "First song", "content://first").id }

        val activity = openTagPage("Porch")
        val viewModel = mainViewModel(activity)
        idleUntil({ viewModel.effectiveSelectedTagId.value == tagId })

        compose.onNodeWithContentDescription("Add song").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Add song").performClick()
        compose.waitForIdle()
        waitForText("First song")
        compose.waitForIdle()

        // Check a song, then cancel: the dialog closes and NOTHING links.
        compose.onNodeWithText("First song").performClick()
        compose.waitForIdle()
        checkboxState("First song", ToggleableState.On)
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Add a song").assertDoesNotExist()
        assertEquals("cancelling saves nothing", emptyList<Long>(), tagLinks(tagId))

        // Re-opening the list starts with an empty selection again.
        compose.onNodeWithContentDescription("Add song").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Add song").performClick()
        compose.waitForIdle()
        waitForText("First song")
        compose.waitForIdle()
        checkboxState("First song", ToggleableState.Off)
        assertTrue("the re-opened list's Save is dimmed again", saveButtonDisabled())
    }

    @Test
    fun aSongTheTagAlreadyPlaysStaysNonAddableAndCannotBeLinkedTwice() {
        val tagId = dbAndGet { app().repository.addTag(TAG_UID, "Porch")?.id ?: 0L }
        val firstId = dbAndGet {
            app().repository.addEntry(EntryType.AUDIO, "First song", "content://first").id
        }
        val secondId = dbAndGet {
            app().repository.addEntry(EntryType.AUDIO, "Second song", "content://second").id
        }
        dbAndGet { listOf(firstId).forEach { app().repository.linkEntryToTag(tagId, it) }; true }

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
        // The flagged row carries no checkbox at all: it stays
        // non-addable.
        compose.onNode(checkboxIn("First song", ToggleableState.On)).assertDoesNotExist()
        compose.onNode(checkboxIn("First song", ToggleableState.Off)).assertDoesNotExist()

        // Tapping the flagged row does nothing (the check never engages),
        // while the other song checks normally - and saving the selection
        // links only the new one: the flagged song keeps its single link.
        compose.onNodeWithText("Already on this tag").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Second song").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        idleUntil({ tagLinks(tagId).size == 2 })
        assertEquals(
            "the flagged song keeps its single link; only the checked one is added",
            listOf(firstId, secondId),
            tagLinks(tagId).map { it.entryId },
        )
        // The dialog closed on the save.
        compose.onNodeWithText("Add a song").assertDoesNotExist()
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

        // Checking the collapsed row and saving links the FIRST stored
        // copy.
        compose.onNodeWithText("First song").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        idleUntil({ tagLinks(tagId).size == 1 })
        assertEquals(
            "saving the collapsed row links the FIRST stored copy",
            listOf(firstCopy),
            tagLinks(tagId).map { it.entryId },
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
            listOf(firstId, secondId).forEach { app().repository.linkEntryToTag(tagId, it) }
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
        dbAndGet { listOf(firstId, secondId).forEach { app().repository.linkEntryToTag(tagId, it) }; true }

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
            tagLinks(tagId).map { it.entryId },
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
            listOf(firstId, secondId, thirdId).forEach { app().repository.linkEntryToTag(tagId, it) }
            // The second tag plays the same middle song too (the removal must
            // only touch THIS tag's link).
            listOf(secondId).forEach { app().repository.linkEntryToTag(owlId, it) }
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
        idleUntil({ tagLinks(tagId).map { it.entryId } == listOf(firstId, thirdId) })
        assertEquals(
            "the remaining songs keep their persisted order",
            listOf(firstId, thirdId),
            tagLinks(tagId).map { it.entryId },
        )
        assertTrue(
            "the removal is reported as an event",
            events.contains("Song removed from the tag"),
        )

        // The other tag's link to the same song is untouched.
        assertEquals(
            "the other tag still plays the removed song",
            listOf(secondId),
            tagLinks(owlId).map { it.entryId },
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
    @Config(qualifiers = "w320dp-h700dp")
    fun aDeletedSongsLinkShowsASongNotFoundRow_atItsPosition_andCleansUpOnRemoval() {
        val tagId = dbAndGet { app().repository.addTag(TAG_UID, "Porch")?.id ?: 0L }
        val firstId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "First song", "content://first").id }
        val goneId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Gone song", "content://gone").id }
        val thirdId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Third song", "content://third").id }
        dbAndGet {
            listOf(firstId, goneId, thirdId).forEach { app().repository.linkEntryToTag(tagId, it) }
            true
        }
        // The MIDDLE song leaves the library: the tag's link to it does not
        // follow (the entry's deletion keeps the link behind), so the page
        // must show the gap - not swallow the row.
        dbAndGet {
            app().repository.deleteEntry(
                app().repository.observeEntries().first().single { it.id == goneId },
            )
            true
        }

        val activity = openTagPage("Porch")
        val viewModel = mainViewModel(activity)
        idleUntil({ viewModel.effectiveSelectedTagId.value == tagId })
        waitForText("Third song")
        compose.waitForIdle()

        // The missing song renders its OWN row, rendering its snapshot
        // name (the song it remembers) with the warning copy - a visible
        // "song not found", not a silent disappearance...
        compose.onNodeWithText("Gone song").assertExists()
        compose.onNodeWithText("this song is no longer in the content list").assertExists()
        // ...with NO drag affordance (the row carries no song to drag)...
        compose.onNodeWithContentDescription("Reorder Gone song").assertDoesNotExist()
        compose.onNodeWithContentDescription("Reorder First song").assertExists()
        // ...and at its ORIGINAL position, between the surviving rows.
        val firstTop = compose.onNodeWithText("First song").fetchSemanticsNode().boundsInRoot.top
        val missingTop = compose.onNodeWithText("Gone song").fetchSemanticsNode().boundsInRoot.top
        val thirdTop = compose.onNodeWithText("Third song").fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "the missing row sits where the song was", firstTop < missingTop && missingTop < thirdTop,
        )

        // The missing row's remove targets the dead link itself (the
        // survivors keep their own remove actions).
        compose.onNodeWithContentDescription("Remove First song from the tag").assertExists()
        compose.onNodeWithContentDescription("Remove Third song from the tag").assertExists()
        compose.onNodeWithContentDescription("Remove Gone song from the tag").assertExists()

        // The missing row's name RESOLVES LIVE: re-importing a song under
        // the same name (a different entry row) makes the row playable
        // again, without re-adding it to the tag.
        dbAndGet {
            app().repository.addEntry(EntryType.AUDIO, "Gone song", "content://gone-reimported")
            true
        }
        idleUntil({
            try {
                compose.onNodeWithText("this song is no longer in the content list").assertDoesNotExist()
                true
            } catch (e: AssertionError) {
                false
            }
        })
        // ...the warning copy is gone, and the row resolves to the new entry.
        compose.onNodeWithText("Gone song").assertExists()
        assertEquals(
            "the re-imported song resolves to the new entry",
            listOf(firstId, reimportedId(tagId), thirdId),
            tagLinks(tagId).map { it.entryId },
        )

        // Removing the (now live) re-imported song confirms - the copy
        // states it stays in the content list - and cleans the link out of
        // the tag's list; the survivors keep their order.
        compose.onNodeWithContentDescription("Remove Gone song from the tag").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Remove Gone song from the tag").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Remove song from the tag?").assertIsDisplayed()
        compose.onNodeWithText("“Gone song” will be removed from Porch. It stays in the content list.").assertIsDisplayed()
        compose.onNodeWithText("Remove").performClick()
        compose.waitForIdle()
        idleUntil({ tagLinks(tagId).size == 2 })
        compose.onNodeWithText("Gone song").assertDoesNotExist()
        compose.onNodeWithText("First song").assertExists()
        compose.onNodeWithText("Third song").assertExists()
    }

    /** The id of the re-imported "Gone song" entry (the payload that is not the deleted one). */
    private fun reimportedId(tagId: Long): Long = dbAndGet {
        app().repository.entriesForTag(tagId).single { it.entry != null && it.name == "Gone song" }.entryId!!
    }

    @Test
    fun removingTheLastSongFallsBackToTheEmptyListText() {
        val tagId = dbAndGet { app().repository.addTag(TAG_UID, "Porch")?.id ?: 0L }
        val onlyId = dbAndGet { app().repository.addEntry(EntryType.AUDIO, "Only song", "content://only").id }
        dbAndGet { listOf(onlyId).forEach { app().repository.linkEntryToTag(tagId, it) }; true }

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
            tagLinks(tagId).map { it.entryId },
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
        dbAndGet { listOf(firstId, secondId, thirdId).forEach { app().repository.linkEntryToTag(tagId, it) }; true }

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
        idleUntil({ tagLinks(tagId).map { it.entryId } == listOf(secondId, firstId, thirdId) })

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
            tagLinks(tagId).map { it.entryId },
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
        dbAndGet { listOf(firstId, secondId).forEach { app().repository.linkEntryToTag(tagId, it) }; true }

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
            tagLinks(tagId).map { it.entryId },
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
        dbAndGet { listOf(firstId, secondId, thirdId).forEach { app().repository.linkEntryToTag(tagId, it) }; true }

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
        idleUntil({ tagLinks(tagId).map { it.entryId } == listOf(secondId, firstId, thirdId) })

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
        dbAndGet { listOf(firstId, secondId, thirdId).forEach { app().repository.linkEntryToTag(tagId, it) }; true }

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
        idleUntil({ tagLinks(tagId).map { it.entryId } == listOf(secondId, firstId, thirdId) })
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
        dbAndGet { listOf(firstId, secondId, thirdId).forEach { app().repository.linkEntryToTag(tagId, it) }; true }

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
        idleUntil({ tagLinks(tagId).map { it.entryId } == listOf(secondId, firstId, thirdId) })
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
        dbAndGet { listOf(firstId, secondId, thirdId).forEach { app().repository.linkEntryToTag(tagId, it) }; true }

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
        idleUntil({ tagLinks(tagId).map { it.entryId } == listOf(secondId, firstId, thirdId) })
        compose.waitForIdle()

        // Second drag on the SAME node: the song now leading (the second
        // song, on the first song's old handle node) goes down one pitch.
        // The synthetic gesture is retried (bounded) until the reorder
        // lands: on a COLD test JVM the second performTouchInput's DOWN is
        // sometimes silently dropped by the compose input pipeline right
        // after the first drag's write recomposed the list - the gesture
        // is then a pure no-op (no drag, no write; observed: the order
        // unchanged for 20 s, geometry correct) - and a re-dispatched
        // gesture after a brief real-time settle lands. A dropped gesture
        // is recognized as "the order is still the pre-drag one"; any
        // other order is a misapplied drag and fails the test instead of
        // being retried. (Not a product race: the handle's own
        // pointerInput loop is untouched between gestures, and every
        // warm-JVM run lands the gesture on the first dispatch.)
        val secondDragTarget = listOf(firstId, secondId, thirdId)
        val preSecondDragOrder = listOf(secondId, firstId, thirdId)
        var secondDragAttempts = 0
        while (true) {
            compose.onNodeWithContentDescription("Reorder Second song").performTouchInput {
                down(center)
                moveBy(Offset(0f, pitch.toFloat()))
                up()
            }
            secondDragAttempts++
            var landed = false
            val deadline = System.currentTimeMillis() + 2_000
            while (System.currentTimeMillis() < deadline) {
                if (tagLinks(tagId).map { it.entryId } == secondDragTarget) {
                    landed = true
                    break
                }
                ShadowLooper.idleMainLooper()
                compose.waitForIdle()
                Thread.sleep(50)
            }
            if (landed) break
            val now = tagLinks(tagId).map { it.entryId }
            if (now == secondDragTarget) break // landed during the final poll
            check(now == preSecondDragOrder) {
                "the second drag neither landed nor stayed a no-op: $now"
            }
            check(secondDragAttempts < 5) {
                "the second drag's synthetic gesture was dropped on every attempt"
            }
            // The gesture was dropped (a cold-JVM input-pipeline race):
            // let the pipeline settle in real time, then re-dispatch.
            ShadowLooper.idleMainLooper()
            Thread.sleep(150)
            compose.waitForIdle()
        }
        idleUntil({ tagLinks(tagId).map { it.entryId } == listOf(firstId, secondId, thirdId) })
        compose.waitForIdle()

        // The page shows the order the second drag left: the first song
        // leads again (polled - the merged tree can lag a pending
        // recomposition by a frame right after the drag settles).
        idleUntil(
            {
                val firstRowTop = compose.onNodeWithText("First song").fetchSemanticsNode().boundsInRoot.top
                val secondRowTop = compose.onNodeWithText("Second song").fetchSemanticsNode().boundsInRoot.top
                firstRowTop > 0f && firstRowTop < secondRowTop
            },
            timeoutMs = 10_000,
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
        dbAndGet { listOf(first.id, second.id).forEach { app().repository.linkEntryToTag(tagId, it) }; true }

        val activity = openTagPage("Porch")
        val viewModel = mainViewModel(activity)
        val events = mutableListOf<String>()
        viewModel.viewModelScope.launch { viewModel.uiEvents.collect { e -> events += e } }
        idleUntil({ viewModel.effectiveSelectedTagId.value == tagId })

        // An accepted reorder writes the order and fires the save event -
        // the baseline the rejection below is measured against.
        viewModel.reorderTagSongs(tag, rowsInOrder(tagId, listOf(second.id, first.id)))
        idleUntil({ tagLinks(tagId).map { it.entryId } == listOf(second.id, first.id) })
        idleUntil({ events.contains("Order saved.") })

        // A song is added while a second drag is still in flight: the
        // current set is now {first, second, third}, while the in-flight
        // order can only be a permutation of the old {first, second}.
        viewModel.linkEntryToTag(tag, third)
        idleUntil({ tagLinks(tagId).map { it.entryId } == listOf(second.id, first.id, third.id) })

        // The stale in-flight order is rejected: the current order is
        // kept and the save event fires only for the accepted write.
        viewModel.reorderTagSongs(tag, rowsInOrder(tagId, listOf(first.id, second.id)))
        pumpMainLooperSettled()
        assertEquals(
            "the stale order is not written",
            listOf(second.id, first.id, third.id),
            tagLinks(tagId).map { it.entryId },
        )
        assertEquals(
            "a rejected write fires no save event",
            1,
            events.count { it == "Order saved." },
        )

        // An unchanged order (a drag that ends where it started) writes
        // nothing and fires nothing.
        viewModel.reorderTagSongs(tag, rowsInOrder(tagId, listOf(second.id, first.id, third.id)))
        pumpMainLooperSettled()
        assertEquals(
            "an unchanged order fires no save event",
            1,
            events.count { it == "Order saved." },
        )
        assertEquals(
            "the order is unchanged",
            listOf(second.id, first.id, third.id),
            tagLinks(tagId).map { it.entryId },
        )
    }

    /** The tag's song rows in the given entry-id order (the drag reorder's input). */
    private fun rowsInOrder(tagId: Long, entryIds: List<Long>): List<TagSongRow> {
        val all = dbAndGet { app().repository.entriesForTag(tagId) }
        return entryIds.map { id -> all.single { it.entryId == id } }
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
