package com.lumi.player.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import com.lumi.player.data.PlayMode
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * The per-tag record the app reads from and writes to NFC tags: the tag's
 * name, the preferred play mode, and the tag's ordered song list (by the
 * songs' display names - the same name-based identity the tag's song list
 * uses, so a written list restores onto a re-registered tag name-for-name).
 *
 * Legacy payloads written before the song list was part of the record carry
 * no "songs" field; they decode with an empty list.
 */
data class LumiTagData(
    val name: String,
    val mode: PlayMode,
    val songs: List<String> = emptyList(),
)

/** The tag's lumi payload and whether the tag can hold the app payload. */
data class TagInspect(val data: LumiTagData?, val writable: Boolean)

/**
 * NDEF codec for the app's own tag payload: a single NDEF record with the
 * custom MIME type [MIME_TYPE] whose payload is a small JSON object
 * (`{"name": …, "mode": …, "songs": […]}`).
 *
 * Reading is lenient (a tag with no lumi record, a malformed payload, or an
 * unknown mode all degrade to "no record" / defaults; a payload without a
 * song list - the legacy shape - decodes with an empty list); writing is
 * strict (never write to a read-only tag or one whose NDEF area is known to
 * be too small for the new message - an area whose capacity the discovery
 * did not report is written anyway, and a too-small one fails the write).
 *
 * API note: this compiles against the project's stub SDK (see AGENTS.md),
 * whose NFC surface is the classic one - [Ndef.get] instead of a
 * [Tag.getNdef] property, [Ndef.getNdefMessage] for the current message,
 * [Ndef.isWritable] for the write flag, and [NdefMessage.getRecords] for
 * the record list.
 */
object LumiTagNdef {

    const val MIME_TYPE = "application/x-lumi"
    const val MAX_NAME_LENGTH = 50
    /**
     * Target maximum total NDEF payload size for a write - sized for the
     * payload WITH the ordered song list (name + mode + song display names):
     * comfortably above a five-song list on the small NTAG215 (145 user
     * bytes), far below the large cards. The discovery-data gate is the
     * per-card check (a KNOWN-small area refuses up front); this is the
     * app's own cap on how much it will try to push onto a card.
     */
    const val MAX_PAYLOAD_BYTES = 512

    /**
     * The song display names' cap in the payload: each name is clamped to
     * the tag-name length, so a card cannot carry a name the tag cannot
     * display. A stored label longer than the cap is carried as its first
     * [MAX_SONG_NAME_LENGTH] characters, and the restore path resolves a
     * carried name of exactly this length back to the longer stored label
     * it was clamped from - and only when exactly one stored label starts
     * with it (zero or several keep the name a visible "song not found"),
     * so a card round trip neither strands a song nor links a different
     * one.
     */
    const val MAX_SONG_NAME_LENGTH = MAX_NAME_LENGTH

    private val TYPE_BYTES: ByteArray = MIME_TYPE.toByteArray(StandardCharsets.UTF_8)

    fun toJson(data: LumiTagData): String {
        val json = JSONObject()
        json.put("name", data.name.take(MAX_NAME_LENGTH))
        json.put("mode", data.mode.storageValue)
        val songs = org.json.JSONArray()
        data.songs.forEach { songs.put(it.take(MAX_SONG_NAME_LENGTH)) }
        json.put("songs", songs)
        return json.toString()
    }

