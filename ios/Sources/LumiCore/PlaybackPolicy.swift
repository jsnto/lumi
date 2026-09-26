// Pure decision logic for the two play modes, decoupled from the iOS
// frameworks so it can be unit tested - a port of the Android app's
// com.lumi.player.player.PlaybackPolicy.
//
// Both modes keep NFC always listening: a registered tag read starts or
// switches playback, and a re-read of the tag already playing resumes it
// when it is paused (NFC reading overrides play/pause).
//
// CONTINUOUS: playback keeps going after the tag is removed; the tag stays
// "active" until a different tag is read. Tag loss is ignored.
// PROXIMITY: a detected tag starts playback and stays active while it is
// detected; losing the active tag PAUSES playback and keeps the tag active,
// so a re-read of the same tag resumes the paused list instead of
// restarting it. The tag is cleared only when playback itself stops.

import Foundation

/// What the app should do in response to an NFC event.
public enum NfcAction: Equatable, Sendable {
    /// Start (or switch to) playback of the tag's content list.
    case startPlayback
    /// Resume the paused list that is already playing.
    case resume
    /// Do nothing (e.g. re-read of the tag already playing).
    case ignore
    /// Pause playback (proximity mode: the tag left the reader range).
    case pausePlayback
}

public final class PlaybackPolicy: Sendable {
    private let mode: PlayMode

    /// UID of the tag whose list is currently playing, if any.
    public private(set) var activeTagUid: String?

    public init(mode: PlayMode) {
        self.mode = mode
    }

    /// A registered tag was read. [paused] reports whether the active tag's
    /// list is currently paused so a re-read of it can resume it (reading
    /// overrides the user's pause).
    public func onTagDetected(uid: String, paused: Bool = false) -> NfcAction {
        if uid == activeTagUid {
            return paused ? .resume : .ignore
        }
        activeTagUid = uid
        return .startPlayback
    }

    /// The active tag left the reader range. In proximity mode this pauses
    /// playback; the tag STAYS active so a re-read of it resumes (the
    /// "reading overrides play/pause" behavior) rather than restarting.
    public func onTagLost(uid: String) -> NfcAction {
        if mode == .proximity && uid == activeTagUid {
            return .pausePlayback
        }
        return .ignore
    }

    public func seedActiveTagUid(_ uid: String?) {
        activeTagUid = uid
    }

    /// The player stopped on its own (user pressed stop, app closed, ...).
    public func onPlaybackStopped() {
        activeTagUid = nil
    }

    public var active: Bool { activeTagUid != nil }
}
