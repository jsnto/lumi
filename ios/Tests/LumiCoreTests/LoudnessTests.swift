// The test-vector contract of the on-device loudness meter: the
// LoudnessMeter must match the reference R128 implementation on the
// pinned fixtures (sines rendered at 48 kHz, 30 s) within 0.05 LU. The
// reference values are the discovery report's fixture table (one
// deliberately rendered per frequency / level):
//
// - 1 kHz sine, 0 dBFS peak:   -10.92 LUFS, peak 0.00 dB,   gain -5.08 dB
// - 1 kHz sine, -20 dBFS:      -30.92 LUFS, peak -20.00 dB, gain +14.92 dB
// - 440 Hz sine, 0 dBFS:       -11.61 LUFS,                 gain -4.39 dB
// - 100 Hz sine, 0 dBFS:       -12.75 LUFS,                 gain -3.25 dB
// - 10 kHz sine, 0 dBFS:       -7.58 LUFS,                  gain -8.42 dB
// - 10 s 0 dBFS + 20 s -20 dBFS @ 1 kHz: -10.99 LUFS, gain -5.01 dB
// - Silence: no loudness.
//
// and the per-track gain must be the level difference capped by the
// track's true-peak headroom (the -1 dBTP ceiling). The iOS port of the
// Android app's data/LoudnessTest.kt, vector for vector.

import Foundation
import Testing
@testable import LumiCore

private let fs = 48_000
private let seconds30 = 30 * fs

private func sine(_ hz: Double, levelDb: Double = 0.0, length: Int = seconds30) -> [Double] {
    let amplitude = levelDb == 0.0 ? 1.0 : pow(10.0, levelDb / 20.0)
    return (0..<length).map { i in
        amplitude * sin(2.0 * .pi * hz * Double(i) / Double(fs))
    }
}

private func meter(_ samples: [Double], rate: Int = fs, channels: Int = 1) -> LoudnessResult {
    let meter = LoudnessMeter()
    meter.configure(sampleRate: rate, channelCount: channels)
    meter.processInterleaved(samples, offset: 0, frameCount: samples.count / channels)
    return meter.result()
}

// MARK: - the fixture table

@Test func fixture1khzFullScale_matchesReferenceWithinFiftyMilLU() {
    let result = meter(sine(1_000.0))

    #expect(result.lufs == nil ? false : abs(result.lufs! - (-10.92)) <= 0.05, "lufs \(result.lufs ?? .nan), want -10.92 +-0.05")
    #expect(result.truePeakDbTp == nil ? false : abs(result.truePeakDbTp! - 0.00) <= 0.05, "peak \(result.truePeakDbTp ?? .nan), want 0.00 +-0.05")
    // The per-track gain at the -16 LUFS default target: the level
    // difference (the headroom cap does not bind: -1 - 0 = -1 > -5.08).
    #expect(abs(gainFor(measuredLufs: result.lufs, truePeakDbTp: result.truePeakDbTp, targetLufs: -16.0) - (-5.08)) <= 0.05)
}

@Test func fixture1khzMinus20Db_matchesReferenceWithinFiftyMilLU() {
    let result = meter(sine(1_000.0, levelDb: -20.0))

    #expect(abs(result.lufs! - (-30.92)) <= 0.05, "lufs \(result.lufs ?? .nan), want -30.92 +-0.05")
    #expect(abs(result.truePeakDbTp! - (-20.00)) <= 0.05, "peak \(result.truePeakDbTp ?? .nan), want -20.00 +-0.05")
    // The gain is the level difference up to the ceiling (the ceiling
    // does not bind here: -1 - (-20) = 19 > 14.92).
    #expect(abs(gainFor(measuredLufs: result.lufs, truePeakDbTp: result.truePeakDbTp, targetLufs: -16.0) - 14.92) <= 0.05)
}

@Test func fixture440HzFullScale_matchesReferenceWithinFiftyMilLU() {
    let result = meter(sine(440.0))

    #expect(abs(result.lufs! - (-11.61)) <= 0.05, "lufs \(result.lufs ?? .nan), want -11.61 +-0.05")
    #expect(abs(gainFor(measuredLufs: result.lufs, truePeakDbTp: result.truePeakDbTp, targetLufs: -16.0) - (-4.39)) <= 0.05)
}

@Test func fixture100HzFullScale_matchesReferenceWithinFiftyMilLU() {
    let result = meter(sine(100.0))

    #expect(abs(result.lufs! - (-12.75)) <= 0.05, "lufs \(result.lufs ?? .nan), want -12.75 +-0.05")
    #expect(abs(gainFor(measuredLufs: result.lufs, truePeakDbTp: result.truePeakDbTp, targetLufs: -16.0) - (-3.25)) <= 0.05)
}

