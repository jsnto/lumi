package com.lumi.player.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import com.lumi.player.data.FolderChild
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.lumi.player.ui.testutil.waitForText
import com.lumi.player.ui.testutil.app
import com.lumi.player.ui.testutil.dbAndGet
import com.lumi.player.ui.testutil.viewModelOf
import com.lumi.player.ui.testutil.openSettings
import com.lumi.player.ui.testutil.openTagPage
import com.lumi.player.ui.testutil.openFolderPage
import com.lumi.player.ui.testutil.openAdvancedPage
import com.lumi.player.ui.testutil.openContentPane
import com.lumi.player.ui.testutil.clickFolderRow
import com.lumi.player.ui.testutil.clickSongRow

/**
 * Behavioral coverage for the settings navigation back gesture: system back
 * (driven through the real [androidx.activity.OnBackPressedDispatcher]) and
 * the in-pane back arrow unwind one level at a time - the tag detail page is
 * a child of the NFC tags pane, the folder detail page a child of the
 * content pane, the audio level page a child of the Advanced options page,
 * and the song profile a child of the pane the song was
 * opened FROM (the folder's own page when opened from that folder's tree,
 * the content pane when opened from there), so each one's back lands on
 * the list it lives under and only the next back reaches the menu - and the
 * menu is left at the exact scroll position it was left at. The screen is
 * configured short (300dp) so the settings menu list genuinely overflows
 * and the scroll position is a meaningful state to check, instead of
 * fitting on one screen.
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
        markSetupCompleteForTests()
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private fun systemBack(activity: MainActivity) {
        activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
    }

    @Test
    fun menuScrollPositionSurvivesLeavingForASubScreen() {
        val activity = compose.openSettings()

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
        // view - the list was not reset to the top. The Advanced options row
        // (the menu's last row - the keep-screen-on row moved into the
        // Advanced options page) is the position anchor: it is comfortably
        // visible at the restored position and fully clipped at the top,
        // while the very bottom (footer) sits within a few dp of the viewport
        // edge on the small test screen, where its visibility is a marginal
        // assertion.
        compose.onNodeWithText("Advanced options").assertIsDisplayed()
        compose.onNodeWithText("Play mode").assertIsNotDisplayed()
        // The menu itself is intact (all setting buttons, not just the list).
        compose.onNodeWithText("NFC tags").assertExists()
        compose.onNodeWithText("Content").assertExists()
    }

    @Test
    fun systemBackUnwindsTheSubScreenAndOnlyThenTheMenu() {
        val activity = compose.openSettings()

        // Open the tags sub-screen from the (top) of the menu (the menu
        // overflows the small test screen, so scroll the row into view
        // first - the click only lands on a laid-out row).
        compose.onNodeWithText("NFC tags").performScrollTo()
        compose.waitForIdle()
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

        val activity = compose.openTagPage("Porch")
        // The detail page is showing.
        compose.waitForText("Tag details")

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

        compose.openTagPage("Porch")
        compose.waitForText("Tag details")

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

        val activity = compose.openFolderPage("Music")
        // The detail page is showing.
        compose.waitForText("Folder details")

        // The first system back unwinds the detail page one level, to the
        // content pane it lives under - not the menu, and not out of
        // settings (the folder's row is back).
        systemBack(activity)
        compose.onNodeWithText("Folder details").assertDoesNotExist()
        compose.onNodeWithText("Music").assertExists()
        compose.onNodeWithText("Tap to view its songs").assertExists()

        // The second system back leaves the content pane for the menu
        // (the menu list keeps its scroll position, so bring the leading
        // row back into view before asserting on it).
        systemBack(activity)
        compose.onNodeWithText("Play mode").performScrollTo()
        compose.waitForIdle()
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

        compose.openFolderPage("Music")
        compose.waitForText("Folder details")

        // The pane's own back arrow (the top bar) lands on the content
        // pane, the detail page's parent pane - not the menu.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Folder details").assertDoesNotExist()
        compose.onNodeWithText("Tap to view its songs").assertExists()
        // The menu is not showing either.
        compose.onNodeWithText("Play mode").assertDoesNotExist()
    }

    // ---------- the song profile unwinds to the pane the song was opened from ----------

    /**
     * Opens the [label] folder's page (through the faked walk seam, since
     * the SAF provider can't run under Robolectric) and the [song] song's
     * profile from the folder page's song row. The folder-imported song
     * keeps out of the content pane's individual list, so the folder's
     * page is the only place its row lives.
     */
    private fun openSongProfileFromFolderPage(folderLabel: String, songLabel: String, songPayload: String): MainActivity {
        dbAndGet {
            val song = checkNotNull(app().repository.addEntryUnique(EntryType.AUDIO, songLabel, songPayload))
            val folder = app().repository.rememberFolder(FOLDER_URI, folderLabel)
            app().repository.markEntriesFromFolder(folder.id, setOf(song.payload))
        }
        val activity = compose.openContentPane()
        // The page's walk goes through the faked seam (it lists the stored
        // row's payload, so the song's row shows on the page).
        viewModelOf(activity).folderChildren = {
            listOf(FolderChild("$songLabel.mp3", isDirectory = false, childUri = songPayload))
        }
        compose.clickFolderRow(folderLabel)
        compose.clickSongRow(songLabel)
        compose.waitForText("Song details")
        return activity
    }

    @Test
    fun systemBackFromASongOpenedOnTheFolderPageUnwindsToTheFolderPageAndOnlyThenTheContentPane() {
        val activity = openSongProfileFromFolderPage("Music", "Folder song", "content://docs/one")

        // The first system back unwinds the profile one level, to the pane
        // the song was opened FROM - the folder's own page - not the
        // content pane, and not out of settings (the page's details and the
        // song's row are back).
        systemBack(activity)
        compose.onNodeWithText("Song details").assertDoesNotExist()
        compose.onNodeWithText("Folder details").assertExists()
        compose.onNodeWithText("Folder song").assertExists()

        // The second back leaves the folder page for the content pane...
        systemBack(activity)
        compose.onNodeWithText("Folder details").assertDoesNotExist()
        compose.onNodeWithText("Tap to view its songs").assertExists()

        // ...and the third leaves the content pane for the menu (the menu
        // list keeps its scroll position, so bring the leading row back
        // into view before asserting on it).
        systemBack(activity)
        compose.onNodeWithText("Play mode").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("Play mode").assertIsDisplayed()
    }

    @Test
    fun theInPaneBackArrowFromASongOpenedOnTheFolderPageLandsOnTheFolderPage() {
        val activity = openSongProfileFromFolderPage("Music", "Folder song", "content://docs/one")

        // The pane's own back arrow (the top bar) lands on the folder's
        // own page - the pane the song was opened from - not the content
        // pane.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Song details").assertDoesNotExist()
        compose.onNodeWithText("Folder details").assertExists()
        // The content pane is not showing either.
        compose.onNodeWithText("Tap to view its songs").assertDoesNotExist()
        // The menu is not showing either.
        compose.onNodeWithText("Play mode").assertDoesNotExist()
    }

    @Test
    fun systemBackFromASongOpenedInTheContentPaneUnwindsToTheContentPane() {
        dbAndGet { app().repository.addEntryUnique(EntryType.AUDIO, "Individual song", "content://docs/solo") }

        val activity = compose.openSettings()
        compose.onNodeWithText("Content").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("Content").performClick()
        compose.waitForIdle()
        compose.waitForText("Individual song")
        compose.onNodeWithText("Individual song").performScrollTo()
        compose.onNodeWithText("Individual song").performClick()
        compose.waitForIdle()
        compose.waitForText("Song details")

        // The song was opened from the content pane's own row, so its back
        // lands on the content pane - the profile's default opener - and
        // only the next back reaches the menu.
        systemBack(activity)
        compose.onNodeWithText("Song details").assertDoesNotExist()
        compose.onNodeWithText("Individual content").assertExists()
        compose.onNodeWithText("Tap to view its songs").assertDoesNotExist()
        systemBack(activity)
        // The menu list keeps its scroll position: bring the leading row
        // back into view before asserting on it.
        compose.onNodeWithText("Play mode").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("Play mode").assertIsDisplayed()
    }

    // ---------- the audio level page unwinds to the Advanced options page, not the menu ----------

    /** Opens settings, the Advanced options page, and the audio level
     *  sub-screen (the audio level setting's home since the reorganization;
     *  it is no longer a top-level menu row). */
    /**
     * Opens the audio level page (settings, the Advanced options page, the
     * page's own last row). The row sits below the fold on the short test
     * screen, so it scrolls into view before the click (a zero-bounds click
     * would land at the screen origin).
     */
    private fun openAudioLevelPage(): MainActivity {
        val activity = compose.openAdvancedPage()
        compose.onNodeWithText("Audio level").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("Audio level").performClick()
        compose.waitForIdle()
        return activity
    }

    @Test
    fun systemBackFromTheAudioLevelPageUnwindsToTheAdvancedPageAndOnlyThenTheMenu() {
        val activity = openAudioLevelPage()
        // The sub-screen is showing (the Normalize action is the pane's
        // own).
        compose.onNodeWithText("Normalize now").assertExists()

        // The first system back unwinds the page one level, to the Advanced
        // options page it lives under - not the menu, and not out of
        // settings (the page's own sections are back).
        systemBack(activity)
        compose.onNodeWithText("Normalize now").assertDoesNotExist()
        compose.onNodeWithText("Auto-lock after idle").assertExists()
        compose.onNodeWithText("Audio level").assertExists()

        // The second system back leaves the Advanced options page for the
        // menu (the menu list keeps its scroll position, so bring the
        // leading row back into view before asserting on it).
        systemBack(activity)
        compose.onNodeWithText("Play mode").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("Play mode").assertIsDisplayed()
        compose.onNodeWithText("Auto-lock after idle").assertDoesNotExist()

        // The third back leaves the menu entirely and returns to the player
        // screen (the two-level unwind, then the host's back handling).
        systemBack(activity)
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        compose.onNodeWithText("Play mode").assertDoesNotExist()
    }

    @Test
    fun theInPaneBackArrowFromTheAudioLevelPageLandsOnTheAdvancedPage() {
        openAudioLevelPage()
        compose.onNodeWithText("Normalize now").assertExists()

        // The pane's own back arrow (the top bar) lands on the Advanced
        // options page, the page's parent - not the menu.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Normalize now").assertDoesNotExist()
        compose.onNodeWithText("Auto-lock after idle").assertExists()
        // The menu is not showing either.
        compose.onNodeWithText("Play mode").assertDoesNotExist()
    }

    companion object {
        const val TAG_UID = "04:CA:FE:99:99"
        const val FOLDER_URI = "content://docs/tree/primary%3AMusic"
    }
}
