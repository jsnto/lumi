package com.lumi.player.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import com.lumi.player.LumiApp
import com.lumi.player.data.FolderChild
import com.lumi.player.player.EntrySpec
import com.lumi.player.player.PlayerService
import com.lumi.player.player.PlayerStateHolder
import com.lumi.player.player.PlayerUiState
import com.lumi.player.player.PlaybackStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.FileNotFoundException

/**
 * Behavioral coverage for the folder's detail page (opened by tapping a
 * folder's row in the content pane): the page shows the folder's details
 * (label + the picked tree uri), lists the songs imported from the folder
 * (the stored content rows the folder's walk identified - including
 * subfolder songs) as the picked folder's own tree (subfolders first, then
 * songs at each level, with the left-side connector lines and a per-level
 * indent), offers the rescan action on the Songs section's title row (a
 * rescan's new songs refresh the list), and ends in a danger zone whose
 * delete removes the folder from the app - and only the folder and its
 * imported songs; the music files on the phone are untouched. The tree's
 * subfolder rows are the show/hide toggle (tapping one hides or restores
 * the rows nested under it; the picked root's own songs always stay), and
 * every song row carries a play/pause button: a non-current song starts
 * through the app's existing single-song path, and the current song's
 * button tracks the shared playback holder live (pause while it plays,
 * play while it is paused) and toggles through the service's own
 * ACTION_TOGGLE. The page is a child of the content pane (its back lands
 * there - the back gesture itself is pinned by [SettingsBackNavUiTest])
 * and it falls back to the content pane when the folder is removed while
 * the page is open.
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
        // The playback holder is process-wide; the button-state tests set
        // it, so every test starts from a clean (idle, nothing current)
        // state.
        PlayerStateHolder.update { PlayerUiState() }
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

    private fun idleUntil(cond: () -> Boolean, timeoutMs: Long = 60_000, message: String = "the operation to settle") {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        check(cond()) { "timed out waiting for $message" }
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

    /** The service intent the play buttons most recently started (the view
     * model's playback path funnels every start and toggle through
     * [android.content.Context.startService]). */
    private fun waitForStartIntent(): Intent {
        var intent: Intent? = null
        idleUntil(
            cond = {
                intent = shadowOf(app() as Application).nextStartedService ?: intent
                intent != null
            },
            message = "the play button's service intent",
        )
        return intent!!
    }

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
    fun thePageShowsTheImportedFolderTreeWithConnectorLinesAndShowHide() {
        val viewModel = openContentPane()
        // The tree's real SAF uri grammar: the picker's tree uri names the
        // picked folder's docId, and every file's payload is the tree-scoped
        // content uri whose docId encodes the full path below the root -
        // here three levels deep (root songs, a subfolder's song, a nested
        // subfolder's song).
        viewModel.folderChildren = { folderUri ->
            when (val s = folderUri.toString()) {
                WALKIN_TREE -> listOf(
                    file("mango.mp3", treeDoc("$WALKIN_ROOT/mango.mp3")),
                    FolderChild("Albums", isDirectory = true, childUri = treeDoc("$WALKIN_ROOT/Albums")),
                    file("alpha.mp3", treeDoc("$WALKIN_ROOT/alpha.mp3")),
                )
                treeDoc("$WALKIN_ROOT/Albums") -> listOf(
                    file("deep.flac", treeDoc("$WALKIN_ROOT/Albums/deep.flac")),
                    FolderChild("Deep", isDirectory = true, childUri = treeDoc("$WALKIN_ROOT/Albums/Deep")),
                )
                treeDoc("$WALKIN_ROOT/Albums/Deep") -> listOf(
                    file("deepest.wav", treeDoc("$WALKIN_ROOT/Albums/Deep/deepest.wav")),
                )
                else -> emptyList()
            }
        }
        addFolder(viewModel, eventsOf(viewModel), "Added 4 songs from the folder.", WALKIN_TREE)

        openFolderPage(WALKIN_TREE)
        // The subfolder is a row of its own (the imported structure, not a
        // flat list), its song nests under it, and the nested subfolder's
        // song nests one level deeper: the tree's left-side connector
        // lines indent each row one step (16 dp, the tree's per-depth
        // indent) per subfolder down, and every folder row leads its own
        // level.
        waitForText("Albums")
        compose.onNodeWithText("deep.flac").assertExists()
        compose.onNodeWithText("Deep").assertExists()
        compose.onNodeWithText("deepest.wav").assertExists()
        assertEquals(
            "the subfolder's song is indented one step (16 dp) beyond the " +
                "root songs (got ${leftOf("deep.flac")} vs root ${leftOf("mango.mp3")})",
            leftOf("mango.mp3") + 16f,
            leftOf("deep.flac"),
        )
        assertEquals(
            "the nested subfolder's song is indented two steps (32 dp) beyond the " +
                "root songs (got ${leftOf("deepest.wav")} vs root ${leftOf("mango.mp3")})",
            leftOf("mango.mp3") + 32f,
            leftOf("deepest.wav"),
        )
        assertTrue(
            "the subfolder row comes above its own nested song",
            topOf("Albums") < topOf("deep.flac"),
        )
        assertTrue(
            "the nested subfolder row comes above its own nested song",
            topOf("Deep") < topOf("deepest.wav"),
        )
        // The root songs sit at the top level (flush with each other).
        assertEquals(
            "the root songs are flush",
            leftOf("mango.mp3"),
            leftOf("alpha.mp3"),
        )
        // The subfolder rows carry the state's own chevron affordance and
        // are the show/hide toggle (never a profile entry point): tapping
        // one hides the rows nested under it - the direct songs AND the
        // nested subfolder's rows - and tapping it again restores them.
        compose.onNodeWithContentDescription("Collapse Albums").assertExists()
        compose.onNodeWithText("Albums").performClick()
        // The merged tree can lag a pending recomposition by a frame (the
        // documented sharp edge) - settle on the flipped chevron (the
        // state's own affordance) before the hard asserts read the
        // post-toggle composition.
        idleUntil(cond = { compose.onNodeWithContentDescription("Expand Albums").isDisplayed() })
        compose.onNodeWithText("deep.flac").assertDoesNotExist()
        compose.onNodeWithText("Deep").assertDoesNotExist()
        compose.onNodeWithText("deepest.wav").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand Albums").assertExists()
        compose.onNodeWithText("Song details").assertDoesNotExist()
        compose.onNodeWithText("Folder details").assertExists()
        compose.onNodeWithText("Albums").performClick()
        idleUntil(cond = { compose.onNodeWithContentDescription("Collapse Albums").isDisplayed() })
        compose.onNodeWithText("deep.flac").assertExists()
        compose.onNodeWithText("deepest.wav").assertExists()
        compose.onNodeWithContentDescription("Collapse Albums").assertExists()
        // The root's own songs are not owned by any subfolder: they stay
        // no matter what collapses, and a song row's body still opens the
        // song's profile page.
        compose.onNodeWithText("mango.mp3").performClick()
        idleUntil(cond = { compose.onNodeWithText("Song details").isDisplayed() })
        compose.onNodeWithText("Song details").assertExists()
    }

    @Test
    fun collapsingANestedFolderHidesOnlyItsOwnRows_andTheStateSurvivesANavigationRoundTrip() {
        val viewModel = openContentPane()
        viewModel.folderChildren = { folderUri ->
            when (val s = folderUri.toString()) {
                WALKIN_TREE -> listOf(
                    file("mango.mp3", treeDoc("$WALKIN_ROOT/mango.mp3")),
                    FolderChild("Albums", isDirectory = true, childUri = treeDoc("$WALKIN_ROOT/Albums")),
                )
                treeDoc("$WALKIN_ROOT/Albums") -> listOf(
                    file("mid.flac", treeDoc("$WALKIN_ROOT/Albums/mid.flac")),
                    FolderChild("Deep", isDirectory = true, childUri = treeDoc("$WALKIN_ROOT/Albums/Deep")),
                )
                treeDoc("$WALKIN_ROOT/Albums/Deep") -> listOf(
                    file("deep.wav", treeDoc("$WALKIN_ROOT/Albums/Deep/deep.wav")),
                )
                else -> emptyList()
            }
        }
        addFolder(viewModel, eventsOf(viewModel), "Added 3 songs from the folder.", WALKIN_TREE)

        openFolderPage(WALKIN_TREE)
        waitForText("mango.mp3")

        // Collapsing the NESTED folder hides only its own content: the
        // outer folder's direct song stays, and the nested folder's own
        // row (the toggle itself) stays - with the chevron pointing the
        // other way.
        compose.onNodeWithText("Deep").performClick()
        idleUntil(cond = { compose.onNodeWithContentDescription("Expand Deep").isDisplayed() })
        compose.onNodeWithText("deep.wav").assertDoesNotExist()
        compose.onNodeWithText("mid.flac").assertExists()
        compose.onNodeWithText("Deep").assertExists()
        compose.onNodeWithContentDescription("Expand Deep").assertExists()

        // The show/hide state is the page's own: leaving the page and
        // coming back keeps the tree exactly as it was left.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Tap to view its songs").assertExists()
        openFolderPage(WALKIN_TREE)
        // The re-entered page re-walks the tree (async) - settle on the
        // surviving collapsed state's own affordance before the asserts.
        idleUntil(cond = { compose.onNodeWithContentDescription("Expand Deep").isDisplayed() })
        compose.onNodeWithText("deep.wav").assertDoesNotExist()
        compose.onNodeWithText("mid.flac").assertExists()
        compose.onNodeWithContentDescription("Expand Deep").assertExists()
    }

    @Test
    fun songRowsCarryPlayButtonsThatStartJustThatSong() {
        val viewModel = openContentPane()
        viewModel.folderChildren = { folderUri ->
            when (val s = folderUri.toString()) {
                WALKIN_TREE -> listOf(
                    file("mango.mp3", treeDoc("$WALKIN_ROOT/mango.mp3")),
                    FolderChild("Albums", isDirectory = true, childUri = treeDoc("$WALKIN_ROOT/Albums")),
                )
                treeDoc("$WALKIN_ROOT/Albums") -> listOf(
                    file("deep.flac", treeDoc("$WALKIN_ROOT/Albums/deep.flac")),
                )
                else -> emptyList()
            }
        }
        addFolder(viewModel, eventsOf(viewModel), "Added 2 songs from the folder.", WALKIN_TREE)

        openFolderPage(WALKIN_TREE)
        waitForText("mango.mp3")

        // Every song row - the root's and the nested one - carries its own
        // play button; the subfolder's row (the show/hide toggle) carries
        // none.
        compose.onNodeWithContentDescription("Play mango.mp3").assertExists()
        compose.onNodeWithContentDescription("Play deep.flac").assertExists()
        compose.onNodeWithContentDescription("Play Albums").assertDoesNotExist()

        // Tapping the nested song's button starts just that song through
        // the app's existing single-song playback path (the service's
        // ACTION_START, unattached to any tag).
        compose.onNodeWithContentDescription("Play deep.flac").performClick()
        val intent = waitForStartIntent()
        assertEquals(PlayerService.ACTION_START, intent.action)
        assertNull("a manual start must not attach a tag", intent.getStringExtra(PlayerService.EXTRA_TAG_UID))
        val specs = (intent.getSerializableExtra(PlayerService.EXTRA_ENTRIES) as ArrayList<*>).map { it as EntrySpec }
        assertEquals(
            "the button queues just that one song",
            listOf(EntrySpec(type = "audio", label = "deep.flac", payload = treeDoc("$WALKIN_ROOT/Albums/deep.flac"))),
            specs,
        )
    }

    @Test
    fun theCurrentSongRowShowsPause_andTogglesThroughTheService() {
        val viewModel = openContentPane()
        viewModel.folderChildren = { folderUri ->
            when (val s = folderUri.toString()) {
                WALKIN_TREE -> listOf(
                    file("mango.mp3", treeDoc("$WALKIN_ROOT/mango.mp3")),
                    file("alpha.mp3", treeDoc("$WALKIN_ROOT/alpha.mp3")),
                )
                else -> emptyList()
            }
        }
        addFolder(viewModel, eventsOf(viewModel), "Added 2 songs from the folder.", WALKIN_TREE)

        openFolderPage(WALKIN_TREE)
        waitForText("mango.mp3")

        // Nothing is current: every row shows its play button.
        compose.onNodeWithContentDescription("Play mango.mp3").assertExists()
        compose.onNodeWithContentDescription("Play alpha.mp3").assertExists()

        // Mango becomes the current track while it plays: its button is
        // live off the shared playback holder - the pause button - while
        // the other row's stays a play button.
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PLAYING, currentLabel = "mango.mp3")
        }
        idleUntil(
            cond = { compose.onAllNodesWithContentDescription("Pause mango.mp3").fetchSemanticsNodes().isNotEmpty() },
            message = "the current song's button to turn into pause",
        )
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Pause mango.mp3").assertExists()
        compose.onNodeWithContentDescription("Play alpha.mp3").assertExists()

        // Tapping it pauses through the service's own toggle - not a fresh
        // start...
        compose.onNodeWithContentDescription("Pause mango.mp3").performClick()
        val pauseIntent = waitForStartIntent()
        assertEquals("pausing must use the service's toggle, not a new start",
            PlayerService.ACTION_TOGGLE, pauseIntent.action)

        // ...and while the SAME song is paused, the button is back to play
        // - tapping it resumes the song through the toggle again, keeping
        // the session unattached to any tag.
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PAUSED, currentLabel = "mango.mp3")
        }
        idleUntil(
            cond = { compose.onAllNodesWithContentDescription("Play mango.mp3").fetchSemanticsNodes().isNotEmpty() },
            message = "the paused song's button to turn back into play",
        )
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Play mango.mp3").performClick()
        val resumeIntent = waitForStartIntent()
        assertEquals("resuming the paused song must reuse the toggle, not start fresh",
            PlayerService.ACTION_TOGGLE, resumeIntent.action)
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
