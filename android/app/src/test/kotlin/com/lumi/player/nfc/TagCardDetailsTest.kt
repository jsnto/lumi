package com.lumi.player.nfc

import android.nfc.Tag
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The discovery-time card-details read ([TagCardDetails.cardDetails])
 * on the degenerate delivered-tag shape: a tag that LISTS the NDEF tech
 * in its tech list but whose NDEF extras bundle is missing (null) - the
 * framework's Ndef handle construction throws on that shape (the extras
 * are null), so the read must degrade to the omitted NDEF fields (the
 * same as a tag without the NDEF tech), never throw on the
 * always-listening read path. The tag is forged the way the
 * card-write tests forge theirs: reflectively-built, the way the
 * framework's foreground dispatch delivers it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TagCardDetailsTest {

    @Test
    fun aTagThatListsNdefWithMissingExtrasReportsTheOmittedFields() {
        val details = forgeNdefTechTagWithMissingExtras().cardDetails()
        // The tech list is the tag's own (independent of the extras):
        // the NDEF tech still shows; its fields are omitted.
        assertEquals(listOf("NfcA", "Ndef"), details.techNames)
        assertNull(details.ndefType)
        assertNull(details.maxNdefSizeBytes)
        assertNull(details.ndefWritable)
    }

    /**
     * Forges a presented-tag handle the way the framework's foreground
     * dispatch delivers one (the same shape as the card-write tests'
     * forgeries): NfcA plus the NDEF tech in the tech list, with the
     * NDEF tech's extras bundle MISSING (null) - the degenerate shape.
     */
    private fun forgeNdefTechTagWithMissingExtras(): Tag {
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
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            intArrayOf(1, 6),
            arrayOf(Bundle(), null),
            0,
            0L,
            tagService,
        ) as Tag
    }
}
