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
 * Behavioral coverage for the in-app add-song write: [LumiRepository.linkEntryToTag]
 * must link an ALREADY-STORED content entry to the end of the tag's song list
 * in one transaction - no new row is stored (a tag's list is an assignment
 * over the app's existing content), no orphaned link, no duplicate link, no
 * bleed across tags.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumiApp::class)
class LinkEntryToTagTest {

    private fun repository(): LumiRepository =
        (RuntimeEnvironment.getApplication() as LumiApp).repository

    @Test
    fun linkEntryToTag_appendsTheExistingEntryAtTheEndOfTheTagsList() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("UID-A", "Porch")!!
        val first = repo.addEntry(EntryType.AUDIO, "First", "content://first")
        val second = repo.addEntry(EntryType.AUDIO, "Second", "content://second")
        val third = repo.addEntry(EntryType.AUDIO, "Third", "content://third")
        repo.updateTagEntries(tag.id) { listOf(second.id, first.id) }

        val linked = repo.linkEntryToTag(tag.id, third.id)

        assertTrue("a fresh link must succeed", linked)
        assertEquals(
            "the tag's list must gain the stored entry in its current order",
            listOf("Second", "First", "Third"),
            repo.entriesForTag(tag.id).map { it.label },
        )
        assertEquals(
            "no new content row is stored - the existing row is linked",
            3,
            repo.observeEntries().first().size,
        )
    }

    @Test
    fun linkEntryToTag_linksAFolderImportedEntryWithoutCopyingIt() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("UID-F", "Bedroom")!!
        val folder = repo.rememberFolder("content://docs/tree/f", "Music")
        val imported = repo.addEntry(
            EntryType.AUDIO,
            "Folder song",
            "content://docs/tree/f/document/x",
            folderId = folder.id,
        )

        val linked = repo.linkEntryToTag(tag.id, imported.id)

        assertTrue("a folder-imported entry links like any other entry", linked)
        assertEquals(listOf("Folder song"), repo.entriesForTag(tag.id).map { it.label })
        assertEquals(
            "the folder-imported row stays where it is (one row, still folder-marked)",
            1,
            repo.observeEntries().first().count { it.payload == "content://docs/tree/f/document/x" },
        )
    }

    @Test
    fun linkEntryToTag_neverDuplicatesAnExistingLink() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("UID-B", "Hall")!!
        val song = repo.addEntry(EntryType.AUDIO, "Shared", "content://shared")
        repo.updateTagEntries(tag.id) { listOf(song.id) }

        val duplicate = repo.linkEntryToTag(tag.id, song.id)

        assertFalse(
            "a second link of the same entry must be refused",
            duplicate,
        )
        assertEquals(
            "the tag's list keeps the song exactly once",
            listOf("Shared"),
            repo.entriesForTag(tag.id).map { it.label },
        )
    }

    @Test
    fun linkEntryToTag_neverBleedsIntoAnotherTagsList() = runBlocking {
        val repo = repository()
        val a = repo.addTag("UID-C", "Hall")!!
        val b = repo.addTag("UID-D", "Kitchen")!!
        val shared = repo.addEntry(EntryType.AUDIO, "Shared", "content://shared")
        repo.updateTagEntries(a.id) { listOf(shared.id) }
        repo.updateTagEntries(b.id) { listOf(shared.id) }

        val onlyA = repo.addEntry(EntryType.AUDIO, "Only A", "content://only-a")
        repo.linkEntryToTag(a.id, onlyA.id)

        assertEquals(listOf("Shared", "Only A"), repo.entriesForTag(a.id).map { it.label })
        assertEquals(
            "the other tag's list must not gain the song",
            listOf("Shared"),
            repo.entriesForTag(b.id).map { it.label },
        )
    }

    @Test
    fun linkEntryToTag_isASafeNoOpWhenTheTagWasDeletedWhilePicking() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("UID-F", "Attic")!!
        val song = repo.addEntry(EntryType.AUDIO, "Ghost", "content://ghost")
        repo.deleteTag(tag)

        val linked = repo.linkEntryToTag(tag.id, song.id)

        assertFalse(
            "a pick that lands on a deleted tag must be dropped without a crash",
            linked,
        )
        assertTrue(
            "no link row may be left behind",
            repo.entriesForTag(tag.id).isEmpty(),
        )
    }

    @Test
    fun linkEntryToTag_isASafeNoOpWhenTheEntryWasDeletedWhilePicking() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("UID-G", "Loft")!!
        val song = repo.addEntry(EntryType.AUDIO, "Gone", "content://gone")
        repo.deleteEntry(song)

        val linked = repo.linkEntryToTag(tag.id, song.id)

        assertFalse("a link to a deleted entry must be refused", linked)
        assertTrue(repo.entriesForTag(tag.id).isEmpty())
    }
}
