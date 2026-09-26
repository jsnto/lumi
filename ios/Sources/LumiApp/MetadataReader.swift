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
            var waiter: CheckedContinuation<SongMetadata?, Never>? = nil
            func set(_ v: SongMetadata?) {
                lock.lock()
                defer { lock.unlock() }
                if done { return }
                value = v
                done = true
                waiter?.resume(returning: v)
            }
            func settleToCurrent() {
                lock.lock()
                defer { lock.unlock() }
                if done { return }
                done = true
                waiter?.resume(returning: value)
            }
            func awaitSettled() async -> SongMetadata? {
                await withCheckedContinuation { continuation in
                    lock.lock()
                    defer { lock.unlock() }
                    if done {
                        continuation.resume(returning: value)
                    } else {
                        waiter = continuation
                    }
                }
            }
        }
        let box = Box()
        // The read runs UNSTRUCTURED, so a load that does not honour
        // cancellation cannot hold the caller past the deadline: at the
        // timeout the caller settles to whatever has landed so far and
        // drops the late result (the Android reader's timeout shape - the
        // read keeps running in the background and its late value is no
        // longer consumed). A withTaskGroup would not do this: returning
        // from its body cancels the remaining children but still awaits
        // their completion.
        let load = Task.detached {
            box.set(await Self.loadMetadata(url: url))
        }
        let deadline = Task {
            try? await Task.sleep(nanoseconds: readTimeoutSeconds * 1_000_000_000)
            load.cancel()
            box.settleToCurrent()
        }
        let result = await box.awaitSettled()
        deadline.cancel()
        // The winner already resumed the continuation: the loser's settle
        // is a locked no-op (done == true). A load that outlives the
        // timeout settles late and is dropped.
        return result
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
