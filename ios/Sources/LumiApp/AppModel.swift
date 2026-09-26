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
    /// The Android app's Toast: the last user-facing event (the UI shows
    /// it as a transient banner).
    @Published public var uiEvent: String? = nil
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
    /// The unified scan's armed state (the scan-in-progress dialog).
    @Published public private(set) var scanActive: Bool = false
    /// The tag whose card the armed card-write scan writes (nil = none).
    @Published public private(set) var cardWriteTargetId: Int? = nil
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
        self.policy = PlaybackPolicy(mode: self.playMode)
        self.isNfcSupported = nfc.isSupported

        engine.delegate = self
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
    /// Support (the iOS counterpart of the Room database file).
    public static func makeFileStore() -> Store {
        let fm = FileManager.default
        let support = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        let dir = support.appendingPathComponent("Lumi", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let file = dir.appendingPathComponent("store.json")
        var doc = StoreDoc()
        if let data = try? Data(contentsOf: file),
           let loaded = try? JSONDecoder().decode(StoreDoc.self, from: data) {
            doc = loaded
        }
        return Store(doc: doc) { document in
            guard let data = try? JSONEncoder().encode(document) else { return }
            try? data.write(to: file, options: .atomic)
        }
    }

    private func refreshSnapshots() {
        tags = store.tags
        entries = store.entries
        folders = store.folders
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
        let specs = audio.map { EntrySpec(type: "audio", label: $0.label, payload: $0.playUrl()) }
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
        scanActive = true
        nfc.armTagScan()
    }

    public func stopTagScan() {
        scanActive = false
        scanAssignEntryId = nil
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

    /// The tag page's "Save to NFC card" (the Android startCardWrite).
    /// The payload is refused up front when it is over the app's write
    /// cap - a permanent size fact, never a retry-able write failure.
    public func startCardWrite(_ tag: Tag) -> Bool {
        let data = LumiTagData(name: tag.name, songs: store.songNamesForTag(tag.id))
        guard NdefCodec.messageByteLength(data) <= NdefCodec.maxPayloadBytes, isNfcSupported else {
            return false
        }
        cardWriteTargetId = tag.id
        nfc.armCardWrite(target: tag)
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
        if store.entriesForTag(tag.id).isEmpty && !capture.restoreSongs.isEmpty {
            var seen = Set<String>()
            for songName in capture.restoreSongs {
                let trimmed = songName.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmed.isEmpty, seen.insert(trimmed).inserted else { continue }
                let entry = store.entryByLabel(trimmed)
                store.insertLinkByName(tagId: tag.id, songName: trimmed, entryId: entry?.id)
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
        for url in urls {
            let bookmark = FolderAccess.bookmarkData(for: url)
            let payload = url.path
            if store.addEntryUnique(
                label: url.deletingPathExtension().lastPathComponent,
                payload: payload,
                folderId: nil,
                bookmark: bookmark,
            ) != nil {
                added += 1
            }
        }
        uiEvent(added == 0 ? "Already in the content list." : "Added \(added) song\(added == 1 ? "" : "s").")
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
            if added == 0 {
                emptyNames.append(label)
            } else {
                addedTotal += added
            }
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
        for folder in store.folders {
            guard let url = FolderAccess.resolve(folder.bookmark) else { continue }
            defer { url.stopAccessingSecurityScopedResource() }
            guard FolderAccess.children(of: url.path) != nil else { continue }
            let walked: Set<String> = Set(
                collectAudioFiles(rootPath: url.path, childrenOf: { FolderAccess.children(of: $0) ?? [] }).map { $0.path },
            )
            store.markEntriesFromFolder(folderId: folder.id, payloads: walked)
        }
    }

    // MARK: - the song profile (the entry detail page)

    /// Opens the profile of an entry (the metadata read is best-effort
    /// with the 5 s timeout; a failure leaves the placeholders).
    public func selectEntry(_ entry: Entry) {
        selectedEntryId = entry.id
        entryMetadata = nil
        let url = URL(fileURLWithPath: entry.playUrl())
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

    /// The url to PLAY: the stored payload (a file path), with the
    /// security-scoped access asserted when the row carries its own
    /// bookmark (individually added files). The scope is asserted at most
    /// once per path per process (the scope is held for the session, like
    /// the Android app's held read grant). Folder-imported rows carry no
    /// row bookmark: the folder's bookmark governs.
    public func playUrl() -> String {
        if let bookmark, !bookmark.isEmpty {
            let path = payload
            AppModel.accessPathLock.lock()
            let first = !AppModel.accessedPaths.contains(path)
            if first { AppModel.accessedPaths.insert(path) }
            AppModel.accessPathLock.unlock()
            if first {
                _ = FolderAccess.resolve(bookmark)
            }
        }
        return payload
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
        switch outcome {
        case let .registeredTag(tag):
            if let entryId = scanAssignEntryId, let entry = store.entryById(entryId) {
                // The scan-to-assign: link the pending entry and stay on
                // the profile (the Android scanAssign registered path).
                _ = assignEntryToTag(tag: tag, entry: entry)
                scanAssignEntryId = nil
                selectedEntryId = entry.id
            } else {
                selectedTagId = tag.id
                uiEvent("Found \(tag.name).")
            }
        case let .unknownTag(uid, payload, memory):
            // The naming prefill: the card's own payload name, else the
            // per-card memory's name (the Android capture prefill).
            var prefill = ""
            var songs: [String] = []
            if case let .answered(data) = payload, let data {
                prefill = data.name
                songs = data.songs
            }
            if prefill.isEmpty { prefill = memory?.name ?? "" }
            if songs.isEmpty { songs = memory?.songs ?? [] }
            capture = CaptureState(uid: uid, name: prefill, restoreSongs: songs)
        case let .cardWrite(message):
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
            )
        }
    }
}
