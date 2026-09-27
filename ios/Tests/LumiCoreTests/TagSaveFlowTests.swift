// The tag save-flow's data-layer behavior pins - the iOS port of the
// Android app's TagSaveFlowTest: the tags list's name sort (case-
// insensitive with the stable tie-break) and the compare scan's
// memory clear (a blank card's verdict), plus the write's IN SYNC
// landing (a successful write records the memory, so the indicator
// recomputes to in-sync).

import Foundation
import Testing
@testable import LumiCore

private func makeStore() -> Store {
    Store(doc: StoreDoc()) { _ in }
}

@Test func theTagsListIsSortedByTagNameCaseInsensitively() {
    let store = makeStore()
    // Added out of order, mixed case, with an equal name (the tie-break:
    // the earlier-created row reads first - a shared creation instant, so
    // the id tie-break orders them).
    _ = store.addTag(uid: "BB11", name: "bravo")
    _ = store.addTag(uid: "AA01", name: "Alpha")
    _ = store.addTag(uid: "CC22", name: "charlie")
    _ = store.addTag(uid: "DD33", name: "alpha")
    #expect(store.tags.map(\.name) == ["Alpha", "alpha", "bravo", "charlie"])
}

@Test func equalNamesTieBreakToTheEarlierCreatedTag() {
    var now: Int64 = 1000
    let store = makeStore()
    store.nowMillis = { now }
    _ = store.addTag(uid: "ZZ99", name: "Hallway")
    now = 2000
    _ = store.addTag(uid: "AA01", name: "hallway")
    // Case-insensitive tie: the earlier-created tag reads first.
    #expect(store.tags.map(\.uid) == ["ZZ99", "AA01"])
}

@Test func forgetTagClearsThePerUidMemoryRecord() {
    let store = makeStore()
    store.rememberTag(uid: "UID1", name: "Porch", songs: ["One"])
    #expect(store.memoryForUid("UID1")?.songs == ["One"])
    // The compare scan's blank-card clear: the record goes.
    #expect(store.forgetTag(uid: "UID1"))
    #expect(store.memoryForUid("UID1") == nil)
    // A uid with no record is a no-op.
    #expect(!store.forgetTag(uid: "UID1"))
    // Other uids' records survive.
    store.rememberTag(uid: "UID2", name: "Hall", songs: [])
    #expect(store.memoryForUid("UID2")?.name == "Hall")
}

@Test func aSuccessfulWriteRecordingTheMemoryReadsInSync() {
    let store = makeStore()
    let tag = store.addTag(uid: "UID1", name: "Porch")!
    _ = store.linkEntryToTag(tagId: tag.id, entryId: store.addEntry(label: "One", payload: "/1.mp3").id)

    // The write's landing: the per-uid memory is the tag's own system
    // data (the write path's payload: name + ordered song names) - the
    // indicator recomputes to IN SYNC.
    let data = LumiTagData(name: tag.name, songs: store.songNamesForTag(tag.id))
    store.rememberTag(uid: tag.uid, name: data.name, songs: data.songs)
    #expect(
        tagSyncState(name: tag.name, songs: store.songNamesForTag(tag.id), memory: store.memoryForUid(tag.uid))
        == .inSync
    )

    // A later edit flips the derived state back to DIFFERS (a rename).
    store.renameTag(uid: tag.uid, name: "Hallway")
    #expect(
        tagSyncState(
            name: store.tagByUid(tag.uid)!.name,
            songs: store.songNamesForTag(tag.id),
            memory: store.memoryForUid(tag.uid),
        )
        == .differs
    )
}
