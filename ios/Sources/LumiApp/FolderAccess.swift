// Folder access - the iOS counterpart of the Android app's SAF folder
// paths (the security-scoped bookmark is the iOS counterpart of SAF's
// persistent read grant; the FileManager walk is the counterpart of the
// DocumentsContract listing the Android seam queries).
//
// Platform notes:
// - Picking: UIDocumentPickerViewController with .forOpeningDirectory
//   (directory urls, multi-select) - the iOS counterpart of the Android
//   OpenDocumentTree launcher. The picker hands out security-scoped URLs;
//   access across launches is the persisted bookmark (startAccessing +
//   resolvingBookmarkData), re-asserted on every access.
// - The walk is the Android collectAudioFiles: pre-order depth-first, the
//   supported-audio filter, a visited set so a folder that lists itself
//   cannot loop the walk.

import Foundation
import UniformTypeIdentifiers

public enum FolderAccess {
    /// The audio file types the document picker offers (the Android app's
    /// supported audio extensions: mp3/m4a/aac/flac/wav).
    public static var audioFileTypes: [UTType] {
        [
            UTType(filenameExtension: "mp3") ?? .mp3,
            UTType(filenameExtension: "m4a") ?? .audio,
            UTType(filenameExtension: "aac") ?? .audio,
            UTType(filenameExtension: "flac") ?? .audio,
            UTType(filenameExtension: "wav") ?? .audio,
        ]
    }

    /// Persists access to [url] across launches: the security-scoped
    /// bookmark (nil when the url cannot be bookmarked - e.g. a url the
    /// picker did not grant).
    public static func bookmarkData(for url: URL) -> Data? {
        (try? url.bookmarkData(options: [.withSecurityScope], includingResourceValuesForKeys: [], relativeTo: nil))
            ?? (try? url.bookmarkData(options: [], includingResourceValuesForKeys: [], relativeTo: nil))
    }

    /// Resolves a persisted bookmark to its url and ASSERTS the
    /// security-scoped access. Returns nil when the bookmark is stale
    /// (the folder was moved/deleted) - the caller surfaces the unreadable
    /// state (the Android app's revoked-grant surface).
    public static func resolve(_ bookmark: Data, releasingOnDeinit: Bool = false) -> URL? {
        var stale = false
        guard let url = URL(
            resolvingBookmarkData: bookmark,
            options: [.withoutUI, .withoutMounting],
            relativeTo: nil,
            bookmarkDataIsStale: &stale,
        ), !stale else {
            return nil
        }
        _ = url.startAccessingSecurityScopedResource()
        return url
    }

    /// The direct children of [folderPath] (files AND subfolders), in
    /// listing order - the seam [collectAudioFiles] recurses through.
    /// Returns nil when the folder can no longer be read (a revoked grant
    /// or a moved folder - the provider-query-thrown failure on Android).
    public static func children(of folderPath: String) -> [FolderChild]? {
        let fm = FileManager.default
        guard let items = try? fm.contentsOfDirectory(
            atPath: folderPath,
            includingPropertiesForKeys: [.isDirectoryKey],
        ) else {
            return nil
        }
        var result: [FolderChild] = []
        for item in items {
            let isDir = (try? item.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) ?? false
            result.append(FolderChild(displayName: item.lastPathComponent, isDirectory: isDir, path: item.path))
        }
        return result
    }

    /// Walks a picked folder recursively (pre-order depth-first) and
    /// returns every supported audio file at any depth. [rootUrl] must
    /// have its security scope asserted by the caller. Returns nil when
    /// the folder can no longer be read at the ROOT (the rescan / removal
    /// / add surfaces that as the unreadable state).
    public static func walk(rootUrl: URL) -> [ScannedSong]? {
        let rootPath = rootUrl.path
        // The root-readability guard: a nil listing at the root means the
        // tree is gone (revoked grant / moved folder) - the add, rescan
        // and removal paths surface that as the unreadable state (the
        // Android app's revoked-grant surface).
        guard children(of: rootPath) != nil else { return nil }
        return collectAudioFiles(rootPath: rootPath, childrenOf: { path in
            // A subfolder that can no longer be read degrades to EMPTY
            // (its songs are simply not listed) - the walk itself only
            // fails at the root.
            children(of: path) ?? []
        })
    }
}
