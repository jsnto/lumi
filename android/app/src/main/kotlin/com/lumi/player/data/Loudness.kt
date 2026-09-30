package com.lumi.player.data

import androidx.room.ColumnInfo
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan
import kotlin.math.tanh

/**
 * On-device loudness measurement and per-track gain (ITU-R BS.1770-4 /
 * EBU R128), pure math.
 *
 * The [LoudnessMeter] is a streaming meter: samples are K-weighted with
 * CONTINUOUS filter state across the 400 ms block windows (100 ms step,
 * 75% overlap), the block mean squares are gated (the absolute gate, then
 * the relative gate 10 LU below the ungated integrated value), and the
 * integrated loudness lands in LUFS. The same module carries the gain
 * math (measure-to-target, headroom-capped) and the soft clip the playback
 * limiter uses on boosted tracks.
 */

// ---------------------------------------------------------------------------
// K-weighting
// ---------------------------------------------------------------------------

/**
 * The BS.1770 K-weighting filter coefficients for [sampleRate] Hz: a
 * 5-tap direct-form II filter (the R128 pre-filter: the high-shelf gain
 * stage cascaded with the high-pass stage), returned as `(a, b)`.
 */
internal fun kWeightCoefficients(sampleRate: Int): Pair<DoubleArray, DoubleArray> {
    // Stage 1: the high shelf (G dB at 1.681974450955533 kHz).
    val f01 = 1681.974450955533
    val gDb = 3.999843853973347
    val q1 = 0.7071752369554196
    val k1 = tan(PI * f01 / sampleRate)
    val vh = 10.0.pow(gDb / 20.0)
    val vb = vh.pow(0.4996667741545416)
    val a0 = 1.0 + k1 / q1 + k1 * k1
    val pb = doubleArrayOf(
        (vh + vb * k1 / q1 + k1 * k1) / a0,
        2.0 * (k1 * k1 - vh) / a0,
        (vh - vb * k1 / q1 + k1 * k1) / a0,
    )
    val pa = doubleArrayOf(
        1.0,
        2.0 * (k1 * k1 - 1.0) / a0,
        (1.0 - k1 / q1 + k1 * k1) / a0,
    )
    // Stage 2: the high-pass at 38.13547087602444 Hz.
    val f02 = 38.13547087602444
    val q2 = 0.5003270373238773
    val k2 = tan(PI * f02 / sampleRate)
    val a02 = 1.0 + k2 / q2 + k2 * k2
    val rb = doubleArrayOf(1.0, -2.0, 1.0)
    val ra = doubleArrayOf(
        1.0,
        2.0 * (k2 * k2 - 1.0) / a02,
        (1.0 - k2 / q2 + k2 * k2) / a02,
    )
    // The cascade, convolved to a single 5-tap filter.
    val b = DoubleArray(5)
    val a = DoubleArray(5)
    for (i in 0..2) {
        for (j in 0..2) {
            b[i + j] += pb[i] * rb[j]
            a[i + j] += pa[i] * ra[j]
        }
    }
    return a to b
}

/**
 * One channel's K-weighting filter (direct form II, transposed). The state
 * is CONTINUOUS: call [process] on every sample of the stream; the 400 ms
 * block windows re-feed overlapping samples through the same running state
 * (the R128 reference's behavior).
 */
internal class KWeightFilter(a: DoubleArray, b: DoubleArray) {

    private val a = a
    private val b = b
    private val x = DoubleArray(5)
    private val y = DoubleArray(5)

    fun process(sample: Double): Double {
        for (i in 4 downTo 1) {
            x[i] = x[i - 1]
            y[i] = y[i - 1]
        }
        x[0] = sample
        var out = b[0] * sample
        for (i in 1..4) out += b[i] * x[i]
        for (j in 1..4) out -= a[j] * y[j]
        y[0] = out
        return out / a[0]
    }
}

// ---------------------------------------------------------------------------
// The streaming meter
// ---------------------------------------------------------------------------

/**
 * A streaming integrated-loudness meter (the gating pass of ITU-R
 * BS.1770-4 / EBU R128).
 *
 * Samples are ingested interleaved ([processInterleaved]), K-weighted with
 * the per-channel continuous filter, and the 400 ms block mean squares
 * (100 ms step, 75% overlap) are gated when [result] is taken: the
 * absolute gate (-70 LUFS mean square), then the relative gate 10 LU below
 * the ungated integrated loudness. A stream shorter than one 400 ms window
 * (or silent) reports NO loudness ([LoudnessResult.lufs] null), never a
 * fake reading.
 */
