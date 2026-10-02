package com.lumi.player.data

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import com.lumi.player.player.LoudnessMeterProcessor
import com.lumi.player.player.MeterAudioSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * The default loudness measurement read (the [LumiViewModel.loudnessMeasurer]
 * seam's production implementation): it decodes the audio at [uri] at
 * DECODE SPEED through a [MeterAudioSink] that plays nothing (no AudioTrack)
 * and feeds a [LoudnessMeterProcessor], and returns the meter's reading
 * once the whole stream has been decoded and drained.
 *
 * A read failure surfaces as the exception the caller settles into an
 * unmeasured entry (the song then plays at its recorded level; an explicit
 * "Normalize now" can retry). The read is time-limited: a stalled decode
 * settles into a failure after [timeoutMs] instead of hanging the pass.
 *
 * Threading: ExoPlayer verifies that every application-thread API call
 * ([ExoPlayer.setMediaItem], [ExoPlayer.prepare], [ExoPlayer.release]) runs
 * on the looper the player was built with, and the [ExoPlayer.Builder]
 * built from a [Context] pins that to the MAIN looper when the calling
 * thread has none of its own (this read is launched from a background
 * dispatcher). So every player API call is made on the main thread (the
 * same thread the app's playback [com.lumi.player.player.PlayerService]
 * uses); the decode itself runs at decode speed on ExoPlayer's own
 * playback thread, and nothing here blocks the main thread (the read
 * suspends, the callbacks are plain messages).
 */
class LoudnessMeasurer(
    private val context: Context,
    private val timeoutMs: Long = MEASURE_TIMEOUT_MS,
) {

    /**
     * Measures the loudness of the audio at [uri]. The measurement runs at
     * decode speed (a few seconds even for long files on device), so the
     * default [MEASURE_TIMEOUT_MS] is far longer than any realistic read.
     *
     * May be called from any thread: the function drives the player on the
     * main thread itself (the player's application thread - see the class
     * KDoc) and suspends while the decode runs.
     */
    suspend fun measure(uri: Uri): LoudnessResult {
        val meterProcessor = LoudnessMeterProcessor()
        val sink = MeterAudioSink(meterProcessor)
        return try {
            withTimeout(timeoutMs) {
                // The player's application thread is the main looper (the
                // builder pins it there): the WHOLE player lifecycle -
                // setup, wait and release - happens on it. The decode and
                // the meter run on ExoPlayer's playback thread; the await()
                // below suspends, it does not block the main thread.
                withContext(Dispatchers.Main) {
                    val factory = object : DefaultRenderersFactory(context) {
                        override fun buildAudioSink(
                            context: Context,
                            enableFloatOutput: Boolean,
                            enableAudioTrackPlaybackParams: Boolean,
                        ): AudioSink = sink
                    }
                    val player = ExoPlayer.Builder(context)
                        .setRenderersFactory(factory)
                        .build()
                    try {
                        CompletableDeferred<Unit>().apply {
                            player.addListener(object : Player.Listener {
                                override fun onPlaybackStateChanged(state: Int) {
                                    // The single item ended: the stream is
                                    // fully decoded, and the sink drained the
                                    // meter through it (the sink is
                                    // synchronous).
                                    if (state == Player.STATE_ENDED) {
                                        complete(Unit)
                                    }
                                }

                                override fun onPlayerError(error: PlaybackException) {
                                    completeExceptionally(error)
                                }
                            })
                            player.setMediaItem(MediaItem.fromUri(uri.toString()))
                            player.playWhenReady = true
                            player.prepare()
                            await()
                        }
                        // The meter's reading is captured BEFORE the release
                        // below: releasing the player resets the sink (and
                        // the meter it feeds), so a read after the release
                        // would report the reset (empty) meter.
                        meterProcessor.result()
                    } finally {
                        player.release()
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            // A stalled decode (an exotic container, a stalled file) settles
            // into a failure: the caller leaves the entry unmeasured.
            throw IOException("Measuring the loudness of $uri timed out", e)
        } catch (e: CancellationException) {
            // An outer cancellation (the view model's scope) stops the pass:
            // rethrow so the caller's job cancels normally.
            throw e
        }
    }

    companion object {
        /**
         * The per-entry measurement timeout. At decode speed a measurement
         * takes seconds even for long files, so this bounds only
         * pathological stalls, not real-time playback of the track.
         */
        const val MEASURE_TIMEOUT_MS = 120_000L
    }
}
