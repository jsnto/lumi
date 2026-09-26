// Playback position remembering (per tag, LRU 3) - a port of the Android
// app's com.lumi.player.data.PlaybackPosition.

import Foundation

/// Maximum number of tags whose last played position is remembered (LRU).
public let maxRememberedTags = 3

/// How a tag's content list should start.
public enum PositionRestore: Equatable, Sendable {
    /// Start from the first entry at the beginning.
    case startFresh
    /// Start at [entryIndex] and seek to [positionMs] within that entry.
    case seekTo(entryIndex: Int, positionMs: Int64)
}

/// Decides how to start a tag's list from its saved [saved] position.
///
/// A saved row only applies when it was saved against the same linked list
/// (a changed list - e.g. a parent swapping the audio file - resets the tag
/// to the beginning) and the saved entry still exists in the list at that
/// index. A position of zero is the beginning and needs no seek.
public func decideRestore(
    saved: TagPosition?,
    queueFingerprint: String,
    queueSize: Int,
) -> PositionRestore {
    guard let saved else { return .startFresh }
    if saved.queueFingerprint != queueFingerprint { return .startFresh }
    if saved.entryIndex < 0 || saved.entryIndex >= queueSize { return .startFresh }
    return saved.positionMs > 0
        ? .seekTo(entryIndex: saved.entryIndex, positionMs: saved.positionMs)
        : .startFresh
}

/// Stable identifier of a tag's linked content list. Changes when the
/// entries or their order change, so a position saved against an older list
/// is recognized as stale.
///
/// (The Android app stores the hash of this string; equality is what the
/// restore decision uses, so the string itself is the fingerprint here.)
public func fingerprintOf(_ entries: [EntrySpec]) -> String {
    entries.map { "\($0.type):\($0.payload)" }.joined(separator: "|")
}

/// LRU eviction: the UIDs to delete so that, once [incoming] is stored, at
/// most [capacity] rows remain. The least-recently played tag (oldest
/// [TagPosition.lastPlayedAt]) is evicted first. Storing a row for a tag
/// that is already remembered never evicts.
public func lruEvictions(
    existing: [TagPosition],
    incoming: TagPosition,
    capacity: Int = maxRememberedTags,
) -> [String] {
    let others = existing.filter { $0.tagUid != incoming.tagUid }
    let toFree = others.count - (capacity - 1)
    if toFree <= 0 { return [] }
    return others.sorted { $0.lastPlayedAt < $1.lastPlayedAt }.prefix(toFree).map { $0.tagUid }
}
