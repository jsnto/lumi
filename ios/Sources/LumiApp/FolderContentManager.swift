// The content manager - the iOS counterpart of the Android app's
// FolderContentManager (the god object's content section, split off in
// the same lockstep split): the content list's file and folder ingestion
// (the persistent access grants), the rescan, the removal, the app-start
// backfill, the play-url security-scope assertion, and the content
// list's views (the Folders section, the individual rows, a folder's
// imported songs).
//
// The app model keeps the same-named public surfaces as thin delegates,
// so the UI's access pattern is untouched; the behavior lives here, with
// the event / measurement / sort / selection seams wired by the model.

import Foundation
import LumiCore

/// The content: files and folders (the add / rescan / removal / backfill)
/// + the content list's views.
@MainActor
public final class FolderContentManager {
    // MARK: - dependencies (the app model wires the seams)

    private let store: Store

    /// The app's event line (the model's uiEvent - the Android Toast).
    public var event: (String) -> Void = { _ in }

    /// The on-add loudness measurement (the model's measureEntry - the
    /// loudness orchestrator's single measurement slot).
    public var measure: (Entry) -> Void = { _ in }

    /// The content sort direction (the model's published value, live).
    public var contentSort: () -> ContentSortDirection = { .ascending }

    /// Clears the open folder page (the model's selectedFolderId).
    public var clearFolderSelection: () -> Void = {}

    /// Clears the open song profile (the model's selectedEntryId).
    public var clearEntrySelection: () -> Void = {}

    public init(store: Store) {
        self.store = store
    }

    // MARK: - files and folders (the add / rescan / removal / backfill)

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
        event(added == 0 ? "Already in the content list." : "Added \(added) song\(added == 1 ? "" : "s").")
        // The on-add measurement (the Android app's measureEntry on the
        // add paths): a song added to the content list is measured for
        // the audio level right away - queued behind the in-flight read
        // in the single measurement slot, one decode at a time.
        for entry in addedEntries where entry.typeIsAudio() {
            measure(entry)
        }
        return added
    }

    /// Removes a content row (the per-row remove). The Android app's
    /// semantics: the row leaves the content list; a tag link to it
    /// survives as a "song not found" row.
    public func deleteEntry(_ entry: Entry) {
        store.deleteEntry(entry)
        clearEntrySelection()
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
            measure(entry)
        }
        if !failedNames.isEmpty {
            event("Couldn't read \(failedNames.joined(separator: ", ")).")
        } else if addedTotal == 0 {
            event(emptyNames.isEmpty ? "Added the folders." : "No new songs in \(emptyNames.joined(separator: ", ")).")
        } else {
            event("Added \(addedTotal) songs.")
        }
    }

    /// Rescans a folder for new songs (the Android rescanFolder): the same
    /// walk as the add, minus the already-stored payloads - an unchanged
    /// folder rescans to nothing. An unreadable tree (a revoked grant) is
    /// the no-op with the report, not a crash.
    public func rescanFolder(_ folder: Folder) {
        guard let url = FolderAccess.resolve(folder.bookmark) else {
            event("Couldn't read that folder's songs.")
            return
        }
        defer { url.stopAccessingSecurityScopedResource() }
        guard let new = rescanFolderWalk(rootPath: url.path) else {
            event("Couldn't read that folder's songs.")
            return
        }
        if new.isEmpty {
            event("Nothing new in that folder.")
            return
        }
        store.addFolderEntry(uri: folder.uri, bookmark: folder.bookmark, label: folder.label, songs: new)
        // The on-add measurement: the freshly stored songs are measured
        // for the audio level right away (queued in the single
        // measurement slot, one decode at a time).
        let newPaths = Set(new.map { $0.path })
        for entry in store.entries where newPaths.contains(entry.payload) && entry.typeIsAudio() {
            measure(entry)
        }
        event("Added \(new.count) new song\(new.count == 1 ? "" : "s") to \(folder.label).")
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
            event("Removed \(folder.label). Its songs stay in the content list.")
            return
        }
        defer { url.stopAccessingSecurityScopedResource() }
        guard FolderAccess.children(of: url.path) != nil else {
            store.removeFolder(folder, importedPayloads: [])
            event("Removed \(folder.label). Its songs stay in the content list.")
            return
        }
        let walked: Set<String> = Set(
            collectAudioFiles(rootPath: url.path, childrenOf: { FolderAccess.children(of: $0) ?? [] }).map { $0.path },
        )
        store.removeFolder(folder, importedPayloads: walked)
        clearFolderSelection()
        event("Removed \(folder.label) and its imported songs. The files on your device are untouched.")
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

    // MARK: - content-list views (the play-url grant + the list sorts)

    /// The paths whose security scope is already asserted (the
    /// process-lifetime access grant, like the Android app's held grants)
    /// and the lock guarding that set.
    nonisolated(unsafe) private static let accessPathLock = NSLock()
    nonisolated(unsafe) private static var accessedPaths: Set<String> = []

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
            Self.assertAccess(path: entry.payload, bookmark: bookmark)
        } else if let folderId = entry.folderId,
                  let folder = store.folderById(folderId),
                  !folder.bookmark.isEmpty
        {
            Self.assertAccess(path: folder.uri, bookmark: folder.bookmark)
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
        sortedByTitle(store.folders, title: { $0.label }, id: { $0.id }, direction: contentSort())
    }

    /// The content pane's Individual content section (the unmarked rows -
    /// folder-imported songs stay OUT of it), sorted by label through the
    /// shared content sort.
    public var sortedIndividualEntries: [Entry] {
        sortedByTitle(store.individualEntries, title: { $0.label }, id: { $0.id }, direction: contentSort())
    }

    /// A folder's imported songs (its stored rows, insertion order - the
    /// folder page's tree groups them).
    public func folderSongs(_ folder: Folder) -> [Entry] {
        store.entriesForFolder(folder.id)
    }
}
