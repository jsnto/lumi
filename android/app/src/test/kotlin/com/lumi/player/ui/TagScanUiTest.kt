package com.lumi.player.ui

import android.nfc.Tag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lumi.player.LumiApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast
import com.lumi.player.ui.testutil.idleUntil
import com.lumi.player.ui.testutil.waitForText
import com.lumi.player.ui.testutil.sendTagWithUid
import com.lumi.player.ui.testutil.app
import com.lumi.player.ui.testutil.viewModelOf
import com.lumi.player.ui.testutil.setupMainActivity
import com.lumi.player.ui.testutil.clickSettings
import com.lumi.player.ui.testutil.clickMenuRow

/**
 * The NFC tags pane's "Scan NFC" button: the pane's single scan entry
 * point, a prominent action ABOVE the tag list (not a row of it - the old
 * "+" register button and the list-row scan are both gone). Tapping it
 * arms the unified scan, which reuses the app's existing NFC read plumbing
 * - the foreground-dispatch read the tag registration already uses (a tag
 * intent delivered to [MainActivity] as EXTRA_TAG, the same shape the
 * system's foreground dispatch delivers). When a tag is scanned, its UID
 * is looked up among the registered tags: a registered one opens that
 * tag's profile page (the tag detail page), and an unregistered one goes
 * into the registration flow (capture, naming dialog - registration never
 * writes the card). The scan result is driven the same way the NFC tests
 * drive tag reads: [ActivityController.newIntent] ->
 * [MainActivity.onNewIntent].
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TagScanUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        markSetupCompleteForTests()
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private fun seedTag(uid: String, name: String) {
        val thread = Thread { runBlocking { app().repository.addTag(uid, name) } }
        thread.start()
        thread.join()
        ShadowLooper.idleMainLooper()
    }

    /** Opens the NFC tags pane (settings, the NFC tags row). */
    private fun openNfcTagsPane() {
        compose.clickSettings()
        compose.clickMenuRow("NFC tags")
    }

    @Test
    fun scanNfcButtonIsThePaneAction_notAListRow_andArmsTheScan() {
        seedTag(REGISTERED_UID, "Porch")
        val controller = compose.setupMainActivity()
        val viewModel = viewModelOf(controller.get())
        openNfcTagsPane()

        // The button reads as an action, not as another row of the list:
        // it carries the click, and the old row's detail line is gone.
        compose.onNodeWithText("Scan NFC").assertHasClickAction()
        compose.onNodeWithText("Scan NFC").assertIsDisplayed()
        compose.onNodeWithText("Scan a registered tag to open its details").assertDoesNotExist()

        // The section's old + button is gone: no "Register a tag" action
        // anywhere in the pane.
        compose.onNodeWithContentDescription("Register a tag").assertDoesNotExist()

        // Visually separated from the list: the button sits ABOVE the
        // section card (the top bar carries the same title, so the LOWER
        // of the "NFC tags" nodes is the section's title row).
        val button = compose.onNodeWithText("Scan NFC").fetchSemanticsNode()
        val sectionTitle = compose
            .onAllNodesWithText("NFC tags")
            .fetchSemanticsNodes()
            .maxBy { it.boundsInRoot.top }
        assertTrue(
            "the scan button must sit above the tag list section " +
                "(button bottom=${button.boundsInRoot.bottom}, section top=${sectionTitle.boundsInRoot.top})",
            button.boundsInRoot.bottom < sectionTitle.boundsInRoot.top,
        )

        // Tapping it arms the scan: the unified scan dialog appears, with
        // copy that names ANY tag (the scan goes either to a profile page
        // or into registration).
        compose.onNodeWithText("Scan NFC").performClick()
        compose.waitForIdle()
        assertTrue("tapping the button must arm the scan", viewModel.scanActive.value)
        compose.onNodeWithText("Scan a tag").assertIsDisplayed()
        compose.onNodeWithText("Hold the back of the phone against any NFC tag…").assertExists()
    }

    @Test
    fun scanDialogCancel_dismissesWithoutArmedScan() {
        seedTag(REGISTERED_UID, "Porch")
        val controller = compose.setupMainActivity()
        val viewModel = viewModelOf(controller.get())
        openNfcTagsPane()

        compose.onNodeWithText("Scan NFC").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Scan a tag").assertIsDisplayed()

        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertFalse("cancel must disarm the scan", viewModel.scanActive.value)
        compose.onNodeWithText("Scan a tag").assertDoesNotExist()
    }

    @Test
    fun scanOfRegisteredTag_opensTheTagProfilePage() {
        seedTag(REGISTERED_UID, "Porch")
        val controller = compose.setupMainActivity()
        val viewModel = viewModelOf(controller.get())
        openNfcTagsPane()

        compose.onNodeWithText("Scan NFC").performClick()
        compose.waitForIdle()
        assertTrue("tapping the button must arm the scan", viewModel.scanActive.value)
        compose.onNodeWithText("Scan a tag").assertIsDisplayed()

        // A registered tag is presented: the read arrives through the
        // foreground-dispatch path, exactly like a registration scan read.
        compose.sendTagWithUid(controller, REGISTERED_UID)
        compose.idleUntil({ !viewModel.scanActive.value })
        compose.waitForText("Tag details")

        // The scan dialog closed and the tag's PROFILE page opened (not an
        // edit dialog): the page shows the tag's details, its (empty)
        // song list, and the danger zone. The top bar carries the tag's
        // name too, so the name appears twice (top bar + details row).
        compose.onNodeWithText("Scan a tag").assertDoesNotExist()
        compose.onNodeWithText("Tag details").assertExists()
        assertEquals(
            "the top bar and the details row must both show the tag's name",
            2,
            compose.onAllNodesWithText("Porch").fetchSemanticsNodes().size,
        )
        compose.onNodeWithText(REGISTERED_UID).assertExists()
        compose.onNodeWithText("This tag plays nothing yet.").assertExists()
        compose.onNodeWithText("Danger zone").assertExists()
        // The edit dialog is NOT what a scan opens.
        compose.onNodeWithText("Edit tag").assertDoesNotExist()
        // The resolved target is consumed, so a later scan can re-trigger.
        compose.idleUntil({ viewModel.pendingTagProfile.value == null })
        assertNull("the resolved tag must be consumed", viewModel.pendingTagProfile.value)
    }

    @Test
    fun scanOfUnregisteredTag_entersTheRegistrationFlow_andSaves() {
        // A different tag is registered: it must not be what the scan
        // resolves to (the scanned tag is unknown).
        seedTag(REGISTERED_UID, "Porch")
        val controller = compose.setupMainActivity()
        val viewModel = viewModelOf(controller.get())
        openNfcTagsPane()

        compose.onNodeWithText("Scan NFC").performClick()
        compose.waitForIdle()
        assertTrue("tapping the button must arm the scan", viewModel.scanActive.value)

        // An unknown tag is presented: the scan settles into the
        // registration flow (capture + naming dialog).
        compose.sendTagWithUid(controller, UNREGISTERED_UID)
        compose.idleUntil({ viewModel.capturedUid.value == UNREGISTERED_UID })
        compose.waitForIdle()
        assertFalse("a captured tag must end the scan", viewModel.scanActive.value)
        compose.onNodeWithText("Scan a tag").assertDoesNotExist()
        compose.onNodeWithText("Tag captured").assertIsDisplayed()
        compose.onNodeWithText("ID: $UNREGISTERED_UID").assertExists()

        // Saving registers the tag: the toast lands once the Room write
        // settles, the naming dialog closes, and the tag row joins the list.
        compose.onNodeWithText("Save").performClick()
        compose.idleUntil({ ShadowToast.getTextOfLatestToast() == "Tag added!" })
        compose.waitForIdle()
        compose.onNodeWithText("Tag captured").assertDoesNotExist()
        compose.waitForText(UNREGISTERED_UID)
        compose.onNodeWithText("Porch").assertExists()
    }

    @Test
    fun scanSettlesOnTheFirstRead_secondReadIsAPlaybackRead() {
        seedTag(REGISTERED_UID, "Porch")
        val controller = compose.setupMainActivity()
        val viewModel = viewModelOf(controller.get())
        openNfcTagsPane()

        compose.onNodeWithText("Scan NFC").performClick()
        compose.waitForIdle()
        compose.sendTagWithUid(controller, REGISTERED_UID)
        compose.idleUntil({ !viewModel.scanActive.value })
        compose.waitForText("Tag details")

        // The scan is over: a further read of the same tag is a normal
        // playback read (the tag has no songs, so it reports, not opens).
        compose.sendTagWithUid(controller, REGISTERED_UID)
        compose.idleUntil({ ShadowToast.getTextOfLatestToast() == "Nothing is linked to Porch yet." })
        assertEquals(
            "Nothing is linked to Porch yet.",
            ShadowToast.getTextOfLatestToast(),
        )
        assertNull("no second profile resolution must be pending", viewModel.pendingTagProfile.value)
    }

    companion object {
        const val REGISTERED_UID = "04A1B2C3"
        const val UNREGISTERED_UID = "04DEADBEEF"
    }
}
