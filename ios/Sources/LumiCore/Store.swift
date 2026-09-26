// The app's persisted store - the iOS counterpart of the Android app's Room
// database + com.lumi.player.data.LumiRepository. One JSON document (the
// [StoreDoc] tables) persisted to the app's support directory; every
// mutation goes through [Store.mutate], which persists and notifies.
//
// The ordering semantics are the Android app's, ported one-for-one:
// - a tag's content list is its links sorted by sortOrder;
// - a tag song is identified by NAME (resolved live against the content -
//   the first stored entry with the name wins, a deleted-then-reimported
//   same-named song resolves live and becomes playable again);
// - a link append lands at sortOrder = the tag's current link count;
// - a removal renumbers the surviving links densely to 0..n-1 in their
//   current read order (without the renumbering the gaps a removal leaves
//   would push a later append mid-list);
// - a reorder is accepted only when it is a permutation of the current
//   link set (a list that changed in flight is refused and the current
//   order kept).

import Foundation

/// The store's persistence sink: receives the full document after every
/// mutation. The default (file-backed) store writes it to a JSON file;
/// tests inject a no-op or an in-memory sink.
public typealias PersistSink = (StoreDoc) -> Void

public final class Store {
    private var doc: StoreDoc
    private let persist: PersistSink
    /// Called after every mutation that changes observed data.
    public var onChange: (() -> Void)?

