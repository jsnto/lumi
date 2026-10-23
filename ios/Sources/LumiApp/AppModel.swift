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
//   to the engine in one synchronous step (the Android ACTION_START).
//
// The god-object split (the Android app's four managers, in lockstep):
// the stored settings (the keys, the tolerant reads, the writes, the
// remembered-positions write flow) live in [SettingsModel], the content:
// files and folders section (the add / rescan / removal / backfill + the
// content list's views) in [FolderContentManager], the tag song
// management (the per-song link / unlink / reorder + the profile's
// assign-to-tag) in [TagLinkManager], and the loudness normalization
// orchestration (the "Normalize now" pass, the on-add measurements, the
// single measurement slot) in [LoudnessOrchestrator]. The model keeps
// every same-named public surface as a thin delegate, so the UI's access
// pattern is untouched; the protocol conformance faces (the NFC event
// sink, the engine delegate) live with their protocols' files.
//

import Combine
import Foundation
import LumiCore

@MainActor
public final class AppModel: ObservableObject {
    // MARK: - dependencies

    public let store: Store
    public let engine: PlaybackEngine
    public let nfc: NfcController
    /// The tag read/lost decisions for the active play mode (internal:
    /// the engine delegate's face, in the engine's file, resets it).
    var policy: PlaybackPolicy

    // MARK: - the managers (the god-object split, in lockstep with the
    // Android app's four managers)

    /// The stored settings (the Android app's settings table): the keys,
    /// the tolerant reads, the writes, the remembered-positions write
    /// flow. The published settings values below are the live mirror; the
    /// setters delegate the write.
    private let settings: SettingsModel
    /// The content: files and folders + the content list's views. The
    /// content surfaces below delegate here.
    private let folderContent: FolderContentManager
    /// The tag song management (link / unlink / reorder + the profile's
    /// assign-to-tag). The tag-song surfaces below delegate here.
    private let tagLinks: TagLinkManager
    /// The loudness normalization orchestration (the pass, the on-add
    /// measurements, the single measurement slot). The loudness surfaces
    /// below delegate here.
    private let loudness: LoudnessOrchestrator

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
    /// button is disabled for its duration - the loudness orchestrator's
    /// mirror, driven through its running-flag seam).
    @Published public internal(set) var normalizing: Bool = false
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
    @Published public internal(set) var isNfcSupported: Bool = false

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
    @Published public internal(set) var scanActive: Bool = false
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
    @Published public internal(set) var scanAssignEntryId: Int? = nil

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
        self.settings = SettingsModel(store: store)
        self.folderContent = FolderContentManager(store: store)
        self.tagLinks = TagLinkManager(store: store)
        self.loudness = LoudnessOrchestrator(store: store)
        self.playMode = settings.playMode()
        self.contentSort = settings.contentSort()
        self.nfcRereadSeconds = settings.nfcRereadSeconds()
        self.audioLevelMode = settings.audioLevelMode()
        self.audioLevelTargetLufs = settings.audioLevelTargetLufs()
        self.loudnessStatus = store.loudnessStatus()
        self.rememberedPositions = settings.rememberedPositions()
        self.setupComplete = settings.setupComplete()
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
        // The managers' seams (the NfcController seam wiring's own shape):
        // their reports and writes land on the model's published state.
        settings.onWriteProgress = { [weak self] inFlight, landed in
            guard let self else { return }
            self.rememberedPositionsWriteInFlight = inFlight
            if let landed { self.rememberedPositions = landed }
        }
        folderContent.event = { [weak self] in self?.uiEvent($0) }
        folderContent.measure = { [weak self] in self?.measureEntry($0) }
        folderContent.contentSort = { [weak self] in self?.contentSort ?? .ascending }
        folderContent.clearFolderSelection = { [weak self] in self?.selectedFolderId = nil }
        folderContent.clearEntrySelection = { [weak self] in self?.selectedEntryId = nil }
        tagLinks.event = { [weak self] in self?.uiEvent($0) }
        loudness.event = { [weak self] in self?.uiEvent($0) }
        loudness.playUrl = { [weak self] in self?.playUrl(for: $0) ?? $0.payload }
        loudness.onNormalizingChange = { [weak self] in self?.normalizing = $0 }

