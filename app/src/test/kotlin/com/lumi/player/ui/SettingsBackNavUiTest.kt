package com.lumi.player.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import kotlinx.coroutines.runBlocking
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
 * Behavioral coverage for the settings navigation back gesture: system back
 * (driven through the real [androidx.activity.OnBackPressedDispatcher]) and
 * the in-pane back arrow unwind one level at a time - the tag detail page is
 * a child of the NFC tags pane and the folder detail page a child of the
 * content pane, so each one's back lands on the list it lives under and
 * only the next back reaches the menu - and the menu is left at the exact
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

    private fun app(): LumiApp = ApplicationProvider.getApplicationContext()

    /** Runs a suspend block on the app's database and returns its result. */
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
            ShadowLooper.idleMainLooper()
            compose.waitForIdle()
            if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(20)
        }
        compose.onNodeWithText(text).assertExists()
    }

    /**
     * Opens settings, the NFC tags pane, and the [name] tag's detail page
     * (the tag row arrives through an async Room round trip, so wait for it
     * before clicking).
     */
    private fun openTagPage(name: String): MainActivity {
        val activity = openSettings()
        compose.onNodeWithText("NFC tags").performClick()
        compose.waitForIdle()
        waitForText(name)
        compose.onNodeWithText(name).performScrollTo()
        compose.onNodeWithText(name).performClick()
        compose.waitForIdle()
        return activity
    }

    /**
     * Opens settings, the content pane, and the [label] folder's detail
     * page (the folder row arrives through an async Room round trip, so
     * wait for it before clicking).
     */
    private fun openFolderPage(label: String): MainActivity {
        val activity = openSettings()
        compose.onNodeWithText("Content").performClick()
        compose.waitForIdle()
        waitForText(label)
        compose.onNodeWithText(label).performScrollTo()
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
        return activity
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

        // Leave the menu for the content sub-screen... (the individual
        // content section may sit below the viewport on the small test
        // screen, so existence is the layout assertion)
        compose.onNodeWithText("Content").performScrollTo()
        compose.onNodeWithText("Content").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Nothing here yet. Add a song.").assertExists()

        // ...and come back via the system back gesture.
        systemBack(activity)

        // The menu is back at the exact scroll position: the bottom rows are
        // still where they were and the leading row is still scrolled out of
        // view - the list was not reset to the top. The keep-screen-on row is
        // the position anchor: it is comfortably visible at the restored
        // position and fully clipped at the top, while the very bottom
        // (footer) sits within a few dp of the viewport edge on the small
        // test screen, where its visibility is a marginal assertion.
        compose.onNodeWithText("Keep screen on").assertIsDisplayed()
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
        // The pane's scan action is on screen (on the small test screen it
        // may sit above or below the viewport, so existence is the layout
        // assertion); the section has no + button.
        compose.onNodeWithText("Scan NFC").assertExists()
        compose.onNodeWithContentDescription("Register a tag").assertDoesNotExist()

        // The first back unwinds the sub-screen to the menu; it must NOT pop
        // settings away.
        systemBack(activity)
        compose.onNodeWithText("Play mode").assertIsDisplayed()
        compose.onNodeWithText("NFC tags").assertIsDisplayed()
        compose.onNodeWithText("Scan NFC").assertDoesNotExist()

        // The second back leaves the menu entirely and returns to the
        // player screen.
        systemBack(activity)
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        compose.onNodeWithText("Play mode").assertDoesNotExist()
    }

    // ---------- the tag detail page unwinds to the NFC tags list, not the menu ----------

    @Test
    fun systemBackFromTheTagDetailPageUnwindsToTheTagsListAndOnlyThenTheMenu() {
        dbAndGet { app().repository.addTag(TAG_UID, "Porch") }

        val activity = openTagPage("Porch")
        // The detail page is showing.
        waitForText("Tag details")

        // The first system back unwinds the detail page one level, to the
        // NFC tags list it lives under - not the menu, and not out of
        // settings (the list's scan action and the tag's row are back).
        systemBack(activity)
        compose.onNodeWithText("Tag details").assertDoesNotExist()
        compose.onNodeWithText("Scan NFC").assertExists()
        compose.onNodeWithText(TAG_UID).assertExists()

        // The second system back leaves the tags list for the menu.
        systemBack(activity)
        compose.onNodeWithText("Play mode").assertIsDisplayed()
        compose.onNodeWithText("Scan NFC").assertDoesNotExist()

        // The third back leaves the menu entirely and returns to the player
        // screen (the two-level unwind, then the host's back handling).
        systemBack(activity)
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        compose.onNodeWithText("Play mode").assertDoesNotExist()
    }

    @Test
    fun theInPaneBackArrowFromTheTagDetailPageLandsOnTheTagsList() {
        dbAndGet { app().repository.addTag(TAG_UID, "Porch") }

        openTagPage("Porch")
        waitForText("Tag details")

        // The pane's own back arrow (the top bar) lands on the NFC tags
        // list, the detail page's parent pane - not the menu.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Tag details").assertDoesNotExist()
        compose.onNodeWithText("Scan NFC").assertExists()
        compose.onNodeWithText(TAG_UID).assertExists()
        // The menu is not showing either.
        compose.onNodeWithText("Play mode").assertDoesNotExist()
    }

    // ---------- the folder detail page unwinds to the content pane, not the menu ----------

    @Test
    fun systemBackFromTheFolderDetailPageUnwindsToTheContentPaneAndOnlyThenTheMenu() {
        dbAndGet { app().repository.rememberFolder(FOLDER_URI, "Music") }

        val activity = openFolderPage("Music")
        // The detail page is showing.
        waitForText("Folder details")

        // The first system back unwinds the detail page one level, to the
        // content pane it lives under - not the menu, and not out of
        // settings (the folder's row is back).
        systemBack(activity)
        compose.onNodeWithText("Folder details").assertDoesNotExist()
        compose.onNodeWithText("Music").assertExists()
        compose.onNodeWithText("Tap to view its songs").assertExists()

        // The second system back leaves the content pane for the menu.
        systemBack(activity)
        compose.onNodeWithText("Play mode").assertIsDisplayed()
        compose.onNodeWithText("Tap to view its songs").assertDoesNotExist()

        // The third back leaves the menu entirely and returns to the player
        // screen (the two-level unwind, then the host's back handling).
        systemBack(activity)
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        compose.onNodeWithText("Play mode").assertDoesNotExist()
    }

    @Test
    fun theInPaneBackArrowFromTheFolderDetailPageLandsOnTheContentPane() {
        dbAndGet { app().repository.rememberFolder(FOLDER_URI, "Music") }

        openFolderPage("Music")
        waitForText("Folder details")

        // The pane's own back arrow (the top bar) lands on the content
        // pane, the detail page's parent pane - not the menu.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Folder details").assertDoesNotExist()
        compose.onNodeWithText("Tap to view its songs").assertExists()
        // The menu is not showing either.
        compose.onNodeWithText("Play mode").assertDoesNotExist()
    }

    companion object {
        const val TAG_UID = "04:CA:FE:99:99"
        const val FOLDER_URI = "content://docs/tree/primary%3AMusic"
    }
}
