package com.lumi.player.data

import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The per-song download predicate and the save target (Robolectric: the
 * URL math goes through the platform Uri). Pins: a URL is a valid song
 * download URL only when it is absolute http(s) with a host and, when it
 * carries an extension, an audio one; the download icon shows only when
 * the URL is present + valid + the song is NOT in the content; the saved
 * file lands in the public music folder's `Music/lumi/` subfolder (the
 * MediaStore RELATIVE_PATH form - the folder the provider creates on
 * insert, so a download never pre-creates directories).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SongDownloadTest {

    // ---- isValidSongDownloadUrl ----

    @Test
    fun validUrls_areAbsoluteHttpsWithAHost() {
        for (url in listOf(
            "https://cdn.example.com/songs/song.mp3",
            "http://cdn.example.com/songs/song.m4a",
            "https://cdn.example.com/a/b/c.flac",
        )) {
            assertTrue(url, SongDownload.isValidSongDownloadUrl(url))
        }
    }

    @Test
    fun aUrlWithoutAnExtensionIsValid() {
        // Some hosts deliver audio without an extension: no extension is
        // not a reason to refuse (the player sniffs the format).
        assertTrue(SongDownload.isValidSongDownloadUrl("https://cdn.example.com/stream/123"))
        assertTrue(SongDownload.isValidSongDownloadUrl("https://cdn.example.com/stream"))
    }

    @Test
    fun aNonAudioExtensionIsNotASongDownload() {
        for (url in listOf(
            "https://cdn.example.com/pic.png",
            "https://cdn.example.com/doc.pdf",
            "https://cdn.example.com/file.MP3.bak",
        )) {
            assertFalse(url, SongDownload.isValidSongDownloadUrl(url))
        }
    }

    @Test
    fun aNonHttpSchemeOrMissingHostIsNotValid() {
        for (url in listOf(
            "ftp://cdn.example.com/song.mp3",
            "file:///sdcard/Music/song.mp3",
            "https://",
            "not a url at all",
            "",
            "   ",
        )) {
            assertFalse(url, SongDownload.isValidSongDownloadUrl(url))
        }
    }

    @Test
    fun extensionsAreCaseInsensitive() {
        assertTrue(SongDownload.isValidSongDownloadUrl("https://cdn.example.com/SONG.MP3"))
        assertTrue(SongDownload.isValidSongDownloadUrl("https://cdn.example.com/Song.Flac"))
    }

    // ---- the download-icon predicate ----

    @Test
    fun theIconShowsOnlyWhenPresentAndValidAndNotInContent() {
        val url = "https://cdn.example.com/song.mp3"
        assertTrue(
            "a present, valid url for a song NOT in the content",
            SongDownload.shouldShowDownloadIcon(url, inContent = false),
        )
        assertFalse("a missing url never shows", SongDownload.shouldShowDownloadIcon(null, false))
        assertFalse(
            "an invalid url never shows (even for a song out of content)",
            SongDownload.shouldShowDownloadIcon("ftp://cdn.example.com/song.mp3", false),
        )
        assertFalse(
            "a valid url for a song THAT IS in the content never shows",
            SongDownload.shouldShowDownloadIcon(url, inContent = true),
        )
    }

    // ---- the saved file name / mime ----

    @Test
    fun theDisplayNameIsTheSongNamePlusTheUrlAudioExtension() {
        assertEquals(
            "Porch song.mp3",
            SongDownload.displayNameFor("Porch song", "https://cdn.example.com/x/porch.mp3"),
        )
        assertEquals(
            "a url without an audio extension keeps the bare name (no guessed extension)",
            "Porch song",
            SongDownload.displayNameFor("Porch song", "https://cdn.example.com/stream/123"),
        )
        assertEquals(
            "a non-audio extension is not a guessed extension either",
            "Porch song",
            SongDownload.displayNameFor("Porch song", "https://cdn.example.com/x/porch.png"),
        )
        assertNull(SongDownload.audioExtensionOf("https://cdn.example.com/stream"))
    }

    @Test
    fun theMimeFollowsTheExtension() {
        assertEquals("audio/mpeg", SongDownload.mimeTypeFor("https://h/x/a.mp3"))
        assertEquals("audio/mp4", SongDownload.mimeTypeFor("https://h/x/a.M4A"))
        assertEquals("audio/aac", SongDownload.mimeTypeFor("https://h/x/a.aac"))
        assertEquals("audio/flac", SongDownload.mimeTypeFor("https://h/x/a.flac"))
        assertEquals("audio/wav", SongDownload.mimeTypeFor("https://h/x/a.wav"))
        assertEquals(
            "an extensionless url settles on the common audio mime",
            "audio/mpeg",
            SongDownload.mimeTypeFor("https://h/x/stream/1"),
        )
    }

    // ---- the save target (the public music folder's lumi subfolder) ----

    @Test
    fun theInsertTargetsThePublicMusicFolderLumiSubfolder() {
        // The save target is the public music folder's /lumi subfolder:
        // the MediaStore RELATIVE_PATH form ("Music/lumi/", the trailing
        // separator the provider expects) is what the provider creates on
        // insert - the download itself never touches the filesystem, so
        // this insert value IS the folder-creation contract.
        assertEquals("Music/lumi/", SongDownload.MUSIC_SUBFOLDER)
        val values = SongDownload.insertValuesFor("Porch song", "https://cdn.example.com/x/porch.mp3")
        assertEquals("Music/lumi/", values.getAsString(MediaStore.Audio.Media.RELATIVE_PATH))
        assertEquals("Porch song.mp3", values.getAsString(MediaStore.Audio.Media.DISPLAY_NAME))
        assertEquals("audio/mpeg", values.getAsString(MediaStore.Audio.Media.MIME_TYPE))
    }
}