        refreshSnapshots()
        // The policy's active tag comes back with the playback state
        // (the Android app's PlayerStateHolder seed): a cold launch while
        // a tag's list is "playing" keeps its tag active.
        policy.seedActiveTagUid(engine.state.activeTagUid)
    }

    private func refreshSnapshots() {
        tags = store.tags
        entries = store.entries
        folders = store.folders
        // The audio level's status follows the store (a measurement that
        // lands - or an entry's removal - refreshes the pane's row).
        loudnessStatus = store.loudnessStatus()
    }

    // MARK: - play mode / settings (the stored settings delegate to
    // [SettingsModel]: the keys, the tolerant reads and the writes live
    // there; the published values above are the live mirror)

    public func setPlayMode(_ mode: PlayMode) {
        playMode = mode
        settings.writePlayMode(mode)
        // The policy re-seeds the active tag across a mode switch (a tag
        // already playing stays the active one).
        let old = policy.activeTagUid
        policy = PlaybackPolicy(mode: mode)
        policy.seedActiveTagUid(old)
        nfc.seedWatchedTag(uid: old)
    }

    public func setContentSort(_ direction: ContentSortDirection) {
        contentSort = direction
        settings.writeContentSort(direction)
    }

    public func setNfcRereadSeconds(_ seconds: Int) {
        // The write clamps first (1-120, the 15 s default) and stores the
        // clamped value; the published value is what landed.
        nfcRereadSeconds = settings.writeNfcRereadSeconds(seconds)
    }

    // MARK: - audio level (the loudness normalization setting; the
    // orchestration lives in [LoudnessOrchestrator])

    public func setAudioLevelMode(_ mode: AudioLevelMode) {
        audioLevelMode = mode
        settings.writeAudioLevelMode(mode)
    }

    public func setAudioLevelTargetLufs(_ targetLufs: Double) {
        audioLevelTargetLufs = targetLufs
        settings.writeAudioLevelTargetLufs(targetLufs)
    }

    /// The audio level's measurement status for the pane is the published
    /// [loudnessStatus] (refreshSnapshots tracks the store).

    /// The explicit "Normalize now" pass (the audio level settings pane's
    /// action) + the on-add measurement, delegated to
    /// [LoudnessOrchestrator].
    public func normalizeNow() {
        loudness.normalizeNow()
    }

    public func measureEntry(_ entry: Entry) {
        loudness.measureEntry(entry)
    }

    // MARK: - remembered playback positions (the Advanced options
    // setting; the write flow lives in [SettingsModel])

    /// The Advanced options page's stepper (the Android app's
    /// setRememberedPositions): the settings model's write flow - the
    /// clamp through the settings-table value makes a tap at a bound a
    /// no-op rather than an escape, and while a step's write is in flight
    /// the next step is refused, so the write always lands from the value
    /// it computed against.
    public func setRememberedPositions(_ count: Int) {
        settings.setRememberedPositions(count)
    }

    // MARK: - first-time setup

    /**
     * The wizard's finish (the Android app's markSetupComplete): writes
     * the stored gate so the normal player UI shows from the next launch
     * (and flips the gate live for this run).
     */
    public func completeSetup() {
        settings.writeSetupComplete()
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

    // MARK: - tag song management (delegated to [TagLinkManager])

    public func linkEntriesToTag(tagId: Int, entryIds: [Int]) -> Int {
        tagLinks.linkEntriesToTag(tagId: tagId, entryIds: entryIds)
    }

    public func unlinkSongFromTag(tagId: Int, linkId: Int) -> Bool {
        tagLinks.unlinkSongFromTag(tagId: tagId, linkId: linkId)
    }

    public func reorderTagSongs(tagId: Int, newOrder: [Int]) {
        tagLinks.reorderTagSongs(tagId: tagId, newOrder: newOrder)
    }

    // MARK: - content: files and folders (delegated to
    // [FolderContentManager]: the add / rescan / removal / backfill, one
    // delegate each)

    public func addFileEntries(urls: [URL]) -> Int {
        folderContent.addFileEntries(urls: urls)
    }

    public func deleteEntry(_ entry: Entry) {
        folderContent.deleteEntry(entry)
    }

    public func addFolderEntries(urls: [URL]) {
        folderContent.addFolderEntries(urls: urls)
    }

    public func rescanFolder(_ folder: Folder) {
        folderContent.rescanFolder(folder)
    }

    public func removeFolder(_ folder: Folder) {
        folderContent.removeFolder(folder)
    }

    public func backfillFolderEntries() {
        folderContent.backfillFolderEntries()
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
    /// the reported no-op) - delegated to [TagLinkManager].
    public func assignEntryToTag(tag: Tag, entry: Entry) -> Bool {
        tagLinks.assignEntryToTag(tag: tag, entry: entry)
    }

    // MARK: - NFC usability (the prompt's gate)

    /// Whether the NFC prompt should show (the Android refreshNfcState's
    /// radio-missing case; iOS has no user-facing radio toggle - a
    /// supported device is always "on").
    public var nfcUnavailable: Bool { !isNfcSupported }

    // MARK: - content-list views (delegated to [FolderContentManager]:
    // the play-url grant assertion + the content list's sections)

    public func playUrl(for entry: Entry) -> String {
        folderContent.playUrl(for: entry)
    }

    public var sortedFolders: [Folder] {
        folderContent.sortedFolders
    }

    public var sortedIndividualEntries: [Entry] {
        folderContent.sortedIndividualEntries
    }

    public func folderSongs(_ folder: Folder) -> [Entry] {
        folderContent.folderSongs(folder)
    }
}