    fun fromJson(json: String): LumiTagData? = try {
        val obj = JSONObject(json)
        if (!obj.has("name") || !obj.has("mode")) null else {
            val songs = obj.optJSONArray("songs")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it).take(MAX_SONG_NAME_LENGTH) }
            } ?: emptyList()
            LumiTagData(
                name = obj.getString("name").take(MAX_NAME_LENGTH),
                // Unknown/missing mode values fall back to the default mode.
                mode = PlayMode.fromStorage(obj.getString("mode")),
                // Legacy payloads (pre-song-list) carry no "songs" field -
                // they decode with an empty list, never as "no record".
                songs = songs,
            )
        }
    } catch (e: Exception) {
        null
    }

    fun toNdefMessage(data: LumiTagData): NdefMessage =
        NdefMessage(NdefRecord.createMime(MIME_TYPE, toJson(data).toByteArray(StandardCharsets.UTF_8)))

    /** The app's payload in [message], if any record carries it; else null. */
    fun fromNdefMessage(message: NdefMessage?): LumiTagData? {
        if (message == null) return null
        for (record in message.getRecords()) {
            if (record.type.contentEquals(TYPE_BYTES)) {
                fromJson(String(record.payload, StandardCharsets.UTF_8))?.let { return it }
            }
        }
        return null
    }

    /** Total on-tag size of [message] in bytes. */
    fun messageByteLength(message: NdefMessage): Int = message.toByteArray().size

    /** The NDEF handle of [tag], if the tag is NDEF formatted; else null. */
    private fun ndefOf(tag: Tag?): Ndef? = try {
        Ndef.get(tag)
    } catch (e: Exception) {
        null
    }

    /**
     * Runs [block] on a connected NDEF handle of [tag], closing it
     * afterwards. Returns null (instead of throwing) when the tag is gone,
     * not NDEF formatted, or the connection fails.
     */
    private inline fun <T> connectedNdef(tag: Tag?, block: (Ndef) -> T): T? {
        val ndef = ndefOf(tag) ?: return null
        return try {
            ndef.connect()
            block(ndef)
        } catch (e: Exception) {
            null
        } finally {
            try {
                ndef.close()
            } catch (e: Exception) {
                // Tag is already gone; nothing to clean up.
            }
        }
    }

    /**
     * Whether this NDEF handle can take [data]: writable, and its area
     * large enough WHEN THE CAPACITY IS KNOWN - a capacity the discovery
     * did not report ([getMaxSize] answering 0) does not fail the check,
     * the write attempt is the one that decides (an area that turns out
     * too small fails in [writeNdefMessage] and the write reports the
     * failure).
     */
    private fun Ndef.fits(data: LumiTagData): Boolean {
        if (!isWritable) return false
        val capacity = maxSize
        return capacity == 0 || capacity >= messageByteLength(toNdefMessage(data))
    }

    /** The app's payload currently on the tag, if the tag is NDEF readable. */
    fun readFromTag(tag: Tag?): LumiTagData? =
        connectedNdef(tag) { ndef -> fromNdefMessage(ndef.getNdefMessage()) }

    /**
     * Whether [tag] is NDEF formatted, writable, and whose NDEF area can
     * hold the message for [data] (a write replaces the tag's whole NDEF
     * message, so the full area is available). An area whose capacity
     * the discovery did not report (getMaxSize answers 0) is reported
     * as able to hold - the write attempt is the one that decides.
     */
    fun canWritePayload(tag: Tag?, data: LumiTagData): Boolean =
        connectedNdef(tag) { ndef -> ndef.fits(data) } ?: false

    /**
     * Reads the tag's lumi payload and, in the same NDEF session, checks
     * whether the tag can hold the app payload (falling back to [fallback]
     * when the tag carries no readable lumi record).
     */
    fun inspect(tag: Tag?, fallback: LumiTagData): TagInspect =
        connectedNdef(tag) { ndef ->
            val data = fromNdefMessage(ndef.getNdefMessage())
            TagInspect(data, ndef.fits(data ?: fallback))
        } ?: TagInspect(null, false)

    /**
     * Writes [data] to [tag], replacing whatever was there. Returns false
     * (instead of throwing) when the tag is gone, not NDEF writable, its
     * NDEF area is known to be too small for the message, or the write
     * itself fails - including on an unknown-capacity area that turns out
     * to be too small.
     */
    fun write(tag: Tag?, data: LumiTagData): Boolean {
        val message = toNdefMessage(data)
        if (messageByteLength(message) > MAX_PAYLOAD_BYTES) return false
        return connectedNdef(tag) { ndef ->
            if (ndef.fits(data)) {
                ndef.writeNdefMessage(message)
                true
            } else {
                false
            }
        } ?: false
    }
}
