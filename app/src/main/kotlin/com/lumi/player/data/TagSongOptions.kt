package com.lumi.player.data

import android.net.Uri

/**
 * The content tree the tag add-song picker shows: the app's content laid
 * out the way the content folder pages lay it out - the individual songs
 * (the non-folder rows) at the top level, and each added folder as a
 * top-level folder row carrying the folder's own imported structure (its
 * subfolders and the songs nested under them). A content entry is offered
 * only once - the first stored copy of its label (the SAME collapse the
 * flat picker does, in the content-list order) - so a label stored twice
 * (e.g. picked individually and inside a folder) appears exactly once,
 * under the first copy's home.
 *
 * The result is unsorted ([sortedTree] applies the picker's per-level
 * order, the content list's own sort). An added folder whose songs are
 * all duplicates of earlier-offered rows carries no offered rows and
 * shows no row of its own.
 *
 * @param entries the app's whole content (the content-list order)
 * @param folders the added folders, in their listed order
 */
fun tagSongPickerTree(entries: List<EntryEntity>, folders: List<FolderEntity>): FolderTreeLevel {
    val seen = HashSet<String>()
    val firstCopies = entries.filter { seen.add(it.label) }
    val individual = firstCopies.filter { it.folderId == null }
    val folderRows = folders.mapNotNull { folder ->
        val folderEntries = firstCopies.filter { it.folderId == folder.id }
        if (folderEntries.isEmpty()) null
        else FolderTreeFolder(
            displayName = folder.label,
            children = folderTreeStructure(Uri.parse(folder.uri), folderEntries),
            folderId = folder.id,
        )
    }
    return FolderTreeLevel(folders = folderRows, songs = individual)
}
