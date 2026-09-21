package com.lumi.player.ui

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
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
 * Behavioral tests for the settings menu (polex-style): the settings screen
 * is a list of buttons, one per setting (play mode, NFC tags, content);
 * clicking a row navigates into that setting's own configuration screen, and
 * the back arrow / system back unwind it back to the menu (from the menu,
 * to the player screen). The NFC tags pane rows offer no row-level delete -
 * deleting a tag happens from the tag detail page's danger zone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsMenuUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private fun openSettings(): MainActivity {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
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
    fun settingsMenuListsEachSettingAsButton() {
        openSettings()

        // Every setting is a button on the settings screen itself. The
        // "Tag to content" entry is gone: linking songs to a tag happens
        // from the tag detail page's add-song action, not from the menu.
        compose.onNodeWithText("Play mode").assertIsDisplayed()
        compose.onNodeWithText("NFC tags").assertExists()
        compose.onNodeWithText("Content").assertExists()
        compose.onNodeWithText("Tag to content").assertDoesNotExist()

        // The menu only lists the settings - none of the configuration
        // content is on it (that lives in the sub-screens).
        compose.onNodeWithText("Keeps playing until you tap a different tag.")
            .assertDoesNotExist()
        compose.onNodeWithText("In proximity mode, how many seconds a tag may go ", substring = true)
            .assertDoesNotExist()
        compose.onNodeWithContentDescription("Register a tag").assertDoesNotExist()
        compose.onNodeWithContentDescription("Add content").assertDoesNotExist()
        compose.onNodeWithText("Scan a tag to view its song list").assertDoesNotExist()
        compose.onNodeWithText("Nothing here yet. Add a song.").assertDoesNotExist()
    }

    @Test
    fun eachMenuButtonOpensItsConfigurationScreen() {
        val activity = openSettings()

        // Play mode: the sub-screen offers the mode selection.
        compose.onNodeWithText("Play mode").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Keeps playing until you tap a different tag.")
            .assertIsDisplayed()
        compose.onNodeWithText("Plays while the tag stays near; pauses when it's removed and resumes when you read it again.")
            .assertExists()
        systemBack(activity)
        compose.onNodeWithText("Play mode").assertExists()
        compose.onNodeWithText("Keeps playing until you tap a different tag.")
            .assertDoesNotExist()

        // NFC tags: the sub-screen offers the scan action (a prominent
        // button above the list, not a row of it) and the (empty) list;
        // the section's title row has no + button.
        compose.onNodeWithText("NFC tags").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Scan NFC").assertExists()
        compose.onNodeWithContentDescription("Register a tag").assertDoesNotExist()
        compose.onNodeWithText("No tags yet. Scan a tag to add it.")
            .assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("NFC tags").assertExists()
        compose.onNodeWithText("Scan NFC").assertDoesNotExist()

        // Content: the sub-screen offers content management.
        compose.onNodeWithText("Content").performClick()
        compose.waitForIdle()
        // The "+ Add content" pill is now the + button on the section's title
        // row (found by content description); existence is the layout
        // assertion (it may sit below the viewport).
        compose.onNodeWithContentDescription("Add content").assertExists()
        compose.onNodeWithText("Nothing here yet. Add a song.").assertExists()
        systemBack(activity)
        compose.onNodeWithText("Content").assertExists()
        compose.onNodeWithContentDescription("Add content").assertDoesNotExist()
    }

    @Test
    fun systemBackFromMenuReturnsToThePlayerScreen() {
        val activity = openSettings()
        compose.onNodeWithText("Play mode").assertIsDisplayed()
        systemBack(activity)
        // The player screen's top bar is back; the settings menu is gone.
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        compose.onNodeWithText("Play mode").assertDoesNotExist()
        compose.onNodeWithText("NFC tags").assertDoesNotExist()
    }

    @Test
    fun nfcTagRowsHaveNoDeleteButton_andTheRowOpensTheTagPage() {
        // Seed one tag so the pane has a row to check.
        val app = ApplicationProvider.getApplicationContext<LumiApp>()
        val thread = Thread {
            runBlocking { app.repository.addTag("04:CA:FE:AA:00", "Porch") }
        }
        thread.start()
        thread.join()
        ShadowLooper.idleMainLooper()

        openSettings()
        compose.onNodeWithText("NFC tags").performClick()
        compose.waitForIdle()

        // The tag row shows the name and UID and offers no row-level
        // delete: deleting a tag happens from the tag detail page's
        // danger zone, not from the list.
        compose.onNodeWithText("Porch").assertIsDisplayed()
        compose.onNodeWithText("04:CA:FE:AA:00").assertExists()
        compose.onNodeWithContentDescription("Delete tag").assertDoesNotExist()

        // Tapping the row body still opens the tag's detail page (the
        // entry point the removed delete icon used to sit next to).
        compose.onNodeWithText("Porch").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Tag details").assertExists()
        // On the page too, the only delete is the danger zone's own
        // button (no content description "Delete tag" anywhere).
        compose.onNodeWithContentDescription("Delete tag").assertDoesNotExist()
        compose.onNodeWithText("Danger zone").assertExists()
    }
}
