// The audio level's math: the R128 integrated-loudness meter (ITU-R
// BS.1770-4 / EBU R128 gating pass), the per-track gain that brings a
// measured track to the target level (capped by its true-peak headroom),
// and the peak limiter's soft clip. The pure counterpart of the Android
// app's data/Loudness.kt + data/AudioLevel.kt: the iOS app measures with
// AVFoundation (LumiApp's LoudnessMeasurer), stores the readings through
// the Store (the loudness rows), and applies the gain/clip in the
// playback path (LumiApp's PlaybackEngine loudness tap).

import Foundation

// ---------------------------------------------------------------------------
// The audio level setting
// ---------------------------------------------------------------------------

/// The playback volume setting (the Android app's AudioLevelMode):
/// OFF plays every song at the level it was recorded at; NORMALIZE brings
/// each measured song to the target level.
public enum AudioLevelMode: String, CaseIterable, Sendable {
    case off = "off"
    case normalize = "normalize"

    /// The stored choice's default (unset/invalid reads as OFF).
    public static let defaultValue: AudioLevelMode = .off

    /// Parses the stored value tolerantly (case/whitespace), like the
    /// app's other settings: unset or invalid falls back to OFF.
    public static func fromStorage(_ value: String?) -> AudioLevelMode {
        guard let value else { return .defaultValue }
        let v = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        for mode in AudioLevelMode.allCases where mode.rawValue == v {
            return mode
        }
        return .defaultValue
    }

    /// The settings-table value this mode stores under.
    public var storageValue: String { rawValue }
}

/// The audio level's target constants (the Android app's
/// AudioLevelSettings).
public enum AudioLevelSettings {
    /// The default target level, in LUFS.
    public static let defaultTargetLufs: Double = -16.0
    /// The ladder the picker offers, in LUFS.
    public static let targets: [Double] = [-18.0, -16.0, -14.0]
}

/// Reads the stored audio-level target. Unset, invalid, or a value outside
/// the ladder falls back to the default (-16 LUFS).
public func audioLevelTargetLufsFromStored(_ value: String?) -> Double {
    guard let value else { return AudioLevelSettings.defaultTargetLufs }
    let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
    guard let parsed = Double(trimmed) else { return AudioLevelSettings.defaultTargetLufs }
    return AudioLevelSettings.targets.contains(parsed) ? parsed : AudioLevelSettings.defaultTargetLufs
}

// ---------------------------------------------------------------------------
// The K-weighting filter
// ---------------------------------------------------------------------------

/**
 * The BS.1770 K-weight coefficients for [sampleRate] (Hz), convolved to a
 * single 5-tap filter (a[b] arrays, a[0] = 1) - the Android app's
 * kWeightCoefficients, coefficient for coefficient.
 */
func kWeightCoefficients(_ sampleRate: Int) -> (a: [Double], b: [Double]) {
    // Stage 1: the high shelf (gDb at f01).
    let f01 = 1681.974450955533
    let gDb = 3.999843853973347
    let q1 = 0.7071752369554196
    let k1 = tan(.pi * f01 / Double(sampleRate))
    let vh = pow(10.0, gDb / 20.0)
    let vb = pow(vh, 0.4996667741545416)
    let a0 = 1.0 + k1 / q1 + k1 * k1
    let pb: [Double] = [
        (vh + vb * k1 / q1 + k1 * k1) / a0,
        2.0 * (k1 * k1 - vh) / a0,
        (vh - vb * k1 / q1 + k1 * k1) / a0,
    ]
    let pa: [Double] = [
        1.0,
        2.0 * (k1 * k1 - 1.0) / a0,
        (1.0 - k1 / q1 + k1 * k1) / a0,
    ]
    // Stage 2: the high-pass at 38.13547087602444 Hz.
    let f02 = 38.13547087602444
    let q2 = 0.5003270373238773
    let k2 = tan(.pi * f02 / Double(sampleRate))
    let a02 = 1.0 + k2 / q2 + k2 * k2
    let rb: [Double] = [1.0, -2.0, 1.0]
    let ra: [Double] = [
        1.0,
        2.0 * (k2 * k2 - 1.0) / a02,
        (1.0 - k2 / q2 + k2 * k2) / a02,
    ]
    // The cascade, convolved to a single 5-tap filter.
    var b = [Double](repeating: 0.0, count: 5)
    var a = [Double](repeating: 0.0, count: 5)
    for i in 0...2 {
        for j in 0...2 {
            b[i + j] += pb[i] * rb[j]
            a[i + j] += pa[i] * ra[j]
        }
    }
    return (a, b)
}

