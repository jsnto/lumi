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
 * [GenericPresencePoller.loop], driven by a fake probe and a fake clock (no
 * NFC hardware, no real delays).
 *
 * The fake probe consumes one outcome per probe: `true` = the tag answered
 * (still in range), `false` = the probe threw IOException (the tag left).
 * Outcomes are consumed in order - a failed first strike and its retry probe
 * each consume one.
 */
class GenericPresencePollerTest {

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
        readIntervalMs: Long = GenericPresencePoller.READ_INTERVAL_MS,
        retryDelayMs: Long = GenericPresencePoller.RETRY_DELAY_MS,
    ): RunResult {
        require(outcomes.any { !it }) { "outcomes must end in a confirmed removal" }
        val clock = FakeClock()
        var index = 0
        var stillPresent = 0
        var confirmedRemoved = 0
        val probe: () -> Unit = {
            val ok = outcomes[index.coerceAtMost(outcomes.size - 1)]
            index++
            if (!ok) throw IOException("tag out of range")
        }
        runBlocking {
            GenericPresencePoller.loop(
                probe = probe,
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
    fun successfulProbes_keepPlaying_andConfirmRemovalAtTheEnd() {
        // Five answered probes, then the tag leaves: the first failed probe
        // is debounced (one retry after the retry delay), the failed retry
        // confirms the removal.
        val result = runToCompletion(listOf(true, true, true, true, true, false, false))
        assertEquals(5, result.stillPresent)
        assertEquals(1, result.confirmedRemoved)
        // Each answered probe waits the read interval; the failed strike
        // waits the retry delay before the single retry probe.
        assertEquals(
            List(5) { GenericPresencePoller.READ_INTERVAL_MS } +
                listOf(GenericPresencePoller.RETRY_DELAY_MS),
            result.delays,
        )
    }

    @Test
    fun singleFailedProbe_debouncesWithOneRetry_andResets() {
        // Probe OK, then a blip (strike 1 fails, the retry succeeds), then
        // the tag genuinely leaves (strike 2 fails, the retry fails).
        // The successful retry must reset the loop so the NEXT failure
        // starts a fresh two-strike sequence.
        val result = runToCompletion(listOf(true, false, true, true, false, false))
        assertEquals(3, result.stillPresent)
        assertEquals(1, result.confirmedRemoved)
        assertEquals(
            listOf(
                GenericPresencePoller.READ_INTERVAL_MS, // after probe 1
                GenericPresencePoller.RETRY_DELAY_MS,    // before the retry probe
                GenericPresencePoller.READ_INTERVAL_MS, // after the successful retry
                GenericPresencePoller.READ_INTERVAL_MS, // after probe 3
                GenericPresencePoller.RETRY_DELAY_MS,   // before the confirming retry
            ),
            result.delays,
        )
    }

    @Test
    fun immediateRemoval_confirmedExactlyOnce() {
        // The tag is already gone when the loop starts: both probes fail and
        // the removal is reported exactly once.
        val result = runToCompletion(listOf(false, false))
        assertEquals(0, result.stillPresent)
        assertEquals(1, result.confirmedRemoved)
        assertEquals(listOf(GenericPresencePoller.RETRY_DELAY_MS), result.delays)
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
        // cancelling a still-present tag must not report a removal. Real
        // delays, so the cancellation lands inside the loop.
        var confirmedRemoved = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.launch {
            GenericPresencePoller.loop(
                probe = { },
                onStillPresent = { },
                onConfirmedRemoved = { confirmedRemoved = true },
            )
        }
        Thread.sleep(GenericPresencePoller.READ_INTERVAL_MS * 3)
        runBlocking { job.cancelAndJoin() }
        assertFalse("cancelling the poll must not report a tag removal", confirmedRemoved)
    }
}
