package com.lumi.player.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * The per-tag record the app reads from and writes to NFC tags: the tag's
 * name and the tag's ordered song list (by the songs' display names - the
 * same name-based identity the tag's song list uses, so a written list
 * restores onto a re-registered tag name-for-name).
 *
 * The play mode is UNRELATED TO TAGS: the record carries no mode, and the
 * codec never parses the payload's legacy "mode" key (cards written before
 * the mode left the format keep that field on the card; [LumiTagNdef.fromJson]
 * ignores it, so a card with or without the field decodes identically).
 * The app's play mode is its own settings feature and is never read from a
 * tag.
 *
 * Legacy payloads written before the song list was part of the record carry
 * no "songs" field; they decode with an empty list.
 *
 * Per-song download URLs are the card side's "secret option": a song entry
 * MAY be a `{"name": ..., "url": ...}` object instead of a plain name, the
 * url pointing at a downloadable copy of the song. The decoder is LENIENT
 * (see [LumiTagNdef.fromJson]): legacy plain-name entries decode exactly as
 * before, a url is kept only when it is a non-blank string, and an entry
 * without a usable name is skipped. The app NEVER writes a url
 * ([LumiTagNdef.toJson] writes plain names only), so its own write path
 * cannot propagate or forge them.
 */
data class LumiTagData(
    val name: String,
    val songs: List<String> = emptyList(),
    /**
     * Optional per-song download URLs, keyed by the song's (clamped) display
     * name - the card side's "secret option", read from the card by the app
     * and never written by it. Empty for legacy cards.
     */
    val songUrls: Map<String, String> = emptyMap(),
)

/** The tag's lumi payload and whether the tag can hold the app payload. */
data class TagInspect(val data: LumiTagData?, val writable: Boolean)

/**
 * NDEF codec for the app's own tag payload: a single NDEF record with the
 * custom MIME type [MIME_TYPE] whose payload is a small JSON object
 * (`{"name": …, "songs": […]}`).
 *
 * Reading is lenient (a tag with no lumi record or a malformed payload
 * degrades to "no record"; a payload without a song list - the legacy
 * shape - decodes with an empty list; and a legacy "mode" field - written
 * before the mode left the payload - is IGNORED: the play mode is unrelated
 * to tags, so a card with or without the field decodes identically, name +
 * songs only). Writing never carries the play mode (the written payload is
 * name + songs only) and is strict
 * (never write to a read-only tag or one whose NDEF area is known to be too
 * small for the new message - an area whose capacity the discovery did not
 * report is written anyway, and a too-small one fails the write).
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
     * payload WITH the ordered song list (name + song display names):
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
        // The play mode is unrelated to tags: the card carries the tag's
        // name and song list only. Cards written before the mode left the
        // format keep their legacy "mode" field - [fromJson] ignores it.
        // The per-song download urls are the card side's secret option and
        // are NEVER written: the written payload stays plain names, so the
        // app's write path cannot propagate or forge them.
        val songs = org.json.JSONArray()
        data.songs.forEach { songs.put(it.take(MAX_SONG_NAME_LENGTH)) }
        json.put("songs", songs)
        return json.toString()
    }

    fun fromJson(json: String): LumiTagData? = try {
        val obj = JSONObject(json)
        // A lumi record needs a name: "mode" is a legacy field (cards
        // written before the mode left the payload) and "songs" a newer
        // one, so neither is required for the record to be readable. The
        // legacy "mode" field is NOT parsed: the play mode is unrelated to
        // tags, so a card with or without the field decodes identically -
        // name + songs only.
        if (!obj.has("name")) null else {
            val (songs, songUrls) = parseSongEntries(obj.optJSONArray("songs"))
            LumiTagData(
                name = obj.getString("name").take(MAX_NAME_LENGTH),
                songs = songs,
                songUrls = songUrls,
            )
        }
    } catch (e: Exception) {
        null
    }

    /**
     * The lenient parse of the payload's song entries. A plain-string
     * entry is the legacy shape (name only, no url) and decodes exactly
     * as before; a JSON-object entry carries the song's name AND an
     * optional download url - the url is kept only when it is a non-blank
     * string (a missing or malformed url means "no url for that song",
     * nothing else changes), and an entry without a usable name is
     * skipped. Any other entry shape (number, boolean, array) is skipped.
     * Names are clamped to [MAX_SONG_NAME_LENGTH] the same way a legacy
     * name is, so the decoded names stay in the same space the restore
     * path resolves against.
     */
    private fun parseSongEntries(arr: org.json.JSONArray?): Pair<List<String>, Map<String, String>> {
        if (arr == null) return emptyList<String>() to emptyMap()
        val names = mutableListOf<String>()
        val urls = mutableMapOf<String, String>()
        for (i in 0 until arr.length()) {
            val entry = arr.get(i)
            val name = when (entry) {
                is String -> entry.take(MAX_SONG_NAME_LENGTH)
                is JSONObject -> entry.optString("name").take(MAX_SONG_NAME_LENGTH)
                else -> continue
            }
            if (name.isBlank()) continue
            names += name
            if (entry is JSONObject) {
                val url = entry.opt("url")
                if (url is String && url.isNotBlank()) {
                    urls[name] = url.trim()
                }
            }
        }
        return names to urls
    }

    /**
     * The url [urls] (the card payload's clamped-name → url map) holds for
     * [songName]: an exact name match first, then - for a stored name
     * longer than the payload's 50-char clamp - the exact clamped form of
     * it. A short name is exact-only: it is never a clamp product, so a
     * prefix hit would surface a different song's url.
     */
    fun songUrlFor(urls: Map<String, String>, songName: String): String? {
        urls[songName]?.let { return it }
        if (songName.length > MAX_SONG_NAME_LENGTH) {
            return urls[songName.take(MAX_SONG_NAME_LENGTH)]
        }
        return null
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
