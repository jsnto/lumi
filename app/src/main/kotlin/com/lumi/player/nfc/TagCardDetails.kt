package com.lumi.player.nfc

import android.nfc.Tag
import android.nfc.tech.Ndef

/**
 * The additional card information a presented tag handle carries: the
 * tag's tech list and, for a tag that carries the NDEF tech, its NDEF
 * type, its NDEF capacity (bytes), and whether the NDEF area is writable.
 *
 * Everything here is read from the tag's DISCOVERY data - the tech list
 * and the NDEF tech's extras, both filled in by the framework when the
 * tag is presented - so no connection is made and no RF activity occurs:
 * this is a plain in-memory read of the delivered [Tag], safe to run on
 * any delivered tag, on the foreground-dispatch read path, at any time.
 * For a tag that does not carry the NDEF tech, the NDEF fields are null -
 * a null field is "not available" (the tag details section shows a field
 * only when its value is actually available, never guessing).
 */
data class TagCardDetails(
    val techs: List<String>,
    val ndefType: String?,
    val maxNdefSizeBytes: Int?,
    val ndefWritable: Boolean?,
) {
    /** The tech list's display names ("NfcA, Ndef"). */
    val techNames: List<String> get() = techs.map { it.substringAfterLast('.') }
}

/**
 * The [Tag]'s discovery-time card details (see [TagCardDetails]): the
 * tech list always, the NDEF type/capacity/writability when the tag
 * carries the NDEF tech. No connect, no RF.
 */
fun Tag.cardDetails(): TagCardDetails {
    val ndef = Ndef.get(this)
    return TagCardDetails(
        techs = techList.toList(),
        ndefType = ndef?.type,
        maxNdefSizeBytes = ndef?.maxSize?.takeIf { it > 0 },
        ndefWritable = ndef?.isWritable,
    )
}

/**
 * The human-readable name for the NDEF type string the platform reports
 * (the NFC Forum type strings and the vendor types the platform's NDEF
 * type field takes). An unrecognized string is shown verbatim - it is
 * the value actually reported, so it is not fabricated.
 */
fun ndefTypeName(type: String): String = when (type) {
    "org.nfcforum.ndef.type1" -> "NFC Forum Type 1"
    "org.nfcforum.ndef.type2" -> "NFC Forum Type 2"
    "org.nfcforum.ndef.type3" -> "NFC Forum Type 3"
    "org.nfcforum.ndef.type4" -> "NFC Forum Type 4"
    "com.nxp.ndef.mifareclassic" -> "Mifare Classic"
    "com.nxp.ndef.icodesli" -> "iCode SLI"
    else -> type
}