    public var nowMillis: () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }

    public init(doc: StoreDoc = StoreDoc(), persist: @escaping PersistSink = { _ in }) {
        self.doc = doc
        self.persist = persist
    }

    // MARK: - observation

    public var tags: [Tag] { doc.tags.sorted { $0.createdAt < $1.createdAt || ($0.createdAt == $1.createdAt && $0.id < $1.id) } }
    public var entries: [Entry] { doc.entries } // insertion order
    public var individualEntries: [Entry] { doc.entries.filter { $0.folderId == nil } }
    public var folders: [Folder] { doc.folders.sorted { $0.createdAt < $1.createdAt } }
    public func entriesForFolder(_ folderId: Int) -> [Entry] {
        doc.entries.filter { $0.folderId == folderId }
    }

    public func tagByUid(_ uid: String) -> Tag? { doc.tags.first { $0.uid == uid.trimmingCharacters(in: .whitespacesAndNewlines) } }
    public func entryByPayload(_ payload: String) -> Entry? { doc.entries.first { $0.payload == payload } }
    public func entryById(_ id: Int) -> Entry? { doc.entries.first { $0.id == id } }
    public func tagById(_ id: Int) -> Tag? { doc.tags.first { $0.id == id } }
    public func folderById(_ id: Int) -> Folder? { doc.folders.first { $0.id == id } }
    public func folderByUri(_ uri: String) -> Folder? { doc.folders.first { $0.uri == uri } }
    public var allEntryPayloads: Set<String> { Set(doc.entries.map { $0.payload }) }

    // MARK: - mutation core

    @discardableResult
    private func mutate(_ block: (inout StoreDoc) -> Bool) -> Bool {
        let changed = block(&doc)
        if changed {
            persist(doc)
            onChange?()
        }
        return changed
    }

    private func nextId(_ ids: [Int]) -> Int { (ids.map { $0 }.max() ?? 0) + 1 }

    // MARK: - tags

    /// Stores a new tag, or returns nil when a tag with the same uid is
    /// already registered.
    @discardableResult
    public func addTag(uid: String, name: String) -> Tag? {
        let uid = uid.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !uid.isEmpty, !name.isEmpty else { return nil }
        if doc.tags.contains(where: { $0.uid == uid }) { return nil }
        var made: Tag?
        mutate { d in
            let tag = Tag(id: self.nextId(d.tags.map { $0.id }), uid: uid, name: name, createdAt: self.nowMillis())
            d.tags.append(tag)
            made = tag
            return true
        }
        return made
    }

    /// Renames a registered tag; a no-op when the uid is unknown.
    public func renameTag(uid: String, name: String) {
        let uid = uid.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        mutate { d in
            guard let i = d.tags.firstIndex(where: { $0.uid == uid }) else { return false }
            d.tags[i].name = name
            return true
        }
    }

    /// Deletes a tag and its song links (the links cascade off the tag).
    /// The per-card memory record is KEPT - it survives the tag row's
    /// deletion, exactly like the Android table with no foreign key.
    public func deleteTag(_ tag: Tag) {
        mutate { d in
            guard let i = d.tags.firstIndex(where: { $0.id == tag.id }) else { return false }
            d.tags.remove(at: i)
            d.links.removeAll { $0.tagId == tag.id }
            return true
        }
    }

    // MARK: - entries

    /// Stores a new entry. [folderId] marks the row as imported from that
    /// folder (the folder-import path); individually added rows store with
    /// no marker. [bookmark] persists the file's access grant (empty for
    /// folder-imported rows: the folder's bookmark governs).
    @discardableResult
    public func addEntry(
        label: String,
        payload: String,
        folderId: Int? = nil,
        bookmark: Data? = nil,
    ) -> Entry {
        var made: Entry!
        mutate { d in
            let entry = Entry(
                id: self.nextId(d.entries.map { $0.id }),
                label: label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "Untitled" : label.trimmingCharacters(in: .whitespacesAndNewlines),
                payload: payload,
                createdAt: self.nowMillis(),
                folderId: folderId,
                bookmark: bookmark,
            )
            d.entries.append(entry)
            made = entry
            return true
        }
        return made
    }

    /// Stores a new entry, or returns nil when an entry with the same
    /// payload is already stored - so re-picking a file, or re-adding a
    /// folder whose songs are already in the content list, adds no
    /// duplicate rows.
    public func addEntryUnique(
        label: String,
        payload: String,
        folderId: Int? = nil,
        bookmark: Data? = nil,
    ) -> Entry? {
        if entryByPayload(payload) != nil { return nil }
        return addEntry(label: label, payload: payload, folderId: folderId, bookmark: bookmark)
    }

    public func deleteEntry(_ entry: Entry) {
        mutate { d in
            guard let i = d.entries.firstIndex(where: { $0.id == entry.id }) else { return false }
            d.entries.remove(at: i)
            return true
        }
    }

    /// The live content entry a tag-song NAME resolves to, when it
    /// resolves to any - the port of the Android repository's entryByLabel:
    /// an exact stored name wins; a name of exactly the 50-char clamp that
    /// no entry carries exactly resolves to the one longer stored label it
    /// was clamped from, and ONLY when exactly one label starts with it
    /// (zero or several keep the name a visible "song not found"); a name
    /// under the clamp is exact-only (a short name is never a clamp
    /// product, so a prefix hit would silently link a different song).
    public func entryByLabel(_ label: String) -> Entry? {
        let label = label.trimmingCharacters(in: .whitespacesAndNewlines)
        if label.isEmpty { return nil }
        if let exact = doc.entries.first(where: { $0.label == label }) { return exact }
        if label.count != NdefCodec.maxSongNameLength { return nil }
        let prefixMatches = doc.entries.filter { $0.label.hasPrefix(label) }
        return prefixMatches.count == 1 ? prefixMatches.first : nil
    }

    // MARK: - links (the tag's ordered song list)

    /// A tag's current song list in link order - name-based semantics:
    /// every link comes back at its original sort position, resolved to
    /// the first stored content entry with its song name, or to nothing
    /// (the "song not found" row) when no content entry carries the name.
    public func entriesForTag(_ tagId: Int) -> [TagSongRow] {
        var byName: [String: Entry] = [:]
        for entry in doc.entries where byName[entry.label] == nil {
            byName[entry.label] = entry // the first stored copy wins
        }
        let links = doc.links
            .filter { $0.tagId == tagId }
            .sorted { $0.sortOrder < $1.sortOrder || ($0.sortOrder == $1.sortOrder && $0.id < $1.id) }
        return links.map { link in
            TagSongRow(
                linkId: link.id,
                entryId: link.entryId,
                sortOrder: link.sortOrder,
                name: link.songName,
                entry: byName[link.songName],
            )
        }
    }

    /// The tag's ordered song NAMES (its links' name snapshots, in link
    /// order, "song not found" names among them) - the card payload's song
    /// list and the per-card memory's song list.
    public func songNamesForTag(_ tagId: Int) -> [String] {
        entriesForTag(tagId).map { $0.name }
    }

    /// Links an ALREADY-STORED entry to the end of the tag's song list -
    /// the in-app add-song flow (a tag's list is an assignment over the
    /// app's existing content, not a store of new files). The link stores
    /// the entry's display name as the song's identity. Returns false when
    /// the tag or the entry no longer exists, or when the tag already
    /// links a song with the entry's name (a second pick of the same song
    /// must not duplicate the link).
    @discardableResult
    public func linkEntryToTag(tagId: Int, entryId: Int) -> Bool {
        mutate { d in
            guard let tag = d.tags.first(where: { $0.id == tagId }),
                let entry = d.entries.first(where: { $0.id == entryId })
            else { return false }
            let alreadyLinked = d.links.contains { $0.tagId == tagId && $0.songName == entry.label }
            if alreadyLinked { return false }
            d.links.append(
                TagLink(
                    id: self.nextId(d.links.map { $0.id }),
                    tagId: tag.id,
                    entryId: entry.id,
                    songName: entry.label,
                    sortOrder: d.links.filter { $0.tagId == tag.id }.count,
                ),
            )
            return true
        }
    }

    /// Appends a name-only link to the end of the tag's song list - the
    /// restore path (a re-scanned card's remembered or carried song list
    /// landing on a freshly re-registered tag). [songName] is the song's
    /// identity; [entryId] is the entry the name currently resolves to,
    /// when it does (the provenance hint), and nil when the name shows as
    /// "song not found". A no-op when the tag is gone.
    public func insertLinkByName(tagId: Int, songName: String, entryId: Int?) -> Bool {
        mutate { d in
            guard d.tags.contains(where: { $0.id == tagId }) else { return false }
            d.links.append(
                TagLink(
                    id: self.nextId(d.links.map { $0.id }),
                    tagId: tagId,
                    entryId: entryId,
                    songName: songName,
                    sortOrder: d.links.filter { $0.tagId == tagId }.count,
                ),
            )
            return true
        }
    }

    /// Unlinks ONE link from ONE tag - the inverse of linkEntryToTag:
    /// only that link (addressed by its stored link id) goes. The song
    /// stays in the content list, its links to any other tags are
    /// untouched, and the remaining songs keep their relative order -
    /// their sortOrder values are renumbered densely to 0..n-1 in the
    /// current read order, so a later append (sortOrder = the tag's link
    /// count) still lands at the end. Returns false when the tag no longer
    /// exists or it carries no such link.
    @discardableResult
    public func unlinkLinkFromTag(tagId: Int, linkId: Int) -> Bool {
        mutate { d in
            guard d.tags.contains(where: { $0.id == tagId }) else { return false }
            let current = d.links
                .filter { $0.tagId == tagId }
                .sorted { $0.sortOrder < $1.sortOrder || ($0.sortOrder == $1.sortOrder && $0.id < $1.id) }
            guard current.contains(where: { $0.id == linkId }) else { return false }
            let next = current.filter { $0.id != linkId }
            d.links.removeAll { $0.tagId == tagId }
            for (index, link) in next.enumerated() {
                d.links.append(
                    TagLink(id: link.id, tagId: tagId, entryId: link.entryId, songName: link.songName, sortOrder: index),
                )
            }
            return true
        }
    }

    /// Replaces the tag's ordered song list with [linkIds] (the drag
    /// reorder): the ids in the order they settle to. Only a permutation of
    /// the current link set is a reorder; a list that changed in flight
    /// (a link added or removed between the read and the write) is refused
    /// and the current order kept. Returns the list the write settled on.
    @discardableResult
    public func reorderTagLinks(tagId: Int, linkIds: [Int]) -> [Int] {
        let current = entriesForTag(tagId).map { $0.linkId }
        guard let tag = tagById(tagId) else { return current }
        _ = tag
        if linkIds.count != current.count || Set(linkIds) != Set(current) || Set(linkIds).count != linkIds.count {
            return current // not a permutation: refuse, keep the current order
        }
        mutate { d in
            let links = d.links
                .filter { $0.tagId == tagId }
                .sorted { $0.sortOrder < $1.sortOrder || ($0.sortOrder == $1.sortOrder && $0.id < $1.id) }
            d.links.removeAll { $0.tagId == tagId }
            for (index, id) in linkIds.enumerated() {
                if let link = links.first(where: { $0.id == id }) {
                    d.links.append(
                        TagLink(id: link.id, tagId: tagId, entryId: link.entryId, songName: link.songName, sortOrder: index),
                    )
                }
            }
            return true
        }
        return linkIds
    }

    // MARK: - folders

    /// Remembers a picked folder so it can be rescanned later. Re-remembering
    /// the same path is a no-op that returns the existing row, so
    /// re-picking a folder adds no duplicate.
    @discardableResult
    public func rememberFolder(uri: String, bookmark: Data, label: String) -> Folder {
        var made: Folder!
        mutate { d in
            if let existing = d.folders.first(where: { $0.uri == uri }) {
                made = existing
                return false
            }
            let folder = Folder(
                id: self.nextId(d.folders.map { $0.id }),
                uri: uri,
                bookmark: bookmark,
                label: label,
                createdAt: self.nowMillis(),
            )
            d.folders.append(folder)
            made = folder
            return true
        }
        return made
    }

    /// Stores the folder and its scanned songs in one mutation (the add
    /// flow): the folder is remembered (deduped by uri) and every scanned
    /// song stored through addEntryUnique's payload dedup with the folder
    /// marker stamped. Returns (folder, number of NEW songs stored).
    @discardableResult
    public func addFolderEntry(uri: String, bookmark: Data, label: String, songs: [ScannedSong]) -> (folder: Folder, added: Int) {
        var folder: Folder!
        var added = 0
        mutate { d in
            let f: Folder
            if let existing = d.folders.first(where: { $0.uri == uri }) {
                f = existing
            } else {
                f = Folder(id: self.nextId(d.folders.map { $0.id }), uri: uri, bookmark: bookmark, label: label, createdAt: self.nowMillis())
                d.folders.append(f)
            }
            folder = f
            for song in songs {
                guard !d.entries.contains(where: { $0.payload == song.path }) else { continue }
                d.entries.append(
                    Entry(
                        id: self.nextId(d.entries.map { $0.id }),
                        label: song.displayName,
                        payload: song.path,
                        createdAt: self.nowMillis(),
                        folderId: f.id,
                    ),
                )
                added += 1
            }
            return true
        }
        return (folder, added)
    }

    /// Marks the stored rows whose payload is in [payloads] as imported
    /// from [folderId], without re-marking rows that already carry a folder
    /// marker (forward-only - an already-stamped shared row keeps its first
    /// folder). Returns the number of rows marked.
    @discardableResult
    public func markEntriesFromFolder(folderId: Int, payloads: Set<String>) -> Int {
        var marked = 0
        mutate { d in
            for i in d.entries.indices where d.entries[i].folderId == nil && payloads.contains(d.entries[i].payload) {
                d.entries[i].folderId = folderId
                marked += 1
            }
            return marked > 0
        }
        return marked
    }

    /// Removes a remembered folder and the content rows its walk identified
    /// (the same payload match the rescan uses): the rows leave the content
    /// list, the folder row goes, and a tag's link to a deleted row
    /// SURVIVES the deletion (the song keeps its place on the tag's list as
    /// a "song not found" row). A row the folder's walk did not identify is
    /// untouched; rows that survive (another folder also imports them, or
    /// the tree can no longer be read and the walk is empty) are UNMARKED,
    /// so they degrade into the individual content list. Returns the number
    /// of content rows deleted.
    @discardableResult
    public func removeFolder(folder: Folder, importedPayloads: Set<String>) -> Int {
        var removed = 0
        mutate { d in
            var kept = d.entries.filter { !importedPayloads.contains($0.payload) }
            removed = d.entries.count - kept.count
            for i in kept.indices where kept[i].folderId == folder.id {
                kept[i].folderId = nil // unmark the survivors
            }
            d.entries = kept
            d.folders.removeAll { $0.id == folder.id }
            return true
        }
        return removed
    }

    // MARK: - per-card memory (survives the tag row's deletion)

    public func memoryForUid(_ uid: String) -> TagMemoryRecord? {
        doc.memory[uid.trimmingCharacters(in: .whitespacesAndNewlines)]
    }

    /// Remembers the last saved name + song list for the card uid [uid] -
    /// written on registration and on a successful card write. Deleting a
    /// tag row never touches the record, so re-scanning the same card later
    /// can restore it.
    public func rememberTag(uid: String, name: String, songs: [String]) {
        let uid = uid.trimmingCharacters(in: .whitespacesAndNewlines)
        mutate { d in
            d.memory[uid] = TagMemoryRecord(name: name, songs: songs)
            return true
        }
    }

    // MARK: - remembered positions (per tag, LRU 3)

    public func positionFor(_ uid: String) -> TagPosition? {
        doc.positions[uid.trimmingCharacters(in: .whitespacesAndNewlines)]
    }

    /// Records [entity], evicting the oldest rows so the capacity is never
    /// exceeded (LRU - see lruEvictions).
    public func remember(_ entity: TagPosition) {
        mutate { d in
            for uid in lruEvictions(existing: Array(d.positions.values), incoming: entity) {
                d.positions.removeValue(forKey: uid)
            }
            d.positions[entity.tagUid] = entity
            return true
        }
    }

    // MARK: - settings

    public func setting(_ key: String) -> String? { doc.settings[key] }

    @discardableResult
    public func setSetting(_ key: String, _ value: String) -> Bool {
        mutate { d in
            d.settings[key] = value
            return true
        }
    }
}
