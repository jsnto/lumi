// The loudness orchestrator - the iOS counterpart of the Android app's
// LoudnessOrchestrator (the god object's loudness-orchestration section,
// split off in the same lockstep shape - the AutoLockEngine-style
// "state + seams, driven from the app-model scope" extraction): the
// explicit "Normalize now" pass, the on-add measurements, the
// fingerprint staleness check, the single measurement slot, and the
// measurement seam.
//
// The app model publishes the pass's running flag (mirrored through the
// [onNormalizingChange] seam; the measurement status follows the store
// change callback) and keeps the same-named public surfaces as thin
// delegates, so the UI's access pattern is untouched.

import Foundation
import LumiCore

/// The loudness normalization orchestration (the audio level's
/// "Normalize now" pass + the on-add measurements): the state and the
/// seams, driven from the app model's scope.
@MainActor
public final class LoudnessOrchestrator {
    // MARK: - dependencies (the app model wires the seams)

    private let store: Store

    /// The app's event line (the model's uiEvent - the Android Toast).
    public var event: (String) -> Void = { _ in }

    /// The url to read for an entry (the model's playUrl - the security
    /// scope asserted for the read).
    public var playUrl: (Entry) -> String = { $0.payload }

    /// The pass's running flag (the model's @Published mirror: the pass's
    /// button is disabled for its duration).
    public var onNormalizingChange: (Bool) -> Void = { _ in }

    /// True while an explicit "Normalize now" pass is running (the
    /// second-pass guard: a slow earlier pass can never run against a
    /// newer selection).
    private var passRunning = false

    /// The single measurement slot: every loudness read - the on-add
    /// measurements and the explicit pass alike - runs inside it, so at
    /// most one entry decodes at a time; a folder add's burst of fresh
    /// songs queues behind the in-flight read, one decode at a time (the
    /// Android app's measurementLock: a Mutex held for the full read).
    private let measurementSlot = SerialQueue()

    /// Test seam: the loudness measurement read, replaced in tests.
    /// The default decodes the file at decode speed (the
    /// [LoudnessMeasurer]: an AVAssetReader with the meter's LINEAR PCM
    /// output and the meter configured for the file's own stream
    /// parameters) and is time-limited (a stalled decode settles into a
    /// failure); a read failure surfaces as the exception the caller
    /// settles to unmeasured.
    internal var loudnessMeasurer: @Sendable (URL) async throws -> LoudnessResult = { url in
        try await LoudnessMeasurer().measure(url: url)
    }

    public init(store: Store) {
        self.store = store
    }

    /**
     * The explicit "Normalize now" pass (the audio level settings pane's
     * action): measures every audio entry that has no measurement yet, or
     * whose content changed since the last measurement (the content
     * fingerprint no longer matches the stored one). Best-effort per
     * entry: a failure leaves that entry unmeasured with its own event
     * and the pass keeps going. A second pass while one is running is
     * ignored, so a slow earlier pass can never run against a newer
     * selection; the live progress is the model's loudnessStatus row,
     * plus the pass's own start / completion events (the app's usual
     * event language).
     */
    public func normalizeNow() {
        guard !passRunning else { return }
        passRunning = true
        onNormalizingChange(true)
        Task { @MainActor [self] in
            defer {
                passRunning = false
                onNormalizingChange(false)
            }
            let pending = store.entries.filter { $0.typeIsAudio() && isStaleForLoudness($0) }
            if pending.isEmpty {
                event("Everything is already measured.")
                return
            }
            event(
                pending.count == 1
                    ? "Measuring 1 song for audio level…"
                    : "Measuring \(pending.count) songs for audio level…",
            )
            var failed = 0
            for entry in pending {
                if !(await measureEntryBestEffort(entry, reportEvent: true)) { failed += 1 }
            }
            event(
                failed > 0
                    ? "Audio level ready - \(failed) \(failed == 1 ? "song" : "songs") couldn't be measured."
                    : "Audio level ready.",
            )
        }
    }

    /**
     * Best-effort measurement of one freshly added audio entry (the
     * on-add measurement: a song added to the content list is measured
     * right away, so the audio level works the moment it is turned on).
     * A failure leaves the entry unmeasured with its own event (the song
     * plays at its recorded level; "Normalize now" can retry it).
     */
    public func measureEntry(_ entry: Entry) {
        Task { @MainActor [self] in
            await measureEntryBestEffort(entry, reportEvent: true)
        }
    }

    /**
     * The measurement pass for [entry]: the content fingerprint check
     * first (an unchanged file keeps its stored measurement - no
     * re-read), then the best-effort read (off the main actor and
     * time-limited - any failure settles to unmeasured, with an event
     * when [reportEvent]). The read itself runs in the single
     * [measurementSlot], so on-add measurements and the explicit pass
     * queue behind each other - never two decodes at once. Returns
     * whether the entry now has a stored measurement.
     */
    private func measureEntryBestEffort(_ entry: Entry, reportEvent: Bool) async -> Bool {
        let fingerprint = fingerprintOf([EntrySpec(type: "audio", label: entry.label, payload: entry.payload)])
        if let existing = store.loudnessForEntry(entry.id), existing.fingerprint == fingerprint {
            return true // unchanged content: the stored measurement stands
        }
        do {
            let url = URL(fileURLWithPath: playUrl(entry))
            let result = try await measurementSlot.run { [measurer = loudnessMeasurer] in
                try await measurer(url)
            }
            store.recordLoudness(
                entryId: entry.id,
                lufs: result.lufs,
                truePeakDbTp: result.truePeakDbTp,
                fingerprint: fingerprint,
            )
            return true
        } catch is CancellationError {
            return false
        } catch {
            storeLog.warning("Couldn't measure the loudness of \(entry.label): \(error)")
            if reportEvent { event("Couldn't measure “\(entry.label)” for audio level.") }
            return false
        }
    }

    /** Whether [entry]'s stored measurement (if any) matches its content now. */
    private func isStaleForLoudness(_ entry: Entry) -> Bool {
        let fingerprint = fingerprintOf([EntrySpec(type: "audio", label: entry.label, payload: entry.payload)])
        guard let row = store.loudnessForEntry(entry.id) else { return true }
        return row.fingerprint != fingerprint
    }
}

/// The app's single measurement slot (the Android app's measurementLock -
/// a Mutex held for the duration of the full read, including its await):
/// serializes async work so at most one runs at a time, in submission
/// order. A folder add's burst of fresh songs queues behind the
/// in-flight read - one decode at a time.
public final class SerialQueue: @unchecked Sendable {
    private let lock = NSLock()
    private var chain: Task<Void, Never> = Task { }

    public init() {}

    /**
     * Runs [work] after every previously chained work has settled, and
     * chains this work for the next caller (a throwing work settles - it
     * does not break the chain). Returns [work]'s result (rethrowing its
     * error to the caller).
     */
    public func run<T: Sendable>(_ work: @Sendable () async throws -> T) async rethrows -> T {
        let previous: Task<Void, Never>
        let resultTask: Task<T, Error>
        lock.lock()
        previous = chain
        // The work starts only once every previously chained work has
        // settled (the serial slot's contract: one decode at a time),
        // and the next caller chains onto THIS work the same way.
        resultTask = Task {
            await previous.value
            return try await work()
        }
        chain = Task { _ = try? await resultTask.value }
        lock.unlock()
        return try await resultTask.value
    }
}
