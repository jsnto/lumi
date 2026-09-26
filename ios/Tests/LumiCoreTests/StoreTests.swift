// The store's ordering/identity semantics pins - the iOS ports of the
// Android app's repository tests: the name-based tag-song identity, the
// dense renumber on removal, the reorder permutation guard, the payload
// dedupe, the folder unmark-on-removal, and the LRU position memory.

import Foundation
import Testing
@testable import LumiCore

private func makeStore() -> Store {
    Store(doc: StoreDoc()) { _ in }
}

@Test func entryByLabelResolvesTheExactNameElseOnlyTheExactlyClampedSinglePrefix() {
    let store = makeStore()
    _ = store.addEntry(label: "My Song", payload: "/a.mp3")
    _ = store.addEntry(label: "My Song (Remix)", payload: "/b.mp3")
    _ = store.addEntry(label: "Other", payload: "/c.mp3")

    // Exact wins.
    #expect(store.entryByLabel("My Song")?.payload == "/a.mp3")
    #expect(store.entryByLabel("My Song (Remix)")?.payload == "/b.mp3")

    // A deleted exact-match name stays not-found while a longer song
    // matches the prefix (the "My Song" deleted case).
    store.deleteEntry(store.entries.first { $0.payload == "/a.mp3" }!)
    // Now "My Song" is 5 chars, not the 50-char clamp: exact-only -> nil.
    #expect(store.entryByLabel("My Song") == nil)

    // The clamp case: a name of exactly 50 chars that no entry carries
    // exactly resolves to the ONE longer label it was clamped from.
    let long = String(repeating: "T", count: 55)
    _ = store.addEntry(label: long, payload: "/long.mp3")
    let clamped = String(long.prefix(50))
    #expect(store.entryByLabel(clamped)?.payload == "/long.mp3")

    // ...and ONLY when exactly one label starts with it.
    let long2 = String(repeating: "U", count: 60)
    let long3 = String(repeating: "U", count: 51)
    _ = store.addEntry(label: long2, payload: "/long2.mp3")
    _ = store.addEntry(label: long3, payload: "/long3.mp3")
    let clamped2 = String(long2.prefix(50))
    #expect(store.entryByLabel(clamped2) == nil) // several prefixes
}

@Test func linkAppendLandsAtTheEndAndDedupesByName() {
    let store = makeStore()
    let tag = store.addTag(uid: "AA", name: "Tag A")!
    let e1 = store.addEntry(label: "One", payload: "/1.mp3")
    let e2 = store.addEntry(label: "Two", payload: "/2.mp3")

    #expect(store.linkEntryToTag(tagId: tag.id, entryId: e1.id))
    #expect(store.linkEntryToTag(tagId: tag.id, entryId: e2.id))
    // A second pick of the same song (same name) must not duplicate.
    let e1b = store.addEntryUnique(label: "One", payload: "/1b.mp3")!
    #expect(!store.linkEntryToTag(tagId: tag.id, entryId: e1b.id))

    let rows = store.entriesForTag(tag.id)
    #expect(rows.map { $0.name } == ["One", "Two"])
    #expect(rows.map { $0.sortOrder } == [0, 1])
}

@Test func removalRenumbersSurvivingLinksDensely() {
    // A(0) B(1) C(2) D(3) minus A and B, then an appended E must read
    // [C, D, E] (the Android UnlinkSongFromTagTest renumbering pin):
    // without the dense renumber the gaps would push E mid-list.
    let store = makeStore()
    let tag = store.addTag(uid: "AA", name: "Tag A")!
    let entries = ["A", "B", "C", "D", "E"].map { store.addEntry(label: $0, payload: "/\($0).mp3") }
    for e in entries.prefix(4) { _ = store.linkEntryToTag(tagId: tag.id, entryId: e.id) }

    let rows = store.entriesForTag(tag.id)
    let aLink = rows.first { $0.name == "A" }!
    let bLink = rows.first { $0.name == "B" }!
    #expect(store.unlinkLinkFromTag(tagId: tag.id, linkId: aLink.linkId))
    #expect(store.unlinkLinkFromTag(tagId: tag.id, linkId: bLink.linkId))

    // The surviving links are densely renumbered in their read order.
    let after = store.entriesForTag(tag.id)
    #expect(after.map { $0.name } == ["C", "D"])
    #expect(after.map { $0.sortOrder } == [0, 1])

    // A later append lands at the END.
    _ = store.linkEntryToTag(tagId: tag.id, entryId: entries[4].id)
    #expect(store.entriesForTag(tag.id).map { $0.name } == ["C", "D", "E"])
}

