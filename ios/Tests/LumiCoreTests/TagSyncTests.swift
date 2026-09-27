// The in-sync indicator's verdict pins - the iOS port of the Android
// app's tag-sync tests: no memory reads unknown, a matching memory
// reads in-sync, any name or song-list change reads differs.

import Foundation
import Testing
@testable import LumiCore

@Test func noMemoryReadsUnknown() {
    #expect(tagSyncState(name: "Porch", songs: [], memory: nil) == .unknown)
    #expect(tagSyncState(name: "Porch", songs: ["One"], memory: nil) == .unknown)
}

@Test func aMatchingMemoryReadsInSync() {
    #expect(
        tagSyncState(name: "Porch", songs: [], memory: TagMemoryRecord(name: "Porch", songs: []))
        == .inSync
    )
    #expect(
        tagSyncState(
            name: "Porch",
            songs: ["One", "Two"],
            memory: TagMemoryRecord(name: "Porch", songs: ["One", "Two"]),
        )
        == .inSync
    )
}

@Test func aNameOrSongChangeReadsDiffers() {
    // A rename.
    #expect(
        tagSyncState(name: "Hallway", songs: [], memory: TagMemoryRecord(name: "Porch", songs: []))
        == .differs
    )
    // A song added to the tag.
    #expect(
        tagSyncState(name: "Porch", songs: ["One"], memory: TagMemoryRecord(name: "Porch", songs: []))
        == .differs
    )
    // The song list is ORDERED: a reorder is a difference too.
    #expect(
        tagSyncState(
            name: "Porch",
            songs: ["Two", "One"],
            memory: TagMemoryRecord(name: "Porch", songs: ["One", "Two"]),
        )
        == .differs
    )
}
