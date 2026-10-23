package com.lumi.player.ui

import com.lumi.player.data.EntryEntity
import com.lumi.player.data.LumiRepository
import com.lumi.player.data.TagEntity
import com.lumi.player.data.TagSongRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The tag-link mutations behind [LumiViewModel]'s tag-song surface:
 * linking already-stored content entries onto a tag's song list (the
 * single-song and the bulk add-song paths), unlinking one song from one
 * tag, and the tag page's drag reorder. Every mutation goes through the
 * repository's transactional seams, so the play order (the persisted link
 * order) is the single source of truth. The [LumiViewModel] keeps
 * same-named delegates, so the UI and the test suite never touch this
 * class directly.
 */
class TagLinkManager(
    private val repository: LumiRepository,
    private val onEvent: (String) -> Unit,
    private val scope: CoroutineScope,
) {

    /**
     * Links an already-stored content entry to the end of the tag's song
     * list - the in-app add-song flow's single-song path: a tag's list is
     * an assignment over the app's existing content (individual or
     * folder-imported alike), so the pick links the song's stored row
     * instead of storing a new one (no second grant, no duplicate content
     * row). When the tag already plays the song (its list carries an entry
     * with the same label) the link is reported instead of silently
     * duplicating. [linkEntriesToTag] runs this same one-song core in
     * order, so a bulk save links each checked song through the very same
     * path.
     */
    fun linkEntryToTag(tag: TagEntity, entry: EntryEntity) {
        scope.launch { linkOneEntryToTag(tag, entry) }
    }

    /**
     * Links several already-stored content entries to a tag in order - the
     * add-song dialog's Save committing its checked selection: every song
     * links through the existing single-song link path (one link per
     * song, each appended after the previous), but each write completes
     * before the next starts, so the appends land in the selection's
     * order - fire-and-forget siblings would race on the link-list count
     * and the songs could land out of the order they were checked in.
     */
    fun linkEntriesToTag(tag: TagEntity, entries: List<EntryEntity>) {
        if (entries.isEmpty()) return
        scope.launch {
            entries.forEach { linkOneEntryToTag(tag, it) }
        }
    }

    /** The one-song link (blocking: its write commits before returning). */
    private suspend fun linkOneEntryToTag(tag: TagEntity, entry: EntryEntity) {
        // The guard matches on the EXISTING songs' names (a tag song is
        // identified by name): a second pick of the same song - or of a
        // duplicate-named row - must not duplicate the link. A "song not
        // found" row carries the deleted song's snapshot name, so re-adding
        // a re-imported song with that name is a refusal, not a duplicate:
        // the tag already shows that song (as the missing row), and the
        // re-imported entry plays it (name-based resolution) - removing the
        // missing row first is what re-links the new entry.
        if (repository.entriesForTag(tag.id).any { it.name == entry.label }) {
            onEvent("That song is already on the tag.")
            return
        }
        val linked = repository.linkEntryToTag(tag.id, entry.id)
        onEvent(if (linked) "Song added to the tag" else "Could not add the song to that tag.")
    }

    /**
     * Removes one song from one tag - the inverse of [linkEntryToTag]: the
     * tag's LINK to [row] goes, and nothing else. For a song that still
     * exists the song stays in the content list (its folder membership
     * untouched), its links to any other tags are untouched, and the
     * remaining songs keep their persisted order (the play order of what
     * stays is unchanged). For a song that has ALREADY been
     * deleted from the library (the tag's "song not found" row) the dead
     * link itself goes: the missing row leaves the list.
     */
    fun unlinkSongFromTag(tag: TagEntity, row: TagSongRow) {
        scope.launch {
            val removed = repository.unlinkLinkFromTag(tag.id, row.linkId)
            onEvent(if (removed) "Song removed from the tag" else "Could not remove the song from that tag.")
        }
    }

    /**
     * Reorders the tag's song list - the tag detail page's drag reorder:
     * the persisted link order is replaced with [ordered] through the same
     * transactional seam the add-song flow appends through, so the play
     * order (the link order) follows the reordered order. [ordered] is the
     * page's full row list (rows whose song is missing among them), so a
     * persisted reorder keeps every link, missing songs at their new
     * positions included. A list that is
     * unchanged (a drag that ends where it started) writes nothing.
     */
    fun reorderTagSongs(tag: TagEntity, ordered: List<TagSongRow>) {
        scope.launch {
            // The link's OWN id is the stable identity of a row (the entry
            // reference is only a provenance hint - a re-imported entry
            // changes nothing about the row), so the reorder addresses links
            // by their stored ids.
            val ids = ordered.map { it.linkId }
            if (repository.entriesForTag(tag.id).map { it.linkId } == ids) return@launch
            var accepted = false
            repository.updateTagEntries(tag.id) { current ->
                // The list may have changed between the drag and the write
                // (a song added or removed in flight); only a permutation of
                // the current set is a reorder - anything else keeps the
                // current order.
                if (current.toSet() == ids.toSet()) {
                    accepted = true
                    ids
                } else current
            }
            if (accepted) onEvent("Order saved.")
        }
    }
}