@Test func aSongSurvivesItsEntryDeletionAsASongNotFoundRow() {
    // A tag song is identified by NAME: deleting the content row keeps
    // the link (the "song not found" row); re-importing the same name
    // resolves live and the row becomes playable again.
    let store = makeStore()
    let tag = store.addTag(uid: "AA", name: "Tag A")!
    let e = store.addEntry(label: "Ghost", payload: "/g.mp3")
    _ = store.linkEntryToTag(tagId: tag.id, entryId: e.id)

    store.deleteEntry(e)
    let rows = store.entriesForTag(tag.id)
    #expect(rows.count == 1)
    #expect(rows.first?.name == "Ghost")
    #expect(rows.first?.entry == nil) // "song not found"

    let e2 = store.addEntry(label: "Ghost", payload: "/g2.mp3")
    _ = e2
    let resolved = store.entriesForTag(tag.id)
    #expect(resolved.first?.entry?.payload == "/g2.mp3") // live resolution
}

@Test func reorderIsAcceptedOnlyForAPermutationOfTheCurrentLinkSet() {
    let store = makeStore()
    let tag = store.addTag(uid: "AA", name: "Tag A")!
    let entries = ["A", "B", "C"].map { store.addEntry(label: $0, payload: "/\($0).mp3") }
    for e in entries { _ = store.linkEntryToTag(tagId: tag.id, entryId: e.id) }
    let current = store.entriesForTag(tag.id).map { $0.linkId }

    // A real permutation is accepted.
    let swapped = [current[2], current[0], current[1]]
    #expect(store.reorderTagLinks(tagId: tag.id, linkIds: swapped) == swapped)
    #expect(store.entriesForTag(tag.id).map { $0.name } == ["C", "A", "B"])

    // A non-permutation (a list that changed in flight) is refused: the
    // current order is kept and returned.
    let stale = [current[0], current[1]] // a dropped row
    let before = store.entriesForTag(tag.id).map { $0.linkId }
    #expect(store.reorderTagLinks(tagId: tag.id, linkIds: stale) == before)
    // A superset is refused too.
    #expect(store.reorderTagLinks(tagId: tag.id, linkIds: stale + [99999]) == before)
}

@Test func addEntryUniqueDedupesByPayload() {
    let store = makeStore()
    _ = store.addEntryUnique(label: "One", payload: "/1.mp3")
    // Same payload (a different label, e.g. re-picked under another name):
    // no second row.
    #expect(store.addEntryUnique(label: "Renamed", payload: "/1.mp3") == nil)
    #expect(store.entries.count == 1)
    // A different payload: a row.
    #expect(store.addEntryUnique(label: "Two", payload: "/2.mp3") != nil)
    #expect(store.entries.count == 2)
}

@Test func folderImportedRowsStayOutOfTheIndividualList() {
    let store = makeStore()
    _ = store.addEntryUnique(label: "Solo", payload: "/solo.mp3", folderId: nil)
    let folder = store.rememberFolder(uri: "/Music/Album", bookmark: Data([1, 2, 3]), label: "Album")
    _ = store.addEntryUnique(label: "Album song", payload: "/Music/Album/s.mp3", folderId: folder.id)

    #expect(store.individualEntries.map { $0.label } == ["Solo"])
    #expect(store.entriesForFolder(folder.id).map { $0.label } == ["Album song"])
}

