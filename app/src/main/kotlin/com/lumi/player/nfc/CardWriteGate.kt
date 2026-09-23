package com.lumi.player.nfc

import android.nfc.Tag
import android.nfc.tech.Ndef

/**
 * A presented card's verdict on holding the app's [LumiTagData] payload,
 * read from discovery-time data ONLY - the NDEF tech's extras (the
 * writable flag and the NDEF capacity, both filled in when the card is
 * presented) - so no connection is made and no RF activity occurs: the
 * gate can be checked on any delivered card, on the foreground-dispatch
 * read path, at any time.
 *
 * A card that passes ([Ok]) is the only kind the physical write is
 * attempted on: the gate is the negative case's whole report - a card
 * that isn't NDEF, is read-only, or is too small is recognized from its
 * discovery data and reported, and nothing is ever written to it.
 */
sealed interface CardWriteGate {
    /** The card is NDEF formatted, writable, and its area holds the payload. */
    object Ok : CardWriteGate

    /** The card isn't NDEF formatted (there is no NDEF area to write to). */
    object NotNdef : CardWriteGate

    /** The card is NDEF formatted but read-only. */
    object ReadOnly : CardWriteGate

    /**
     * The card's KNOWN NDEF area cannot hold the payload (the discovery
     * data reported a capacity, and it is below what the data needs).
     */
    data class TooSmall(val capacityBytes: Int, val neededBytes: Int) : CardWriteGate
}

/**
 * Gates a card-write of [data] on [tag] (see [CardWriteGate]): the card
 * must be NDEF formatted, writable, and - when the discovery data reports
 * a capacity - large enough for the message (the write replaces the
 * card's whole NDEF message, so the full area is available - the same
 * arithmetic the write itself uses). A capacity the discovery data does
 * not report ([Ndef.getMaxSize] answering 0 - no NDEF length in the
 * card's extras, the same case the details reader treats as unavailable)
 * is NOT a verdict: the card passes the gate, and the physical write -
 * whose own capacity check runs on the connected handle, where the
 * real size is known - is the one that decides (a card that turns out
 * to be too small fails there and reports the write's failure). Pure
 * discovery-data reads; no connect, no RF.
 */
fun cardWriteGate(tag: Tag, data: LumiTagData): CardWriteGate {
    val ndef = try {
        Ndef.get(tag)
    } catch (e: Exception) {
        null
    } ?: return CardWriteGate.NotNdef
    if (!ndef.isWritable) return CardWriteGate.ReadOnly
    val needed = LumiTagNdef.messageByteLength(LumiTagNdef.toNdefMessage(data))
    val capacity = ndef.maxSize
    return if (capacity > 0 && capacity < needed) CardWriteGate.TooSmall(capacity, needed)
    else CardWriteGate.Ok
}
