package com.lumi.player.data

import com.lumi.player.core.LIMITER_KNEE_LINEAR
import com.lumi.player.core.LoudnessMeter
import com.lumi.player.core.LoudnessResult
import com.lumi.player.core.gainFor as coreGainFor
import com.lumi.player.core.softClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min
import kotlin.math.pow

/**
 * The test-vector contract of the on-device loudness meter: the
 * [LoudnessMeter] must match the reference R128 implementation on the
 * pinned fixtures (sines rendered at 48 kHz, 30 s) within 0.05 LU. The
 * reference values are the discovery report's fixture table (one
 * deliberately rendered per frequency / level):
 *
 * - 1 kHz sine, 0 dBFS peak:   -10.92 LUFS, peak 0.00 dB,   gain -5.08 dB
 * - 1 kHz sine, -20 dBFS:      -30.92 LUFS, peak -20.00 dB, gain +14.92 dB
 * - 440 Hz sine, 0 dBFS:       -11.61 LUFS,                 gain -4.39 dB
 * - 100 Hz sine, 0 dBFS:       -12.75 LUFS,                 gain -3.25 dB
 * - 10 kHz sine, 0 dBFS:       -7.58 LUFS,                  gain -8.42 dB
 * - 10 s 0 dBFS + 20 s -20 dBFS @ 1 kHz: -10.99 LUFS, gain -5.01 dB
 * - Silence: no loudness.
 *
 * and the per-track gain must be the level difference capped by the
 * track's true-peak headroom (the -1 dBTP ceiling).
 */
class LoudnessTest {

    private val fs = 48_000
    private val seconds30 = 30 * fs

    private fun sine(hz: Double, levelDb: Double = 0.0, length: Int = seconds30): DoubleArray {
        val amplitude = if (levelDb == 0.0) 1.0 else 10.0.pow(levelDb / 20.0)
        return DoubleArray(length) { i ->
            amplitude * Math.sin(2.0 * Math.PI * hz * i / fs)
        }
    }

    private fun meter(samples: DoubleArray, rate: Int = fs, channels: Int = 1): LoudnessResult {
        val meter = LoudnessMeter()
        meter.configure(rate, channels)
        meter.processInterleaved(samples, 0, samples.size / channels)
        return meter.result()
    }

    // ---- the fixture table -------------------------------------------------

    @Test
    fun fixture1khzFullScale_matchesReferenceWithinFiftyMilLU() {
        val result = meter(sine(1_000.0))

        assertEquals(-10.92, result.lufs!!, 0.05)
        assertEquals(0.00, result.truePeakDbTp!!, 0.05)
        // The per-track gain at the -16 LUFS default target: the level
        // difference (the headroom cap does not bind: -1 - 0 = -1 > -5.08).
        assertEquals(-5.08, coreGainFor(result.lufs, result.truePeakDbTp, -16.0), 0.05)
    }

    @Test
    fun fixture1khzMinus20Db_matchesReferenceWithinFiftyMilLU() {
        val result = meter(sine(1_000.0, levelDb = -20.0))

        assertEquals(-30.92, result.lufs!!, 0.05)
        assertEquals(-20.00, result.truePeakDbTp!!, 0.05)
        // The gain is the level difference up to the ceiling (the ceiling
        // does not bind here: -1 - (-20) = 19 > 14.92).
        assertEquals(+14.92, coreGainFor(result.lufs, result.truePeakDbTp, -16.0), 0.05)
    }

    @Test
    fun fixture440HzFullScale_matchesReferenceWithinFiftyMilLU() {
        val result = meter(sine(440.0))

        assertEquals(-11.61, result.lufs!!, 0.05)
        assertEquals(-4.39, coreGainFor(result.lufs, result.truePeakDbTp, -16.0), 0.05)
    }

    @Test
    fun fixture100HzFullScale_matchesReferenceWithinFiftyMilLU() {
        val result = meter(sine(100.0))

        assertEquals(-12.75, result.lufs!!, 0.05)
        assertEquals(-3.25, coreGainFor(result.lufs, result.truePeakDbTp, -16.0), 0.05)
    }

