package com.lumi.player.nfc

import kotlinx.coroutines.delay
import java.io.IOException

/**
 * A connected low-level read session on an NFC tag.
 *
 * In the app this is backed by a connected [android.nfc.tech.MifareUltralight]
 * session: reading page 0 of the tag succeeds only while the tag is
 * physically in the reader's field AND its RF session is still alive. Note
 * that a read failure on a stale session is NOT by itself a reliable
 * "tag removed" signal - under foreground dispatch a tag held still in
 * range can go cold (its RF session drops while the tag is still in the
 * field). The app therefore pairs this with
 * [ReconnectingPresenceSession], which re-establishes a fresh session on a
 * failed read: only when the fresh session also cannot read the tag is the
 * failure a genuine "tag removed" strike.
 */
fun interface PresenceReader {
    /** Reads page 0 of the tag; throws [IOException] when the tag is out of range. */
    @Throws(IOException::class)
    fun readPage0()

    /**
     * Closes the low-level session (a no-op when the tag is already gone).
     *
     * The default keeps this a single-abstract-method interface, so the
     * lambda implementations in the tests compile unchanged; a real
     * tag-tech session must close the connected tech so a superseded
     * session is released instead of leaking.
     */
    fun close() {}
}

/**
 * The continuous-reading loop that gives a fast, reliable tag-lost signal
 * for the active tag. It runs the shared [PresencePollLoop] over the Mifare
 * page-0 read; the loop's two-strike debounce semantics, its injectable
 * delay and its background-dispatcher constraint are documented on
 * [PresencePollLoop.loop].
 */
object MifarePresencePoller {
    /** Cadence of the continuous reads. */
    const val READ_INTERVAL_MS = 300L

    /** Debounce delay before the single retry read after a failed read. */
    const val RETRY_DELAY_MS = 100L

    /**
     * Runs the continuous-reading loop; returns when the removal is
     * confirmed or the coroutine is cancelled. [delayFn] is injectable so
     * tests can advance time without a real clock. The loop's body is the
     * shared [PresencePollLoop.loop] (the generic-tech fast path runs the
     * same loop over its probe).
     */
    suspend fun loop(
        readPage0: () -> Unit,
        onStillPresent: () -> Unit,
        onConfirmedRemoved: () -> Unit,
        readIntervalMs: Long = READ_INTERVAL_MS,
        retryDelayMs: Long = RETRY_DELAY_MS,
        delayFn: suspend (Long) -> Unit = { delay(it) },
    ): Unit {
        PresencePollLoop.loop(
            read = readPage0,
            onStillPresent = onStillPresent,
            onConfirmedRemoved = onConfirmedRemoved,
            readIntervalMs = readIntervalMs,
            retryDelayMs = retryDelayMs,
            delayFn = delayFn,
        )
    }
}
