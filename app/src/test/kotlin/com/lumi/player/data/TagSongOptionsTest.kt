package com.lumi.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure coverage of the in-app add-song list's collapse: duplicate content
 * (the same label stored more than once) displays once - the first stored
 * copy - and a song the tag already plays (directly, or via a duplicate
 * label) is marked, so the pick is reported instead of silently linked.
 */
class TagSongOptionsTest {

    private fun entry(id: Long, label: String, payload: String, folderId: Long? = null): EntryEntity =
        EntryEntity(
            id = id,
            type = EntryType.AUDIO.storageValue,
            label = label,
            payload = payload,
            folderId = folderId,
        )

    @Test
    fun oneRowPerDistinctLabel_inContentOrder() {
        val content = listOf(
            entry(1, "First", "content://first"),
            entry(2, "Second", "content://second"),
            entry(3, "First", "content://first-dup"),
            entry(4, "Third", "content://third"),
        )

        val options = tagSongOptions(content, emptyList())

        assertEquals(listOf("First", "Second", "Third"), options.map { it.entry.label })
        assertEquals(
            "the collapsed row carries the FIRST stored copy",
            listOf(1L, 2L, 4L),
            options.map { it.entry.id },
        )
        assertEquals("nothing is on the tag yet", listOf(false, false, false), options.map { it.alreadyOnTag })
    }

    @Test
    fun aSongTheTagAlreadyPlaysIsMarked_alreadyOnTag() {
        val content = listOf(
            entry(1, "First", "content://first"),
            entry(2, "Second", "content://second"),
            entry(3, "Third", "content://third"),
        )
        val tagEntries = listOf(
            entry(9, "First", "content://first"),
            entry(10, "Third", "content://third"),
        )

        val options = tagSongOptions(content, tagEntries)

        assertEquals(listOf(true, false, true), options.map { it.alreadyOnTag })
    }

    @Test
    fun alreadyOnTag_matchesByLabel_soADuplicateTagEntryMarksTheRow() {
        // The tag plays a copy of the song (a different stored row, same
        // label): picking the content's row must be reported, not linked.
        val content = listOf(entry(1, "First", "content://first"))
        val tagEntries = listOf(entry(7, "First", "content://first-other-copy"))

        val options = tagSongOptions(content, tagEntries)

        assertEquals(true, options.single().alreadyOnTag)
    }

    @Test
    fun aFolderImportedEntryIsOfferedLikeAnyOtherContent() {
        val content = listOf(entry(1, "Folder song", "content://docs/tree/f/document/x", folderId = 42))

        val options = tagSongOptions(content, emptyList())

        assertEquals(1, options.size)
        assertEquals("Folder song", options.single().entry.label)
        assertEquals(false, options.single().alreadyOnTag)
    }

    @Test
    fun emptyContentYieldsAnEmptyList() {
        assertEquals(emptyList<TagSongOption>(), tagSongOptions(emptyList(), emptyList()))
    }
}
