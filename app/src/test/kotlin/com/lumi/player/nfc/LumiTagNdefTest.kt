package com.lumi.player.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import com.lumi.player.data.PlayMode
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
 * behavior (unknown mode, malformed JSON, missing fields, oversized names).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LumiTagNdefTest {

    private val data = LumiTagData(name = "Hallway", mode = PlayMode.PROXIMITY)

    // ---- JSON ----

    @Test
    fun json_roundTrips_includingTheSongList() {
        val withSongs = LumiTagData(
            name = "Hallway",
            mode = PlayMode.PROXIMITY,
            songs = listOf("First song", "Gone song", "Third \u00e9"),
        )
        assertEquals(withSongs, LumiTagNdef.fromJson(LumiTagNdef.toJson(withSongs)))
    }

    @Test
    fun json_songsAreClampedToThePerSongLimit() {
        val decoded = LumiTagNdef.fromJson(
            LumiTagNdef.toJson(LumiTagData("T", PlayMode.CONTINUOUS, listOf("x".repeat(80)))),
        )
        assertEquals(listOf("x".repeat(50)), decoded?.songs)
    }

    @Test
    fun json_legacyPayloadWithoutASongsField_decodesWithAnEmptyList() {
        // A card written before the song list existed carries only name +
        // mode: it must decode (not be treated as "no record") with an
        // empty song list.
        val decoded = LumiTagNdef.fromJson("""{"name":"Legacy","mode":"proximity"}""")
        assertEquals("Legacy", decoded?.name)
        assertEquals(PlayMode.PROXIMITY, decoded?.mode)
        assertEquals(emptyList<String>(), decoded?.songs)
    }

    @Test
    fun json_nameIsTruncatedTo50Chars() {
        val long = "x".repeat(80)
        val decoded = LumiTagNdef.fromJson(LumiTagNdef.toJson(LumiTagData(long, PlayMode.CONTINUOUS)))
        assertEquals("x".repeat(50), decoded?.name)
    }

    @Test
    fun json_unknownModeFallsBackToContinuous() {
        val decoded = LumiTagNdef.fromJson("""{"name":"T","mode":"something-else"}""")
        assertEquals(PlayMode.CONTINUOUS, decoded?.mode)
    }

    @Test
    fun json_missingFieldsOrGarbage_decodesToNull() {
        assertNull(LumiTagNdef.fromJson("""{"name":"T"}"""))
        assertNull(LumiTagNdef.fromJson("not json at all"))
        assertNull(LumiTagNdef.fromJson("[]"))
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
        // name, the mode, and the songs): the worst case - a max-length
        // name plus five max-length song names - fits the 512-byte target.
        val message = LumiTagNdef.toNdefMessage(
            LumiTagData("y".repeat(50), PlayMode.PROXIMITY, List(5) { "z".repeat(50) }),
        )
        assertTrue(
            "payload of ${LumiTagNdef.messageByteLength(message)}B must fit the " +
                "${LumiTagNdef.MAX_PAYLOAD_BYTES}B target",
            LumiTagNdef.messageByteLength(message) <= LumiTagNdef.MAX_PAYLOAD_BYTES,
        )
        assertEquals("the target carries name + mode + a real song list", 512, LumiTagNdef.MAX_PAYLOAD_BYTES)
    }

    @Test
    fun tagAccessors_withoutAnNdefTag_areSafe() {
        assertNull(LumiTagNdef.readFromTag(null))
        assertFalse(LumiTagNdef.canWritePayload(null, data))
        assertFalse(LumiTagNdef.write(null, data))
    }
}
