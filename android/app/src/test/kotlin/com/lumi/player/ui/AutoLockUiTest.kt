package com.lumi.player.ui

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.data.AutoLockDelay
import com.lumi.player.data.SettingsEntity
import com.lumi.player.player.PlaybackStatus
import com.lumi.player.player.PlayerStateHolder
import com.lumi.player.player.PlayerUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * The Advanced options page's "Auto-lock after idle" (and the Advanced
 * options page itself, which the keep-screen-on row moved into - and which
 * replaces the System page): the menu's Advanced options row and the moved
 * row's new home, the picker's persistence (the app's settings-table
 * pattern), the back navigation's one-level unwind, the idle timer
 * end-to-end (a fake clock drives it through the view model's tick seam),
 * the lock winning over keep-screen-on, the foreground gate (a
 * backgrounded app holds the elapsed stretch - no lock - and it fires on
 * the return to the foreground), and the admin-not-granted state (the
 * setting stays visible and prompts to enable; the app keeps working).
 *
 * The actual screen lock goes through [LumiViewModel.lockDevice] (a test
 * seam for [DevicePolicyManager.lockNow]), and the admin's state through
 * [LumiViewModel.isDeviceAdminActive]; the platform's own consent screen
 * (the real [DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN] launch) is an
 * on-device verification item.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutoLockUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        markSetupCompleteForTests()
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
        // The shared player state is process-wide: every test starts from
        // "nothing is loaded" (the app just opened).
        PlayerStateHolder.update { PlayerUiState() }
    }

    private fun openSettings(): MainActivity {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        return activity
    }
    /** Opens the Advanced options page from the settings menu. */
    private fun openAdvancedPage(activity: MainActivity) {
        compose.onNodeWithText("Advanced options").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("Advanced options").performClick()
        compose.waitForIdle()
    }

    /** The activity's real [LumiViewModel], via reflection. */
    private fun viewModelOf(activity: MainActivity): LumiViewModel {
        val field = MainActivity::class.java
            .getDeclaredField("viewModel")
            .also { it.isAccessible = true }
        return field.get(activity) as LumiViewModel
    }

    // onResume/onPause are protected on Activity, so drive the real
    // MainActivity overrides directly (running their full body) without the
    // framework lifecycle plumbing.
    private fun drive(activity: MainActivity, method: String) {
        val m = MainActivity::class.java.getDeclaredMethod(method)
        m.isAccessible = true
        m.invoke(activity)
    }

    /** Pumps the main looper until [condition] holds or the deadline lapses. */
    private fun await(condition: () -> Boolean, message: () -> String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(10)
        }
        fail(message())
    }

    private fun keepScreenOnFlag(activity: MainActivity): Boolean =
        (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0

    /** A ladder row (a [ModeRow] chip) in its selected state. The mirror's
     * [androidx.compose.ui.semantics.SemanticsConfiguration] `get` throws
     * for an absent key, so the entry is read by iteration, not by key. */
    private fun selectedLadderRowContaining(text: String): SemanticsMatcher =
        SemanticsMatcher("the selected ladder row containing $text") { node ->
            val entries = node.config.toList()
            val selected = entries.any { it.key == SemanticsProperties.Selected && it.value == true }
            val hasText = (entries.firstOrNull { it.key == SemanticsProperties.Text }?.value as? List<AnnotatedString>)
                ?.any { it.text.contains(text) } == true
            selected && hasText
        }

    /**
     * Re-anchors the running idle stretch to the test's fake clock. The
     * activity's setup already armed a stretch at the REAL wall clock
     * (the player-state collector runs before the test swaps the clock
     * seam), and a stretch only re-arms when playback goes active and
     * idle again - so a playback cycle under the fake clock restarts it
     * at the fake `now`, and the measured idle time lines up with the
     * test's fake timeline.
     */
    private fun reanchorIdleStretch(vm: LumiViewModel) {
        PlayerStateHolder.update { it.copy(status = PlaybackStatus.PLAYING) }
        ShadowLooper.idleMainLooper()
        PlayerStateHolder.update { it.copy(status = PlaybackStatus.PAUSED) }
        ShadowLooper.idleMainLooper()
    }

    private fun app(): LumiApp =
        ApplicationProvider.getApplicationContext<LumiApp>()

    /** Runs a suspend block on the app's database and returns its result. */
    private fun <T> dbAndGet(block: suspend () -> T): T {
        var result: T? = null
        Thread { result = runBlocking { block() } }.also { it.start() }.apply { join() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    // ---- the menu and the moved rows ----

    @Test
    fun theMenuListsAdvancedOptionsAndNotTheMovedRows() {
        openSettings()

        // The menu keeps the now-playing strip's home rows (play mode, NFC
        // tags, content), gains the Advanced options row (replacing the old
        // System row), and no longer carries the keep-screen-on row (moved
        // into the Advanced options page). Appearance is an everyday
        // setting: it is a top-level menu row.
        compose.onNodeWithText("Advanced options").assertExists()
        compose.onNodeWithText("Keep screen on").assertDoesNotExist()
        compose.onNodeWithText("Appearance").assertExists()

        // The order reads top to bottom: play mode, NFC tags, content,
        // appearance, advanced options.
        compose.onNodeWithText("Advanced options").performScrollTo()
        compose.waitForIdle()
        val playModeTop = compose.onNodeWithText("Play mode").fetchSemanticsNode().boundsInRoot.top
        val nfcTop = compose.onNodeWithText("NFC tags").fetchSemanticsNode().boundsInRoot.top
        val contentTop = compose.onNodeWithText("Content").fetchSemanticsNode().boundsInRoot.top
        val appearanceTop = compose.onNodeWithText("Appearance").fetchSemanticsNode().boundsInRoot.top
        val advancedTop = compose.onNodeWithText("Advanced options").fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "the menu lists play mode, NFC tags, content, appearance, advanced options - in that order",
            playModeTop < nfcTop && nfcTop < contentTop && contentTop < appearanceTop && appearanceTop < advancedTop,
        )
    }

    @Test
    fun theAdvancedPageShowsTheMovedRowAndTheAutoLockLadder() {
        openAdvancedPage(openSettings())

        // The keep-screen-on row moved here from the menu, look and test
        // tag intact (unchecked by default).
        compose.onNodeWithText("Keep screen on").assertExists()
        compose.onNodeWithText("Keep the display on while the app is open.").assertExists()

        // The auto-lock setting: the section with its ladder (off by
        // default) and the screen-lock permission's state (not enabled -
        // the app's admin has never been granted in a fresh test run).
        compose.onNodeWithText("Auto-lock after idle").assertExists()
        // The ladder's "Off" row (the audio level section's row subtitle
        // reads "Off" too while the mode is off - the reorganization moved
        // the setting onto this page - so the count is two).
        compose.onAllNodesWithText("Off").assertCountEquals(2)
        compose.onNodeWithText("After 5 minutes").assertExists()
        compose.onNodeWithText("After 10 minutes").assertExists()
        compose.onNodeWithText("After 15 minutes").assertExists()
        compose.onNodeWithText("After 30 minutes").assertExists()
        compose.onNodeWithText("Screen lock not enabled").assertExists()
        compose.onNodeWithText("Enable screen lock").assertExists()

        // The appearance row is NOT on this page any more (it is a
        // top-level menu row).
        compose.onNodeWithText("Appearance").assertDoesNotExist()
    }

    // ---- the picker's persistence (the app's settings pattern) ----

    @Test
    fun choosingALadderOptionPersistsAndTheRestartedAppKeepsIt() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        openAdvancedPage(activity)

        // Off is selected by default (fresh store).
        compose.onNode(selectedLadderRowContaining("Off")).assertExists()
        compose.onNode(selectedLadderRowContaining("After 5 minutes")).assertDoesNotExist()

        // Pick the 5-minute option: the choice is off by a tap through the
        // view model, and the Room round trip makes it observable.
        compose.onNodeWithText("After 5 minutes").performClick()
        await(
            { viewModelOf(activity).autoLockDelay.value == AutoLockDelay.FIVE_MINUTES },
            { "the auto-lock choice must become 5 minutes" },
        )
        compose.waitForIdle()
        compose.onNode(selectedLadderRowContaining("After 5 minutes")).assertExists()
        compose.onNode(selectedLadderRowContaining("Off")).assertDoesNotExist()

        // The choice is in the app's settings storage: a restarted app
        // (a fresh activity over the same store) reads it back and the
        // page shows it selected.
        controller.destroy()
        val restarted = Robolectric.buildActivity(MainActivity::class.java).setup()
        await(
            { viewModelOf(restarted.get()).autoLockDelay.value == AutoLockDelay.FIVE_MINUTES },
            { "the restarted app must read the stored 5-minute choice back" },
        )
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        openAdvancedPage(restarted.get())
        compose.onNode(selectedLadderRowContaining("After 5 minutes")).assertExists()
    }

    @Test
    fun aGarbageStoredValueColdLaunchResolvesToOff() {
        // A hand-written (or corrupt) value the ladder does not carry: the
        // repository maps it to off, and a cold launch shows off selected.
        dbAndGet {
            app().database.dao().upsertSetting(
                SettingsEntity(SettingsEntity.AUTO_LOCK_MINUTES, "999"),
            )
        }
        val activity = openSettings()
        openAdvancedPage(activity)
        assertEquals(
            "the garbage stored value must resolve to off",
            AutoLockDelay.OFF,
            viewModelOf(activity).autoLockDelay.value,
        )
        compose.onNode(selectedLadderRowContaining("Off")).assertExists()
    }

    // ---- back navigation: one level at a time ----

    private fun systemBack(activity: MainActivity) {
        activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
    }

    @Test
    fun backFromTheAdvancedPageUnwindsToTheMenuAndOnlyThenToThePlayer() {
        val activity = openSettings()
        openAdvancedPage(activity)
        compose.onNodeWithText("Keep screen on").assertExists()

        // The first back leaves the Advanced options page for the menu
        // (the moved row is gone from view, the Advanced options row is
        // back).
        systemBack(activity)
        compose.onNodeWithText("Play mode").assertIsDisplayed()
        compose.onNodeWithText("Keep screen on").assertDoesNotExist()
        compose.onNodeWithText("Advanced options").assertExists()

        // The second back leaves the menu entirely for the player screen.
        systemBack(activity)
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        compose.onNodeWithText("Advanced options").assertDoesNotExist()
    }

    @Test
    fun backFromAppearanceUnwindsToTheMenuAndOnlyThenToThePlayer() {
        val activity = openSettings()
        // The appearance row is a top-level menu row (below the viewport on
        // the small screen): scroll it into view before clicking.
        compose.onNodeWithText("Appearance").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("Appearance").performClick()
        compose.waitForIdle()
        // The appearance sub-screen (its choices are here, not on the
        // menu).
        compose.onNodeWithText("Uses the phone's own light or dark setting.").assertExists()

        // The first back unwinds to the menu (the appearance row's own
        // row is back), not out of settings...
        systemBack(activity)
        compose.onNodeWithText("Uses the phone's own light or dark setting.").assertDoesNotExist()
        compose.onNodeWithText("Appearance").assertExists()
        compose.onNodeWithText("Keep screen on").assertDoesNotExist()

        // ...and the second leaves the menu for the player screen.
        systemBack(activity)
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        compose.onNodeWithText("Appearance").assertDoesNotExist()
    }

    // ---- the lock: timer, keep-screen-on precedence, the admin gate ----

    @Test
    fun theLockFiresAfterTheConfiguredIdleAndWinsOverKeepScreenOn() {
        val activity = openSettings()
        val vm = viewModelOf(activity)
        var lockCalls = 0
        vm.isDeviceAdminActive = { true }
        vm.lockDevice = { lockCalls++ }
        var fakeNow = 1_000_000L
        vm.clock = { fakeNow }

        // Keep screen on is ON (the setting the lock must win over): the
        // window flag is set.
        vm.setKeepScreenOn(true)
        await(
            { vm.keepScreenOnEffective.value },
            { "the keep-screen-on setting must take effect" },
        )
        assertTrue("the flag must be set while the setting is on", keepScreenOnFlag(activity))

        // Arm the auto-lock at 5 minutes and re-anchor the idle stretch
        // to the fake clock (see [reanchorIdleStretch]).
        vm.setAutoLockDelay(AutoLockDelay.FIVE_MINUTES)
        await(
            { vm.autoLockDelay.value == AutoLockDelay.FIVE_MINUTES },
            { "the auto-lock choice must become 5 minutes" },
        )
        reanchorIdleStretch(vm)

        // One tick before the idle time: nothing happens...
        fakeNow += 5 * 60_000L - 1
        assertFalse("before the idle time the lock must not fire", vm.runAutoLockTick())
        assertEquals(0, lockCalls)
        assertTrue(
            "before the idle time keep-screen-on must hold",
            keepScreenOnFlag(activity),
        )

        // ...at the idle time the lock fires, EVEN THOUGH keep screen on is
        // on: the lock wins, and the app's own flag is released so it does
        // not keep the (now locked) screen awake. A further tick in the
        // same stretch does not re-fire (one lock per stretch).
        fakeNow += 1
        assertTrue("at the idle time the lock must fire", vm.runAutoLockTick())
        assertEquals("the lock must have been applied", 1, lockCalls)
        assertFalse("a further tick in the same stretch must not re-fire", vm.runAutoLockTick())
        assertEquals("still exactly one lock", 1, lockCalls)
        await(
            { !vm.keepScreenOnEffective.value },
            { "after the lock keep-screen-on must be effectively off" },
        )
        ShadowLooper.idleMainLooper()
        assertTrue(
            "the setting still says on",
            vm.keepScreenOn.value,
        )
        assertFalse(
            "but the flag must be released - the lock wins over keep-screen-on",
            keepScreenOnFlag(activity),
        )

        // Any active playback resets it: the suppression is released and
        // keep-screen-on applies as configured again.
        PlayerStateHolder.update { it.copy(status = PlaybackStatus.PLAYING) }
        await(
            { vm.keepScreenOnEffective.value },
            { "playing again must release the lock's claim on the screen" },
        )
        assertTrue(
            "with music playing the flag is back",
            keepScreenOnFlag(activity),
        )
        // Leave the composition idle: this test's last player-state write
        // (the PLAYING reset above) schedules a recomposition that only a
        // pumped frame settles, and an activity torn down with unprocessed
        // recomposer work poisons the next test's Compose idling in this
        // pruned mirror (a later back-press transition then never reaches
        // idle and the rule's waitForIdle runs out its 60 s).
        compose.waitForIdle()
    }

    @Test
    fun aFiredLockDoesNotRefireWhileTheSameStretchLingers() {
        val activity = openSettings()
        val vm = viewModelOf(activity)
        var lockCalls = 0
        vm.isDeviceAdminActive = { true }
        vm.lockDevice = { lockCalls++ }
        var fakeNow = 1_000_000L
        vm.clock = { fakeNow }

        vm.setAutoLockDelay(AutoLockDelay.FIVE_MINUTES)
        await(
            { vm.autoLockDelay.value == AutoLockDelay.FIVE_MINUTES },
            { "the auto-lock choice must become 5 minutes" },
        )
        reanchorIdleStretch(vm)

        fakeNow += 5 * 60_000L
        assertTrue(vm.runAutoLockTick())
        // The same stretch lingers (the user stays idle): the next tick
        // must NOT lock again.
        fakeNow += 5 * 60_000L
        assertFalse("a fired stretch must not re-fire without a playback cycle", vm.runAutoLockTick())
        assertEquals("the lock fires exactly once per idle stretch", 1, lockCalls)
        // Leave the composition idle (see the note in the keep-screen-on
        // test above): the re-anchor's player-state writes left a
        // recomposition unprocessed, and tearing the activity down with
        // pending recomposer work poisons the next test's Compose idling.
        compose.waitForIdle()
    }

    @Test
    fun aColdLaunchWhileMusicIsPlayingSurvivesAndTheStretchArmsFromThePause() {
        // The crash-class input: the shared player state is ALREADY PLAYING
        // when the app is constructed. The engine's player-state collector
        // then delivers its first emission INLINE during construction on
        // the main thread (the scope is Main.immediate), so every flow the
        // first emission can write must be initialised by the time the
        // engine's init block runs - a wrong declaration order throws an
        // uninitialised-property error and kills the cold launch.
        PlayerStateHolder.update {
            it.copy(status = PlaybackStatus.PLAYING, currentLabel = "Lumi test tone")
        }

        // The cold launch survives the inline first emission: the app
        // composes and the settings menu is on screen.
        val activity = openSettings()
        compose.onNodeWithText("Play mode").assertIsDisplayed()
        val vm = viewModelOf(activity)
        var lockCalls = 0
        vm.isDeviceAdminActive = { true }
        vm.lockDevice = { lockCalls++ }
        var fakeNow = 1_000_000L
        vm.clock = { fakeNow }

        // The engine is alive and consistent: while the song plays, the
        // lock has not claimed the screen, so keep screen on applies as
        // configured.
        vm.setKeepScreenOn(true)
        await(
            { vm.keepScreenOnEffective.value },
            { "keep-screen-on must take effect while the song plays" },
        )
        assertTrue(
            "the flag must be set while the song plays",
            keepScreenOnFlag(activity),
        )

        // The auto-lock is armed at 5 minutes and the song's PAUSE starts
        // the idle stretch at the fake clock - the very transition the
        // cold-launched engine saw (no re-anchoring cycle is needed).
        vm.setAutoLockDelay(AutoLockDelay.FIVE_MINUTES)
        await(
            { vm.autoLockDelay.value == AutoLockDelay.FIVE_MINUTES },
            { "the auto-lock choice must become 5 minutes" },
        )
        PlayerStateHolder.update { it.copy(status = PlaybackStatus.PAUSED) }
        ShadowLooper.idleMainLooper()

        // The stretch fired at the configured idle time, exactly once.
        fakeNow += 5 * 60_000L
        assertTrue(
            "the cold-launched engine must arm the stretch from the pause and fire it",
            vm.runAutoLockTick(),
        )
        assertEquals("the lock must have been applied", 1, lockCalls)
        // Leave the composition idle (see the note in the keep-screen-on
        // test above): the player-state writes left a recomposition
        // unprocessed, and tearing the activity down with pending
        // recomposer work poisons the next test's Compose idling.
        compose.waitForIdle()
    }

    @Test
    fun aBackgroundedAppDoesNotLockAndTheHeldStretchFiresOnReturn() {
        val activity = openSettings()
        val vm = viewModelOf(activity)
        var lockCalls = 0
        vm.isDeviceAdminActive = { true }
        vm.lockDevice = { lockCalls++ }
        var fakeNow = 1_000_000L
        vm.clock = { fakeNow }

        vm.setAutoLockDelay(AutoLockDelay.FIVE_MINUTES)
        await(
            { vm.autoLockDelay.value == AutoLockDelay.FIVE_MINUTES },
            { "the auto-lock choice must become 5 minutes" },
        )
        reanchorIdleStretch(vm)

        // The idle time elapses while the user is in another app: the app
        // goes to the background...
        fakeNow += 5 * 60_000L
        drive(activity, "onPause")

        // ...and the elapsed stretch is HELD, not fired: the timeout does
        // not report, nothing locks, and the stretch stays un-fired...
        assertFalse(
            "a backgrounded app must not fire the idle timeout",
            vm.runAutoLockTick(),
        )
        assertEquals(
            "nothing may be locked while the app is backgrounded",
            0,
            lockCalls,
        )

        // ...so when the app comes back to the foreground the pending
        // timeout fires on the first tick - and only there.
        drive(activity, "onResume")
        assertTrue(
            "the held stretch must fire once the app is back in the foreground",
            vm.runAutoLockTick(),
        )
        assertEquals("the lock must have been applied", 1, lockCalls)
        assertFalse("a further tick in the same stretch must not re-fire", vm.runAutoLockTick())
        assertEquals("still exactly one lock", 1, lockCalls)
        // Leave the composition idle (see the note in the keep-screen-on
        // test above): the re-anchor's player-state writes left a
        // recomposition unprocessed, and tearing the activity down with
        // pending recomposer work poisons the next test's Compose idling.
        compose.waitForIdle()
    }

    @Test
    fun anUngrantedAdminDoesNotLockAndTheSettingStillWorks() {
        val activity = openSettings()
        val vm = viewModelOf(activity)
        var lockCalls = 0
        vm.isDeviceAdminActive = { false }
        vm.lockDevice = { lockCalls++ }
        var fakeNow = 1_000_000L
        vm.clock = { fakeNow }

        // Keep screen on is on; the auto-lock is armed; the app goes idle
        // past the idle time.
        vm.setKeepScreenOn(true)
        await(
            { vm.keepScreenOnEffective.value },
            { "the keep-screen-on setting must take effect" },
        )
        vm.setAutoLockDelay(AutoLockDelay.FIVE_MINUTES)
        await(
            { vm.autoLockDelay.value == AutoLockDelay.FIVE_MINUTES },
            { "the auto-lock choice must become 5 minutes" },
        )
        reanchorIdleStretch(vm)
        fakeNow += 5 * 60_000L

        // The timeout fires, but the admin is not granted: nothing locks,
        // and the app keeps working (keep-screen-on is untouched - the
        // setting the user configured still behaves as configured).
        assertTrue("the idle timeout still fires", vm.runAutoLockTick())
        assertEquals("without the admin nothing may be locked", 0, lockCalls)
        assertTrue(
            "without the admin the keep-screen-on flag stays as configured",
            keepScreenOnFlag(activity),
        )

        // The setting stays visible and prompts to enable the permission:
        // the Advanced options page shows the not-enabled state with its
        // action.
        openAdvancedPage(activity)
        compose.onNodeWithText("Screen lock not enabled").assertExists()
        compose.onNodeWithText("Enable screen lock").assertExists()
        compose.onNodeWithText("Auto-lock after idle").assertExists()
    }

    @Test
    fun theEnableFlowFeedsTheAdminStateBackAndFlipsThePage() {
        val activity = openSettings()
        val vm = viewModelOf(activity)
        vm.isDeviceAdminActive = { false }

        openAdvancedPage(activity)
        compose.onNodeWithText("Screen lock not enabled").assertExists()

        // The enable action launches the platform's own consent screen:
        // the view model builds the ACTION_ADD_DEVICE_ADMIN intent with
        // the app's own admin component and an explanation of what is
        // being granted. (The real launch of the consent screen is an
        // on-device verification item.)
        val intent = vm.deviceAdminIntent()
        assertEquals(
            DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN,
            intent.action,
        )
        assertEquals(vm.deviceAdminComponent, intent.getParcelableExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN))
        assertTrue(
            "the consent screen must explain what is being granted",
            !intent.getStringExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION).isNullOrEmpty(),
        )

        // The consent screen comes back with a grant: the view model
        // re-checks the platform's real state (the seam now answers
        // active) and the page flips from the prompt to the enabled state.
        vm.isDeviceAdminActive = { true }
        vm.onDeviceAdminResult(Activity.RESULT_OK)
        await(
            { vm.deviceAdminActive.value },
            { "the device admin must read as active after the grant" },
        )
        compose.waitForIdle()
        compose.onNodeWithText("Screen lock enabled").assertExists()
        compose.onNodeWithText("Screen lock not enabled").assertDoesNotExist()
    }
}