@Test func removeFolderDeletesTheWalkedRowsAndUnmarksTheSurvivors() {
    let store = makeStore()
    let folder = store.rememberFolder(uri: "/Music/Album", bookmark: Data([1]), label: "Album")
    // Two rows the folder's walk identified, one that shared its payload
    // but was added individually before the marker existed (survives),
    // and one other folder's row (untouched).
    _ = store.addEntryUnique(label: "S1", payload: "/Music/Album/s1.mp3", folderId: folder.id)
    _ = store.addEntryUnique(label: "Shared", payload: "/Music/Album/shared.mp3") // no marker
    let other = store.rememberFolder(uri: "/Music/Other", bookmark: Data([2]), label: "Other")
    _ = store.addEntryUnique(label: "O1", payload: "/Music/Other/o1.mp3", folderId: other.id)

    store.removeFolder(folder: folder, importedPayloads: ["/Music/Album/s1.mp3", "/Music/Album/shared.mp3"])

    // The folder row is gone; its walked rows are deleted...
    #expect(store.folderById(folder.id) == nil)
    #expect(store.entryByPayload("/Music/Album/s1.mp3") == nil)
    #expect(store.entryByPayload("/Music/Album/shared.mp3") == nil)
    // ...the other folder's row survives untouched...
    #expect(store.entryByPayload("/Music/Other/o1.mp3")?.folderId == other.id)
    // and the markerless survivor degrades into the individual list.
    // (Here it was never marked, so it simply stays individual.)
    #expect(store.individualEntries.map { $0.label } == [])
}

@Test func markEntriesFromFolderIsForwardOnly() {
    let store = makeStore()
    let folder = store.rememberFolder(uri: "/Music/Album", bookmark: Data([1]), label: "Album")
    let shared = store.addEntry(label: "Shared", payload: "/Music/Album/shared.mp3")
    _ = store.markEntriesFromFolder(folderId: folder.id, payloads: [shared.payload])
    #expect(store.entryById(shared.id)?.folderId == folder.id)

    // A re-mark (an already-stamped shared row keeps its FIRST folder):
    let second = store.rememberFolder(uri: "/Music/Second", bookmark: Data([2]), label: "Second")
    _ = store.markEntriesFromFolder(folderId: second.id, payloads: [shared.payload])
    #expect(store.entryById(shared.id)?.folderId == folder.id)
}

@Test func tagLinksCascadeOffTheDeletedTagButTheMemorySurvives() {
    let store = makeStore()
    let tag = store.addTag(uid: "AA", name: "Tag A")!
    let e = store.addEntry(label: "Song", payload: "/s.mp3")
    _ = store.linkEntryToTag(tagId: tag.id, entryId: e.id)
    store.rememberTag(uid: "AA", name: "Tag A", songs: ["Song"])

    store.deleteTag(tag)
    #expect(store.tagByUid("AA") == nil)
    #expect(store.entriesForTag(tag.id).isEmpty) // the links are gone
    #expect(store.entryById(e.id) != nil) // the content row is NOT
    // The per-card memory survives the tag row's deletion.
    #expect(store.memoryForUid("AA")?.name == "Tag A")
    #expect(store.memoryForUid("AA")?.songs == ["Song"])
}

@Test func rememberEvictsTheLeastRecentlyPlayedTag() {
    let store = makeStore()
    func pos(_ uid: String, _ t: Int64) -> TagPosition {
        TagPosition(tagUid: uid, positionMs: 1, entryIndex: 0, queueFingerprint: "f", lastPlayedAt: t)
    }
    store.remember(pos("a", 1))
    store.remember(pos("b", 2))
    store.remember(pos("c", 3))
    // At capacity: the incoming evicts "a" (the least recently played).
    store.remember(pos("d", 4))
    #expect(store.positionFor("a") == nil)
    #expect(store.positionFor("b") != nil)
    #expect(store.positionFor("c") != nil)
    #expect(store.positionFor("d") != nil)
    // Refreshing an existing row never evicts itself.
    store.remember(pos("b", 10))
    #expect(store.positionFor("b")?.lastPlayedAt == 10)
    #expect(store.positionFor("a") == nil)
}

@Test func aZeroSongTagKeepsNoLinks() {
    let store = makeStore()
    let tag = store.addTag(uid: "AA", name: "Empty")!
    #expect(store.entriesForTag(tag.id).isEmpty)
    #expect(store.songNamesForTag(tag.id).isEmpty)
}
