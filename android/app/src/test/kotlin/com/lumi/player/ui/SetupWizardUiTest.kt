package com.lumi.player.ui

import android.app.admin.DevicePolicyManager
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import com.lumi.player.LumiApp
import com.lumi.player.data.FolderChild
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
import org.robolectric.shadows.ShadowLooper

/**
 * The first-time setup wizard: a fresh install (cleared app data - a fresh
 * Room store, which Robolectric gives every test) lands on the wizard
 * instead of the player; importing a music folder is the one required step
 * (the wizard cannot finish before it); the optional items (NFC tag
 * play/pause, the screen lock) are offered with a skip; and the wizard ends
 * into the app, with the completion persisted so a returned user never
 * sees it again.
 *
 * Unlike the other UI tests, this class deliberately does NOT call
 * [markSetupCompleteForTests]: the fresh store IS the fresh install under
 * test. The folder import is driven through the view model's real
 * [LumiViewModel.addFolderEntry] path with a faked [LumiViewModel.folderChildren]
 * tree (the SAF picker itself cannot run in Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetupWizardUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private val TREE_URI = "content://com.android.documentsui/tree/primary:Music"

    private fun startMainActivity(): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java).setup()

    /** The activity's real [LumiViewModel], via reflection. */
    private fun viewModelOf(activity: MainActivity): LumiViewModel {
        val field = MainActivity::class.java.getDeclaredField("viewModel")
        field.isAccessible = true
        return field.get(activity) as LumiViewModel
    }

    private fun idleUntil(cond: () -> Boolean, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        check(cond()) { "timed out waiting for the wizard to settle" }
    }

    /**
     * Polls [text] in with a looper pump: the wizard's finish persists the
     * completion through a Room write (Room's background executor, outside
     * [waitForIdle]), and only the store's emission flips the gate from the
     * wizard to the app - the swap lands a frame or two after the click.
     */
    private fun waitForText(text: String, timeoutMs: Long = 5_000) {
        idleUntil({
            try {
                compose.onNodeWithText(text).assertExists()
                true
            } catch (e: AssertionError) {
                false
            }
        }, timeoutMs)
    }

    private fun eventsOf(viewModel: LumiViewModel): MutableList<String> {
        val events = mutableListOf<String>()
        viewModel.viewModelScope.launch { viewModel.uiEvents.collect { e: String -> events += e } }
        return events
    }

    /**
     * Imports a one-song folder through the view model's real add path (the
     * picker's pick is what [LumiViewModel.addFolderEntry] receives, so the
     * test calls it directly with a faked tree seam).
     */
    private fun importAFolder(viewModel: LumiViewModel) {
        val events = eventsOf(viewModel)
        viewModel.folderChildren = { folderUri ->
            if (folderUri.toString() == TREE_URI) {
                listOf(FolderChild("song-a.mp3", isDirectory = false, childUri = "content://docs/document/audio:100"))
            } else emptyList()
        }
        viewModel.addFolderEntry(Uri.parse(TREE_URI))
        idleUntil({ events.any { it.startsWith("Added") } })
    }

    /** Walks welcome -> music -> (folder imported) -> the music step's Next. */
    private fun reachTheMusicStepDone(activity: MainActivity) {
        compose.onNodeWithText("Let's set up").performClick()
        compose.waitForIdle()
        val viewModel = viewModelOf(activity)
        importAFolder(viewModel)
        compose.waitForIdle()
        compose.onNodeWithText("1 song imported.").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        compose.waitForIdle()
    }

    @Test
    fun aFreshInstallLandsOnTheWizardWelcome() {
        startMainActivity().get()
        compose.waitForIdle()

        // The wizard's welcome, not the player: the player's idle prompt is
        // not up yet.
        compose.onNodeWithText("Welcome to lumi").assertIsDisplayed()
        compose.onNodeWithText("Let's set up").assertIsDisplayed()
        compose.onNodeWithText("Tap a tag to start!").assertDoesNotExist()
    }

    @Test
    fun theWizardWalksAFreshInstallThroughTheRequiredFolderIntoTheApp() {
        val controller = startMainActivity()
        val activity = controller.get()
        compose.waitForIdle()

        // The required step: a music folder. No Next while the library is
        // still empty - the wizard cannot finish without it.
        compose.onNodeWithText("Let's set up").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Add your music").assertIsDisplayed()
        compose.onNodeWithText("Choose a music folder").assertIsDisplayed()
        compose.onNodeWithText("Next").assertDoesNotExist()

        // The folder lands (the view model's real import path) and the step
        // settles into its success state.
        importAFolder(viewModelOf(activity))
        compose.waitForIdle()
        compose.onNodeWithText("1 song imported.").assertIsDisplayed()
        compose.onNodeWithText("Choose a music folder").assertDoesNotExist()

        // The optional steps, each skippable, then the end of the wizard.
        compose.onNodeWithText("Next").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Play with a tag").assertIsDisplayed()
        compose.onNodeWithText("Skip").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Screen lock").assertIsDisplayed()
        compose.onNodeWithText("Enable screen lock").assertIsDisplayed()
        compose.onNodeWithText("Skip").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("You're all set!").assertIsDisplayed()

        // The wizard ends into the app: the player's front screen (the
        // completion's Room write + the gate's flip land a frame or two
        // after the click - poll with the pump).
        compose.onNodeWithText("Open lumi").performClick()
        waitForText("Tap a tag to start!")
        compose.onNodeWithText("Tap a tag to start!").assertIsDisplayed()
        compose.onNodeWithText("You're all set!").assertDoesNotExist()

        // The completion persists: a relaunch over the same store goes
        // straight to the player (no wizard).
        controller.destroy()
        Robolectric.buildActivity(MainActivity::class.java).setup()
        waitForText("Tap a tag to start!")
        compose.onNodeWithText("Tap a tag to start!").assertIsDisplayed()
        compose.onNodeWithText("Welcome to lumi").assertDoesNotExist()
    }

    @Test
    fun theTagStepSetUpNowEndsIntoTheNfcTagsPane() {
        val controller = startMainActivity()
        val activity = controller.get()
        compose.waitForIdle()

        reachTheMusicStepDone(activity)
        compose.onNodeWithText("Play with a tag").assertIsDisplayed()

        // "Set up now" ends the wizard into the app's settings screen,
        // deep-linked to the NFC tags pane (where the tag is scanned) -
        // the swap lands a frame or two after the click (see waitForText).
        compose.onNodeWithText("Set up now").performClick()
        waitForText("Scan NFC")
        compose.onNodeWithText("Scan NFC").assertIsDisplayed()
        compose.onNodeWithText("Play with a tag").assertDoesNotExist()

        // ...and the completion is recorded: a relaunch skips the wizard
        // (the store read in the new activity's onCreate seeds the gate to
        // the player before the first frame).
        controller.destroy()
        Robolectric.buildActivity(MainActivity::class.java).setup()
        waitForText("Tap a tag to start!")
        compose.onNodeWithText("Welcome to lumi").assertDoesNotExist()
        compose.onNodeWithText("Tap a tag to start!").assertIsDisplayed()
    }

    @Test
    fun theScreenLockStepLaunchesThePlatformConsentAndStaysOnTheStep() {
        val controller = startMainActivity()
        val activity = controller.get()
        compose.waitForIdle()

        reachTheMusicStepDone(activity)
        compose.onNodeWithText("Play with a tag").assertIsDisplayed()
        compose.onNodeWithText("Skip").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Screen lock").assertIsDisplayed()

        // "Enable screen lock" launches the platform's own device-admin
        // consent (the wizard stays put; on the way back the step shows the
        // permission's real state).
        compose.onNodeWithText("Enable screen lock").performClick()
        compose.waitForIdle()
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("tapping Enable screen lock must launch the device-admin consent", started)
        assertEquals(
            DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN,
            started!!.action,
        )
        // The wizard is still on its screen-lock step.
        compose.onNodeWithText("Enable screen lock").assertIsDisplayed()
        compose.onNodeWithText("Skip").assertIsDisplayed()
    }
}
