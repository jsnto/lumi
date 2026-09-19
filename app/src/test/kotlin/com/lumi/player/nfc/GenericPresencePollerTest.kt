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

/**
 * Unit tests for the connected-session presence loop of
 * [GenericPresencePoller.loop], driven by a fake connection state and a
 * fake clock (no NFC hardware, no real delays).
 *
 * The fake connection is a queue of [isConnected] outcomes consumed one per
 * check: `true` = the tag is still on the antenna, `false` = the connection
 * dropped (the tag left).
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
        val disconnected: Int,
        val delays: List<Long>,
    )

    /** Runs the loop to completion (it ends when the connection drops). */
    private fun runToCompletion(
        outcomes: List<Boolean>,
        checkIntervalMs: Long = GenericPresencePoller.CHECK_INTERVAL_MS,
    ): RunResult {
        require(outcomes.any { !it }) { "outcomes must end in a dropped connection" }
        val clock = FakeClock()
        var index = 0
        var stillPresent = 0
        var disconnected = 0
        val isConnected: () -> Boolean = {
            val ok = outcomes[index.coerceAtMost(outcomes.size - 1)]
            index++
            ok
        }
        runBlocking {
            GenericPresencePoller.loop(
                isConnected = isConnected,
                onStillPresent = { stillPresent++ },
                onDisconnected = { disconnected++ },
                checkIntervalMs = checkIntervalMs,
                delayFn = clock::delay,
            )
        }
        return RunResult(stillPresent, disconnected, clock.delays)
    }

    @Test
    fun connectedChecks_keepPlaying_andConfirmDropAtTheEnd() {
        // Five connected checks, then the tag leaves: the broken connection
        // confirms the removal.
        val result = runToCompletion(listOf(true, true, true, true, true, false))
        assertEquals(5, result.stillPresent)
        assertEquals(1, result.disconnected)
        // Each connected check waits the check interval; the confirming
        // check does not wait afterwards.
        assertEquals(List(5) { GenericPresencePoller.CHECK_INTERVAL_MS }, result.delays)
    }

    @Test
    fun immediateDrop_confirmedExactlyOnce() {
        // The connection is already broken when the loop starts: the
        // removal is reported exactly once and the loop does not wait.
        val result = runToCompletion(listOf(false))
        assertEquals(0, result.stillPresent)
        assertEquals(1, result.disconnected)
        assertEquals(emptyList<Long>(), result.delays)
    }

    @Test
    fun loop_usesTheConfiguredInterval() {
        val result = runToCompletion(
            outcomes = listOf(true, false),
            checkIntervalMs = 50,
        )
        assertEquals(1, result.stillPresent)
        assertEquals(1, result.disconnected)
        assertEquals(listOf(50L), result.delays)
    }

    @Test
    fun cancelledLoop_stopsWithoutReportingRemoval() {
        // The loop only leaves on a dropped connection or a cancellation:
        // cancelling a still-connected tag must not report a removal. Real
        // delays, so the cancellation lands inside the loop.
        var disconnected = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.launch {
            GenericPresencePoller.loop(
                isConnected = { true },
                onStillPresent = { },
                onDisconnected = { disconnected = true },
            )
        }
        Thread.sleep(GenericPresencePoller.CHECK_INTERVAL_MS * 3)
        runBlocking { job.cancelAndJoin() }
        assertFalse("cancelling the poll must not report a tag removal", disconnected)
    }
}
