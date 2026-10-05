// The app model - the iOS counterpart of the Android app's LumiViewModel:
// the single orchestration layer between the store (persistence), the
// playback engine, the NFC controller and the UI. Every behavior the user
// can observe lands here, ported from the Android source:
//
// - a tag read: the policy decides start / switch / resume (the NFC always
//   listening behavior); a zero-song tag read reports and leaves playback
//   alone;
// - a tag loss: proximity mode pauses (the tag stays active - a re-read
//   resumes the paused list, never restarts it);
// - a start: the queue + the remembered position (fingerprint-checked) go
//   to the engine in one synchronous step (the Android ACTION_START);
// - content: file + folder ingestion with the persistent access grants,
//   the rescan, the removal, the Folders-first list split;
// - tags: the scan (registered -> page, unknown -> registration with the
//   payload/memory prefill), the card write (uid match, cap, memory), the
//   rename, the per-song link/unlink/reorder with the Android ordering
//   semantics.

import Combine
import Foundation
import os
import LumiCore

/// The app's own log (store load/save failures must not fail silently).
private let storeLog = Logger(subsystem: "com.lumi.player", category: "store")

@MainActor
public final class AppModel: ObservableObject {
    // MARK: - dependencies

    public let store: Store
    public let engine: PlaybackEngine
    public let nfc: NfcController
    private var policy: PlaybackPolicy

    // MARK: - published state (the UI's observed data)

    @Published public private(set) var tags: [Tag] = []
    @Published public private(set) var entries: [Entry] = [] // insertion order
    @Published public private(set) var folders: [Folder] = []
    @Published public var playMode: PlayMode
    @Published public var contentSort: ContentSortDirection
    @Published public var nfcRereadSeconds: Int
    /// The audio level's mode (Off / Normalize - the Android app's
    /// audio_level_mode setting; unset/invalid reads as OFF).
    @Published public private(set) var audioLevelMode: AudioLevelMode
    /// The audio level's target (LUFS; unset/invalid/out-of-ladder reads
    /// as the default -16).
    @Published public private(set) var audioLevelTargetLufs: Double
    /// The audio level's measurement status ("N of M songs measured" -
    /// the settings pane's status row), kept live across measurements
    /// (the store's change callback refreshes it).
    @Published public private(set) var loudnessStatus: LoudnessStatus
    /// True while an explicit "Normalize now" pass is running (the pass's
    /// button is disabled for its duration).
    @Published public private(set) var normalizing: Bool = false
    /// How many tags' last played position the LRU remembers (the
    /// Advanced options page's "Remembered playback positions": a count,
    /// 1 to 20, defaulting to the hard-coded default of 3; unset/invalid
    /// stored values read as the default).
    @Published public private(set) var rememberedPositions: Int
    /// True while a remembered-positions write is in flight (the
    /// settings row's stepper controls are disabled for the write's
    /// duration - the next step always computes from the value the
    /// write landed).
    @Published public private(set) var rememberedPositionsWriteInFlight: Bool = false
    /// The first-time setup wizard's gate (the Android app's
    /// setup_complete): the wizard shows until it is finished (the
    /// model's completeSetup writes the stored flag), after which the
    /// normal player UI shows (a flag written by an earlier run stays
    /// read across launches).
    @Published public private(set) var setupComplete: Bool

    /// The wizard's "Set up now" route (the Android app's
    /// onFinish(openNfcTags: true)): when the wizard finishes through the
    /// NFC step's set-up-now, the app opens the settings menu's NFC tags
    /// pane (the one-shot flag the root view consumes once the wizard's
    /// gate flips to the player UI).
    public var wizardOpenNfcTags: Bool = false
    /// The Android app's Toast: the last user-facing event (the UI shows
    /// it as a transient banner).
    @Published public var uiEvent: String? = nil

