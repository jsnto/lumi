// The remembered-position decision's behavior pins - the iOS ports of the
// Android app's PlaybackPosition tests: the fingerprint + bounds check,
// the LRU 3 capacity.

import Testing
@testable import LumiCore

private func entry(_ label: String) -> EntrySpec {
    EntrySpec(type: "audio", label: label, payload: "/Music/\(label).mp3")
}

@Test func decideRestoreStartsFreshWithoutASavedRow() {
    #expect(decideRestore(saved: nil, queueFingerprint: "f", queueSize: 3) == .startFresh)
}

@Test func decideRestoreStartsFreshWhenTheListChanged() {
    let saved = TagPosition(
        tagUid: "AA",
        positionMs: 30_000,
        entryIndex: 1,
        queueFingerprint: "old",
        lastPlayedAt: 1,
    )
    #expect(decideRestore(saved: saved, queueFingerprint: "new", queueSize: 3) == .startFresh)
}

@Test func decideRestoreStartsFreshWhenTheEntryIndexIsOutOfRange() {
    let saved = TagPosition(
        tagUid: "AA",
        positionMs: 30_000,
        entryIndex: 5,
        queueFingerprint: "f",
        lastPlayedAt: 1,
    )
    #expect(decideRestore(saved: saved, queueFingerprint: "f", queueSize: 3) == .startFresh)
}

@Test func decideRestoreSeeksWhenTheListAndIndexMatchAndThePositionIsNonZero() {
    let saved = TagPosition(
        tagUid: "AA",
        positionMs: 30_000,
        entryIndex: 1,
        queueFingerprint: "f",
        lastPlayedAt: 1,
    )
    #expect(
        decideRestore(saved: saved, queueFingerprint: "f", queueSize: 3)
            == .seekTo(entryIndex: 1, positionMs: 30_000),
    )
}

@Test func decideRestoreTreatsZeroAsTheBeginning() {
    let saved = TagPosition(
        tagUid: "AA",
        positionMs: 0,
        entryIndex: 0,
        queueFingerprint: "f",
        lastPlayedAt: 1,
    )
    #expect(decideRestore(saved: saved, queueFingerprint: "f", queueSize: 3) == .startFresh)
}

@Test func fingerprintChangesWithTheListOrItsOrder() {
    let a = [entry("a"), entry("b")]
    let same = [entry("a"), entry("b")]
    let reordered = [entry("b"), entry("a")]
    let renamed = [entry("a"), entry("B")]
    #expect(fingerprintOf(a) == fingerprintOf(same))
    #expect(fingerprintOf(a) != fingerprintOf(reordered))
    #expect(fingerprintOf(a) != fingerprintOf(renamed))
}

@Test func lruEvictionKeepsCapacityThreeAndEvictsTheOldestFirst() {
    let oldest = TagPosition(tagUid: "old1", positionMs: 1, entryIndex: 0, queueFingerprint: "f", lastPlayedAt: 1)
    let mid = TagPosition(tagUid: "old2", positionMs: 1, entryIndex: 0, queueFingerprint: "f", lastPlayedAt: 2)
    let newest = TagPosition(tagUid: "old3", positionMs: 1, entryIndex: 0, queueFingerprint: "f", lastPlayedAt: 3)
    let incoming = TagPosition(tagUid: "new", positionMs: 1, entryIndex: 0, queueFingerprint: "f", lastPlayedAt: 4)

    // At capacity (3 rows): the incoming evicts the least-recently played.
    #expect(
        lruEvictions(existing: [oldest, mid, newest], incoming: incoming) == ["old1"],
    )
    // Storing a row for a tag that is already remembered never evicts.
    let selfIncoming = TagPosition(tagUid: "old2", positionMs: 1, entryIndex: 0, queueFingerprint: "f", lastPlayedAt: 5)
    #expect(lruEvictions(existing: [oldest, mid, newest], incoming: selfIncoming) == [])
}
