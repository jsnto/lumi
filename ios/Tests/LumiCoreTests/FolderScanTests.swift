// The folder audio scan's behavior pins - the iOS ports of the Android
// app's FolderAudioScan tests: the pre-order DFS, the extension filter,
// the self-listing guard, and the rescan's new-only semantics.

import Testing
@testable import LumiCore

/// A fake folder tree the walk can recurse through without a filesystem.
private struct FakeTree {
    /// Maps a folder path to its direct children.
    var children: [String: [FolderChild]] = [:]

    func listing(_ path: String) -> [FolderChild] {
        children[path] ?? []
    }
}

private func child(_ name: String, in parent: String, dir: Bool = false) -> FolderChild {
    FolderChild(
        displayName: name,
        isDirectory: dir,
        path: parent.hasSuffix("/") ? parent + name : parent + "/" + name,
    )
}

@Test func collectAudioFilesFindsEverySupportedFilePreOrderDepthFirst() {
    var tree = FakeTree()
    let root = "/Music/Album"
    tree.children[root] = [
        child("a.mp3", in: root),
        child("sub", in: root, dir: true),
        child("b.wav", in: root),
        child("notes.txt", in: root),
        child("c.FLAC", in: root),
    ]
    let sub = root + "/sub"
    tree.children[sub] = [
        child("d.m4a", in: sub),
        child("deeper", in: sub, dir: true),
    ]
    let deeper = sub + "/deeper"
    tree.children[deeper] = [child("e.aac", in: deeper)]

    let songs = collectAudioFiles(rootPath: root, childrenOf: tree.listing)
    // Pre-order depth-first: a.mp3, (sub: d.m4a, deeper: e.aac), b.wav,
    // c.FLAC (case-insensitive extension). The text file is skipped.
    #expect(songs.map { $0.displayName } == ["a.mp3", "d.m4a", "e.aac", "b.wav", "c.FLAC"])
}

@Test func collectAudioFilesSkipsUnsupportedExtensions() {
    var tree = FakeTree()
    let root = "/Music"
    tree.children[root] = [
        child("song.mid", in: root),
        child("song.mp3", in: root),
        child("song.WAV", in: root),
        child("song.ogg", in: root),
        child("song", in: root),
    ]
    let songs = collectAudioFiles(rootPath: root, childrenOf: tree.listing)
    #expect(songs.map { $0.displayName } == ["song.mp3", "song.WAV"])
}

@Test func aFolderThatListsItselfTerminatesTheWalk() {
    // The self-listing guard (a symlink loop / provider quirk): the walk
    // visits each folder at most once, so the repeat is skipped and the
    // file comes back exactly once.
    var tree = FakeTree()
    let root = "/Music"
    tree.children[root] = [
        child("a.mp3", in: root),
        child(root, in: root, dir: true), // the folder listing itself
    ]
    let songs = collectAudioFiles(rootPath: root, childrenOf: tree.listing)
    #expect(songs.map { $0.displayName } == ["a.mp3"])
}

@Test func anEmptyFolderYieldsNoSongs() {
    var tree = FakeTree()
    let root = "/Music/Empty"
    tree.children[root] = []
    #expect(collectAudioFiles(rootPath: root, childrenOf: tree.listing).isEmpty)
}

@Test func rescanNewSongsReturnsOnlyTheNotYetStoredFilesInWalkOrder() {
    var tree = FakeTree()
    let root = "/Music/Album"
    tree.children[root] = [
        child("old.mp3", in: root),
        child("sub", in: root, dir: true),
    ]
    let sub = root + "/sub"
    tree.children[sub] = [
        child("newer.mp3", in: sub),
        child("new.wav", in: sub),
    ]
    let stored: Set<String> = [root + "/old.mp3"]
    let new = rescanNewSongs(rootPath: root, storedPayloads: stored, childrenOf: tree.listing)
    #expect(new.map { $0.displayName } == ["newer.mp3", "new.wav"])
}

@Test func anUnchangedFolderRescansToNothing() {
    var tree = FakeTree()
    let root = "/Music/Album"
    tree.children[root] = [child("a.mp3", in: root)]
    let stored: Set<String> = [root + "/a.mp3"]
    #expect(rescanNewSongs(rootPath: root, storedPayloads: stored, childrenOf: tree.listing).isEmpty)
}

@Test func isSupportedAudioFileIsCaseInsensitive() {
    #expect(isSupportedAudioFile("song.MP3"))
    #expect(isSupportedAudioFile("song.m4a"))
    #expect(isSupportedAudioFile("song.aac"))
    #expect(isSupportedAudioFile("song.flac"))
    #expect(isSupportedAudioFile("song.wav"))
    #expect(!isSupportedAudioFile("song.mid"))
    #expect(!isSupportedAudioFile("song"))
    #expect(!isSupportedAudioFile("song.mp3x"))
}
