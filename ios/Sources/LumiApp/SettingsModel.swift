// The app's stored settings (the Android app's settings table) - the
// collapse of the observe/set boilerplate the god object carried one pair
// per setting: the setting keys, the tolerant read of each stored value
// (an unset or invalid stored string reads as the default), and the write
// back. The remembered-positions stepper's write flow (the write seam +
// the in-flight gate) lives here too.
//
// The app model publishes the live values (the UI's observed data) and
// delegates the write here - the god-object split's settings-flow
// collapse, in lockstep with the Android app.

import Foundation
import LumiCore

@MainActor
public final class SettingsModel {
    // MARK: - the setting keys (the Android app's settings table keys)

    public static let playModeKey = "play_mode"
    public static let contentSortKey = "content_sort_direction"
    public static let nfcRereadKey = "nfc_reread_seconds"
    public static let audioLevelModeKey = "audio_level_mode"
    public static let audioLevelTargetKey = "audio_level_target_lufs"
    public static let rememberedPositionsKey = "remembered_positions"
    public static let setupCompleteKey = "setup_complete"

    public let store: Store

    /// The remembered-positions write (the Android app's
    /// rememberedPositionsWriter seam): the settings-table upsert. A
    /// test can hold the in-flight window open by replacing it.
    public var rememberedPositionsWriter: (Int) -> Void

    /// True while a remembered-positions write is in flight (the
    /// settings row's stepper controls are disabled for the write's
    /// duration - the next step always computes from the value the
    /// write landed).
    public private(set) var rememberedPositionsWriteInFlight = false

    /// The write's progress (the app model's @Published mirror: the
    /// flag's flip at the step's start, and the value the write landed -
    /// the clamp through the settings-table value - in the landing hop).
    public var onWriteProgress: (Bool, Int?) -> Void = { _, _ in }

    public init(store: Store) {
        self.store = store
        self.rememberedPositionsWriter = { [store] count in
            store.setSetting(Self.rememberedPositionsKey, rememberedPositionsStoredValue(count))
        }
    }

    // MARK: - the tolerant reads (unset/invalid -> the default)

    /// The play mode (the PlayMode.fromStorage fallback: unset/invalid
    /// reads as the default mode).
    public func playMode() -> PlayMode {
        .fromStorage(store.setting(Self.playModeKey) ?? "")
    }

    /// The content sort direction (the ASCENDING fallback).
    public func contentSort() -> ContentSortDirection {
        contentSortDirectionFromStored(store.setting(Self.contentSortKey))
    }

    /// The "Tag re-read wait" window (the Android app's clamp 1-120 with
    /// the 15 s default).
    public func nfcRereadSeconds() -> Int {
        Self.rereadSeconds(from: store.setting(Self.nfcRereadKey))
    }

    /// The "Tag re-read wait" window helper (the Android app's clamp
    /// 1-120 with the 15 s default) on its own.
    public static func rereadSeconds(from stored: String?) -> Int {
        let value = Int((stored ?? "").trimmingCharacters(in: .whitespacesAndNewlines))
        guard let v = value else { return NfcController.rereadSecondsDefault }
        return max(1, min(120, v))
    }

    /// The audio level's mode (unset/invalid reads as OFF).
    public func audioLevelMode() -> AudioLevelMode {
        .fromStorage(store.setting(Self.audioLevelModeKey))
    }

    /// The audio level's target (unset/invalid/out-of-ladder reads as the
    /// default -16).
    public func audioLevelTargetLufs() -> Double {
        audioLevelTargetLufsFromStored(store.setting(Self.audioLevelTargetKey))
    }

    /// The remembered-positions count (unset/invalid reads as the
    /// hard-coded default of 3).
    public func rememberedPositions() -> Int {
        rememberedPositionsFromStored(store.setting(Self.rememberedPositionsKey))
    }

    /// The first-time setup wizard's gate (the Android app's
    /// setup_complete).
    public func setupComplete() -> Bool {
        store.setting(Self.setupCompleteKey) == "1"
    }

    // MARK: - the writes (the settings-table upserts)

    public func writePlayMode(_ mode: PlayMode) {
        store.setSetting(Self.playModeKey, mode.rawValue)
    }

    public func writeContentSort(_ direction: ContentSortDirection) {
        store.setSetting(Self.contentSortKey, direction.storedValue)
    }

    /// The re-read window write: clamps first (1-120, the 15 s default),
    /// stores the CLAMPED value, and returns it (the model publishes what
    /// landed).
    @discardableResult
    public func writeNfcRereadSeconds(_ seconds: Int) -> Int {
        let clamped = Self.rereadSeconds(from: String(seconds))
        store.setSetting(Self.nfcRereadKey, String(clamped))
        return clamped
    }

    public func writeAudioLevelMode(_ mode: AudioLevelMode) {
        store.setSetting(Self.audioLevelModeKey, mode.storageValue)
    }

    /// The target is stored as an int LUFS string (the Android app's
    /// setAudioLevelTargetLufs: targetLufs.toInt()).
    public func writeAudioLevelTargetLufs(_ targetLufs: Double) {
        store.setSetting(Self.audioLevelTargetKey, String(Int(targetLufs)))
    }

    /// The wizard's finish (the Android app's markSetupComplete's stored
    /// gate).
    public func writeSetupComplete() {
        store.setSetting(Self.setupCompleteKey, "1")
    }

    // MARK: - the remembered-positions write flow

    /// The Advanced options page's stepper: changes the count one at a
    /// time (the clamp through [rememberedPositionsStoredValue] makes a
    /// tap at a bound a no-op rather than an escape), persists through
    /// the settings table, and takes effect at the next stored position
    /// (a lower count evicts the oldest remembered tags first, a higher
    /// one keeps the rows already remembered). While a step's write is in
    /// flight the next step is refused, so the write always lands from
    /// the value it computed against; the [onWriteProgress] seam reports
    /// the flag's flip and the landed value.
    public func setRememberedPositions(_ count: Int) {
        guard !rememberedPositionsWriteInFlight else { return }
        rememberedPositionsWriteInFlight = true
        onWriteProgress(true, nil)
        Task { @MainActor [self] in
            // The write hops off the main actor for the write itself
            // (the Android app's Room write is an off-main suspend
            // call), so the stepper's disabled window is the write's own
            // duration, the same observable behavior the Android app's
            // in-flight flag has.
            await Task.yield()
            rememberedPositionsWriter(count)
            // The published value is what the write landed: the writer's
            // clamp through the settings-table value (a tap at a bound is
            // a no-op rather than an escape).
            let landed = rememberedPositionsFromStored(rememberedPositionsStoredValue(count))
            rememberedPositionsWriteInFlight = false
            onWriteProgress(false, landed)
        }
    }
}
