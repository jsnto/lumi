package com.lumi.player.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The playback audio processors (the in-player gain + soft-clip limiter and
 * the pass-through meter feeder), driven through the real [BaseAudioProcessor]
 * queue (queue input, drain output).
 */
class LoudnessProcessorTest {

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    // The input buffers carry the platform's NATIVE PCM layout - exactly
    // what the decoder hands the processor chain on the device (the bulk
    // copy into a native-order buffer then round-trips the samples).
    private fun floatBuffer(samples: FloatArray): ByteBuffer {
        val b = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.nativeOrder())
        for (s in samples) b.putFloat(s)
        b.flip()
        return b
    }

    private fun shortBuffer(samples: ShortArray): ByteBuffer {
        val b = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder())
        for (s in samples) b.putShort(s)
        b.flip()
        return b
    }

    private fun configure(processor: BaseAudioProcessor, encoding: Int): AudioProcessor.AudioFormat {
        val format = AudioProcessor.AudioFormat(44100, 1, encoding)
        val out = processor.configure(format)
        assertEquals(format.encoding, out.encoding)
        return format
    }

    /** Queues [input], drains the processor's output, and returns it. */
    private fun <T : BaseAudioProcessor> run(processor: T, input: ByteBuffer): ByteBuffer {
        processor.queueInput(input)
        val out = processor.getOutput()
        assertNotNull("the queue produced an output", out)
        assertFalse("the drained output is the real buffer, not the empty sentinel", out === AudioProcessor.EMPTY_BUFFER)
        return out
    }

    // The views are read by INDEX (get(i) never moves the buffer's
    // position), so the same buffer can be read back repeatedly.
    private fun floatsOf(buffer: ByteBuffer): List<Float> {
        val v = buffer.asFloatBuffer()
        val out = ArrayList<Float>(v.limit())
        for (i in 0 until v.limit()) out.add(v[i])
        return out
    }

    private fun shortsOf(buffer: ByteBuffer): List<Short> {
        val v = buffer.asShortBuffer()
        val out = ArrayList<Short>(v.limit())
        for (i in 0 until v.limit()) out.add(v[i])
        return out
    }

    // ------------------------------------------------------------------
    // The pass-through (the audio level's OFF state)
    // ------------------------------------------------------------------

    @Test
    fun offStateIsAExactBitPassThroughForFloat() {
        val p = LoudnessProcessor()
        configure(p, C.ENCODING_PCM_FLOAT)
        p.setLevel(0.0, engageLimiter = false)

        val out = run(p, floatBuffer(floatArrayOf(0.25f, -0.5f, 0.999f, 0f)))
        assertEquals(listOf(0.25f, -0.5f, 0.999f, 0f), floatsOf(out))
    }

    @Test
    fun offStateIsAExactBitPassThroughForInt16() {
        val p = LoudnessProcessor()
        configure(p, C.ENCODING_PCM_16BIT)
        p.setLevel(0.0, engageLimiter = false)

        val samples = shortArrayOf(100, -32767, 32767, 0)
        val out = run(p, shortBuffer(samples))
        assertEquals(samples.toList(), shortsOf(out))
    }

    // ------------------------------------------------------------------
    // The gain
    // ------------------------------------------------------------------

    @Test
    fun floatGainIsAppliedPerSample() {
        val p = LoudnessProcessor()
        configure(p, C.ENCODING_PCM_FLOAT)
        // 20*log10(2): a doubling gain.
        val gainDb = 20.0 * Math.log10(2.0)
        p.setLevel(gainDb, engageLimiter = false)

        val out = run(p, floatBuffer(floatArrayOf(0.25f, -0.5f, 0.125f)))
        val got = floatsOf(out)
        assertEquals(0.5f, got[0], 1e-4f)
        assertEquals(-1.0f, got[1], 1e-4f)
        assertEquals(0.25f, got[2], 1e-4f)
    }

    @Test
    fun int16GainIsAppliedInTheFullScaleDomain() {
        val p = LoudnessProcessor()
        configure(p, C.ENCODING_PCM_16BIT)
        val gainDb = 20.0 * Math.log10(2.0)
        p.setLevel(gainDb, engageLimiter = false)

        // 8192 = 0.25 full scale -> 0.5 full scale = 16383 (32767 scale).
        val out = run(p, shortBuffer(shortArrayOf(8192, -8192)))
        val got = shortsOf(out)
        assertTrue("8192 doubles to ~16383", Math.abs(got[0].toDouble() - 16383.0) <= 2.0)
        assertTrue("-8192 doubles to ~-16383", Math.abs(got[1].toDouble() + 16383.0) <= 2.0)
    }

    @Test
    fun aNegativeGainAttenuates() {
        val p = LoudnessProcessor()
        configure(p, C.ENCODING_PCM_FLOAT)
        p.setLevel(-20.0, engageLimiter = false) // 10^( -20 / 20 ) = 0.1
        val out = run(p, floatBuffer(floatArrayOf(0.5f, -0.5f)))
        val got = floatsOf(out)
        assertEquals(0.05f, got[0], 1e-5f)
        assertEquals(-0.05f, got[1], 1e-5f)
    }

    // ------------------------------------------------------------------
    // The soft-clip limiter (boosted tracks only)
    // ------------------------------------------------------------------

    @Test
    fun theLimiterBendsABoostedSignalJustBelowFullScale() {
        val p = LoudnessProcessor()
        configure(p, C.ENCODING_PCM_FLOAT)
        p.setLevel(14.0, engageLimiter = true)

        // 0.5 full scale at +14 dB is ~2.5 full scale before the bend.
        val out = run(p, floatBuffer(floatArrayOf(0.5f)))
        val got = floatsOf(out).single()
        // The bend asymptotically approaches -1 dBTP; in single precision
        // tanh saturates at 1.0, so the sample lands AT the full-scale
        // ceiling (the representable maximum) - it never exceeds it.
        assertTrue("the bend keeps the sample at or under full scale", got <= 1.0f)
        assertTrue("and far above the raw, un-limited expectation", got > 0.95f)
    }

    @Test
    fun theLimiterIsSymmetricForNegativeSamples() {
        val p = LoudnessProcessor()
        configure(p, C.ENCODING_PCM_FLOAT)
        p.setLevel(14.0, engageLimiter = true)

        val out = run(p, floatBuffer(floatArrayOf(0.5f, -0.5f)))
        val (pos, neg) = floatsOf(out)
        assertEquals(pos, -neg, 1e-6f)
        // At or under the full-scale ceiling (float tanh saturates at 1.0).
        assertTrue(neg >= -1.0f)
    }

    @Test
    fun theLimiterIsNotEngagedUnlessTold() {
        val p = LoudnessProcessor()
        configure(p, C.ENCODING_PCM_FLOAT)
        // The exact doubling gain (20*log10(2) dB): 0.5 lands exactly on
        // 1.0 full scale - with no limiter that raw (representable) value
        // stands, not a bend.
        val gainDb = 20.0 * Math.log10(2.0)
        p.setLevel(gainDb, engageLimiter = false)

        val out = run(p, floatBuffer(floatArrayOf(0.5f)))
        assertEquals(1.0f, floatsOf(out).single(), 1e-5f)
    }

    @Test
    fun int16WithTheLimiterNeverExceedsTheScale() {
        val p = LoudnessProcessor()
        configure(p, C.ENCODING_PCM_16BIT)
        p.setLevel(14.0, engageLimiter = true)

        val out = run(p, shortBuffer(shortArrayOf(16384, -16384)))
        val got = shortsOf(out)
        assertTrue(got[0] in 32000..32767)
        assertTrue(got[1] in -32767..-32000)
    }

    // ------------------------------------------------------------------
    // The format contract
    // ------------------------------------------------------------------

    @Test
    fun configureAccepts16BitAndFloatAndPassesTheFormatThrough() {
        val p = LoudnessProcessor()
        val f16 = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
        assertEquals(f16.encoding, p.configure(f16).encoding)
        val f32 = AudioProcessor.AudioFormat(44100, 1, C.ENCODING_PCM_FLOAT)
        assertEquals(f32.encoding, p.configure(f32).encoding)
    }

    @Test
    fun configureRefusesAnUnsupportedEncoding() {
        val p = LoudnessProcessor()
        val bad = AudioProcessor.AudioFormat(44100, 1, C.ENCODING_PCM_32BIT)
        assertThrows(AudioProcessor.UnhandledAudioFormatException::class.java) {
            p.configure(bad)
        }
    }

    @Test
    fun flushKeepsTheGainLive() {
        val p = LoudnessProcessor()
        configure(p, C.ENCODING_PCM_FLOAT)
        p.setLevel(6.0, engageLimiter = false)
        p.flush()
        // A flush clears nothing: the gain is a live value, and a new
        // level is applied before the next track's audio flows.
        p.setLevel(0.0, engageLimiter = false)
        val out = run(p, floatBuffer(floatArrayOf(0.5f)))
        assertEquals(0.5f, floatsOf(out).single(), 1e-6f)
    }

    // ------------------------------------------------------------------
    // The measurement processor
    // ------------------------------------------------------------------

    @Test
    fun theMeterProcessorPassesTheSamplesThroughUntouched() {
        val p = LoudnessMeterProcessor()
        configure(p, C.ENCODING_PCM_FLOAT)

        val samples = floatArrayOf(0.25f, -0.5f, 0.75f, 0.125f)
        val out = run(p, floatBuffer(samples))
        assertEquals(samples.toList(), floatsOf(out))
    }

    @Test
    fun theMeterProcessorFeedsTheMeter() {
        val p = LoudnessMeterProcessor()
        configure(p, C.ENCODING_PCM_FLOAT)

        // One second of a 440 Hz sine at half full scale (mono): a real
        // reading, far from silence.
        val seconds = 1
        val rate = 44100
        val samples = FloatArray(seconds * rate) {
            0.5f * sin(2.0 * Math.PI * 440.0 * it / rate).toFloat()
        }
        run(p, floatBuffer(samples))

        val result = p.result()
        assertNotNull("a real stream yields a loudness", result.lufs)
        assertTrue("440 Hz at half scale is well above silence", result.lufs!! in -40.0..0.0)
        // The true peak is the sine's own amplitude (0.5 = -6.02 dBTP).
        assertNotNull(result.truePeakDbTp)
        assertEquals(-6.0206, result.truePeakDbTp!!, 0.05)
    }

    @Test
    fun theMeterProcessorCountsFramesForInterleavedChannels() {
        val p = LoudnessMeterProcessor()
        // Stereo: two samples per frame. The feed must divide by the
        // channel count (a per-sample count would run past the end).
        val stereoFormat = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_FLOAT)
        p.configure(stereoFormat)

        val rate = 44100
        val seconds = 1
        val samples = FloatArray(seconds * rate * 2) {
            0.5f * sin(2.0 * Math.PI * 440.0 * (it / 2.0) / rate).toFloat()
        }
        run(p, floatBuffer(samples))

        val result = p.result()
        assertNotNull("the stereo stream is measured", result.lufs)
        assertTrue(result.lufs!! in -40.0..0.0)
    }

    @Test
    fun aFlushResetsTheMeter() {
        val p = LoudnessMeterProcessor()
        configure(p, C.ENCODING_PCM_FLOAT)
        val rate = 44100
        val samples = FloatArray(rate) {
            0.5f * sin(2.0 * Math.PI * 440.0 * it / rate).toFloat()
        }
        run(p, floatBuffer(samples))
        assertNotNull(p.result().lufs)

        // A mid-stream discontinuity: the meter resumes as if the stream
        // had just started (no blocks yet - no loudness).
        p.flush()
        assertNull("the flushed meter reports no loudness yet", p.result().lufs)
    }

    @Test
    fun silenceYieldsNoLoudness() {
        val p = LoudnessMeterProcessor()
        configure(p, C.ENCODING_PCM_16BIT)
        val zeros = ShortArray(44100)
        run(p, shortBuffer(zeros))
        assertNull("silence carries no loudness (never a fake reading)", p.result().lufs)
    }
}