class LoudnessMeter {

    private var sampleRate = 0
    private var channelCount = 0
    private var blockLen = 0
    private var stepLen = 0
    private var coefficients: Pair<DoubleArray, DoubleArray>? = null
    private var filters: Array<KWeightFilter>? = null
    private var ring: Array<DoubleArray>? = null
    private var totals: LongArray? = null
    private val blockMeans = ArrayList<Double>()
    private var truePeak = 0.0
    private var primed = false

    /**
     * Configures the meter for a stream. A repeat configure with the same
     * [sampleRate] and [channelCount] is a no-op; any change resets the
     * streaming state (a new stream starts fresh).
     */
    fun configure(sampleRate: Int, channelCount: Int) {
        if (primed && this.sampleRate == sampleRate && this.channelCount == channelCount) return
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        blockLen = 400 * sampleRate / 1000 // the 400 ms window
        stepLen = 100 * sampleRate / 1000 // the 100 ms step
        val (a, b) = kWeightCoefficients(sampleRate)
        coefficients = a to b
        filters = Array(channelCount) { KWeightFilter(a, b) }
        ring = Array(channelCount) { DoubleArray(blockLen) }
        totals = LongArray(channelCount)
        blockMeans.clear()
        truePeak = 0.0
        primed = true
    }

    /**
     * Resets the streaming state (the K-weighting filter state, the block
     * statistics, the true peak) while keeping the stream configuration:
     * the meter resumes as if the stream had just started. Used on
     * discontinuities (flushes) inside one measured stream.
     */
    fun reset() {
        if (!primed) return
        val (a, b) = coefficients ?: return
        filters = Array(channelCount) { KWeightFilter(a, b) }
        ring?.forEach { it.fill(0.0) }
        totals?.fill(0L)
        blockMeans.clear()
        truePeak = 0.0
    }

    /**
     * Ingests [frameCount] frames of interleaved samples: [channelCount]
     * samples per frame, channel 0 first, read from [samples] at [offset].
     */
    fun processInterleaved(samples: DoubleArray, offset: Int, frameCount: Int) {
        val filters = filters ?: return
        val ring = ring ?: return
        val totals = totals ?: return
        for (ch in 0 until channelCount) {
            val filter = filters[ch]
            val channelRing = ring[ch]
            var total = totals[ch]
            var index = offset + ch
            for (frame in 0 until frameCount) {
                val s = samples[index]
                index += channelCount
                val magnitude = abs(s)
                if (magnitude > truePeak) truePeak = magnitude
                channelRing[(total % blockLen).toInt()] = s
                total++
                if (total >= blockLen && total % stepLen.toLong() == stepLen - 1L) {
                    processWindow(filter, channelRing, total)
                }
            }
            totals[ch] = total
        }
    }

    /**
     * The integrated-loudness result for everything ingested so far.
     * [LoudnessResult.lufs] is null when no block survives the gates
     * (silence, or a stream shorter than one window) - "no loudness",
     * not zero.
     */
    fun result(): LoudnessResult {
        if (!primed) return LoudnessResult(null, null)
        val absolutelyGated = blockMeans.filter { it > ABSOLUTE_GATE }
        val lufs = absolutelyGated.takeIf { it.isNotEmpty() }?.let {
            val ungated = it.average()
            val relativeGate = ungated / RELATIVE_GATE
            val relativelyGated = it.filter { mean -> mean >= relativeGate }
            INTEGRATION_OFFSET + 10.0 * ln(relativelyGated.average()) / LN_10
        }
        val truePeakDbTp = if (truePeak > 0.0) 20.0 * ln(truePeak) / LN_10 else null
        return LoudnessResult(lufs, truePeakDbTp)
    }

    private fun processWindow(filter: KWeightFilter, ringChannel: DoubleArray, total: Long) {
        var slot = ((total - blockLen) % blockLen).toInt()
        var accumulator = 0.0
        for (k in 0 until blockLen) {
            val weighted = filter.process(ringChannel[slot])
            accumulator += weighted * weighted
            slot++
            if (slot == blockLen) slot = 0
        }
        blockMeans.add(accumulator / blockLen)
    }

    companion object {
        /** The absolute gate, in block mean square (-70 LUFS). */
        private const val ABSOLUTE_GATE = 1e-7
        /** The relative gate: 10 LU below the ungated integrated value. */
        private const val RELATIVE_GATE = 10.0
        /** The BS.1770 integration offset. */
        private const val INTEGRATION_OFFSET = -8.61
        private const val LN_10 = 2.302585092994046
    }
}

