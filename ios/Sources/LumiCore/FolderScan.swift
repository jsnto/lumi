// The folder audio scan - a port of the Android app's
// com.lumi.player.data.FolderAudioScan.
//
// Walks a picked folder recursively (pre-order depth-first: a subfolder is
// descended into right where it is listed, before the folder's later
// children) and returns every supported audio file at any depth of
// subfolder. [childrenOf] lists the direct children of the folder path it
// is given, so the recursion is testable without the filesystem.

import Foundation

/// One direct child of a folder: a file or a subfolder.
public struct FolderChild: Hashable, Sendable {
    public let displayName: String
    public let isDirectory: Bool
    /// The child's own path - the walker recurses into subfolders through
    /// it and stores files through it.
    public let path: String

    public init(displayName: String, isDirectory: Bool, path: String) {
        self.displayName = displayName
        self.isDirectory = isDirectory
        self.path = path
    }
}

/// A supported audio file found by collectAudioFiles.
public struct ScannedSong: Hashable, Sendable {
    public let displayName: String
    public let path: String

    public init(displayName: String, path: String) {
        self.displayName = displayName
        self.path = path
    }
}

/// The audio file extensions the player can decode (the iOS AVFoundation
/// counterpart of the Android app's ExoPlayer set: mp3, aac/m4a, flac, wav).
/// Scans of a picked folder skip every other file, so a folder of documents
/// does not turn into a pile of dead entries.
public let supportedAudioExtensions: Set<String> = ["mp3", "m4a", "aac", "flac", "wav"]

/// Whether [displayName] ends in a supported audio extension (case-insensitive).
public func isSupportedAudioFile(_ displayName: String) -> Bool {
    guard let dot = displayName.lastIndex(of: ".") else { return false }
    let ext = displayName.index(after: dot)
    guard ext < displayName.endIndex else { return false }
    return supportedAudioExtensions.contains(displayName[ext...].lowercased())
}

/// Walks a picked folder recursively and returns every supported audio file
/// at any depth of subfolder. [childrenOf] lists the direct children of the
/// folder path it is given - the root path for the first call - so the
/// recursion is testable without the filesystem.
///
/// Each folder path is walked at most once: a listing that contains its own
/// folder again (a symlink loop, a provider quirk) must not loop the walk
/// forever - the repeat is skipped and the walk still terminates with the
/// file found once.
public func collectAudioFiles(
    rootPath: String,
    childrenOf: (String) -> [FolderChild],
) -> [ScannedSong] {
    var songs: [ScannedSong] = []
    var walkedFolders = Set<String>()

    func walk(_ folderPath: String) {
        guard walkedFolders.insert(folderPath).inserted else { return }
        for child in childrenOf(folderPath) {
            if child.isDirectory {
                walk(child.path)
            } else if isSupportedAudioFile(child.displayName) {
                songs.append(ScannedSong(displayName: child.displayName, path: child.path))
            }
        }
    }

    walk(rootPath)
    return songs
}

/// The rescan decision for a folder that was added earlier: re-walks the
/// tree (every subfolder included) and returns only the supported audio
/// files whose path is NOT already in [storedPayloads] (the content paths
/// already in the content list). Files that are already stored are skipped,
/// and a folder with no new songs returns an empty list - the rescan no-op.
/// The walk shares [collectAudioFiles], so the new songs come back in the
/// same order the initial add would have stored them.
public func rescanNewSongs(
    rootPath: String,
    storedPayloads: Set<String>,
    childrenOf: (String) -> [FolderChild],
) -> [ScannedSong] {
    collectAudioFiles(rootPath: rootPath, childrenOf: childrenOf).filter { !storedPayloads.contains($0.path) }
}
