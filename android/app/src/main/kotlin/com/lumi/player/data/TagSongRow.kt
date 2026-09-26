package com.lumi.player.data

/**
 * A row in a tag's song list: the song's stored name (the link's snapshot)
 * plus the content entry it resolves to WHILE the song exists in the
 * content.
 *
 * A tag song is identified by NAME, not by the linked entry row: the row is
 * playable when a song with [name] exists in the content, evaluated live -
 * a song deleted from the library and later re-imported under the same
 * name resolves to the re-imported entry and plays again without
 * re-adding it to the tag. A name that resolves to no content shows on the
 * tag's page as the "song not found" row: [entry] is `null`, and the row
 * still renders its name (the snapshot the link carries).
 *
 * The link row survives its entry's deletion (tag_links carries no foreign
 * key to entries - deleting an entry must neither be blocked nor cascade),
 * so the tag's list never silently loses a song: the row keeps its
 * original sort position either way. [entryId] is the link's provenance
 * hint (the entry it was made from, when one was linked); [linkId]
 * addresses the stored link row for removal and reorder.
 */
data class TagSongRow(
    val linkId: Long,
    val entryId: Long?,
    val sortOrder: Int,
    val name: String,
    val entry: EntryEntity?,
)
