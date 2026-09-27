package com.lumi.player.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lumi.player.data.AutoLockDelay
import com.lumi.player.data.AutoLockTimer
import com.lumi.player.device.LumiDeviceAdminReceiver
import com.lumi.player.player.PlayerStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The auto-lock engine (the System setting's "Auto-lock after idle"): the
 * [AutoLockTimer]'s decision logic (pure; the wall clock is fed in through
 * [clock]) driven from the shared player state so the idle time is unit-
 * testable and survives the settings screens' navigation: the stored delay
 * ([autoLockDelay], passed in by [LumiViewModel]) is applied to the timer
 * whenever it lands (or changes), every playback change resets/arms the
 * idle stretch, and a loop ticks the timer until the stretch's idle time
 * elapses (the lock then wins over keep-screen-on - see
 * [runAutoLockTick]). The loop runs for the view model's lifetime (the
 * app's process), so the idle time is measured while the app sits open and
 * idle, foreground or background; only a process death loses the running
 * stretch.
 *
 * **The lock fires only from the foreground**: while the app is
 * backgrounded the tick holds an elapsed stretch (its fired flag is not
 * consumed), so a timeout that elapses while the user is in another app
 * never force-locks the screen mid-session; it fires on the first tick
 * once the app is back in the foreground.
 *
 * **The lock mechanism is a device admin**: a normal app cannot lock the
 * screen, so the real [lockDevice] seam is DevicePolicyManager.lockNow,
 * only valid while the app's screen-lock admin ([deviceAdminComponent])
 * is active; a timeout that fires without the admin does not lock (the
 * app keeps working, the setting stays visible).
 *
 * INIT-ORDER SHARP EDGE (a production crash class): this class's init
 * block and EVERY StateFlow it can touch (the seams,
 * [deviceAdminComponent], [_deviceAdminActive],
 * [_autoLockKeepScreenSuppressed], [keepScreenOnEffective]) sit AFTER
 * those properties' initializers in source order - the collectors run on
 * the view model's scope (Main.immediate), so the player-state collector's
 * first emission is delivered INLINE during construction on the main
 * thread, and a construction that starts while music is already playing
 * would write [_autoLockKeepScreenSuppressed] before the property's own
 * initializer ran (a cold-start-during-playback crash).
 */
