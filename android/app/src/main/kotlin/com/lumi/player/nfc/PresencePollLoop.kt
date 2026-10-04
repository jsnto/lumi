package com.lumi.player.nfc

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import java.io.IOException

/**
 * The continuous-reading loop the two presence fast paths share
 * ([MifarePresencePoller] and [GenericPresencePoller] both run this exact
 * body over their own read/probe seam): while the calling coroutine is
 * active it repeatedly performs a low-level field read:
 *
 * - a successful read refreshes [onStillPresent] (the tag is still in range);
 * - an [IOException] from a read waits [retryDelayMs] and performs ONE retry
 *   read (a two-strike debounce against single-occurrence blips): a
 *   successful retry means the tag is still there - the loop resets and
 *   continues; a failed retry confirms the removal - [onConfirmedRemoved]
 *   is called exactly once and the loop ends.
 *
 * One implementation for both fast paths on purpose: the debounce semantics
 * are the same for a Mifare page read and for a generic-tech probe (the
 * per-read fresh-session retry happens INSIDE the caller's
 * [ReconnectingPresenceSession], which the supplied [read] is wrapped in),
 * so the two pollers can never drift apart.
 *
 * The loop only reads and never touches playback itself; it must run on a
 * background dispatcher (each read blocks for the duration of the NFC
 * command) and the callbacks are responsible for hopping to the main
 * thread.
 */
internal object PresencePollLoop {

    /**
     * Runs the continuous-reading loop; returns when the removal is
     * confirmed or the coroutine is cancelled. [delayFn] is injectable so
     * tests can advance time without a real clock.
     */
    suspend fun loop(
        read: () -> Unit,
        onStillPresent: () -> Unit,
        onConfirmedRemoved: () -> Unit,
        readIntervalMs: Long,
        retryDelayMs: Long,
        delayFn: suspend (Long) -> Unit,
    ): Unit {
        val job = currentCoroutineContext()[Job]
        while (job?.isActive != false) {
            val present = try {
                read()
                true
            } catch (e: IOException) {
                false
            }
            if (!present) {
                delayFn(retryDelayMs)
                val stillPresent = try {
                    read()
                    true
                } catch (e: IOException) {
                    false
                }
                if (!stillPresent) {
                    onConfirmedRemoved()
                    return
                }
            }
            onStillPresent()
            delayFn(readIntervalMs)
        }
    }
}
