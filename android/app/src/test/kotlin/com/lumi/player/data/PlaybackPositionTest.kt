package com.lumi.player.data

import com.lumi.player.player.EntrySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the remembered-position logic: LRU eviction across the
 * three-tag capacity and the start decision made from a saved row.
 */
class PlaybackPositionTest {

    private fun position(
        uid: String,
        at: Long,
        index: Int = 0,
        ms: Long = 1_000L,
        fingerprint: String = "fp",
    ) = TagPositionEntity(uid, ms, index, fingerprint, at)

    // ---- LRU eviction ----

    @Test
    fun storesBelowCapacityEvictNothing() {
        assertTrue(lruEvictions(emptyList(), position("A", 1_000)).isEmpty())
        assertTrue(
            lruEvictions(listOf(position("A", 1_000), position("B", 2_000)), position("C", 3_000)).isEmpty(),
        )
    }

    @Test
    fun fourthTagEvictsTheOldestRememberedTag() {
        val existing = listOf(
            position("A", 1_000),
            position("B", 2_000),
            position("C", 3_000),
        )
        assertEquals(listOf("A"), lruEvictions(existing, position("D", 4_000)))
    }

    @Test
    fun evictionIsByLastPlayedAtNotByStorageOrder() {
        val existing = listOf(
            position("A", 3_000),
            position("B", 1_000),
            position("C", 2_000),
        )
        assertEquals(listOf("B"), lruEvictions(existing, position("D", 4_000)))
    }

    @Test
    fun reRememberingAnExistingTagEvictsNothing() {
        val existing = listOf(
            position("A", 1_000),
            position("B", 2_000),
            position("C", 3_000),
        )
        assertTrue(lruEvictions(existing, position("B", 4_000)).isEmpty())
    }

    @Test
    fun oversizedStoreEvictsDownToCapacity() {
        val existing = listOf(
            position("A", 1_000),
            position("B", 2_000),
            position("C", 3_000),
            position("D", 4_000),
        )
        assertEquals(listOf("A", "B"), lruEvictions(existing, position("E", 5_000)))
    }

    @Test
    fun customCapacityIsRespected() {
        val existing = listOf(position("A", 1_000))
        assertEquals(listOf("A"), lruEvictions(existing, position("B", 2_000), capacity = 1))
    }

    @Test
    fun shrinkingTheConfiguredCapacityEvictsTheOldestDownToTheNewCount() {
        // Three rows remembered at the default capacity, then the user
        // lowers the setting to two: the next store evicts the two oldest.
        val existing = listOf(
            position("A", 1_000),
            position("B", 2_000),
            position("C", 3_000),
        )
        assertEquals(listOf("A", "B"), lruEvictions(existing, position("D", 4_000), capacity = 2))
    }

    @Test
    fun raisingTheConfiguredCapacityEvictsNothing() {
        val existing = listOf(
            position("A", 1_000),
            position("B", 2_000),
            position("C", 3_000),
        )
        assertTrue(lruEvictions(existing, position("D", 4_000), capacity = 5).isEmpty())
    }

    // ---- the remembered-positions setting ----

    @Test
    fun unsetAndInvalidStoredCountsFallBackToTheDefault() {
        assertEquals(MAX_REMEMBERED_TAGS, rememberedPositionsFromStored(null))
        assertEquals(MAX_REMEMBERED_TAGS, rememberedPositionsFromStored(""))
        assertEquals(MAX_REMEMBERED_TAGS, rememberedPositionsFromStored("garbage"))
        assertEquals(MAX_REMEMBERED_TAGS, rememberedPositionsFromStored("2.5"))
    }

    @Test
    fun outOfRangeStoredCountsFallBackToTheDefault() {
        // Below the minimum or above the setting's bound the stored value
        // is invalid (like the other settings' unset/invalid fallback).
        assertEquals(MAX_REMEMBERED_TAGS, rememberedPositionsFromStored("0"))
        assertEquals(MAX_REMEMBERED_TAGS, rememberedPositionsFromStored("-1"))
        assertEquals(MAX_REMEMBERED_TAGS, rememberedPositionsFromStored((MAX_REMEMBERED_POSITIONS + 1).toString()))
        assertEquals(MAX_REMEMBERED_TAGS, rememberedPositionsFromStored("1000"))
    }

    @Test
    fun storedCountsInsideTheRangeAreHonored() {
        assertEquals(1, rememberedPositionsFromStored("1"))
        assertEquals(MAX_REMEMBERED_TAGS, rememberedPositionsFromStored("3"))
        assertEquals(MAX_REMEMBERED_POSITIONS, rememberedPositionsFromStored("20"))
        assertEquals(7, rememberedPositionsFromStored("  7  "))
    }

    @Test
    fun theStoredValueClampsTheCountToTheSettingRange() {
        assertEquals("1", rememberedPositionsStoredValue(0))
        assertEquals("20", rememberedPositionsStoredValue(MAX_REMEMBERED_POSITIONS))
        assertEquals("20", rememberedPositionsStoredValue(MAX_REMEMBERED_POSITIONS + 1))
        assertEquals("1", rememberedPositionsStoredValue(-3))
        assertEquals("5", rememberedPositionsStoredValue(5))
    }

    // ---- restore decision ----

    @Test
    fun noSavedPositionStartsFresh() {
        assertEquals(PositionRestore.StartFresh, decideRestore(null, "fp", 2))
    }

    @Test
    fun savedPositionRestoresTheEntryAndOffset() {
        val saved = position("A", 1_000, index = 1, ms = 12_345, fingerprint = "fp")
        assertEquals(PositionRestore.SeekTo(1, 12_345L), decideRestore(saved, "fp", 2))
    }

    @Test
    fun changedLinkedListResetsThePositionToTheBeginning() {
        // The parent swapped the audio file linked to the tag: the saved
        // position was made against a different list.
        val saved = position("A", 1_000, ms = 12_345, fingerprint = "old-list")
        assertEquals(PositionRestore.StartFresh, decideRestore(saved, "new-list", 1))
    }

    @Test
    fun rememberedEntryNoLongerInTheListStartsFresh() {
        val saved = position("A", 1_000, index = 3, ms = 12_345)
        assertEquals(PositionRestore.StartFresh, decideRestore(saved, "fp", 2))
    }

    @Test
    fun zeroPositionStartsFresh() {
        val saved = position("A", 1_000, ms = 0)
        assertEquals(PositionRestore.StartFresh, decideRestore(saved, "fp", 1))
    }

    // ---- fingerprint ----

    private val songA = EntrySpec("audio", "Song A", "content://file/A")
    private val songB = EntrySpec("audio", "Song B", "content://file/B")

    @Test
    fun sameListHasSameFingerprint() {
        assertEquals(fingerprintOf(listOf(songA, songB)), fingerprintOf(listOf(songA, songB)))
    }

    @Test
    fun swappedFileChangesTheFingerprint() {
        val swapped = EntrySpec("audio", "Song B", "content://file/OTHER")
        assertTrue(fingerprintOf(listOf(songA, songB)) != fingerprintOf(listOf(songA, swapped)))
    }

    @Test
    fun reorderedListChangesTheFingerprint() {
        assertTrue(fingerprintOf(listOf(songA, songB)) != fingerprintOf(listOf(songB, songA)))
    }
}
