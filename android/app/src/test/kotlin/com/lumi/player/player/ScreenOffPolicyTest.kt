package com.lumi.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure decision logic for the screen-off pause: the SCREEN_OFF broadcast is
 * never believed on its own - only the PowerManager.isInteractive()
 * readings decide (the spoof guard + the short race where isInteractive is
 * still true right after a genuine screen-off).
 */
class ScreenOffPolicyTest {

    @Test
    fun screenOffWithANonInteractiveScreenPausesAtOnce() {
        assertEquals(
            "a non-interactive reading at broadcast time confirms the screen off",
            ScreenOffVerdict.Pause,
            ScreenOffPolicy.onScreenOff(isInteractive = false),
        )
    }

    @Test
    fun screenOffThatIsStillInteractiveIsRecheckedAndIgnoredWhileItStaysInteractive() {
        assertEquals(
            "an interactive reading at broadcast time decides nothing yet",
            ScreenOffVerdict.Recheck,
            ScreenOffPolicy.onScreenOff(isInteractive = true),
        )
        assertEquals(
            "a recheck that is still interactive must not pause",
            ScreenOffVerdict.Ignore,
            ScreenOffPolicy.onRecheck(isInteractive = true),
        )
    }

    @Test
    fun aSpoofedScreenOffWhileTheScreenIsGenuinelyOnIsIgnored() {
        // Any app can emit SCREEN_OFF (it is not a protected broadcast). With
        // the screen genuinely on, both readings are interactive: the
        // broadcast-time reading only buys the recheck, and the recheck's
        // interactive reading drops the report - the spoof never pauses.
        assertEquals(
            ScreenOffVerdict.Recheck,
            ScreenOffPolicy.onScreenOff(isInteractive = true),
        )
        assertEquals(
            "a spoofed SCREEN_OFF must never reach the pause",
            ScreenOffVerdict.Ignore,
            ScreenOffPolicy.onRecheck(isInteractive = true),
        )
    }

    @Test
    fun aGenuineScreenOffThatLagsTheBroadcastPausesOnTheRecheck() {
        // The race the recheck exists for: isInteractive is still true right
        // after the broadcast but reports the screen off once the race window
        // has passed.
        assertEquals(ScreenOffVerdict.Recheck, ScreenOffPolicy.onScreenOff(isInteractive = true))
        assertEquals(
            "a genuine screen-off that lags the broadcast must still pause",
            ScreenOffVerdict.Pause,
            ScreenOffPolicy.onRecheck(isInteractive = false),
        )
    }

    @Test
    fun theRecheckDelayIsAShortRaceWindow() {
        // A few hundred ms: long enough for the isInteractive race to settle,
        // short enough that the pause still lands at the lock moment.
        assertTrue(
            "the recheck delay must be a short race window (got ${ScreenOffPolicy.RECHECK_DELAY_MS}ms)",
            ScreenOffPolicy.RECHECK_DELAY_MS in 100L..1_000L,
        )
    }
}
