package com.lumi.player.ui

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.isDisplayed
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.data.ContentSortDirection
import com.lumi.player.data.EntryType
import com.lumi.player.player.EntrySpec
import com.lumi.player.player.PlayerService
import androidx.lifecycle.viewModelScope
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

/**
 * The content pane's sort control and play buttons. The list sorts by
 * title (descending is the default order; a pick reorders the list and
 * persists in the settings table). Every row carries a play button that
 * starts playback through the app's existing playback path (the service's
 * ACTION_START, unattached to any tag): a song's button plays just that
 * song, a folder's plays every song the folder holds, in the list's
 * order.
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

    private fun eventsOf(viewModel: LumiViewModel): MutableList<String> {
        val events = mutableListOf<String>()
        viewModel.viewModelScope.launch {
            viewModel.uiEvents.collect { e -> events += e }
        }
        return events
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
    fun theContentListSortsByTitleDescendingByDefault() {
        openContentPane()
        // The stored rows arrive in insertion order; the list must show
        // Z->A by title until the user picks otherwise.
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

        // The individual section reads Z->A: charlie above bravo, bravo
        // above alpha.
        val charlie = compose.onNodeWithText("charlie").fetchSemanticsNode()
        val bravo = compose.onNodeWithText("bravo").fetchSemanticsNode()
        val alpha = compose.onNodeWithText("alpha").fetchSemanticsNode()
        assertTrue(
            "charlie must sort above bravo in the default descending order",
            charlie.boundsInWindow.bottom < bravo.boundsInWindow.top,
        )
        assertTrue(
            "bravo must sort above alpha in the default descending order",
            bravo.boundsInWindow.bottom < alpha.boundsInWindow.top,
        )
    }

    @Test
    fun togglingTheSortControlReordersTheListAndPersistsThePick() {
        val (_, viewModel) = openContentPane()
        storeSongs("alpha", "charlie", "bravo")
        idleUntil(cond = { compose.onNodeWithText("alpha").isDisplayed() })

        // Descending is the default order.
        assertTrue(
            compose.onNodeWithText("charlie").fetchSemanticsNode().boundsInWindow.bottom <
                compose.onNodeWithText("alpha").fetchSemanticsNode().boundsInWindow.top,
        )

        // Pick ascending: the order flips and the pick persists (the
        // observed direction is re-read from the settings table).
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

        // Back to descending: the order flips again.
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
    fun aFolderRowPlayButtonPlaysAllOfItsSongsInListOrder() {
        openContentPane()
        // Two songs (stored in this order) marked as imported from one
        // folder; the folder row's play button must queue both, in that
        // order.
        dbAndGet {
            val a = checkNotNull(app().repository.addEntryUnique(EntryType.AUDIO, "song-one", "content://one"))
            val b = checkNotNull(app().repository.addEntryUnique(EntryType.AUDIO, "song-two", "content://two"))
            val folder = app().repository.rememberFolder("content://docs/tree:walkin", "Walkin")
            app().repository.markEntriesFromFolder(folder.id, setOf(a.payload, b.payload))
        }
        idleUntil(cond = { compose.onNodeWithContentDescription("Play Walkin").isDisplayed() })
        compose.onNodeWithContentDescription("Play Walkin").performClick()

        val intent = waitForStartIntent()
        assertEquals(PlayerService.ACTION_START, intent.action)
        assertNull(
            "manual playback must not attach a tag",
            intent.getStringExtra(PlayerService.EXTRA_TAG_UID),
        )
        val specs = (intent.getSerializableExtra(PlayerService.EXTRA_ENTRIES) as ArrayList<*>).map { it as EntrySpec }
        assertEquals(
            "the folder's songs play in the list's order",
            listOf("song-one", "song-two"),
            specs.map { it.label },
        )
        assertEquals(
            listOf("content://one", "content://two"),
            specs.map { it.payload },
        )
    }

    @Test
    fun playingAnEmptyFolderReportsInsteadOfStartingAnything() {
        val (_, viewModel) = openContentPane()
        dbAndGet { app().repository.rememberFolder("content://docs/tree:empty", "Empty folder") }
        idleUntil(cond = { compose.onNodeWithContentDescription("Play Empty folder").isDisplayed() })

        val events = eventsOf(viewModel)
        compose.onNodeWithContentDescription("Play Empty folder").performClick()
        idleUntil(cond = { events.any { it == "That folder has no songs to play." } })
        assertNull(
            "an empty folder must not start the service",
            shadowOf(app() as Application).nextStartedService,
        )
    }
}
