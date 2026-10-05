// The remembered-position decision's behavior pins - the iOS ports of the
// Android app's PlaybackPosition tests: the fingerprint + bounds check,
// the LRU capacity (the configured remembered-positions count), and the
// setting's stored-value parsing.

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

// MARK: - the remembered-positions setting

@Test func unsetAndInvalidStoredCountsFallBackToTheDefault() {
    #expect(rememberedPositionsFromStored(nil) == maxRememberedTags)
    #expect(rememberedPositionsFromStored("") == maxRememberedTags)
    #expect(rememberedPositionsFromStored("garbage") == maxRememberedTags)
    #expect(rememberedPositionsFromStored("2.5") == maxRememberedTags)
}

@Test func outOfRangeStoredCountsFallBackToTheDefault() {
    // Below the minimum or above the setting's bound the stored value
    // is invalid (like the other settings' unset/invalid fallback).
    #expect(rememberedPositionsFromStored("0") == maxRememberedTags)
    #expect(rememberedPositionsFromStored("-1") == maxRememberedTags)
    #expect(rememberedPositionsFromStored("\(maxRememberedPositions + 1)") == maxRememberedTags)
    #expect(rememberedPositionsFromStored("1000") == maxRememberedTags)
}

@Test func storedCountsInsideTheRangeAreHonored() {
    #expect(rememberedPositionsFromStored("1") == 1)
    #expect(rememberedPositionsFromStored("3") == maxRememberedTags)
    #expect(rememberedPositionsFromStored("20") == maxRememberedPositions)
    #expect(rememberedPositionsFromStored("7") == 7)
    #expect(rememberedPositionsFromStored("  7  ") == 7)
}

@Test func theStoredValueClampsTheCountToTheSettingRange() {
    #expect(rememberedPositionsStoredValue(0) == "1")
    #expect(rememberedPositionsStoredValue(maxRememberedPositions) == "20")
    #expect(rememberedPositionsStoredValue(maxRememberedPositions + 1) == "20")
    #expect(rememberedPositionsStoredValue(-3) == "1")
    #expect(rememberedPositionsStoredValue(5) == "5")
}

@Test func lruEvictionsEvictAgainstTheConfiguredCapacity() {
    func pos(_ uid: String, _ at: Int64) -> TagPosition {
        TagPosition(tagUid: uid, positionMs: 2_000, entryIndex: 0, queueFingerprint: "f", lastPlayedAt: at)
    }
    // Capacity 1: the incoming keeps only itself.
    #expect(lruEvictions(existing: [pos("A", 1), pos("B", 2)], incoming: pos("C", 3), capacity: 1) == ["A", "B"])
    // Capacity 5 with 4 other rows: no eviction.
    #expect(
        lruEvictions(
            existing: [pos("A", 1), pos("B", 2), pos("C", 3), pos("D", 4)],
            incoming: pos("E", 5),
            capacity: 5,
        ).isEmpty,
    )
    // A lower count evicts the OLDEST first: capacity 2 with 4 other rows
    // evicts the three least-recently played (the survivors are the newest
    // other row plus the incoming).
    let evicted = Set(lruEvictions(
        existing: [pos("A", 1), pos("B", 2), pos("C", 3), pos("D", 4)],
        incoming: pos("E", 5),
        capacity: 2,
    ))
    #expect(evicted == ["A", "B", "C"])
}