@Test func fixture10kHzFullScale_matchesReferenceWithinFiftyMilLU() {
    let result = meter(sine(10_000.0))

    #expect(abs(result.lufs! - (-7.58)) <= 0.05, "lufs \(result.lufs ?? .nan), want -7.58 +-0.05")
    #expect(abs(gainFor(measuredLufs: result.lufs, truePeakDbTp: result.truePeakDbTp, targetLufs: -16.0) - (-8.42)) <= 0.05)
}

@Test func fixtureMixed10sFullThen20sMinus20_matchesReferenceWithinFiftyMilLU() {
    var mixed = [Double](repeating: 0.0, count: seconds30)
    let breakPoint = 10 * fs
    for i in 0..<breakPoint {
        mixed[i] = sin(2.0 * .pi * 1_000.0 * Double(i) / Double(fs))
    }
    for i in breakPoint..<mixed.count {
        mixed[i] = 0.1 * sin(2.0 * .pi * 1_000.0 * Double(i) / Double(fs))
    }
    let result = meter(mixed)

    #expect(abs(result.lufs! - (-10.99)) <= 0.05, "lufs \(result.lufs ?? .nan), want -10.99 +-0.05")
    #expect(abs(gainFor(measuredLufs: result.lufs, truePeakDbTp: result.truePeakDbTp, targetLufs: -16.0) - (-5.01)) <= 0.05)
}

@Test func silence_reportsNoLoudnessNeverAFakeReading() {
    let result = meter([Double](repeating: 0.0, count: seconds30))

    #expect(result.lufs == nil, "silence has no loudness")
    #expect(result.truePeakDbTp == nil, "silence has no true peak")
    // And no gain is ever computed from it.
    #expect(gainFor(measuredLufs: result.lufs, truePeakDbTp: result.truePeakDbTp, targetLufs: -16.0) == 0.0)
}

// MARK: - the meter's invariants

@Test func levelShiftOf20Db_shiftsLoudnessByExactly20Lu() {
    let full = meter(sine(1_000.0)).lufs!
    let quiet = meter(sine(1_000.0, levelDb: -20.0)).lufs!

    #expect(abs(full - quiet - 20.0) <= 0.05, "full \(full), quiet \(quiet)")
}

@Test func streamShorterThanOneWindow_reportsNoLoudness() {
    let result = meter(sine(1_000.0, length: 200)) // 4.1 ms

    #expect(result.lufs == nil)
}

@Test func oneFullWindow_reportsLoudnessTheMomentItsLastSampleLands() {
    // Exactly 400 ms at 48 kHz (19 200 samples): one complete window,
    // and the window is evaluated once its full 400 ms has been
    // consumed - a stream of 400-499 ms carries it.
    let result = meter(sine(1_000.0, length: fs * 4 / 10))

    #expect(result.hasLoudness, "a 400 ms stream carries one complete window")
    #expect(abs(result.lufs! - (-10.92)) <= 0.05, "lufs \(result.lufs ?? .nan), want -10.92 +-0.05")
}

@Test func oneSampleShortOfOneWindow_reportsNoLoudness() {
    let result = meter(sine(1_000.0, length: fs * 4 / 10 - 1)) // 399.98 ms

    #expect(result.lufs == nil, "the window is not complete until its 400 ms is ingested")
}

@Test func reset_restartsTheMeterOnTheSameConfiguration() {
    let oneSecond = (0..<fs).map { sin(2.0 * .pi * 1_000.0 * Double($0) / Double(fs)) }
    let meter = LoudnessMeter()
    meter.configure(sampleRate: fs, channelCount: 1)
    meter.processInterleaved(oneSecond, offset: 0, frameCount: fs)
    let first = meter.result()
    #expect(first.hasLoudness)

    meter.reset()
    let empty = meter.result()
    #expect(empty.lufs == nil, "a reset clears the block statistics")

    meter.processInterleaved(oneSecond, offset: 0, frameCount: fs)
    let second = meter.result()
    #expect(first == second, "a reset restarts the statistics deterministically")
}

@Test func reconfigureToADifferentStream_resetsTheStatistics() {
    let meter = LoudnessMeter()
    meter.configure(sampleRate: fs, channelCount: 1)
    meter.processInterleaved(sine(1_000.0, length: 2 * fs), offset: 0, frameCount: 2 * fs)
    #expect(meter.result().hasLoudness)

    meter.configure(sampleRate: 44_100, channelCount: 1) // a new stream
    let rightAfter = meter.result()
    #expect(rightAfter.lufs == nil, "a reconfigure starts the new stream fresh")
}