/**
 * One channel's K-weighting filter (direct form II, transposed). The state
 * is CONTINUOUS: call [process] on every sample of the stream; the 400 ms
 * block windows re-feed overlapping samples through the same running state
 * (the R128 reference's behavior - and the Android implementation's,
 * which this mirrors call for call).
 */
final class KWeightFilter {
    private let a: [Double]
    private let b: [Double]
    private var x = [Double](repeating: 0.0, count: 5)
    private var y = [Double](repeating: 0.0, count: 5)

    init(a: [Double], b: [Double]) {
        self.a = a
        self.b = b
    }

    func process(_ sample: Double) -> Double {
        for i in (1..<5).reversed() {
            x[i] = x[i - 1]
            y[i] = y[i - 1]
        }
        x[0] = sample
        var out = b[0] * sample
        for i in 1...4 { out += b[i] * x[i] }
        for j in 1...4 { out -= a[j] * y[j] }
        y[0] = out
        return out / a[0]
    }
}

// ---------------------------------------------------------------------------
// The streaming meter
// ---------------------------------------------------------------------------

private enum LoudnessGate {
    /// The absolute gate, in block mean square (-70 LUFS).
    static let absoluteGate = 1e-7
    /// The relative gate: 10 LU below the ungated integrated value.
    static let relativeGate = 10.0
    /// The BS.1770 integration offset.
    static let integrationOffset = -8.61
    static let ln10 = 2.302585092994046
}

/**
 * A streaming integrated-loudness meter (the gating pass of ITU-R
 * BS.1770-4 / EBU R128).
 *
 * Samples are ingested interleaved ([processInterleaved]), K-weighted with
 * the per-channel continuous filter, and the 400 ms block mean squares
 * (100 ms step, 75% overlap) are combined across the channels per block
 * (the linear average - the BS.1770-4 block value) and gated when
 * [result] is taken: the absolute gate (-70 LUFS mean square), then the
 * relative gate 10 LU below the ungated integrated loudness. A block is
 * evaluated once its full 400 ms has been ingested - a stream of exactly
 * one 400 ms window already reports loudness; a shorter (or silent)
 * stream reports NO loudness ([result.lufs] nil), never a fake reading.
 */
public final class LoudnessMeter {
    private var sampleRate = 0
    private var channelCount = 0
    private var blockLen = 0
    private var stepLen = 0
    private var aCoeffs: [Double] = []
    private var bCoeffs: [Double] = []
    private var filters: [KWeightFilter] = []
    private var ring: [[Double]] = []
    private var totals: [Int64] = []
    /** The channel-combined 400 ms block mean squares, one per evaluated block. */
    private var windowMeans: [Double] = []
    private var truePeak = 0.0
    private var primed = false

    public init() {}

    /**
     * Configures the meter for a stream. A repeat configure with the same
     * [sampleRate] and [channelCount] is a no-op; any change resets the
     * streaming state (a new stream starts fresh).
     */
    public func configure(sampleRate: Int, channelCount: Int) {
        if primed && self.sampleRate == sampleRate && self.channelCount == channelCount { return }
        self.sampleRate = sampleRate
        self.channelCount = channelCount
        blockLen = 400 * sampleRate / 1000 // the 400 ms window
        stepLen = 100 * sampleRate / 1000 // the 100 ms step
        let coeffs = kWeightCoefficients(sampleRate)
        aCoeffs = coeffs.a
        bCoeffs = coeffs.b
        filters = (0..<channelCount).map { _ in KWeightFilter(a: coeffs.a, b: coeffs.b) }
        ring = (0..<channelCount).map { _ in [Double](repeating: 0.0, count: blockLen) }
        totals = [Int64](repeating: 0, count: channelCount)
        windowMeans.removeAll()
        truePeak = 0.0
        primed = true
    }

    /**
     * Resets the streaming state (the K-weighting filter state, the block
     * statistics, the true peak) while keeping the stream configuration:
     * the meter resumes as if the stream had just started. Used on
     * discontinuities (flushes) inside one measured stream.
     */
    public func reset() {
        guard primed else { return }
        filters = (0..<channelCount).map { _ in KWeightFilter(a: aCoeffs, b: bCoeffs) }
        for i in ring.indices {
            ring[i] = [Double](repeating: 0.0, count: blockLen)
        }
        for i in totals.indices {
            totals[i] = 0
        }
        windowMeans.removeAll()
        truePeak = 0.0
    }

