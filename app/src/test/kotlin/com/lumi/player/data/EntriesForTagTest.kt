package com.lumi.player.data

import com.lumi.player.LumiApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Behavioral coverage for the tag-content resolution after the refactor
 * replaced the per-link `entryById` reads with a single join query
 * ([LumiDao.entriesForTag]): the tag's list must come back in link order,
 * tags sharing or interleaving entries must not see each other's rows,
 * and the transactional [LumiRepository.updateTagEntries] (which reads
 * through the same query) must keep replacing the whole list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumiApp::class)
class EntriesForTagTest {

    private fun repository(): LumiRepository =
        (RuntimeEnvironment.getApplication() as LumiApp).repository

    @Test
    fun entriesForTag_returnsEntriesInLinkOrder_notInsertionOrder() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("UID-A", "Hallway")!!
        // Insert in one order, link in another: the link order wins.
        val first = repo.addEntry(EntryType.AUDIO, "First", "content://first")
        val second = repo.addEntry(EntryType.AUDIO, "Second", "content://second")
        val third = repo.addEntry(EntryType.AUDIO, "Third", "content://third")
        repo.updateTagEntries(tag.id) { listOf(second.id, first.id, third.id) }

        assertEquals(
            listOf("Second", "First", "Third"),
            repo.entriesForTag(tag.id).map { it.label },
        )
        // The live observation reads the same links and must agree.
        assertEquals(
            listOf("Second", "First", "Third"),
            repo.observeEntriesForTag(tag.id).first().map { it.label },
        )
    }

    @Test
    fun entriesForTag_neverMixesRowsAcrossTags_sharingEntries() = runBlocking {
        val repo = repository()
        val a = repo.addTag("UID-A", "Hallway")!!
        val b = repo.addTag("UID-B", "Bedroom")!!
        // The same entry is linked from both tags in different orders:
        // the join must not bleed one tag's rows into the other.
        val shared = repo.addEntry(EntryType.AUDIO, "Shared", "content://shared")
        val onlyA = repo.addEntry(EntryType.AUDIO, "OnlyA", "content://only-a")
        val onlyB = repo.addEntry(EntryType.AUDIO, "OnlyB", "content://only-b")
        repo.updateTagEntries(a.id) { listOf(shared.id, onlyA.id) }
        repo.updateTagEntries(b.id) { listOf(onlyB.id, shared.id) }

        assertEquals(listOf("Shared", "OnlyA"), repo.entriesForTag(a.id).map { it.label })
        assertEquals(listOf("OnlyB", "Shared"), repo.entriesForTag(b.id).map { it.label })
        assertEquals(
            listOf("OnlyB", "Shared"),
            repo.observeEntriesForTag(b.id).first().map { it.label },
        )
    }

    @Test
    fun updateTagEntries_readsThroughTheJoinQuery_andReplacesTheList() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("UID-C", "Kitchen")!!
        val one = repo.addEntry(EntryType.AUDIO, "One", "content://one")
        val two = repo.addEntry(EntryType.AUDIO, "Two", "content://two")
        repo.updateTagEntries(tag.id) { listOf(one.id) }

        // Append by reading the current (join-backed) id list back.
        val next = repo.updateTagEntries(tag.id) { it + two.id }
        assertEquals(listOf(one.id, two.id), next)
        assertEquals(listOf("One", "Two"), repo.entriesForTag(tag.id).map { it.label })
    }
}
