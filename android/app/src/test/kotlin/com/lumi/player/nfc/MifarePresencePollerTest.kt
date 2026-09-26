package com.lumi.player.nfc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for the two-strike debounce and the poll-loop logic of
 * [MifarePresencePoller.loop], driven by a fake read and a fake clock (no
 * NFC hardware, no real delays).
 *
 * The fake read consumes one outcome per read: `true` = the tag answered
 * (still in range), `false` = the read threw IOException (the tag left).
 * Outcomes are consumed in order - a failed first strike and its retry read
 * each consume one.
 */
class MifarePresencePollerTest {

    private class FakeClock {
        val delays = mutableListOf<Long>()
        suspend fun delay(ms: Long) {
            delays.add(ms)
        }
    }

    private class RunResult(
        val stillPresent: Int,
        val confirmedRemoved: Int,
        val delays: List<Long>,
    )

    /** Runs the loop to completion (it ends when the removal is confirmed). */
    private fun runToCompletion(
        outcomes: List<Boolean>,
        readIntervalMs: Long = MifarePresencePoller.READ_INTERVAL_MS,
        retryDelayMs: Long = MifarePresencePoller.RETRY_DELAY_MS,
    ): RunResult {
        require(outcomes.any { !it }) { "outcomes must end in a confirmed removal" }
        val clock = FakeClock()
        var index = 0
        var stillPresent = 0
        var confirmedRemoved = 0
        val readPage0: () -> Unit = {
            val ok = outcomes[index.coerceAtMost(outcomes.size - 1)]
            index++
            if (!ok) throw IOException("tag out of range")
        }
        runBlocking {
            MifarePresencePoller.loop(
                readPage0 = readPage0,
                onStillPresent = { stillPresent++ },
                onConfirmedRemoved = { confirmedRemoved++ },
                readIntervalMs = readIntervalMs,
                retryDelayMs = retryDelayMs,
                delayFn = clock::delay,
            )
        }
        return RunResult(stillPresent, confirmedRemoved, clock.delays)
    }

    @Test
    fun successfulReads_keepPlaying_andConfirmRemovalAtTheEnd() {
        // Five answered reads, then the tag leaves: the first failed read is
        // debounced (one retry after the retry delay), the failed retry
        // confirms the removal.
        val result = runToCompletion(listOf(true, true, true, true, true, false, false))
        assertEquals(5, result.stillPresent)
        assertEquals(1, result.confirmedRemoved)
        // Each answered read waits the read interval; the failed strike
        // waits the retry delay before the single retry read.
        assertEquals(
            List(5) { MifarePresencePoller.READ_INTERVAL_MS } +
                listOf(MifarePresencePoller.RETRY_DELAY_MS),
            result.delays,
        )
    }

    @Test
    fun singleFailedRead_debouncesWithOneRetry_andResets() {
        // Read OK, then a blip (strike 1 fails, the retry succeeds), then
        // the tag genuinely leaves (strike 2 fails, the retry fails).
        // The successful retry must reset the loop so the NEXT failure
        // starts a fresh two-strike sequence.
        val result = runToCompletion(listOf(true, false, true, true, false, false))
        assertEquals(3, result.stillPresent)
        assertEquals(1, result.confirmedRemoved)
        assertEquals(
            listOf(
                MifarePresencePoller.READ_INTERVAL_MS, // after read 1
                MifarePresencePoller.RETRY_DELAY_MS,   // before the retry read
                MifarePresencePoller.READ_INTERVAL_MS, // after the successful retry
                MifarePresencePoller.READ_INTERVAL_MS, // after read 3
                MifarePresencePoller.RETRY_DELAY_MS,   // before the confirming retry
            ),
            result.delays,
        )
    }

    @Test
    fun immediateRemoval_confirmedExactlyOnce() {
        // The tag is already gone when the loop starts: both reads fail and
        // the removal is reported exactly once.
        val result = runToCompletion(listOf(false, false))
        assertEquals(0, result.stillPresent)
        assertEquals(1, result.confirmedRemoved)
        assertEquals(listOf(MifarePresencePoller.RETRY_DELAY_MS), result.delays)
    }

    @Test
    fun loop_usesTheConfiguredIntervals() {
        val result = runToCompletion(
            outcomes = listOf(true, false, false),
            readIntervalMs = 50,
            retryDelayMs = 25,
        )
        assertEquals(1, result.stillPresent)
        assertEquals(1, result.confirmedRemoved)
        assertEquals(listOf(50L, 25L), result.delays)
    }

    @Test
    fun cancelledLoop_stopsWithoutReportingRemoval() {
        // The loop only leaves on a confirmed removal or a cancellation:
        // cancelling a still-playing tag must not report a removal. Real
        // delays, so the cancellation lands inside the loop.
        var confirmedRemoved = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.launch {
            MifarePresencePoller.loop(
                readPage0 = { },
                onStillPresent = { },
                onConfirmedRemoved = { confirmedRemoved = true },
            )
        }
        Thread.sleep(MifarePresencePoller.READ_INTERVAL_MS * 3)
        runBlocking { job.cancelAndJoin() }
        assertFalse("cancelling the poll must not report a tag removal", confirmedRemoved)
    }
}
