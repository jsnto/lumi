package com.lumi.player.ui

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.isDisplayed
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.data.ContentSortDirection
import com.lumi.player.data.EntryType
import com.lumi.player.data.FolderChild
import com.lumi.player.player.EntrySpec
import com.lumi.player.player.PlayerService
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

/**
 * The content pane's sort control and play buttons. The list sorts by
 * title (ascending is the default order - a fresh or unchosen install
 * starts A->Z; a pick reorders the list and persists in the settings
 * table, and an explicit pick stays authoritative over the default). The
 * individual rows carry a play button that starts playback through the
 * app's existing playback path (the service's ACTION_START, unattached to
 * any tag), playing just that song; the folder rows carry NO play button -
 * a folder's row opens the folder's own page, where its songs carry the
 * play buttons instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
    qualifiers = "w412dp-h900dp",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContentSortPlayUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        markSetupCompleteForTests()
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private fun app(): LumiApp = ApplicationProvider.getApplicationContext()

    private fun openContentPane(): Pair<MainActivity, LumiViewModel> {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Content").performClick()
        compose.waitForIdle()
        val field = MainActivity::class.java.getDeclaredField("viewModel")
        field.isAccessible = true
        return activity to field.get(activity) as LumiViewModel
    }

    /** Runs a suspend block on the app's database and returns its result. */
    private fun <T> dbAndGet(block: suspend () -> T): T {
        var result: T? = null
        Thread { result = runBlocking { block() } }.also { it.start() }.apply { join() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun idleUntil(cond: () -> Boolean, timeoutMs: Long = 5_000, message: String = "the content pane to settle") {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        check(cond()) { "timed out waiting for $message" }
    }

    /** The ACTION_START intent the play buttons most recently started. */
    private fun waitForStartIntent(): Intent {
        var intent: Intent? = null
        idleUntil(
            cond = {
                intent = shadowOf(app() as Application).nextStartedService ?: intent
                intent != null
            },
            message = "the play button's start intent",
        )
        return intent!!
    }

    private fun storeSongs(vararg labels: String) {
        dbAndGet {
            labels.forEachIndexed { index, label ->
                app().repository.addEntryUnique(EntryType.AUDIO, label, "content://$label/$index")
            }
        }
    }

    @Test
    fun theContentListSortsByTitleAscendingByDefault() {
        openContentPane()
        // The stored rows arrive in insertion order; the list must show
        // A->Z by title until the user picks otherwise (the default).
        storeSongs("alpha", "charlie", "bravo")
        idleUntil(
            cond = {
                compose.onNodeWithText("charlie").isDisplayed() &&
                    compose.onNodeWithText("bravo").isDisplayed() &&
                    compose.onNodeWithText("alpha").isDisplayed()
            },
        )
        compose.waitForIdle()

        // The sort control is present, with the list's own A-Z / Z-A copy.
        compose.onNodeWithText("Sort by title").assertExists()
        compose.onNodeWithText("A-Z").assertExists()
        compose.onNodeWithText("Z-A").assertExists()

        // The individual section reads A->Z: alpha above bravo, bravo
        // above charlie.
        val charlie = compose.onNodeWithText("charlie").fetchSemanticsNode()
        val bravo = compose.onNodeWithText("bravo").fetchSemanticsNode()
        val alpha = compose.onNodeWithText("alpha").fetchSemanticsNode()
        assertTrue(
            "alpha must sort above bravo in the default ascending order",
            alpha.boundsInWindow.bottom < bravo.boundsInWindow.top,
        )
        assertTrue(
            "bravo must sort above charlie in the default ascending order",
            bravo.boundsInWindow.bottom < charlie.boundsInWindow.top,
        )
    }

    @Test
    fun togglingTheSortControlReordersTheListAndPersistsThePick() {
        val (_, viewModel) = openContentPane()
        storeSongs("alpha", "charlie", "bravo")
        idleUntil(cond = { compose.onNodeWithText("charlie").isDisplayed() })

        // Ascending is the default order.
        assertTrue(
            compose.onNodeWithText("alpha").fetchSemanticsNode().boundsInWindow.bottom <
                compose.onNodeWithText("charlie").fetchSemanticsNode().boundsInWindow.top,
        )

        // Pick descending: the order flips and the pick persists (the
        // observed direction is re-read from the settings table).
        compose.onNodeWithText("Z-A").performClick()
        idleUntil(
            cond = {
                compose.onNodeWithText("charlie").fetchSemanticsNode().boundsInWindow.bottom <
                    compose.onNodeWithText("alpha").fetchSemanticsNode().boundsInWindow.top
            },
            message = "the descending reorder",
        )
        idleUntil(
            cond = { viewModel.contentSortDirection.value == ContentSortDirection.DESCENDING },
            message = "the persisted sort direction",
        )

        // Back to ascending: the order flips again.
        compose.onNodeWithText("A-Z").performClick()
        idleUntil(
            cond = {
                compose.onNodeWithText("alpha").fetchSemanticsNode().boundsInWindow.bottom <
                    compose.onNodeWithText("charlie").fetchSemanticsNode().boundsInWindow.top
            },
            message = "the ascending reorder",
        )
        idleUntil(
            cond = { viewModel.contentSortDirection.value == ContentSortDirection.ASCENDING },
            message = "the persisted sort direction",
        )
    }

    @Test
    fun aSongRowPlayButtonPlaysJustThatSongThroughTheExistingPath() {
        openContentPane()
        storeSongs("alpha", "bravo")
        idleUntil(cond = { compose.onNodeWithContentDescription("Play bravo").isDisplayed() })
        compose.onNodeWithContentDescription("Play bravo").performClick()

        val intent = waitForStartIntent()
        assertEquals(PlayerService.ACTION_START, intent.action)
        assertNull(
            "manual playback must not attach a tag",
            intent.getStringExtra(PlayerService.EXTRA_TAG_UID),
        )
        val specs = (intent.getSerializableExtra(PlayerService.EXTRA_ENTRIES) as ArrayList<*>).map { it as EntrySpec }
        assertEquals(
            "the play button queues just that one song",
            listOf(EntrySpec(type = "audio", label = "bravo", payload = "content://bravo/1")),
            specs,
        )
    }

    @Test
    fun aFolderRowCarriesNoPlayButton_andItsRowStillOpensTheFolderPage() {
        val (_, viewModel) = openContentPane()
        // Two songs (stored in this order) marked as imported from one
        // folder.
        dbAndGet {
            val a = checkNotNull(app().repository.addEntryUnique(EntryType.AUDIO, "song-one", "content://one"))
            val b = checkNotNull(app().repository.addEntryUnique(EntryType.AUDIO, "song-two", "content://two"))
            val folder = app().repository.rememberFolder("content://docs/tree:walkin", "Walkin")
            app().repository.markEntriesFromFolder(folder.id, setOf(a.payload, b.payload))
        }
        idleUntil(cond = { compose.onNodeWithText("Walkin").isDisplayed() })

        // The folder row's play button is gone - and its songs' buttons
        // are not on the content pane at all: the folder-imported rows
        // live on the folder's own page, not in the individual list.
        compose.onNodeWithContentDescription("Play Walkin").assertDoesNotExist()
        compose.onNodeWithContentDescription("Play song-one").assertDoesNotExist()

        // The row body is still the folder page's entry point - the
        // folder's songs (and their play buttons) live on that page now.
        // The page's own walk goes through the faked seam (it lists the
        // stored rows' payloads, so the page's list shows).
        viewModel.folderChildren = {
            listOf(
                FolderChild("song-one.mp3", isDirectory = false, childUri = "content://one"),
                FolderChild("song-two.mp3", isDirectory = false, childUri = "content://two"),
            )
        }
        compose.onNodeWithText("Walkin").performClick()
        compose.waitForIdle()
        waitForFolderPage()
        // The page's walk settles asynchronously (a Room round trip); the
        // song rows (and their play buttons) arrive with it.
        idleUntil(
            cond = { compose.onNodeWithContentDescription("Play song-one").isDisplayed() },
            timeoutMs = 10_000,
            message = "the folder page's song play buttons",
        )
        compose.onNodeWithContentDescription("Play song-two").assertExists()
        // The page's own rows are the songs' - the folder's label carries
        // no play button of its own there either.
        compose.onNodeWithContentDescription("Play Walkin").assertDoesNotExist()
    }

    /** Waits for the folder detail page to show. */
    private fun waitForFolderPage() {
        var deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            compose.waitForIdle()
            if (compose.onAllNodesWithText("Folder details").fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(20)
        }
        compose.onNodeWithText("Folder details").assertExists()
    }
}