    @Test
    fun fixture10kHzFullScale_matchesReferenceWithinFiftyMilLU() {
        val result = meter(sine(10_000.0))

        assertEquals(-7.58, result.lufs!!, 0.05)
        assertEquals(-8.42, coreGainFor(result.lufs, result.truePeakDbTp, -16.0), 0.05)
    }

    @Test
    @Suppress("ktlint:standard:function-naming")
    fun fixtureMixed10sFullThen20sMinus20_matchesReferenceWithinFiftyMilLU() {
        val mixed = DoubleArray(seconds30)
        val breakPoint = 10 * fs
        for (i in 0 until breakPoint) {
            mixed[i] = Math.sin(2.0 * Math.PI * 1_000.0 * i / fs)
        }
        for (i in breakPoint until mixed.size) {
            mixed[i] = 0.1 * Math.sin(2.0 * Math.PI * 1_000.0 * i / fs)
        }
        val result = meter(mixed)

        assertEquals(-10.99, result.lufs!!, 0.05)
        assertEquals(-5.01, coreGainFor(result.lufs, result.truePeakDbTp, -16.0), 0.05)
    }

    @Test
    fun silence_reportsNoLoudnessNeverAFakeReading() {
        val result = meter(DoubleArray(seconds30))

        assertNull("silence has no loudness", result.lufs)
        assertNull("silence has no true peak", result.truePeakDbTp)
        // And no gain is ever computed from it.
        assertEquals(0.0, coreGainFor(result.lufs, result.truePeakDbTp, -16.0), 1e-9)
    }

    // ---- the meter's invariants ---------------------------------------------

    @Test
    fun levelShiftOf20Db_shiftsLoudnessByExactly20Lu() {
        val full = meter(sine(1_000.0)).lufs!!
        val quiet = meter(sine(1_000.0, levelDb = -20.0)).lufs!!

        assertEquals(20.0, full - quiet, 0.05)
    }

    @Test
    fun streamShorterThanOneWindow_reportsNoLoudness() {
        val result = meter(sine(1_000.0, length = 200)) // 4.1 ms

        assertNull(result.lufs)
    }

    @Test
    fun oneFullWindow_reportsLoudnessTheMomentItsLastSampleLands() {
        // Exactly 400 ms at 48 kHz (19 200 samples): one complete window,
        // and the window is evaluated once its full 400 ms has been
        // consumed - a stream of 400-499 ms carries it.
        val result = meter(sine(1_000.0, length = fs * 4 / 10))

        assertTrue("a 400 ms stream carries one complete window", result.hasLoudness)
        assertEquals(-10.92, result.lufs!!, 0.05)
    }

    @Test
    fun oneSampleShortOfOneWindow_reportsNoLoudness() {
        val result = meter(sine(1_000.0, length = fs * 4 / 10 - 1)) // 399.98 ms

        assertNull("the window is not complete until its 400 ms is ingested", result.lufs)
    }

    @Test
    fun reset_restartsTheMeterOnTheSameConfiguration() {
        val oneSecond = DoubleArray(fs) { i ->
            Math.sin(2.0 * Math.PI * 1_000.0 * i / fs)
        }
        val meter = LoudnessMeter()
        meter.configure(fs, 1)
        meter.processInterleaved(oneSecond, 0, fs)
        val first = meter.result()
        assertTrue(first.hasLoudness)

        meter.reset()
        val empty = meter.result()
        assertNull("a reset clears the block statistics", empty.lufs)

        meter.processInterleaved(oneSecond, 0, fs)
        val second = meter.result()
        assertEquals("a reset restarts the statistics deterministically", first, second)
    }

