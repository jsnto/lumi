package com.lumi.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the picked-folder audio scan: the recursive walk over a
 * documents tree (nested folders, unsupported files, empty trees) and the
 * supported-audio extension filter.
 */
class FolderAudioScanTest {

    private fun file(name: String, uri: String) =
        FolderChild(displayName = name, isDirectory = false, childUri = uri)

    private fun dir(name: String, uri: String) =
        FolderChild(displayName = name, isDirectory = true, childUri = uri)

    private fun childrenOf(lookup: Map<String, List<FolderChild>>) =
        { folderUri: String -> lookup[folderUri] ?: emptyList() }

    // ---- recursion ----

    @Test
    fun emptyFolderFindsNothing() {
        assertTrue(collectAudioFiles("root", childrenOf(emptyMap())).isEmpty())
    }

    @Test
    fun folderWithoutSupportedFilesFindsNothing() {
        val tree = childrenOf(
            mapOf(
                "root" to listOf(
                    file("notes.txt", "c://x/1"),
                    file("photo.jpg", "c://x/2"),
                    file("noextension", "c://x/3"),
                    dir("sub", "c://x/4"),
                ),
                "c://x/4" to listOf(file("more.pdf", "c://x/5")),
            ),
        )
        assertTrue(collectAudioFiles("root", tree).isEmpty())
    }

    @Test
    fun songsFoundAtEveryDepthInListingOrder() {
        val tree = childrenOf(
            mapOf(
                "root" to listOf(
                    file("a.mp3", "c://x/a"),
                    dir("sub", "c://x/sub"),
                    file("notes.txt", "c://x/notes"),
                    dir("deep", "c://x/deep"),
                ),
                "c://x/sub" to listOf(
                    dir("deeper", "c://x/sub/deeper"),
                    file("b.flac", "c://x/b"),
                ),
                "c://x/sub/deeper" to listOf(file("c.wav", "c://x/c")),
                "c://x/deep" to emptyList(),
            ),
        )
        val songs = collectAudioFiles("root", tree)
        // Pre-order: a subfolder's songs come before the root's songs listed
        // after that subfolder.
        assertEquals(
            listOf("a.mp3", "c.wav", "b.flac"),
            songs.map { it.displayName },
        )
        assertEquals(listOf("c://x/a", "c://x/c", "c://x/b"), songs.map { it.uri })
    }

    @Test
    fun unsupportedFilesAreSkippedButTheirAudioSiblingsKept() {
        val tree = childrenOf(
            mapOf(
                "root" to listOf(
                    file("song1.mp3", "c://x/1"),
                    file("cover.jpg", "c://x/2"),
                    file("song2.m4a", "c://x/3"),
                    file("readme.md", "c://x/4"),
                ),
            ),
        )
        assertEquals(
            listOf("song1.mp3", "song2.m4a"),
            collectAudioFiles("root", tree).map { it.displayName },
        )
    }

    @Test
    fun aSubfolderIsWalkedOnlyWhenItIsMarkedDirectory() {
        // A file that merely looks like a folder name must not be recursed.
        val tree = { folderUri: String ->
            when (folderUri) {
                "root" -> listOf(dir("albums", "c://x/d"), file("odd.mp3", "c://x/f"))
                // Children under "c://x/f" must never be consulted.
                "c://x/f" -> error("a non-directory must not be walked")
                "c://x/d" -> listOf(file("deep-note.pdf", "c://x/g"), file("deep1.aac", "c://x/h"))
                else -> emptyList()
            }
        }
        // Pre-order: the subfolder's song precedes the root's own song.
        assertEquals(listOf("deep1.aac", "odd.mp3"), collectAudioFiles("root", tree).map { it.displayName })
    }

    // ---- extension filter ----

    @Test
    fun supportedExtensionsAreRecognized() {
        for (name in listOf("s.mp3", "s.m4a", "s.aac", "s.flac", "s.wav")) {
            assertTrue("$name must be supported", isSupportedAudioFile(name))
        }
    }

    @Test
    fun extensionMatchIsCaseInsensitive() {
        assertTrue(isSupportedAudioFile("Song.MP3"))
        assertTrue(isSupportedAudioFile("Song.FLAC"))
    }

    @Test
    fun unsupportedExtensionsAndMissingExtensionsAreRejected() {
        for (name in listOf("s.pdf", "s.txt", "s.jpg", "noextension", "s.", "s.mp3x")) {
            assertTrue("$name must be rejected", !isSupportedAudioFile(name))
        }
    }

    @Test
    fun theLastExtensionWins() {
        // "photo.jpg.mp3" ends in an audio extension and is treated as one.
        assertTrue(isSupportedAudioFile("photo.jpg.mp3"))
        assertTrue(!isSupportedAudioFile("song.mp3.txt"))
    }

    // ---- rescan of an added folder ----

    @Test
    fun rescanPicksUpANewFileInTheRootFolder() {
        val tree = childrenOf(
            mapOf(
                "root" to listOf(
                    file("old.mp3", "c://x/old"),
                    file("new-song.mp3", "c://x/new"),
                ),
            ),
        )
        val newSongs = rescanNewSongs("root", storedPayloads("c://x/old"), tree)
        assertEquals(listOf("new-song.mp3"), newSongs.map { it.displayName })
    }

    @Test
    fun rescanPicksUpANewFileInASubfolder() {
        val tree = childrenOf(
            mapOf(
                "root" to listOf(dir("albums", "c://x/sub")),
                "c://x/sub" to listOf(file("added-later.flac", "c://x/later")),
            ),
        )
        // Nothing was stored before; the new song lives in a subfolder.
        val newSongs = rescanNewSongs("root", emptySet(), tree)
        assertEquals(listOf("added-later.flac"), newSongs.map { it.displayName })
    }

    @Test
    fun rescanSkipsFilesAlreadyStored() {
        val tree = childrenOf(
            mapOf(
                "root" to listOf(
                    file("song-a.mp3", "c://x/a"),
                    file("song-b.mp3", "c://x/b"),
                ),
            ),
        )
        val newSongs = rescanNewSongs("root", storedPayloads("c://x/a"), tree)
        // Only the not-yet-stored file is returned.
        assertEquals(listOf("song-b.mp3"), newSongs.map { it.displayName })
    }

    @Test
    fun rescanOfAnUnchangedFolderIsANoOp() {
        val tree = childrenOf(
            mapOf(
                "root" to listOf(file("song-a.mp3", "c://x/a")),
            ),
        )
        // Every song in the folder is already stored: the rescan is empty.
        assertTrue(rescanNewSongs("root", storedPayloads("c://x/a"), tree).isEmpty())
    }

    @Test
    fun rescanIgnoresUnsupportedFilesItWalks() {
        val tree = childrenOf(
            mapOf(
                "root" to listOf(
                    file("notes.txt", "c://x/n"),
                    file("brand-new.m4a", "c://x/m"),
                ),
            ),
        )
        val newSongs = rescanNewSongs("root", emptySet(), tree)
        assertEquals(listOf("brand-new.m4a"), newSongs.map { it.displayName })
    }

    private fun storedPayloads(vararg payloads: String): Set<String> = payloads.toSet()
}
