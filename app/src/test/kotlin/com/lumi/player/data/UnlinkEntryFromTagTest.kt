package com.lumi.player.data

import com.lumi.player.LumiApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Behavioral coverage for the per-song removal write: [LumiRepository.unlinkEntryFromTag]
 * must delete EXACTLY that tag's link row to that entry - the inverse of
 * [LumiRepository.linkEntryToTag]. The entry itself stays in the content
 * list, its folder membership is untouched, its links to any other tags are
 * untouched, and the remaining songs keep their persisted link order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumiApp::class)
class UnlinkEntryFromTagTest {

    private fun repository(): LumiRepository =
        (RuntimeEnvironment.getApplication() as LumiApp).repository

    @Test
    fun unlink_deletesExactlyThatTagsLinkForThatEntry() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("UID-A", "Porch")!!
        val a = repo.addEntry(EntryType.AUDIO, "Song A", "content://a")
        val b = repo.addEntry(EntryType.AUDIO, "Song B", "content://b")
        val c = repo.addEntry(EntryType.AUDIO, "Song C", "content://c")
        repo.updateTagEntries(tag.id) { listOf(a.id, b.id, c.id) }

        val removed = repo.unlinkEntryFromTag(tag.id, b.id)

        assertTrue("an existing link must be removed", removed)
        assertEquals(
            "exactly that link is gone - the remaining songs keep their order",
            listOf("Song A", "Song C"),
            repo.entriesForTag(tag.id).map { it.label },
        )
        assertEquals(
            "the other tags' lists are untouched (there is only one tag here, but the entry rows must be intact)",
            3,
            repo.observeEntries().first().size,
        )
    }

    @Test
    fun unlink_leavesTheEntryInTheContentList() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("UID-B", "Hall")!!
        val song = repo.addEntry(EntryType.AUDIO, "Song", "content://song")
        repo.updateTagEntries(tag.id) { listOf(song.id) }

        val removed = repo.unlinkEntryFromTag(tag.id, song.id)

        assertTrue("the existing link must be removed", removed)
        val survivor = repo.observeEntries().first().first { it.id == song.id }
        assertEquals(
            "the entry row itself is never deleted - it stays in the content list",
            "content://song",
            survivor.payload,
        )
        assertEquals("Song", survivor.label)
    }

    @Test
    fun unlink_leavesTheEntriesLinksToOtherTagsUntouched() = runBlocking {
        val repo = repository()
        val a = repo.addTag("UID-C", "Hall")!!
        val b = repo.addTag("UID-D", "Kitchen")!!
        val shared = repo.addEntry(EntryType.AUDIO, "Shared", "content://shared")
        repo.updateTagEntries(a.id) { listOf(shared.id) }
        repo.updateTagEntries(b.id) { listOf(shared.id) }

        val removed = repo.unlinkEntryFromTag(a.id, shared.id)

        assertTrue(removed)
        assertTrue(
            "the unlinked tag's list loses the song",
            repo.entriesForTag(a.id).isEmpty(),
        )
        assertEquals(
            "the other tag's link to the same entry is untouched",
            listOf("Shared"),
            repo.entriesForTag(b.id).map { it.label },
        )
    }

    @Test
    fun unlink_leavesTheEntriesFolderMembershipUntouched() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("UID-E", "Attic")!!
        val folder = repo.rememberFolder("content://docs/tree/f", "Music")
        val imported = repo.addEntry(
            EntryType.AUDIO,
            "Folder song",
            "content://docs/tree/f/document/x",
            folderId = folder.id,
        )
        repo.updateTagEntries(tag.id) { listOf(imported.id) }

        val removed = repo.unlinkEntryFromTag(tag.id, imported.id)

        assertTrue(removed)
        val survivor = repo.observeEntries().first().first { it.id == imported.id }
        assertEquals(
            "the entry's folder marker is untouched - it stays a folder song",
            folder.id,
            survivor.folderId,
        )
        assertEquals(
            "a folder-imported song stays out of the individual content list",
            0,
            repo.observeIndividualEntries().first().count { it.id == imported.id },
        )
    }

    @Test
    fun unlink_keepsTheRemainingSongsInTheirPersistedOrder() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("UID-F", "Loft")!!
        val a = repo.addEntry(EntryType.AUDIO, "A", "content://a")
        val b = repo.addEntry(EntryType.AUDIO, "B", "content://b")
        val c = repo.addEntry(EntryType.AUDIO, "C", "content://c")
        val d = repo.addEntry(EntryType.AUDIO, "D", "content://d")
        repo.updateTagEntries(tag.id) { listOf(a.id, b.id, c.id, d.id) }

        val removed = repo.unlinkEntryFromTag(tag.id, c.id)

        assertTrue(removed)
        assertEquals(
            "removing a middle song keeps the remaining play order",
            listOf("A", "B", "D"),
            repo.entriesForTag(tag.id).map { it.label },
        )
    }

    @Test
    fun unlink_isASafeNoOpWhenTheEntryIsNotOnTheTag() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("UID-G", "Garage")!!
        val onTag = repo.addEntry(EntryType.AUDIO, "On tag", "content://on")
        val offTag = repo.addEntry(EntryType.AUDIO, "Off tag", "content://off")
        repo.updateTagEntries(tag.id) { listOf(onTag.id) }

        val removed = repo.unlinkEntryFromTag(tag.id, offTag.id)

        assertFalse(
            "a link that does not exist must not be reported as removed",
            removed,
        )
        assertEquals(
            "the tag's list is unchanged",
            listOf("On tag"),
            repo.entriesForTag(tag.id).map { it.label },
        )
    }

    @Test
    fun unlink_isASafeNoOpWhenTheTagWasDeletedWhileTheRemovalWasInFlight() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("UID-H", "Gone")!!
        val song = repo.addEntry(EntryType.AUDIO, "Ghost", "content://ghost")
        repo.deleteTag(tag)

        val removed = repo.unlinkEntryFromTag(tag.id, song.id)

        assertFalse(removed)
        assertTrue(repo.entriesForTag(tag.id).isEmpty())
        assertTrue(
            "the entry survives the tag's deletion",
            repo.observeEntries().first().any { it.id == song.id },
        )
    }

    @Test
    fun unlink_isASafeNoOpWhenTheEntryWasDeletedWhileTheRemovalWasInFlight() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("UID-I", "Storeroom")!!
        val song = repo.addEntry(EntryType.AUDIO, "Gone", "content://gone")
        repo.deleteEntry(song)

        val removed = repo.unlinkEntryFromTag(tag.id, song.id)

        assertFalse(removed)
        assertTrue(repo.entriesForTag(tag.id).isEmpty())
    }

    @Test
    fun anUnlinkedSongCanBeLinkedAgain() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("UID-J", "Porch")!!
        val a = repo.addEntry(EntryType.AUDIO, "First", "content://a")
        val b = repo.addEntry(EntryType.AUDIO, "Second", "content://b")
        repo.updateTagEntries(tag.id) { listOf(a.id, b.id) }
        repo.unlinkEntryFromTag(tag.id, a.id)

        val reLinked = repo.linkEntryToTag(tag.id, a.id)

        assertTrue("a removed song can be added back", reLinked)
        assertEquals(
            "the re-added song lands at the end of the list",
            listOf("Second", "First"),
            repo.entriesForTag(tag.id).map { it.label },
        )
    }
}
