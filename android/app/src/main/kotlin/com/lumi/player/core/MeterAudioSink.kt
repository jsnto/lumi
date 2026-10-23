package com.lumi.player.core

import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer

/**
 * A decode-speed [AudioSink]: it accepts the decoded PCM and feeds the
 * measurement processor ([LoudnessMeterProcessor]), but plays NOTHING (no
 * AudioTrack). A player backed by this sink therefore runs at decode speed
 * instead of real time, and the meter sees the stream's FULL-SCALE samples
 * (no output stage and no volume stand between the decoded stream and the
 * meter).
 *
 * The sink is synchronous: every queued buffer is drained through the
 * processor before [handleBuffer] returns, so the renderer never sees
 * pending data.
 *
 * Format support is reported the way the platform's own sink reports it
 * (see `DefaultAudioSink`): linear PCM (what the decoder emits) is
 * supported DIRECTLY, compressed audio is supported only AFTER DECODE
 * (the renderer inserts the decoder and feeds the decoded PCM to this
 * sink), and anything else is unsupported. Claiming direct support for a
 * compressed format would tell the renderer the stream can skip the
 * decoder, and the meter would then see raw compressed frames (the
 * processor is configured with the stream's format, whose encoding is
 * unknown, and feeds the meter nothing).
 */
class MeterAudioSink(
    private val processor: AudioProcessor,
) : AudioSink {

    private var listener: AudioSink.Listener? = null
    private var ended = false

    /**
     * The position reported to the renderer ([getCurrentPositionUs]): the
     * PRESENTATION TIME (the CCodec time base, in microsecond units) of the
     * last decoded buffer of the current stream. [C.TIME_UNSET] until the
     * first buffer.
     *
     * ExoPlayer's position model works in the renderer's PRESENTATION-TIME
     * domain: on every seek it pins the period's renderer offset to
     * 1_000_000_000_000 us (the CCodec time base) and maps the clock's
     * position into the period domain by subtracting that offset. The
     * platform's own sink honours the same domain:
     * [androidx.media3.exoplayer.audio.DefaultAudioSink] anchors its position
     * on `startMediaTimeUs = Math.max(0, firstBufferPresentationTimeUs)` and
     * reports `startMediaTimeUs + the AudioTrack's played time`. Reporting a
     * position in any other domain (the period domain, wall clock, ...) puts
     * the player's position out of domain: after the offset subtraction it
     * goes negative, the buffering checks and the end-of-stream transition
     * stop making progress, and the player sits in BUFFERING forever (the
     * decode is idle but alive, so a measurement on this sink would time
     * out). The [handleBuffer] `bufferDurationUs` is exactly a
     * presentation-time-domain coordinate (the decoder output buffer's
     * presentationTimeUs), so the position is the last decoded buffer's
     * presentation time: no sample arithmetic, and after a seek or a flush
     * the stream's own next buffer re-anchors the position automatically
     * (a degenerate non-positive time keeps the previous one).
     */
    private var positionUs: Long = C.TIME_UNSET

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    override fun supportsFormat(format: Format): Boolean =
        getFormatSupport(format) == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY

    override fun getFormatSupport(format: Format): Int = when {
        format.sampleMimeType == null || !MimeTypes.isAudio(format.sampleMimeType) ->
            AudioSink.SINK_FORMAT_UNSUPPORTED
        // What the decoder emits: consumed as-is.
        Util.isEncodingLinearPcm(format.pcmEncoding) ->
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        // Compressed audio: the renderer decodes it first (the honest
        // contract - see the class KDoc).
        else -> AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
    }

    override fun getCurrentPositionUs(forceCommit: Boolean): Long =
        if (positionUs == C.TIME_UNSET) {
            // No buffer of the current stream has been decoded yet: the
            // renderer keeps the position its onPositionReset was given
            // (the platform sink reports TIME_UNSET until its AudioTrack is
            // up too, and the renderer honours a TIME_UNSET answer).
            C.TIME_UNSET
        } else {
            // Renderer-domain position: the last decoded buffer's
            // presentation time (see [positionUs]).
            positionUs
        }

    override fun configure(format: Format, channelCount: Int, channelMap: IntArray?) {
        // A new stream: the position re-anchors on this stream's first
        // buffer (see [positionUs]).
        positionUs = C.TIME_UNSET
        try {
            processor.configure(AudioProcessor.AudioFormat(format))
        } catch (e: AudioProcessor.UnhandledAudioFormatException) {
            throw AudioSink.ConfigurationException(e, format)
        }
    }

    override fun play() {
        // No AudioTrack: there is nothing to start.
    }

    override fun handleDiscontinuity() {
        processor.flush()
        listener?.onPositionDiscontinuity()
    }

    override fun handleBuffer(buffer: ByteBuffer, bufferDurationUs: Long, flags: Int): Boolean {
        processor.queueInput(buffer)
        drainOutput()
        // The buffer's presentation time is the position (see [positionUs]).
        if (bufferDurationUs > 0) {
            positionUs = bufferDurationUs
        }
        if (flags and BUFFER_FLAG_EOS != 0) {
            finishStream()
        }
        return true
    }

    override fun playToEndOfStream() {
        finishStream()
    }

    override fun isEnded(): Boolean = ended

    override fun hasPendingData(): Boolean = false

    override fun setPlaybackParameters(parameters: PlaybackParameters) {
        // The measurement runs at decode speed: the rate is not applied.
    }

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT

    override fun setSkipSilenceEnabled(enabled: Boolean) {
        // Silence is MEASURED (it feeds the gates): never skipped.
    }

    override fun getSkipSilenceEnabled(): Boolean = false

    override fun setAudioAttributes(attributes: AudioAttributes) {
        // No AudioTrack: attributes never leave this sink.
    }

    override fun getAudioAttributes(): AudioAttributes = AudioAttributes.DEFAULT

    override fun setAudioSessionId(sessionId: Int) {
        // No AudioTrack.
    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
        // No AudioTrack.
    }

    override fun enableTunnelingV21() {
        // No AudioTrack.
    }

    override fun disableTunneling() {
        // No AudioTrack.
    }

    override fun setVolume(volume: Float) {
        // No AudioTrack: the meter sees the full-scale stream.
    }

    override fun pause() {
        // No AudioTrack.
    }

    override fun flush() {
        processor.flush()
        // The next buffer of the stream re-anchors the position (see
        // [positionUs]).
        positionUs = C.TIME_UNSET
    }

    override fun reset() {
        processor.reset()
        positionUs = C.TIME_UNSET
        ended = false
    }

    private fun finishStream() {
        if (ended) return
        processor.queueEndOfStream()
        drainOutput()
        ended = true
    }

    private fun drainOutput() {
        while (true) {
            val out = processor.getOutput()
            if (out === AudioProcessor.EMPTY_BUFFER) return
            // The meter already saw the samples: the pass-through output
            // is discarded.
        }
    }

    companion object {
        /**
         * The [handleBuffer] flag marking the stream's last buffer (the
         * media3 BUFFER_FLAG_EOS value, kept as a literal here: the renderer
         * keeps its own private copy of the flag and the [AudioSink] contract
         * exposes none).
         */
        private const val BUFFER_FLAG_EOS = 2
    }
}
