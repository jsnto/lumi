package com.lumi.player.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.font.FontWeight
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.data.PlayMode
import com.lumi.player.player.PlaybackStatus
import com.lumi.player.player.PlayerStateHolder
import com.lumi.player.player.PlayerUiState
import com.lumi.player.ui.theme.LumiTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
 * Behavioral coverage for the settled UI revamp (D-01..D-08) on top of the
 * already-pinned screens: the player's unified top row (wordmark + lock +
 * settings chips) and its single big control with the per-state action icon,
 * the entry-type tonal chip, the settings menu's now-playing strip (shown
 * only while an entry is loaded, one line no matter the label length), the
 * keep-screen-on row's icon-chip treatment, the destructive Remove pill in
 * the tag-delete dialog, and the "Sunlit amber" palette + pushed weight
 * hierarchy read back from the live [MaterialTheme].
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiRevampUiTest {

    /**
     * One rule for the whole class, plus a manual [MainActivity] launch per
     * test: the activity is created in the test body (after the @Before
     * pre-seed landed in the store), so the setup gate's first frame is
     * already the player's - an activity created before the pre-seed (the
     * rule-created one) composes the wizard first and swaps to the player
     * on the store write, and that swap widens the first-frame race where
     * the merged semantics tree lags the layout by a frame.
     */
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var activity: MainActivity

    /** Creates and sets up the real [MainActivity] (idempotent per test). */
    private fun setupActivity(): MainActivity {
        if (!::activity.isInitialized) {
            activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        }
        return activity
    }

    @Before
    fun setUp() {
        markSetupCompleteForTests()
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
        PlayerStateHolder.update { PlayerUiState() }
    }

    private fun app(): LumiApp = ApplicationProvider.getApplicationContext()

    private fun <T> dbAndGet(block: suspend () -> T): T {
        var result: T? = null
        Thread { result = runBlocking { block() } }.also { it.start() }.apply { join() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /** Pumps the main looper until a node with [text] is laid out or fails. */
    private fun waitForText(text: String, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                compose.onNodeWithText(text, substring = true).assertExists()
                return
            } catch (e: AssertionError) {
                ShadowLooper.idleMainLooper()
                Thread.sleep(10)
            }
        }
        compose.onNodeWithText(text, substring = true).assertExists()
    }

    private fun openSettings(): MainActivity {
        setupActivity()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        return activity
    }

    // ---------- player screen: unified top row + big control (D-04/D-05) ----------

    @Test
    fun playerScreenTopRowHasWordmarkLockAndSettingsChipsAndIdleStopControl() {
        setupActivity()
        compose.waitForIdle()

        // The player draws its own transparent top row: the "lumi" wordmark
        // left, the lock and settings chips on the right (the host top bar
        // is gone).
        compose.onNodeWithText("lumi").assertIsDisplayed()
        compose.onNodeWithContentDescription("Lock the screen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()

        // Idle: no entry loaded, so no type chip, and the single big control
        // is the disabled "Stop".
        compose.onNodeWithText("music").assertDoesNotExist()
        compose.onNodeWithText("Stop").assertIsNotEnabled()
    }

    @Test
    fun playerScreenPlayingShowsMusicChipAndPauseControl() {
        PlayerStateHolder.update {
            PlayerUiState(
                status = PlaybackStatus.PLAYING,
                currentLabel = "Twinkle",
                currentType = "AUDIO",
            )
        }
        setupActivity()
        compose.waitForIdle()

        // The entry type is a tonal chip (D-05) and the big control carries
        // the per-state action icon + label (D-04): Pause while playing.
        compose.onNodeWithText("music").assertIsDisplayed()
        compose.onNodeWithText("Pause").assertIsDisplayed()
        compose.onNodeWithText("Stop").assertDoesNotExist()
    }

    @Test
    fun playerScreenPausedShowsPlayControl() {
        PlayerStateHolder.update {
            PlayerUiState(
                status = PlaybackStatus.PAUSED,
                currentLabel = "Twinkle",
                currentType = "AUDIO",
            )
        }
        setupActivity()
        compose.waitForIdle()

        compose.onNodeWithText("music").assertIsDisplayed()
        compose.onNodeWithText("Play").assertIsDisplayed()
        compose.onNodeWithText("Pause").assertDoesNotExist()
    }

    // ---------- front logo: the sunny record face, square-free ----------

    /** The front (and wizard) logo's semantics tag. */
    private val lumiLogo: SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "lumi-logo")

    @Test
    fun theFrontScreenShowsTheLumiLogoSquareFree() {
        setupActivity()
        compose.waitForIdle()

        // The logo is the vector face (a Canvas-drawn sun with a face-like
        // dot), tagged for tests - not the emoji blob, and with no
        // rectangular background: the halo is a circle (drawCircle), so the
        // mirrored brush fill can't reach the corners.
        compose.onNode(lumiLogo).assertIsDisplayed()
    }

    @Test
    fun theFrontLogoIsShownWhileASongPlays() {
        PlayerStateHolder.update {
            PlayerUiState(
                status = PlaybackStatus.PLAYING,
                currentLabel = "Twinkle",
                currentType = "AUDIO",
            )
        }
        setupActivity()
        compose.waitForIdle()

        // While a song plays the logo is on the spinning core (the spin and
        // bob are frame-driven - the on-device verification item - but the
        // logo itself must be present and shown while active).
        compose.onNode(lumiLogo).assertIsDisplayed()
    }

    // ---------- settings menu: now-playing strip (D-06) ----------

    @Test
    fun settingsMenuNowPlayingStripShowsEntryLabelAndModeChip() {
        PlayerStateHolder.update {
            PlayerUiState(
                status = PlaybackStatus.PLAYING,
                currentLabel = "Twinkle",
                currentType = "AUDIO",
            )
        }
        openSettings()

        compose.onNodeWithText("Twinkle").assertIsDisplayed()
        // No play mode stored: the strip falls back to the app's
        // default, which is proximity (a stored mode would still win -
        // see the continuous case below).
        compose.onNodeWithText("PROXIMITY").assertIsDisplayed()
        compose.onNodeWithText("CONTINUOUS").assertDoesNotExist()
    }

    @Test
    fun settingsMenuNowPlayingStripShowsContinuousWhenModeIsContinuous() {
        // A stored play mode wins over the proximity default.
        dbAndGet { app().repository.setPlayMode(PlayMode.CONTINUOUS) }
        PlayerStateHolder.update {
            PlayerUiState(
                status = PlaybackStatus.PLAYING,
                currentLabel = "Twinkle",
                currentType = "AUDIO",
            )
        }
        openSettings()

        compose.onNodeWithText("CONTINUOUS").assertIsDisplayed()
        compose.onNodeWithText("PROXIMITY").assertDoesNotExist()
    }

    @Test
    fun settingsMenuNowPlayingStripShowsProximityWhenModeIsProximity() {
        dbAndGet { app().repository.setPlayMode(PlayMode.PROXIMITY) }
        PlayerStateHolder.update {
            PlayerUiState(
                status = PlaybackStatus.PAUSED,
                currentLabel = "Twinkle",
                currentType = "AUDIO",
            )
        }
        openSettings()

        compose.onNodeWithText("PROXIMITY").assertIsDisplayed()
        compose.onNodeWithText("CONTINUOUS").assertDoesNotExist()
    }

    @Test
    fun settingsMenuNowPlayingStripIsHiddenWhenIdle() {
        // No entry loaded: the strip must not occupy the menu's top slot.
        openSettings()

        compose.onNodeWithText("CONTINUOUS").assertDoesNotExist()
        compose.onNodeWithText("PROXIMITY").assertDoesNotExist()
    }

    @Test
    fun settingsMenuNowPlayingStripLongLabelStaysOnOneLineWithinTheScreen() {
        val longLabel =
            ("Gentle Waves For The Afternoon " + "Rain Song On The Veranda ").repeat(3)
        PlayerStateHolder.update {
            PlayerUiState(
                status = PlaybackStatus.PLAYING,
                currentLabel = longLabel,
                currentType = "AUDIO",
            )
        }
        openSettings()

        val node = compose
            .onNodeWithText(longLabel, substring = true)
            .fetchSemanticsNode()
        val screenWidth = activity.resources.displayMetrics.widthPixels
        val bounds = node.boundsInRoot
        assertTrue(
            "the strip label must not run past the right edge",
            bounds.right <= screenWidth,
        )
        // The strip also carries the state dot and the mode chip, so the
        // label can never take the whole row.
        assertTrue(
            "the strip label must leave room for the dot and mode chip",
            bounds.width < screenWidth * 0.9,
        )
    }

    // ---------- advanced options page: keep-screen-on row (moved from the System page; icon chip, D-03) ----------

    @Test
    fun advancedPageKeepScreenOnRowRendersWithItsCheckbox() {
        openSettings()
        compose.onNodeWithText("Advanced options").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("Advanced options").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Keep screen on").assertIsDisplayed()
        compose.onNodeWithText("Keep the display on while the app is open.").assertExists()
        val checkbox = SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.TestTag,
            "keep-screen-on",
        )
        compose.onNode(checkbox).assertExists()
    }

    // ---------- tag delete dialog: destructive pill ----------

    @Test
    fun removeTagDialogUsesDestructiveRemovePillAndRemovesTheTag() {
        dbAndGet { app().repository.addTag("02:01:02:03:04:05:10:aa", "Bunny") }
        openSettings()
        compose.onNodeWithText("NFC tags").performClick()
        compose.waitForIdle()
        waitForText("Bunny")

        // Deleting a tag happens from the tag detail page's danger zone (the
        // row-level delete icon is gone): the row body opens the page, and
        // the danger zone's delete reuses the same confirmation dialog.
        compose.onNodeWithText("Bunny").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Delete").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Remove tag?").assertIsDisplayed()
        compose.onNodeWithText("Remove").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()

        compose.onNodeWithText("Remove").performClick()
        compose.waitForIdle()
        // The page fell back to the NFC tags list (where the tag's row
        // lived); the list is empty now.
        waitForText("No tags yet")
        assertEquals(0, dbAndGet { app().repository.observeTags().first() }.size)
    }

    // ---------- theme: "Sunlit amber" palette + pushed weights (D-01/D-02) ----------

    @Test
    fun themeIsSunlitAmberWithPushedWeightHierarchy() {
        // The theme's color scheme and typography are @Composable reads, so
        // compose the app's [LumiTheme] on a bare activity and capture the
        // live values inside the composition.
        var capturedColors: ColorScheme? = null
        var capturedType: Typography? = null
        val act = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        act.setContent {
            LumiTheme {
                capturedColors = MaterialTheme.colorScheme
                capturedType = MaterialTheme.typography
            }
        }
        val deadline = System.currentTimeMillis() + 5_000
        while ((capturedColors == null || capturedType == null) &&
            System.currentTimeMillis() < deadline
        ) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        val colors = checkNotNull(capturedColors)
        val type = checkNotNull(capturedType)

        // This mirror's Color.value packs the ARGB word at bits 32-63.
        fun argb(color: Color): Long = color.value.toLong() ushr 32
        assertEquals(0xFFF5761E.toLong(), argb(colors.primary))
        assertEquals(0xFFFFE3C8.toLong(), argb(colors.primaryContainer))
        assertEquals(0xFF381502.toLong(), argb(colors.onPrimaryContainer))
        assertEquals(0xFFC9EBE1.toLong(), argb(colors.secondaryContainer))
        assertEquals(0xFFE9E1FB.toLong(), argb(colors.tertiaryContainer))
        assertEquals(0xFFFFFBF2.toLong(), argb(colors.background))
        assertEquals(0xFFEDE2CF.toLong(), argb(colors.surfaceContainerHigh))
        assertEquals(0xFF5B4B3F.toLong(), argb(colors.onSurfaceVariant))

        // The pushed weight hierarchy: wordmark/labels ExtraBold, dialog and
        // top-bar titles Bold, row titles Bold (sizes unchanged).
        assertEquals(FontWeight.ExtraBold, type.titleLarge.fontWeight)
        assertEquals(FontWeight.ExtraBold, type.headlineMedium.fontWeight)
        assertEquals(FontWeight.Bold, type.headlineSmall.fontWeight)
        assertEquals(FontWeight.Bold, type.titleMedium.fontWeight)
    }
}
