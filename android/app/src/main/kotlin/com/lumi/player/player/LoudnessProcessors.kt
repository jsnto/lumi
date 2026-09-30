package com.lumi.player.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.lumi.player.data.LIMITER_KNEE_LINEAR
import com.lumi.player.data.LoudnessMeter
import com.lumi.player.data.LoudnessResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.tanh

/**
 * The playback audio processor for the audio level (the in-player gain): it
 * applies the per-track gain (in dB, from the on-device loudness
 * measurement) and, for boosted tracks only, the soft-clip peak limiter
 * whose knee sits at -1 dBTP ([LIMITER_KNEE_LINEAR]), so a boost never
 * hard-clips at 0 dBFS.
 *
 * The processor is STATELESS with respect to the stream: the gain is a live
 * value the service updates ([setLevel], volatile), and [onFlush] keeps
 * nothing across a track change or seek. The output format is the input
 * format - the gain happens in-sample, in the stream's own encoding
 * (int16 or float32, both interleaved).
 *
 * The input format is tracked by the processor itself ([onConfigure]): the
 * [BaseAudioProcessor] only materializes its [inputAudioFormat] field on
 * [flush], and the sink may queue the stream's first buffers before a
 * flush arrives.
 */
class LoudnessProcessor : BaseAudioProcessor() {

    /** The last gain the service applied, in dB (0.0 = no change). */
    @Volatile
    var gainDb: Double = 0.0
        private set

    /** Whether the peak limiter is engaged (boosted tracks only). */
    @Volatile
    var limiterEngaged: Boolean = false
        private set

    private var linearGain = 1f
    private var linearKnee = 0f

    /** The configured input format (see the class KDoc for why it is local). */
    private var format: AudioProcessor.AudioFormat? = null

    /**
     * Applies the per-track level: [gainDb] dB of gain, and the soft-clip
     * limiter only when [engageLimiter] (a boosted track). A zero gain with
     * no limiter is the exact pass-through (the audio level's OFF state
     * leaves the volume untouched).
     */
    fun setLevel(gainDb: Double, engageLimiter: Boolean) {
        this.gainDb = gainDb
        this.limiterEngaged = engageLimiter
        linearGain = if (gainDb == 0.0 && !engageLimiter) 1f else 10.0.pow(gainDb / 20.0).toFloat()
        linearKnee = if (engageLimiter) LIMITER_KNEE_LINEAR.toFloat() else 0f
    }

    override fun onConfigure(format: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (format.encoding != C.ENCODING_PCM_16BIT && format.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(format)
        }
        this.format = format
        // The same format out: the gain stays in-sample.
        return format
    }

    override fun onReset() {
        format = null
    }

    override fun queueInput(input: ByteBuffer) {
        // The size is taken BEFORE the transform consumes the input (the
        // output carries exactly the input's bytes).
        val size = input.remaining()
        if (size == 0) return
        if (hasPendingOutput()) {
            throw IllegalStateException("The output must be consumed before new input is queued")
        }
        val out = replaceOutputBuffer(size)
        transformInto(input, out)
        // The transform wrote through views (the base buffer's position
        // never moved): position the output at the start, limit it to the
        // input's byte count.
        out.position(0)
        out.limit(size)
    }

    override fun onFlush() {
        // Stateless: nothing to clear - the gain is a live value, not a
        // per-buffer one.
    }

    private fun transformInto(input: ByteBuffer, out: ByteBuffer) {
        val format = format ?: return
        // The exact pass-through (a zero gain with no limiter - the audio
        // level's OFF state): a bit-identical byte copy, no float round trip.
        if (linearGain == 1f && linearKnee == 0f) {
            out.put(input)
            return
        }
        // Copy to an aligned direct buffer (the input's position need not be
        // 4-byte aligned for a float view). The bytes in the input carry the
        // platform's NATIVE PCM layout (the decoder writes them), so the view
        // is declared over a native-order buffer - the input buffer's own
        // order field is never consulted (a default big-endian heap buffer
        // still carries native bytes in bulk).
        val copy = ByteBuffer.allocateDirect(input.remaining()).order(ByteOrder.nativeOrder())
        copy.put(input)
        copy.flip()
        when (format.encoding) {
            C.ENCODING_PCM_FLOAT -> transformFloat(copy, out)
            C.ENCODING_PCM_16BIT -> transformInt16(copy, out)
            // The pipeline only ever delivers the two PCM encodings above;
            // anything else is left as the (already positioned) output.
            else -> Unit
        }
    }

