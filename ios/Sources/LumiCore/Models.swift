// The persisted model of the app - the iOS counterpart of the Android
// app's Room entities (android/.../data/Entities.kt). Same identity rules:
// a tag is its NFC uid, a tag song is a NAME (resolved live against the
// content), a folder is its picked path, and a content entry is deduped by
// its payload (the file url it was added from).

import Foundation

/// A piece of content that can be assigned to a tag.
///
/// `payload` is the file's own url (a `file://` url on iOS - the iOS
/// counterpart of the Android content uri); `label` is the song's display
/// name (its title). `folderId` marks a row imported from a folder (that
/// folder's `Folder.id`): such songs stay OUT of the individual content
/// list - they are visible only inside their folder's page. Rows the user
/// added one by one carry no marker.
public struct Entry: Codable, Hashable, Identifiable, Sendable {
    public var id: Int
    public var label: String
    public var payload: String
    /// Insertion time (unix millis) - the content list's tie-break order.
    public var createdAt: Int64
    public var folderId: Int?
    /// The security-scoped bookmark granting access to this file across
    /// launches (the iOS counterpart of the Android app's per-file SAF
    /// read grant). Empty/absent for folder-imported rows: their file
    /// paths live under the folder's own tree, whose bookmark governs.
    public var bookmark: Data? = nil

    public init(id: Int, label: String, payload: String, createdAt: Int64, folderId: Int? = nil, bookmark: Data? = nil) {
        self.id = id
        self.label = label
        self.payload = payload
        self.createdAt = createdAt
        self.folderId = folderId
        self.bookmark = bookmark
    }
}

/// A folder the user added as content, remembered so it can be rescanned
/// for audio files added since the last scan.
///
/// `uri` is the folder's own path - the scan root - and is unique: re-picking
/// the same folder does not create a second row. `bookmark` is the
/// security-scoped bookmark (the iOS counterpart of the Android SAF
/// persistent read grant) that keeps access to the folder across launches.
public struct Folder: Codable, Hashable, Identifiable, Sendable {
    public var id: Int
    public var uri: String
    public var bookmark: Data
    public var label: String
    public var createdAt: Int64

    public init(id: Int, uri: String, bookmark: Data, label: String, createdAt: Int64) {
        self.id = id
        self.uri = uri
        self.bookmark = bookmark
        self.label = label
        self.createdAt = createdAt
    }
}

/// A registered NFC tag, identified by its NFC UID.
public struct Tag: Codable, Hashable, Identifiable, Sendable {
    public var id: Int
    public var uid: String
    public var name: String
    public var createdAt: Int64

    public init(id: Int, uid: String, name: String, createdAt: Int64) {
        self.id = id
        self.uid = uid
        self.name = name
        self.createdAt = createdAt
    }
}

/// Ordered association from a tag to a song. A tag's content list is the set
/// of links sorted by `sortOrder`.
///
/// A tag song is identified by NAME, not by the linked entry row: `songName`
/// is a snapshot of the song's display name taken when the link was made,
/// and the row is playable while a content entry with that name exists
/// (evaluated live - a song deleted from the library and later re-imported
/// under the same name becomes playable again without re-adding it). A name
/// that resolves to no content shows on the tag's page as the "song not
/// found" row, rendering its name from this snapshot.
///
/// `entryId` is NOT a reference the deletion relies on - it is only a
/// provenance hint (the entry the link was made from), so a link survives
/// its entry's deletion: the song keeps its place on the tag's list as a
/// "song not found" row instead of vanishing from it. It is nil for links
/// restored from a name list without a live entry.
public struct TagLink: Codable, Hashable, Identifiable, Sendable {
    public var id: Int
    public var tagId: Int
    public var entryId: Int?
    public var songName: String
    public var sortOrder: Int

    public init(id: Int, tagId: Int, entryId: Int?, songName: String, sortOrder: Int) {
        self.id = id
        self.tagId = tagId
        self.entryId = entryId
        self.songName = songName
        self.sortOrder = sortOrder
    }
}

/// A row of a tag's song list as the UI sees it: the stored link plus the
/// live-resolved content entry (nil while the song is missing from the
/// content list - the "song not found" row).
public struct TagSongRow: Hashable, Sendable {
    public let linkId: Int
    public let entryId: Int?
    public let sortOrder: Int
    public let name: String
    public let entry: Entry?

    public init(linkId: Int, entryId: Int?, sortOrder: Int, name: String, entry: Entry?) {
        self.linkId = linkId
        self.entryId = entryId
        self.sortOrder = sortOrder
        self.name = name
        self.entry = entry
    }
}

/// The last saved name and song list for a card uid - the per-card memory
/// that survives the tag row's deletion (the Android app's `tag_memory`
/// table). Written on registration and on a successful card write; the
/// restore source for a re-scanned card whose payload carries no song list.
public struct TagMemoryRecord: Codable, Hashable, Sendable {
    public var name: String
    public var songs: [String]

    public init(name: String, songs: [String]) {
        self.name = name
        self.songs = songs
    }
}

/// The last played position of an NFC tag's content list (the Android
/// app's `tag_positions` row) - see PlaybackPosition.swift for the restore
/// decision.
public struct TagPosition: Codable, Hashable, Sendable {
    public var tagUid: String
    /// Offset in milliseconds within the entry at `entryIndex`.
    public var positionMs: Int64
    /// Index of the entry in the tag's linked list the offset applies to.
    public var entryIndex: Int
    public var queueFingerprint: String
    public var lastPlayedAt: Int64

    public init(
        tagUid: String,
        positionMs: Int64,
        entryIndex: Int,
        queueFingerprint: String,
        lastPlayedAt: Int64,
    ) {
        self.tagUid = tagUid
        self.positionMs = positionMs
        self.entryIndex = entryIndex
        self.queueFingerprint = queueFingerprint
        self.lastPlayedAt = lastPlayedAt
    }
}

/// One playable unit of the player's queue - the iOS counterpart of the
/// Android app's `EntrySpec` (the service's start extra).
public struct EntrySpec: Codable, Hashable, Sendable {
    /// "audio" - the app is audio-only (the Android app keeps its extensible
    /// entry types; new non-audio types are skipped by the player, same here).
    public var type: String
    public var label: String
    /// The file url to play.
    public var payload: String

    public init(type: String, label: String, payload: String) {
        self.type = type
        self.label = label
        self.payload = payload
    }
}

/// The app's stored document: every table in one persisted JSON document
/// (the iOS counterpart of the Room database).
public struct StoreDoc: Codable, Sendable {
    public var tags: [Tag] = []
    public var entries: [Entry] = []
    public var folders: [Folder] = []
    public var links: [TagLink] = []
    public var memory: [String: TagMemoryRecord] = [:]
    public var positions: [String: TagPosition] = [:]
    public var settings: [String: String] = [:]

    public init() {}
}
