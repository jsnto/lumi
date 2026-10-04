package com.lumi.player.nfc

import kotlinx.coroutines.delay
import java.io.IOException

/**
 * A live low-level read session on a tag the Mifare Ultralight fast path
 * cannot read (any other NfcA or IsoDep tag).
 *
 * In the app this wraps a connected [android.nfc.tech.TagTechnology]
 * session: a probe is a real low-level read command to the tag, and it
 * succeeds only while the tag is physically in the reader's field AND its
 * RF session is still alive. Note that a failed probe on a stale session is
 * NOT by itself a reliable "tag removed" signal - under foreground dispatch
 * a tag held still in range can go cold (its RF session drops while the tag
 * is still in the field). The app therefore pairs this with
 * [ReconnectingPresenceSession], which re-establishes a fresh session on a
 * failed probe: only when the fresh session also cannot read the tag is the
 * failure a genuine "tag removed" strike.
 */
interface GenericPresenceSession {
    /**
     * Performs one low-level read on the tag; throws [IOException] when the
     * tag is out of range.
     */
    @Throws(IOException::class)
    fun probe()

    /** Closes the tag-tech session (a no-op when the tag is already gone). */
    fun close()
}

/**
 * The probe loop for non-Mifare tags. Under foreground dispatch a
 * stationary tag may be delivered only once, so without a fast path a held
 * non-Mifare tag would look "removed" to the absence-timeout fallback.
 * Probing the tag with a real low-level read on every tick (like the
 * Mifare fast path's page-0 reads) refreshes the presence record as long
 * as the tag stays on the antenna; it runs the shared [PresencePollLoop]
 * over the probe, and the loop's two-strike debounce semantics, its
 * injectable delay and its background-dispatcher constraint are documented
 * on [PresencePollLoop.loop].
 */
object GenericPresencePoller {
    /** Cadence of the probes; matches the Mifare fast path. */
    const val READ_INTERVAL_MS = 300L

    /** Debounce delay before the single retry probe after a failed probe. */
    const val RETRY_DELAY_MS = 100L

    /**
     * Runs the probe loop; returns when the removal is confirmed or the
     * coroutine is cancelled. [delayFn] is injectable so tests can advance
     * time without a real clock. The loop's body is the shared
     * [PresencePollLoop.loop] (the Mifare fast path runs the same loop
     * over its page read).
     */
    suspend fun loop(
        probe: () -> Unit,
        onStillPresent: () -> Unit,
        onConfirmedRemoved: () -> Unit,
        readIntervalMs: Long = READ_INTERVAL_MS,
        retryDelayMs: Long = RETRY_DELAY_MS,
        delayFn: suspend (Long) -> Unit = { delay(it) },
    ): Unit {
        PresencePollLoop.loop(
            read = probe,
            onStillPresent = onStillPresent,
            onConfirmedRemoved = onConfirmedRemoved,
            readIntervalMs = readIntervalMs,
            retryDelayMs = retryDelayMs,
            delayFn = delayFn,
        )
    }
}
