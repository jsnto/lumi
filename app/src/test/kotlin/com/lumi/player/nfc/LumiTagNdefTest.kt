package com.lumi.player.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets

/**
 * Codec coverage for the app's on-tag payload: JSON serialization, the
 * custom-MIME NDEF record round trip, and the lenient-read / strict-fallback
 * behavior (the legacy "mode" key ignored on decode, malformed JSON, missing
 * fields, oversized names). The play mode is unrelated to tags: the record
 * carries no mode, the written payload has no "mode" key, and a card with or
 * without the legacy field decodes identically.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LumiTagNdefTest {

    private val data = LumiTagData(name = "Hallway")

    // ---- JSON ----

    @Test
    fun json_roundTrips_includingTheSongList() {
        val withSongs = LumiTagData(
            name = "Hallway",
            songs = listOf("First song", "Gone song", "Third \u00e9"),
        )
        // The written payload carries no play mode (the mode is unrelated to
        // tags): the round trip keeps name + songs, verbatim.
        assertEquals(withSongs, LumiTagNdef.fromJson(LumiTagNdef.toJson(withSongs)))
    }

    @Test
    fun json_theWrittenPayloadCarriesNoPlayModeField() {
        // Save-to-card writes name + songs only: the encoding of a
        // current-format payload carries no "mode" key at all.
        val obj = org.json.JSONObject(
            LumiTagNdef.toJson(LumiTagData("Hallway", listOf("First song"))),
        )
        assertTrue("the name is written", obj.has("name"))
        assertTrue("the song list is written", obj.has("songs"))
        assertFalse("the play mode is no longer part of the written payload", obj.has("mode"))
    }

    @Test
    fun json_aNameOnlyPayloadDecodesWithAnEmptySongList() {
        // A card written in the current format carries name + songs and no
        // mode: the minimal form (a name only) decodes cleanly, with the
        // empty song list.
        assertEquals(LumiTagData("T"), LumiTagNdef.fromJson("""{"name":"T"}"""))
    }

    @Test
    fun json_songsAreClampedToThePerSongLimit() {
        val decoded = LumiTagNdef.fromJson(
            LumiTagNdef.toJson(LumiTagData("T", listOf("x".repeat(80)))),
        )
        assertEquals(listOf("x".repeat(50)), decoded?.songs)
    }

    @Test
    fun json_legacyPayloadWithAModeField_decodesIdenticallyToWithoutTheField() {
        // A card written before the mode left the payload carries a "mode"
        // field. It must decode (not be treated as "no record") with an
        // empty song list (written before the song list existed) - and the
        // mode field must have NO influence: the payload decodes identically
        // with and without it (name + songs only).
        val withLegacyMode = LumiTagNdef.fromJson("""{"name":"Legacy","mode":"proximity"}""")
        val withoutTheField = LumiTagNdef.fromJson("""{"name":"Legacy"}""")
        assertEquals(withoutTheField, withLegacyMode)
        assertEquals(LumiTagData("Legacy"), withLegacyMode)
    }

    @Test
    fun json_nameIsTruncatedTo50Chars() {
        val long = "x".repeat(80)
        val decoded = LumiTagNdef.fromJson(LumiTagNdef.toJson(LumiTagData(long)))
        assertEquals("x".repeat(50), decoded?.name)
    }

    @Test
    fun json_aLegacyModeFieldIsIgnoredOnDecode_forAnyValue() {
        // The legacy "mode" key is not parsed at all (the play mode is
        // unrelated to tags): any value of it - known or not - leaves the
        // decode untouched, identical to the same payload without the field.
        for (value in listOf("proximity", "continuous", "something-else", "")) {
            assertEquals(
                "a legacy \"mode\":\"$value\" field must decode identically to none",
                LumiTagNdef.fromJson("""{"name":"T"}"""),
                LumiTagNdef.fromJson("""{"name":"T","mode":"$value"}"""),
            )
        }
    }

    @Test
    fun json_missingNameOrGarbage_decodesToNull() {
        // A lumi record needs a name: a record that carries only the legacy
        // mode field (no name) is "no record" - the mode field is ignored,
        // not a substitute for a name - as is garbage.
        assertNull(LumiTagNdef.fromJson("""{"mode":"proximity"}"""))
        assertNull(LumiTagNdef.fromJson("not json at all"))
        assertNull(LumiTagNdef.fromJson("[]"))
    }

    // ---- per-song download urls (the card side's "secret option") ----

    @Test
    fun json_legacyPlainNameEntries_decodeWithNoUrls() {
        // A legacy card (plain-name song entries) decodes EXACTLY as before:
        // the same name + songs, and no urls - the lenient parse must not
        // change the legacy shape's outcome.
        val decoded = LumiTagNdef.fromJson("""{"name":"T","songs":["A song","B song"]}""")
        assertEquals(LumiTagData("T", listOf("A song", "B song")), decoded)
    }

    @Test
    fun json_objectSongEntries_decodeNameAndUrl() {
        // The "secret option": a song entry that is an object carries the
        // name AND the download url. The names decode in the same order the
        // legacy entries do (name-based identity), and each url lands on
        // its own (clamped) name.
        val decoded = LumiTagNdef.fromJson(
            """{"name":"T","songs":["A song",{"name":"B song","url":"https://cdn.example/b.mp3"},{"name":"C song","url":"https://cdn.example/c.flac"}]}""",
        )
        assertEquals(listOf("A song", "B song", "C song"), decoded?.songs)
        assertEquals(
            mapOf(
                "B song" to "https://cdn.example/b.mp3",
                "C song" to "https://cdn.example/c.flac",
            ),
            decoded?.songUrls,
        )
    }

    @Test
    fun json_objectEntriesWithMissingOrMalformedUrls_decodeNameOnly() {
        // A missing or malformed url means NO url for that song - and
        // NOTHING ELSE CHANGES: the name still decodes (a non-string url -
        // a number, an object, a null - is not a url), and a blank url is
        // the same as a missing one.
        val decoded = LumiTagNdef.fromJson(
            """{"name":"T","songs":[{"name":"No url"},{"name":"Number url","url":123},{"name":"Object url","url":{"a":1}},{"name":"Null url","url":null},{"name":"Blank url","url":"   "}]}""",
        )
        assertEquals(
            listOf("No url", "Number url", "Object url", "Null url", "Blank url"),
            decoded?.songs,
        )
        assertEquals(emptyMap<String, String>(), decoded?.songUrls)
    }

    @Test
    fun json_objectEntriesWithoutAUsableName_areSkipped() {
        // An entry without a usable name is not a song (the app's identity
        // is name-based): it is skipped - its url cannot surface on any
        // row - while the remaining entries decode untouched.
        val decoded = LumiTagNdef.fromJson(
            """{"name":"T","songs":["Kept song",{"url":"https://cdn.example/nameless.mp3"},{"name":"   "},7,{"name":"Other kept"}]}""",
        )
        assertEquals(listOf("Kept song", "Other kept"), decoded?.songs)
        assertEquals(emptyMap<String, String>(), decoded?.songUrls)
    }

    @Test
    fun json_objectEntryNamesAreClampedToThePerSongLimit() {
        // The object entry's name is clamped exactly like a legacy name, so
        // the url is keyed in the same clamped space the restore path
        // resolves against.
        val long = "n".repeat(80)
        val payload = """{"name":"T","songs":[{"name":"${long}","url":"https://cdn.example/long.mp3"}]}"""
        val decoded = LumiTagNdef.fromJson(payload)
        assertEquals(listOf("n".repeat(50)), decoded?.songs)
        assertEquals(mapOf("n".repeat(50) to "https://cdn.example/long.mp3"), decoded?.songUrls)
    }

    @Test
    fun json_theWrittenPayloadCarriesNoUrlEntries() {
        // The app NEVER writes a url: a payload that decodes WITH urls
        // writes back as plain names only - no url key anywhere, and a
        // re-decode of the written payload carries no urls (the app's write
        // path cannot propagate or forge the card side's secret option).
        val withUrls = LumiTagData(
            name = "T",
            songs = listOf("A song", "B song"),
            songUrls = mapOf("A song" to "https://cdn.example/a.mp3"),
        )
        val written = LumiTagNdef.toJson(withUrls)
        val obj = org.json.JSONObject(written)
        val songs = obj.getJSONArray("songs")
        for (i in 0 until songs.length()) {
            assertEquals(
                "every written song entry is a plain name string",
                songs.getString(i),
                songs.get(i) as String,
            )
        }
        assertFalse("the written payload carries no url key anywhere", written.contains("\"url\""))
        val redecoded = LumiTagNdef.fromJson(written)
        assertEquals(withUrls.copy(songUrls = emptyMap()), redecoded)
    }

    @Test
    fun songUrlFor_resolvesExactNamesAndOnlyTheClampedFormOfLongerOnes() {
        // The card's urls are keyed by the CLAMPED name: an exact stored
        // name matches directly, a stored name longer than the clamp
        // resolves through its exact 50-char clamped form, and a short name
        // is exact-only (it is never a clamp product, so a prefix hit would
        // surface a different song's url).
        val urls = mapOf("n".repeat(50) to "https://cdn.example/long.mp3", "Short" to "https://cdn.example/short.mp3")
        assertEquals("https://cdn.example/short.mp3", LumiTagNdef.songUrlFor(urls, "Short"))
        assertEquals("https://cdn.example/long.mp3", LumiTagNdef.songUrlFor(urls, "n".repeat(80) + "-tail"))
        assertNull("a short name never resolves to a clamped key", LumiTagNdef.songUrlFor(urls, "S"))
        assertNull("a long name with no clamped key has no url", LumiTagNdef.songUrlFor(urls, "m".repeat(80)))
        assertNull("no urls at all means no url", LumiTagNdef.songUrlFor(emptyMap(), "Short"))
    }

    // ---- NDEF record ----

    @Test
    fun ndefRecord_usesCustomMimeTypeAndRoundTrips() {
        val message = LumiTagNdef.toNdefMessage(data)
        assertEquals(1, message.getRecords().size)
        val record = message.getRecords()[0]
        assertEquals(
            "application/x-lumi",
            String(record.type, StandardCharsets.UTF_8),
        )
        assertEquals(data, LumiTagNdef.fromNdefMessage(message))
    }

    @Test
    fun fromNdefMessage_findsLumiRecordAmongOthers() {
        val textRecord = NdefRecord.createTextRecord("Hello tag", "en")
        val message = NdefMessage(textRecord, LumiTagNdef.toNdefMessage(data).getRecords()[0])
        assertEquals(data, LumiTagNdef.fromNdefMessage(message))
    }

    @Test
    fun fromNdefMessage_noLumiRecord_returnsNull() {
        val other = NdefMessage(
            NdefRecord.createMime("text/plain", byteArrayOf(1)),
        )
        assertNull(LumiTagNdef.fromNdefMessage(other))
        assertNull(LumiTagNdef.fromNdefMessage(null))
    }

    @Test
    fun payloadFitsThe512ByteTargetWithAMaxLengthNameAndASongList() {
        // The payload carries the full ordered song list (a write saves the
        // name and the songs): the worst case - a max-length name plus five
        // max-length song names - fits the 512-byte target.
        val message = LumiTagNdef.toNdefMessage(
            LumiTagData("y".repeat(50), List(5) { "z".repeat(50) }),
        )
        assertTrue(
            "payload of ${LumiTagNdef.messageByteLength(message)}B must fit the " +
                "${LumiTagNdef.MAX_PAYLOAD_BYTES}B target",
            LumiTagNdef.messageByteLength(message) <= LumiTagNdef.MAX_PAYLOAD_BYTES,
        )
        assertEquals("the target carries name + a real song list", 512, LumiTagNdef.MAX_PAYLOAD_BYTES)
    }

    @Test
    fun tagAccessors_withoutAnNdefTag_areSafe() {
        assertNull(LumiTagNdef.readFromTag(null))
        assertFalse(LumiTagNdef.canWritePayload(null, data))
        assertFalse(LumiTagNdef.write(null, data))
    }
}
