package com.lumi.player.ui

import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * The tag detail page's add-song picker laid out as the content folder
 * pages' TREE: the individual songs (the non-folder content rows) at the
 * top level, and each added folder a top-level folder row carrying the
 * folder's own imported structure (its subfolders and the songs nested
 * under them), with the tree's left-side connector guide lines and the
 * folder rows toggling their nested rows' display (show/hide). The multi
 * select + bottom Save behavior is carried over unchanged: a song's
 * checkbox toggles its check, a song the tag already plays is flagged and
 * non-addable, and the Save button commits the checked selection in the
 * tree's render order (folders first, a folder's nested rows right after
 * its own row, the level's songs after - collapsing is display-only and
 * never changes what Save commits).
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TagAddSongTreeUiTest {

    private companion object {
        const val TAG_UID = "AABBCCDDEEFF0011"
        const val TREE_URI = "content://com.android.providers.documents/tree/primary%3AMusic"

        /** A tree-scoped content payload under the picked root (the docId
         *  percent-encoded into its single path segment, the stored
         *  payload's shape). */
        fun payload(docId: String): String =
            "content://com.android.providers.documents/tree/primary%3AMusic/document/" + Uri.encode(docId, null)
    }

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

    private fun idleUntil(cond: () -> Boolean, timeoutMs: Long = 15_000, message: String = "the condition") {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            compose.waitForIdle()
            Thread.sleep(10)
        }
        check(cond()) { "timed out waiting for $message" }
    }

    private fun waitForText(text: String, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: AssertionError? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                compose.onNodeWithText(text).assertExists()
                return
            } catch (e: AssertionError) {
                last = e
                ShadowLooper.idleMainLooper()
                compose.waitForIdle()
                Thread.sleep(10)
            }
        }
        throw last ?: AssertionError("timed out waiting for text '$text'")
    }

    /**
     * Stores the content the tree tests drive against: two individual
     * songs, and one added folder ("Music") carrying a root song and a
     * subfolder ("Album") with a song nested under it.
     */
    private fun storeTreeContent(): Map<String, Long> {
        return dbAndGet {
            val repository = app().repository
            val alpha = repository.addEntry(EntryType.AUDIO, "Alpha", "content://alpha")
            val beta = repository.addEntry(EntryType.AUDIO, "Beta", "content://beta")
            val folder = repository.rememberFolder(TREE_URI, "Music")
            val root = repository.addEntry(
                EntryType.AUDIO, "Root song",
                payload("primary:Music/root.mp3"), folderId = folder.id,
            )
            val album = repository.addEntry(
                EntryType.AUDIO, "Album song",
                payload("primary:Music/Album/album.mp3"), folderId = folder.id,
            )
            mapOf("alpha" to alpha.id, "beta" to beta.id, "root" to root.id, "album" to album.id)
        }
    }

    /** Registers the tag and opens its details page. */
    private fun openTagPage(): ActivityController<MainActivity> {
        dbAndGet { app().repository.addTag(TAG_UID, "Porch") }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("NFC tags").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Porch").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Tag details").assertIsDisplayed()
        return controller
    }

    /** Opens the add-song picker from the tag page. */
    /**
     * Scrolls the [label] row into the dialog's visible region and clicks
     * it. The dialog's list is scrollable, and on the default 470 dp
     * Robolectric screen its lower rows sit clipped below the viewport
     * (their bounds report zero and a click there is a silent no-op),
     * so a row must be scrolled into view before it can be clicked.
     */
    private fun clickRow(label: String) {
        compose.onNodeWithText(label).performScrollTo()
        compose.waitForIdle()
        // The merged tree can lag a pending recomposition by a frame, and
        // a clipped row reports zero bounds until its layout settles:
        // wait for the row to be laid out in view before clicking it.
        idleUntil(
            {
                try {
                    compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.height > 0f
                } catch (e: AssertionError) {
                    false
                }
            },
            message = "the '$label' row to be in view",
        )
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
    }

    /** The activity's view model (the same seam the other settings tests use). */
    private fun mainViewModel(activity: MainActivity): LumiViewModel {
        val field = MainActivity::class.java.getDeclaredField("viewModel")
        field.isAccessible = true
        return field.get(activity) as LumiViewModel
    }

    private fun openPicker() {
        compose.onNodeWithContentDescription("Add song").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Add song").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Add a song").assertIsDisplayed()
    }

    /** The [label] row's checkbox node in the given check state. */
    private fun checkboxIn(label: String, state: ToggleableState): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "add-song-check-$label")
            .and(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state))

    /** The tag's current song list (link order), read off the database. */
    private fun tagLinks(): List<EntryEntity> =
        dbAndGet { app().repository.entriesForTag(dbAndGet { app().repository.tagByUid(TAG_UID)!!.id }) }

    /** Whether the dialog's Save button carries the disabled semantic. */
    private fun saveButtonDisabled(): Boolean =
        compose.onNodeWithText("Save").fetchSemanticsNode()
            .config.contains(SemanticsProperties.Disabled)

    @Test
    fun thePickerShowsTheContentAsTheFolderPagesTree() {
        storeTreeContent()
        openTagPage()
        openPicker()

        // The top level: the individual songs (the non-folder rows) and
        // the added folder's row.
        waitForText("Alpha")
        waitForText("Beta")
        waitForText("Music")
        // The folder row carries the folder's own imported structure:
        // its subfolder row and the songs nested under it (plus the song
        // sitting directly at the folder's root).
        waitForText("Album")
        waitForText("Root song")
        waitForText("Album song")

        // The tree's show/hide affordance: the folder rows carry a
        // collapse chevron (shown while expanded).
        compose.onNodeWithContentDescription("Collapse Music").assertExists()
        compose.onNodeWithContentDescription("Collapse Album").assertExists()

        // The tree's indent: each level's rows sit one rail step (16 dp,
        // 16 px at the test density) further right than the level above.
        val rootLeft = compose.onNodeWithText("Root song").fetchSemanticsNode().boundsInRoot.left
        val albumLeft = compose.onNodeWithText("Album song").fetchSemanticsNode().boundsInRoot.left
        assertEquals(
            "a nested level indents one rail step from its parent level",
            rootLeft + 16f,
            albumLeft,
            1f,
        )
    }

    @Test
    fun aFolderRowTogglesItsNestedRows_showAndHide() {
        storeTreeContent()
        openTagPage()
        openPicker()
        waitForText("Music")

        // The folder's rows are shown by default (its root song and the
        // subfolder's nested row).
        compose.onNodeWithText("Root song").assertExists()
        compose.onNodeWithText("Album song").assertExists()

        // Tapping the folder row hides its nested rows (the chevron
        // points right) - the top level's own rows stay.
        compose.onNodeWithText("Music").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Root song").assertDoesNotExist()
        compose.onNodeWithText("Album song").assertDoesNotExist()
        compose.onNodeWithText("Alpha").assertExists()
        compose.onNodeWithContentDescription("Expand Music").assertExists()

        // Tapping again restores the nested rows.
        compose.onNodeWithText("Music").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Root song").assertExists()
        compose.onNodeWithText("Album song").assertExists()
        compose.onNodeWithContentDescription("Collapse Music").assertExists()
    }

    @Test
    fun equalLabelTopLevelFoldersCollapseIndependently() {
        // Two added folders with the SAME label (a "Music" on the
        // internal volume and one on a USB drive): the top-level rows
        // are the folders' labels, so they must collapse independently -
        // each row's show/hide is keyed by the added folder's id, not
        // the shared label.
        val usbTree = "content://com.android.providers.documents/tree/usb%3AMusic"
        dbAndGet {
            val repository = app().repository
            val internal = repository.rememberFolder(TREE_URI, "Music")
            repository.addEntry(
                EntryType.AUDIO, "Internal song",
                payload("primary:Music/internal.mp3"),
                folderId = internal.id,
            )
            val external = repository.rememberFolder(usbTree, "Music")
            repository.addEntry(
                EntryType.AUDIO, "External song",
                "content://com.android.providers.documents/tree/usb%3AMusic/document/"
                    + Uri.encode("usb:Music/external.mp3", null),
                folderId = external.id,
            )
        }
        openTagPage()
        openPicker()
        waitForText("Internal song")
        waitForText("External song")
        assertEquals(
            "the two equal-label folders show two rows",
            2,
            compose.onAllNodesWithText("Music").fetchSemanticsNodes().size,
        )

        // Collapsing one row hides EXACTLY that folder's rows: the other
        // equal-label folder's rows stay.
        compose.onAllNodesWithText("Music")[0].performClick()
        compose.waitForIdle()
        assertEquals(
            "the first collapse hides only the first folder's song",
            1,
            visibleSongs().size,
        )

        // Collapsing the other row hides its song too.
        compose.onAllNodesWithText("Music")[1].performClick()
        compose.waitForIdle()
        assertEquals("the second collapse hides the last song", 0, visibleSongs().size)

        // Expanding the first brings back its song alone: the keys still
        // name one folder each.
        compose.onAllNodesWithText("Music")[0].performClick()
        compose.waitForIdle()
        assertEquals(
            "the first folder's song returns, the second's stays hidden",
            1,
            visibleSongs().size,
        )
    }

    /** The songs this test's two equal-label folders carry, displayed now. */
    private fun visibleSongs(): List<String> =
        listOf("Internal song", "External song").filter { label ->
            compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        }

    @Test
    fun sameNamedSubfoldersUnderEqualLabelFoldersCollapseIndependently() {
        // Two added folders with the SAME label (a "Music" on the
        // internal volume and on a USB drive), each carrying a same-named
        // subfolder ("Albums") with a song nested under it: the
        // subfolder rows must collapse independently too - their keys
        // lead with the added folder's id, not the shared label.
        val usbTree = "content://com.android.providers.documents/tree/usb%3AMusic"
        dbAndGet {
            val repository = app().repository
            val internal = repository.rememberFolder(TREE_URI, "Music")
            repository.addEntry(
                EntryType.AUDIO, "Internal album song",
                payload("primary:Music/Albums/internal.mp3"),
                folderId = internal.id,
            )
            val external = repository.rememberFolder(usbTree, "Music")
            repository.addEntry(
                EntryType.AUDIO, "External album song",
                "content://com.android.providers.documents/tree/usb%3AMusic/document/"
                    + Uri.encode("usb:Music/Albums/external.mp3", null),
                folderId = external.id,
            )
        }
        openTagPage()
        openPicker()
        waitForText("Internal album song")
        waitForText("External album song")
        assertEquals(
            "the two equal-label folders show two rows",
            2,
            compose.onAllNodesWithText("Music").fetchSemanticsNodes().size,
        )
        assertEquals(
            "the two equal-named subfolders show two rows",
            2,
            compose.onAllNodesWithText("Albums").fetchSemanticsNodes().size,
        )

        // Collapsing the FIRST "Albums" row hides exactly that
        // subfolder's song: the other equal-named subfolder's song
        // stays.
        clickAllRow("Albums", 0)
        assertEquals(
            "the first subfolder collapse hides only its own song",
            1,
            albumSongs().size,
        )
        // Collapsing the OTHER equal-named subfolder hides its song too.
        clickAllRow("Albums", 1)
        assertEquals("the second subfolder collapse hides the last song", 0, albumSongs().size)
        // Expanding the first brings back its song alone: the keys still
        // name one subfolder each.
        clickAllRow("Albums", 0)
        assertEquals(
            "the first subfolder's song returns, the second's stays hidden",
            1,
            albumSongs().size,
        )
    }

    /** The songs this test's two same-named subfolders carry, displayed now. */
    private fun albumSongs(): List<String> =
        listOf("Internal album song", "External album song").filter { label ->
            compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        }

    /**
     * Clicks the [index]th row carrying [label] (several rows can share
     * a label): the row must be scrolled into the dialog's visible
     * region first, the same way [clickRow] does it for a single row.
     */
    private fun clickAllRow(label: String, index: Int) {
        compose.onAllNodesWithText(label)[index].performScrollTo()
        compose.waitForIdle()
        idleUntil(
            {
                try {
                    compose.onAllNodesWithText(label)[index]
                        .fetchSemanticsNode().boundsInRoot.height > 0f
                } catch (e: AssertionError) {
                    false
                }
            },
            message = "the '$label' row $index to be in view",
        )
        compose.onAllNodesWithText(label)[index].performClick()
        compose.waitForIdle()
    }

    @Test
    fun checkingSongsAndSavingLinksThemInTheTreesRenderOrder() {
        val ids = storeTreeContent()
        openTagPage()
        openPicker()
        waitForText("Music")

        // Check the nested song FIRST, then the top-level song: the save
        // must commit them in the TREE'S render order (the folder's
        // nested rows before the top level's own songs), not in the
        // order they were checked.
        clickRow("Album song")
        clickRow("Alpha")

        // The dialog's Save is its fixed bottom button (no scrollable
        // ancestor - a scroll there throws), and it sits in view: click
        // it directly.
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        // The save closed the dialog.
        compose.onNodeWithText("Add a song").assertDoesNotExist()
        // The commit runs on the main looper through the view model (one
        // awaited write per song), so pump until both links have settled
        // in the database.
        idleUntil(
            { tagLinks().size == 2 },
            message = "both checked songs to be linked",
        )
        val links = tagLinks()
        assertEquals(
            "the checked songs land in the tree's render order (the folder's nested row first)",
            listOf(ids["album"]!!, ids["alpha"]!!),
            links.map { it.id },
        )
    }

    @Test
    fun collapsingNeverChangesWhatSaveCommits() {
        storeTreeContent()
        openTagPage()
        openPicker()
        waitForText("Music")

        // Check the nested song, collapse the folder (the row hides),
        // check the top-level song, and save: the hidden-but-checked row
        // is still committed - collapse is display-only, and the commit
        // order is the tree's render order (the folder's nested rows
        // before the top level's own songs).
        clickRow("Root song")
        clickRow("Music")
        compose.onNodeWithText("Root song").assertDoesNotExist()
        clickRow("Beta")
        // The dialog's Save is its fixed bottom button (no scrollable
        // ancestor - a scroll there throws), and it sits in view: click
        // it directly.
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Add a song").assertDoesNotExist()
        // The commit runs on the main looper through the view model (one
        // awaited write per song), so pump until both links have settled
        // in the database.
        idleUntil(
            { tagLinks().size == 2 },
            message = "both checked songs to be linked",
        )
        val labels = tagLinks().map { it.label }
        assertEquals(
            "the collapsed row's check survives the collapse into the save, in the tree's render order",
            listOf("Root song", "Beta"),
            labels,
        )
    }

    @Test
    fun aSongTheTagAlreadyPlaysIsFlagged_andNonAddable_inTheTree() {
        val ids = storeTreeContent()
        val controller = openTagPage()
        val tagId = dbAndGet { app().repository.tagByUid(TAG_UID)!!.id }
        dbAndGet { app().repository.updateTagEntries(tagId) { listOf(ids["alpha"]!!) } }
        // The page's own song list (the flag's source) must be selected
        // before the picker is opened.
        idleUntil({ mainViewModel(controller.get()).effectiveSelectedTagId.value == tagId })
        openPicker()
        waitForText("Music")

        // The flagged row carries its "Already on this tag" marking, and
        // it carries no checkbox (it is non-addable): the check can never
        // engage, so the song can never be linked a second time.
        compose.onNodeWithText("Already on this tag").assertExists()
        compose.onNode(checkboxIn("Alpha", ToggleableState.Off)).assertDoesNotExist()
        compose.onNode(checkboxIn("Alpha", ToggleableState.On)).assertDoesNotExist()

        // Checking the other song and saving links exactly that song -
        // the flagged one is not re-linked (the tag's list keeps its one
        // "Alpha" link and gains "Beta").
        clickRow("Beta")
        // The dialog's Save is its fixed bottom button (no scrollable
        // ancestor - a scroll there throws), and it sits in view: click
        // it directly.
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Add a song").assertDoesNotExist()
        // The commit runs on the main looper through the view model, so
        // pump until the checked link has settled in the database.
        idleUntil(
            { tagLinks().map { it.label } == listOf("Alpha", "Beta") },
            message = "the checked song to be linked after the flagged one",
        )

        val links = tagLinks()
        assertEquals(
            "the flagged song is not re-linked; only the checked one is",
            listOf("Alpha", "Beta"),
            links.map { it.label },
        )
        assertEquals(1, links.count { it.label == "Alpha" })

        // Re-opening the picker, BOTH songs are on the tag now, so both
        // rows are flagged (one flag node per flagged row).
        compose.onNodeWithContentDescription("Add song").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Add song").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Already on this tag").assertCountEquals(2)
    }

    @Test
    fun theSaveButtonIsDisabledWhileNothingIsChecked_evenWithFlaggedRows() {
        val ids = storeTreeContent()
        val controller = openTagPage()
        val tagId = dbAndGet { app().repository.tagByUid(TAG_UID)!!.id }
        dbAndGet { app().repository.updateTagEntries(tagId) { listOf(ids["alpha"]!!) } }
        openPicker()
        waitForText("Music")

        // Nothing is checked (the only content row is flagged): the Save
        // button carries the disabled semantic - a flagged row is not a
        // selection.
        assertTrue("the Save is disabled while nothing is checked", saveButtonDisabled())
        // The dialog's Save is its fixed bottom button (no scrollable
        // ancestor - a scroll there throws), and it sits in view: click
        // it directly.
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertEquals("the disabled save links nothing", listOf("Alpha"), tagLinks().map { it.label })
        compose.onNodeWithText("Add a song").assertExists()
    }

    @Test
    fun aDuplicateLabelIsOfferedOnce_inTheTree() {
        // "Twin" is stored individually AND inside the folder (the
        // content-list order: the individual row first): the tree offers
        // it once, at its first copy's home (the top level).
        dbAndGet {
            val repository = app().repository
            repository.addEntry(EntryType.AUDIO, "Twin", "content://twin")
            val folder = repository.rememberFolder(TREE_URI, "Music")
            repository.addEntry(
                EntryType.AUDIO, "Twin",
                payload("primary:Music/twin.mp3"), folderId = folder.id,
            )
            repository.addEntry(EntryType.AUDIO, "Solo", "content://solo")
        }
        dbAndGet { app().repository.addTag(TAG_UID, "Porch") }
        openPickerAtFreshPage()

        compose.onAllNodesWithText("Twin").fetchSemanticsNodes()
            .let { assertEquals("the duplicate label is offered exactly once", 1, it.size) }
        compose.onAllNodesWithText("Solo").assertCountEquals(1)
    }

    /** Opens the tag page (a fresh activity, no tag songs yet) and the picker. */
    private fun openPickerAtFreshPage() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("NFC tags").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Porch").performClick()
        compose.waitForIdle()
        openPicker()
    }
}
