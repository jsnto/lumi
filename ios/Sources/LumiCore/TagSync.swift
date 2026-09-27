// The in-sync state of the tag detail page's "Save to NFC tag" section -
// a port of the Android app's com.lumi.player.data.TagSync.
//
// The tag's CURRENT system payload (its name + its ordered song display
// names - exactly what the card-write path composes) is compared against
// the tag's last known tag state (its per-uid saved memory: name +
// songs). The state is DERIVED, not event-driven: any tag edit (a
// rename, a song added/removed/reordered) flips it to .differs
// immediately, and a successful write (which records the memory) flips
// it to .inSync.

import Foundation

/// The in-sync state of the tag detail page's "Save to NFC tag" section.
public enum TagSyncState: Equatable, Sendable {
    /// The memory exists and equals the current system data: no change
    /// since the last save.
    case inSync
    /// The memory exists but the name or the song list no longer matches:
    /// the system tag differs from the saved tag.
    case differs
    /// No saved memory for this tag's uid yet: nothing saved or compared
    /// yet.
    case unknown
}

/// The comparison's verdict: [memory] is the tag's last known tag state
/// (nil - no record for the uid - reads [TagSyncState.unknown]), and
/// [name] + [songs] is the current system data in the same shape the
/// write path composes (the tag's name + its ordered song display names).
public func tagSyncState(name: String, songs: [String], memory: TagMemoryRecord?) -> TagSyncState {
    guard let memory else { return .unknown }
    if memory.name == name && memory.songs == songs { return .inSync }
    return .differs
}
