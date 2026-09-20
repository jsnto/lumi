package com.lumi.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the tag link reorder logic: [reorderEntryIds] moves the
 * entry at [fromIndex] to [toIndex] while every other entry keeps its
 * relative order, and is a no-op for empty, single-item, out-of-range, or
 * equal-index cases so raw drag math needs no clamping.
 */
class LinkedReorderTest {

    @Test
    fun reorder_movesTheEntryToTheNewPosition() {
        val current = listOf(1L, 2L, 3L, 4L)
        assertEquals(listOf(2L, 3L, 4L, 1L), reorderEntryIds(current, 0, 3))
        assertEquals(listOf(4L, 1L, 2L, 3L), reorderEntryIds(current, 3, 0))
        assertEquals(listOf(1L, 3L, 2L, 4L), reorderEntryIds(current, 1, 2))
        assertEquals(listOf(3L, 1L, 2L, 4L), reorderEntryIds(current, 2, 0))
    }

    @Test
    fun reorder_untouchedEntriesKeepTheirRelativeOrder() {
        val current = listOf(10L, 20L, 30L, 40L, 50L)
        // Moving the first entry to the end must keep 20, 30, 40, 50 in order.
        val moved = reorderEntryIds(current, 0, 4)
        assertEquals(listOf(20L, 30L, 40L, 50L), moved.subList(0, 4))
        assertEquals(10L, moved.last())
        // Moving the last entry to the front must keep 10, 20, 30, 40 in order.
        val movedToFront = reorderEntryIds(current, 4, 0)
        assertEquals(50L, movedToFront.first())
        assertEquals(listOf(10L, 20L, 30L, 40L), movedToFront.subList(1, 5))
    }

    @Test
    fun reorder_emptyListIsANoOp() {
        assertEquals(emptyList<Long>(), reorderEntryIds(emptyList(), 0, 1))
        assertEquals(emptyList<Long>(), reorderEntryIds(emptyList(), -5, 0))
    }

    @Test
    fun reorder_singleItemListIsANoOp() {
        assertEquals(listOf(7L), reorderEntryIds(listOf(7L), 0, 0))
        assertEquals(listOf(7L), reorderEntryIds(listOf(7L), 0, 1))
        assertEquals(listOf(7L), reorderEntryIds(listOf(7L), -1, 0))
    }

    @Test
    fun reorder_equalIndicesAreANoOp() {
        val current = listOf(1L, 2L, 3L)
        assertEquals(current, reorderEntryIds(current, 1, 1))
    }

    @Test
    fun reorder_outOfRangeIndicesAreANoOp() {
        val current = listOf(1L, 2L, 3L)
        assertEquals(current, reorderEntryIds(current, 0, 3))
        assertEquals(current, reorderEntryIds(current, -1, 2))
        assertEquals(current, reorderEntryIds(current, 2, 5))
        assertEquals(current, reorderEntryIds(current, -2, -1))
    }
}