    /// Sets the event and schedules its own auto-dismiss (the Android
    /// Toast's ~3-second dismissal) - the dismiss runs where the event is
    /// SET, so an event raised while the user is on any screen still goes
    /// away. An earlier event's timer only clears a value that is still its
    /// own (compare-and-clear), so a later event is never dismissed by an
    /// earlier schedule.
    public func uiEvent(_ message: String) {
        uiEvent = message
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            if self.uiEvent == message { self.uiEvent = nil }
        }
    }
    @Published public private(set) var isNfcSupported: Bool = false

    /// A pending registration (the naming dialog): the scanned tag's uid,
    /// the prefilled name and the song list to restore onto an empty tag.
    public struct CaptureState: Equatable {
        public let uid: String
        public var name: String
        public let restoreSongs: [String]
        public init(uid: String, name: String, restoreSongs: [String]) {
            self.uid = uid
            self.name = name
            self.restoreSongs = restoreSongs
        }
    }

    @Published public var capture: CaptureState? = nil
    /// The unified scan's armed state (the scan-in-progress dialog) - set
    /// for EVERY armed scan, the card-write scan among them (the Android
    /// startCardWrite arms the same scan flag the dialog is driven by).
    @Published public private(set) var scanActive: Bool = false
    /// The tag whose card the armed card-write scan writes (nil = none).
    @Published public private(set) var cardWriteTargetId: Int? = nil
    /// The tag whose own tag the armed compare scan reads (nil = none) -
    /// the tag page's "Scan to compare" action.
    @Published public private(set) var cardCompareTargetId: Int? = nil
    /// A registered tag an armed scan just resolved (the Android
    /// pendingTagProfile route): the tags pane opens its detail page and
    /// consumes this.
    @Published public var scannedTagId: Int? = nil
    /// The entry a pending scan-to-assign will link (the profile's
    /// "Scan a tag to assign": a registered read links it and stays on
    /// the profile; an unknown read registers first, then links on save).
    @Published public private(set) var scanAssignEntryId: Int? = nil

    /// Selection state (the detail pages).
    @Published public var selectedTagId: Int? = nil
    @Published public var selectedFolderId: Int? = nil
    @Published public var selectedEntryId: Int? = nil
    @Published public private(set) var entryMetadata: SongMetadata? = nil

    // MARK: - init

    public init(store: Store = AppModel.makeFileStore()) {
        self.store = store
        self.engine = PlaybackEngine()
        self.nfc = NfcController()
        self.playMode = PlayMode.fromStorage(store.setting("play_mode") ?? "")
        self.contentSort = contentSortDirectionFromStored(store.setting("content_sort_direction"))
        self.nfcRereadSeconds = Self.rereadSeconds(from: store.setting("nfc_reread_seconds"))
        self.audioLevelMode = AudioLevelMode.fromStorage(store.setting("audio_level_mode"))
        self.audioLevelTargetLufs = audioLevelTargetLufsFromStored(store.setting("audio_level_target_lufs"))
        self.loudnessStatus = store.loudnessStatus()
        self.rememberedPositions = rememberedPositionsFromStored(store.setting("remembered_positions"))
        self.setupComplete = store.setting("setup_complete") == "1"
        self.policy = PlaybackPolicy(mode: self.playMode)
        self.isNfcSupported = nfc.isSupported

        engine.delegate = self
        // The per-track loudness level seam (the Android service's
        // applyLoudness resolution): the entry's stored loudness (the
        // app's per-payload snapshot - no store read beyond the snapshot
        // on the playback path) against the audio level setting. OFF
        // mode, an unmeasured entry, or a silent one all resolve to the
        // pass-through (the gain's own contract).
        engine.loudnessProvider = { [weak self] spec in
            guard let self else { return .passThrough }
            let sample = self.store.loudnessSnapshot()[spec.payload]
            let gain = gainFor(mode: self.audioLevelMode, targetLufs: self.audioLevelTargetLufs, sample: sample)
            // The limiter engages only for boosted tracks (the Android
            // service's engageLimiter = gainDb > 0.0).
            return LoudnessLevel(gainDb: gain, engageLimiter: gain > 0.0)
        }
        nfc.eventListener = self
        nfc.tagByUid = { [store] uid in store.tagByUid(uid) }
        nfc.nfcRereadSeconds = { [weak self] in
            self?.nfcRereadSeconds ?? NfcController.rereadSecondsDefault
        }
        nfc.cardWriteDataFor = { [weak self] tag in
            guard let self else { return nil }
            return LumiTagData(name: tag.name, songs: self.store.songNamesForTag(tag.id))
        }
        nfc.onCardWriteLanded = { [weak self] tag, data in
            self?.store.rememberTag(tag.uid, tag.name, data.songs)
        }
        nfc.onCardCompared = { [weak self] tag, data in
            // The compare's persistence: the card's ACTUAL contents become
            // the per-uid memory; a blank card clears the record (the
            // in-sync indicator recomputes from the store change).
            guard let self else { return }
            if let data {
                self.store.rememberTag(uid: tag.uid, name: data.name, songs: data.songs)
            } else {
                self.store.forgetTag(uid: tag.uid)
            }
        }
        nfc.memoryRecord = { [store] uid in store.memoryForUid(uid) }
        store.onChange = { [weak self] in
            self?.refreshSnapshots()
        }

        refreshSnapshots()
        // The policy's active tag comes back with the playback state
        // (the Android app's PlayerStateHolder seed): a cold launch while
        // a tag's list is "playing" keeps its tag active.
        policy.seedActiveTagUid(engine.state.activeTagUid)
    }

    /// The default store: the JSON document persisted in Application
    /// Support (the iOS counterpart of the Room database file). A corrupt
    /// document is logged, KEPT (moved aside under a timestamped name -
    /// starting empty and overwriting it would lose the whole library),
    /// and the app starts fresh; a write that fails keeps the old file
    /// (the atomic write) and logs.
    public static func makeFileStore() -> Store {
        let fm = FileManager.default
        let support = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        let dir = support.appendingPathComponent("Lumi", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let file = dir.appendingPathComponent("store.json")
        var doc = StoreDoc()
        if let data = try? Data(contentsOf: file) {
            do {
                doc = try JSONDecoder().decode(StoreDoc.self, from: data)
            } catch {
                storeLog.error("store file is corrupt - starting empty and keeping it: \(error)")
                let stamp = Int(Date().timeIntervalSince1970)
                let corrupt = dir.appendingPathComponent("store.json.corrupt-\(stamp)")
                do {
                    try fm.moveItem(at: file, to: corrupt)
                } catch {
                    storeLog.error("could not keep the corrupt store file aside: \(error)")
                }
            }
        }
        return Store(doc: doc) { document in
            do {
                let data = try JSONEncoder().encode(document)
                try data.write(to: file, options: .atomic)
            } catch {
                // The atomic write keeps the old file on failure: the
                // change is lost from disk (the in-memory store keeps
                // working and a later mutation retries), never the
                // earlier document.
                storeLog.error("failed to save the store file - the change is not persisted: \(error)")
            }
        }
    }

    private func refreshSnapshots() {
        tags = store.tags
        entries = store.entries
        folders = store.folders
        // The audio level's status follows the store (a measurement that
        // lands - or an entry's removal - refreshes the pane's row).
        loudnessStatus = store.loudnessStatus()
    }

    // MARK: - play mode / settings

    /// The "Tag re-read wait" window helper (the Android app's clamp
    /// 1-120 with the 15 s default).
    public static func rereadSeconds(from stored: String?) -> Int {
        let value = Int((stored ?? "").trimmingCharacters(in: .whitespacesAndNewlines))
        guard let v = value else { return NfcController.rereadSecondsDefault }
        return max(1, min(120, v))
    }

    public func setPlayMode(_ mode: PlayMode) {
        playMode = mode
        store.setSetting("play_mode", mode.rawValue)
        // The policy re-seeds the active tag across a mode switch (a tag
        // already playing stays the active one).
        let old = policy.activeTagUid
        policy = PlaybackPolicy(mode: mode)
        policy.seedActiveTagUid(old)
        nfc.seedWatchedTag(uid: old)
    }

    public func setContentSort(_ direction: ContentSortDirection) {
        contentSort = direction
        store.setSetting("content_sort_direction", direction.storedValue)
    }

    public func setNfcRereadSeconds(_ seconds: Int) {
        nfcRereadSeconds = Self.rereadSeconds(from: String(seconds))
        store.setSetting("nfc_reread_seconds", String(nfcRereadSeconds))
    }

    // MARK: - audio level (the loudness normalization setting)

    public func setAudioLevelMode(_ mode: AudioLevelMode) {
        audioLevelMode = mode
        store.setSetting("audio_level_mode", mode.storageValue)
    }

    public func setAudioLevelTargetLufs(_ targetLufs: Double) {
        audioLevelTargetLufs = targetLufs
        // The settings table stores the target as an int LUFS string (the
        // Android app's setAudioLevelTargetLufs: targetLufs.toInt()).
        store.setSetting("audio_level_target_lufs", String(Int(targetLufs)))
    }

    /// The audio level's measurement status for the pane is the published
    /// [loudnessStatus] (refreshSnapshots tracks the store).

    /// The single measurement slot: every loudness read - the on-add
    /// measurements and the explicit pass alike - runs inside it, so at
    /// most one entry decodes at a time; a folder add's burst of fresh
    /// songs queues behind the in-flight read, one decode at a time (the
    /// Android app's measurementLock: a Mutex held for the full read).
    private let measurementSlot = SerialQueue()

    /**
     * The explicit "Normalize now" pass (the audio level settings pane's
     * action): measures every audio entry that has no measurement yet, or
     * whose content changed since the last measurement (the content
     * fingerprint no longer matches the stored one). Best-effort per
     * entry: a failure leaves that entry unmeasured with its own event
     * and the pass keeps going. A second pass while one is running is
     * ignored, so a slow earlier pass can never run against a newer
     * selection; the live progress is the [loudnessStatus] row, plus the
     * pass's own start / completion events (the app's usual event
     * language).
     */
    public func normalizeNow() {
        guard !normalizing else { return }
        normalizing = true
        Task { @MainActor [self] in
            defer { normalizing = false }
            let pending = store.entries.filter { $0.typeIsAudio() && isStaleForLoudness($0) }
            if pending.isEmpty {
                uiEvent("Everything is already measured.")
                return
            }
            uiEvent(
                pending.count == 1
                    ? "Measuring 1 song for audio level…"
                    : "Measuring \(pending.count) songs for audio level…",
            )
            var failed = 0
            for entry in pending {
                if !(await measureEntryBestEffort(entry, reportEvent: true)) { failed += 1 }
            }
            uiEvent(
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
            let result = try await measurementSlot.run { [measurer = loudnessMeasurer] in
                try await measurer(URL(fileURLWithPath: entry.payload))
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
            if reportEvent { uiEvent("Couldn't measure “\(entry.label)” for audio level.") }
            return false
        }
    }

    /** Whether [entry]'s stored measurement (if any) matches its content now. */
    private func isStaleForLoudness(_ entry: Entry) -> Bool {
        let fingerprint = fingerprintOf([EntrySpec(type: "audio", label: entry.label, payload: entry.payload)])
        guard let row = store.loudnessForEntry(entry.id) else { return true }
        return row.fingerprint != fingerprint
    }

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

    // MARK: - remembered playback positions (the Advanced options setting)

    /// The remembered-positions write (the Android app's
    /// rememberedPositionsWriter seam): the settings-table upsert. A
    /// test can hold the in-flight window open by replacing it.
    internal var rememberedPositionsWriter: (Int) -> Void = { [store] count in
        store.setSetting("remembered_positions", rememberedPositionsStoredValue(count))
    }

    /// The Advanced options page's stepper: changes the count one at a
    /// time (the clamp through [rememberedPositionsStoredValue] makes a
    /// tap at a bound a no-op rather than an escape), persists through
    /// the settings table, and takes effect at the next stored position
    /// (a lower count evicts the oldest remembered tags first, a higher
    /// one keeps the rows already remembered). While a step's write is in
    /// flight the next step is refused, so the write always lands from
    /// the value it computed against.
    public func setRememberedPositions(_ count: Int) {
        guard !rememberedPositionsWriteInFlight else { return }
        rememberedPositionsWriteInFlight = true
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
            rememberedPositions = rememberedPositionsFromStored(rememberedPositionsStoredValue(count))
            rememberedPositionsWriteInFlight = false
        }
    }

    // MARK: - first-time setup

    /**
     * The wizard's finish (the Android app's markSetupComplete): writes
     * the stored gate so the normal player UI shows from the next launch
     * (and flips the gate live for this run).
     */
    public func completeSetup() {
        store.setSetting("setup_complete", "1")
        setupComplete = true
    }

    // MARK: - playback commands

    /// A manual (no-tag) play of one entry: the content pane's and folder
    /// page's play button (no remembered position; no tag attached).
    public func playEntry(_ entry: Entry) {
        startPlayer([entry], uid: nil)
    }

    /// The play/pause toggle on the current song (the Android
    /// toggleCurrentPlayback: a paused current song RESUMES, a playing one
    /// pauses in place).
    public func toggleCurrentPlayback() {
        engine.togglePlayPause()
    }

    public func stopPlayback() {
        engine.stopPlayback()
    }

    /// The Android startPlayer: resolves the queue, decides the
    /// remembered position (the fingerprint + bounds check), and starts
    /// the engine in one synchronous step. Manual playback (uid nil)
    /// clears the policy's active tag - a later read of the previously
    /// active tag still starts THAT tag's own list, never the manual
    /// queue.
    public func startPlayer(_ entries: [Entry], uid: String?) {
        let audio = entries.filter { $0.typeIsAudio() }
        guard !audio.isEmpty else {
            // A zero-song (or all-non-audio) start: no policy switch, no
            // presence arming - the Android zero-song guard.
            if let uid, let tag = store.tagByUid(uid) {
                uiEvent(
                    store.entriesForTag(tag.id).isEmpty
                        ? "Nothing is linked to \(tag.name) yet."
                        : "None of \(tag.name)'s songs are in the content list anymore.",
                )
            }
            return
        }
        if uid == nil {
            policy.onPlaybackStopped()
            nfc.seedWatchedTag(uid: nil)
        }
        let specs = audio.map { EntrySpec(type: "audio", label: $0.label, payload: self.playUrl(for: $0)) }
        var startIndex = 0
        var startSeekMs: Int64 = 0
        if let uid {
            switch decideRestore(
                saved: store.positionFor(uid),
                queueFingerprint: fingerprintOf(specs),
                queueSize: specs.count,
            ) {
            case .startFresh:
                break
            case let .seekTo(index, ms):
                startIndex = index
                startSeekMs = ms
            }
        }
        engine.startQueue(specs, uid: uid, startIndex: startIndex, startSeekMs: startSeekMs)
    }

    // MARK: - NFC event sink

    /// The always-listening tag read (the Android onTagDetected entry,
    /// minus the scan arming which the NFC controller settles itself).
    public func onTagDetected(uid: String, payload: LumiTagData?) {
        // The Android entry's early return: a re-read of the active tag
        // while it plays is a no-op (the resume case is the policy's).
        let paused = engine.status == .paused
        if uid == policy.activeTagUid && !paused { return }

        guard let tag = store.tagByUid(uid) else { return }
        let rows = store.entriesForTag(tag.id)
        let entries = rows.compactMap { $0.entry }
        guard !entries.isEmpty else {
            // The zero-song tag read: report, leave playback alone (the
            // Android copy - an empty list keeps the policy unchanged and
            // arms no presence watch).
            uiEvent(
                rows.isEmpty
                    ? "Nothing is linked to \(tag.name) yet."
                    : "None of \(tag.name)'s songs are in the content list anymore.",
            )
            return
        }
        switch policy.onTagDetected(uid: uid, paused: paused) {
        case .startPlayback:
            nfc.seedWatchedTag(uid: uid)
            startPlayer(entries, uid: uid)
        case .resume:
            engine.resumePlayback()
        case .ignore, .pausePlayback:
            break
        }
    }

    /// The confirmed tag removal (the Android onTagLost entry).
    public func onTagLost(uid: String) {
        if policy.onTagLost(uid: uid) == .pausePlayback {
            engine.pausePlayback()
        }
    }

    // MARK: - scan / registration / card write

    /// The tags pane's "Scan NFC" (the Android startTagScan: the scan
    /// settles on the FIRST read, so the next read is a playback read).
    public func startTagScan() {
        capture = nil
        scanAssignEntryId = nil
        scannedTagId = nil
        scanActive = true
        nfc.armTagScan()
    }

    public func stopTagScan() {
        scanActive = false
        scanAssignEntryId = nil
        cardWriteTargetId = nil // the single cancel disarms every armed scan (the Android cancelTagScan)
        cardCompareTargetId = nil
        nfc.disarmScan()
    }

    /// The profile's "Scan a tag to assign": the same unified scan with a
    /// pending entry - a registered read links it and stays on the
    /// profile; an unknown read enters registration and links on save.
    public func startTagScanForEntry(_ entry: Entry) {
        capture = nil
        scanAssignEntryId = entry.id
        scanActive = true
        nfc.armTagScan()
    }

    /// The tag page's "Save to NFC tag" (the Android startCardWrite):
    /// the armed state rides the SAME unified-scan flag the dialog is
    /// driven by, so the scan-in-progress dialog (with the card-write's
    /// own copy) appears for the card write too. The payload is refused
    /// up front when it is over the app's write cap - a permanent size
    /// fact, never a retry-able write failure.
    public func startCardWrite(_ tag: Tag) -> Bool {
        let data = LumiTagData(name: tag.name, songs: store.songNamesForTag(tag.id))
        guard NdefCodec.messageByteLength(data) <= NdefCodec.maxPayloadBytes, isNfcSupported else {
            return false
        }
        capture = nil
        scanAssignEntryId = nil
        cardWriteTargetId = tag.id
        cardCompareTargetId = nil
        scanActive = true
        nfc.armCardWrite(target: tag)
        return true
    }

    /// The tag page's "Scan to compare" (the Android startCardCompare):
    /// the armed state rides the SAME unified-scan flag the dialog is
    /// driven by, so the scan-in-progress dialog (with the compare's own
    /// copy) appears for the compare too. The compare is a READ - it
    /// works on read-only and blank tags (a blank card is a legitimate
    /// compare result), so it carries the write's usability gate but not
    /// the over-cap refusal (a tag that cannot take the payload still
    /// answers a read).
    public func startCardCompare(_ tag: Tag) -> Bool {
        guard isNfcSupported else { return false }
        capture = nil
        scanAssignEntryId = nil
        cardWriteTargetId = nil
        cardCompareTargetId = tag.id
        scanActive = true
        nfc.armCardCompare(target: tag)
        return true
    }

    /// Saves the naming dialog (the Android confirmTagName). A tag is
    /// identified by its uid: saving against an existing uid re-saves (the
    /// name update), an empty tag gets the captured song list restored
    /// onto it (the remembered/captured song list landing by name, with
    /// the entry provenance when the name resolves to a live entry).
    public func confirmTagName() {
        guard let capture else { return }
        let name = capture.name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        var tag = store.tagByUid(capture.uid)
        if let existing = tag {
            store.renameTag(uid: existing.uid, name: name)
            tag = store.tagByUid(capture.uid)
        } else {
            tag = store.addTag(uid: capture.uid, name: name)
        }
        guard let tag else {
            capture = nil
            return
        }
        // The empty-tag restore (the Android confirmTagName's restore
        // step): distinct names, by name, with the live-entry provenance.
        // The link stores the entry's FULL label - a clamped card name
        // that resolves to a longer stored label links it in full (the
        // Android insertLinkByName's entry?.label ?: songName), never the
        // clamped form (which would never match its entry by name).
        if store.entriesForTag(tag.id).isEmpty && !capture.restoreSongs.isEmpty {
            var seen = Set<String>()
            for songName in capture.restoreSongs {
                let trimmed = songName.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmed.isEmpty, seen.insert(trimmed).inserted else { continue }
                let entry = store.entryByLabel(trimmed)
                store.insertLinkByName(tagId: tag.id, songName: entry?.label ?? trimmed, entryId: entry?.id)
            }
        }
        // The per-card memory: the last saved name + song list for this
        // card uid (it survives the tag row's deletion).
        store.rememberTag(uid: capture.uid, name: name, songs: store.songNamesForTag(tag.id))
        capture = nil
        scanActive = false
        // A pending scan-to-assign links the just-saved tag (the Android
        // scanAssign flow: the pending entry links when the tag is
        // saved).
        if let entryId = scanAssignEntryId, let entry = store.entryById(entryId) {
            _ = assignEntryToTag(tag: tag, entry: entry)
            scanAssignEntryId = nil
        }
        uiEvent("Saved \(name).")
        selectedTagId = tag.id
    }

    public func discardCapture() {
        capture = nil
        scanActive = false
    }

    // MARK: - tag CRUD

    public func renameTag(_ tag: Tag, to name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        store.renameTag(uid: tag.uid, name: trimmed)
        uiEvent("Renamed to \(trimmed).")
    }

    public func removeTag(_ tag: Tag) {
        store.deleteTag(tag)
        selectedTagId = nil
        uiEvent("Deleted \(tag.name).")
    }

    /// A tag's rows as the UI sees them (live-resolved against the
    /// content).
    public func tagRows(_ tag: Tag) -> [TagSongRow] {
        store.entriesForTag(tag.id)
    }

    // MARK: - tag song management (the tag detail page)

    /// Links [entryIds] to [tagId] (the in-app add-song flow). Each write
    /// commits before the next starts (the Android bulk-save's commit
    /// order: the tree's render order), and a song the tag already plays
    /// is the reported no-op (the label-level duplicate refusal).
    public func linkEntriesToTag(tagId: Int, entryIds: [Int]) -> Int {
        var linked = 0
        for entryId in entryIds {
            if store.linkEntryToTag(tagId: tagId, entryId: entryId) {
                linked += 1
            }
        }
        return linked
    }

    /// Unlinks ONE song from ONE tag (the per-song remove): the content
    /// row, the folder membership, and the song's links to OTHER tags are
    /// untouched; the tag's surviving links renumber densely (the Android
    /// removal semantics).
    public func unlinkSongFromTag(tagId: Int, linkId: Int) -> Bool {
        store.unlinkLinkFromTag(tagId: tagId, linkId: linkId)
    }

    /// Reorders a tag's song list (the drag reorder). A no-op where the
    /// order is unchanged; a refused non-permutation keeps the current
    /// order (the Android reorder guard).
    public func reorderTagSongs(tagId: Int, newOrder: [Int]) {
        let current = store.entriesForTag(tagId).map { $0.linkId }
        guard newOrder != current else { return } // no write where it started
        store.reorderTagLinks(tagId: tagId, linkIds: newOrder)
        uiEvent("Order saved.")
    }

    // MARK: - content: files and folders

    /// Adds individually picked files (the content pane's "Add content"):
    /// each url is granted a security-scoped bookmark (the iOS per-file
    /// read grant), and a file already stored adds no duplicate row.
    public func addFileEntries(urls: [URL]) -> Int {
        var added = 0
        var addedEntries: [Entry] = []
        for url in urls {
            let bookmark = FolderAccess.bookmarkData(for: url)
            let payload = url.path
            if let entry = store.addEntryUnique(
                label: url.lastPathComponent,
                payload: payload,
                folderId: nil,
                bookmark: bookmark,
            ) {
                added += 1
                addedEntries.append(entry)
            }
        }
        uiEvent(added == 0 ? "Already in the content list." : "Added \(added) song\(added == 1 ? "" : "s").")
        // The on-add measurement (the Android app's measureEntry on the
        // add paths): a song added to the content list is measured for
        // the audio level right away - queued behind the in-flight read
        // in the single measurement slot, one decode at a time.
        for entry in addedEntries where entry.typeIsAudio() {
            measureEntry(entry)
        }
        return added
    }

    /// Removes a content row (the per-row remove). The Android app's
    /// semantics: the row leaves the content list; a tag link to it
    /// survives as a "song not found" row.
    public func deleteEntry(_ entry: Entry) {
        store.deleteEntry(entry)
        selectedEntryId = nil
    }

    /// Adds picked folders (the content pane's Folders "+"): each folder
    /// gets its own persistent access grant (the security-scoped
    /// bookmark), its songs are stored (deduped by payload), and the
    /// folder row is remembered for rescans.
    public func addFolderEntries(urls: [URL]) {
        var addedTotal = 0
        var emptyNames: [String] = []
        var failedNames: [String] = []
        var addedEntries: [Entry] = []
        for url in urls {
            let label = url.lastPathComponent
            guard let bookmark = FolderAccess.bookmarkData(for: url) else {
                failedNames.append(label)
                continue
            }
            _ = url.startAccessingSecurityScopedResource()
            defer { url.stopAccessingSecurityScopedResource() }
            // The root-readability guard: an unreadable tree is the
            // "couldn't read" report, not a crash (the Android
            // guard-the-walk fix).
            guard FolderAccess.children(of: url.path) != nil else {
                failedNames.append(label)
                continue
            }
            let new = rescanNewSongs(
                rootPath: url.path,
                storedPayloads: store.allEntryPayloads,
                childrenOf: { FolderAccess.children(of: $0) ?? [] },
            )
            let (_, added) = store.addFolderEntry(
                uri: url.path,
                bookmark: bookmark,
                label: label,
                songs: new,
            )
            // The rows this add stored: the walked songs' paths (they
            // were absent before, so the payload match is exact).
            let newPaths = Set(new.map { $0.path })
            addedEntries += store.entries.filter { newPaths.contains($0.payload) }
            if added == 0 {
                emptyNames.append(label)
            } else {
                addedTotal += added
            }
        }
        // The on-add measurement (the Android app's measureEntry on the
        // add paths): the freshly stored songs are measured for the audio
        // level right away, queued behind the in-flight read in the
        // single measurement slot - a folder's burst never decodes two at
        // once.
        for entry in addedEntries where entry.typeIsAudio() {
            measureEntry(entry)
        }
        if !failedNames.isEmpty {
            uiEvent("Couldn't read \(failedNames.joined(separator: ", ")).")
        } else if addedTotal == 0 {
            uiEvent(emptyNames.isEmpty ? "Added the folders." : "No new songs in \(emptyNames.joined(separator: ", ")).")
        } else {
            uiEvent("Added \(addedTotal) songs.")
        }
    }

    /// Rescans a folder for new songs (the Android rescanFolder): the same
    /// walk as the add, minus the already-stored payloads - an unchanged
    /// folder rescans to nothing. An unreadable tree (a revoked grant) is
    /// the no-op with the report, not a crash.
    public func rescanFolder(_ folder: Folder) {
        guard let url = FolderAccess.resolve(folder.bookmark) else {
            uiEvent("Couldn't read that folder's songs.")
            return
        }
        defer { url.stopAccessingSecurityScopedResource() }
        guard let new = rescanFolderWalk(rootPath: url.path) else {
            uiEvent("Couldn't read that folder's songs.")
            return
        }
        if new.isEmpty {
            uiEvent("Nothing new in that folder.")
            return
        }
        store.addFolderEntry(uri: folder.uri, bookmark: folder.bookmark, label: folder.label, songs: new)
        // The on-add measurement: the freshly stored songs are measured
        // for the audio level right away (queued in the single
        // measurement slot, one decode at a time).
        let newPaths = Set(new.map { $0.path })
        for entry in store.entries where newPaths.contains(entry.payload) && entry.typeIsAudio() {
            measureEntry(entry)
        }
        uiEvent("Added \(new.count) new song\(new.count == 1 ? "" : "s") to \(folder.label).")
    }

    private func rescanFolderWalk(rootPath: String) -> [ScannedSong]? {
        // The root-readability guard (nil listing = the tree is gone).
        guard FolderAccess.children(of: rootPath) != nil else { return nil }
        return rescanNewSongs(
            rootPath: rootPath,
            storedPayloads: store.allEntryPayloads,
            childrenOf: { FolderAccess.children(of: $0) ?? [] },
        )
    }

    /// Removes a folder from the app (the Android removal): the folder
    /// row AND the stored rows the walk identified leave (tag links to
    /// them cascade off); rows the walk did NOT identify survive
    /// unmarked (the degraded-into-the-individual-list semantics). The
    /// music files on the phone are not touched.
    public func removeFolder(_ folder: Folder) {
        guard let url = FolderAccess.resolve(folder.bookmark) else {
            // The tree can no longer be read: only the folder row goes
            // (its songs cannot be matched - the Android unreadable-tree
            // variant).
            store.removeFolder(folder, importedPayloads: [])
            uiEvent("Removed \(folder.label). Its songs stay in the content list.")
            return
        }
        defer { url.stopAccessingSecurityScopedResource() }
        guard FolderAccess.children(of: url.path) != nil else {
            store.removeFolder(folder, importedPayloads: [])
            uiEvent("Removed \(folder.label). Its songs stay in the content list.")
            return
        }
        let walked: Set<String> = Set(
            collectAudioFiles(rootPath: url.path, childrenOf: { FolderAccess.children(of: $0) ?? [] }).map { $0.path },
        )
        store.removeFolder(folder, importedPayloads: walked)
        selectedFolderId = nil
        uiEvent("Removed \(folder.label) and its imported songs. The files on your device are untouched.")
    }

    /// The app-start backfill (the Android backfillFolderEntries): rows
    /// stored before the folder marker existed get it stamped on a
    /// re-walk of every remembered folder. A folder whose tree can no
    /// longer be read is left unmarked (its songs degrade into the
    /// individual list - visible, not lost).
    public func backfillFolderEntries() {
        let folders = store.folders
        guard !folders.isEmpty else { return }
        // The resolve + walk runs OFF the main actor (the Android app's
        // backfill runs the same walk as a suspending view-model
        // coroutine): only the store write hops back. An unreadable folder
        // is left unmarked (the individual list degrades, the songs stay
        // visible) - the tolerance semantics are unchanged.
        Task {
            let walked: [(folderId: Int, payloads: Set<String>)] = await withTaskGroup(
                of: (Int, Set<String>)?.self,
            ) { group -> [(Int, Set<String>)] in
                for folder in folders {
                    group.addTask {
                        guard let url = FolderAccess.resolve(folder.bookmark) else { return nil }
                        defer { url.stopAccessingSecurityScopedResource() }
                        guard FolderAccess.children(of: url.path) != nil else { return nil }
                        let payloads: Set<String> = Set(
                            collectAudioFiles(rootPath: url.path, childrenOf: { FolderAccess.children(of: $0) ?? [] })
                                .map { $0.path },
                        )
                        return (folder.id, payloads)
                    }
                }
                var results: [(Int, Set<String>)] = []
                for await pair in group where pair != nil {
                    results.append((pair!.0, pair!.1))
                }
                return results
            }
            for result in walked {
                store.markEntriesFromFolder(folderId: result.folderId, payloads: result.payloads)
            }
        }
    }

    // MARK: - the song profile (the entry detail page)

    /// Opens the profile of an entry (the metadata read is best-effort
    /// with the 5 s timeout; a failure leaves the placeholders).
    public func selectEntry(_ entry: Entry) {
        selectedEntryId = entry.id
        entryMetadata = nil
        let url = URL(fileURLWithPath: playUrl(for: entry))
        Task {
            let metadata = await MetadataReader.read(url: url)
            // A stale read (a newer entry selected meanwhile) is dropped.
            guard self.selectedEntryId == entry.id else { return }
            self.entryMetadata = metadata
        }
    }

    /// The profile's assign-to-tag (a tag that already plays the song is
    /// the reported no-op - the song is identified by its name).
    public func assignEntryToTag(tag: Tag, entry: Entry) -> Bool {
        if store.linkEntryToTag(tagId: tag.id, entryId: entry.id) {
            uiEvent("Added \(entry.label) to \(tag.name).")
            return true
        }
        uiEvent("That song is already on \(tag.name).")
        return false
    }

    // MARK: - NFC usability (the prompt's gate)

    /// Whether the NFC prompt should show (the Android refreshNfcState's
    /// radio-missing case; iOS has no user-facing radio toggle - a
    /// supported device is always "on").
    public var nfcUnavailable: Bool { !isNfcSupported }

    // MARK: - content-list views

    /// The paths whose security scope is already asserted (the
    /// process-lifetime access grant, like the Android app's held grants)
    /// and the lock guarding that set.
    nonisolated(unsafe) fileprivate static let accessPathLock = NSLock()
    nonisolated(unsafe) fileprivate static var accessedPaths: Set<String> = []

    /// The url to PLAY for [entry], with the security scope asserted for
    /// the session: a row's OWN bookmark (individually added files) or,
    /// for a folder-imported row (no row bookmark), the OWNING FOLDER's
    /// bookmark - the folder's grant covers its whole tree, and
    /// asserting it is what makes the row's file path readable at all
    /// (the picker's own grant is released long after the pick; without
    /// the assertion every play of a folder song fails and the bad-file
    /// walk burns the whole list). The scope is asserted at most once per
    /// path per process and held for the session, like the Android app's
    /// held read grant.
    public func playUrl(for entry: Entry) -> String {
        if let bookmark = entry.bookmark, !bookmark.isEmpty {
            AppModel.assertAccess(path: entry.payload, bookmark: bookmark)
        } else if let folderId = entry.folderId,
                  let folder = store.folderById(folderId),
                  !folder.bookmark.isEmpty
        {
            AppModel.assertAccess(path: folder.uri, bookmark: folder.bookmark)
        }
        return entry.payload
    }

    /// Asserts the security scope for [path] via [bookmark] at most once
    /// per path per process (the scope is then held for the session). A
    /// failed assert (a stale bookmark) is NOT cached: the next call
    /// retries, so a fresh bookmark stored on a re-add is assertable in
    /// the same process.
    nonisolated private static func assertAccess(path: String, bookmark: Data) {
        accessPathLock.lock()
        defer { accessPathLock.unlock() }
        guard !accessedPaths.contains(path) else { return }
        guard FolderAccess.resolve(bookmark) != nil else { return }
        accessedPaths.insert(path)
    }

    /// The content pane's Folders section (sorted by label through the
    /// shared content sort).
    public var sortedFolders: [Folder] {
        sortedByTitle(folders, title: { $0.label }, id: { $0.id }, direction: contentSort)
    }

    /// The content pane's Individual content section (the unmarked rows -
    /// folder-imported songs stay OUT of it), sorted by label through the
    /// shared content sort.
    public var sortedIndividualEntries: [Entry] {
        sortedByTitle(store.individualEntries, title: { $0.label }, id: { $0.id }, direction: contentSort)
    }

    /// A folder's imported songs (its stored rows, insertion order - the
    /// folder page's tree groups them).
    public func folderSongs(_ folder: Folder) -> [Entry] {
        store.entriesForFolder(folder.id)
    }
}

// MARK: - entry helpers

extension Entry {
    /// Whether the entry is playable audio (the app is audio-only; a
    /// non-audio entry is skipped by the player, the Android app's
    /// loadCurrent skip).
    public func typeIsAudio() -> Bool {
        // The payload is a file path; the supported-audio extension is the
        // type discriminator (the Android app's AUDIO type, ported as the
        // extension check at the player boundary).
        let last = payload.lastPathComponent
        guard let dot = last.lastIndex(of: ".") else { return false }
        let ext = last[last.index(after: dot)...]
        return supportedAudioExtensions.contains(ext.lowercased())
    }
}

// MARK: - NfcEventListener

extension AppModel: NfcEventListener {
    nonisolated public func nfcControllerDidDetect(uid: String, payload: LumiTagData?) {
        Task { @MainActor in
            self.onTagDetected(uid: uid, payload: payload)
        }
    }

    nonisolated public func nfcControllerDidLose(uid: String) {
        Task { @MainActor in
            self.onTagLost(uid: uid)
        }
    }

    nonisolated public func nfcControllerDidSettleScan(_ outcome: NfcController.ScanOutcome) {
        Task { @MainActor in
            self.settleScan(outcome)
        }
    }

    nonisolated public func nfcControllerUsabilityChanged(supported: Bool) {
        Task { @MainActor in
            self.isNfcSupported = supported
        }
    }
}

private extension AppModel {
    /// The armed scan's settle (the Android onTagDetected's scan branch,
    /// driven by the controller's routing): registered -> the detail page
    /// (or the pending assign), unknown -> the registration flow.
    func settleScan(_ outcome: NfcController.ScanOutcome) {
        scanActive = false
        cardWriteTargetId = nil
        cardCompareTargetId = nil
        switch outcome {
        case let .registeredTag(tag):
            if let entryId = scanAssignEntryId, let entry = store.entryById(entryId) {
                // The scan-to-assign: link the pending entry and stay on
                // the profile (the Android scanAssign registered path).
                _ = assignEntryToTag(tag: tag, entry: entry)
                scanAssignEntryId = nil
                selectedEntryId = entry.id
            } else {
                // The scan resolved a registered tag: open its detail
                // page (the Android pendingTagProfile -> tag detail page
                // route, consumed in the tags pane).
                scannedTagId = tag.id
                uiEvent("Found \(tag.name).")
            }
        case let .unknownTag(uid, payload, memory):
            // The naming prefill: the card's own payload name, else the
            // per-card memory's name (the Android capture prefill).
            var prefill = ""
            var songs: [String] = []
            if let payload {
                prefill = payload.name
                songs = payload.songs
            }
            if prefill.isEmpty { prefill = memory?.name ?? "" }
            if songs.isEmpty { songs = memory?.songs ?? [] }
            capture = CaptureState(uid: uid, name: prefill, restoreSongs: songs)
        case let .cardWrite(message):
            uiEvent(message)
        case let .cardCompare(message):
            uiEvent(message)
        }
    }
}

// MARK: - PlaybackEngineDelegate

extension AppModel: PlaybackEngineDelegate {
    nonisolated public func playbackEngineDidStop() {
        Task { @MainActor in
            self.policy.onPlaybackStopped()
            self.nfc.seedWatchedTag(uid: nil)
        }
    }

    nonisolated public func playbackEngineSavePosition(
        tagUid: String,
        positionMs: Int64,
        entryIndex: Int,
        queueFingerprint: String,
    ) {
        Task { @MainActor in
            self.store.remember(
                TagPosition(
                    tagUid: tagUid,
                    positionMs: positionMs,
                    entryIndex: entryIndex,
                    queueFingerprint: queueFingerprint,
                    lastPlayedAt: Int64(Date().timeIntervalSince1970 * 1000),
                ),
                // The app's remembered-positions setting, live (a step on
                // the Advanced options page changes the LRU capacity at
                // the next saved position).
                capacity: self.rememberedPositions,
            )
        }
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
