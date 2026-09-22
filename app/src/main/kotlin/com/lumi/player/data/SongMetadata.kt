package com.lumi.player.data

/**
 * A song's media metadata, read best-effort from the file itself (the song
 * profile page's artist / album / duration rows). A null field is metadata
 * the file does not carry - or that could not be read - so the page shows a
 * placeholder for it instead of an error.
 */
data class SongMetadata(
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
)

/** A duration in the profile's "m:ss" form. */
fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
    return "${totalSeconds / 60L}:${(totalSeconds % 60L).toString().padStart(2, '0')}"
}
