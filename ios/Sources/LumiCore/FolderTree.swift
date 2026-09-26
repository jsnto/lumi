// The directory structure of a picked folder, rebuilt from the stored
// song rows - a port of the Android app's com.lumi.player.data.FolderTree.
//
// A song's payload carries its file's full path; the segments below the
// picked folder's own path are the song's subfolder path. The folder
// page renders the tree with connector lines (subfolder rows above their
// songs, indented one rail step per level), the per-level order through
// the shared content sort, and a screen-level show/hide state keyed by
// a subfolder's path from the picked root (siblings carry distinct
// names, so the path names one node).

import Foundation

/// A direct subfolder of one level of a picked folder's tree: its
/// display name (the folder's own file-system name) and the level nested
/// inside it.
public struct FolderTreeFolder: Hashable, Sendable {
    public var displayName: String
    public var children: FolderTreeLevel

    public init(displayName: String, children: FolderTreeLevel) {
        self.displayName = displayName
        self.children = children
    }
}

/// One depth of a picked folder's directory structure, as shown on the
/// folder detail page: the direct subfolders (each carrying its own
/// nested level) and the songs sitting directly at that depth. The order
/// is the order the import stored (unsorted); the page applies its
/// per-level order through `sortedTree`.
public struct FolderTreeLevel: Hashable, Sendable {
    public var folders: [FolderTreeFolder]
    public var songs: [Entry]

    public init(folders: [FolderTreeFolder] = [], songs: [Entry] = []) {
        self.folders = folders
        self.songs = songs
    }
}

/// Rebuilds the picked folder's directory structure from the folder's
/// stored song rows, so the folder detail page can show the structure
/// the import walked (subfolders and the songs nested under them)
/// instead of one flat list: a song directly in the root folder sits at
/// the top level, a song inside subfolder "a/b" sits two levels down,
/// and deeper subfolders nest further. A song whose payload is not
/// under the root cannot be placed and degrades to the top level
/// (visible, not lost). The subfolder order is first-seen order.
public func folderTreeStructure(rootPath: String, entries: [Entry]) -> FolderTreeLevel {
    let root = rootPath.hasSuffix("/") ? String(rootPath.dropLast()) : rootPath
    guard !root.isEmpty else {
        return FolderTreeLevel(songs: entries)
    }
    final class Node {
        var subfolders: [String: Node] = [:]
        private var folderOrder: [String] = []
        var songs: [Entry] = []

        func child(_ name: String) -> Node {
            if let existing = subfolders[name] { return existing }
            let node = Node()
            subfolders[name] = node
            folderOrder.append(name)
            return node
        }

        func level() -> FolderTreeLevel {
            FolderTreeLevel(
                folders: folderOrder.map { name in
                    FolderTreeFolder(displayName: name, children: subfolders[name]!.level())
                },
                songs: songs,
            )
        }
    }
    let rootNode = Node()
    for entry in entries {
        var segments: [String] = []
        if entry.payload.hasPrefix(root + "/") {
            let relative = String(entry.payload.dropFirst(root.count + 1))
            segments = relative.split(separator: "/").map { String($0) }.filter { !$0.isEmpty }
        }
        // The song's subfolder path is every segment but the file's own.
        var node = rootNode
        for segment in segments.dropLast() {
            node = node.child(segment)
        }
        node.songs.append(entry)
    }
    return rootNode.level()
}

extension FolderTreeLevel {
    /// The folder page's per-level order: the content list's title sort
    /// applied to each depth of the tree. At every level the subfolder
    /// rows come first (sorted by display name) and the song rows follow
    /// (sorted by title with the row id breaking ties), both case-
    /// insensitive in [direction] - descending is the exact reverse of
    /// the ascending order, the content list's own rule. The structure
    /// is untouched: a new tree is returned.
    func sortedTree(direction: ContentSortDirection) -> FolderTreeLevel {
        let sortedFolders = sortedByTitle(
            folders,
            title: { $0.displayName.lowercased() },
            id: { _ in 0 },
            direction: direction,
        ).map {
            FolderTreeFolder(
                displayName: $0.displayName,
                children: $0.children.sortedTree(direction: direction),
            )
        }
        return FolderTreeLevel(
            folders: sortedFolders,
            songs: sortedByTitle(songs, title: { $0.label }, id: { $0.id }, direction: direction),
        )
    }

    /// The rows [level] renders when the subfolders in [collapsed] (their
    /// paths from the picked root) are collapsed: every visible subfolder
    /// contributes its own row plus the visible rows of its nested
    /// levels, and every song counts one. A collapsed folder contributes
    /// its own row only - its descendants' rows are hidden (the folder's
    /// row itself stays, as the show/hide toggle).
    func visibleRowCount(collapsed: Set<String>, prefix: String = "") -> Int {
        var rows = songs.count
        for folder in folders {
            let key = prefix.isEmpty ? folder.displayName : prefix + "/" + folder.displayName
            rows += 1
            if !collapsed.contains(key) {
                rows += folder.children.visibleRowCount(collapsed: collapsed, prefix: key)
            }
        }
        return rows
    }
}
