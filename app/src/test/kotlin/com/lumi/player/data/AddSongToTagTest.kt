package com.lumi.player.data

import com.lumi.player.LumiApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Behavioral coverage for the scan-first "add a song onto a tag" write:
 * [LumiRepository.addEntryToTag] must store the new entry and append it to
 * the end of the tag's song list in one transaction, so reading the tag's
 * current list and appending the new song always agree - no orphaned
 * entry, no missing link, no bleed across tags.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumiApp::class)
class AddSongToTagTest {

    private fun repository(): LumiRepository =
        (RuntimeEnvironment.getApplication() as LumiApp).repository

    @Test
    fun addEntryToTag_appendsTheNewSongAtTheEndOfTheTagsList() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("UID-A", "Porch")!!
        val first = repo.addEntry(EntryType.AUDIO, "First", "content://first")
        val second = repo.addEntry(EntryType.AUDIO, "Second", "content://second")
        repo.updateTagEntries(tag.id) { listOf(second.id, first.id) }

        val added = repo.addEntryToTag(tag.id, EntryType.AUDIO, "Third", "content://third")!!

        assertEquals("the new song must be the stored entry", "content://third", added.payload)
        assertEquals(
            "the tag's list must gain the new song in its current order",
            listOf("Second", "First", "Third"),
            repo.entriesForTag(tag.id).map { it.label },
        )
    }

    @Test
    fun addEntryToTag_makesTheSongAppearOnTheTagAndInContent() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("UID-B", "Bedroom")!!

        val added = repo.addEntryToTag(tag.id, EntryType.AUDIO, "New song", "content://new")!!

        // The tag's list now carries the song...
        assertEquals(listOf("New song"), repo.entriesForTag(tag.id).map { it.label })
        // ...the app's content list holds the entry...
        assertTrue(
            "the entry must be a first-class content item",
            repo.observeEntries().first().any { it.id == added.id },
        )
        // ...and the tag's live observation sees the link.
        assertEquals(
            listOf("New song"),
            repo.observeEntriesForTag(tag.id).first().map { it.label },
        )
    }

    @Test
    fun addEntryToTag_neverBleedsIntoAnotherTagsSharedList() = runBlocking {
        val repo = repository()
        val a = repo.addTag("UID-C", "Hall")!!
        val b = repo.addTag("UID-D", "Kitchen")!!
        val shared = repo.addEntry(EntryType.AUDIO, "Shared", "content://shared")
        repo.updateTagEntries(a.id) { listOf(shared.id) }
        repo.updateTagEntries(b.id) { listOf(shared.id) }

        repo.addEntryToTag(a.id, EntryType.AUDIO, "Only A", "content://only-a")

        assertEquals(listOf("Shared", "Only A"), repo.entriesForTag(a.id).map { it.label })
        assertEquals(
            "the other tag's list must not gain the song",
            listOf("Shared"),
            repo.entriesForTag(b.id).map { it.label },
        )
    }

    @Test
    fun addEntryToTag_isASafeNoOpWhenTheTagWasDeletedWhilePicking() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("UID-F", "Attic")!!
        repo.deleteTag(tag)

        val added = repo.addEntryToTag(tag.id, EntryType.AUDIO, "Ghost", "content://ghost")

        assertNull(
            "a pick that lands on a deleted tag must be dropped without a crash",
            added,
        )
        assertTrue(
            "no entry row may be left behind",
            repo.observeEntries().first().isEmpty(),
        )
    }

    @Test
    fun addEntryToTag_blankLabelFallsBackToUntitled() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("UID-E", "Deck")!!

        val added = repo.addEntryToTag(tag.id, EntryType.AUDIO, "   ", "content://blank")!!

        assertEquals("Untitled", added.label)
        assertEquals(listOf("Untitled"), repo.entriesForTag(tag.id).map { it.label })
    }
}
