package com.lumi.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auto-lock setting's pure logic: the stored-value parsing (the
 * settings-table contract - off is the default and the fallback for every
 * unrecognized value) and the idle timer's decision (start / reset /
 * timeout, one fire per idle stretch, the delay honoured mid-stretch).
 *
 * The timer is fed wall-clock timestamps instead of reading a clock, so the
 * tests drive it with fake times.
 */
class AutoLockTest {

    private companion object {
        const val FIVE_MINUTES_MS = 5 * 60_000L
        const val TEN_MINUTES_MS = 10 * 60_000L
    }

    // ---- the stored-value contract ----

    @Test
    fun unsetValueResolvesToOff() {
        assertEquals(AutoLockDelay.OFF, autoLockDelayFromStored(null))
    }

    @Test
    fun blankOrGarbageStoredValuesResolveToOff() {
        assertEquals(AutoLockDelay.OFF, autoLockDelayFromStored(""))
        assertEquals(AutoLockDelay.OFF, autoLockDelayFromStored("   "))
        assertEquals(AutoLockDelay.OFF, autoLockDelayFromStored("bogus"))
        assertEquals(AutoLockDelay.OFF, autoLockDelayFromStored("ten"))
    }

    @Test
    fun aMinuteCountTheLadderDoesNotCarryResolvesToOff() {
        // A plain number that is not one of the ladder's options is not a
        // valid pick (the view model only ever stores ladder values): off.
        assertEquals(AutoLockDelay.OFF, autoLockDelayFromStored("1"))
        assertEquals(AutoLockDelay.OFF, autoLockDelayFromStored("999"))
        assertEquals(AutoLockDelay.OFF, autoLockDelayFromStored("-5"))
    }

    @Test
    fun everyLadderOptionRoundTrips() {
        AutoLockDelay.entries.forEach { option ->
            assertEquals(
                "the stored value of $option must resolve back to it",
                option,
                autoLockDelayFromStored(option.storedValue()),
            )
        }
    }

    @Test
    fun whitespaceVariedStoredValuesResolveToTheirOptions() {
        assertEquals(AutoLockDelay.OFF, autoLockDelayFromStored(" 0 "))
        assertEquals(AutoLockDelay.FIVE_MINUTES, autoLockDelayFromStored(" 5 "))
        assertEquals(AutoLockDelay.TEN_MINUTES, autoLockDelayFromStored("10"))
        assertEquals(AutoLockDelay.FIFTEEN_MINUTES, autoLockDelayFromStored("15"))
        assertEquals(AutoLockDelay.THIRTY_MINUTES, autoLockDelayFromStored("30"))
    }

    // ---- the idle timer ----

    @Test
    fun aFreshTimerOffNeverFires() {
        val timer = AutoLockTimer()
        assertFalse(timer.tick(1_000L))
        // Idle but no delay configured: still nothing to fire.
        timer.onPlaybackStatus(false, 1_000L)
        assertFalse(timer.tick(100_000L))
    }

    @Test
    fun anActivePlaybackNeverFires() {
        val timer = AutoLockTimer()
        timer.setDelay(FIVE_MINUTES_MS, 1_000L)
        timer.onPlaybackStatus(true, 1_000L)
        assertFalse(
            "a playing song must never trigger the lock",
            timer.tick(100_000L),
        )
        assertFalse(timer.tick(1_000_000L))
    }

    @Test
    fun theTimeoutFiresExactlyAtTheConfiguredIdleTimeAndOnlyOnce() {
        val timer = AutoLockTimer()
        val start = 1_000L
        timer.setDelay(FIVE_MINUTES_MS, start)
        timer.onPlaybackStatus(false, start)

        assertFalse("before the idle time", timer.tick(start + FIVE_MINUTES_MS - 1))
        assertTrue("at the idle time", timer.tick(start + FIVE_MINUTES_MS))
        assertFalse("no re-fire for the same stretch", timer.tick(start + FIVE_MINUTES_MS + 10_000L))
        assertFalse("still no re-fire later", timer.tick(start + TEN_MINUTES_MS))
    }

    @Test
    fun anActivePlaybackResetsARunningStretch() {
        val timer = AutoLockTimer()
        val start = 1_000L
        timer.setDelay(FIVE_MINUTES_MS, start)
        timer.onPlaybackStatus(false, start)

        // A song starts mid-stretch: the stretch resets...
        timer.onPlaybackStatus(true, start + 60_000L)
        assertFalse(
            "the reset stretch must not fire at the old deadline",
            timer.tick(start + FIVE_MINUTES_MS),
        )
        // ...and a later pause measures a FRESH stretch from the pause.
        timer.onPlaybackStatus(false, start + 60_000L)
        assertFalse(
            "the fresh stretch counts from the pause",
            timer.tick(start + 60_000L + FIVE_MINUTES_MS - 1),
        )
        assertTrue(
            "and fires the full idle time after it",
            timer.tick(start + 60_000L + FIVE_MINUTES_MS),
        )
    }