@Test func stereoStream_measuresThePerChannelAverage() {
    // Both channels carry the same 1 kHz sine: the per-channel block
    // means are equal, so the integrated loudness equals the mono
    // reading of one channel.
    let mono = (0..<seconds30).map { sin(2.0 * .pi * 1_000.0 * Double($0) / Double(fs)) }
    var stereo = [Double](repeating: 0.0, count: seconds30 * 2)
    for i in 0..<seconds30 {
        stereo[i * 2] = mono[i]
        stereo[i * 2 + 1] = mono[i]
    }
    let monoResult = meter(mono, channels: 1)
    let stereoResult = meter(stereo, channels: 2)

    #expect(abs(monoResult.lufs! - stereoResult.lufs!) <= 0.05)
}

@Test func aToneInOnlyOneChannel_measuresAboutThreeLuBelowTheDuplicatedTone() {
    // The block value is the LINEAR channel average of the per-channel
    // block mean squares: a tone duplicated on both channels keeps the
    // mono reading, while the same tone in only one of the two channels
    // halves the mean square - 10 * log10(2) = 3.01 LU lower.
    let tone = (0..<seconds30).map { sin(2.0 * .pi * 1_000.0 * Double($0) / Double(fs)) }
    var duplicated = [Double](repeating: 0.0, count: seconds30 * 2)
    var hardPanned = [Double](repeating: 0.0, count: seconds30 * 2)
    for i in 0..<seconds30 {
        duplicated[i * 2] = tone[i]
        duplicated[i * 2 + 1] = tone[i]
        hardPanned[i * 2] = tone[i]
        // The right channel stays silent.
    }
    let duplicatedLufs = meter(duplicated, channels: 2).lufs!
    let hardPannedLufs = meter(hardPanned, channels: 2).lufs!

    #expect(abs(duplicatedLufs - (-10.92)) <= 0.05, "the duplicated tone keeps the mono reading: \(duplicatedLufs)")
    #expect(abs(hardPannedLufs - (-13.93)) <= 0.05, "the single-channel tone lands at the combined-gate value: \(hardPannedLufs)")
    #expect(abs(duplicatedLufs - hardPannedLufs - 3.0103) <= 0.05)
}

@Test func chunkedFeeding_acrossManyCalls_matchesTheWholeStreamReading() {
    // The measurement sink feeds the meter in small buffers, so the
    // window schedule must hold across calls, not just within one.
    let oneSecond = (0..<fs).map { sin(2.0 * .pi * 1_000.0 * Double($0) / Double(fs)) }
    let whole = meter(oneSecond)

    let chunked = LoudnessMeter()
    chunked.configure(sampleRate: fs, channelCount: 1)
    var offset = 0
    while offset < oneSecond.count {
        let chunk = min(7_000, oneSecond.count - offset)
        chunked.processInterleaved(oneSecond, offset: offset, frameCount: chunk)
        offset += chunk
    }

    #expect(whole.lufs! == chunked.result().lufs!)
}

@Test func otherSampleRate_measuresAFiniteLoudness() {
    // The 44.1 kHz coefficients differ slightly from 48 kHz; the
    // contract pins the 48 kHz vectors, so here the 1 kHz sine at
    // 44.1 kHz must simply measure a finite loudness near the 48 kHz
    // reference.
    let sampleRate = 44_100
    let samples = (0..<(30 * sampleRate)).map { sin(2.0 * .pi * 1_000.0 * Double($0) / Double(sampleRate)) }
    let result = meter(samples, rate: sampleRate)

    #expect(result.hasLoudness)
    #expect(abs(result.lufs! - (-10.92)) <= 0.5, "lufs \(result.lufs ?? .nan), want near -10.92")
}

// MARK: - the gain math

@Test func gain_isTheLevelDifference_whenHeadroomAllows() {
    // Gap 3 dB, headroom -1 - (-10) = 9 dB: the gap wins.
    #expect(gainFor(measuredLufs: -10.0, truePeakDbTp: 0.0, targetLufs: -15.0) == -5.0)
    #expect(gainFor(measuredLufs: -19.0, truePeakDbTp: -10.0, targetLufs: -16.0) == 3.0)
}

@Test func gain_isCappedByTheTruePeakHeadroom() {
    // Measured -15.0, peak 0.0, target -16: the gap is -1 dB, and the
    // headroom cap (-1 - 0 = -1) is exactly equal - it must never push
    // the peak past the ceiling.
    #expect(gainFor(measuredLufs: -15.0, truePeakDbTp: 0.0, targetLufs: -16.0) == -1.0)
    // A louder track with no headroom: the cap (not the gap) binds.
    #expect(gainFor(measuredLufs: -16.5, truePeakDbTp: 0.0, targetLufs: -14.0) == -1.0)
}

@Test func gain_withNoPeakReading_isTheUnboundedGap() {
    #expect(gainFor(measuredLufs: -16.5, truePeakDbTp: nil, targetLufs: -14.0) == 2.5)
}

@Test func gain_forSilence_isZero() {
    #expect(gainFor(measuredLufs: nil, truePeakDbTp: nil, targetLufs: -16.0) == 0.0)
}

