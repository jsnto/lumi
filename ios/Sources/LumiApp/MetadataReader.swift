// Song file metadata (the iOS counterpart of the Android app's
// MediaMetadataRetriever read): read best-effort from the file itself via
// AVFoundation's AVAsset metadata (title / artist / album + duration).
//
// The read runs off the main thread with a 5 s time limit (the Android
// app's METADATA_READ_TIMEOUT_MS), and any failure (an unreadable file, a
// revoked grant) settles to NO metadata rather than an error - the profile
// page shows its placeholders instead. A null field is metadata the file
// does not carry (or could not be read).

import AVFoundation
import Foundation

public struct SongMetadata: Hashable, Sendable {
    public var artist: String? = nil
    public var album: String? = nil
    public var durationMs: Int64? = nil

    public init(artist: String? = nil, album: String? = nil, durationMs: Int64? = nil) {
        self.artist = artist
        self.album = album
        self.durationMs = durationMs
    }
}

/// A duration in the profile's "m:ss" form (the Android formatDuration).
public func formatDuration(_ ms: Int64) -> String {
    let totalSeconds = max(0, ms / 1000)
    return "\(totalSeconds / 60):\(String(format: "%02d", totalSeconds % 60))"
}

public enum MetadataReader {
    /// How long a song's metadata read may take before it gives up
    /// (the Android app's 5 s timeout).
    public static let readTimeoutSeconds: UInt64 = 5

    /// The best-effort metadata read for [url]: async (off the main
    /// thread), time-limited, settling to nil on any failure.
    public static func read(url: URL) async -> SongMetadata? {
        final class Box: @unchecked Sendable {
            let lock = NSLock()
            var value: SongMetadata? = nil
            var done = false
            func set(_ v: SongMetadata?) {
                lock.lock()
                defer { lock.unlock() }
                value = v
                done = true
            }
            func snapshot() -> (value: SongMetadata?, done: Bool) {
                lock.lock()
                defer { lock.unlock() }
                return (value, done)
            }
        }
        let box = Box()
        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                let metadata = await Self.loadMetadata(url: url)
                box.set(metadata)
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: readTimeoutSeconds * 1_000_000_000)
            }
            // Settle on the FIRST of: the read finishing or the timeout.
            // Returning here cancels the group's remaining task (the slow
            // read keeps running in the background, harmlessly - it is
            // best-effort and its late result is dropped).
            _ = await group.next()
        }
        return box.snapshot().value
    }

    private static func loadMetadata(url: URL) async -> SongMetadata? {
        let asset = AVURLAsset(url: url)
        do {
            let common = try await asset.load(.commonMetadata)
            func string(for key: AVMetadataKey) -> String? {
                guard let item = common.first(where: { $0.commonKey == key }),
                    let value = try? await item.load(.stringValue)
                else { return nil }
                return value.isEmpty ? nil : value
            }
            let duration = try await asset.load(.duration)
            let seconds = duration.seconds.isFinite ? duration.seconds : 0
            return SongMetadata(
                artist: string(for: .commonKeyArtist),
                album: string(for: .commonKeyAlbumTitle),
                durationMs: Int64(seconds * 1000),
            )
        } catch {
            return nil // any failure settles to no metadata (best-effort)
        }
    }
}