    /**
     * Ingests [frameCount] frames of interleaved samples: [channelCount]
     * samples per frame, channel 0 first, read from [samples] at [offset].
     */
    public func processInterleaved(_ samples: [Double], offset: Int, frameCount: Int) {
        guard primed, frameCount > 0 else { return }
        // The block windows that COMPLETE within this ingest (one per 100 ms
        // step, the first once the full 400 ms has landed): each is
        // evaluated the moment its last sample is ingested, while the ring
        // still holds its samples.
        let firstTotal = totals[0]
        let endTotal = firstTotal + Int64(frameCount)
        var windows: [Int64] = []
        var window = (firstTotal / Int64(stepLen) + 1) * Int64(stepLen)
        while window <= endTotal {
            if window >= Int64(blockLen) { windows.append(window) }
            window += Int64(stepLen)
        }
        var channelMeans = [[Double]](
            repeating: [Double](repeating: 0.0, count: channelCount),
            count: windows.count,
        )
        for ch in 0..<channelCount {
            let filter = filters[ch]
            // The ring is a Swift array (a value type): copy it out,
            // append the new samples, and write the full ring back after
            // the ingest (processWindow reads the local copy mid-ingest,
            // which is the same ring state the channel has at that point).
            var channelRing = ring[ch]
            var total = totals[ch]
            var index = offset + ch
            var windowPointer = 0
            for frame in 0..<frameCount {
                let s = samples[index]
                index += channelCount
                let magnitude = abs(s)
                if magnitude > truePeak { truePeak = magnitude }
                channelRing[Int(total % Int64(blockLen))] = s
                total += 1
                if windowPointer < windows.count && total == windows[windowPointer] {
                    channelMeans[windowPointer][ch] = processWindow(
                        filter, ringChannel: channelRing, total: total,
                    )
                    windowPointer += 1
                }
            }
            ring[ch] = channelRing
            totals[ch] = total
        }
        for means in channelMeans {
            windowMeans.append(means.reduce(0, +) / Double(means.count))
        }
    }

    /**
     * The integrated-loudness result for everything ingested so far.
     * [LoudnessResult.lufs] is nil when no block survives the gates
     * (silence, or a stream shorter than one window) - "no loudness",
     * not zero.
     */
    public func result() -> LoudnessResult {
        guard primed else { return LoudnessResult(lufs: nil, truePeakDbTp: nil) }
        let absolutelyGated = windowMeans.filter { $0 > LoudnessGate.absoluteGate }
        var lufs: Double?
        if !absolutelyGated.isEmpty {
            let ungated = absolutelyGated.reduce(0, +) / Double(absolutelyGated.count)
            let relativeGate = ungated / LoudnessGate.relativeGate
            let relativelyGated = absolutelyGated.filter { $0 >= relativeGate }
            if !relativelyGated.isEmpty {
                let mean = relativelyGated.reduce(0, +) / Double(relativelyGated.count)
                lufs = LoudnessGate.integrationOffset + 10.0 * log(mean) / LoudnessGate.ln10
            }
        }
        let truePeakDbTp = truePeak > 0.0 ? 20.0 * log(truePeak) / LoudnessGate.ln10 : nil
        return LoudnessResult(lufs: lufs, truePeakDbTp: truePeakDbTp)
    }

    private func processWindow(
        _ filter: KWeightFilter, ringChannel: [Double], total: Int64,
    ) -> Double {
        var slot = Int((total - Int64(blockLen)) % Int64(blockLen))
        var accumulator = 0.0
        for _ in 0..<blockLen {
            let weighted = filter.process(ringChannel[slot])
            accumulator += weighted * weighted
            slot += 1
            if slot == blockLen { slot = 0 }
        }
        return accumulator / Double(blockLen)
    }
}

// ---------------------------------------------------------------------------
// Results and the gain math
// ---------------------------------------------------------------------------

/**
 * A loudness measurement. [lufs] nil means the audio carries NO loudness
 * (it is silent, or shorter than one 400 ms window) - a gain must never be
 * computed from such a reading. [truePeakDbTp] nil means no peak sample
 * was seen (the same silence case).
 */
public struct LoudnessResult: Equatable, Sendable {
    public let lufs: Double?
    public let truePeakDbTp: Double?

