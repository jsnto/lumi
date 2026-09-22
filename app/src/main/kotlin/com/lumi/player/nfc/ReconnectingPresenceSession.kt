package com.lumi.player.nfc

import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Wraps a low-level tag read session with a fresh-session reconnect.
 *
 * Why it exists: under foreground dispatch a tag HELD STILL in range can go
 * cold - the phone's RF session for it drops while the tag is physically
 * still in the reader's field. Reads on the stale session then fail, and a
 * plain two-strike debounce would confirm "tag removed" (and pause) even
 * though the tag never left. Device-proven: on a real device the fast-path
 * reads of a stationary tag fail within ~500 ms of the activating read.
 *
 * A reconnection attempt is the disambiguator:
 * - the tag is still in range (its session just went cold): [reconnect]
 *   establishes a fresh session and the follow-up read SUCCEEDS - the
 *   wrapper adopts the fresh session (closing the stale one) and reports the
 *   read as successful (the tag is present; the caller's loop resets and
 *   keeps polling);
 * - the tag genuinely left the field: the reconnection (or its read) fails
 *   too, and [read] throws [IOException] - a real "tag out of range" strike
 *   for the caller's debounce.
 *
 * [read] only reads and reconnects; it never touches playback. Both the
 * reads and the reconnects block for the duration of an NFC command, so it
 * must run on a background dispatcher, single-threaded (one poll coroutine).
 *
 * Session hygiene: [reconnect] must return null (not throw) when no fresh
 * session can be established. Every session the wrapper opens that it no
 * longer holds is closed through [closeOf] exactly once - a stale session on
 * adoption, a fresh session whose read failed, and the current session via
 * [close]. [closeOf] is wrapped defensively so a failing close never masks
 * the read result.
 */
class ReconnectingPresenceSession<R>(
    initial: R,
    private val readOf: (R) -> Unit,
    private val reconnect: () -> R?,
    private val closeOf: (R) -> Unit = {},
) {
    private var current: R = initial

    /**
     * Performs one low-level read. Throws [IOException] when the tag is out
     * of range - the read on the current session failed AND no fresh session
     * could read it either; the exception carries the original read failure
     * as its cause so the caller's log can name it. A
     * [CancellationException] propagates.
     */
    @Throws(IOException::class)
    fun read() {
        val readFailure: Exception? = try {
            readOf(current)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The session is stale (the tag went cold) or the tag left:
            // disambiguate with a fresh session before counting the strike.
            e
        }
        if (readFailure == null) return
        val fresh: R? = try {
            reconnect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failing reconnection is "no fresh session": the strike
            // stands.
            null
        }
        if (fresh == null) {
            throw IOException(
                "read failed and no fresh session could be established", readFailure
            )
        }
        try {
            readOf(fresh)
        } catch (e: CancellationException) {
            safelyClose(fresh)
            throw e
        } catch (e: Exception) {
            // A fresh session also cannot read the tag: it is genuinely out
            // of range (or the tag is not connectable at all).
            safelyClose(fresh)
            throw IOException("read failed after reconnect", e)
        }
        // The fresh session reads: adopt it, closing the stale one.
        safelyClose(current)
        current = fresh
    }

    /** Closes the session the wrapper currently holds. */
    fun close() {
        safelyClose(current)
    }

    private fun safelyClose(session: R) {
        runCatching { closeOf(session) }
    }
}
