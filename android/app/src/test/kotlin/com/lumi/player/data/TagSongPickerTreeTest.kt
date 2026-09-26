package com.lumi.player.data

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure coverage of the tag add-song picker's content tree
 * ([tagSongPickerTree]): the app's content laid out the way the content
 * folder pages lay it out - the individual songs (the non-folder rows) at
 * the top level, each added folder a top-level folder row carrying its own
 * imported structure - with a duplicate label offered once (the first
 * stored copy, in the content-list order), and the tree's render order
 * ([FolderTreeLevel.rowsInTreeOrder]) - the order the picker's Save
 * button commits its checked selection in.
 *
 * Robolectric supplies the framework's [android.net.Uri] parsing (a plain
 * unit test's android.net.Uri is unmocked); the logic itself is
 * framework-free.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TagSongPickerTreeTest {

    private fun entry(id: Long, label: String, payload: String, folderId: Long? = null): EntryEntity =
        EntryEntity(
            id = id,
            type = EntryType.AUDIO.storageValue,
            label = label,
            payload = payload,
            folderId = folderId,
        )

    /** The tree-scoped content payload under the picked tree (the docId
     *  percent-encoded into its single path segment, the stored payload's
     *  shape). */
    private fun payload(docId: String): String =
        "content://com.android.providers.documents/tree/primary%3AMusic/document/" + Uri.encode(docId, null)

    private val treeUri = "content://com.android.providers.documents/tree/primary%3AMusic"

    private fun folder(id: Long = 42): FolderEntity =
        FolderEntity(id = id, uri = treeUri, label = "Music")

    @Test
    fun individualSongsSitAtTheTopLevel_andTheFolderRowCarriesItsImportedStructure() {
        val entries = listOf(
            entry(1, "A song", "content://first"),
            entry(2, "B song", "content://second"),
            entry(3, "Root song", payload("primary:Music/root.mp3"), folderId = 42),
            entry(4, "Album song", payload("primary:Music/Album/album.mp3"), folderId = 42),
        )

        val tree = tagSongPickerTree(entries, listOf(folder()))

        // The individual songs (the non-folder rows) sit at the top level.
        assertEquals(listOf("A song", "B song"), tree.songs.map { it.label })
        // The added folder is a top-level folder row carrying its own
        // imported structure: a subfolder and the songs at each level.
        assertEquals(1, tree.folders.size)
        val music = tree.folders.single()
        assertEquals("Music", music.displayName)
        assertEquals(1, music.children.folders.size)
        val album = music.children.folders.single()
        assertEquals("Album", album.displayName)
        assertEquals(listOf("Album song"), album.children.songs.map { it.label })
        assertEquals(listOf("Root song"), music.children.songs.map { it.label })
    }

    @Test
    fun aDuplicateLabelIsOfferedOnce_asTheFirstStoredCopy() {
        // "First" is stored individually AND inside the folder: the
        // content-list-order first copy (the individual row) is offered;
        // the folder's copy is not (it is not the first stored copy).
        val entries = listOf(
            entry(1, "First", "content://first"),
            entry(2, "Second", "content://second"),
            entry(3, "First", payload("primary:Music/first.mp3"), folderId = 42),
            entry(4, "Folder only", payload("primary:Music/only.mp3"), folderId = 42),
        )

        val tree = tagSongPickerTree(entries, listOf(folder()))

        val offered = tree.rowsInTreeOrder()
        assertEquals(
            "the duplicate is offered once, in the tree's render order (the folder's rows first)",
            listOf(4L, 1L, 2L),
            offered.map { it.id },
        )
        // The folder row survives (it carries "Folder only") but does not
        // re-offer "First".
        assertEquals(1, tree.folders.size)
        assertEquals(listOf("Folder only"), tree.folders.single().children.songs.map { it.label })
        assertEquals(listOf("First", "Second"), tree.songs.map { it.label })
    }

    @Test
    fun anAddedFolderWithNoOfferedRowsShowsNoRowOfItsOwn() {
        // The folder's only song is a duplicate of an individually stored
        // row (stored earlier, in the content-list order): the folder
        // carries no offered rows and shows no row of its own.
        val entries = listOf(
            entry(1, "First", "content://first"),
            entry(2, "First", payload("primary:Music/first.mp3"), folderId = 42),
        )

        val tree = tagSongPickerTree(entries, listOf(folder()))

        assertEquals(emptyList<FolderTreeFolder>(), tree.folders)
        assertEquals(listOf("First"), tree.songs.map { it.label })
    }

    @Test
    fun rowsInTreeOrder_listsFoldersFirst_withTheirNestedRowsAfterThemAndTheLevelSongsLast() {
        val entries = listOf(
            entry(1, "Alpha", "content://a"),
            entry(2, "Zeta", "content://z"),
            entry(3, "Root song", payload("primary:Music/root.mp3"), folderId = 42),
            entry(4, "Album song", payload("primary:Music/Album/album.mp3"), folderId = 42),
            entry(5, "Deep song", payload("primary:Music/Album/Deep/deep.mp3"), folderId = 42),
        )

        val tree = tagSongPickerTree(entries, listOf(folder()))
        val order = tree.rowsInTreeOrder().map { it.id }

        // The tree's render order: the top-level folder row first, then
        // its nested rows depth-first (the deepest subfolder's songs
        // first - Deep's song, then Album's own song), then the top
        // level's own songs, then the top level's songs.
        assertEquals(listOf(5L, 4L, 3L, 1L, 2L), order)
    }

    @Test
    fun sortedTree_appliesThePerLevelOrder_toThePickerTree() {
        val entries = listOf(
            entry(1, "Bela", "content://b"),
            entry(2, "Alfa", "content://a"),
            entry(3, "Root song", payload("primary:Music/root.mp3"), folderId = 42),
            entry(4, "A song", payload("primary:Music/Album/a.mp3"), folderId = 42),
            entry(5, "Z song", payload("primary:Music/Album/z.mp3"), folderId = 42),
        )

        val ascending = tagSongPickerTree(entries, listOf(folder())).sortedTree(ContentSortDirection.ASCENDING)
        assertEquals(listOf("Alfa", "Bela"), ascending.songs.map { it.label })
        assertEquals(listOf("A song", "Z song"), ascending.folders.single().children.folders.single().children.songs.map { it.label })

        val descending = tagSongPickerTree(entries, listOf(folder())).sortedTree(ContentSortDirection.DESCENDING)
        assertEquals(listOf("Bela", "Alfa"), descending.songs.map { it.label })
        assertEquals(listOf("Z song", "A song"), descending.folders.single().children.folders.single().children.songs.map { it.label })
    }

    @Test
    fun multipleFoldersKeepTheirListedOrder_andRowsAreKeptPerFolder() {
        val tree2 = "content://com.android.providers.documents/tree/primary%3APodcasts"
        val podcastFolder = FolderEntity(id = 43, uri = tree2, label = "Podcasts")
        val entries = listOf(
            entry(1, "M song", "content://m"),
            entry(2, "M root", payload("primary:Music/m.mp3"), folderId = 42),
            entry(3, "P song", payload("primary:Podcasts/p.mp3"), folderId = 43),
        )

        val tree = tagSongPickerTree(entries, listOf(folder(), podcastFolder))

        assertEquals(listOf("Music", "Podcasts"), tree.folders.map { it.displayName })
        assertEquals(listOf("M song"), tree.songs.map { it.label })
        assertEquals(listOf("M root"), tree.folders[0].children.songs.map { it.label })
        assertEquals(listOf("P song"), tree.folders[1].children.songs.map { it.label })
        // The rowsInTreeOrder keeps the folders' listed order.
        assertEquals(listOf(2L, 3L, 1L), tree.rowsInTreeOrder().map { it.id })
    }

    @Test
    fun equalLabelTopLevelFoldersKeyByTheirId_soTheyCollapseIndependently() {
        val usbTree = "content://com.android.providers.documents/tree/usb%3AMusic"
        val entries = listOf(
            entry(1, "Internal song", "content://int", folderId = 42),
            entry(2, "External song", "content://ext", folderId = 43),
        )
        val tree = tagSongPickerTree(
            entries,
            listOf(
                FolderEntity(id = 42, uri = treeUri, label = "Music"),
                FolderEntity(id = 43, uri = usbTree, label = "Music"),
            ),
        )

        assertEquals(listOf("Music", "Music"), tree.folders.map { it.displayName })
        // The two equal-label rows carry their OWN keys (the added
        // folder's id, not the shared label): collapsing one names one
        // folder.
        assertEquals(listOf(listOf("42"), listOf("43")), tree.folders.map { it.collapseKey(emptyList()) })
        // Collapsing one hides exactly its own rows; the other's stay.
        assertEquals(4, tree.visibleRowCount(emptySet()))
        assertEquals(3, tree.visibleRowCount(setOf(listOf("42"))))
        assertEquals(3, tree.visibleRowCount(setOf(listOf("43"))))
        // The shared label alone keys neither row.
        assertEquals(4, tree.visibleRowCount(setOf(listOf("Music"))))
    }

    @Test
    fun sameNamedSubfoldersUnderEqualLabelFoldersCollapseIndependently() {
        val usbTree = "content://com.android.providers.documents/tree/usb%3AMusic"
        val entries = listOf(
            entry(1, "Internal album song", payload("primary:Music/Albums/ia.mp3"), folderId = 42),
            entry(2, "External album song", payload("usb:Music/Albums/ea.mp3"), folderId = 43),
        )
        val tree = tagSongPickerTree(
            entries,
            listOf(
                FolderEntity(id = 42, uri = treeUri, label = "Music"),
                FolderEntity(id = 43, uri = usbTree, label = "Music"),
            ),
        )

        val first = tree.folders[0]
        val second = tree.folders[1]
        // Each "Music" row carries its own same-named subfolder: the
        // subfolder's key leads with the ADDED FOLDER'S ID (not the
        // shared label), so the two "Albums" rows name distinct nodes.
        assertEquals(
            listOf("42", "Albums"),
            first.children.folders.single().collapseKey(first.collapseKey(emptyList())),
        )
        assertEquals(
            listOf("43", "Albums"),
            second.children.folders.single().collapseKey(second.collapseKey(emptyList())),
        )
        // Collapsing one subfolder hides exactly its own song; the
        // other equal-named subfolder's song stays.
        assertEquals(6, tree.visibleRowCount(emptySet()))
        assertEquals(5, tree.visibleRowCount(setOf(listOf("42", "Albums"))))
        assertEquals(5, tree.visibleRowCount(setOf(listOf("43", "Albums"))))
        assertEquals(4, tree.visibleRowCount(setOf(listOf("42", "Albums"), listOf("43", "Albums"))))
        // The label-led key names nothing any more.
        assertEquals(6, tree.visibleRowCount(setOf(listOf("Music", "Albums"))))
    }

    @Test
    fun emptyContentYieldsAnEmptyTree() {
        val tree = tagSongPickerTree(emptyList(), emptyList())
        assertTrue(tree.folders.isEmpty())
        assertTrue(tree.songs.isEmpty())
    }
}
