// The store-level pins for the audio level's loudness rows and the
// configured remembered-positions capacity - the iOS ports of the Android
// app's loudness store + repository tests: the record/read upsert, the
// per-payload snapshot the playback path reads, the settings status, the
// capacity-aware LRU remember, and the backward-compatible document
// decode (a document persisted before the loudness table existed must
// still load - the corrupt-file policy must not fire on the new key).

import Foundation
import Testing
@testable import LumiCore

private func makeStore() -> Store {
    Store(doc: StoreDoc()) { _ in }
}

private func pos(_ uid: String, _ at: Int64) -> TagPosition {
    TagPosition(tagUid: uid, positionMs: 2_000, entryIndex: 0, queueFingerprint: "f", lastPlayedAt: at)
}

// MARK: - loudness rows

@Test func recordLoudness_storesAndReplacesTheEntryRow() {
    let store = makeStore()
    let entry = store.addEntry(label: "A", payload: "/a.mp3")

    #expect(store.loudnessForEntry(entry.id) == nil)
    store.recordLoudness(entryId: entry.id, lufs: -10.0, truePeakDbTp: 0.0, fingerprint: "f1")
    #expect(store.loudnessForEntry(entry.id)?.lufs == -10.0)
    #expect(store.loudnessForEntry(entry.id)?.truePeakDbTp == 0.0)
    #expect(store.loudnessForEntry(entry.id)?.fingerprint == "f1")

    // A re-measure replaces the row (upsert, not append).
    store.recordLoudness(entryId: entry.id, lufs: -12.0, truePeakDbTp: -1.0, fingerprint: "f2")
    #expect(store.loudnessForEntry(entry.id)?.lufs == -12.0)
    #expect(store.loudnessForEntry(entry.id)?.fingerprint == "f2")
}

@Test func recordLoudness_aSilentReadingIsRecordedToo() {
    // "Measured, nothing to normalize" is a stable state: the row exists
    // with a nil loudness, and the status counts it as measured.
    let store = makeStore()
    let entry = store.addEntry(label: "Silent", payload: "/silent.mp3")
    store.recordLoudness(entryId: entry.id, lufs: nil, truePeakDbTp: nil, fingerprint: "f")

    let row = store.loudnessForEntry(entry.id)
    #expect(row != nil)
    #expect(row?.lufs == nil)
    #expect(store.loudnessStatus().measured == 1)
}

@Test func loudnessSnapshot_isKeyedByTheContentPayload() {
    let store = makeStore()
    let a = store.addEntry(label: "A", payload: "/a.mp3")
    let b = store.addEntry(label: "B", payload: "/b.mp3")
    store.recordLoudness(entryId: a.id, lufs: -10.0, truePeakDbTp: 0.0, fingerprint: "fa")
    store.recordLoudness(entryId: b.id, lufs: nil, truePeakDbTp: nil, fingerprint: "fb")

    let snapshot = store.loudnessSnapshot()
    #expect(snapshot.count == 2)
    #expect(snapshot["/a.mp3"]?.lufs == -10.0)
    // The silent entry is in the snapshot too (its sample carries nils -
    // the playback path's gain math turns that into no gain).
    #expect(snapshot["/b.mp3"]?.lufs == nil)
    // An unmeasured entry is NOT in the snapshot.
    let c = store.addEntry(label: "C", payload: "/c.mp3")
    _ = c
    #expect(store.loudnessSnapshot()["/c.mp3"] == nil)
}

@Test func loudnessSnapshot_anEntryRemovalTakesItsMeasurementWithIt() {
    let store = makeStore()
    let a = store.addEntry(label: "A", payload: "/a.mp3")
    store.recordLoudness(entryId: a.id, lufs: -10.0, truePeakDbTp: 0.0, fingerprint: "fa")
    store.deleteEntry(a)
    // The ROW itself is gone (not just the snapshot projection): the
    // entry id is reused by the next add, so a stale row would land on
    // the replacement song's first playback (the Android FK's
    // ON DELETE CASCADE).
    #expect(store.loudnessForEntry(a.id) == nil)
    #expect(store.loudnessSnapshot().isEmpty)
}

@Test func removeFolder_aPartialWalkKeepsTheSurvivorsMeasurements() {
    let store = makeStore()
    let folder = store.rememberFolder(uri: "/Music", bookmark: Data([1]), label: "Music")
    for name in ["x", "y"] {
        let entry = store.addEntry(label: name, payload: "/Music/\(name).mp3", folderId: folder.id)
        store.recordLoudness(entryId: entry.id, lufs: -12.0, truePeakDbTp: -1.0, fingerprint: "f-\(name)")
    }
    let kept = store.addEntry(label: "k", payload: "/Music/k.mp3", folderId: folder.id)
    store.recordLoudness(entryId: kept.id, lufs: -13.0, truePeakDbTp: -3.0, fingerprint: "fk")

    // A removal whose walk identifies only the two imported rows
    // deletes them and their measurements; the survivor (unmarked, not
    // in the walk) keeps its row.
    let removed = store.removeFolder(
        folder: folder,
        importedPayloads: ["/Music/x.mp3", "/Music/y.mp3"],
    )
    #expect(removed == 2)
    #expect(store.loudnessForEntry(kept.id) != nil)
    #expect(store.loudnessSnapshot().count == 1)
}

