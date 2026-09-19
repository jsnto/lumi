package com.lumi.player.nfc

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * A connected low-level read session on an NFC tag.
 *
 * In the app this is backed by a connected [android.nfc.tech.MifareUltralight]
 * session: reading page 0 of the tag only succeeds while the tag is
 * physically in the reader's field, so a read failure is a reliable
 * "tag removed" signal even when the NFC stack itself reports nothing about
 * a stationary tag.
 */
fun interface PresenceReader {
    /** Reads page 0 of the tag; throws [IOException] when the tag is out of range. */
    @Throws(IOException::class)
    fun readPage0()
}

/**
 * The continuous-reading loop that gives a fast, reliable tag-lost signal
 * for the active tag. While the calling coroutine is active it repeatedly
 * performs a low-level field read:
 *
 * - a successful read refreshes [onStillPresent] (the tag is still in range);
 * - an [IOException] from a read waits [retryDelayMs] and performs ONE retry
 *   read (a two-strike debounce against single-occurrence blips): a
 *   successful retry means the tag is still there - the loop resets and
 *   continues; a failed retry confirms the removal - [onConfirmedRemoved]
 *   is called exactly once and the loop ends.
 *
 * The loop only reads and never touches playback itself; it must run on a
 * background dispatcher (each read blocks for the duration of the NFC
 * command) and the callbacks are responsible for hopping to the main
 * thread.
 */
object MifarePresencePoller {
    /** Cadence of the continuous reads. */
    const val READ_INTERVAL_MS = 300L

    /** Debounce delay before the single retry read after a failed read. */
    const val RETRY_DELAY_MS = 100L

    /**
     * Runs the continuous-reading loop; returns when the removal is
     * confirmed or the coroutine is cancelled. [delayFn] is injectable so
     * tests can advance time without a real clock.
     */
    suspend fun loop(
        readPage0: () -> Unit,
        onStillPresent: () -> Unit,
        onConfirmedRemoved: () -> Unit,
        readIntervalMs: Long = READ_INTERVAL_MS,
        retryDelayMs: Long = RETRY_DELAY_MS,
        delayFn: suspend (Long) -> Unit = { delay(it) },
    ): Unit {
        val job = currentCoroutineContext()[Job]
        while (job?.isActive != false) {
            val present = try {
                readPage0()
                true
            } catch (e: IOException) {
                false
            }
            if (!present) {
                delayFn(retryDelayMs)
                val stillPresent = try {
                    readPage0()
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
