package com.lumi.player.ui

import android.view.WindowManager
import com.lumi.player.LumiApp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Verifies the keep-screen-on lifecycle on the main activity: [MainActivity]
 * sets FLAG_KEEP_SCREEN_ON while resumed and clears it while paused, so the
 * screen stays on for the activity's lifetime (NFC reads and playback are not
 * interrupted by the screen locking, sleeping, or going dark).
 *
 * The flag lifecycle IS the behavior under test, so this drives onResume /
 * onPause directly and asserts the window flag synchronously. It deliberately
 * does NOT idle the main looper (no ActivityController.pause/resume, no
 * compose.waitForIdle): the player screen's infinite Compose pulse
 * (rememberInfiniteTransition) reschedules frames forever, so any attempt to
 * idle the looper while it is running would block indefinitely.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeepScreenOnUiTest {

    @Before
    fun setUp() {
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private fun keepScreenOn(activity: MainActivity): Boolean =
        (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0

    // onResume/onPause are protected on Activity, so drive the real
    // MainActivity overrides directly (running their full body) without the
    // framework lifecycle plumbing or any main-looper idling.
    private fun drive(activity: MainActivity, method: String) {
        val m = MainActivity::class.java.getDeclaredMethod(method)
        m.isAccessible = true
        m.invoke(activity)
    }

    @Test
    fun keepScreenOn_setOnResume_clearedOnPause() {
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .start()
            .get()

        assertFalse(
            "FLAG_KEEP_SCREEN_ON must not be set before onResume",
            keepScreenOn(activity),
        )

        // onResume keeps the screen on for the activity's lifetime.
        drive(activity, "onResume")
        assertTrue(
            "onResume must set FLAG_KEEP_SCREEN_ON",
            keepScreenOn(activity),
        )

        // onPause releases the keep-screen-on hold.
        drive(activity, "onPause")
        assertTrue(
            "onPause must clear FLAG_KEEP_SCREEN_ON",
            !keepScreenOn(activity),
        )

        // Re-resume restores it: the screen stays on while the app is open.
        drive(activity, "onResume")
        assertTrue(
            "re-resume must set FLAG_KEEP_SCREEN_ON again",
            keepScreenOn(activity),
        )
    }
}
