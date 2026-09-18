package com.lumi.player.ui

import android.app.Activity
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lumi.player.LumiApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Behavioral tests for the NFC-availability prompt: [MainActivity] checks the
 * radio on launch/resume and the UI surfaces the right dialog. Driven through
 * the real activity + Compose content under Robolectric, with
 * [ShadowNfcAdapterForTest] standing in for the NFC radio via [NfcTestState].
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NfcPromptUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun resetNfcState() {
        NfcTestState.reset()
        NfcTestState.adapterExists = true
    }

    private fun startMainActivity(): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java).setup()

    @Test
    fun radioOff_promptIsShownOnLaunch() {
        NfcTestState.radioEnabled = false
        startMainActivity().get()
        compose.waitForIdle()
        compose.onNodeWithText("NFC is off").assertIsDisplayed()
        compose.onNodeWithText("lumi needs NFC to read tags. Turn it on to use the app.")
            .assertIsDisplayed()
        compose.onNodeWithText("Enable NFC").assertIsDisplayed()
        compose.onNodeWithText("Later").assertIsDisplayed()
        compose.onNodeWithText("NFC not available").assertDoesNotExist()
    }

    @Test
    fun noNfcHardware_promptIsShown() {
        NfcTestState.adapterExists = false
        startMainActivity().get()
        compose.waitForIdle()
        compose.onNodeWithText("NFC not available").assertIsDisplayed()
        compose.onNodeWithText("This device doesn't have NFC hardware, so lumi can't read tags.")
            .assertIsDisplayed()
        compose.onNodeWithText("OK").assertIsDisplayed()
        compose.onNodeWithText("NFC is off").assertDoesNotExist()
    }

    @Test
    fun radioOff_tapEnableNfc_opensSystemNfcSettings() {
        NfcTestState.radioEnabled = false
        val activity = startMainActivity().get()
        compose.waitForIdle()
        compose.onNodeWithText("Enable NFC").performClick()
        compose.waitForIdle()
        val started = shadowOf(activity as Activity).nextStartedActivity
        assertNotNull("tapping Enable NFC must launch the NFC settings", started)
        assertEquals(Settings.ACTION_NFC_SETTINGS, started!!.action)
    }

    @Test
    fun radioOff_tapLater_dismissesPrompt() {
        NfcTestState.radioEnabled = false
        startMainActivity().get()
        compose.waitForIdle()
        compose.onNodeWithText("NFC is off").assertIsDisplayed()
        compose.onNodeWithText("Later").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("NFC is off").assertDoesNotExist()
    }

    /**
     * Regression: after the user enables the radio in the system settings and
     * returns, the stale "NFC is off" prompt state must be cleared on resume
     * instead of lingering. The dialog is additionally gated by [nfcDisabled],
     * so the stale flag is invisible until a recomposition - the regression
     * therefore asserts on the prompt state itself. Fails against the pre-fix
     * [MainActivity] (which only ever raised the flag) and passes with the fix
     * that re-checks and syncs it.
     */
    @Test
    fun radioOn_afterReturningFromSettings_noPromptIsShown() {
        NfcTestState.radioEnabled = false
        val controller = startMainActivity()
        val activity = controller.get()
        compose.waitForIdle()
        compose.onNodeWithText("NFC is off").assertIsDisplayed()
        assertTrue("prompt flag raised while the radio is off",
            promptState(activity))
        // User enables the radio in the system settings and comes back:
        // the app goes to the background, the radio turns on, the app resumes.
        controller.pause()
        NfcTestState.radioEnabled = true
        controller.resume()
        compose.waitForIdle()
        compose.onNodeWithText("NFC is off").assertDoesNotExist()
        assertFalse("stale prompt flag must be cleared once the radio is on",
            promptState(activity))
        // And it stays cleared across further resume cycles.
        controller.pause()
        controller.resume()
        compose.waitForIdle()
        compose.onNodeWithText("NFC is off").assertDoesNotExist()
        compose.onNodeWithText("NFC not available").assertDoesNotExist()
        assertFalse("prompt flag must stay cleared on later resumes",
            promptState(activity))
    }

    /** The internal [MainActivity.showNfcPrompt] Compose state, via reflection. */
    private fun promptState(activity: MainActivity): Boolean {
        val state = org.robolectric.util.ReflectionHelpers.getField(
            activity,
            "showNfcPrompt\$delegate",
        ) as androidx.compose.runtime.MutableState<Boolean>
        return state.value
    }
}
