package com.lumi.player.data

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore

/**
 * The per-song download URL a card may carry (the "secret option" on the
 * NFC side) and the destination inside the device's public music folder.
 *
 * The URL is offered by whatever writes the card externally; the app reads
 * it from the card's lumi payload (see [com.lumi.player.nfc.LumiTagData.songUrls])
 * and NEVER writes it back (the app's own card write carries name + songs
 * only). A song row on the tag page offers a download only when the URL is
 * present, is a valid song download URL, and the song is NOT currently in
 * the app's content (the app's name-based song identity).
 *
 * Download destination: the [MUSIC_SUBFOLDER] subfolder of the public music
 * folder (the MediaStore RELATIVE_PATH spelling - "Music/lumi/" WITH the
 * trailing separator, the form the MediaProvider accepts). The provider
 * creates the subfolder as part of the insert, so the download never
 * pre-creates directories itself: after the first insert the folder
 * exists, and later inserts land in it.
 */
object SongDownload {

    /** The destination subfolder of the public music folder (MediaStore RELATIVE_PATH form). */
    const val MUSIC_SUBFOLDER = "Music/lumi/"

    /**
     * Whether [url] is a valid song download URL: an absolute http(s) URL
     * with a non-blank host, and - when the path carries a file extension -
     * an audio extension the app plays (a link to an image or a document is
     * not a song download). A URL whose path has no extension passes: some
     * hosts deliver audio without one.
     */
    fun isValidSongDownloadUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        val uri = try {
            Uri.parse(trimmed)
        } catch (e: Exception) {
            return false
        }
        val scheme = uri.scheme
        if (scheme != "http" && scheme != "https") return false
        if (uri.host.isNullOrEmpty()) return false
        val path = uri.path ?: return true
        val dot = path.lastIndexOf('.')
        if (dot < 0 || dot == path.length - 1) return true
        return path.substring(dot + 1).lowercase() in SUPPORTED_AUDIO_EXTENSIONS
    }

    /**
     * The download-icon predicate for a song row: the URL is present, is a
     * valid song download URL, and the song is not currently in the app's
     * content. Any miss shows no icon (nothing else on the row changes).
     */
    fun shouldShowDownloadIcon(url: String?, inContent: Boolean): Boolean =
        url != null && isValidSongDownloadUrl(url) && !inContent

    /**
     * The file name the downloaded [songName] is stored under: the song's
     * display name, plus the URL's own audio extension when the path
     * carries one. A URL without an audio extension keeps the bare name -
     * the player sniffs the format, and a guessed extension would lie
     * about the bytes.
     */
    fun displayNameFor(songName: String, url: String): String {
        val extension = audioExtensionOf(url) ?: return songName
        return "$songName.$extension"
    }

    /** The audio extension in [url]'s path (lowercased), or null when it carries none. */
    fun audioExtensionOf(url: String): String? {
        val path = Uri.parse(url.trim()).path ?: return null
        val dot = path.lastIndexOf('.')
        if (dot < 0 || dot == path.length - 1) return null
        val extension = path.substring(dot + 1).lowercase()
        return if (extension in SUPPORTED_AUDIO_EXTENSIONS) extension else null
    }

    /** The MIME type the stored row carries (best guess from the URL's extension). */
    fun mimeTypeFor(url: String): String = when (audioExtensionOf(url)) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        else -> "audio/mpeg"
    }

    /**
     * The MediaStore values of the inserted row: the file name, the MIME
     * type, and [MUSIC_SUBFOLDER] as the RELATIVE_PATH - the public music
     * folder's subfolder, which the provider creates on insert (the
     * folder-creation contract of the scoped-storage mechanism).
     */
    fun insertValuesFor(songName: String, url: String): ContentValues =
        ContentValues(3).apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayNameFor(songName, url))
            put(MediaStore.Audio.Media.MIME_TYPE, mimeTypeFor(url))
            put(MediaStore.Audio.Media.RELATIVE_PATH, MUSIC_SUBFOLDER)
        }
}
