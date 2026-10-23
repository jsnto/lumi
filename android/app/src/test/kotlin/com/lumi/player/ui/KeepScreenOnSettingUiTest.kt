package com.lumi.player.ui

import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.lumi.player.LumiApp
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import com.lumi.player.ui.testutil.viewModelOf
import com.lumi.player.ui.testutil.openAdvancedPage

/**
 * Verifies the keep-screen-on settings checkbox on the Advanced options
 * page (the row moved there from the settings menu, keeping its look and
 * test tag): the page shows the row unchecked by default (off is the
 * default - the screen follows the system timeout while the app is open),
 * and toggling it persists the choice through the same settings mechanism
 * as the other settings.
 *
 * The pruned material3 [androidx.compose.material3.Checkbox] exposes its state
 * as the [SemanticsProperties.ToggleableState] semantic property (not the
 * upstream `Selected` one), so the checkbox node is matched by its test tag
 * plus that property value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeepScreenOnSettingUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        markSetupCompleteForTests()
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    /** The Advanced options page's keep-screen-on checkbox node in a given state. */
    private fun checkboxIn(state: ToggleableState): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "keep-screen-on")
            .and(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state))

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
    fun advancedPageShowsKeepScreenOnUncheckedByDefault() {
        val activity = compose.openAdvancedPage()

        compose.onNodeWithText("Keep screen on").assertIsDisplayed()
        compose.onNodeWithText("Keep the display on while the app is open.").assertExists()
        // The checkbox is unchecked by default (off: the screen follows the
        // system timeout while the app is open).
        compose.onNode(checkboxIn(ToggleableState.Off)).assertExists()
        compose.onNode(checkboxIn(ToggleableState.On)).assertDoesNotExist()
        // The setting starts unset in the database, so the view model
        // reports off.
        Shadows.shadowOf(Looper.getMainLooper())
            .idleFor(500, TimeUnit.MILLISECONDS)
        assertTrue(
            "the keep-screen-on setting must default to off",
            !viewModelOf(activity).keepScreenOn.value,
        )
    }

    @Test
    fun checkingTheCheckboxPersistsTheSetting() {
        val activity = compose.openAdvancedPage()

        compose.onNode(checkboxIn(ToggleableState.Off)).performClick()
        // The click drives the view model, which persists to the database;
        // the observable value (a Room flow) landing on true proves the
        // round trip.
        awaitKeepScreenOn(activity, true)
        compose.waitForIdle()
        compose.onNode(checkboxIn(ToggleableState.On)).assertExists()
        compose.onNode(checkboxIn(ToggleableState.Off)).assertDoesNotExist()
    }

    @Test
    fun uncheckingTheCheckboxRestoresTheDefault() {
        val activity = compose.openAdvancedPage()

        // Check it, then uncheck it: the choice persists as off again.
        compose.onNode(checkboxIn(ToggleableState.Off)).performClick()
        awaitKeepScreenOn(activity, true)
        compose.waitForIdle()
        compose.onNode(checkboxIn(ToggleableState.On)).performClick()
        awaitKeepScreenOn(activity, false)
        compose.waitForIdle()
        compose.onNode(checkboxIn(ToggleableState.Off)).assertExists()
        compose.onNode(checkboxIn(ToggleableState.On)).assertDoesNotExist()
    }
}
