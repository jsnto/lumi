package com.lumi.player.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.lumi.player.player.PlaybackStatus
import com.lumi.player.player.PlayerService
import com.lumi.player.player.PlayerStateHolder
import com.lumi.player.player.ScreenOffPolicy
import com.lumi.player.player.ScreenOffVerdict

/**
 * The manifest-registered receiver for ACTION_SCREEN_OFF: the device's
 * screen going dark is the lock sequence starting, so the currently playing
 * song is paused at that moment. Stock Android exposes no pre-lock signal,
 * so the screen-off instant is the lock moment - this pauses AT it, not
 * ahead of it.
 *
 * Two registration surfaces funnel into this one receiver. The manifest
 * entry is the classic reliable surface: ACTION_SCREEN_OFF is exempt from
 * the Android 8+ implicit-broadcast background restrictions, and the
 * foreground PlayerService keeps the process alive while a song plays.
 * Recent platform builds, however, send the system's SCREEN_OFF broadcast
 * with FLAG_RECEIVER_REGISTERED_ONLY (device-verified on the API 37 Pixel
 * 7 Pro: the static receiver is excluded from resolution and only
 * runtime-registered receivers get the broadcast), so the app also
 * registers this receiver at runtime from [com.lumi.player.LumiApp] for as
 * long as the process is alive. Whichever surface (or both) delivers the
 * broadcast reaches the same decision logic: the pause is idempotent, and
 * the shared recheck state dedupes a double delivery.
 *
 * The broadcast itself is NOT trusted on its own (on builds where it is
 * not a protected broadcast, any app can emit it): the verification runs
 * through [ScreenOffPolicy] against PowerManager.isInteractive() before
 * anything touches playback.
 *
 * The pause goes through the service's own ACTION_PAUSE entry point - the
 * existing pause machinery, the same path the presence poll uses for a tag
 * leaving range. It pauses without ending the session (a re-read of the
 * active tag resumes the paused list) and is idempotent, so it is safe on
 * every service state, including an empty queue (the service stops itself
 * there). When the shared state holds no session (IDLE - the initial value
 * and the exact value a stop writes), the service start is skipped at both
 * call sites: there is nothing to pause, and a startService from a
 * backgrounded process that holds no foreground service would throw - the
 * delayed recheck in particular runs outside the implicit-broadcast start
 * window.
 * There is deliberately NO resume on SCREEN_ON: the user resumes explicitly.
 */
class ScreenOffReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_SCREEN_OFF) return
        val appContext = context.applicationContext
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        when (ScreenOffPolicy.onScreenOff(pm.isInteractive)) {
            ScreenOffVerdict.Pause -> {
                cancelPendingRecheck()
                Log.i(TAG, "screen off confirmed - pausing playback")
                pauseThroughService(appContext)
            }

            ScreenOffVerdict.Recheck -> {
                Log.i(TAG, "still interactive at broadcast time - rechecking after the race window")
                scheduleRecheck(appContext)
            }

            ScreenOffVerdict.Ignore -> Unit
        }
    }

    /**
     * The receiver instance does not outlive the broadcast, so the recheck
     * is posted through the companion's main-looper handler and captures
     * the application context: the delayed runnable is the only state that
     * must survive onReceive returning. A new SCREEN_OFF within the window
     * replaces the pending recheck, so the delayed reading fires exactly
     * once.
     */
    private fun scheduleRecheck(appContext: Context) {
        cancelPendingRecheck()
        val recheck = Runnable {
            pendingRecheck = null
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            when (ScreenOffPolicy.onRecheck(pm.isInteractive)) {
                ScreenOffVerdict.Pause -> {
                    Log.i(TAG, "recheck: the screen is off - pausing playback")
                    pauseThroughService(appContext)
                }

                ScreenOffVerdict.Ignore ->
                    Log.i(TAG, "recheck: still interactive - ignoring the screen-off report")

                // [ScreenOffPolicy.onRecheck] never issues one; the branch
                // keeps the when exhaustive (it is the lambda's expression).
                ScreenOffVerdict.Recheck -> Unit
            }
        }
        pendingRecheck = recheck
        handler.postDelayed(recheck, ScreenOffPolicy.RECHECK_DELAY_MS)
    }

    companion object {
        private const val TAG = "ScreenOffReceiver"

        private val handler = Handler(Looper.getMainLooper())
        private var pendingRecheck: Runnable? = null

        private fun cancelPendingRecheck() {
            pendingRecheck?.let { handler.removeCallbacks(it) }
            pendingRecheck = null
        }

        /**
         * The existing pause machinery: the service's own ACTION_PAUSE
         * intent. Skipped when the shared state holds no session (IDLE):
         * there is nothing to pause, and starting the service from a
         * backgrounded process without a running foreground service would
         * throw (the recheck's call site in particular runs outside the
         * implicit-broadcast start window).
         */
        private fun pauseThroughService(context: Context) {
            if (PlayerStateHolder.state.value.status == PlaybackStatus.IDLE) {
                Log.i(TAG, "no active session - nothing to pause")
                return
            }
            context.startService(
                Intent(context, PlayerService::class.java).setAction(PlayerService.ACTION_PAUSE),
            )
        }
    }
}
