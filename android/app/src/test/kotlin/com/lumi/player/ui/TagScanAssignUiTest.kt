package com.lumi.player.ui

import android.nfc.Tag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.isDisplayed
import androidx.lifecycle.viewModelScope
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import com.lumi.player.data.SongMetadata
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import com.lumi.player.ui.testutil.idleUntil
import com.lumi.player.ui.testutil.waitForText
import com.lumi.player.ui.testutil.sendTagWithUid
import com.lumi.player.ui.testutil.app
import com.lumi.player.ui.testutil.dbAndGet
import com.lumi.player.ui.testutil.viewModelOf
import com.lumi.player.ui.testutil.setupMainActivity

/**
 * The song profile's second assign-to-tag option - "Scan a tag to assign":
 * it arms the app's existing unified scan (the foreground-dispatch read
 * path, no new read plumbing) targeting the song. A read of a registered
 * tag links the song to it through the existing link mechanism (a tag
 * that already plays the song is reported as a no-op - the picker's own
 * language, no duplicate link), and a read of an unregistered tag goes
 * through the registration flow first (capture, naming dialog) and links
 * the song once the tag is saved. The option stays available with no
 * registered tags (scanning a new tag registers it and then assigns).
 *
 * The page's "Assigned tags" section is also pinned here: it lists the
 * tags the song is currently assigned to and updates live - a fresh link
 * joins the list, and the empty state reads plainly.
 *
 * Tag reads are driven the way the NFC tests drive them: reflectively-
 * built [Tag]s delivered as EXTRA_TAG intents via [ActivityController.newIntent],
 * the same shape the system's foreground dispatch delivers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
    qualifiers = "w412dp-h900dp",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TagScanAssignUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        markSetupCompleteForTests()
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private fun eventsOf(viewModel: LumiViewModel): MutableList<String> {
        val events = mutableListOf<String>()
        viewModel.viewModelScope.launch { viewModel.uiEvents.collect { e: String -> events += e } }
        return events
    }

    /** Stores one song, opens the content pane, and opens its profile. */
    private fun openSongProfile(label: String = "Second song") {
        // The top bar and the settings menu settle through async Room round
        // trips: wait for the merged tree to have each node before clicking
        // it (a click racing a mid-recomposition top bar misses the node).
        compose.idleUntil({ compose.onNodeWithContentDescription("Settings").isDisplayed() })
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.idleUntil({ compose.onAllNodesWithText("Content").fetchSemanticsNodes().isNotEmpty() })
        compose.onNodeWithText("Content").performClick()
        compose.waitForIdle()
        dbAndGet { app().repository.addEntryUnique(EntryType.AUDIO, label, "content://docs/$label") }
        compose.idleUntil({ compose.onNodeWithText(label).isDisplayed() }, message = "the song's row")
        compose.onNodeWithText(label).performClick()
        compose.idleUntil({ compose.onNodeWithText("Song details").isDisplayed() }, message = "the profile page")
        compose.waitForIdle()
    }

    /** The tags that currently link the stored entry. */
    private fun linkedTagNames(): List<String> {
        val entry = dbAndGet { app().repository.observeEntries().first() }.single()
        return dbAndGet { app().repository.observeTagsForEntry(entry.id).first() }.map { it.name }
    }

    // ---- the assigned-tags section ----

    @Test
    fun theAssignedTagsSectionIsEmptyAtFirstAndFillsLive() {
        val controller = compose.setupMainActivity()
        val viewModel = viewModelOf(controller.get())
        viewModel.metadataReader = { SongMetadata() }
        openSongProfile()

        // The page lists the tags the song is currently assigned to -
        // none yet, with a plain empty state.
        compose.onNodeWithText("Assigned tags").assertExists()
        compose.onNodeWithText("This song is on no tags yet.").assertExists()

        // A link landing while the page is open (the picker's own
        // mechanism) joins the section live - no page reload needed.
        val entry = dbAndGet { app().repository.observeEntries().first() }.single()
        val tag = dbAndGet { app().repository.addTag(REGISTERED_UID, "Porch") }!!
        dbAndGet { app().repository.linkEntryToTag(tag.id, entry.id) }
        compose.waitForText("Porch")
        compose.onNodeWithText("This song is on no tags yet.").assertDoesNotExist()
        assertEquals(listOf("Porch"), linkedTagNames())
    }

    // ---- the scan-to-assign option ----

    @Test
    fun theScanOptionIsAvailableWithNoRegisteredTags_andCancelDisarms() {
        val controller = compose.setupMainActivity()
        val viewModel = viewModelOf(controller.get())
        viewModel.metadataReader = { SongMetadata() }
        openSongProfile()

        // With no registered tags the picker option is disabled with the
        // clear message, but the scan option stays available (scanning a
        // new tag registers it and then assigns).
        compose.onNodeWithText("No tags registered yet. Scan a tag below to add this song to it.").assertExists()
        compose.onNodeWithText("Scan a tag to assign").assertExists()

        // Tapping it arms the unified scan for the song: the scan dialog
        // appears, with the existing NFC scan copy naming the song.
        compose.onNodeWithText("Scan a tag to assign").performClick()
        compose.waitForIdle()
        assertTrue("tapping the option must arm the scan", viewModel.scanActive.value)
        assertEquals("Second song", viewModel.scanAssignEntry.value?.label)
        compose.onNodeWithText("Scan a tag").assertExists()
        compose.onNodeWithText("Hold the back of the phone against any NFC tag to add “Second song” to it.").assertExists()

        // Cancel disarms the scan and clears the target song.
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertFalse("cancel must disarm the scan", viewModel.scanActive.value)
        assertNull("cancel must clear the target song", viewModel.scanAssignEntry.value)
        compose.onNodeWithText("Scan a tag").assertDoesNotExist()
    }

    @Test
    fun scanOfARegisteredTag_linksTheSongToIt_andStaysOnTheProfile() {
        val controller = compose.setupMainActivity()
        val viewModel = viewModelOf(controller.get())
        viewModel.metadataReader = { SongMetadata() }
        openSongProfile()
        dbAndGet { app().repository.addTag(REGISTERED_UID, "Porch") }

        compose.onNodeWithText("Scan a tag to assign").performClick()
        compose.waitForIdle()
        assertTrue(viewModel.scanActive.value)

        // A registered tag is presented: the read settles the scan by
        // linking the song to the tag - the picker's own mechanism.
        val events = eventsOf(viewModel)
        compose.sendTagWithUid(controller, REGISTERED_UID)
        compose.idleUntil({ !viewModel.scanActive.value }, message = "the scan to settle")
        compose.idleUntil({ events.any { it == "Song added to the tag" } }, message = "the link report")
        assertEquals(listOf("Porch"), linkedTagNames())

        // The user stays on the profile (no tag profile page opened), and
        // the section picked the link up live.
        compose.onNodeWithText("Tag details").assertDoesNotExist()
        compose.onNodeWithText("Song details").assertExists()
        compose.onNodeWithText("Porch").assertExists()
        compose.onNodeWithText("This song is on no tags yet.").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w320dp-h470dp")
    fun scanOfAnUnregisteredTag_registersItFirst_andLinksTheSongOnSave() {
        val controller = compose.setupMainActivity()
        val viewModel = viewModelOf(controller.get())
        viewModel.metadataReader = { SongMetadata() }
        openSongProfile()

        // Armed through the view model: the button's own wiring is pinned
        // by the registered-tag test (on the class's tall screen), and the
        // naming dialog this flow reaches does not reach an idle state on
        // that screen in the pruned test mirror (a TextField inside a
        // dialog at 412x900dp never settles), so the registration flow
        // runs on the default screen, the way the app's own
        // registration-flow tests (TagScanUiTest) do.
        val entry = dbAndGet { app().repository.observeEntries().first() }.single()
        viewModel.startTagScanForEntry(entry)
        compose.waitForIdle()
        assertTrue(viewModel.scanActive.value)

        // An unknown tag is presented: the scan settles into the
        // registration flow (capture + naming dialog), the target song
        // still pending.
        compose.sendTagWithUid(controller, UNREGISTERED_UID)
        compose.idleUntil({ viewModel.capturedUid.value == UNREGISTERED_UID }, message = "the capture")
        compose.waitForIdle()
        assertFalse("a captured tag must end the scan", viewModel.scanActive.value)
        compose.onNodeWithText("Tag captured").assertIsDisplayed()
        // The target song is still armed (it links once the tag is saved).
        assertEquals("Second song", viewModel.scanAssignEntry.value?.label)

        // Saving registers the tag (no name typed - it saves with an empty
        // name, the app's own optional-name flow) AND links the song to it.
        compose.onNodeWithText("Save").performClick()
        compose.idleUntil({ ShadowToast.getTextOfLatestToast() == "Tag added!" }, message = "the save toast")
        compose.idleUntil({ entryIdsForTagUid(UNREGISTERED_UID).isNotEmpty() }, message = "the link")
        // The section shows the new tag (name empty - the uid is shown).
        compose.onNodeWithText(UNREGISTERED_UID).assertExists()
    }

    /** The stored link ids of the tag registered under [uid] (a tag song's link is the unit the name-based list carries). */
    private fun entryIdsForTagUid(uid: String): List<Long> = dbAndGet {
        val tag = app().repository.tagByUid(uid)
        if (tag != null) app().repository.entriesForTag(tag.id).map { it.linkId } else emptyList()
    }

    @Test
    fun scanOfATagThatAlreadyHasTheSong_isARemovedNoOp() {
        val controller = compose.setupMainActivity()
        val viewModel = viewModelOf(controller.get())
        viewModel.metadataReader = { SongMetadata() }
        openSongProfile()

        // The song is already on the tag (through the picker's path).
        val entry = dbAndGet { app().repository.observeEntries().first() }.single()
        val tag = dbAndGet { app().repository.addTag(REGISTERED_UID, "Porch") }!!
        dbAndGet { app().repository.linkEntryToTag(tag.id, entry.id) }
        compose.waitForText("Porch")

        // Scanning the same tag reports the picker's own no-op language -
        // no duplicate link.
        compose.onNodeWithText("Scan a tag to assign").performClick()
        compose.waitForIdle()
        assertTrue(viewModel.scanActive.value)
        val events = eventsOf(viewModel)
        compose.sendTagWithUid(controller, REGISTERED_UID)
        compose.idleUntil({ !viewModel.scanActive.value }, message = "the scan to settle")
        compose.idleUntil({ events.any { it == "That song is already on the tag." } }, message = "the no-op report")
        assertEquals(
            "no duplicate link may be stored",
            1,
            dbAndGet { app().repository.entriesForTag(tag.id) }.size,
        )
        // The section still lists the tag exactly once.
        assertEquals(1, compose.onAllNodesWithText("Porch").fetchSemanticsNodes().size)
    }

    companion object {
        const val REGISTERED_UID = "04A1B2C3"
        const val UNREGISTERED_UID = "04DEADBEEF"
    }
}
