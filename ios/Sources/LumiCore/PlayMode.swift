// How the app reacts to NFC tags - a port of the Android app's
// com.lumi.player.data.PlayMode.
//
// CONTINUOUS: reading a tag starts playback and it keeps going after the
// tag is removed, until a different tag is read (or the user pauses).
// PROXIMITY: playback continues only while the tag stays in the reader's
// range; removing the tag PAUSES playback (the tag stays active), so a
// re-read of the same tag resumes the paused list instead of restarting it.

import Foundation

public enum PlayMode: String, CaseIterable, Codable, Sendable {
    case continuous
    case proximity

    /// Reads a stored play mode. Unset or invalid values fall back to
    /// .proximity - the app's default (a stored mode still wins).
    public static func fromStorage(_ value: String) -> PlayMode {
        allCases.first { $0.rawValue.caseInsensitiveCompare(value) == .orderedSame } ?? .proximity
    }
}