@Test func removeFolder_aWalkThatNamesEveryRowCascadesAllTheirMeasurements() {
    let store = makeStore()
    let folder = store.rememberFolder(uri: "/Music", bookmark: Data([1]), label: "Music")
    var ids: [Int] = []
    for name in ["x", "y"] {
        let entry = store.addEntry(label: name, payload: "/Music/\(name).mp3", folderId: folder.id)
        store.recordLoudness(entryId: entry.id, lufs: -12.0, truePeakDbTp: -1.0, fingerprint: "f-\(name)")
        ids.append(entry.id)
    }
    store.removeFolder(
        folder: folder,
        importedPayloads: ["/Music/x.mp3", "/Music/y.mp3"],
    )
    for id in ids {
        #expect(store.loudnessForEntry(id) == nil)
    }
    #expect(store.loudnessSnapshot().isEmpty)
}

@Test func loudnessStatus_countsTheAudioEntriesMeasuredOutOfTotal() {
    let store = makeStore()
    store.addEntry(label: "A", payload: "/a.mp3")
    store.addEntry(label: "B", payload: "/b.flac")
    store.addEntry(label: "C", payload: "/c.m4a")
    let a = store.entryByPayload("/a.mp3")!
    store.recordLoudness(entryId: a.id, lufs: -14.0, truePeakDbTp: -2.0, fingerprint: "f")

    let status = store.loudnessStatus()
    #expect(status.measured == 1)
    #expect(status.total == 3)
    #expect(status.text == "1 of 3 songs measured")
}

@Test func loudnessStatus_theSinglSongCopiesSaysSong() {
    let store = makeStore()
    let a = store.addEntry(label: "A", payload: "/a.mp3")
    store.recordLoudness(entryId: a.id, lufs: -14.0, truePeakDbTp: -2.0, fingerprint: "f")
    #expect(store.loudnessStatus().text == "1 of 1 song measured")
}

@Test func loudnessStatus_aNonAudioPayloadIsNotCounted() {
    // The app is audio-only in practice, but the status must not count a
    // payload that is not one of the supported audio extensions (the
    // Android app's EntryType.AUDIO filter, ported as the extension check).
    let store = makeStore()
    store.addEntry(label: "Doc", payload: "/doc.txt")
    #expect(store.loudnessStatus().total == 0)
}

// MARK: - the configured remembered-positions capacity (store level)

@Test func remember_evictsAgainstTheConfiguredCapacity() {
    let store = makeStore()
    store.remember(pos("A", 1), capacity: 2)
    store.remember(pos("B", 2), capacity: 2)
    #expect(store.positionFor("A") != nil)
    #expect(store.positionFor("B") != nil)

    // Capacity 2: the incoming evicts the least-recently played.
    store.remember(pos("C", 3), capacity: 2)
    #expect(store.positionFor("A") == nil, "the oldest row evicts first")
    #expect(store.positionFor("B") != nil)
    #expect(store.positionFor("C") != nil)
}

@Test func remember_theDefaultCapacityIsThree() {
    let store = makeStore()
    store.remember(pos("A", 1))
    store.remember(pos("B", 2))
    store.remember(pos("C", 3))
    store.remember(pos("D", 4))
    #expect(store.positionFor("A") == nil)
    #expect(store.positionFor("B") != nil)
    #expect(store.positionFor("C") != nil)
    #expect(store.positionFor("D") != nil)
}

@Test func remember_aReplayOfTheIncomingTagNeverEvicts() {
    let store = makeStore()
    store.remember(pos("A", 1), capacity: 1)
    store.remember(pos("B", 2), capacity: 1)
    // B is already remembered at capacity 1: the re-store evicts nothing.
    store.remember(pos("B", 3), capacity: 1)
    #expect(store.positionFor("B")?.lastPlayedAt == 3)
    #expect(store.positionFor("A") == nil)
}

// MARK: - the backward-compatible document decode

@Test func aDocumentWithoutTheLoudnessKey_stillDecodes() {
    // A store.json persisted by the pre-loudness build carries no
    // "loudness" key: the strict decode failure would move the WHOLE
    // library file aside and start empty, so the new key must decode
    // leniently (absent = empty) while the older keys stay strict.
    var doc = StoreDoc()
    doc.tags = [Tag(id: 1, uid: "AA", name: "Tag", createdAt: 1)]
    doc.entries = [Entry(id: 1, label: "A", payload: "/a.mp3", createdAt: 2)]
    doc.settings = ["play_mode": "proximity"]
    var data = try! JSONEncoder().encode(doc)

    // Strip the key the way the old document lacks it.
    var object = try! JSONSerialization.jsonObject(with: data) as! [String: Any]
    object.removeValue(forKey: "loudness")
    data = try! JSONSerialization.data(withJSONObject: object)

    let decoded = try! JSONDecoder().decode(StoreDoc.self, from: data)
    #expect(decoded.tags.count == 1)
    #expect(decoded.entries.count == 1)
    #expect(decoded.loudness.isEmpty)
}

@Test func theLoudnessTable_survivesAnEncodeDecodeRoundTrip() {
    var doc = StoreDoc()
    let entry = Entry(id: 1, label: "A", payload: "/a.mp3", createdAt: 1)
    doc.entries = [entry]
    doc.loudness[1] = EntryLoudness(entryId: 1, lufs: -10.5, truePeakDbTp: -0.5, fingerprint: "f", measuredAt: 123)

    let data = try! JSONEncoder().encode(doc)
    let decoded = try! JSONDecoder().decode(StoreDoc.self, from: data)
    #expect(decoded.loudness[1]?.lufs == -10.5)
    #expect(decoded.loudness[1]?.truePeakDbTp == -0.5)
    #expect(decoded.loudness[1]?.fingerprint == "f")
    #expect(decoded.loudness[1]?.measuredAt == 123)
}