    public init(lufs: Double?, truePeakDbTp: Double?) {
        self.lufs = lufs
        self.truePeakDbTp = truePeakDbTp
    }

    public var hasLoudness: Bool { lufs != nil }
}

/**
 * A measured loudness sample as the player service consumes it (the
 * per-payload snapshot kept by the Store). [lufs] / [truePeakDbTp] follow
 * [LoudnessResult]'s nulls.
 */
public struct LoudnessSample: Equatable, Sendable {
    public let lufs: Double?
    public let truePeakDbTp: Double?
    public let fingerprint: String
    public let measuredAt: Int64

    public init(lufs: Double?, truePeakDbTp: Double?, fingerprint: String, measuredAt: Int64) {
        self.lufs = lufs
        self.truePeakDbTp = truePeakDbTp
        self.fingerprint = fingerprint
        self.measuredAt = measuredAt
    }
}

/**
 * A measured loudness row joined to its content row: the Store's loudness
 * table (the Android app's entry_loudness entity, keyed by the entry's id).
 */
public struct EntryLoudness: Codable, Equatable, Sendable {
    public let entryId: Int
    public let lufs: Double?
    public let truePeakDbTp: Double?
    public let fingerprint: String
    public let measuredAt: Int64

    public init(entryId: Int, lufs: Double?, truePeakDbTp: Double?, fingerprint: String, measuredAt: Int64) {
        self.entryId = entryId
        self.lufs = lufs
        self.truePeakDbTp = truePeakDbTp
        self.fingerprint = fingerprint
        self.measuredAt = measuredAt
    }
}

/**
 * The audio level's measurement status for the settings pane's status row:
 * [measured] of [total] audio entries carry a stored measurement.
 */
public struct LoudnessStatus: Equatable, Sendable {
    public let measured: Int
    public let total: Int

    public init(measured: Int, total: Int) {
        self.measured = measured
        self.total = total
    }

    /** The row's copy: "N of M songs measured" ("1 of 1 song" when one). */
    public var text: String {
        let song = total == 1 ? "song" : "songs"
        return "\(measured) of \(total) \(song) measured"
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
public let PEAK_CEILING_DBTP = -1.0

/**
 * The gain (dB) that brings a measured track's integrated loudness to
 * [targetLufs], capped by the track's peak headroom:
 * `min(target - measured, ceiling - peak)`. A track with no loudness
 * ([measuredLufs] nil) gets NO gain (0.0) - a silence reading is not a
 * quiet reading.
 */
public func gainFor(
    measuredLufs: Double?,
    truePeakDbTp: Double?,
    targetLufs: Double,
    peakCeilingDbTp: Double = PEAK_CEILING_DBTP,
) -> Double {
    guard let measuredLufs else { return 0.0 }
    let gap = targetLufs - measuredLufs
    let headroom: Double = truePeakDbTp.map { peakCeilingDbTp - $0 } ?? .infinity
    return min(gap, headroom)
}

/**
 * The audio level setting's convenience: no gain while the mode is off, or
 * while the track is unmeasured or carries no loudness; otherwise the
 * measure-to-target gain capped by the track's peak headroom.
 */
public func gainFor(mode: AudioLevelMode, targetLufs: Double, sample: LoudnessSample?) -> Double {
    if mode == .off || sample == nil || sample!.lufs == nil {
        return 0.0
    }
    return gainFor(measuredLufs: sample!.lufs, truePeakDbTp: sample!.truePeakDbTp, targetLufs: targetLufs)
}

/**
 * The playback limiter's soft-clip knee, in linear amplitude (-1 dBTP).
 * Boosted tracks are limited there so the applied gain never hard-clips.
 */
public let LIMITER_KNEE_LINEAR = 0.8912509381337453 // 10 ^ (-1/20)

/**
 * The soft clip: identity up to [knee], and above it the magnitude is
 * compressed toward the 1.0 asymptote (a tangent-matched tanh bend), so a
 * boosted track settles just under full scale instead of clipping.
 */
public func softClip(_ x: Double, knee: Double = LIMITER_KNEE_LINEAR) -> Double {
    let magnitude = abs(x)
    if magnitude <= knee { return x }
    let sign: Double = x < 0 ? -1.0 : 1.0
    let normalized = (magnitude - knee) / (1.0 - knee)
    return sign * (knee + (1.0 - knee) * tanh(normalized))
}
