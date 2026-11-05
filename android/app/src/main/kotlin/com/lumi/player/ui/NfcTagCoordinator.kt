package com.lumi.player.ui

import android.nfc.Tag
import androidx.lifecycle.viewModelScope
import com.lumi.player.data.EntryEntity
import com.lumi.player.data.LumiRepository
import com.lumi.player.data.TagEntity
import com.lumi.player.nfc.CardWriteGate
import com.lumi.player.nfc.LumiTagData
import com.lumi.player.nfc.LumiTagNdef
import com.lumi.player.nfc.TagCardDetails
import com.lumi.player.nfc.TagInspect
import com.lumi.player.nfc.TagPayloadRead
import com.lumi.player.nfc.cardDetails
import com.lumi.player.nfc.cardWriteGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The NFC tag session behind [LumiViewModel]'s tag-scan surface: the scan
 * arm states (the NFC tags pane's plain scan, the song profile's
 * scan-to-assign, the tag page's card-write and card-compare scans), the
 * settle of an armed scan by the next tag read, the registration capture
 * (and its save), the per-uid card details observed per presentation, and
 * the phone's NFC usability flags
 * published by [LumiViewModel]'s host activity.
 *
 * The view model keeps the read dispatch ([LumiViewModel.onTagDetected]):
 * it consults [settleIfScanning] first and, for a read no scan consumed,
 * publishes [inspectTagDetails] per presentation.
 *
 * Test seams: unit tests replace the physical card [tagWriter] /
 * [tagInspector] / [songPayloadReader] through the view model's
 * same-named delegates.
 */
class NfcTagCoordinator(
    private val viewModel: LumiViewModel,
    private val onEvent: (String) -> Unit,
) {

    private val repository: LumiRepository
        get() = viewModel.repository

    // ---- observed data for the UI ----

    /**
     * Whether a tag scan is in progress: the next tag read settles it - a
     * registered tag resolves to its profile page, an unregistered one
     * enters the registration flow (capture + naming dialog - registration
     * never writes the card).
     */
    private val _scanActive = MutableStateFlow(false)
    val scanActive: StateFlow<Boolean> = _scanActive.asStateFlow()

    /**
     * The registered tag a settled scan resolved to; consumed by the
     * settings screen (it opens the tag's profile page).
     */
    private val _pendingTagProfile = MutableStateFlow<TagEntity?>(null)
    val pendingTagProfile: StateFlow<TagEntity?> = _pendingTagProfile.asStateFlow()

    /**
     * The song an assign-to-tag scan targets (null for the NFC tags pane's
     * plain scan): while set, a read links the song to the tag the read
     * resolves to - registering the tag first, through the usual flow, when
     * it is not registered yet (the song is linked once the tag is saved).
     */
    private val _scanAssignEntry = MutableStateFlow<EntryEntity?>(null)
    val scanAssignEntry: StateFlow<EntryEntity?> = _scanAssignEntry.asStateFlow()

    // The tag whose data a card-write scan will write (the tag details
    // page's "Save to NFC tag" action; null for a plain scan). The scan
    // writes the tag's data only to the tag's OWN card - the presented
    // card's NFC ID must match the tag's uid (a mismatched card is
    // reported, nothing is written; see [settleCardWrite]).
    private val _cardWriteTarget = MutableStateFlow<TagEntity?>(null)
    val cardWriteTarget: StateFlow<TagEntity?> = _cardWriteTarget.asStateFlow()

    // The tag whose data a COMPARE scan will compare (the tag details
    // page's "Save to NFC tag" section's "Scan to compare" action; null
    // for a plain scan). The scan READS the tag's own card - the
    // presented card's NFC ID must match the tag's uid (a mismatched card
    // is reported, nothing is recorded) - and records the card's ACTUAL
    // contents as the tag's per-uid memory (a card with no lumi data
    // clears the record); the section's in-sync indicator recomputes
    // from the comparison (see [settleCardCompare]).
    private val _cardCompareTarget = MutableStateFlow<TagEntity?>(null)
    val cardCompareTarget: StateFlow<TagEntity?> = _cardCompareTarget.asStateFlow()

    // The card details observed per presented tag uid: a read updates the
    // entry for THAT uid only, so a tag's page keeps its own details no
    // matter what is scanned afterwards. A page shows the entry for its
    // own uid - never guessed: a tag whose details were never observed
    // shows the section empty.
    private val _tagCardDetails = MutableStateFlow<Map<String, TagCardDetails>>(emptyMap())
    val tagCardDetails: StateFlow<Map<String, TagCardDetails>> = _tagCardDetails.asStateFlow()

    // ---- device NFC usability (published by MainActivity from the NfcAdapter) ----

    /**
     * The phone's own NFC hardware state, published by [MainActivity]
     * (on create/resume and on the radio's state-changed broadcast, so it
     * goes live when the radio is toggled while the app is open):
     * [nfcMissing] = no NFC hardware at all; [nfcDisabled] = hardware
     * present but the radio off. The tag details page's save-to-card
     * control greys out while either holds - a card write needs the
     * phone's own radio - and the note next to it names the reason.
     */
    private val _nfcMissing = MutableStateFlow(false)
    val nfcMissing: StateFlow<Boolean> = _nfcMissing.asStateFlow()
    private val _nfcDisabled = MutableStateFlow(false)
    val nfcDisabled: StateFlow<Boolean> = _nfcDisabled.asStateFlow()

    /** Re-publishes the phone's NFC usability (the activity re-checks on every resume + state change). */
    fun setNfcUsability(missing: Boolean, disabled: Boolean) {
        _nfcMissing.value = missing
        _nfcDisabled.value = disabled
    }

    private val _capturedUid = MutableStateFlow<String?>(null)
    val capturedUid: StateFlow<String?> = _capturedUid.asStateFlow()

    /**
     * The captured tag's prefill: the payload it carried (name + song
     * list), with the name falling back to the card's REMEMBERED name
     * (the per-card memory) when the payload is missing or nameless - a
     * re-scanned card whose tag was deleted prefills its old name. Null
     * when neither the payload nor the memory offers anything.
     */
    private val _capturedTagData = MutableStateFlow<LumiTagData?>(null)
    val capturedTagData: StateFlow<LumiTagData?> = _capturedTagData.asStateFlow()

    /**
     * The captured card's RESTORE SONG LIST - the song names a save of this
     * capture should land on the tag when it comes back empty: the payload's
     * ordered song list when the card carries one (the card payload is the
     * restore source for tags written in the new format), else the
     * remembered per-uid song list (the memory of cards written before the
     * payload carried songs, or never written).
     */
    private val _capturedRestoreSongs = MutableStateFlow<List<String>>(emptyList())
    val capturedRestoreSongs: StateFlow<List<String>> = _capturedRestoreSongs.asStateFlow()

    // ---- scan arming ----

    /**
     * Arms a tag scan (the NFC tags pane's "Scan NFC" action): the next
     * tag read settles it - a registered tag opens its profile page, an
     * unregistered one goes into the registration flow (capture, naming
     * dialog - registration never writes the card). Reuses the app's only
     * read path - the foreground-dispatch read into
     * [LumiViewModel.onTagDetected] - so only the flag checked there
     * differs.
     */
    fun startScan() {
        _capturedUid.value = null
        _scanAssignEntry.value = null
        _cardWriteTarget.value = null
        _cardCompareTarget.value = null
        _scanActive.value = true
    }

    /**
     * Arms the unified scan targeting [entry] (the song profile's
     * "Scan a tag to assign" action): a read of a registered tag links the
     * song to it through the existing link mechanism, and a read of an
     * unregistered one registers it first (the naming dialog) and links
     * the song once the tag is saved. The scan dialog ([scanActive]) and
     * the foreground-dispatch read path ([LumiViewModel.onTagDetected])
     * are the app's only ones - only what a settled read links differs.
     */
    fun startScanForEntry(entry: EntryEntity) {
        _capturedUid.value = null
        _scanAssignEntry.value = entry
        _cardWriteTarget.value = null
        _cardCompareTarget.value = null
        _scanActive.value = true
    }

    /**
     * Arms the card-write scan (the tag details page's "Save to NFC tag"
     * section's write action): the next tag read - through the app's own
     * foreground-dispatch read path ([LumiViewModel.onTagDetected]) -
     * writes [tag]'s data to it ONLY when that card's NFC ID is the tag's
     * own uid (see [settleCardWrite]); a card with a different NFC ID is
     * reported and nothing is written. The scan dialog's copy asks the
     * user to hold the tag's own tag against the back of the phone.
     */
    fun startCardWrite(tag: TagEntity) {
        _capturedUid.value = null
        _scanAssignEntry.value = null
        _cardCompareTarget.value = null
        _cardWriteTarget.value = tag
        _scanActive.value = true
    }

    /**
     * Arms the COMPARE scan (the tag details page's "Save to NFC tag"
     * section's "Scan to compare" action): the next tag read - through
     * the app's own foreground-dispatch read path
     * ([LumiViewModel.onTagDetected]) - READS [tag]'s own card (its NFC
     * ID must match the tag's uid; a mismatched card is reported and
     * nothing is recorded) and records the card's actual contents as the
     * tag's per-uid memory (see [settleCardCompare]); the section's
     * in-sync indicator recomputes from the comparison.
     */
    fun startCardCompare(tag: TagEntity) {
        _capturedUid.value = null
        _scanAssignEntry.value = null
        _cardWriteTarget.value = null
        _cardCompareTarget.value = tag
        _scanActive.value = true
    }

    fun cancelScan() {
        _scanActive.value = false
        _scanAssignEntry.value = null
        _cardWriteTarget.value = null
        _cardCompareTarget.value = null
    }

    /** Consumes the resolved scan target (the settings screen has opened the tag's profile page). */
    fun consumePendingTagProfile() {
        _pendingTagProfile.value = null
    }

    // ---- scan settlement ----

    /**
     * [LumiViewModel.onTagDetected]'s scan dispatch: while a tag scan is
     * in progress, the read settles the armed scan (the card-write scan,
     * then the compare scan, then the plain scan) and returns true - a
     * read consumed by a scan never reaches the playback path.
     */
    internal fun settleIfScanning(uid: String, tag: Tag?): Boolean {
        // A card-write scan (the tag details page's "Save to NFC tag")
        // is armed for a specific card write: the read's card is written
        // the target tag's data ONLY when the card's NFC ID is the tag's
        // own uid - a mismatched card is reported, nothing is written -
        // and it never goes to registration or playback. The write path
        // makes its OWN single NDEF connection (the physical write) - no
        // payload read runs alongside it, so the write's connect never
        // hits a second client on the tag's single connection slot.
        val cardWrite = _cardWriteTarget.value
        if (_scanActive.value && cardWrite != null) {
            settleCardWrite(cardWrite, uid, tag)
            return true
        }
        // A COMPARE scan (the save section's "Scan to compare" action) is
        // armed for a specific card read: the read's card is READ (not
        // written) the target tag's data only when the card's NFC ID is
        // the tag's own uid, and its actual contents become the tag's
        // per-uid memory (see [settleCardCompare]).
        val cardCompare = _cardCompareTarget.value
        if (_scanActive.value && cardCompare != null) {
            settleCardCompare(cardCompare, uid, tag)
            return true
        }
        if (_scanActive.value) {
            settleTagScan(uid, tag)
            return true
        }
        return false
    }

    /**
     * Settles a tag read while a tag scan is in progress (the NFC tags
     * pane's "Scan NFC" action, or the song profile's scan-to-assign): a
     * registered tag resolves to its profile page (the settings screen
     * consumes [pendingTagProfile] and opens the tag detail page) - or, for
     * a scan-to-assign, links its target song to it; an unknown one goes
     * into the registration flow (capture + naming dialog - registration
     * never writes the card), and a scan-to-assign's song waits for the
     * tag's save. Either way the scan is over, so further reads are normal
     * playback reads.
     */
    private fun settleTagScan(uid: String, tag: Tag?) {
        _scanActive.value = false
        viewModel.viewModelScope.launch {
            val existing = repository.tagByUid(uid)
            val assignEntry = _scanAssignEntry.value
            if (existing != null) {
                if (assignEntry != null) {
                    // The song profile's scan-to-assign: link the song
                    // to the scanned tag (the picker's own mechanism -
                    // a tag that already plays it is reported, not
                    // duplicated). The profile page's live assigned-tags
                    // list picks the link up; the user stays on it.
                    _scanAssignEntry.value = null
                    viewModel.linkEntryToTag(existing, assignEntry)
                } else {
                    _pendingTagProfile.value = existing
                }
            } else {
                capture(uid, tag)
            }
        }
    }

    /**
     * Settles a tag read while a card-write scan is armed (the tag
     * details page's "Save to NFC tag" section's write action): the read's card is written the
     * target tag's data - the tag's name and the tag's ORDERED SONG LIST
     * (by the songs' display names, "song not
     * found" names among them - the names a re-registered tag restores
     * name-for-name; the play mode is no longer part of the written
     * payload) - the card's own payload shape - through the existing
     * write path, but ONLY when the card's NFC ID ([uid]) is the tag's own
     * uid: a mismatched card is reported and nothing is written to it.
     * Before any per-card verdict the payload's own size is checked
     * against the app's write cap ([LumiTagNdef.MAX_PAYLOAD_BYTES]): a
     * payload that exceeds it can be refused by NO card, so it is
     * reported as too large - a permanent size fact, never as a card
     * failure to retry - and nothing is written to any card. For the
     * matching card the verdict is discovery-time data (no connect, no
     * RF): a card that isn't NDEF, is read-only, or is too small to hold
     * the payload is reported and nothing is written to it; only a card
     * that passes reaches the physical write (which itself reports a
     * failure - an unknown-capacity card can turn out too small there, or
     * the card left range mid-write). A write that lands remembers the
     * card's last saved name + songs (the per-card memory). The scan
     * settles either way (the target is cleared and the flag disarmed):
     * further reads are normal reads, never re-routed to this write - the
     * user re-arms the action to try again.
     */
    private fun settleCardWrite(target: TagEntity, uid: String, tag: Tag?) {
        _scanActive.value = false
        _cardWriteTarget.value = null
        if (tag == null) {
            onEvent("Couldn't read that tag - try again.")
            return
        }
        // The card must BE the tag's own card: the presented card's NFC
        // ID has to match the tag's uid (the same string the tags table
        // stores). A different card is reported and left untouched.
        if (uid != target.uid) {
            onEvent("That card's NFC ID doesn't match this tag's - nothing was written.")
            return
        }
        viewModel.viewModelScope.launch {
            // The full payload: the tag's name + ordered song list (the
            // names the list shows, missing songs' snapshot names
            // included - the restore source for a re-registered tag). The
            // play mode is no longer part of the written payload.
            val names = repository.songNamesForTag(target.id)
            val payload = LumiTagData(name = target.name, songs = names)
            val messageBytes = LumiTagNdef.messageByteLength(LumiTagNdef.toNdefMessage(payload))
            if (messageBytes > LumiTagNdef.MAX_PAYLOAD_BYTES) {
                onEvent(
                    "The tag's data is too large to save to a tag - it needs $messageBytes bytes, the app writes at most ${LumiTagNdef.MAX_PAYLOAD_BYTES}. Nothing was written.",
                )
                return@launch
            }
            val gate = withContext(Dispatchers.IO) { cardWriteGate(tag, payload) }
            onEvent(
                when (gate) {
                    is CardWriteGate.Ok ->
                        if (withContext(Dispatchers.IO) { tagWriter(tag, payload) }) {
                            // The write landed: the card now carries the
                            // name + songs, and the per-uid memory
                            // follows the written card (the restore
                            // source that survives the tag row's deletion;
                            // the save section's in-sync indicator
                            // recomputes from it).
                            repository.rememberTag(target.uid, target.name, names)
                            "Saved to the tag."
                        } else {
                            "Couldn't write to that tag - keep it on the phone and try again."
                        }
                    is CardWriteGate.NotNdef ->
                        "That tag isn't an NDEF tag - nothing was written."
                    is CardWriteGate.ReadOnly ->
                        "That tag is read-only - nothing was written."
                    is CardWriteGate.TooSmall ->
                        "That tag is too small for the tag's data - it holds ${gate.capacityBytes} bytes, the data needs ${gate.neededBytes}. Nothing was written."
                },
            )
        }
    }

    /**
     * Settles a tag read while a COMPARE scan is armed (the tag details
     * page's "Save to NFC tag" section's "Scan to compare" action): the
     * read's card is READ - not written - and its lumi payload (name +
     * songs) is recorded as the tag's per-uid saved memory, but ONLY
     * when the card's NFC ID ([uid]) is the tag's own uid: a mismatched
     * card is reported with the write path's own verdict language and
     * nothing is recorded. For the matching card the read's payload is
     * the card's ACTUAL current contents: a card that carries a lumi
     * record updates the memory to it (the section's in-sync indicator
     * then recomputes from the comparison - InSync when the card equals
     * the tag's current system data, Differs when it does not), and a
     * card that carries NO lumi data CLEARS the record (the indicator
     * goes Unknown - nothing saved or compared). A read that could not
     * connect says nothing about the card's payload (it must never be
     * read as "the card carries no record"), so it reports and records
     * nothing. The scan settles either way (disarmed): further reads are
     * normal reads, never re-routed to the compare.
     */
    private fun settleCardCompare(target: TagEntity, uid: String, tag: Tag?) {
        _scanActive.value = false
        _cardCompareTarget.value = null
        if (tag == null) {
            onEvent("Couldn't read that tag - try again.")
            return
        }
        // The card must BE the tag's own card (the same uid-match the
        // write path enforces): a different card is reported with the
        // write path's own verdict language and left untouched.
        if (uid != target.uid) {
            onEvent("That card's NFC ID doesn't match this tag's - nothing was written.")
            return
        }
        viewModel.viewModelScope.launch {
            val read = withContext(Dispatchers.IO) { songPayloadReader(tag) }
            when (read) {
                is TagPayloadRead.Answered -> {
                    val data = read.data
                    if (data != null) {
                        // The card's ACTUAL contents become the tag's
                        // last known tag state (the per-uid memory): the
                        // indicator recomputes from the comparison.
                        repository.rememberTag(uid, data.name, data.songs)
                        val systemNames = repository.songNamesForTag(target.id)
                        onEvent(
                            if (data.name == target.name && data.songs == systemNames) {
                                "In sync - no changes since the last save."
                            } else {
                                "This tag's data differs from the saved tag."
                            },
                        )
                    } else {
                        // The card carries no lumi data: the record is
                        // cleared (the indicator goes Unknown - nothing
                        // saved or compared).
                        repository.forgetTag(uid)
                        onEvent("That tag carries no saved data.")
                    }
                }

                is TagPayloadRead.Failed -> onEvent("Couldn't read that tag - try again.")
            }
        }
    }

    // ---- per-presentation observation ----

    /**
     * Observes a presented tag's additional card details (its tech list,
     * and, when the handle carries the NDEF tech, its NDEF type, capacity
     * and writability - all of it discovery-time data: no connection, no
     * RF activity) and records them for that tag's uid. The record is
     * PER-UID (a read updates only that uid's entry), so a tag's page
     * keeps its own details no matter what is scanned afterwards, and a
     * re-scan of the tag refreshes them. A page shows the details only
     * for the tag it is open on (its own uid), so a read of another tag
     * can never surface its details - and a tag whose details were never
     * observed shows the section empty (never guessed).
     */
    internal fun inspectTagDetails(uid: String, tag: Tag) {
        viewModel.viewModelScope.launch {
            // Pure discovery-data reads (no IO, no binder): the launch
            // body runs before any suspension, so the publish is ordered
            // with the read that produced it.
            _tagCardDetails.value = _tagCardDetails.value + (uid to tag.cardDetails())
        }
    }

    // ---- registration capture and save ----

    /**
     * Records a tag captured during a registration session, reading its
     * lumi payload (for the name/songs form prefill) and merging the
     * card's per-uid memory (the last saved name + song list) as the
     * fallback prefill and restore source. Registration NEVER writes the
     * physical card - the explicit card-write action (the tag detail
     * page's "Save to NFC tag") is the only write path.
     */
    internal fun capture(uid: String, tag: Tag? = null) {
        _capturedUid.value = uid
        if (tag == null) {
            _capturedTagData.value = null
            _capturedRestoreSongs.value = emptyList()
            return
        }
        viewModel.viewModelScope.launch {
            // The capture's read is this presentation's only NDEF read:
            // nothing else runs an NDEF session against the tag here, so
            // it never races for the tag's single connection slot.
            val inspected = withContext(Dispatchers.IO) {
                tagInspector(tag, LumiTagData(name = ""))
            }
            if (_capturedUid.value != uid) return@launch
            when (val read = inspected.read) {
                is TagPayloadRead.Answered -> publishCapture(uid, read.data)
                is TagPayloadRead.Failed -> publishCapture(uid, null)
            }
        }
    }

    /** Test seam: capture with an explicit payload (no live tag). */
    internal fun captureForTest(uid: String, data: LumiTagData?) {
        _capturedUid.value = uid
        viewModel.viewModelScope.launch { publishCapture(uid, data) }
    }

    /**
     * Publishes a capture's prefill + restore songs: the payload when it
     * offers the name (a payload-less card falls back to the card's
     * REMEMBERED name, and a nameless payload to the remembered one), and
     * the restore song list (the payload's songs when non-empty, else the
     * remembered songs).
     */
    private suspend fun publishCapture(uid: String, data: LumiTagData?) {
        val memory = repository.memoryForUid(uid)
        _capturedTagData.value = when {
            data == null -> memory?.name?.takeIf { it.isNotBlank() }?.let { LumiTagData(it) }
            data.name.isBlank() && (memory?.name?.isNotBlank() == true) -> data.copy(name = memory.name)
            else -> data
        }
        _capturedRestoreSongs.value = data?.songs?.takeIf { it.isNotEmpty() } ?: (memory?.songs ?: emptyList())
    }

    fun discardCaptured() {
        _capturedUid.value = null
        _scanAssignEntry.value = null
        _capturedTagData.value = null
        _capturedRestoreSongs.value = emptyList()
    }

    /**
     * Saves the captured tag under [name]. Registration never writes the
     * physical card - the explicit card-write action is the only write
     * path - but it remembers the card's last saved name + song list (the
     * per-card memory that survives the tag row's deletion): when the card
     * comes back with an EMPTY tag (its tag was deleted and re-registered,
     * or a fresh registration of a card whose payload carries songs), the
     * capture's restore song list (the payload's songs, else the remembered
     * ones) is linked onto the tag name-for-name, with each name resolving
     * to the live content entry that plays it - a name of exactly the
     * payload's 50-char clamp that no entry carries exactly resolves to
     * the one longer stored label it was clamped from, and only when
     * exactly one label starts with it (the link stores that label in
     * full) - or staying a "song not found" row when nothing resolves
     * (no exact match, or several labels share the clamped prefix).
     */
    fun confirmTagName(name: String) {
        val uid = _capturedUid.value ?: return
        val restoreSongs = _capturedRestoreSongs.value
        // A scan-to-assign's target song, if the capture came from one: it
        // is linked once this save settles (below).
        val assignEntry = _scanAssignEntry.value
        _capturedUid.value = null
        _capturedTagData.value = null
        _capturedRestoreSongs.value = emptyList()
        viewModel.viewModelScope.launch {
            val saved = repository.addTag(uid, name)
            onEvent(if (saved != null) "Tag added!" else "That tag is already registered.")
            val tag = saved ?: repository.tagByUid(uid)
            // The card's songs restored onto a tag that came back empty -
            // the card payload's list when it carried one, else the
            // remembered per-uid list. Names land in their carried order;
            // each resolves to the live content entry its name points at
            // (a 50-char clamped name the payload's name cap wrote as a
            // prefix resolves to the one longer stored label it was
            // clamped from, and only when exactly one label starts with
            // it; a name the content carries in no form resolves to
            // nothing), and the link stores that entry's full label - or
            // the carried name, when nothing resolves (the tag's "song not
            // found" row).
            if (tag != null && restoreSongs.isNotEmpty() && repository.entriesForTag(tag.id).isEmpty()) {
                restoreSongs.distinct().forEach { songName ->
                    val entry = repository.entryByLabel(songName)
                    repository.insertLinkByName(tag.id, entry?.label ?: songName, entry?.id)
                }
            }
            // The card's last saved name + song list remembered per uid -
            // the restore source that survives this tag row's deletion.
            // The name is stored the way the tag row stores it (trimmed),
            // so a later re-scan prefills exactly what was saved.
            tag?.let { repository.rememberTag(uid, name.trim(), repository.songNamesForTag(it.id)) }
            if (saved != null) viewModel.selectTag(saved.id)
            // The scan-to-assign's song is assigned once its tag is saved -
            // the tag it was scanned against, looked up by uid when the tag
            // already existed.
            assignEntry?.let { entry ->
                _scanAssignEntry.value = null
                tag?.let { viewModel.linkEntryToTag(it, entry) }
            }
        }
    }

    // ---- physical card seams ----

    /** Test seam: the physical card write, replaced in unit tests. */
    internal var tagWriter: suspend (Tag?, LumiTagData) -> Boolean = { tag, data ->
        LumiTagNdef.write(tag, data)
    }

    /**
     * Test seam: the physical tag inspect (payload + writability in one
     * NDEF session), replaced in unit tests.
     */
    internal var tagInspector: (Tag?, LumiTagData) -> TagInspect = { tag, fallback ->
        LumiTagNdef.inspect(tag, fallback)
    }

    /**
     * Test seam: the card's lumi payload read (the compare scan's read;
     * an answered read is the card's payload, a failed read says nothing
     * about it), replaced in unit tests.
     */
    internal var songPayloadReader: suspend (Tag?) -> TagPayloadRead = { tag ->
        LumiTagNdef.readFromTag(tag)
    }
}
