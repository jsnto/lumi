package com.lumi.player.data

import com.lumi.player.LumiApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Behavioral coverage for the per-song removal ([LumiRepository.unlinkLinkFromTag]):
 * the removal addresses the tag's LINK to the song (by the link's own id -
 * the song is identified by name, so the link, not the entry row, is the
 * unit that goes). Only that link goes: the song stays in the content list,
 * its links to other tags are untouched, the survivors keep their relative
 * order, and a removal of a "song not found" row (a name that resolves to
 * no content) cleans the dead link out of the list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumiApp::class)
class UnlinkSongFromTagTest {
    private fun repository(): LumiRepository =
        (RuntimeEnvironment.getApplication() as LumiApp).repository

    /** The tag's rows as song names (a missing song renders its snapshot name). */
    private fun names(rows: List<TagSongRow>): List<String> = rows.map { it.name }

    /** The row of the song named [name] on the tag (there is one link per name). */
    private suspend fun rowOf(repo: LumiRepository, tagId: Long, name: String): TagSongRow =
        repo.entriesForTag(tagId).single { it.name == name }

    @Test
    fun unlinkingOnlyRemovesTheTagLinkToThatOneSong() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("uid-a", "Porch")!!
        val other = repo.addTag("uid-b", "Balcony")!!
        val song = repo.addEntry(EntryType.AUDIO, "Song", "content://song")
        val kept = repo.addEntry(EntryType.AUDIO, "Kept", "content://kept")
        repo.linkEntryToTag(tag.id, song.id)
        repo.linkEntryToTag(tag.id, kept.id)
        repo.linkEntryToTag(other.id, song.id)

        val removed = repo.unlinkLinkFromTag(tag.id, rowOf(repo, tag.id, "Song").linkId)

