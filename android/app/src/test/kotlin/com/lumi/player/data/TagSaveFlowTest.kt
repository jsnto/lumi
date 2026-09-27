package com.lumi.player.data

import com.lumi.player.LumiApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The NFC tag save-flow data path: the tag list's name sort (case-
 * insensitive, with a stable tie-break for equal names), the play mode's
 * proximity fallback (a stored mode still wins), and the save section's
 * in-sync indicator (the derived comparison of a tag's current system
 * data against its per-uid saved memory, incl. the compare-scan's
 * remember/forget writes).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumiApp::class)
class TagSaveFlowTest {

    private fun repository(): LumiRepository =
        (RuntimeEnvironment.getApplication() as LumiApp).repository

    @Test
    fun observeTagsIsSortedByTagNameCaseInsensitivelyWithAStableTieBreak() = runBlocking {
        val repo = repository()
        // Deliberately added out of order, mixed case, with one equal
        // name (the tie-break: created/inserted first reads first).
        val bravo = repo.addTag("UID-B", "bravo")!!
        val alpha = repo.addTag("UID-A", "Alpha")!!
        val charlie = repo.addTag("UID-C", "charlie")!!
        val alpha2 = repo.addTag("UID-D", "alpha")!!

        val tags = repo.observeTags().first()
        assertEquals(
            listOf(alpha.id, alpha2.id, bravo.id, charlie.id),
            tags.map { it.id },
        )
        // The tie-break is stable across re-reads (no reshuffle).
        assertEquals(tags.map { it.id }, repo.observeTags().first().map { it.id })
    }

    @Test
    fun thePlayModeFallsBackToProximityAndAStoredModeStillWins() = runBlocking {
        val repo = repository()
        // Nothing stored: the app's default is proximity now.
        assertEquals(PlayMode.PROXIMITY, repo.observePlayMode().first())
        // A stored mode always wins over the default.
        repo.setPlayMode(PlayMode.CONTINUOUS)
        assertEquals(PlayMode.CONTINUOUS, repo.observePlayMode().first())
        repo.setPlayMode(PlayMode.PROXIMITY)
        assertEquals(PlayMode.PROXIMITY, repo.observePlayMode().first())
    }

    @Test
    fun observeTagSyncDerivesTheThreeStatesAsTheTagAndMemoryChange() = runBlocking {
        val repo = repository()
        val tag = repo.addTag("UID-1", "Hallway")!!
        val first = repo.addEntry(EntryType.AUDIO, "First song", "content://first")
        val second = repo.addEntry(EntryType.AUDIO, "Second song", "content://second")
        repo.linkEntryToTag(tag.id, first.id)
        repo.linkEntryToTag(tag.id, second.id)

        // UNKNOWN: no saved memory for the uid yet.
        assertEquals(TagSyncState.Unknown, repo.observeTagSync(tag.id).first())

        // A successful write remembers the tag's data: IN SYNC.
        repo.rememberTag(tag.uid, tag.name, listOf("First song", "Second song"))
        assertEquals(TagSyncState.InSync, repo.observeTagSync(tag.id).first())

        // Any tag edit flips it to DIFFERS at once - a rename...
        repo.renameTag(tag.uid, "Hallway 2")
        assertEquals(TagSyncState.Differs, repo.observeTagSync(tag.id).first())

        // ...and the save section re-computes back to IN SYNC when the
        // next save (or a compare of the rewritten card) records the
        // matching data again.
        repo.rememberTag(tag.uid, "Hallway 2", listOf("First song", "Second song"))
        assertEquals(TagSyncState.InSync, repo.observeTagSync(tag.id).first())

        // A song edit flips it to DIFFERS again.
        val third = repo.addEntry(EntryType.AUDIO, "Third song", "content://third")
        repo.linkEntryToTag(tag.id, third.id)
        assertEquals(TagSyncState.Differs, repo.observeTagSync(tag.id).first())

        // A compare scan of a blank card clears the record: UNKNOWN.
        repo.forgetTag(tag.uid)
        assertEquals(TagSyncState.Unknown, repo.observeTagSync(tag.id).first())
        assertNull(repo.memoryForUid(tag.uid))
    }

    @Test
    fun forgetTagClearsThePerUidMemoryRecord() = runBlocking {
        val repo = repository()
        repo.rememberTag("UID-9", "Porch", listOf("First song"))
        assertEquals(
            TagMemory("Porch", listOf("First song")),
            repo.memoryForUid("UID-9"),
        )
        repo.forgetTag("UID-9")
        assertNull(repo.memoryForUid("UID-9"))
    }
}
