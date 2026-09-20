package com.lumi.player.ui

/**
 * Pure reorder of a tag's ordered content list (entry ids in play order).
 * Moves the entry at [fromIndex] to [toIndex]; every other entry keeps its
 * relative order. Out-of-range or equal indices are a no-op, so callers can
 * pass raw drag math without clamping.
 */
fun reorderEntryIds(current: List<Long>, fromIndex: Int, toIndex: Int): List<Long> =
    if (fromIndex !in current.indices || toIndex !in current.indices || fromIndex == toIndex) {
        current
    } else {
        val moved = current[fromIndex]
        current.toMutableList().apply {
            removeAt(fromIndex)
            add(toIndex, moved)
        }
    }