    @Test
    fun reconfigureToADifferentStream_resetsTheStatistics() {
        val meter = LoudnessMeter()
        meter.configure(fs, 1)
        meter.processInterleaved(sine(1_000.0, length = 2 * fs), 0, 2 * fs)
        assertTrue(meter.result().hasLoudness)

        meter.configure(44_100, 1) // a new stream
        val rightAfter = meter.result()
        assertNull("a reconfigure starts the new stream fresh", rightAfter.lufs)
    }

    @Test
    fun stereoStream_measuresThePerChannelAverage() {
        // Both channels carry the same 1 kHz sine: the per-channel block
        // means are equal, so the integrated loudness equals the mono
        // reading of one channel.
        val mono = DoubleArray(seconds30) { i -> Math.sin(2.0 * Math.PI * 1_000.0 * i / fs) }
        val stereo = DoubleArray(seconds30 * 2)
        for (i in 0 until seconds30) {
            stereo[i * 2] = mono[i]
            stereo[i * 2 + 1] = mono[i]
        }
        val monoResult = meter(mono, channels = 1)
        val stereoResult = meter(stereo, channels = 2)

        assertEquals(monoResult.lufs!!, stereoResult.lufs!!, 0.05)
    }

    @Test
    @Suppress("ktlint:standard:function-naming")
    fun aToneInOnlyOneChannel_measuresAboutThreeLuBelowTheDuplicatedTone() {
        // The block value is the LINEAR channel average of the per-channel
        // block mean squares: a tone duplicated on both channels keeps the
        // mono reading, while the same tone in only one of the two channels
        // halves the mean square - 10 * log10(2) = 3.01 LU lower.
        val tone = DoubleArray(seconds30) { i -> Math.sin(2.0 * Math.PI * 1_000.0 * i / fs) }
        val duplicated = DoubleArray(seconds30 * 2)
        val hardPanned = DoubleArray(seconds30 * 2)
        for (i in 0 until seconds30) {
            duplicated[i * 2] = tone[i]
            duplicated[i * 2 + 1] = tone[i]
            hardPanned[i * 2] = tone[i]
            // The right channel stays silent.
        }
        val duplicatedLufs = meter(duplicated, channels = 2).lufs!!
        val hardPannedLufs = meter(hardPanned, channels = 2).lufs!!

        assertEquals("the duplicated tone keeps the mono reading", -10.92, duplicatedLufs, 0.05)
        assertEquals("the single-channel tone lands at the combined-gate value", -13.93, hardPannedLufs, 0.05)
        assertEquals(3.0103, duplicatedLufs - hardPannedLufs, 0.05)
    }

    @Test
    fun chunkedFeeding_acrossManyCalls_matchesTheWholeStreamReading() {
        // The measurement sink feeds the meter in small buffers, so the
        // window schedule must hold across calls, not just within one.
        val oneSecond = DoubleArray(fs) { i -> Math.sin(2.0 * Math.PI * 1_000.0 * i / fs) }
        val whole = meter(oneSecond)

        val chunked = LoudnessMeter()
        chunked.configure(fs, 1)
        var offset = 0
        while (offset < oneSecond.size) {
            val chunk = min(7_000, oneSecond.size - offset)
            chunked.processInterleaved(oneSecond, offset, chunk)
            offset += chunk
        }

        assertEquals(whole.lufs!!, chunked.result().lufs!!, 1e-9)
    }

    @Test
    fun otherSampleRate_measuresAFiniteLoudness() {
        // The 44.1 kHz coefficients differ slightly from 48 kHz; the
        // contract pins the 48 kHz vectors, so here the 1 kHz sine at
        // 44.1 kHz must simply measure a finite loudness near the 48 kHz
        // reference.
        val sampleRate = 44_100
        val samples = DoubleArray(30 * sampleRate) { i ->
            Math.sin(2.0 * Math.PI * 1_000.0 * i / sampleRate)
        }
        val result = meter(samples, rate = sampleRate)

        assertTrue(result.hasLoudness)
        assertEquals(-10.92, result.lufs!!, 0.5)
    }

    // ---- the gain math -------------------------------------------------------

