package com.lumi.player.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.lumi.player.LumiApp
import com.lumi.player.data.AudioLevelMode
import com.lumi.player.data.EntryType
import com.lumi.player.data.LoudnessResult
import com.lumi.player.data.LoudnessStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * The "Audio level" settings pane (the top-level settings row): the mode
 * selection (Off / Normalize, Off the default - an existing user's volume is
 * never changed unexpectedly), the target-level picker (-18 / -16 / -14,
 * -16 the default), the measurement status row, and the explicit
 * "Normalize now" action. The choices persist through the app's existing
 * settings storage (a restarted app keeps them) and the menu row's
 * subtitle follows the stored choice.
 *
 * Like SettingsBackNavUiTest, the tests intentionally leave their
 * activities up: a NATIVE-graphics teardown of a settings-screen activity
 * wedges the class's SHARED recomposer for the later tests' back-press
 * transitions (their waitForIdle never settles - AppNotIdleException after
 * the full 60 s). Robolectric tears the app down between tests regardless,
 * so nothing leaks across tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
    qualifiers = "w320dp-h300dp",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioLevelUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    /** The app's view model (the real construction, the test seam
     *  reachable through it). */
    private fun viewModelOf(activity: MainActivity): LumiViewModel {
        return MainActivity::class.java.getDeclaredField("viewModel").apply {
            isAccessible = true
        }.get(activity) as LumiViewModel
    }

    /** Opens the audio level sub-screen (player -> settings menu -> the
     *  "Audio level" row). The activity is left up - see the class
     *  documentation. */
    private fun openAudioLevel(): Pair<MainActivity, LumiViewModel> {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Audio level").performClick()
        compose.waitForIdle()
        return activity to viewModelOf(activity)
    }

    private fun <T> dbAndGet(block: suspend () -> T): T {
        var result: T? = null
        Thread { result = runBlocking { block() } }.also { it.start() }.apply { join() }
        @Suppress("UNCHECK_CAST")
        return result as T
    }

    private fun idleUntil(cond: () -> Boolean, timeoutMs: Long = 30_000, message: String = "the operation to settle") {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        assertTrue("timed out waiting for $message", cond())
    }

    @Test
    fun thePaneOffersTheModesTheTargetsAndTheNormalizeAction() {
        val (activity, viewModel) = openAudioLevel()

        // The mode selection: Off is the default (an existing user's
        // volume is never changed unexpectedly), Normalize the choice.
        compose.onNodeWithText("Off").assertIsDisplayed()
        compose.onNodeWithText("Normalize").assertExists()

        // The target picker: -16 the default, -18 and -14 the
        // alternatives (the rows may sit below the small test screen's
        // fold - existence is the assertion).
        compose.onNodeWithText("-18 LUFS").assertExists()
        compose.onNodeWithText("-16 LUFS").assertExists()
        compose.onNodeWithText("-14 LUFS").assertExists()

        // The measurement status (nothing added yet) and the explicit
        // action.
        compose.onNodeWithText("0 of 0 songs measured").assertExists()
        compose.onNodeWithText("Normalize now").assertExists()

        // The default state is Off at the default target.
        idleUntil({ viewModel.audioLevelMode.value == AudioLevelMode.OFF })
        assertEquals(-16.0, viewModel.audioLevelTargetLufs.value, 0.0001)
        compose.waitForIdle()
    }

    @Test
    fun aModePickPersistsAndTheMenuSubtitleFollows() {
        val (activity, viewModel) = openAudioLevel()

        compose.onNodeWithText("Off").assertIsDisplayed()

        // Pick Normalize: the state settles on the Room round trip.
        compose.onNodeWithText("Normalize").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("Normalize").performClick()
        idleUntil({ viewModel.audioLevelMode.value == AudioLevelMode.NORMALIZE })
        compose.waitForIdle()

        // The persisted state survives a restart (a fresh activity over the
        // same store reads NORMALIZE back), and the menu row's subtitle
        // follows the stored choice.
        val restarted = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        val vm2 = viewModelOf(restarted)
        idleUntil({ vm2.audioLevelMode.value == AudioLevelMode.NORMALIZE })
        compose.waitForIdle()
        compose.onNodeWithText("Normalize to -16 LUFS").assertExists()
        restarted.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
    }

    @Test
    fun aTargetPickPersistsThroughTheRoomRoundTrip() {
        val (activity, viewModel) = openAudioLevel()

        compose.onNodeWithText("-18 LUFS").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("-18 LUFS").performClick()
        idleUntil({ viewModel.audioLevelTargetLufs.value == -18.0 })
        compose.waitForIdle()
        assertEquals(-18.0, viewModel.audioLevelTargetLufs.value, 0.0001)

        // Switching the mode on and back off keeps the target (the
        // picker is a separate setting from the mode).
        compose.onNodeWithText("Normalize").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("Normalize").performClick()
        idleUntil({ viewModel.audioLevelMode.value == AudioLevelMode.NORMALIZE })
        compose.onNodeWithText("Off").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("Off").performClick()
        idleUntil({ viewModel.audioLevelMode.value == AudioLevelMode.OFF })
        assertEquals("-18 is the stored target still", -18.0, viewModel.audioLevelTargetLufs.value, 0.0001)
        compose.waitForIdle()
    }

    @Test
    fun theStatusRowCountsMeasuredSongsAndNormalizeNowMeasuresThem() {
        val (activity, viewModel) = openAudioLevel()

        val app: LumiApp = RuntimeEnvironment.getApplication() as LumiApp
        dbAndGet {
            checkNotNull(app.repository.addEntryUnique(EntryType.AUDIO, "One", "content://one"))
            checkNotNull(app.repository.addEntryUnique(EntryType.AUDIO, "Two", "content://two"))
        }
        val calls = java.util.concurrent.atomic.AtomicInteger()
        viewModel.loudnessMeasurer = {
            calls.incrementAndGet()
            LoudnessResult(-16.0, -1.0)
        }
        idleUntil({ viewModel.loudnessStatus.value == LoudnessStatus(0, 2) })
        compose.waitForIdle()
        compose.onNodeWithText("0 of 2 songs measured").assertExists()

        // The explicit pass (the pane's button): it measures the
        // pending songs and the status follows. (The button sits below
        // the small test screen's fold: scroll it into view so the
        // click lands.)
        compose.onNodeWithText("Normalize now").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("Normalize now").performClick()
        idleUntil({ calls.get() == 2 && !viewModel.normalizing.value })
        idleUntil({ viewModel.loudnessStatus.value == LoudnessStatus(2, 2) })
        compose.waitForIdle()
        compose.onNodeWithText("2 of 2 songs measured").assertExists()
        compose.waitForIdle()
    }

    @Test
    fun systemBackUnwindsThePaneToTheMenu() {
        val (activity, _) = openAudioLevel()

        // The sub-screen is up (the Normalize action is the pane's own).
        compose.onNodeWithText("Normalize now").assertExists()

        // System back: one level back to the settings menu (the pane is a
        // child of the menu - the same one-level unwind the other
        // sub-screens get; the menu -> player hop is pinned by
        // SettingsMenuUiTest.systemBackFromMenuReturnsToThePlayerScreen,
        // the shared `pane == Menu` pop the pane's unwind lands on).
        activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        compose.onNodeWithText("Play mode").assertIsDisplayed()
        compose.onNodeWithText("Normalize now").assertDoesNotExist()
    }
}
