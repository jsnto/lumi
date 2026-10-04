package com.lumi.player.data

import com.lumi.player.LumiApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests for the [TagPositionStore] persistence path against the real Room
 * database: the LRU capacity is enforced through writes, and re-remembering
 * a tag updates its row instead of evicting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumiApp::class)
class TagPositionStoreTest {

    private fun app(): LumiApp = RuntimeEnvironment.getApplication() as LumiApp

    private fun row(uid: String, at: Long, index: Int = 0, ms: Long = 1_000L) =
        TagPositionEntity(uid, ms, index, "fp", at)

    @Test
    fun playingAFourthTagEvictsTheOldestRememberedTag() = runBlocking {
        val store = app().positionStore
        listOf("A", "B", "C", "D").forEachIndexed { i, uid ->
            store.remember(row(uid, (i + 1) * 1_000L))
        }
        val db = app().repository
        assertNull("the oldest remembered tag must be evicted", db.positionFor("A"))
        assertNotNull(db.positionFor("B"))
        assertNotNull(db.positionFor("C"))
        assertNotNull(db.positionFor("D"))
    }

    @Test
    fun evictionFollowsLastPlayedAtNotInsertionOrder() = runBlocking {
        val store = app().positionStore
        // Insert in one order, then touch A most recently so B becomes the LRU.
        store.remember(row("A", 1_000L))
        store.remember(row("B", 2_000L))
        store.remember(row("C", 3_000L))
        store.remember(row("A", 4_000L))
        store.remember(row("D", 5_000L))
        val db = app().repository
        assertNull("the least-recently played tag must be evicted", db.positionFor("B"))
        assertNotNull(db.positionFor("A"))
        assertNotNull(db.positionFor("C"))
        assertNotNull(db.positionFor("D"))
    }

    @Test
    fun aConfiguredCapacityBelowTheDefaultIsHonored() = runBlocking {
        val store = app().positionStore
        store.remember(row("A", 1_000L), capacity = 1)
        store.remember(row("B", 2_000L), capacity = 1)
        val db = app().repository
        assertNull("the configured capacity 1 must evict the older tag", db.positionFor("A"))
        assertNotNull(db.positionFor("B"))
    }

    @Test
    fun shrinkingTheConfiguredCapacityEvictsTheOldestAtTheNextStore() = runBlocking {
        val store = app().positionStore
        // Three rows remembered at the default capacity...
        store.remember(row("A", 1_000L))
        store.remember(row("B", 2_000L))
        store.remember(row("C", 3_000L))
        // ...then the user lowers the setting to two: the next stored
        // position evicts the oldest remembered tags down to the new count.
        store.remember(row("D", 4_000L), capacity = 2)
        val db = app().repository
        assertNull("the oldest remembered tag must be evicted", db.positionFor("A"))
        assertNull(db.positionFor("B"))
        assertNotNull(db.positionFor("C"))
        assertNotNull(db.positionFor("D"))
    }

    @Test
    fun raisingTheConfiguredCapacityKeepsAllExistingRows() = runBlocking {
        val store = app().positionStore
        // Three rows at the default capacity; the user raises the setting
        // to five, so the next store evicts nothing.
        store.remember(row("A", 1_000L))
        store.remember(row("B", 2_000L))
        store.remember(row("C", 3_000L))
        store.remember(row("D", 4_000L), capacity = 5)
        val db = app().repository
        assertNotNull("the raised capacity must keep the existing rows", db.positionFor("A"))
        assertNotNull(db.positionFor("B"))
        assertNotNull(db.positionFor("C"))
        assertNotNull(db.positionFor("D"))
    }

    @Test
    fun reRememberingAnExistingTagUpdatesItsRow() = runBlocking {
        val store = app().positionStore
        store.remember(row("A", 1_000L, index = 1, ms = 5_000L))
        store.remember(row("A", 2_000L, index = 2, ms = 9_000L))
        val db = app().repository
        val saved = checkNotNull(db.positionFor("A"))
        assertEquals(2, saved.entryIndex)
        assertEquals(9_000L, saved.positionMs)
        assertEquals(2_000L, saved.lastPlayedAt)
    }
}
