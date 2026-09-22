package com.lumi.player.ui

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.data.FolderChild
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
import java.io.FileNotFoundException

/**
 * Behavioral coverage for the folder's detail page (opened by tapping a
 * folder's row in the content pane): the page shows the folder's details
 * (label + the picked tree uri), lists the songs imported from the folder
 * (the stored content rows the folder's walk identified - including
 * subfolder songs), offers the rescan action on the Songs section's title
 * row (a rescan's new songs refresh the list), and ends in a danger zone
 * whose delete removes the folder from the app - and only the folder and
 * its imported songs; the music files on the phone are untouched. The page
 * is a child of the content pane (its back lands there - the back gesture
 * itself is pinned by [SettingsBackNavUiTest]) and it falls back to the
 * content pane when the folder is removed while the page is open.
 *
 * The walk goes through the faked [LumiViewModel.folderChildren] seam (the
 * SAF picker and provider cannot run in Robolectric); the removal is driven
 * through the view model, the way the rescan and removal tests drive theirs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
    qualifiers = "w412dp-h900dp",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FolderDetailPageUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private fun app(): LumiApp = ApplicationProvider.getApplicationContext()

    private fun openContentPane(): LumiViewModel {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Content").performClick()
        compose.waitForIdle()
        val field = MainActivity::class.java.getDeclaredField("viewModel")
        field.isAccessible = true
        return field.get(activity) as LumiViewModel
    }

    /** Runs a suspend block on the app's database and returns its result. */
    private fun <T> dbAndGet(block: suspend () -> T): T {
        var result: T? = null
        Thread { result = runBlocking { block() } }.also { it.start() }.apply { join() }
        @Suppress("UNCHECK_CAST")
        return result as T
    }

    private fun idleUntil(cond: () -> Boolean, timeoutMs: Long = 60_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        check(cond()) { "timed out waiting for the operation to settle" }
    }

    /** Pumps the main looper until a node with [text] is laid out or fails. */
    private fun waitForText(text: String, timeoutMs: Long = 60_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            compose.waitForIdle()
            if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(20)
        }
        compose.onNodeWithText(text).assertExists()
    }

    private fun eventsOf(viewModel: LumiViewModel): MutableList<String> {
        val events = mutableListOf<String>()
        viewModel.viewModelScope.launch { viewModel.uiEvents.collect { e: String -> events += e } }
        return events
    }

    private fun file(name: String, uri: String) = FolderChild(name, isDirectory = false, childUri = uri)

    /** Replaces the folder-children seam with a two-level tree. */
    private fun swapTree(
        viewModel: LumiViewModel,
        root: List<FolderChild>,
        childrenOf: Map<String, List<FolderChild>> = emptyMap(),
    ) {
        viewModel.folderChildren = { folderUri ->
            when (val s = folderUri.toString()) {
                TREE_URI -> root
                else -> childrenOf[s] ?: emptyList()
            }
        }
    }

    /** Adds a folder through the view model until its add event settles. */
    private fun addFolder(
        viewModel: LumiViewModel,
        events: MutableList<String>,
        expected: String,
        treeUri: String = TREE_URI,
    ) {
        viewModel.addFolderEntry(Uri.parse(treeUri))
        idleUntil({ events.any { it == expected } })
    }

    /**
     * Taps the folder's row in the content pane (its title is the folder's
     * label - the tree uri string here, since no provider is registered)
     * and waits for the detail page to show.
     */
    private fun openFolderPage(treeUri: String = TREE_URI) {
        compose.onNodeWithText(treeUri).performScrollTo()
        compose.onNodeWithText(treeUri).performClick()
        compose.waitForIdle()
        waitForText("Folder details")
    }

    /** The left edge of the first row whose title is [text]. */
    private fun leftOf(text: String): Float =
        compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.left

    /** The top edge of the first row whose title is [text]. */
    private fun topOf(text: String): Float =
        compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun thePageShowsTheFolderDetailsAndItsImportedSongs() {
        val viewModel = openContentPane()
        swapTree(
            viewModel,
            listOf(
                file("song-a.mp3", "c://x/a"),
                FolderChild("Albums", isDirectory = true, childUri = "c://x/albums"),
            ),
            childrenOf = mapOf("c://x/albums" to listOf(file("song-b.flac", "c://x/albums/b"))),
        )
        addFolder(viewModel, eventsOf(viewModel), "Added 2 songs from the folder.")

        openFolderPage()
        // The page lists the imported songs - including the subfolder's song.
        waitForText("song-a.mp3")
        compose.onNodeWithText("song-b.flac").assertExists()
        // The page carries the rescan action and the danger zone; the
        // content pane's row-level copy is gone.
        compose.onNodeWithContentDescription("Rescan").assertExists()
        compose.onNodeWithText("Delete folder from the app").assertExists()
        compose.onNodeWithText("Tap to view its songs").assertDoesNotExist()
    }

    @Test
    fun thePageShowsTheImportedFolderStructureWithSubfoldersIndented() {
        val viewModel = openContentPane()
        // The tree's real SAF uri grammar: the picker's tree uri names the
        // picked folder's docId, and every file's payload is the tree-scoped
        // content uri whose docId encodes the full path below the root.
        viewModel.folderChildren = { folderUri ->
            when (val s = folderUri.toString()) {
                WALKIN_TREE -> listOf(
                    file("mango.mp3", treeDoc("$WALKIN_ROOT/mango.mp3")),
                    FolderChild("Albums", isDirectory = true, childUri = treeDoc("$WALKIN_ROOT/Albums")),
                    file("alpha.mp3", treeDoc("$WALKIN_ROOT/alpha.mp3")),
                )
                treeDoc("$WALKIN_ROOT/Albums") -> listOf(
                    file("deep.flac", treeDoc("$WALKIN_ROOT/Albums/deep.flac")),
                )
                else -> emptyList()
            }
        }
        addFolder(viewModel, eventsOf(viewModel), "Added 3 songs from the folder.", WALKIN_TREE)

        openFolderPage(WALKIN_TREE)
        // The subfolder is a row of its own (the imported structure, not a
        // flat list) and its song nests under it: the nested song's row is
        // indented exactly one step (16 dp, the tree's per-depth indent)
        // beyond the root songs, and the subfolder row sits above it.
        waitForText("Albums")
        compose.onNodeWithText("deep.flac").assertExists()
        assertEquals(
            "the subfolder's song is indented one step (16 dp) beyond the " +
                "root songs (got ${leftOf("deep.flac")} vs root ${leftOf("mango.mp3")})",
            leftOf("mango.mp3") + 16f,
            leftOf("deep.flac"),
        )
        assertTrue(
            "the subfolder row comes above its own nested song",
            topOf("Albums") < topOf("deep.flac"),
        )
        // The root songs sit at the top level (flush with each other).
        assertEquals(
            "the root songs are flush",
            leftOf("mango.mp3"),
            leftOf("alpha.mp3"),
        )
        // The subfolder row is display-only: tapping it opens no profile.
        compose.onNodeWithText("Albums").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Song details").assertDoesNotExist()
        compose.onNodeWithText("Folder details").assertExists()
        // The root songs stay rows that open the song's profile.
        compose.onNodeWithText("mango.mp3").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Song details").assertExists()
    }

    @Test
    fun thePageSortsItsTreeLevelsWithTheSharedDirectionControl() {
        val viewModel = openContentPane()
        viewModel.folderChildren = { folderUri ->
            when (val s = folderUri.toString()) {
                WALKIN_TREE -> listOf(
                    file("mango.mp3", treeDoc("$WALKIN_ROOT/mango.mp3")),
                    FolderChild("Albums", isDirectory = true, childUri = treeDoc("$WALKIN_ROOT/Albums")),
                    file("alpha.mp3", treeDoc("$WALKIN_ROOT/alpha.mp3")),
                )
                treeDoc("$WALKIN_ROOT/Albums") -> listOf(
                    file("deep.flac", treeDoc("$WALKIN_ROOT/Albums/deep.flac")),
                )
                else -> emptyList()
            }
        }
        addFolder(viewModel, eventsOf(viewModel), "Added 3 songs from the folder.", WALKIN_TREE)

        openFolderPage(WALKIN_TREE)
        waitForText("mango.mp3")
        // The content pane's own sort control, with its per-level caption.
        compose.onNodeWithText("Sort by title").assertExists()
        compose.onNodeWithText("Applies to the folders and the songs at each level below.").assertExists()
        // The list's default order (descending): the subfolder leads the
        // level, then the songs Z->A - mango above alpha.
        assertTrue(
            "the subfolder row comes before the songs at its level",
            topOf("Albums") < topOf("mango.mp3"),
        )
        assertTrue(
            "descending puts mango above alpha (got ${topOf("mango.mp3")} vs ${topOf("alpha.mp3")})",
            topOf("mango.mp3") < topOf("alpha.mp3"),
        )
        // The A-Z pick flips the level to the content list's ascending order
        // (alpha above mango), and the subfolder still leads.
        compose.onNodeWithText("A-Z").performClick()
        idleUntil({ topOf("alpha.mp3") < topOf("mango.mp3") })
        assertTrue("the subfolder row still comes before the songs", topOf("Albums") < topOf("alpha.mp3"))
    }

    @Test
    fun thePageShowsAnEmptyStateWhenTheFolderHasNoImportedSongs() {
        val viewModel = openContentPane()
        swapTree(viewModel, listOf(file("notes.txt", "c://x/n")))
        addFolder(viewModel, eventsOf(viewModel), "No songs found in that folder.")

        openFolderPage()
        compose.onNodeWithText("No songs imported from that folder yet.").assertExists()
        // The rescan is still offered (new songs may appear), and so is the
        // danger zone.
        compose.onNodeWithContentDescription("Rescan").assertExists()
        compose.onNodeWithText("Delete folder from the app").assertExists()
    }

    @Test
    fun thePageShowsTheUnreadableStateWhenTheTreeCanNoLongerBeRead() {
        val viewModel = openContentPane()
        swapTree(viewModel, listOf(file("song-a.mp3", "c://x/a")))
        addFolder(viewModel, eventsOf(viewModel), "Added 1 song from the folder.")

        // The grant goes away after the add: the page's own walk fails, so
        // the page says so plainly instead of showing a stale or empty list
        // as if the folder simply had no songs.
        viewModel.folderChildren = { throw FileNotFoundException("grant revoked") }

        openFolderPage()
        compose.onNodeWithText("That folder's songs can't be read right now, so they can't be listed.").assertExists()
    }

    @Test
    fun rescanFromThePagePicksUpNewSongsAndRefreshesTheList() {
        val viewModel = openContentPane()
        swapTree(viewModel, listOf(file("song-a.mp3", "c://x/a")))
        addFolder(viewModel, eventsOf(viewModel), "Added 1 song from the folder.")

        openFolderPage()
        waitForText("song-a.mp3")

        // The folder gains two songs since the add; a rescan from the page
        // stores them AND refreshes the page's own list.
        swapTree(viewModel, listOf(
            file("song-a.mp3", "c://x/a"),
            file("song-b.mp3", "c://x/b"),
            file("song-c.mp3", "c://x/c"),
        ))
        val events = eventsOf(viewModel)
        compose.onNodeWithContentDescription("Rescan").performClick()
        idleUntil({ events.any { it == "Added 2 new songs from that folder." } })
        compose.waitForIdle()
        compose.onNodeWithText("song-b.mp3").assertExists()
        compose.onNodeWithText("song-c.mp3").assertExists()
        compose.onNodeWithText("song-a.mp3").assertExists()
    }

    @Test
    fun theDangerZoneDeleteRemovesTheFolderAndItsSongsAndFallsBackToTheContentPane() {
        val viewModel = openContentPane()
        swapTree(viewModel, listOf(file("song-a.mp3", "c://x/a"), file("song-b.mp3", "c://x/b")))
        val events = eventsOf(viewModel)
        addFolder(viewModel, events, "Added 2 songs from the folder.")

        openFolderPage()

        // The danger zone's delete opens the destructive confirmation...
        // (scrolled into view first - the settled pattern for the page's
        // bottom controls on the small test screen.)
        compose.onNodeWithText("Delete").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("Delete").performClick()
        idleUntil({ viewModel.folderRemovalPreview.value != null })
        compose.waitForIdle()
        compose.onNodeWithText("Remove folder?").assertExists()
        compose.onNodeWithText(
            "and its 2 imported songs will be removed from the content list",
            substring = true,
        ).assertExists()
        compose.onNodeWithText("The music files themselves stay on the phone.", substring = true)
            .assertExists()
        // ...and the confirm removes the folder and its songs.
        viewModel.confirmFolderRemoval()
        idleUntil({ events.any { it.startsWith("Removed the folder") } })
        assertTrue("the folder row is removed", dbAndGet { app().repository.observeFolders().first() }.isEmpty())
        assertTrue("the imported songs are removed", dbAndGet { app().repository.observeEntries().first() }.isEmpty())
        // The page, left behind by the removal, falls back to the content
        // pane the folder's row lived in.
        compose.waitForIdle()
        compose.onNodeWithText("Folder details").assertDoesNotExist()
        compose.onNodeWithText("Nothing here yet. Add a song.").assertIsDisplayed()
    }

    @Test
    fun removingTheFolderWhileThePageIsOpenFallsBackToTheContentPane() {
        val viewModel = openContentPane()
        swapTree(viewModel, listOf(file("song-a.mp3", "c://x/a")))
        addFolder(viewModel, eventsOf(viewModel), "Added 1 song from the folder.")

        openFolderPage()

        // The folder row is removed while the page is open (not through the
        // page's own confirm) - the page must fall back to the content pane
        // rather than hold a page for a folder that no longer exists. (The
        // row's departure arrives through an async Room round trip, so pump
        // the main looper until the fallback lands.)
        val folder = dbAndGet { app().repository.observeFolders().first() }.single()
        dbAndGet { app().repository.removeFolder(folder, emptySet()) }
        idleUntil({ compose.onAllNodesWithText("Folder details").fetchSemanticsNodes().isEmpty() })
        // The song stays in the content list (the row-level removal was not
        // used), and the folder's row is gone.
        compose.onNodeWithText("song-a.mp3").assertExists()
        compose.onNodeWithText("Tap to view its songs").assertDoesNotExist()
    }

    companion object {
        /** The documents tree uri the folder is picked at. */
        const val TREE_URI = "content://docs/document/audio%3A1:tree"

        /** A picked folder's docId, in the real tree- uri grammar. */
        const val WALKIN_ROOT = "primary:Music/Walkin"

        /** The picker's tree uri for [WALKIN_ROOT]. */
        val WALKIN_TREE: String = "content://docs/tree/" + Uri.encode(WALKIN_ROOT, null)

        /** The tree-scoped content uri of the document [docId] under [WALKIN_TREE] (the stored payload's shape). */
        fun treeDoc(docId: String): String =
            "content://docs/tree/" + Uri.encode(WALKIN_ROOT, null) + "/document/" + Uri.encode(docId, null)
    }
}
