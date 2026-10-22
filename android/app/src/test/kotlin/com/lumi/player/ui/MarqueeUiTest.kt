package com.lumi.player.ui

import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import com.lumi.player.player.PlaybackStatus
import com.lumi.player.player.PlayerStateHolder
import com.lumi.player.player.PlayerUiState
import kotlinx.coroutines.runBlocking
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
 * A song title that is too long to fit auto-scrolls (marquee) instead of
 * clipping, on the surfaces the app marquee titles: the content pane's
 * song rows (a long title flips into the scrolling state once its first
 * layout measures it; a fitting title stays static and never scrolls).
 *
 * The scrolling marquee carries the FULL title's content description
 * (accessibility: the service reads the whole title, not a clipped
 * fragment), and that is what these tests observe: the long title's row
 * carries its full label as a content description once it scrolls, and a
 * fitting title's row carries none. The marquee's own offset math is
 * pinned by [com.lumi.player.ui.MarqueeOffsetTest] (pure).
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
    // The content pane's song rows sit below the fold on the default
    // 320x470 screen (a clipped row reports empty bounds and is not
    // "displayed"), so the pane is opened on a taller screen.
    qualifiers = "w412dp-h900dp",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarqueeUiTest {

    private companion object {
        val LONG_TITLE = "l".repeat(400) + "ong"
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        markSetupCompleteForTests()
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private fun app(): LumiApp =
        androidx.test.core.app.ApplicationProvider.getApplicationContext()

    /** Runs a suspend block on the app's database and returns its result. */
    private fun <T> dbAndGet(block: suspend () -> T): T {
        var result: T? = null
        Thread { result = runBlocking { block() } }.also { it.start() }.apply { join() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun idleUntil(cond: () -> Boolean, timeoutMs: Long = 5_000, message: String = "the screen to settle") {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        check(cond()) { "timed out waiting for $message" }
    }

    private fun openContentPane(): MainActivity {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Content").performClick()
        compose.waitForIdle()
        return activity
    }

    private fun storeSongs(vararg labels: String) {
        dbAndGet {
            labels.forEachIndexed { index, label ->
                app().repository.addEntryUnique(EntryType.AUDIO, label, "content://marquee/$index")
            }
        }
    }

    @Test
    fun aLongSongTitleScrollsInTheContentPaneRow() {
        openContentPane()
        storeSongs("short song", LONG_TITLE)
        // The long title's row flips into the scrolling state once its
        // first layout measures it: the scrolling marquee carries the
        // FULL title as the row's content description (merged up from the
        // marquee's own box).
        idleUntil(
            cond = {
                compose.onNodeWithContentDescription(LONG_TITLE).isDisplayed() &&
                    compose.onNodeWithText("short song").isDisplayed()
            },
            message = "the long title's row to flip into the scrolling marquee",
        )
        compose.waitForIdle()

        // The long title's row carries the scrolling marquee's description;
        // the short title's row does not (it fits, so nothing scrolls).
        assertTrue(compose.onNodeWithContentDescription(LONG_TITLE).isDisplayed())
        assertFalse(compose.onNodeWithContentDescription("short song").isDisplayed())
        // The long title still renders its own text (the marquee's).
        compose.onNodeWithText(LONG_TITLE).assertExists()
    }

    @Test
    fun aFittingSongTitleStaysStaticInTheContentPaneRow() {
        openContentPane()
        storeSongs("short song")
        idleUntil(
            cond = { compose.onNodeWithText("short song").isDisplayed() },
            message = "the content row to settle",
        )
        compose.waitForIdle()
        // The title fits: no scrolling marquee, so no full-title
        // description on the row (nothing animates either).
        assertFalse(compose.onNodeWithContentDescription("short song").isDisplayed())
    }

    @Test
    fun aLongNowPlayingLabelScrollsInTheSettingsStrip() {
        // The now-playing state drives the settings menu's strip.
        PlayerStateHolder.update {
            PlayerUiState(
                status = PlaybackStatus.PLAYING,
                currentLabel = LONG_TITLE,
                currentType = "audio",
            )
        }
        Robolectric.buildActivity(MainActivity::class.java).setup()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        // The strip's title flips into the scrolling state once its first
        // layout measures it: the scrolling marquee carries the FULL label
        // as its own content description.
        idleUntil(
            cond = { compose.onNodeWithContentDescription(LONG_TITLE).isDisplayed() },
            message = "the strip's title to flip into the scrolling marquee",
        )
        compose.waitForIdle()
        assertTrue(compose.onNodeWithContentDescription(LONG_TITLE).isDisplayed())
        // The strip still shows its label (the marquee's text).
        compose.onNodeWithText(LONG_TITLE).assertExists()
    }

    @Test
    fun aShortNowPlayingLabelStaysStaticInTheSettingsStrip() {
        PlayerStateHolder.update {
            PlayerUiState(
                status = PlaybackStatus.PLAYING,
                currentLabel = "short song",
                currentType = "audio",
            )
        }
        Robolectric.buildActivity(MainActivity::class.java).setup()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("short song").assertExists()
        // The label fits: no scrolling marquee (no full-label description
        // on the strip).
        compose.waitForIdle()
        assertFalse(compose.onNodeWithContentDescription("short song").isDisplayed())
    }

    @Test
    fun aLongTitleThatFitsTwoLinesShowsAsIsOnTheNowPlayingSurface() {
        // A title that fits the front screen's two lines does not scroll
        // (it shows as-is, centered).
        val twoLineFitting = "a".repeat(40) + "b"
        PlayerStateHolder.update {
            PlayerUiState(
                status = PlaybackStatus.PLAYING,
                currentLabel = twoLineFitting,
                currentType = "audio",
            )
        }
        Robolectric.buildActivity(MainActivity::class.java).setup()
        compose.waitForIdle()
        compose.onNodeWithText(twoLineFitting).assertExists()
        compose.waitForIdle()
        assertFalse(compose.onNodeWithContentDescription(twoLineFitting).isDisplayed())
    }

    @Test
    fun aTitleTooLongToFitScrollsOnTheNowPlayingSurface() {
        // A title too long for the front screen's two lines flips into the
        // scrolling marquee (its own content description carries the full
        // title) once the first layout measures the overflow.
        PlayerStateHolder.update {
            PlayerUiState(
                status = PlaybackStatus.PLAYING,
                currentLabel = LONG_TITLE,
                currentType = "audio",
            )
        }
        Robolectric.buildActivity(MainActivity::class.java).setup()
        compose.waitForIdle()
        idleUntil(
            cond = { compose.onNodeWithContentDescription(LONG_TITLE).isDisplayed() },
            message = "the title to flip into the scrolling marquee",
        )
        compose.waitForIdle()
        assertTrue(compose.onNodeWithContentDescription(LONG_TITLE).isDisplayed())
        compose.onNodeWithText(LONG_TITLE).assertExists()
    }
}