    private fun transformFloat(copy: ByteBuffer, out: ByteBuffer) {
        val src = copy.asFloatBuffer()
        val dst = out.asFloatBuffer()
        val gain = linearGain
        val knee = linearKnee
        val total = src.limit()
        for (i in 0 until total) {
            var sample = src[i] * gain
            if (knee > 0f) {
                val magnitude = abs(sample)
                if (magnitude > knee) {
                    val sign = if (sample < 0f) -1f else 1f
                    val normalized = (magnitude - knee) / (1f - knee)
                    sample = sign * (knee + (1f - knee) * tanh(normalized))
                }
            }
            dst.put(i, sample)
        }
    }

    private fun transformInt16(copy: ByteBuffer, out: ByteBuffer) {
        val src = copy.asShortBuffer()
        val dst = out.asShortBuffer()
        val gain = linearGain
        val knee = linearKnee
        val total = src.limit()
        for (i in 0 until total) {
            // The gain and the limiter run in the full-scale float domain
            // (int16 is only the transport format), then scale back.
            var sample = (src[i] / 32768f) * gain
            if (knee > 0f) {
                val magnitude = abs(sample)
                if (magnitude > knee) {
                    val sign = if (sample < 0f) -1f else 1f
                    val normalized = (magnitude - knee) / (1f - knee)
                    sample = sign * (knee + (1f - knee) * tanh(normalized))
                }
            }
            dst.put(i, (sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
    }
}

/**
 * The measurement audio processor: it passes the decoded PCM through
 * UNTOUCHED and feeds it to the [LoudnessMeter] (per-channel, interleaved).
 * It never changes a sample - the measurement sink discards the pass-through
 * output (see [MeterAudioSink]).
 *
 * Like [LoudnessProcessor], it tracks the configured format itself (the
 * [BaseAudioProcessor] materializes [inputAudioFormat] only on [flush]).
 */
class LoudnessMeterProcessor : BaseAudioProcessor() {

    /** The meter for the current stream (flushes / reconfigures reset it). */
    val meter: LoudnessMeter = LoudnessMeter()

    private var sampleScratch: DoubleArray = DoubleArray(0)

    /** The configured input format (see the class KDoc for why it is local). */
    private var format: AudioProcessor.AudioFormat? = null

    override fun onConfigure(format: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        this.format = format
        meter.configure(format.sampleRate, format.channelCount)
        return format
    }

    override fun onReset() {
        format = null
        meter.reset()
    }

    override fun queueInput(input: ByteBuffer) {
        val size = input.remaining()
        if (size == 0) return
        if (hasPendingOutput()) {
            throw IllegalStateException("The output must be consumed before new input is queued")
        }
        val out = replaceOutputBuffer(size)
        // The single pass: the raw bytes land untouched in the output (the
        // measurement sink discards them), and the meter reads the samples
        // back from what was just written - the input is consumed exactly
        // once.
        out.put(input)
        out.position(0)
        out.limit(size)
        feedMeterFrom(out, size)
    }

    override fun onFlush() {
        // A mid-stream discontinuity: the meter resumes as if the stream had
        // just started (its filter state, statistics and peak all reset).
        meter.reset()
    }

    /** The measurement of everything queued so far. */
    fun result(): LoudnessResult = meter.result()

    /**
     * Feeds the meter from the samples in [out] - which [queueInput] has
     * just filled and positioned at [0, [size]). The index reads never move
     * the buffer, so the output stays in its drained state. The output
     * buffer carries the platform's native PCM layout (the base class
     * allocates it native-order), so the view reads the samples correctly
     * whatever order field the INPUT buffer carried.
     */
    private fun feedMeterFrom(out: ByteBuffer, size: Int) {
        val format = format ?: return
        val channels = format.channelCount.coerceAtLeast(1)
        when (format.encoding) {
            C.ENCODING_PCM_FLOAT -> {
                val src = out.asFloatBuffer()
                val count = src.limit()
                if (sampleScratch.size < count) sampleScratch = DoubleArray(count)
                for (i in 0 until count) sampleScratch[i] = src[i].toDouble()
                meter.processInterleaved(sampleScratch, 0, count / channels)
            }
            C.ENCODING_PCM_16BIT -> {
                val src = out.asShortBuffer()
                val count = src.limit()
                if (sampleScratch.size < count) sampleScratch = DoubleArray(count)
                for (i in 0 until count) sampleScratch[i] = src[i].toDouble() / 32768.0
                meter.processInterleaved(sampleScratch, 0, count / channels)
            }
            else -> Unit
        }
    }
}
