package com.lumi.player.device

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.player.PlaybackStatus
import com.lumi.player.player.PlayerService
import com.lumi.player.player.PlayerStateHolder
import com.lumi.player.player.ScreenOffPolicy
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The manifest-registered SCREEN_OFF receiver: a genuine screen-off pauses
 * through the service's own ACTION_PAUSE (the existing pause machinery -
 * the started-service intent the app would deliver), a report that is still
 * interactive after the delayed recheck never touches the service (the
 * spoof guard), and the recheck itself survives the receiver instance. A
 * screen-off with no active session (the shared state IDLE) starts nothing
 * at all - no background service start with nothing to pause, on the
 * direct delivery AND the delayed recheck (the reachable crash path).
 *
 * The PowerManager.isInteractive() reading is faked through its shadow, the
 * recheck's delay is advanced with the paused main looper, and the service
 * intent is observed the way the UI tests observe the view model's playback
 * path (the started-service record on the application shadow).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumiApp::class)
class ScreenOffReceiverTest {

    private val receiver = ScreenOffReceiver()
    private val context: LumiApp = ApplicationProvider.getApplicationContext()
    private val pm: PowerManager
        get() = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Before
    fun setUp() {
        shadowOf(context).clearStartedServices()
        // The screen is on unless a test says otherwise.
        shadowOf(pm).setIsInteractive(true)
        // A session is active unless a test says otherwise: the pause starts
        // the service only when there is a session to pause.
        PlayerStateHolder.update { it.copy(status = PlaybackStatus.PLAYING) }
    }

    private fun screenOff() = Intent(Intent.ACTION_SCREEN_OFF)

    /** Advances the main looper past the recheck's race window. */
    private fun idlePastTheRecheck() {
        shadowOf(Looper.getMainLooper())
            .idleFor(Duration.ofMillis(ScreenOffPolicy.RECHECK_DELAY_MS + 100L))
    }

    @Test
    fun aGenuineScreenOffPausesThroughTheService() {
        shadowOf(pm).setIsInteractive(false)

        receiver.onReceive(context, screenOff())

        val intent = shadowOf(context).nextStartedService
        assertEquals(
            "the pause must go through the service's own ACTION_PAUSE",
            PlayerService.ACTION_PAUSE,
            intent?.action,
        )
        assertEquals(
            "the pause must target the PlayerService",
            PlayerService::class.java.name,
            intent?.component?.className,
        )
    }

    @Test
    fun aScreenOffThatLagsTheBroadcastPausesOnTheRecheck() {
        // Interactive at broadcast time (the race window)...
        receiver.onReceive(context, screenOff())
        assertNull(
            "an interactive first reading must not pause immediately",
            shadowOf(context).nextStartedService,
        )
        // ...non-interactive once the race settles: the delayed recheck pauses.
        shadowOf(pm).setIsInteractive(false)
        idlePastTheRecheck()
        assertEquals(
            PlayerService.ACTION_PAUSE,
            shadowOf(context).nextStartedService?.action,
        )
    }

    @Test
    fun aSpoofedScreenOffWhileTheScreenIsGenuinelyOnNeverTouchesTheService() {
        // isInteractive stays true at the broadcast AND at the recheck: the
        // report is a spoof (or the screen is genuinely on) and nothing
        // happens - the service is never even started.
        receiver.onReceive(context, screenOff())
        idlePastTheRecheck()
        assertNull("a spoofed SCREEN_OFF must not start the service",
            shadowOf(context).nextStartedService)
    }

    @Test
    fun aGenuineScreenOffWithNoActiveSessionStartsNothing() {
        // The user stopped the song (the shared state is IDLE - the service
        // is gone) and left the app backgrounded: locking the phone must
        // not startService the service - there is nothing to pause, and a
        // backgrounded process without a foreground service may not start
        // one.
        PlayerStateHolder.update { it.copy(status = PlaybackStatus.IDLE) }
        shadowOf(pm).setIsInteractive(false)

        receiver.onReceive(context, screenOff())

        assertNull(
            "no session means no service start",
            shadowOf(context).nextStartedService,
        )
    }

    @Test
    fun aRecheckWithNoActiveSessionStartsNothing() {
        // The reachable crash path: the recheck runs outside the
        // implicit-broadcast start window, so with no session (IDLE) it
        // must not start the service either.
        PlayerStateHolder.update { it.copy(status = PlaybackStatus.IDLE) }

        receiver.onReceive(context, screenOff())
        assertNull(
            "an interactive first reading must not pause immediately",
            shadowOf(context).nextStartedService,
        )
        shadowOf(pm).setIsInteractive(false)
        idlePastTheRecheck()
        assertNull(
            "a recheck with no session must not start the service",
            shadowOf(context).nextStartedService,
        )
    }

    @Test
    fun aNewScreenOffBroadcastWithinTheWindowReplacesThePendingRecheck() {
        // Two reports within the race window (e.g. a spoof followed by the
        // real thing): the second replaces the first's recheck, so the
        // delayed reading fires exactly once and pauses exactly once.
        receiver.onReceive(context, screenOff())
        receiver.onReceive(context, screenOff())
        shadowOf(pm).setIsInteractive(false)
        idlePastTheRecheck()
        val started = shadowOf(context).allStartedServices
        assertEquals("the replaced recheck must not double-fire", 1, started.size)
        assertEquals(PlayerService.ACTION_PAUSE, started[0].action)
    }

    @Test
    fun aConfirmedScreenOffCancelsThePendingRecheck() {
        // A spoof (still interactive) arms the recheck; a genuine screen-off
        // (non-interactive) that follows within the window pauses at once
        // and cancels the pending recheck - no second pause after it.
        receiver.onReceive(context, screenOff())
        shadowOf(pm).setIsInteractive(false)
        receiver.onReceive(context, screenOff())
        // peek: nextStartedService would consume the intent the size
        // assertion below still needs to see.
        assertEquals(
            "the confirmed screen-off pauses at once",
            PlayerService.ACTION_PAUSE,
            shadowOf(context).peekNextStartedService()?.action,
        )
        idlePastTheRecheck()
        assertEquals(
            "the cancelled recheck must not pause a second time",
            1,
            shadowOf(context).allStartedServices.size,
        )
    }

    @Test
    fun otherActionsAreIgnored() {
        // The receiver is SCREEN_OFF-only (there is deliberately no
        // screen-on resume: the user resumes explicitly).
        receiver.onReceive(context, Intent(Intent.ACTION_SCREEN_ON))
        shadowOf(pm).setIsInteractive(false)
        idlePastTheRecheck()
        assertNull("a SCREEN_ON must not start the service",
            shadowOf(context).nextStartedService)
    }
}
