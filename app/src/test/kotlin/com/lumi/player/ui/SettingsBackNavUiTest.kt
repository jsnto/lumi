package com.lumi.player.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Behavioral coverage for the settings navigation back gesture: system back
 * (driven through the real [androidx.activity.OnBackPressedDispatcher])
 * unwinds one settings sub-screen at a time and leaves the menu at the exact
 * scroll position it was left at. The screen is configured short (300dp) so
 * the settings menu list genuinely overflows and the scroll position is a
 * meaningful state to check, instead of fitting on one screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = com.lumi.player.LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
    qualifiers = "w320dp-h300dp",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsBackNavUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private fun openSettings(): MainActivity {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        return activity
    }

    private fun systemBack(activity: MainActivity) {
        activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
    }

    @Test
    fun menuScrollPositionSurvivesLeavingForASubScreen() {
        val activity = openSettings()

        // On the short screen the menu list overflows: scroll it down until
        // the footer (and thus the bottom rows) is visible and the leading
        // row has scrolled out of view.
        compose.onNodeWithText("lumi v0.1.0").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("lumi v0.1.0").assertIsDisplayed()
        compose.onNodeWithText("Play mode").assertIsNotDisplayed()

        // Leave the menu for the link sub-screen...
        compose.onNodeWithText("Tag to content").performScrollTo()
        compose.onNodeWithText("Tag to content").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Scan a tag to view its song list").assertIsDisplayed()

        // ...and come back via the system back gesture.
        systemBack(activity)

        // The menu is back at the exact scroll position: the footer is still
        // visible and the leading row is still scrolled out of view - the
        // list was not reset to the top.
        compose.onNodeWithText("lumi v0.1.0").assertIsDisplayed()
        compose.onNodeWithText("Play mode").assertIsNotDisplayed()
        // The menu itself is intact (all setting buttons, not just the list).
        compose.onNodeWithText("NFC tags").assertExists()
        compose.onNodeWithText("Content").assertExists()
    }

    @Test
    fun systemBackUnwindsTheSubScreenAndOnlyThenTheMenu() {
        val activity = openSettings()

        // Open the tags sub-screen from the (top) of the menu.
        compose.onNodeWithText("NFC tags").performClick()
        compose.waitForIdle()
        // The "+ Register a tag" pill is now the + button on the section's
        // title row (found by content description); on the small test screen
        // it may sit below the viewport, so existence is the layout
        // assertion.
        compose.onNodeWithContentDescription("Register a tag").assertExists()

        // The first back unwinds the sub-screen to the menu; it must NOT pop
        // settings away.
        systemBack(activity)
        compose.onNodeWithText("Play mode").assertIsDisplayed()
        compose.onNodeWithText("NFC tags").assertIsDisplayed()
        compose.onNodeWithContentDescription("Register a tag").assertDoesNotExist()

        // The second back leaves the menu entirely and returns to the
        // player screen.
        systemBack(activity)
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        compose.onNodeWithText("Play mode").assertDoesNotExist()
    }
}
