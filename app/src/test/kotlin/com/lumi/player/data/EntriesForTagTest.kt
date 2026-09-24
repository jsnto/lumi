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
 * Behavioral coverage for a tag's name-based song list: a tag song is
 * identified by NAME (the link's snapshot column), resolved live against
 * the content. A name whose song has been removed from the library keeps
 * its row on the tag's list - at its original sort position, rendering its
 * snapshot name (a [TagSongRow] with a null entry) - and a song deleted
 * and later re-imported under the same name resolves to the re-imported
 * entry and plays again, without re-adding it to the tag. The
 * renumber/reorder seams address links by their own ids and keep missing
 * songs at their positions instead of dropping them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumiApp::class)
class EntriesForTagTest {

    private fun repository(): LumiRepository =
        (RuntimeEnvironment.getApplication() as LumiApp).repository

    /** The tag's rows as song names (every row carries its own name). */
    private fun names(rows: List<TagSongRow>): List<String> = rows.map { it.name }

    @Test
    fun entriesForTagReturnsSongsInLinkOrderResolvedLive() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("uid-a", "Porch")!!
        val a = repo.addEntry(EntryType.AUDIO, "Alpha", "content://a")
        val b = repo.addEntry(EntryType.AUDIO, "Beta", "content://b")
        val c = repo.addEntry(EntryType.AUDIO, "Gamma", "content://c")
        // Linked in an order that is NOT the entries' insertion order, so
        // a query that forgot the sort order would come back wrong.
        repo.linkEntryToTag(tag.id, c.id)
        repo.linkEntryToTag(tag.id, a.id)
        repo.linkEntryToTag(tag.id, b.id)

