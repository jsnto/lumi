package com.lumi.player.ui

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.lumi.player.LumiApp
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
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
 * Verifies the remembered-playback-positions advanced option (the Advanced
 * options page's "Remembered playback positions" section): the page shows
 * the stepper defaulting to the hard-coded count (3), the stepper's
 * changes persist the count through the same settings mechanism as the
 * other settings (and survive an app restart), a step issued while a
 * write is in flight is not applied (the stepper's buttons are disabled
 * for the write's round trip) and the next step then steps from the
 * landed value, and an out-of-range pick is clamped to the setting's
 * bounds (1..20).
 *
 * The setting's value is the number of songs' last played position the
 * per-tag LRU remembers: the player service evicts against it at the next
 * stored position (a lower count evicts the oldest remembered tags first,
 * a higher one keeps the rows already remembered - the LRU itself is
 * pinned by [com.lumi.player.data.PlaybackPositionTest] and
 * [com.lumi.player.data.TagPositionStoreTest]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RememberedPositionsSettingUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        markSetupCompleteForTests()
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    /**
     * Asserts the section renders and scrolls its row (the stepper's host)
     * into view. The section sits below the fold on the small test screen,
     * and scrolling the section's title only brings the title (the row's
     * top edge) into view - the row's controls below it keep empty bounds
     * and would not answer a click - so the row scrolls itself in.
     */
    private fun showSection(activity: MainActivity) {
        compose.onNodeWithText("Remembered playback positions").assertExists()
        compose.onNodeWithText(
            "How many songs' last played position lumi remembers (per tag, newest first).",
        ).assertExists()
        compose.onNodeWithText("Songs to remember").performScrollTo()
        compose.waitForIdle()
    }

    /** Waits for the asynchronous Room round trip to make the setting observable. */
    private fun awaitRememberedPositions(activity: MainActivity, expected: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (viewModelOf(activity).rememberedPositions.value == expected) return
        }
        fail("the remembered-positions setting must become $expected")
    }

    @Test
    fun advancedPageShowsRememberedPositionsDefaultingToThree() {
        val activity = compose.openAdvancedPage()
        showSection(activity)

        // The stepper defaults to the hard-coded count (3) - today's
        // behavior - with both stepper buttons reachable on the row.
        compose.onNodeWithText("3").assertExists()
        compose.onNodeWithContentDescription("Decrease remembered positions").assertExists()
        compose.onNodeWithContentDescription("Increase remembered positions").assertExists()
        // The setting starts unset in the database, so the view model
        // reports the default.
        Shadows.shadowOf(Looper.getMainLooper())
            .idleFor(500, TimeUnit.MILLISECONDS)
        assertTrue(
            "the remembered-positions setting must default to 3",
            viewModelOf(activity).rememberedPositions.value == 3,
        )
    }

    @Test
    fun steppingUpPersistsTheCount() {
        val activity = compose.openAdvancedPage()
        showSection(activity)

        // Each step drives the view model, which persists to the database;
        // the observable value (a Room flow) landing on the stepped count
        // proves the round trip. The next step waits for the previous
        // write to land so it steps from the new value.
        compose.onNodeWithContentDescription("Increase remembered positions").performClick()
        awaitRememberedPositions(activity, 4)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Increase remembered positions").performClick()
        awaitRememberedPositions(activity, 5)
        compose.waitForIdle()
        compose.onNodeWithText("5").assertExists()
    }

    @Test
    fun steppingDownPersistsTheCountAndClampsAtOne() {
        val activity = compose.openAdvancedPage()
        showSection(activity)

        // 3 -> 2 -> 1, then a step below the minimum: the settings writer
        // clamps the count to the setting's range, so the value stays at 1.
        compose.onNodeWithContentDescription("Decrease remembered positions").performClick()
        awaitRememberedPositions(activity, 2)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Decrease remembered positions").performClick()
        awaitRememberedPositions(activity, 1)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Decrease remembered positions").performClick()
        awaitRememberedPositions(activity, 1)
        compose.waitForIdle()
        compose.onNodeWithText("1").assertExists()
    }

    @Test
    fun aTapIssuedWhileTheWriteIsInFlightIsNotAppliedAndTheNextStepStepsFromTheLandedValue() {
        val activity = compose.openAdvancedPage()
        showSection(activity)

        // Hold the write's in-flight window open: the write persists its
        // target only once the test releases the gate.
        val vm = viewModelOf(activity)
        val gate = CompletableDeferred<Unit>()
        vm.rememberedPositionsWriter = { count ->
            gate.await()
            vm.repository.setRememberedPositions(count)
        }

        // Step up: the write is in flight and the round trip has not
        // landed (the displayed value stays bound to the flow - still 3).
        compose.onNodeWithContentDescription("Increase remembered positions").performClick()
        compose.waitForIdle()

        // While the write is in flight the stepper's buttons are
        // disabled, so a tap issued now is not applied from the stale
        // value - and not queued: the value stays 3 while the write is
        // held.
        compose.onNodeWithContentDescription("Increase remembered positions").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Decrease remembered positions").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Increase remembered positions").performClick()
        compose.waitForIdle()
        assertEquals(
            "a tap issued while the write is in flight is not applied",
            3,
            vm.rememberedPositions.value,
        )
        assertTrue(
            "the write is still in flight while the gate holds it",
            vm.rememberedPositionsWriteInFlight.value,
        )
        compose.onNodeWithText("3").assertExists()

        // The write lands: the value becomes 4 and the buttons come
        // back.
        gate.complete(Unit)
        awaitRememberedPositions(activity, 4)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Increase remembered positions").assertIsEnabled()
        compose.onNodeWithContentDescription("Decrease remembered positions").assertIsEnabled()

        // The next step steps from the persisted value.
        compose.onNodeWithContentDescription("Increase remembered positions").performClick()
        awaitRememberedPositions(activity, 5)
        compose.waitForIdle()
        compose.onNodeWithText("5").assertExists()
    }

    @Test
    fun anOutOfRangePickIsClampedToTheSettingBound() {
        val activity = compose.openAdvancedPage()
        showSection(activity)

        // A pick far above the setting's bound (20) is clamped to it -
        // the range the stored value is written with.
        viewModelOf(activity).setRememberedPositions(100)
        awaitRememberedPositions(activity, 20)
        compose.waitForIdle()
        compose.onNodeWithText("20").assertExists()
    }

    @Test
    fun theCountSurvivesAnAppRestart() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Advanced options").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("Advanced options").performClick()
        compose.waitForIdle()
        showSection(activity)

        // Step the count up, then destroy the activity: a fresh activity
        // over the same store (a restarted app) reads the pick back.
        compose.onNodeWithContentDescription("Increase remembered positions").performClick()
        awaitRememberedPositions(activity, 4)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Increase remembered positions").performClick()
        awaitRememberedPositions(activity, 5)
        compose.waitForIdle()
        controller.destroy()

        val restarted = Robolectric.buildActivity(MainActivity::class.java).setup()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Advanced options").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("Advanced options").performClick()
        compose.waitForIdle()
        showSection(restarted.get())
        compose.onNodeWithText("5").assertIsDisplayed()
        compose.waitForIdle()
    }
}
