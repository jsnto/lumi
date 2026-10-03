package com.lumi.player.ui

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lumi.player.LumiApp
import com.lumi.player.player.PlaybackStatus
import com.lumi.player.player.PlayerStateHolder
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Behavioral tests for the front-screen soft lock: the lock button shows the
 * pre-lock popup (or engages the lock directly once the popup was suppressed),
 * the dimming overlay keeps the app underneath working, and 3 taps - close
 * enough together - unlock. Driven through the real activity + Compose content
 * under Robolectric, like the other front-screen UI tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SoftLockUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var controller: ActivityController<MainActivity>

    @Before
    fun setUp() {
        markSetupCompleteForTests()
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
        PlayerStateHolder.update { it.copy() }
    }

    @After
    fun tearDown() {
        PlayerStateHolder.update { it.copy() }
    }

    private fun startMainActivity() {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
    }

    private fun tapLockControl() {
        compose.onNodeWithContentDescription("Lock the screen").performClick()
        compose.waitForIdle()
    }

    private fun confirmLock() {
        tapLockControl()
        compose.onNodeWithText("Lock").performClick()
        compose.waitForIdle()
    }

    private fun tapOverlay(times: Int) {
        repeat(times) {
            compose.onNodeWithText(UNLOCK_HINT).performClick()
            compose.waitForIdle()
        }
    }

    private fun assertLocked() {
        compose.onNodeWithText(UNLOCK_HINT).assertIsDisplayed()
    }

    private fun assertUnlocked() {
        compose.onNodeWithText(UNLOCK_HINT).assertDoesNotExist()
    }

    @Test
    fun lockControl_showsPreLockPopup() {
        startMainActivity()
        compose.waitForIdle()
        assertUnlocked()
        tapLockControl()
        compose.onNodeWithText("Lock the screen?").assertIsDisplayed()
        compose.onNodeWithText(POPUP_TEXT).assertIsDisplayed()
        compose.onNodeWithText("Don't show this again").assertIsDisplayed()
        compose.onNodeWithText("Lock").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        assertUnlocked()
    }

    @Test
    fun cancel_dismissesPopupAndStaysUnlocked() {
        startMainActivity()
        compose.waitForIdle()
        tapLockControl()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Lock the screen?").assertDoesNotExist()
        assertUnlocked()
    }

    @Test
    fun confirm_engagesLockOverlay() {
        startMainActivity()
        compose.waitForIdle()
        confirmLock()
        compose.onNodeWithText("Lock the screen?").assertDoesNotExist()
        assertLocked()
    }

    @Test
    fun threeTapsOnOverlay_unlock() {
        startMainActivity()
        compose.waitForIdle()
        confirmLock()
        assertLocked()
        tapOverlay(2)
        assertLocked()
        tapOverlay(1)
        assertUnlocked()
    }

    @Test
    fun tapsFarApart_doNotUnlock_untilThreeConsecutive() {
        startMainActivity()
        compose.waitForIdle()
        confirmLock()

        // Tap, wait far past the unlock window, then tap twice more: the slow
        // gap reset the count, so the screen is still locked.
        tapOverlay(1)
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(
            TripleTapUnlocker.UNLOCK_TAP_WINDOW_MS + 1_000L,
            TimeUnit.MILLISECONDS,
        )
        tapOverlay(2)
        assertLocked()

        // A fresh consecutive triple still unlocks.
        tapOverlay(1)
        assertUnlocked()
    }

    @Test
    fun appKeepsWorkingWhileLocked() {
        // Seed a playing state, like a tag read would.
        PlayerStateHolder.update {
            it.copy(
                status = PlaybackStatus.PLAYING,
                currentLabel = "Song A",
                currentType = "audio",
            )
        }
        startMainActivity()
        compose.waitForIdle()
        // (assertExists rather than assertIsDisplayed: the small test screen
        // clips the bottom of the front screen, but the content must still be
        // composed and functional.)
        compose.onNodeWithText("Song A").assertExists()
        compose.onNodeWithText("Pause").assertExists()

        confirmLock()
        assertLocked()
        // The front screen underneath is still composed and functional...
        compose.onNodeWithText("Song A").assertExists()
        compose.onNodeWithText("Pause").assertExists()
        // ...and playback state is untouched while the lock is engaged.
        assertTrue(
            "playback must keep going while soft-locked",
            PlayerStateHolder.state.value.isPlaying,
        )

        // An incomplete unlock attempt must not change anything either.
        tapOverlay(2)
        assertLocked()
        assertTrue(
            "an incomplete unlock must not disturb playback",
            PlayerStateHolder.state.value.isPlaying,
        )
    }

    @Test
    fun dontShowAgain_isPersisted_andSkipsThePopup() {
        startMainActivity()
        compose.waitForIdle()

        // First lock: tick the checkbox and confirm.
        tapLockControl()
        compose.onNodeWithTag("lock-dont-show-again").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Lock").performClick()
        compose.waitForIdle()
        assertLocked()

        // The setting was persisted to the database (the Room write is
        // asynchronous, so poll the main looper until it lands).
        awaitSuppressed()

        // Unlock...
        tapOverlay(3)
        assertUnlocked()

        // ...and the next lock engages directly, without the popup.
        tapLockControl()
        compose.onNodeWithText("Lock the screen?").assertDoesNotExist()
        assertLocked()
    }

    /** Waits for the asynchronous Room write to make the setting observable. */
    private fun awaitSuppressed() {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            if (viewModelOf().lockPopupSuppressed.value) return
        }
        fail("the 'don't show again' setting must be persisted")
    }

    /** The activity's real [LumiViewModel], via reflection. */
    private fun viewModelOf(): LumiViewModel {
        val field = MainActivity::class.java
            .getDeclaredField("viewModel")
            .also { it.isAccessible = true }
        return field.get(controller.get()) as LumiViewModel
    }

    companion object {
        private const val UNLOCK_HINT = "🔒 Tap the screen 3 times fast to unlock"
        private const val POPUP_TEXT =
            "The screen will darken, but lumi keeps working: music keeps playing. " +
                "Tap the screen 3 times fast to unlock."
    }
}
