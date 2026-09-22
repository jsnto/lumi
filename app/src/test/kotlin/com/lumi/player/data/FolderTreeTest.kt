package com.lumi.player.data

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The folder detail page's tree (pure): the picked folder's directory
 * structure rebuilt from the folder's stored song rows (their payloads are
 * the tree's content uris, whose document ids encode the full path below
 * the picked root), the docId grammar the rebuild reads, and the per-level
 * order the page's sort control applies (subfolders first, then songs, at
 * every depth - the content list's own case-insensitive title sort).
 *
 * Robolectric supplies the framework's [Uri] parsing (a plain unit test's
 * android.net.Uri is unmocked); the logic itself is framework-free.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FolderTreeTest {

    /** The picker's tree uri for the folder whose docId is [ROOT_DOC_ID]. */
    private val treeUri = "content://docs/tree/" + Uri.encode(ROOT_DOC_ID, null)

    /** One stored song row whose payload is the tree-scoped content uri of [docId]. */
    private fun entry(id: Long, label: String, docId: String): EntryEntity =
        EntryEntity(
            id = id,
            type = "AUDIO",
            label = label,
            payload = "content://docs/tree/" + Uri.encode(ROOT_DOC_ID, null) + "/document/" + Uri.encode(docId, null),
        )

    // ---- the docId grammar ----

    @Test
    fun thePickerTreeUriCarriesThePickedFolderDocIdWithTheOpenableSuffixStripped() {
        assertEquals(
            ROOT_DOC_ID,
            folderDocIdOf(Uri.parse("content://docs/tree/" + Uri.encode("$ROOT_DOC_ID:openable", null))),
        )
        assertEquals(ROOT_DOC_ID, folderDocIdOf(Uri.parse(treeUri)))
    }

    @Test
    fun aTreeScopedNodeUriCarriesItsDocIdVerbatim() {
        assertEquals(
            "$ROOT_DOC_ID/Albums",
            folderDocIdOf(
                Uri.parse("content://docs/tree/" + Uri.encode(ROOT_DOC_ID, null) + "/document/" + Uri.encode("$ROOT_DOC_ID/Albums", null)),
            ),
        )
    }

    @Test
    fun anUnrecognizedUriDegradesToItsSecondSegment() {
        assertEquals("audio:1:tree", folderDocIdOf(Uri.parse("content://docs/document/audio%3A1:tree")))
    }

    // ---- structure ----

    @Test
    fun rootSongsSitAtTheTopLevelAndSubfoldersCarryTheirNestedSongs() {
        val tree = folderTreeStructure(
            Uri.parse(treeUri),
            listOf(
                entry(1, "root-a.mp3", "$ROOT_DOC_ID/root-a.mp3"),
                entry(2, "albums-b.flac", "$ROOT_DOC_ID/Albums/albums-b.flac"),
                entry(3, "root-c.mp3", "$ROOT_DOC_ID/root-c.mp3"),
            ),
        )
        assertEquals(listOf(1L, 3L), tree.songs.map { it.id })
        assertEquals(1, tree.folders.size)
        val albums = tree.folders.single()
        assertEquals("Albums", albums.displayName)
        assertEquals(listOf(2L), albums.children.songs.map { it.id })
        assertTrue("the subfolder itself holds no root songs", albums.children.folders.isEmpty())
    }

    @Test
    fun deeperSubfoldersIndentFurther() {
        val tree = folderTreeStructure(
            Uri.parse(treeUri),
            listOf(entry(1, "deep.wav", "$ROOT_DOC_ID/Artists/Artist Name/Album/deep.wav")),
        )
        val artists = tree.folders.single()
        assertEquals("Artists", artists.displayName)
        val artist = artists.children.folders.single()
        assertEquals("Artist Name", artist.displayName)
        val album = artist.children.folders.single()
        assertEquals("Album", album.displayName)
        assertEquals(listOf(1L), album.children.songs.map { it.id })
        assertTrue("no level between the root and the album may hold the song",
            artists.children.songs.isEmpty() && artist.children.songs.isEmpty())
    }

    @Test
    fun aPayloadOutsideTheTreeDegradesToTheTopLevel_visibleNotLost() {
        val tree = folderTreeStructure(
            Uri.parse(treeUri),
            listOf(
                EntryEntity(id = 1, type = "AUDIO", label = "legacy", payload = "content://docs/document/audio%3A9"),
                entry(2, "placed.mp3", "$ROOT_DOC_ID/Placed/placed.mp3"),
            ),
        )
        // The unmappable row degrades to the top level; the tree-scoped row
        // still nests under its subfolder.
        assertEquals(listOf(1L), tree.songs.map { it.id })
        assertEquals(listOf("Placed"), tree.folders.map { it.displayName })
        assertEquals(listOf(2L), tree.folders.single().children.songs.map { it.id })
    }

    @Test
    fun thePickerOpenableSuffixStillMatchesTheStoredPayloads() {
        // The folders table stores the picker uri verbatim (the ":openable"
        // suffix and all): the rebuild must still place the songs under it.
        val openableTree = "content://docs/tree/" + Uri.encode("$ROOT_DOC_ID:openable", null)
        val tree = folderTreeStructure(Uri.parse(openableTree), listOf(entry(1, "s.mp3", "$ROOT_DOC_ID/s.mp3")))
        assertEquals(listOf(1L), tree.songs.map { it.id })
        assertTrue(tree.folders.isEmpty())
    }

    @Test
    fun aVolumeRootPickPlacesChildrenThroughTheColonJoin() {
        // A pick at the volume root: the root docId is the bare volume id
        // ("primary"), and the provider joins the volume id and path with
        // ':' rather than '/' - a child's docId looks like
        // "primary:Music/song.mp3".
        fun volumeEntry(id: Long, label: String, docId: String): EntryEntity =
            EntryEntity(
                id = id,
                type = "AUDIO",
                label = label,
                payload = "content://docs/tree/primary/document/" + Uri.encode(docId, null),
            )
        val tree = folderTreeStructure(
            Uri.parse("content://docs/tree/primary"),
            listOf(
                volumeEntry(1, "song.mp3", "primary:Music/song.mp3"),
                volumeEntry(2, "root-song.mp3", "primary:root-song.mp3"),
            ),
        )
        assertEquals(listOf(2L), tree.songs.map { it.id })
        assertEquals(listOf("Music"), tree.folders.map { it.displayName })
        assertEquals(listOf(1L), tree.folders.single().children.songs.map { it.id })
        assertTrue("the volume root's own level holds no root songs elsewhere", tree.folders.single().children.folders.isEmpty())
    }

    @Test
    fun aSubfolderPickWithAColonInTheFolderNameStillResolvesThroughTheSlashJoin() {
        // The picked folder's docId contains ':' (the folder "Al:bum" under
        // the volume root) but no '/': its children are joined with '/', so
        // the file must resolve through the slash case against the FULL
        // root docId - stripping only the volume part would place it under
        // a spurious "Al:bum" folder row.
        val colonTreeUri = "content://docs/tree/" + Uri.encode("primary:Al:bum", null)
        val tree = folderTreeStructure(
            Uri.parse(colonTreeUri),
            listOf(
                EntryEntity(
                    id = 1,
                    type = "AUDIO",
                    label = "x.mp3",
                    payload = colonTreeUri + "/document/" + Uri.encode("primary:Al:bum/x.mp3", null),
                ),
            ),
        )
        assertEquals(listOf(1L), tree.songs.map { it.id })
        assertTrue("a direct file under the root sits at the top level, in no folder row", tree.folders.isEmpty())
    }

    @Test
    fun emptyEntriesBuildAnEmptyTree() {
        val tree = folderTreeStructure(Uri.parse(treeUri), emptyList())
        assertTrue(tree.songs.isEmpty())
        assertTrue(tree.folders.isEmpty())
    }

    // ---- the show/hide row count ----

    @Test
    fun expandedLevelsRenderEveryRow() {
        val tree = folderTreeStructure(
            Uri.parse(treeUri),
            listOf(
                entry(1, "root-a.mp3", "$ROOT_DOC_ID/root-a.mp3"),
                entry(2, "albums-b.flac", "$ROOT_DOC_ID/Albums/albums-b.flac"),
                entry(3, "deep-c.flac", "$ROOT_DOC_ID/Albums/Deep/deep-c.flac"),
            ),
        )
        // root-a, Albums, albums-b, Deep, deep-c: every row of the tree.
        assertEquals(5, tree.visibleRowCount(emptySet()))
    }

    @Test
    fun collapsingAFolderHidesItsDescendantsButKeepsItsOwnRow() {
        val tree = folderTreeStructure(
            Uri.parse(treeUri),
            listOf(
                entry(1, "root-a.mp3", "$ROOT_DOC_ID/root-a.mp3"),
                entry(2, "albums-b.flac", "$ROOT_DOC_ID/Albums/albums-b.flac"),
                entry(3, "deep-c.flac", "$ROOT_DOC_ID/Albums/Deep/deep-c.flac"),
            ),
        )
        // Albums collapsed: its song AND its nested subfolder's rows are
        // hidden, Albums' own row stays, and the root's row is untouched.
        assertEquals(2, tree.visibleRowCount(setOf(listOf("Albums"))))
    }

    @Test
    fun collapsingANestedFolderHidesOnlyThatFolderContent() {
        val tree = folderTreeStructure(
            Uri.parse(treeUri),
            listOf(
                entry(1, "root-a.mp3", "$ROOT_DOC_ID/root-a.mp3"),
                entry(2, "albums-b.flac", "$ROOT_DOC_ID/Albums/albums-b.flac"),
                entry(3, "deep-c.flac", "$ROOT_DOC_ID/Albums/Deep/deep-c.flac"),
            ),
        )
        // Deep collapsed: only its own song hides; Albums' direct song and
        // Deep's row stay.
        assertEquals(4, tree.visibleRowCount(setOf(listOf("Albums", "Deep"))))
    }

    @Test
    fun collapseKeysArePathFromTheRoot_soSameNamesAtDifferentLevelsDoNotCollide() {
        val tree = folderTreeStructure(
            Uri.parse(treeUri),
            listOf(
                // Two "Inner" folders under different roots: the keys must
                // name the full path, not the bare name.
                entry(1, "a-x.mp3", "$ROOT_DOC_ID/Alpha/Inner/a-x.mp3"),
                entry(2, "b-y.mp3", "$ROOT_DOC_ID/Beta/Inner/b-y.mp3"),
            ),
        )
        assertEquals(6, tree.visibleRowCount(emptySet()))
        // Colliding on the bare name would hide nothing specific; the path
        // key hides exactly the named folder's content.
        assertEquals(5, tree.visibleRowCount(setOf(listOf("Alpha", "Inner"))))
        assertEquals(5, tree.visibleRowCount(setOf(listOf("Beta", "Inner"))))
        // A stale key at the wrong depth hides nothing.
        assertEquals(6, tree.visibleRowCount(setOf(listOf("Inner"))))
    }

    // ---- the per-level order ----

    @Test
    fun atEachLevelSubfoldersComeFirstThenSongs() {
        val tree = FolderTreeLevel(
            folders = listOf(FolderTreeFolder("zeta", FolderTreeLevel(emptyList(), emptyList()))),
            songs = listOf(entry(1, "alpha", "$ROOT_DOC_ID/alpha.mp3")),
        )
        // The order the UI reads: folders sorted, then songs sorted, at the
        // level - descending here puts "zeta" above the songs either way,
        // so pin the groupings explicitly.
        val sorted = tree.sortedTree(ContentSortDirection.ASCENDING)
        assertEquals(listOf("zeta"), sorted.folders.map { it.displayName })
        assertEquals(listOf(1L), sorted.songs.map { it.id })
    }

    @Test
    fun songsSortByTitleCaseInsensitivelyAndFoldersByDisplayNameAtEveryLevel() {
        val tree = FolderTreeLevel(
            folders = listOf(
                FolderTreeFolder("bravo", FolderTreeLevel(emptyList(), listOf(entry(10, "in-b1", "$ROOT_DOC_ID/bravo/x")))),
                FolderTreeFolder("alpha", FolderTreeLevel(emptyList(), emptyList())),
                FolderTreeFolder("ALPHA", FolderTreeLevel(emptyList(), emptyList())),
            ),
            songs = listOf(
                entry(1, "zulu.mp3", "$ROOT_DOC_ID/z.mp3"),
                entry(2, "mango.mp3", "$ROOT_DOC_ID/m.mp3"),
                entry(3, "alpha.mp3", "$ROOT_DOC_ID/a.mp3"),
            ),
        )
        val ascending = tree.sortedTree(ContentSortDirection.ASCENDING)
        // The case-insensitive tie ("alpha" / "ALPHA") keeps the stored
        // order (a stable sort), the content list's own rule.
        assertEquals(listOf("alpha", "ALPHA", "bravo"), ascending.folders.map { it.displayName })
        assertEquals(listOf(3L, 2L, 1L), ascending.songs.map { it.id })
        // The order is applied recursively: the subfolder's own songs are
        // sorted too.
        val deep = tree.sortedTree(ContentSortDirection.ASCENDING)
            .folders.first { it.displayName == "bravo" }
            .children
        assertEquals(listOf(10L), deep.songs.map { it.id })

        val descending = tree.sortedTree(ContentSortDirection.DESCENDING)
        // Descending is the exact reverse of the ascending order.
        assertEquals(listOf("bravo", "ALPHA", "alpha"), descending.folders.map { it.displayName })
        assertEquals(listOf(1L, 2L, 3L), descending.songs.map { it.id })
    }

    @Test
    fun descendingIsTheExactReverseOfAscending() {
        val tree = FolderTreeLevel(
            folders = listOf(
                FolderTreeFolder("b", FolderTreeLevel(emptyList(), emptyList())),
                FolderTreeFolder("a", FolderTreeLevel(emptyList(), emptyList())),
            ),
            songs = listOf(
                entry(1, "x.mp3", "$ROOT_DOC_ID/x.mp3"),
                entry(2, "y.mp3", "$ROOT_DOC_ID/y.mp3"),
            ),
        )
        val ascending = tree.sortedTree(ContentSortDirection.ASCENDING)
        val descending = tree.sortedTree(ContentSortDirection.DESCENDING)
        assertEquals(ascending.folders.map { it.displayName }.asReversed(), descending.folders.map { it.displayName })
        assertEquals(ascending.songs.map { it.id }.asReversed(), descending.songs.map { it.id })
    }

    @Test
    fun sortingReturnsANewTreeAndLeavesTheBuiltOneUntouched() {
        val tree = folderTreeStructure(
            Uri.parse(treeUri),
            listOf(
                entry(1, "zulu.mp3", "$ROOT_DOC_ID/z.mp3"),
                entry(2, "alpha.mp3", "$ROOT_DOC_ID/a.mp3"),
            ),
        )
        val storedOrder = tree.songs.map { it.id }
        tree.sortedTree(ContentSortDirection.ASCENDING)
        assertEquals(storedOrder, tree.songs.map { it.id })
    }

    private companion object {
        const val ROOT_DOC_ID = "primary:Music/Walkin"
    }
}
