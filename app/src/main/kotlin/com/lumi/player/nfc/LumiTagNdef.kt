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
 * name and the preferred play mode.
 */
data class LumiTagData(val name: String, val mode: PlayMode)

/**
 * NDEF codec for the app's own tag payload: a single NDEF record with the
 * custom MIME type [MIME_TYPE] whose payload is a small JSON object
 * (`{"name": …, "mode": …}`).
 *
 * Reading is lenient (a tag with no lumi record, a malformed payload, or an
 * unknown mode all degrade to "no record" / defaults); writing is strict
 * (never write to a read-only tag or one without enough free NDEF space).
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
    /** Target maximum total NDEF payload size for a write. */
    const val MAX_PAYLOAD_BYTES = 200

    private val TYPE_BYTES: ByteArray = MIME_TYPE.toByteArray(StandardCharsets.UTF_8)

    fun toJson(data: LumiTagData): String {
        val json = JSONObject()
        json.put("name", data.name.take(MAX_NAME_LENGTH))
        json.put("mode", data.mode.storageValue)
        return json.toString()
    }

    fun fromJson(json: String): LumiTagData? = try {
        val obj = JSONObject(json)
        if (!obj.has("name") || !obj.has("mode")) null else
            LumiTagData(
                name = obj.getString("name").take(MAX_NAME_LENGTH),
                // Unknown/missing mode values fall back to the default mode.
                mode = PlayMode.fromStorage(obj.getString("mode")),
            )
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

    /** The app's payload currently on the tag, if the tag is NDEF readable. */
    fun readFromTag(tag: Tag?): LumiTagData? {
        val ndef = ndefOf(tag) ?: return null
        return try {
            fromNdefMessage(ndef.getNdefMessage())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Whether [tag] is NDEF formatted, writable, and has at least
     * [payloadBytes] of free NDEF space left.
     */
    fun canWritePayload(tag: Tag?, payloadBytes: Int = MAX_PAYLOAD_BYTES): Boolean {
        val ndef = ndefOf(tag) ?: return false
        return try {
            ndef.isWritable && ndef.maxSize - currentByteLength(ndef) >= payloadBytes
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Writes [data] to [tag], replacing whatever was there. Returns false
     * (instead of throwing) when the tag is gone, not NDEF writable, or has
     * no room.
     */
    fun write(tag: Tag?, data: LumiTagData): Boolean {
        val ndef = ndefOf(tag) ?: return false
        val message = toNdefMessage(data)
        val size = messageByteLength(message)
        if (size > MAX_PAYLOAD_BYTES) return false
        return try {
            if (!ndef.isWritable || ndef.maxSize - currentByteLength(ndef) < size) {
                false
            } else {
                ndef.connect()
                ndef.writeNdefMessage(message)
                true
            }
        } catch (e: Exception) {
            false
        } finally {
            try {
                ndef.close()
            } catch (e: Exception) {
                // Tag is already gone; nothing to clean up.
            }
        }
    }

    /** Byte size of the message currently on the tag (0 when empty). */
    private fun currentByteLength(ndef: Ndef): Int = try {
        ndef.getNdefMessage()?.toByteArray()?.size ?: 0
    } catch (e: Exception) {
        0
    }
}