@Test func gainForTheAudioLevelSetting_offOrUnmeasuredMeansNoGain() {
    #expect(gainFor(mode: .off, targetLufs: -16.0, sample: LoudnessSample(lufs: -10.0, truePeakDbTp: 0.0, fingerprint: "fp", measuredAt: 0)) == 0.0)
    #expect(gainFor(mode: .normalize, targetLufs: -16.0, sample: nil) == 0.0)
    #expect(gainFor(mode: .normalize, targetLufs: -16.0, sample: LoudnessSample(lufs: nil, truePeakDbTp: nil, fingerprint: "fp", measuredAt: 0)) == 0.0)
    #expect(gainFor(mode: .normalize, targetLufs: -15.0, sample: LoudnessSample(lufs: -10.0, truePeakDbTp: 0.0, fingerprint: "fp", measuredAt: 0)) == -5.0)
}

// MARK: - the soft clip

@Test func softClip_isIdentityBelowTheKnee() {
    for v in [-0.5, -0.1, 0.0, 0.3, 0.8] {
        #expect(softClip(v) == v, "softClip(\(v))")
    }
    #expect(softClip(LIMITER_KNEE_LINEAR) == LIMITER_KNEE_LINEAR)
    #expect(softClip(-LIMITER_KNEE_LINEAR) == -LIMITER_KNEE_LINEAR)
}

@Test func softClip_compressesAboveTheKneeAndBoundedBelowFullScale() {
    let knee = LIMITER_KNEE_LINEAR

    let atKneePlusEps = softClip(knee + 1e-6)
    // The bend is tangent-matched: at the knee the curve leaves at
    // slope 1, so infinitesimally above the knee the output still
    // tracks the input - the compression shows up further out.
    #expect(atKneePlusEps > knee, "above the knee the output still moves up")

    let above = softClip(2.0 * knee)
    #expect(above < 2.0 * knee, "compressed below the 1:1 line")
    #expect(above > knee, "but still above the knee")

    for input in [1.0, 1.5, 2.0, 10.0] {
        let out = softClip(input)
        #expect(out <= 1.0, "never exceeds full scale (no hard clip)")
        #expect(out > knee, "above the knee")
    }
    // Monotone, and the asymptote is 1.0: a huge input settles AT the
    // asymptote (full scale, which a float sample can hold - not a
    // clip), never past it.
    #expect(softClip(2.0) > softClip(1.0))
    #expect(abs(softClip(1_000.0) - 1.0) <= 1e-12)
    #expect(abs(softClip(-1_000.0) - (-1.0)) <= 1e-12)
    // The negative side mirrors the positive one.
    #expect(softClip(-1.5) == -softClip(1.5))
}

// MARK: - the audio level setting's parsing

@Test func audioLevelMode_parsesTheStoredChoiceTolerantly() {
    #expect(AudioLevelMode.fromStorage(nil) == .off)
    #expect(AudioLevelMode.fromStorage("") == .off)
    #expect(AudioLevelMode.fromStorage("garbage") == .off)
    #expect(AudioLevelMode.fromStorage("off") == .off)
    #expect(AudioLevelMode.fromStorage("OFF") == .off)
    #expect(AudioLevelMode.fromStorage("  off  ") == .off)
    #expect(AudioLevelMode.fromStorage("normalize") == .normalize)
    #expect(AudioLevelMode.fromStorage("Normalize") == .normalize)
}

@Test func audioLevelMode_roundTripsThroughTheStoredValue() {
    for mode in AudioLevelMode.allCases {
        #expect(AudioLevelMode.fromStorage(mode.storageValue) == mode)
    }
}

@Test func audioLevelTarget_parsesTheStoredTargetTolerantly() {
    #expect(audioLevelTargetLufsFromStored(nil) == -16.0)
    #expect(audioLevelTargetLufsFromStored("") == -16.0)
    #expect(audioLevelTargetLufsFromStored("garbage") == -16.0)
    // A value outside the ladder falls back to the default (the ladder
    // is the picker's surface - a stored value the picker never wrote is
    // invalid).
    #expect(audioLevelTargetLufsFromStored("-15") == -16.0)
    #expect(audioLevelTargetLufsFromStored("0") == -16.0)
    #expect(audioLevelTargetLufsFromStored("-18") == -18.0)
    #expect(audioLevelTargetLufsFromStored("-16") == -16.0)
    #expect(audioLevelTargetLufsFromStored("-14") == -14.0)
    #expect(audioLevelTargetLufsFromStored("  -14  ") == -14.0)
}

@Test func audioLevelTarget_matchesTheLadderExactly() {
    #expect(AudioLevelSettings.targets == [-18.0, -16.0, -14.0])
    #expect(AudioLevelSettings.defaultTargetLufs == -16.0)
}