// ---------------------------------------------------------------------------
// Results and the gain math
// ---------------------------------------------------------------------------

/**
 * A loudness measurement. [lufs] null means the audio carries NO loudness
 * (it is silent, or shorter than one 400 ms window) - a gain must never be
 * computed from such a reading. [truePeakDbTp] null means no peak sample
 * was seen (the same silence case).
 */
data class LoudnessResult(
    val lufs: Double?,
    val truePeakDbTp: Double?,
) {
    val hasLoudness: Boolean
        get() = lufs != null
}

/**
 * A measured loudness sample as the player service consumes it (the
 * per-payload snapshot kept by [com.lumi.player.data.LoudnessStore]).
 * [lufs] / [truePeakDbTp] follow [LoudnessResult]'s nulls.
 */
data class LoudnessSample(
    val lufs: Double?,
    val truePeakDbTp: Double?,
    val fingerprint: String,
    val measuredAt: Long,
)

/**
 * A loudness row joined to its content row's payload: the DAO mapping
 * behind [LoudnessStore]'s service snapshot.
 */
data class EntryLoudnessPayload(
    @ColumnInfo(name = "entry_id") val entryId: Long,
    @ColumnInfo(name = "lufs") val lufs: Double?,
    @ColumnInfo(name = "true_peak_db_tp") val truePeakDbTp: Double?,
    @ColumnInfo(name = "fingerprint") val fingerprint: String,
    @ColumnInfo(name = "measured_at") val measuredAt: Long,
    val payload: String,
)

/**
 * The audio level's measurement status for the settings pane's status row:
 * [measured] of [total] audio entries carry a stored measurement.
 */
data class LoudnessStatus(
    val measured: Int,
    val total: Int,
) {
    /** The row's copy: "N of M songs measured" ("1 of 1 song" when one). */
    val text: String
        get() {
            val song = if (total == 1) "song" else "songs"
            return "$measured of $total $song measured"
        }
}

// ---------------------------------------------------------------------------
// The per-track gain
// ---------------------------------------------------------------------------

/**
 * The peak the gain may push the true peak up to, in dBTP: -1 dBTP leaves
 * headroom for the measurement's own error, and the playback limiter (the
 * soft clip below) keeps anything past the knee below full scale.
 */
const val PEAK_CEILING_DBTP = -1.0

/**
 * The gain (dB) that brings a measured track's integrated loudness to
 * [targetLufs], capped by the track's peak headroom:
 * `min(target - measured, ceiling - peak)`. A track with no loudness
 * ([measuredLufs] null) gets NO gain (0.0) - a silence reading is not a
 * quiet reading.
 */
fun gainFor(
    measuredLufs: Double?,
    truePeakDbTp: Double?,
    targetLufs: Double,
    peakCeilingDbTp: Double = PEAK_CEILING_DBTP,
): Double {
    if (measuredLufs == null) return 0.0
    val gap = targetLufs - measuredLufs
    val headroom = truePeakDbTp?.let { peakCeilingDbTp - it } ?: Double.POSITIVE_INFINITY
    return min(gap, headroom)
}

/**
 * The audio level setting's convenience: no gain while the mode is off, or
 * while the track is unmeasured or carries no loudness; otherwise the
 * measure-to-target gain capped by the track's peak headroom.
 */
fun gainFor(
    mode: AudioLevelMode,
    targetLufs: Double,
    sample: LoudnessSample?,
): Double =
    if (mode == AudioLevelMode.OFF || sample == null || sample.lufs == null) {
        0.0
    } else {
        gainFor(sample.lufs, sample.truePeakDbTp, targetLufs)
    }

/**
 * The playback limiter's soft-clip knee, in linear amplitude (-1 dBTP).
 * Boosted tracks are limited there so the applied gain never hard-clips.
 */
const val LIMITER_KNEE_LINEAR = 0.8912509381337453 // 10 ^ (-1/20)

/**
 * The soft clip: identity up to [knee], and above it the magnitude is
 * compressed toward the 1.0 asymptote (a tangent-matched tanh bend), so a
 * boosted track settles just under full scale instead of clipping.
 */
fun softClip(x: Double, knee: Double = LIMITER_KNEE_LINEAR): Double {
    val magnitude = abs(x)
    if (magnitude <= knee) return x
    val sign = if (x < 0) -1.0 else 1.0
    val normalized = (magnitude - knee) / (1.0 - knee)
    return sign * (knee + (1.0 - knee) * tanh(normalized))
}
