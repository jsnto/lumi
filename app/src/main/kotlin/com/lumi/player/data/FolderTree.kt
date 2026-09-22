package com.lumi.player.data

import android.net.Uri

/**
 * A direct subfolder of one level of a picked folder's tree (as shown on
 * the folder detail page): its display name - the folder's own name on the
 * file system (the last path segment of its document id) - and the level
 * nested inside it.
 */
data class FolderTreeFolder(
    val displayName: String,
    val children: FolderTreeLevel,
)

/**
 * One depth of a picked folder's directory structure, as shown on the
 * folder detail page: the direct subfolders (each carrying its own nested
 * [FolderTreeLevel]) and the songs sitting directly at that depth (the
 * folder's stored content rows). The order is the order the import stored
 * (unsorted); the page applies its sort control's per-level order through
 * [FolderTreeLevel.sortedTree].
 */
data class FolderTreeLevel(
    val folders: List<FolderTreeFolder>,
    val songs: List<EntryEntity>,
)

/**
 * The docId a uri identifies - the shared grammar of the folder-walk seam
 * (see the `folderChildren` seam in `ui/LumiViewModel.kt`): the picker's
 * tree uri (`tree/<treeId>`) carries the picked folder's docId as its
 * second path segment (a trailing `:openable` picker suffix stripped - the
 * provider answers only the bare docId), and a tree-scoped node uri
 * (`tree/<treeId>/document/<docId>`) carries it as the fourth segment,
 * verbatim (the percent-encoding is already undone by [Uri.pathSegments]).
 * Any other shape degrades to the second segment (the walk never builds
 * one; the tests' synthetic uris keep working).
 */
fun folderDocIdOf(folderUri: Uri): String {
    val segments = folderUri.pathSegments
    return when {
        // A tree-scoped node uri: the docId is the fourth segment, verbatim.
        segments.size == 4 && segments.getOrNull(0) == "tree" && segments.getOrNull(2) == "document" ->
            segments.getOrNull(3).orEmpty()
        // The picker's tree uri: the docId is the second segment, with the
        // optional `:openable` picker suffix stripped.
        segments.getOrNull(0) == "tree" -> segments.getOrNull(1)?.removeSuffix(":openable").orEmpty()
        // Kept verbatim for the tests' synthetic tree uris.
        else -> segments.getOrNull(1).orEmpty()
    }
}

/**
 * Rebuilds the picked folder's directory structure from the folder's stored
 * song rows, so the folder detail page can show the structure the import
 * walked (subfolders and the songs nested under them) instead of one flat
 * list. A song's payload is the tree's content uri, whose document id
 * encodes the full path from the volume root - cutting the picked folder's
 * own docId (the picker's tree uri's, through [folderDocIdOf]) off the
 * front leaves the song's path below the picked root: a song directly in
 * the root folder sits at the top level, a song inside subfolder "a/b"
 * sits two levels down, and deeper subfolders indent further.
 *
 * The provider joins a document id's volume id and path with ':' (the
 * volume root's docId is the bare volume id, e.g. "primary") and path
 * segments with '/'. So a docId below the root starts with "$rootDocId/"
 * - the slash case, which wins whenever it can - or, when the root docId
 * is a bare volume id (no '/'), with "$rootDocId:" - the colon case of a
 * volume-root pick, whose children's docIds look like
 * "primary:Music/song.mp3". Either way, cutting the prefix and splitting
 * the remainder on '/' (dropping the file segment; the empty-segment
 * filter swallows a USB path's leading '/') yields the song's subfolder
 * path.
 *
 * A song whose payload is not a tree-scoped uri under the root (a legacy
 * row, or a synthetic test uri) cannot be placed and degrades to the top
 * level - visible, not lost. The result is unsorted;
 * [FolderTreeLevel.sortedTree] applies the page's per-level order.
 */
