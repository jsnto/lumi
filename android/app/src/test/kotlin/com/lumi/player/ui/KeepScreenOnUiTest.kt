package com.lumi.player.ui

import android.view.WindowManager
import com.lumi.player.LumiApp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Verifies the keep-screen-on behavior on the main activity: the screen is
 * kept on only when the user enabled the keep-screen-on setting (default:
 * off, so the screen follows the system timeout while the app is open).
 * When on, [MainActivity] sets FLAG_KEEP_SCREEN_ON while resumed and clears
 * it while paused, and applies/clears the flag live when the setting
 * changes while the app is open (the settings checkbox).
 *
 * The flag lifecycle IS the behavior under test, so this drives onResume /
 * onPause directly and asserts the window flag synchronously. The only
 * asynchronous part is the setting's Room round trip, which is pumped with a
 * bounded idleFor (the player screen's infinite Compose pulse would block an
 * unbounded looper idle, so the pump is deadline-bounded and checks the
 * condition, like the other settings UI tests).
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
        markSetupCompleteForTests()
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private fun keepScreenOn(activity: MainActivity): Boolean =
        (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0

    // onResume/onPause are protected on Activity, so drive the real
    // MainActivity overrides directly (running their full body) without the
    // framework lifecycle plumbing.
    private fun drive(activity: MainActivity, method: String) {
        val m = MainActivity::class.java.getDeclaredMethod(method)
        m.isAccessible = true
        m.invoke(activity)
    }

    /** The activity's real [LumiViewModel], via reflection. */
    private fun viewModelOf(activity: MainActivity): LumiViewModel {
        val field = MainActivity::class.java
            .getDeclaredField("viewModel")
            .also { it.isAccessible = true }
        return field.get(activity) as LumiViewModel
    }

    /** Waits for the asynchronous Room round trip to make the setting observable. */
    private fun awaitKeepScreenOn(activity: MainActivity, expected: Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (viewModelOf(activity).keepScreenOn.value == expected) return
        }
        fail("the keep-screen-on setting must become $expected")
    }

    @Test
    fun defaultOff_screenFollowsSystemTimeout() {
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .start()
            .get()

        // No keep_screen_on row in the database: the setting is off by
        // default, so the resume never sets the flag...
        assertFalse(
            "FLAG_KEEP_SCREEN_ON must not be set before onResume",
            keepScreenOn(activity),
        )
        drive(activity, "onResume")
        assertFalse(
            "with the setting off, onResume must not set FLAG_KEEP_SCREEN_ON",
            keepScreenOn(activity),
        )

        // ...and the pause clears whatever is there (nothing here).
        drive(activity, "onPause")
        assertFalse(
            "FLAG_KEEP_SCREEN_ON must not be set after onPause",
            keepScreenOn(activity),
        )
    }

    @Test
    fun settingOn_setOnResume_clearedOnPause() {
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .start()
            .get()

        // Enable the setting through the view model (the same path the
        // settings checkbox drives) and wait for the Room round trip.
        viewModelOf(activity).setKeepScreenOn(true)
        awaitKeepScreenOn(activity, true)

        // Normalize from the (started) state, then exercise the resume /
        // pause lifecycle: on keeps the screen on for the activity's
        // lifetime, off releases it.
        drive(activity, "onPause")
        assertFalse(
            "onPause must clear FLAG_KEEP_SCREEN_ON",
            keepScreenOn(activity),
        )

        drive(activity, "onResume")
        assertTrue(
            "with the setting on, onResume must set FLAG_KEEP_SCREEN_ON",
            keepScreenOn(activity),
        )

        drive(activity, "onPause")
        assertFalse(
            "onPause must clear FLAG_KEEP_SCREEN_ON again",
            keepScreenOn(activity),
        )

        // Re-resume restores it: the screen stays on while the app is open.
        drive(activity, "onResume")
        assertTrue(
            "re-resume must set FLAG_KEEP_SCREEN_ON again",
            keepScreenOn(activity),
        )
    }

    @Test
    fun settingChangedWhileStarted_appliesAndClearsLive() {
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .start()
            .get()

        // The activity is started (foreground), so toggling the setting must
        // apply the flag without waiting for a resume...
        viewModelOf(activity).setKeepScreenOn(true)
        awaitKeepScreenOn(activity, true)
        assertTrue(
            "enabling the setting while started must set FLAG_KEEP_SCREEN_ON",
            keepScreenOn(activity),
        )

        // ...and unchecking it must release the flag without a pause.
        viewModelOf(activity).setKeepScreenOn(false)
        awaitKeepScreenOn(activity, false)
        assertFalse(
            "disabling the setting while started must clear FLAG_KEEP_SCREEN_ON",
            keepScreenOn(activity),
        )
    }
}
