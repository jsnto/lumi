package com.lumi.player.data

/**
 * One selectable row of a tag's in-app add-song list: a song from the
 * app's existing content (individual or folder-imported alike) plus
 * whether the tag already plays it.
 */
data class TagSongOption(
    val entry: EntryEntity,
    val alreadyOnTag: Boolean,
)

/**
 * Collapses [content] (ALL stored content - the individual list and the
 * folder-imported rows alike, in the content list's order) into ONE row per
 * distinct label: duplicate content (the same label stored more than once)
 * displays once, and saving the collapsed row checked links the FIRST
 * stored copy.
 *
 * Each option is marked [TagSongOption.alreadyOnTag] when [tagEntries]
 * carries an entry with the same label - the tag's list may already hold
 * the song (directly, or via a duplicate label) - so the row stays
 * non-addable and the selection can never link it a second time.
 */
fun tagSongOptions(content: List<EntryEntity>, tagEntries: List<EntryEntity>): List<TagSongOption> {
    val labelsOnTag = tagEntries.mapTo(HashSet()) { it.label }
    val seen = HashSet<String>()
    return content
        .filter { seen.add(it.label) }
        .map { TagSongOption(entry = it, alreadyOnTag = it.label in labelsOnTag) }
}
