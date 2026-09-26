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
    /// state (the Android app's revoked-grant surface). The caller owns
    /// the asserted access for as long as it needs it and stops it itself
    /// (`stopAccessingSecurityScopedResource`) when done.
    public static func resolve(_ bookmark: Data) -> URL? {
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
        // The URL overload (contentsOfDirectory(at:includingPropertiesForKeys:)
        // -> [URL]): the atPath: overload returns [String] names, which have
        // no resourceValues / lastPathComponent / path members.
        guard let items = try? fm.contentsOfDirectory(
            at: URL(fileURLWithPath: folderPath),
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
}
