package com.lumi.player.nfc

import android.nfc.Tag
import android.os.Bundle
import com.lumi.player.data.PlayMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shared write path's capacity pre-refusal ([LumiTagNdef]
 * [LumiTagNdef.canWritePayload] - the same check the physical write
 * runs before it writes): a KNOWN-small NDEF area is refused, a
 * known-roomy one is offered, and a capacity the discovery did not
 * report (the NDEF length missing from the presentation extras - the
 * framework answers 0) is NOT refused up front: the write attempt is
 * the one that decides, and an area that turns out too small fails in
 * the write itself.
 *
 * The tags are forged the way the card-write tests forge them: the
 * NDEF tech's extras filled the way the framework fills them on
 * presentation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NdefWriteCapacityTest {

    private val data = LumiTagData(name = "Hallway", mode = PlayMode.PROXIMITY)

    /** A payload carrying the full ordered song list (a write saves the songs too). */
    private val withSongs = LumiTagData(
        name = "Hallway",
        mode = PlayMode.PROXIMITY,
        songs = listOf("First song", "Second song", "Gone song"),
    )

    private val needed: Int by lazy {
        LumiTagNdef.messageByteLength(LumiTagNdef.toNdefMessage(data))
    }

    private val neededWithSongs: Int by lazy {
        LumiTagNdef.messageByteLength(LumiTagNdef.toNdefMessage(withSongs))
    }

    @Test
    fun aWritableCardWithUnknownCapacity_isNotRefusedUpFront() {
        // The NDEF length missing from the presentation extras: the
        // framework answers 0. The pre-refusal must not treat the
        // unknown capacity as "too small (0 bytes)" - the write attempt
        // is the one that decides.
        assertTrue(
            "an unknown-capacity writable card passes the pre-refusal",
            LumiTagNdef.canWritePayload(forgeCard(maxNdefLength = 0), data),
        )
        assertTrue(
            "...and a song-list payload (the write's real data) too",
            LumiTagNdef.canWritePayload(forgeCard(maxNdefLength = 0), withSongs),
        )
    }

    @Test
    fun aCardRoomyForNameAndModeButNotTheSongList_isRefused() {
        // The payload's size depends on the song list: an area that fits
        // the name + mode but not the full payload (a card that would
        // have taken the old-format write) is refused for the song-list
        // write - a genuinely too-small card fails with the write's own
        // refusal message.
        assertTrue(
            "the song-list payload is larger than the name+mode payload",
            neededWithSongs > needed,
        )
        assertFalse(
            "an area that fits only the old payload is refused for the song list",
            LumiTagNdef.canWritePayload(forgeCard(maxNdefLength = needed + 1), withSongs),
        )
        assertTrue(
            "...and the same area takes the name+mode payload",
            LumiTagNdef.canWritePayload(forgeCard(maxNdefLength = needed + 1), data),
        )
    }

    @Test
    fun aWritableCardWithAKnownSmallArea_isStillRefused() {
        // A capacity that IS reported and is below what the data needs:
        // the refusal stands (the write is never attempted).
        assertFalse(
            "a known-small area is refused up front",
            LumiTagNdef.canWritePayload(forgeCard(maxNdefLength = needed / 2), data),
        )
    }

    @Test
    fun aWritableCardWithAKnownRoomyArea_passes() {
        assertTrue(
            "a known-roomy area passes the pre-refusal",
            LumiTagNdef.canWritePayload(forgeCard(maxNdefLength = needed + 50), data),
        )
    }

    @Test
    fun aReadOnlyCardWithUnknownCapacity_isStillRefused() {
        // Writability is its own refusal, independent of capacity: a
        // read-only card is never written, known or not.
        assertFalse(
            "a read-only card is refused even with an unknown capacity",
            LumiTagNdef.canWritePayload(forgeCard(writable = false, maxNdefLength = 0), data),
        )
    }

    /** Forges a presented NDEF card (the NDEF tech's extras the way the framework fills them). */
    private fun forgeCard(writable: Boolean = true, maxNdefLength: Int = 151): Tag {
        val ndefBundle = Bundle().apply {
            putInt("ndefmaxlength", maxNdefLength)
            putInt("ndefcardstate", if (writable) 2 else 0)
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
            intArrayOf(1, 6),
            arrayOf(Bundle(), ndefBundle),
            0,
            0L,
            tagService,
        ) as Tag
    }
}
