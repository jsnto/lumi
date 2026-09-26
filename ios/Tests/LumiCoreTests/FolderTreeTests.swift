// The folder tree's observable-behavior pins - the iOS port of the
// Android app's FolderTree tests: the structure rebuilt from the stored
// payloads, the per-level sort (exact-reverse descending), and the
// visible row count under a collapsed set.

import Foundation
import Testing
@testable import LumiCore

private func entry(_ id: Int, _ label: String, _ payload: String) -> Entry {
    Entry(id: id, label: label, payload: payload, createdAt: Int64(id), folderId: 1)
}

@Test func treeStructureNestsSongsUnderTheirSubfolders() {
    let entries = [
        entry(1, "root song", "/Music/Album/root.mp3"),
        entry(2, "a song", "/Music/Album/a/s1.mp3"),
        entry(3, "deep song", "/Music/Album/a/b/s2.mp3"),
        entry(4, "a other", "/Music/Album/a/s3.mp3"),
        entry(5, "c song", "/Music/Album/c/s4.mp3"),
    ]
    let tree = folderTreeStructure(rootPath: "/Music/Album", entries: entries)

    // The root's own songs sit at the top level.
    #expect(tree.songs.map(\.label) == ["root song"])
    // The direct subfolders, in first-seen order.
    #expect(tree.folders.map(\.displayName) == ["a", "c"])
    let a = tree.folders[0]
    // "a" nests its deep subfolder and its own songs.
    #expect(a.children.folders.map(\.displayName) == ["b"])
    #expect(a.children.songs.map(\.label).sorted() == ["a other", "a song"])
    #expect(a.children.folders[0].children.songs.map(\.label) == ["deep song"])
    #expect(tree.folders[1].children.songs.map(\.label) == ["c song"])
    // Nothing is lost: every song lands exactly once.
    var seen = [String]()
    func collect(_ level: FolderTreeLevel) {
        for song in level.songs { seen.append(song.label) }
        for folder in level.folders { collect(folder.children) }
    }
    collect(tree)
    #expect(seen.sorted() == entries.map(\.label).sorted())
}

@Test func treeStructurePlacesNonMatchingPayloadsAtTheTopLevel() {
    // A payload outside the picked root cannot be placed: it degrades to
    // the top level (visible, not lost).
    let entries = [
        entry(1, "inside", "/Music/Album/a/inner.mp3"),
        entry(2, "outside", "/Other/Place/out.mp3"),
    ]
    let tree = folderTreeStructure(rootPath: "/Music/Album", entries: entries)
    #expect(tree.songs.map(\.label) == ["outside"])
    #expect(tree.folders.map(\.displayName) == ["a"])
    #expect(tree.folders[0].children.songs.map(\.label) == ["inside"])
}

@Test func sortedTreeOrdersEveryLevelAndDescendingIsTheExactReverse() {
    let entries = [
        entry(1, "beta", "/M/AlbumRoot/album/x1.mp3"),
        entry(2, "alpha", "/M/AlbumRoot/album/x2.mp3"),
        entry(3, "Gamma", "/M/AlbumRoot/y1.mp3"),
        entry(4, "delta", "/M/AlbumRoot/y2.mp3"),
    ]
    let tree = folderTreeStructure(rootPath: "/M/AlbumRoot", entries: entries)
    let up = tree.sortedTree(direction: .ascending)
    let down = tree.sortedTree(direction: .descending)

    // The level's own songs sort case-insensitively by title.
    #expect(up.songs.map(\.label) == ["delta", "Gamma"])
    #expect(up.folders.map(\.displayName) == ["album"])
    #expect(up.folders[0].children.songs.map(\.label) == ["alpha", "beta"])
    // Descending is the exact reverse at every depth.
    #expect(down.songs.map(\.label) == ["Gamma", "delta"])
    #expect(down.folders[0].children.songs.map(\.label) == ["beta", "alpha"])
}

@Test func visibleRowCountCountsVisibleFoldersAndSongsAndHidesCollapsedBranches() {
    let entries = [
        entry(1, "root", "/M/r.mp3"),
        entry(2, "a1", "/M/a/1.mp3"),
        entry(3, "a2", "/M/a/2.mp3"),
        entry(4, "deep", "/M/a/sub/3.mp3"),
        entry(5, "b", "/M/b/4.mp3"),
    ]
    let tree = folderTreeStructure(rootPath: "/M", entries: entries)
    // Nothing collapsed: 1 root song + a(1) + a's 2 songs + a/sub(1) +
    // its 1 song + b(1) + b's 1 song = 8.
    #expect(tree.visibleRowCount(collapsed: []) == 8)
    // Collapsing "a" hides its nested rows (a's songs and "sub"): 1 + a
    // + b + b's song = 4.
    #expect(tree.visibleRowCount(collapsed: ["a"]) == 4)
    // Collapsing only the nested "a/sub" keeps a's own songs: 1 + a + a's
    // 2 songs + a/sub + b + b's song = 7.
    #expect(tree.visibleRowCount(collapsed: ["a/sub"]) == 7)
}