    @Test
    fun gain_isTheLevelDifference_whenHeadroomAllows() {
        // Gap 3 dB, headroom -1 - (-10) = 9 dB: the gap wins.
        assertEquals(-5.0, coreGainFor(-10.0, 0.0, -15.0), 1e-9)
        assertEquals(+3.0, coreGainFor(-19.0, -10.0, -16.0), 1e-9)
    }

    @Test
    fun gain_isCappedByTheTruePeakHeadroom() {
        // Measured -15.0, peak 0.0, target -16: the gap is -1 dB, and the
        // headroom cap (-1 - 0 = -1) is exactly equal - it must never push
        // the peak past the ceiling.
        assertEquals(-1.0, coreGainFor(-15.0, 0.0, -16.0), 1e-9)
        // A louder track with no headroom: the cap (not the gap) binds.
        assertEquals(-1.0, coreGainFor(-16.5, 0.0, -14.0), 1e-9)
    }

    @Test
    fun gain_withNoPeakReading_isTheUnboundedGap() {
        assertEquals(+2.5, coreGainFor(-16.5, null, -14.0), 1e-9)
    }

    @Test
    fun gain_forSilence_isZero() {
        assertEquals(0.0, coreGainFor(null, null, -16.0), 1e-9)
    }

    @Test
    fun gainForTheAudioLevelSetting_offOrUnmeasuredMeansNoGain() {
        assertEquals(0.0, gainFor(AudioLevelMode.OFF, -16.0, LoudnessSample(-10.0, 0.0, "fp", 0L)), 1e-9)
        assertEquals(0.0, gainFor(AudioLevelMode.NORMALIZE, -16.0, null), 1e-9)
        assertEquals(
            0.0,
            gainFor(AudioLevelMode.NORMALIZE, -16.0, LoudnessSample(null, null, "fp", 0L)),
            1e-9,
        )
        assertEquals(
            -5.0,
            gainFor(AudioLevelMode.NORMALIZE, -15.0, LoudnessSample(-10.0, 0.0, "fp", 0L)),
            1e-9,
        )
    }

    // ---- the soft clip ---------------------------------------------------------

    @Test
    fun softClip_isIdentityBelowTheKnee() {
        for (v in listOf(-0.5, -0.1, 0.0, 0.3, 0.8)) {
            assertEquals(v, softClip(v), 1e-9)
        }
        assertEquals(LIMITER_KNEE_LINEAR, softClip(LIMITER_KNEE_LINEAR), 1e-9)
        assertEquals(-LIMITER_KNEE_LINEAR, softClip(-LIMITER_KNEE_LINEAR), 1e-9)
    }

    @Test
    fun softClip_compressesAboveTheKneeAndBoundedBelowFullScale() {
        val knee = LIMITER_KNEE_LINEAR

        val atKneePlusEps = softClip(knee + 1e-6)
        // The bend is tangent-matched: at the knee the curve leaves at
        // slope 1, so infinitesimally above the knee the output still
        // tracks the input - the compression shows up further out.
        assertTrue("above the knee the output still moves up", atKneePlusEps > knee)

        val above = softClip(2.0 * knee)
        assertTrue("compressed below the 1:1 line", above < 2.0 * knee)
        assertTrue("but still above the knee", above > knee)

        for (input in listOf(1.0, 1.5, 2.0, 10.0)) {
            val out = softClip(input)
            assertTrue("never exceeds full scale (no hard clip)", out <= 1.0)
            assertTrue("above the knee", out > knee)
        }
        // Monotone, and the asymptote is 1.0: a huge input settles AT the
        // asymptote (full scale, which a float sample can hold - not a
        // clip), never past it.
        assertTrue(softClip(2.0) > softClip(1.0))
        assertEquals(1.0, softClip(1_000.0), 1e-12)
        assertEquals(-1.0, softClip(-1_000.0), 1e-12)
        // The negative side mirrors the positive one.
        assertEquals(-softClip(1.5), softClip(-1.5), 1e-9)
    }
}