        assertTrue("the tag linked the song, so the unlink succeeds", removed)
        // Only the tag's link to the song goes: the tag keeps its other
        // song...
        assertEquals(listOf("Kept"), names(repo.entriesForTag(tag.id)))
        // ...the song stays in the content list (it was only unlinked, not deleted)...
        assertEquals(2, repo.observeEntries().first().size)
        // ...and the song's link to the OTHER tag is untouched.
        assertEquals(listOf("Song"), names(repo.entriesForTag(other.id)))
    }

    @Test
    fun unlinkRenumbersTheSurvivingOrderDensely() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("uid-a", "Porch")!!
        val a = repo.addEntry(EntryType.AUDIO, "A", "content://a")
        val b = repo.addEntry(EntryType.AUDIO, "B", "content://b")
        val c = repo.addEntry(EntryType.AUDIO, "C", "content://c")
        val d = repo.addEntry(EntryType.AUDIO, "D", "content://d")
        listOf(a.id, b.id, c.id, d.id).forEach { repo.linkEntryToTag(tag.id, it) }

        // Remove the FIRST two links: the survivors keep their relative
        // order, and the renumbered order is dense (a later append lands
        // at the end, not mid-list).
        assertTrue(repo.unlinkLinkFromTag(tag.id, rowOf(repo, tag.id, "A").linkId))
        assertTrue(repo.unlinkLinkFromTag(tag.id, rowOf(repo, tag.id, "B").linkId))
        assertEquals(listOf("C", "D"), names(repo.entriesForTag(tag.id)))

        val e = repo.addEntry(EntryType.AUDIO, "E", "content://e")
        repo.linkEntryToTag(tag.id, e.id)
        assertEquals(listOf("C", "D", "E"), names(repo.entriesForTag(tag.id)))
    }

    @Test
    fun unlinkingAMissingSongRemovesTheDeadLink() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("uid-a", "Porch")!!
        val kept = repo.addEntry(EntryType.AUDIO, "Kept", "content://kept")
        val gone = repo.addEntry(EntryType.AUDIO, "Gone", "content://gone")
        repo.linkEntryToTag(tag.id, kept.id)
        repo.linkEntryToTag(tag.id, gone.id)
        repo.deleteEntry(gone)

        // The song is missing (the tag's "song not found" row, rendering
        // the snapshot name) - the removal succeeds and cleans the row out
        // of the list.
        val rows = repo.entriesForTag(tag.id)
        assertEquals(listOf("Kept", "Gone"), names(rows))
        val missing = rows.single { it.entry == null }
        assertEquals("the missing row renders the deleted song's name", "Gone", missing.name)
        assertEquals(gone.id, missing.entryId)

        assertTrue(
            "a missing song's link is a link: the removal succeeds",
            repo.unlinkLinkFromTag(tag.id, missing.linkId),
        )
        assertEquals(listOf("Kept"), names(repo.entriesForTag(tag.id)))
    }

    @Test
    fun unlinkingOneMissingSongKeepsTheOtherMissingSongs() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("uid-a", "Porch")!!
        val gone1 = repo.addEntry(EntryType.AUDIO, "Gone 1", "content://g1")
        val gone2 = repo.addEntry(EntryType.AUDIO, "Gone 2", "content://g2")
        repo.linkEntryToTag(tag.id, gone1.id)
        repo.linkEntryToTag(tag.id, gone2.id)
        repo.deleteEntry(gone1)
        repo.deleteEntry(gone2)

        // Both rows are missing: removing ONE of them leaves the other in
        // place (the renumber of the links keeps the missing rows).
        assertTrue(repo.unlinkLinkFromTag(tag.id, rowOf(repo, tag.id, "Gone 1").linkId))
        assertEquals(listOf("Gone 2"), names(repo.entriesForTag(tag.id)))
        assertEquals(gone2.id, repo.entriesForTag(tag.id).single().entryId)
    }

    @Test
    fun unlinkIsASafeNoOpForALinkTheTagDoesNotCarry() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("uid-a", "Porch")!!
        val other = repo.addTag("uid-b", "Balcony")!!
        val song = repo.addEntry(EntryType.AUDIO, "Song", "content://song")
        repo.linkEntryToTag(other.id, song.id)

        // A link id the tag does not carry (another tag's link to the same
        // song): a safe no-op, the other tag's link untouched.
        val foreignLinkId = repo.entriesForTag(other.id).single().linkId
        assertFalse(repo.unlinkLinkFromTag(tag.id, foreignLinkId))
        assertEquals(emptyList<TagSongRow>(), repo.entriesForTag(tag.id))
        assertEquals(listOf("Song"), names(repo.entriesForTag(other.id)))
    }

    @Test
    fun unlinkIsASafeNoOpWhenTheTagNoLongerExists() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("uid-a", "Porch")!!
        val song = repo.addEntry(EntryType.AUDIO, "Song", "content://song")
        repo.linkEntryToTag(tag.id, song.id)
        repo.deleteTag(tag)

        assertFalse(repo.unlinkLinkFromTag(tag.id, 1L))
        assertNull(repo.tagByUid("uid-a"))
    }

    @Test
    fun unlinkingTheOnlySongEmptiesTheList() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("uid-a", "Porch")!!
        val song = repo.addEntry(EntryType.AUDIO, "Song", "content://song")
        repo.linkEntryToTag(tag.id, song.id)

        assertTrue(repo.unlinkLinkFromTag(tag.id, rowOf(repo, tag.id, "Song").linkId))
        assertEquals(emptyList<TagSongRow>(), repo.entriesForTag(tag.id))
        // The song itself is untouched by the unlink.
        assertEquals(1, repo.observeEntries().first().size)
    }

    @Test
    fun unlinkingAllTheSongsEmptiesTheTagList() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("uid-a", "Porch")!!
        val a = repo.addEntry(EntryType.AUDIO, "A", "content://a")
        val b = repo.addEntry(EntryType.AUDIO, "B", "content://b")
        repo.linkEntryToTag(tag.id, a.id)
        repo.linkEntryToTag(tag.id, b.id)

        assertTrue(repo.unlinkLinkFromTag(tag.id, rowOf(repo, tag.id, "A").linkId))
        assertTrue(repo.unlinkLinkFromTag(tag.id, rowOf(repo, tag.id, "B").linkId))
        assertEquals(emptyList<TagSongRow>(), repo.entriesForTag(tag.id))
        // Both songs are still stored in the content list.
        assertEquals(2, repo.observeEntries().first().size)
    }
}
