package com.lumi.player.nfc

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay

/**
 * A live tag-tech connection to a tag the Mifare Ultralight fast path cannot
 * read (any other NfcA or IsoDep tag).
 *
 * In the app this wraps a connected [android.nfc.tech.TagTechnology]
 * session: [isConnected] stays true only while the tag is physically in the
 * reader's field, so the connection dropping is a reliable "tag removed"
 * signal even when the NFC stack itself reports nothing about a stationary
 * tag.
 */
interface GenericPresenceSession {
    /** True while the tag is still connected to the phone. */
    val isConnected: Boolean

    /** Closes the tag-tech session (a no-op when the tag is already gone). */
    fun close()
}

/**
 * The connected-session presence loop for non-Mifare tags. Under foreground
 * dispatch a stationary tag may be delivered only once, so without the
 * Mifare fast path (page-0 reads) a held non-Mifare tag would look "removed"
 * to the absence-timeout fallback. Keeping a tag-tech session connected and
 * watching its connection state refreshes the presence record as long as
 * the tag stays on the antenna:
 *
 * - a connected check refreshes [onStillPresent] (the tag is still in
 *   range);
 * - a broken connection confirms the removal - [onDisconnected] is called
 *   exactly once and the loop ends.
 *
 * A session drop is a stronger signal than a failed Mifare page read (the
 * NFC stack actively tears the connection down when the tag leaves the
 * field), so no two-strike debounce is needed.
 *
 * The loop only observes the connection and never touches playback itself.
 * It must run on a background dispatcher and the callbacks are responsible
 * for hopping to the main thread.
 */
object GenericPresencePoller {
    /** Cadence of the connection checks; matches the Mifare fast path. */
    const val CHECK_INTERVAL_MS = 300L

    /**
     * Runs the connected-session loop; returns when the connection drops or
     * the coroutine is cancelled (cancellation never reports the removal).
     * [delayFn] is injectable so tests can advance time without a real
     * clock.
     */
    suspend fun loop(
        isConnected: () -> Boolean,
        onStillPresent: () -> Unit,
        onDisconnected: () -> Unit,
        checkIntervalMs: Long = CHECK_INTERVAL_MS,
        delayFn: suspend (Long) -> Unit = { delay(it) },
    ): Unit {
        val job = currentCoroutineContext()[Job]
        while (job?.isActive != false) {
            if (!isConnected()) {
                onDisconnected()
                return
            }
            onStillPresent()
            delayFn(checkIntervalMs)
        }
    }
}
