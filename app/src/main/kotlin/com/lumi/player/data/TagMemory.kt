package com.lumi.player.data

import org.json.JSONArray

/**
 * The last saved name and ordered song list for a card uid - the per-card
 * memory that survives the tag row's deletion (the [TagMemoryEntity] table,
 * whose uid is the card's NFC uid, the [TagEntity.uid] string). Written on
 * registration and on a successful card write; the restore source for a
 * re-scanned card whose payload carries no song list.
 */
data class TagMemory(
    val name: String,
    val songs: List<String>,
) {
    companion object {
        /** [songs] as the stored JSON array string (the payload's field shape). */
        fun songsToJson(songs: List<String>): String =
            JSONArray().apply { songs.forEach { put(it) } }.toString()

        /** A stored JSON array string back to its song list; malformed input decodes to nothing. */
        fun songsFromJson(json: String): List<String> =
            try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (e: Exception) {
                emptyList()
            }
    }
}
