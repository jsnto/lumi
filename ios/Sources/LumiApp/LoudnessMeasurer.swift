// The loudness measurement read (the iOS counterpart of the Android
// app's data/LoudnessMeasurer): decodes the audio at [url] at DECODE
// SPEED through an AVAssetReader - no output, the decoded samples feed
// the LumiCore LoudnessMeter and nothing is rendered - and returns the
// meter's reading once the whole stream has been decoded and drained.
//
// The #60 fix's platform adaptation, stated out: the Android reader had
// to be driven on the player's application thread (the main looper)
// because ExoPlayer pins its API there; iOS's AVFoundation is
// thread-safe and has no such pin, so the read runs on the caller's
// background task (the app model's single measurement slot) and reports
// the result back on the main actor. The platform sink contract is
// mirrored in the reader's own output settings: the output requests
// LINEAR PCM at the file's OWN sample rate and channel count, so the
// meter sees decoded full-scale samples (the reader decodes first, like
// the platform sink's "compressed audio is only supported after decode"
// - it never consumes compressed frames directly), and the meter's
// configure takes that same sample rate + channel count (the meter's
// window schedule and K-weighting coefficients follow the stream, not a
// device default).
//
// The read is time-limited: a stalled decode settles into a failure at
// [measureTimeout] (checked between buffers - the Android read's
// withTimeout), so an unresponsive file can never hold the app's single
// measurement slot forever.

import AVFoundation
import Foundation
import LumiCore

/// The loudness measurement's failure (a file with no audio track, a
/// decode that cannot start, a decode that fails or stalls past the
/// timeout).
public enum LoudnessMeasurementError: Error, Equatable {
    case noAudioTrack
    case decodeFailed
    case timedOut
    case cancelled
}

public final class LoudnessMeasurer {
    /// The per-entry measurement timeout. Decode speed is FASTER than
    /// real time even for long files, so the bound is a stall guard, not
    /// a length guard (the Android app's MEASURE_TIMEOUT_MS = 120 s).
    public static let measureTimeout: TimeInterval = 120

    public init() {}

    /**
     * Measures the integrated loudness of [url]'s audio (the meter's
     * reading over the WHOLE stream). The read runs on the cooperative
     * pool (this is a nonisolated async function - it does NOT run on the
     * main actor) and blocks a pool thread for the decode's duration
     * (seconds for a real song), which the app model's single
     * measurement slot keeps to one at a time.
     */
    public func measure(url: URL) async throws -> LoudnessResult {
        // The asset's load is async (the modern AVFoundation API); the
        // decode below is a synchronous pull loop.
        let asset = AVURLAsset(url: url)
        let tracks: [AVAssetTrack]
        do {
            tracks = try await asset.loadTracks(withMediaType: .audio)
        } catch {
            throw LoudnessMeasurementError.decodeFailed
        }
        guard let track = tracks.first else {
            throw LoudnessMeasurementError.noAudioTrack
        }
        // The file's OWN stream parameters (the sink contract's mirror):
        // the reader's output keeps them, and the meter is configured
        // for them.
        let sampleRate = Int(track.nominalSampleRate)
        let channelCount = Int(track.channelCount)
        guard sampleRate > 0, channelCount > 0 else {
            throw LoudnessMeasurementError.decodeFailed
        }

        let reader: AVAssetReader
        do {
            reader = try AVAssetReader(asset: asset)
        } catch {
            throw LoudnessMeasurementError.decodeFailed
        }
        let outputSettings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatLinearPCM),
            AVSampleRateKey: sampleRate,
            AVNumberOfChannelsKey: channelCount,
            AVLinearPCMBitDepthKey: 32,
            AVLinearPCMIsFloatKey: true,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false,
        ]
        let output: AVAssetReaderTrackOutput
        do {
            output = try AVAssetReaderTrackOutput(track: track, settings: outputSettings)
        } catch {
            throw LoudnessMeasurementError.decodeFailed
        }
        guard reader.canSupport(output) else {
            throw LoudnessMeasurementError.decodeFailed
        }
        reader.add(output)

        let meter = LoudnessMeter()
        meter.configure(sampleRate: sampleRate, channelCount: channelCount)

        do {
            try reader.startReading()
        } catch {
            throw LoudnessMeasurementError.decodeFailed
        }

        let deadline = Date().addingTimeInterval(Self.measureTimeout)
        defer {
            if reader.status == .reading { reader.cancelReading() }
            try? reader.stopReading()
        }

        // The decode-speed pull loop: drain the reader until it settles.
        while reader.status == .reading {
            guard !Task.isCancelled else {
                reader.cancelReading()
                throw LoudnessMeasurementError.cancelled
            }
            guard Date() <= deadline else {
                reader.cancelReading()
                throw LoudnessMeasurementError.timedOut
            }
            guard output.isReadyForMoreData, let sampleBuffer = output.copyNextSampleBuffer() else {
                // Not ready (a stalled decode, or the output drained
                // ahead of the decoder): yield the cooperative pool
                // thread instead of spinning on it.
                await Task.yield()
                continue
            }
            guard let blockBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) else { continue }
            // The samples are interleaved float32 (the output settings'
            // contract): feed the meter frame by frame. The read goes
            // through the pointer-agnostic per-sample load (the data
            // accessor hands a raw pointer: bindMemory's no-capacity
            // overload does not exist on that shape).
            var doubles = [Double](repeating: 0.0, count: blockBuffer.dataLength / MemoryLayout<Float>.size)
            let floatCount = blockBuffer.withDataBytes { raw -> Int in
                let count = blockBuffer.dataLength / MemoryLayout<Float>.size
                for i in 0..<count {
                    doubles[i] = Double(raw.load(fromByteOffset: i * MemoryLayout<Float>.size, as: Float.self))
                }
                return count
            }
            guard floatCount > 0 else { continue }
            meter.processInterleaved(doubles, offset: 0, frameCount: floatCount / channelCount)
        }

        switch reader.status {
        case .completed:
            break
        case .cancelled:
            if Task.isCancelled { throw LoudnessMeasurementError.cancelled }
            throw LoudnessMeasurementError.timedOut
        default:
            throw LoudnessMeasurementError.decodeFailed
        }

        // The meter's reading, captured (the meter is local - nothing
        // below could still touch it).
        return meter.result()
    }
}