class AutoLockEngine(
    private val context: Context,
    autoLockDelay: StateFlow<AutoLockDelay>,
    keepScreenOn: StateFlow<Boolean>,
    scope: CoroutineScope,
) {

    private val timer = AutoLockTimer()

    // Test seams (the real ones ask the platform's DevicePolicyManager):
    // the device-admin state check, the actual screen lock (only valid
    // while the admin is active), and the wall clock the idle timer reads.
    var isDeviceAdminActive: (ComponentName) -> Boolean = { admin ->
        (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager)
            .isAdminActive(admin)
    }

    var lockDevice: () -> Unit = {
        (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager)
            .lockNow()
    }

    var clock: () -> Long = System::currentTimeMillis

    /**
     * Whether the host activity is in the foreground right now
     * ([MainActivity] keeps it in sync from onResume / onPause): the
     * auto-lock may lock the screen only while the app is in the
     * foreground - an idle stretch that elapses while the user is in
     * another app is held, not fired, and fires on the first foreground
     * tick.
     */
    var appInForeground: Boolean = false

    /** The app's own screen-lock device admin (the auto-lock's consent target). */
    val deviceAdminComponent: ComponentName =
        ComponentName(context.packageName, LumiDeviceAdminReceiver::class.java.name)

    /**
     * Whether the app's screen-lock device admin is active right now. The
     * lock can only happen while it is active; the setting stays visible
     * and usable either way (the System page prompts to enable it when it
     * is not, and the app keeps working without the admin).
     */
    private val _deviceAdminActive =
        MutableStateFlow(isDeviceAdminActive(deviceAdminComponent))
    val deviceAdminActive: StateFlow<Boolean> = _deviceAdminActive.asStateFlow()

    /**
     * The auto-lock's claim on the screen (the lock wins over keep
     * screen-on): set when the idle timeout fires and the lock is
     * applied, cleared when music plays again - until then the app's own
     * keep-screen-on flag must not keep the (locked) screen awake.
     */
    private val _autoLockKeepScreenSuppressed = MutableStateFlow(false)
    val autoLockKeepScreenSuppressed: StateFlow<Boolean> =
        _autoLockKeepScreenSuppressed.asStateFlow()

    /**
     * Whether the window should carry FLAG_KEEP_SCREEN_ON right now: the
     * user's keep-screen-on choice AND the auto-lock not having claimed the
     * screen (the lock wins over keep-screen-on until music plays again).
     */
    val keepScreenOnEffective: StateFlow<Boolean> =
        keepScreenOn.combine(autoLockKeepScreenSuppressed) { on, suppressed ->
            on && !suppressed
        }
            .stateIn(scope, SharingStarted.Eagerly, false)

    // The engine's collectors (the stored delay, the shared player state,
    // and the tick loop). This init block runs AFTER every property it can
    // touch (property initializers and init blocks run in source order),
    // so the collectors cannot write a not-yet-initialized flow on an
    // inline first emission.
    init {
        scope.launch {
            autoLockDelay.collect { delay ->
                applyAutoLockDelayToTimer(delay)
            }
        }
        scope.launch {
            PlayerStateHolder.state.collect { state ->
                onPlaybackStatusForAutoLock(state.isPlaying)
            }
        }
        scope.launch {
            while (true) {
                delay(AUTO_LOCK_TICK_MS)
                runAutoLockTick()
            }
        }
    }

    /**
     * Feeds the auto-lock timer with a playback state change (the engine's
     * own collector of the shared player state forwards every change): an
     * active playback resets the idle stretch and releases the keep-screen-
     * on suppression; an inactive state starts (or keeps) the stretch.
     */
    private fun onPlaybackStatusForAutoLock(isPlaying: Boolean) {
        val now = clock()
        timer.onPlaybackStatus(isPlaying, now)
        if (isPlaying) _autoLockKeepScreenSuppressed.value = false
    }

    /**
     * One idle check: the production loop calls this every
     * [AUTO_LOCK_TICK_MS]; tests drive it with a faked clock. Returns true
     * when the idle timeout fired on this tick. The lock is applied only
     * while the activity is in the foreground: while it is not, an elapsed
     * stretch is HELD - the tick reports nothing and the stretch stays
     * un-fired - so it fires on the first tick once the app is back in the
     * foreground, never mid-way through the user's time in another app.
     */
    fun runAutoLockTick(): Boolean {
        if (!appInForeground) return false
        return if (timer.tick(clock())) {
            performAutoLock()
            true
        } else {
            false
        }
    }

    /** The idle timeout fired: lock the screen - or not, when the admin is
     * not active (the app keeps working, the setting stays visible). */
    private fun performAutoLock() {
        val adminActive = isDeviceAdminActive(deviceAdminComponent)
        _deviceAdminActive.value = adminActive
        if (adminActive) {
            lockDevice()
            // The lock wins over keep-screen-on: the screen is being locked
            // now, so the app's own keep-screen-on flag must not keep it
            // awake (and must not be re-applied while the stretch lasts).
            _autoLockKeepScreenSuppressed.value = true
            Log.i(TAG, "Auto-lock: the idle timeout fired and the screen was locked")
        } else {
            Log.w(
                TAG,
                "Auto-lock: the idle timeout fired but the screen-lock permission " +
                    "(device admin) is not active - the screen stays unlocked",
            )
        }
    }

    /** Applies [delay] to the timer (or disarms it on zero) against the
     * current playback state: the stored-delay collector and the view
     * model's [setAutoLockDelay] both go through it, so a running stretch
     * follows a new choice immediately. */
    fun applyAutoLockDelayToTimer(delay: AutoLockDelay) {
        val now = clock()
        timer.setDelay(delay.minutes * 60_000L, now)
        timer.onPlaybackStatus(PlayerStateHolder.state.value.isPlaying, now)
    }

    /**
     * Re-checks the screen-lock admin's real state with the platform:
     * called when the System page opens (a revocation can happen behind the
     * app's back) and after the enable flow's result comes back.
     */
    fun refreshDeviceAdminState() {
        _deviceAdminActive.value = isDeviceAdminActive(deviceAdminComponent)
    }

    /**
     * The platform's device-admin enable intent: launching it opens the
     * phone's own consent screen - the app is being granted the ability to
     * lock the screen, so the explanation states exactly that. The result
     * is fed back through [onDeviceAdminResult].
     */
    fun deviceAdminIntent(): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, deviceAdminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "lumi is asking for permission to lock the phone's screen after " +
                    "music has stopped playing for the time you choose in the System setting.",
            )
        }

    /** The device-admin enable flow's result: the platform's real state is
     * the authority, so re-check it (a cancel and a grant both land here). */
    fun onDeviceAdminResult(resultCode: Int) {
        refreshDeviceAdminState()
    }

    private companion object {
        /** How often the auto-lock idle timer is checked. */
        const val AUTO_LOCK_TICK_MS = 1_000L

        private const val TAG = "LumiViewModel"
    }
}