fun folderTreeStructure(folderUri: Uri, entries: List<EntryEntity>): FolderTreeLevel {
    val rootDocId = folderDocIdOf(folderUri)
    val volumeRootPick = rootDocId.isNotEmpty() && !rootDocId.contains('/')
    val root = TreeNode()
    entries.forEach { entry ->
        val docId = treeEntryDocId(Uri.parse(entry.payload))
        val folderPath =
            if (rootDocId.isNotEmpty() && docId.startsWith("$rootDocId/")) {
                docId.removePrefix("$rootDocId/").split('/').filter { it.isNotEmpty() }.dropLast(1)
            } else if (volumeRootPick && docId.startsWith("$rootDocId:")) {
                docId.removePrefix("$rootDocId:").split('/').filter { it.isNotEmpty() }.dropLast(1)
            } else {
                emptyList()
            }
        var node = root
        folderPath.forEach { segment -> node = node.child(segment) }
        node.songs += entry
    }
    return root.toLevel()
}

/**
 * The document id a stored entry's payload carries: the fourth path segment
 * of the tree-scoped content uri, verbatim (the percent-encoding is already
 * undone by [Uri.pathSegments]). A payload that is not a tree-scoped uri
 * degrades to the empty id (and so to the tree's top level).
 */
private fun treeEntryDocId(payload: Uri): String {
    val segments = payload.pathSegments
    return if (segments.size == 4 && segments.getOrNull(0) == "tree" && segments.getOrNull(2) == "document") {
        segments.getOrNull(3).orEmpty()
    } else {
        ""
    }
}

/** The tree's building block: the direct subfolders (first-seen order) and the direct songs. */
private class TreeNode(val displayName: String = "") {
    private val subfolders = LinkedHashMap<String, TreeNode>()
    val songs = mutableListOf<EntryEntity>()

    fun child(segment: String): TreeNode = subfolders.getOrPut(segment) { TreeNode(segment) }

    fun toLevel(): FolderTreeLevel =
        FolderTreeLevel(
            folders = subfolders.values.map { FolderTreeFolder(it.displayName, it.toLevel()) },
            songs = songs,
        )
}

/**
 * The folder page's per-level order: the content list's title sort applied
 * to each depth of the tree. At every level the subfolder rows come first
 * (sorted by display name) and the song rows follow (sorted by title), both
 * case-insensitive in [direction] - descending is the exact reverse of the
 * ascending order, the content list's own rule ([sortedByTitle]). The
 * structure is untouched: a new tree is returned.
 */
fun FolderTreeLevel.sortedTree(direction: ContentSortDirection): FolderTreeLevel {
    fun <T> List<T>.titledOrder(key: (T) -> String): List<T> {
        val ascending = sortedWith(compareBy(key))
        return if (direction == ContentSortDirection.ASCENDING) ascending else ascending.asReversed()
    }
    return FolderTreeLevel(
        folders = folders
            .titledOrder { it.displayName.lowercase() }
            .map { it.copy(children = it.children.sortedTree(direction)) },
        songs = songs.sortedByTitle({ it.label }, { it.id }, direction),
    )
}

/**
 * The rows [level] renders when the subfolders in [collapsed] are
 * collapsed: every visible subfolder contributes its own row plus the
 * visible rows of its nested levels, and every song counts one. A
 * collapsed folder contributes its own row only - its descendants' rows
 * are hidden from the display (the folder's row itself stays, as the
 * show/hide toggle). [path] is the chain of name segments from the picked
 * root down to this level; each subfolder's collapse key is its [path]
 * extended with its own display name (siblings carry distinct names, so
 * the key identifies one node - the same key the page's show/hide state
 * is keyed by).
 */
fun FolderTreeLevel.visibleRowCount(collapsed: Set<List<String>>, path: List<String> = emptyList()): Int {
    var rows = songs.size
    folders.forEach { folder ->
        val folderPath = path + folder.displayName
        rows += 1
        if (folderPath !in collapsed) {
            rows += folder.children.visibleRowCount(collapsed, folderPath)
        }
    }
    return rows
}
