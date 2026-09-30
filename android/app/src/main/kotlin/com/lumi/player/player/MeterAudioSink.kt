package com.lumi.player.player

import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
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
 * pending data and the position is just the cumulative buffer duration.
 */
class MeterAudioSink(
    private val processor: AudioProcessor,
) : AudioSink {

    private var listener: AudioSink.Listener? = null
    private var positionUs = 0L
    private var ended = false

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    override fun supportsFormat(format: Format): Boolean = true

    override fun getFormatSupport(format: Format): Int = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY

    override fun getCurrentPositionUs(forceCommit: Boolean): Long = positionUs

    override fun configure(format: Format, channelCount: Int, channelMap: IntArray?) {
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
        positionUs += bufferDurationUs
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
    }

    override fun reset() {
        processor.reset()
        positionUs = 0L
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
