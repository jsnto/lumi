package com.lumi.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The content list's sort control (pure): the title ordering (case-
 * insensitive, the id breaking ties so the order is deterministic),
 * descending being the exact reverse of ascending, and the stored
 * direction's read-back (descending when unset or invalid).
 */
class ContentSortTest {

    private fun entry(id: Long, label: String) =
        EntryEntity(id = id, type = "AUDIO", label = label, payload = "content://x/$id")

    @Test
    fun `ascending orders by title case-insensitively and ties by id`() {
        val list = listOf(
            entry(3, "bravo"),
            entry(5, "alpha"),
            entry(1, "Alpha"),
            entry(2, "Alpha"),
        )
        val sorted = list.sortedByTitle({ it.label }, { it.id }, ContentSortDirection.ASCENDING)
        assertEquals(listOf(1L, 2L, 5L, 3L), sorted.map { it.id })
    }

    @Test
    fun `descending is the exact reverse of the ascending order`() {
        val list = listOf(entry(1, "c"), entry(2, "a"), entry(3, "b"), entry(4, "A"))
        val asc = list.sortedByTitle({ it.label }, { it.id }, ContentSortDirection.ASCENDING)
        val desc = list.sortedByTitle({ it.label }, { it.id }, ContentSortDirection.DESCENDING)
        assertEquals(asc.asReversed(), desc)
    }

    @Test
    fun `sorting returns a new list and leaves the stored order untouched`() {
        val list = listOf(entry(1, "b"), entry(2, "a"))
        list.sortedByTitle({ it.label }, { it.id }, ContentSortDirection.DESCENDING)
        assertEquals("the stored order is the source list's own", listOf(1L, 2L), list.map { it.id })
    }

    @Test
    fun `the default direction is descending and the stored values round-trip`() {
        assertEquals(ContentSortDirection.DESCENDING, contentSortDirectionFromStored(null))
        assertEquals(ContentSortDirection.DESCENDING, contentSortDirectionFromStored(""))
        assertEquals(ContentSortDirection.DESCENDING, contentSortDirectionFromStored("sideways"))
        assertEquals(ContentSortDirection.DESCENDING, contentSortDirectionFromStored("desc"))
        assertEquals(ContentSortDirection.ASCENDING, contentSortDirectionFromStored("asc"))
        assertEquals(ContentSortDirection.ASCENDING, contentSortDirectionFromStored("ASC "))
        assertEquals("asc", ContentSortDirection.ASCENDING.storedValue())
        assertEquals("desc", ContentSortDirection.DESCENDING.storedValue())
    }

    @Test
    fun `folders sort by their label through the same seam`() {
        val folders = listOf(
            FolderEntity(id = 2, uri = "content://t/b", label = "walkin"),
            FolderEntity(id = 1, uri = "content://t/a", label = "Zephyr"),
            FolderEntity(id = 3, uri = "content://t/c", label = "alpha"),
        )
        val desc = folders.sortedByTitle({ it.label }, { it.id }, ContentSortDirection.DESCENDING)
        assertEquals(listOf(1L, 2L, 3L), desc.map { it.id })
    }
}
