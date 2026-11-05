package com.lumi.player.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.os.Bundle
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

    // ---- the card side's object entries (a name plus an ignored url field) ----

    @Test
    fun json_legacyPlainNameEntries_decodeUntouched() {
        // A legacy card (plain-name song entries) decodes EXACTLY as
        // before - the lenient parse must not change the legacy shape's
        // outcome.
        val decoded = LumiTagNdef.fromJson("""{"name":"T","songs":["A song","B song"]}""")
        assertEquals(LumiTagData("T", listOf("A song", "B song")), decoded)
    }

    @Test
    fun json_objectSongEntries_decodeTheNamesAndIgnoreTheUrlField() {
        // A card-side tool may write a song entry as an object carrying a
        // name AND a download url (the "secret option" the app no longer
        // offers): the names decode in the same order the legacy entries
        // do (name-based identity), and the url field is ignored - a card
        // that still carries it decodes name-for-name, identical to the
        // same payload with plain-name entries.
        val withUrls = LumiTagNdef.fromJson(
            """{"name":"T","songs":["A song",{"name":"B song","url":"https://cdn.example/b.mp3"},{"name":"C song","url":"https://cdn.example/c.flac"}]}""",
        )
        assertEquals(listOf("A song", "B song", "C song"), withUrls?.songs)
        assertEquals(
            "the object entries decode identically to plain-name entries",
            LumiTagNdef.fromJson("""{"name":"T","songs":["A song","B song","C song"]}"""),
            withUrls,
        )
    }

    @Test
    fun json_objectEntriesWithMissingOrMalformedUrls_decodeNameOnly() {
        // The url field is ignored in ANY shape: a missing url, a
        // non-string url (a number, an object, a null), or a blank url -
        // nothing changes, the name still decodes.
        val decoded = LumiTagNdef.fromJson(
            """{"name":"T","songs":[{"name":"No url"},{"name":"Number url","url":123},{"name":"Object url","url":{"a":1}},{"name":"Null url","url":null},{"name":"Blank url","url":"   "}]}""",
        )
        assertEquals(
            listOf("No url", "Number url", "Object url", "Null url", "Blank url"),
            decoded?.songs,
        )
    }

    @Test
    fun json_objectEntriesWithoutAUsableName_areSkipped() {
        // An entry without a usable name is not a song (the app's identity
        // is name-based): it is skipped - its url field cannot surface
        // anywhere - while the remaining entries decode untouched.
        val decoded = LumiTagNdef.fromJson(
            """{"name":"T","songs":["Kept song",{"url":"https://cdn.example/nameless.mp3"},{"name":"   "},7,{"name":"Other kept"}]}""",
        )
        assertEquals(listOf("Kept song", "Other kept"), decoded?.songs)
    }

    @Test
    fun json_objectEntryNamesAreClampedToThePerSongLimit() {
        // The object entry's name is clamped exactly like a legacy name,
        // so the decoded names stay in the same space the restore path
        // resolves against.
        val long = "n".repeat(80)
        val payload = """{"name":"T","songs":[{"name":"${long}","url":"https://cdn.example/long.mp3"}]}"""
        val decoded = LumiTagNdef.fromJson(payload)
        assertEquals(listOf("n".repeat(50)), decoded?.songs)
    }

    @Test
    fun json_theWrittenPayloadCarriesPlainNameEntries() {
        // The app writes plain names only: every written song entry is a
        // plain name string (no object entries, no url key anywhere), and
        // a re-decode of the written payload round-trips to the data.
        val data = LumiTagData(name = "T", songs = listOf("A song", "B song"))
        val written = LumiTagNdef.toJson(data)
        val songs = org.json.JSONObject(written).getJSONArray("songs")
        for (i in 0 until songs.length()) {
            assertEquals(
                "every written song entry is a plain name string",
                songs.getString(i),
                songs.get(i) as String,
            )
        }
        assertFalse("the written payload carries no url key anywhere", written.contains("\"url\""))
        assertEquals(data, LumiTagNdef.fromJson(written))
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
        // No tag is a "no record" read (not a failed one): the app's read
        // path never presents a null tag, but the codec still answers.
        assertEquals(TagPayloadRead.Answered(null), LumiTagNdef.readFromTag(null))
        assertFalse(LumiTagNdef.canWritePayload(null, data))
        assertFalse(LumiTagNdef.write(null, data))
    }

    // ---- the read seam's outcome: a failed read is not "no record" ----

    @Test
    fun readFromTag_aTagWithoutTheNdefTechAnswersNoRecord() {
        // A positive discovery fact: the tag's tech list carries no NDEF
        // tech, so the card CARRIES no lumi record. That is an answered
        // read with a null payload - never the failed-read outcome.
        val tag = forgeTag(ndef = false)
        assertEquals(TagPayloadRead.Answered(null), LumiTagNdef.readFromTag(tag))
    }

    @Test
    fun readFromTag_aReadThatCannotCompleteIsFailed_notNoRecord() {
        // The single-connection-slot shape: the tag exposes the NDEF tech,
        // but the read cannot complete (the connection is not established
        // - on the device, another tech's session holds the tag's single
        // connection slot, the presence poll's). The outcome is a FAILED
        // read - the card's payload is unknown - never "the card carries
        // no record" (that would clear urls the card still carries).
        val tag = forgeTag()
        assertEquals(TagPayloadRead.Failed, LumiTagNdef.readFromTag(tag))
    }

    /** Forges a presented tag the way the framework delivers it (the NDEF tech's extras included when [ndef]). */
    private fun forgeTag(ndef: Boolean = true): Tag {
        val ndefBundle = Bundle().apply {
            putInt("ndefmaxlength", 151)
            putInt("ndefcardstate", 2)
            putInt("ndeftype", 2)
        }
        val nfcTagCls = Class.forName("android.nfc.INfcTag")
        val ctor = Tag::class.java.getDeclaredConstructor(
            ByteArray::class.java,
            IntArray::class.java,
            Array<Bundle>::class.java,
            Int::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
            nfcTagCls,
        )
        ctor.isAccessible = true
        val tagService = java.lang.reflect.Proxy.newProxyInstance(
            nfcTagCls.classLoader,
            arrayOf(nfcTagCls),
        ) { _, _, _ -> null } as Any
        return ctor.newInstance(
            ByteArray(8) { it.toByte() },
            if (ndef) intArrayOf(1, 6) else intArrayOf(1),
            if (ndef) arrayOf(Bundle(), ndefBundle) else arrayOf(Bundle()),
            0,
            0L,
            tagService,
        ) as Tag
    }
}