    @Test
    fun aFiredStretchReArmsAfterAPlaybackCycle() {
        val timer = AutoLockTimer()
        val start = 1_000L
        timer.setDelay(FIVE_MINUTES_MS, start)
        timer.onPlaybackStatus(false, start)
        assertTrue(timer.tick(start + FIVE_MINUTES_MS))

        // The stretch fired (the screen locked). The user unlocks and plays
        // again, then the song stops: the NEXT idle stretch must arm and
        // fire again.
        val replay = start + FIVE_MINUTES_MS + 10_000L
        timer.onPlaybackStatus(true, replay)
        timer.onPlaybackStatus(false, replay)
        assertFalse(timer.tick(replay + FIVE_MINUTES_MS - 1))
        assertTrue(timer.tick(replay + FIVE_MINUTES_MS))
    }

    @Test
    fun turningTheDelayOffMidStretchDisarmsIt() {
        val timer = AutoLockTimer()
        val start = 1_000L
        timer.setDelay(FIVE_MINUTES_MS, start)
        timer.onPlaybackStatus(false, start)
        assertFalse("not before the idle time", timer.tick(start + 60_000L))

        // The user switches the setting off while idle: nothing may fire.
        timer.setDelay(0L, start + 60_000L)
        assertFalse(timer.tick(start + FIVE_MINUTES_MS))
        assertFalse(timer.tick(start + TEN_MINUTES_MS))
    }

    @Test
    fun aShortenedDelayMidStretchFiresAtTheShortenedDeadline() {
        val timer = AutoLockTimer()
        val start = 1_000L
        timer.setDelay(TEN_MINUTES_MS, start)
        timer.onPlaybackStatus(false, start)

        // After four idle minutes the user picks the 5-minute option: the
        // stretch keeps its start, so the 5-minute deadline is already
        // past start + 5 minutes (not the old 10).
        timer.setDelay(FIVE_MINUTES_MS, start + 4 * 60_000L)
        assertFalse(
            "the shortened deadline is start + 5 minutes",
            timer.tick(start + FIVE_MINUTES_MS - 1),
        )
        assertTrue(timer.tick(start + FIVE_MINUTES_MS))
    }

    @Test
    fun aLengthenedDelayMidStretchWaitsForTheNewDeadline() {
        val timer = AutoLockTimer()
        val start = 1_000L
        timer.setDelay(FIVE_MINUTES_MS, start)
        timer.onPlaybackStatus(false, start)

        // After two idle minutes the user picks the 10-minute option: the
        // old 5-minute deadline no longer counts.
        timer.setDelay(TEN_MINUTES_MS, start + 2 * 60_000L)
        assertFalse(
            "the old deadline is void after the lengthening",
            timer.tick(start + FIVE_MINUTES_MS),
        )
        assertTrue(
            "the full new delay is measured from the stretch's start",
            timer.tick(start + TEN_MINUTES_MS),
        )
    }

    @Test
    fun enablingADelayWhileAlreadyIdleMeasuresFromTheExistingStretch() {
        val timer = AutoLockTimer()
        val start = 1_000L
        // The app has been idle (no playback) with the setting off for an
        // hour; the user then enables the 5-minute option. The condition
        // "no song has been playing for 5 minutes" is already met, so the
        // next tick fires.
        timer.onPlaybackStatus(false, start)
        timer.setDelay(FIVE_MINUTES_MS, start + 60 * 60_000L)
        assertTrue(
            "the idle span already exceeds the new delay",
            timer.tick(start + 60 * 60_000L),
        )
    }

    @Test
    fun pausedAndStoppedBothCountAsIdleWithoutResettingTheStretch() {
        val timer = AutoLockTimer()
        val start = 1_000L
        timer.setDelay(FIVE_MINUTES_MS, start)
        timer.onPlaybackStatus(true, start)
        timer.onPlaybackStatus(false, start + 60_000L) // paused
        // A stop (IDLE) while already idle is not a reset: the stretch
        // keeps its start at the pause.
        timer.onPlaybackStatus(false, start + 3 * 60_000L)
        assertFalse(timer.tick(start + 60_000L + FIVE_MINUTES_MS - 1))
        assertTrue(timer.tick(start + 60_000L + FIVE_MINUTES_MS))
    }
}