        val linked = repo.entriesForTag(tag.id)
        // Every row carries the link's own identity (in LINK order - the
        // order the songs were added to the tag, not storage order)...
        assertEquals(3, linked.size)
        assertEquals(listOf(0, 1, 2), linked.map { it.sortOrder })
        // ...the song's snapshot name...
        assertEquals(listOf("Gamma", "Alpha", "Beta"), names(linked))
        // ...and the still-existing entry the name resolves to.
        assertEquals(listOf(c.id, a.id, b.id), linked.map { it.entryId })
        assertEquals(c.id, linked[0].entry?.id)
        assertEquals("content://c", linked[0].entry?.payload)
    }

    @Test
    fun observeEntriesForTagEmitsInOrderAndFollowsChanges() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("uid-a", "Porch")!!
        val a = repo.addEntry(EntryType.AUDIO, "Alpha", "content://a")
        val b = repo.addEntry(EntryType.AUDIO, "Beta", "content://b")
        repo.linkEntryToTag(tag.id, a.id)

        val flow = repo.observeEntriesForTag(tag.id)
        assertEquals(listOf("Alpha"), names(flow.first()))

        repo.linkEntryToTag(tag.id, b.id)
        assertEquals(listOf("Alpha", "Beta"), names(flow.first()))
    }

    @Test
    fun aDeletedSongKeepsItsRowAtItsOriginalPosition_renderingItsName() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("uid-a", "Porch")!!
        val a = repo.addEntry(EntryType.AUDIO, "Alpha", "content://a")
        val b = repo.addEntry(EntryType.AUDIO, "Beta", "content://b")
        val c = repo.addEntry(EntryType.AUDIO, "Gamma", "content://c")
        repo.linkEntryToTag(tag.id, a.id)
        repo.linkEntryToTag(tag.id, b.id)
        repo.linkEntryToTag(tag.id, c.id)

        // The song's deletion does NOT cascade off the tag's link (the
        // link stores the song's NAME and no foreign key to entries): the
        // song keeps its place in the list, rendering its snapshot name.
        repo.deleteEntry(b)
        // The song is gone from the content list itself...
        val content = repo.observeEntries().first()
        assertEquals("only the surviving entries stay", 2, content.size)
        assertFalse(
            "the deleted entry left the content list",
            content.any { it.id == b.id },
        )
        // ...but its song on the tag does not follow it.

        val rows = repo.entriesForTag(tag.id)
        assertEquals("the song keeps its row", 3, rows.size)
        // At its ORIGINAL position: the missing row sits where the song
        // was, the other rows keep their order...
        assertEquals(listOf("Alpha", "Beta", "Gamma"), names(rows))
        // ...rendering the deleted song's name (the snapshot)...
        assertEquals(
            "the missing row renders the song's snapshot name",
            "Beta",
            rows[1].name,
        )
        // ...with its deleted entry id as the provenance hint...
        assertEquals(
            "the missing row carries the deleted entry's id",
            b.id,
            rows[1].entryId,
        )
        assertNull("the missing row's entry is null", rows[1].entry)
        assertEquals("the missing row keeps its sort position", 1, rows[1].sortOrder)

        // The live observation carries the same semantics (the flow is
        // what the tag detail page renders).
        val flow = repo.observeEntriesForTag(tag.id)
        assertEquals(listOf("Alpha", "Beta", "Gamma"), names(flow.first()))
        repo.deleteEntry(a)
        assertEquals(listOf("Alpha", "Beta", "Gamma"), names(flow.first()))
        assertEquals(
            "a second deletion leaves two missing rows",
            2,
            flow.first().count { it.entry == null },
        )
    }

    @Test
    fun aDeletedSongReimportedUnderTheSameName_resolvesToTheNewEntry_andPlaysAgain() =
        runBlocking {
            val repo = repository()
            val tag = repo.addTag("uid-a", "Porch")!!
            val a = repo.addEntry(EntryType.AUDIO, "Alpha", "content://a")
            val b = repo.addEntry(EntryType.AUDIO, "Beta", "content://b")
            repo.linkEntryToTag(tag.id, a.id)
            repo.linkEntryToTag(tag.id, b.id)
            repo.deleteEntry(b)

            // The song is missing...
            assertEquals("Beta", names(repo.entriesForTag(tag.id))[1])
            assertNull(repo.entriesForTag(tag.id)[1].entry)

            // ...and later re-imported under the SAME name (a different
            // entry row: the old entry is gone). The tag's song resolves
            // to the re-imported entry - no re-adding, no new link: the
            // song plays again.
            val reimported = repo.addEntry(EntryType.AUDIO, "Beta", "content://b-new")
            val rows = repo.entriesForTag(tag.id)
            assertEquals(2, rows.size)
            assertEquals(listOf("Alpha", "Beta"), names(rows))
            assertEquals(
                "the re-imported song resolves to the new entry",
                reimported.id,
                rows[1].entry?.id,
            )
            assertEquals(
                "the song is playable again - the spec the player queues",
                listOf("content://a", "content://b-new"),
                rows.mapNotNull { it.entry?.payload },
            )
            // The LIVE flow resolves it the same way (the page never
            // needs a refresh).
            val flow = repo.observeEntriesForTag(tag.id)
            assertEquals(reimported.id, flow.first()[1].entry?.id)
        }

    @Test
    fun aNameAlreadyOnTheTag_refusesALinkToADifferentEntryWithTheSameName() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("uid-a", "Porch")!!
        val first = repo.addEntry(EntryType.AUDIO, "Song", "content://one")
        assertTrue("the first pick links", repo.linkEntryToTag(tag.id, first.id))

        // A duplicate-named row (a second stored copy of the song): the
        // tag already has the song BY NAME, so no second link.
        val duplicate = repo.addEntry(EntryType.AUDIO, "Song", "content://two")
        assertFalse(
            "a same-name entry must not create a second link",
            repo.linkEntryToTag(tag.id, duplicate.id),
        )
        assertEquals("exactly one link for the name", 1, repo.entriesForTag(tag.id).size)

        // The same refusal holds for a re-imported copy once the original
        // is deleted: the name is still on the tag (its missing row
        // resolves to the re-imported entry), so the link is never
        // duplicated.
        val reimported = repo.addEntry(EntryType.AUDIO, "Song", "content://three")
        assertFalse(
            "a re-imported same-name entry must not create a second link",
            repo.linkEntryToTag(tag.id, reimported.id),
        )
        assertEquals("still exactly one link for the name", 1, repo.entriesForTag(tag.id).size)
    }

    @Test
    fun unlinkAndReorderKeepMissingSongsInTheList() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("uid-a", "Porch")!!
        val a = repo.addEntry(EntryType.AUDIO, "Alpha", "content://a")
        val b = repo.addEntry(EntryType.AUDIO, "Beta", "content://b")
        val c = repo.addEntry(EntryType.AUDIO, "Gamma", "content://c")
        repo.linkEntryToTag(tag.id, a.id)
        repo.linkEntryToTag(tag.id, b.id)
        repo.linkEntryToTag(tag.id, c.id)
        repo.deleteEntry(b)

        // A reorder of the full row list (the missing song included) keeps
        // the missing row at its new position - a persisted reorder never
        // drops it. The reorder addresses the links by their own ids.
        val rows = repo.entriesForTag(tag.id)
        val reordered = rows.toMutableList().apply {
            val missing = removeAt(1)
            add(missing)
        }
        repo.updateTagEntries(tag.id) { current -> reordered.map { it.linkId } }
        assertEquals(
            listOf("Alpha", "Gamma", "Beta"),
            names(repo.entriesForTag(tag.id)),
        )

        // Unlinking the missing song removes its link and returns true
        // (the missing row IS the link, addressed by its link id).
        val missing = repo.entriesForTag(tag.id).last()
        assertNull(missing.entry)
        assertEquals(true, repo.unlinkLinkFromTag(tag.id, missing.linkId))
        assertEquals(listOf("Alpha", "Gamma"), names(repo.entriesForTag(tag.id)))
    }

    @Test
    fun songNamesForTagIsTheOrderedList_includingMissingSongs() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("uid-a", "Porch")!!
        val a = repo.addEntry(EntryType.AUDIO, "Alpha", "content://a")
        val b = repo.addEntry(EntryType.AUDIO, "Beta", "content://b")
        repo.linkEntryToTag(tag.id, a.id)
        repo.linkEntryToTag(tag.id, b.id)
        assertEquals(listOf("Alpha", "Beta"), repo.songNamesForTag(tag.id))

        // The payload's song list (and the per-card memory's) carries the
        // missing songs' names too - a write saves the full ordered list.
        repo.deleteEntry(b)
        assertEquals(listOf("Alpha", "Beta"), repo.songNamesForTag(tag.id))
    }

    @Test
    fun insertLinkByNameAppendsALinkThatResolvesToTheLiveEntry_orStaysMissing() =
        runBlocking {
            val repo = repository()
            val tag = repo.addTag("uid-a", "Porch")!!
            val live = repo.addEntry(EntryType.AUDIO, "Alpha", "content://a")

            // A name with a live entry resolves to it (the provenance
            // hint carried).
            repo.insertLinkByName(tag.id, "Alpha", live.id)
            // A name with no entry stays missing (no provenance hint).
            repo.insertLinkByName(tag.id, "Gone song", null)

            val rows = repo.entriesForTag(tag.id)
            assertEquals(listOf("Alpha", "Gone song"), names(rows))
            assertEquals(live.id, rows[0].entry?.id)
            assertNull(rows[1].entry)
            assertNull(rows[1].entryId)
            assertEquals(listOf(0, 1), rows.map { it.sortOrder })
        }

    @Test
    fun entryByLabelResolvesTheExactName_elseTheFirstStoredLabelCarryingItAsAPrefix() =
        runBlocking {
            val repo = repository()
            // An exact stored name wins outright, even when a longer
            // stored label also carries it as a prefix.
            repo.addEntry(EntryType.AUDIO, "Exact song (the full title).mp3", "content://longer")
            val exact = repo.addEntry(EntryType.AUDIO, "Exact song", "content://exact")
            assertEquals(exact.id, repo.entryByLabel("Exact song")?.id)
            // A name no entry carries exactly resolves to the FIRST stored
            // entry whose label carries it as a prefix - the shape of a
            // song name the card payload's 50-char name cap wrote as a
            // prefix of its longer stored label.
            val carried = "y".repeat(49)
            val first = repo.addEntry(EntryType.AUDIO, "$carried one.mp3", "content://first")
            repo.addEntry(EntryType.AUDIO, "$carried two.mp3", "content://second")
            assertEquals(first.id, repo.entryByLabel(carried)?.id)
            // A name the content carries in no form resolves to nothing.
            assertNull(repo.entryByLabel("Gone song"))
        }
}
