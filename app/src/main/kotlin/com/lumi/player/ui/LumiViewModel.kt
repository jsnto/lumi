package com.lumi.player.ui

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.nfc.Tag
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import com.lumi.player.data.EntryEntity
import com.lumi.player.data.ContentSortDirection
import com.lumi.player.data.PlayMode
import com.lumi.player.data.PositionRestore
import com.lumi.player.data.SongMetadata
import com.lumi.player.data.TagEntity
import com.lumi.player.data.TagSongRow
import com.lumi.player.data.FolderChild
import com.lumi.player.data.FolderEntity
import com.lumi.player.data.LumiRepository
import com.lumi.player.data.ScannedSong
import com.lumi.player.data.collectAudioFiles
import com.lumi.player.data.decideRestore
import com.lumi.player.data.fingerprintOf
import com.lumi.player.data.folderDocIdOf
import com.lumi.player.data.rescanNewSongs
import com.lumi.player.nfc.CardWriteGate
import com.lumi.player.nfc.LumiTagData
import com.lumi.player.nfc.LumiTagNdef
import com.lumi.player.nfc.TagCardDetails
import com.lumi.player.nfc.TagInspect
import com.lumi.player.nfc.cardDetails
import com.lumi.player.nfc.cardWriteGate
import com.lumi.player.player.EntrySpec
import com.lumi.player.player.NfcAction
import com.lumi.player.player.PlaybackPolicy
import com.lumi.player.player.PlaybackStatus
import com.lumi.player.player.PlayerService
import com.lumi.player.player.PlayerStateHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Single view model for the whole app (front screen + settings). It owns the
 * [PlaybackPolicy] for the active play mode, the NFC registration flow, and
 * all registration/linking mutations.
 */
class LumiViewModel(
    private val context: Context,
    val repository: LumiRepository,
) : ViewModel() {

    /** The current play mode, persisted in the database. */
    val playMode: StateFlow<PlayMode> =
        repository.observePlayMode().stateIn(viewModelScope, SharingStarted.Eagerly, PlayMode.CONTINUOUS)

    /** Tag read/lost decisions for the active mode. */
    @Volatile
    private var policy: PlaybackPolicy = PlaybackPolicy(playMode.value)

    // ---- observed data for the UI ----

    fun observeTags(): Flow<List<TagEntity>> = repository.observeTags()

    fun observeEntries(): Flow<List<EntryEntity>> = repository.observeEntries()

    /** Added folder trees, each rescannable for new songs. */
    fun observeFolders(): Flow<List<FolderEntity>> = repository.observeFolders()

    /** The individual content list: the stored rows with no folder marker. */
    fun observeIndividualEntries(): Flow<List<EntryEntity>> = repository.observeIndividualEntries()

    /** The tags that link the entry (the song profile's assign-to-tag flags), live. */
    fun observeTagsForEntry(entryId: Long): Flow<List<TagEntity>> = repository.observeTagsForEntry(entryId)

    // ---- tag scan: the "Scan NFC" action of the NFC tags pane ----

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
    // page's "Save to NFC card" action; null for a plain scan). The scan
    // writes the tag's data only to the tag's OWN card - the presented
    // card's NFC ID must match the tag's uid (a mismatched card is
    // reported, nothing is written; see [settleCardWrite]).
    private val _cardWriteTarget = MutableStateFlow<TagEntity?>(null)
    val cardWriteTarget: StateFlow<TagEntity?> = _cardWriteTarget.asStateFlow()

    // The card details observed per presented tag uid: a read updates the
    // entry for THAT uid only, so a tag's page keeps its own details no
    // matter what is scanned afterwards. A page shows the entry for its
    // own uid - never guessed: a tag whose details were never observed
    // shows the section empty.
    private val _tagCardDetails = MutableStateFlow<Map<String, TagCardDetails>>(emptyMap())
    val tagCardDetails: StateFlow<Map<String, TagCardDetails>> = _tagCardDetails.asStateFlow()

    private val _capturedUid = MutableStateFlow<String?>(null)
    val capturedUid: StateFlow<String?> = _capturedUid.asStateFlow()

    /**
     * The captured tag's prefill: the payload it carried (name + mode +
     * song list), with the name falling back to the card's REMEMBERED name
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

    // ---- transient UI events (toasts/snackbars) ----

    private val _uiEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val uiEvents: SharedFlow<String> = _uiEvents

    /**
     * Emitted when a tag read makes a tag the ACTIVE one - starting playback
     * from idle or switching from another tag's list. The activity seeds its
     * proximity presence record here because this is the point where the new
     * active uid is observable: the policy switch happens asynchronously in
     * [onTagDetected] after a Room query, so the activity cannot know from
     * the raw read alone which tag became active.
     */
    private val _tagActivated = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val tagActivated: SharedFlow<String> = _tagActivated

    // ---- tag detail page: selected tag + its live entry list ----

    private val _selectedTagId = MutableStateFlow<Long?>(null)

    fun selectTag(id: Long?) {
        _selectedTagId.value = id
    }

    val effectiveSelectedTagId: StateFlow<Long?> =
        combine(_selectedTagId, repository.observeTags()) { selected, tags ->
            selected ?: tags.firstOrNull()?.id
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val linkedEntries: StateFlow<List<TagSongRow>> =
        effectiveSelectedTagId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else repository.observeEntriesForTag(id)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** UID of the tag whose list is currently playing, if any. */
    val activeTagUid: String? get() = policy.activeTagUid

    init {
        viewModelScope.launch {
            playMode.collect { mode ->
                policy = PlaybackPolicy(mode).also { p ->
                    val carried = PlayerStateHolder.state.value
                    if (carried.status != PlaybackStatus.IDLE) {
                        p.seedActiveTagUid(carried.activeTagUid)
                    }
                }
            }
        }
        // Best-effort v3 -> v4 backfill: content rows stored before the
        // folder marker existed are stamped with the folder that imported
        // them (the same tree walk the folder page uses); a folder that can
        // no longer be read degrades to its songs appearing in the
        // individual content list (its rows keep no marker).
        viewModelScope.launch { backfillFolderEntries() }
    }

    // ---- NFC events from the activity ----

    /**
     * A tag was detected (a foreground-dispatch read in either play mode
     * or a tap delivered by intent on cold start). While a tag scan is in
     * progress, the read settles the scan: a registered tag resolves to
     * its profile page, an unregistered one is captured for the
     * registration flow.
     * [tag] is the raw NFC tag: its lumi payload is read for the
     * registration's form prefill and its discovery data recorded as the
     * card's per-uid details.
     */
    fun onTagDetected(uid: String, tag: Tag? = null) {
        if (tag != null) inspectTagDetails(uid, tag)
        // A card-write scan (the tag details page's "Save to NFC card")
        // is armed for a specific card write: the read's card is written
        // the target tag's data ONLY when the card's NFC ID is the tag's
        // own uid - a mismatched card is reported, nothing is written -
        // and it never goes to registration or playback.
        val cardWrite = _cardWriteTarget.value
        if (_scanActive.value && cardWrite != null) {
            settleCardWrite(cardWrite, uid, tag)
            return
        }
        if (_scanActive.value) {
            settleTagScan(uid, tag)
            return
        }
        val paused = PlayerStateHolder.state.value.status == PlaybackStatus.PAUSED
        if (policy.activeTagUid == uid && !paused) return
        viewModelScope.launch {
            val tag = repository.tagByUid(uid) ?: return@launch
            // The rows resolve live by name: a name whose song has been
            // removed from the library contributes no spec to the queue
            // (and a list whose songs are all missing plays nothing, like
            // an empty one: it must not become the active tag).
            val entries = repository.entriesForTag(tag.id).mapNotNull { it.entry }
            if (entries.isEmpty()) {
                // A tag with no linked songs plays nothing: it must not become
                // the active tag (that would hold the policy, and arm the
                // presence poll, for a list that never plays).
                event("Nothing is linked to ${tag.name.ifEmpty { "this tag" }} yet.")
                return@launch
            }
            when (policy.onTagDetected(uid, paused)) {
                NfcAction.StartPlayback -> {
                    // This read made [uid] the active tag (start or switch);
                    // let the activity seed its presence record with it.
                    _tagActivated.tryEmit(uid)
                    startPlayer(entries.map { it.toSpec() }, uid)
                }

                // Re-read of the tag already playing while the user had
                // paused it: reading overrides the pause.
                NfcAction.Resume -> command(PlayerService.ACTION_RESUME)

                // No-op outcomes for a read: a re-read of the tag already
                // playing (Ignore), or a proximity pause (handled via
                // [onTagLost] instead of the read path).
                NfcAction.Ignore, NfcAction.PausePlayback -> Unit
            }
        }
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
        viewModelScope.launch {
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
                    linkEntryToTag(existing, assignEntry)
                } else {
                    _pendingTagProfile.value = existing
                }
            } else {
                capture(uid, tag)
            }
        }
    }

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
    private fun inspectTagDetails(uid: String, tag: Tag) {
        viewModelScope.launch {
            // Pure discovery-data reads (no IO, no binder): the launch
            // body runs before any suspension, so the publish is ordered
            // with the read that produced it.
            _tagCardDetails.value = _tagCardDetails.value + (uid to tag.cardDetails())
        }
    }

    /**
     * Settles a tag read while a card-write scan is armed (the tag
     * details page's "Save to NFC card"): the read's card is written the
     * target tag's data - the tag's name, the app's play mode, and the
     * tag's ORDERED SONG LIST (by the songs' display names, "song not
     * found" names among them - the names a re-registered tag restores
     * name-for-name) - the card's own payload shape - through the existing
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
            event("Couldn't read that card - try again.")
            return
        }
        // The card must BE the tag's own card: the presented card's NFC
        // ID has to match the tag's uid (the same string the tags table
        // stores). A different card is reported and left untouched.
        if (uid != target.uid) {
            event("That card's NFC ID doesn't match this tag's - nothing was written.")
            return
        }
        viewModelScope.launch {
            // The full payload: the tag's name + mode + ordered song list
            // (the names the list shows, missing songs' snapshot names
            // included - the restore source for a re-registered tag).
            val names = repository.songNamesForTag(target.id)
            val payload = LumiTagData(name = target.name, mode = playMode.value, songs = names)
            val messageBytes = LumiTagNdef.messageByteLength(LumiTagNdef.toNdefMessage(payload))
            if (messageBytes > LumiTagNdef.MAX_PAYLOAD_BYTES) {
                event(
                    "The tag's data is too large to save to a card - it needs $messageBytes bytes, the app writes at most ${LumiTagNdef.MAX_PAYLOAD_BYTES}. Nothing was written.",
                )
                return@launch
            }
            val gate = withContext(Dispatchers.IO) { cardWriteGate(tag, payload) }
            event(
                when (gate) {
                    is CardWriteGate.Ok ->
                        if (withContext(Dispatchers.IO) { tagWriter(tag, payload) }) {
                            // The write landed: the card now carries the
                            // name + songs, and the per-uid memory follows
                            // the written card (the restore source that
                            // survives the tag row's deletion).
                            repository.rememberTag(target.uid, target.name, names)
                            "Saved to the card."
                        } else {
                            "Couldn't write to that card - keep it on the phone and try again."
                        }
                    is CardWriteGate.NotNdef ->
                        "That card isn't an NDEF card - nothing was written."
                    is CardWriteGate.ReadOnly ->
                        "That card is read-only - nothing was written."
                    is CardWriteGate.TooSmall ->
                        "That card is too small for the tag's data - it holds ${gate.capacityBytes} bytes, the data needs ${gate.neededBytes}. Nothing was written."
                },
            )
        }
    }

    /**
     * The tag left the reader range (proximity mode). Pauses playback rather
     * than stopping the session: the tag stays active in the policy, so a
     * re-read of it resumes the paused list ("reading overrides pause").
     */
    fun onTagLost(uid: String) {
        if (policy.onTagLost(uid) == NfcAction.PausePlayback) {
            command(PlayerService.ACTION_PAUSE)
        }
    }

    /** The service reported playback stopped on its own. */
    fun onPlaybackStopped() {
        policy.onPlaybackStopped()
    }

    // ---- player commands ----

    /**
     * Starts the service to play [specs] for tag [uid]. When the tag has a
     * remembered position saved against this exact linked list, it is passed
     * to the service so playback resumes where it stopped. A null [uid] is
     * manual playback (the content play buttons): no remembered position
     * applies and the policy's active tag is cleared, so a later tag read
     * starts that tag's own list instead of resuming or being ignored
     * against the manual queue.
     */
    private suspend fun startPlayer(specs: List<EntrySpec>, uid: String?) {
        if (uid == null) {
            policy.onPlaybackStopped()
        }
        val intent = Intent(context, PlayerService::class.java).apply {
            action = PlayerService.ACTION_START
            putExtra(PlayerService.EXTRA_ENTRIES, ArrayList(specs))
            putExtra(PlayerService.EXTRA_TAG_UID, uid)
            if (uid != null) applyStartPosition(uid, specs)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * The remembered-position start extras for a tag's list: when the tag
     * has a position saved against this exact linked list, the start entry
     * index and the position the service seeks to on its first ready state.
     */
    private suspend fun Intent.applyStartPosition(uid: String, specs: List<EntrySpec>) {
        when (val restore = decideRestore(repository.positionFor(uid), fingerprintOf(specs), specs.size)) {
            is PositionRestore.StartFresh -> Unit
            is PositionRestore.SeekTo -> {
                putExtra(PlayerService.EXTRA_START_ENTRY_INDEX, restore.entryIndex)
                putExtra(PlayerService.EXTRA_START_POSITION_MS, restore.positionMs)
            }
        }
    }

    fun command(action: String) {
        val intent = Intent(context, PlayerService::class.java).apply { this.action = action }
        context.startService(intent)
    }

    // ---- settings: mode ----

    fun setPlayMode(mode: PlayMode) {
        viewModelScope.launch { repository.setPlayMode(mode) }
    }

    // ---- settings: soft lock ----

    /** Whether the user asked not to show the soft-lock prompt again. */
    val lockPopupSuppressed: StateFlow<Boolean> =
        repository.observeLockPopupSuppressed()
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setLockPopupSuppressed(suppressed: Boolean) {
        viewModelScope.launch { repository.setLockPopupSuppressed(suppressed) }
    }

    // ---- settings: keep screen on ----

    /**
     * Whether the user wants the screen to stay on while the app is open.
     * Off by default: the screen follows the system timeout while the app
     * is open.
     */
    val keepScreenOn: StateFlow<Boolean> =
        repository.observeKeepScreenOn()
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setKeepScreenOn(on: Boolean) {
        viewModelScope.launch { repository.setKeepScreenOn(on) }
    }

    // ---- settings: nfc re-read wait ----

    /**
     * How many seconds of absence of reports on the active tag (proximity
     * mode) count as the tag having been removed; persisted in the database.
     */
    val nfcRereadSeconds: StateFlow<Long> =
        repository.observeNfcRereadSeconds()
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                LumiRepository.NFC_REREAD_SECONDS_DEFAULT,
            )

    fun setNfcRereadSeconds(seconds: Long) {
        viewModelScope.launch { repository.setNfcRereadSeconds(seconds) }
    }

    // ---- settings: content sort direction ----

    /**
     * The content list's title sort direction (both the Folders section and
     * the individual content follow it). Ascending is the default - the
     * list shows A->Z until the user picks otherwise; the pick persists in
     * the database (an explicit pick stays authoritative over the default).
     */
    val contentSortDirection: StateFlow<ContentSortDirection> =
        repository.observeContentSortDirection()
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                ContentSortDirection.ASCENDING,
            )

    fun setContentSortDirection(direction: ContentSortDirection) {
        viewModelScope.launch { repository.setContentSortDirection(direction) }
    }

    // ---- content: manual playback (the play buttons) ----

    /**
     * Plays a single stored song (a content row's play button): starts the
     * session with just that entry through the app's existing playback path
     * (the service's ACTION_START), unattached to any tag - a later tag
     * read still switches to the tag's own list.
     */
    fun playEntry(entry: EntryEntity) {
        viewModelScope.launch {
            startPlayer(listOf(entry.toSpec()), uid = null)
        }
    }

    /**
     * Toggles the currently playing song between play and pause (the
     * folder page's song-row play/pause button): the service's own
     * ACTION_TOGGLE - a paused current song resumes the SAME song (no
     * fresh start, no new queue), and a playing one pauses in place. The
     * button's state is live off the shared playback holder's status and
     * current label, so the row tracks the toggle without a second source
     * of playback truth.
     */
    fun toggleCurrentPlayback() {
        command(PlayerService.ACTION_TOGGLE)
    }

    // ---- song profile page: metadata ----

    /** The open song's media metadata; null while the read is in flight. */
    private val _songMetadata = MutableStateFlow<SongMetadata?>(null)
    val songMetadata: StateFlow<SongMetadata?> = _songMetadata.asStateFlow()

    private var selectedEntryId: Long? = null
    private var metadataJob: Job? = null

    /**
     * Points the song profile page at [entry] and loads its media metadata
     * (artist / album / duration) best-effort: the read runs off the main
     * thread with a time limit, and any failure (an unreadable file, a
     * revoked grant) settles to no metadata rather than an error - the page
     * shows its placeholders instead. Re-selecting cancels an in-flight
     * read; a read that settles after the page moved on writes nothing.
     */
    fun selectEntry(entry: EntryEntity) {
        selectedEntryId = entry.id
        _songMetadata.value = null
        metadataJob?.cancel()
        metadataJob = viewModelScope.launch {
            val metadata = readEntryMetadata(entry)
            if (selectedEntryId == entry.id) _songMetadata.value = metadata
        }
    }

    /**
     * The best-effort metadata read for [entry]: off the main thread with a
     * time limit, settling to no metadata on any failure (an unreadable
     * file, a revoked grant) rather than an error - the page shows its
     * placeholders instead.
     */
    private suspend fun readEntryMetadata(entry: EntryEntity): SongMetadata? =
        try {
            withTimeout(METADATA_READ_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { metadataReader(Uri.parse(entry.payload)) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read the metadata of ${entry.label}", e)
            null
        }

    /**
     * Test seam: the song profile's media-metadata read, replaced in unit
     * tests. The default reads the file's tags with a [MediaMetadataRetriever]
     * (a blocking content-resolver read - [selectEntry] keeps it off the
     * main thread and time-limited); any read failure surfaces as the
     * exception the caller settles to no metadata. The pruned framework's
     * metadata surface is [MediaMetadataRetriever.extractMetadata] with the
     * int [MediaMetadataRetriever.METADATA_KEY_ARTIST]-style keys (a
     * duration comes back as its millisecond count's text). The retriever is
     * released with [MediaMetadataRetriever.close] on API 29+ and the native
     * [MediaMetadataRetriever.release] below - [MediaMetadataRetriever.close]
     * does not exist before API 29, and minSdk is 26.
     */
    internal var metadataReader: (Uri) -> SongMetadata = { uri ->
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            SongMetadata(
                artist = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.ifEmpty { null },
                album = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?.ifEmpty { null },
                durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.takeIf { it > 0 },
            )
        } finally {
            if (Build.VERSION.SDK_INT >= 29) {
                retriever.close()
            } else {
                retriever.release()
            }
        }
    }

    private companion object {
        /** How long a song's metadata read may take before it gives up. */
        const val METADATA_READ_TIMEOUT_MS = 5_000L
    }

    // ---- settings: tags ----

    /**
     * Arms a tag scan (the NFC tags pane's "Scan NFC" action): the next
     * tag read settles it - a registered tag opens its profile page, an
     * unregistered one goes into the registration flow (capture, naming
     * dialog - registration never writes the card). Reuses the app's only
     * read path - the foreground-dispatch read into [onTagDetected] - so
     * only the flag checked there differs.
     */
    fun startTagScan() {
        _capturedUid.value = null
        _scanAssignEntry.value = null
        _scanActive.value = true
    }

    /**
     * Arms the unified scan targeting [entry] (the song profile's
     * "Scan a tag to assign" action): a read of a registered tag links the
     * song to it through the existing link mechanism, and a read of an
     * unregistered one registers it first (the naming dialog) and links
     * the song once the tag is saved. The scan dialog ([scanActive]) and
     * the foreground-dispatch read path ([onTagDetected]) are the app's
     * only ones - only what a settled read links differs.
     */
    fun startTagScanForEntry(entry: EntryEntity) {
        _capturedUid.value = null
        _scanAssignEntry.value = entry
        _scanActive.value = true
    }

    /**
     * Arms the card-write scan (the tag details page's "Save to NFC card"
     * action): the next tag read - through the app's own
     * foreground-dispatch read path ([onTagDetected]) - writes [tag]'s
     * data to it ONLY when that card's NFC ID is the tag's own uid
     * (see [settleCardWrite]); a card with a different NFC ID is
     * reported and nothing is written. The scan dialog's copy asks the
     * user to hold the tag's own card against the back of the phone.
     */
    fun startCardWrite(tag: TagEntity) {
        _capturedUid.value = null
        _scanAssignEntry.value = null
        _cardWriteTarget.value = tag
        _scanActive.value = true
    }

    fun cancelTagScan() {
        _scanActive.value = false
        _scanAssignEntry.value = null
        _cardWriteTarget.value = null
    }

    /** Consumes the resolved scan target (the settings screen has opened the tag's profile page). */
    fun consumePendingTagProfile() {
        _pendingTagProfile.value = null
    }

    /**
     * Records a tag captured during a registration session, reading its
     * lumi payload (for the name/mode/songs form prefill) and merging the
     * card's per-uid memory (the last saved name + song list) as the
     * fallback prefill and restore source. Registration NEVER writes the
     * physical card - the explicit card-write action (the tag detail page's
     * "Save to NFC card") is the only write path.
     */
    internal fun capture(uid: String, tag: Tag? = null) {
        _capturedUid.value = uid
        if (tag == null) {
            _capturedTagData.value = null
            _capturedRestoreSongs.value = emptyList()
            return
        }
        viewModelScope.launch {
            // One NDEF session reads the payload.
            val data = withContext(Dispatchers.IO) {
                tagInspector(tag, LumiTagData(name = "", mode = playMode.value)).data
            }
            if (_capturedUid.value != uid) return@launch
            publishCapture(uid, data)
        }
    }

    /** Test seam: capture with an explicit payload (no live tag). */
    internal fun captureForTest(uid: String, data: LumiTagData?) {
        _capturedUid.value = uid
        viewModelScope.launch { publishCapture(uid, data) }
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
            data == null -> memory?.name?.takeIf { it.isNotBlank() }?.let { LumiTagData(it, playMode.value) }
            data.name.isBlank() && (memory?.name?.isNotBlank() == true) -> data.copy(name = memory.name)
            else -> data
        }
        _capturedRestoreSongs.value = data?.songs?.takeIf { it.isNotEmpty() } ?: (memory?.songs ?: emptyList())
    }

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

    fun discardCaptured() {
        _capturedUid.value = null
        _scanAssignEntry.value = null
        _capturedTagData.value = null
        _capturedRestoreSongs.value = emptyList()
    }

    /**
     * Saves the captured tag under [name]. The mode only records what the
     * tag was registered with (and prefills the form on re-read); it does
     * NOT change the app's play mode. Registration never writes the
     * physical card - the explicit card-write action is the only write
     * path - but it remembers the card's last saved name + song list (the
     * per-card memory that survives the tag row's deletion): when the card
     * comes back with an EMPTY tag (its tag was deleted and re-registered,
     * or a fresh registration of a card whose payload carries songs), the
     * capture's restore song list (the payload's songs, else the remembered
     * ones) is linked onto the tag name-for-name, with each name resolving
     * to the live content entry that plays it - a name the card payload's
     * 50-char name cap wrote as a prefix of a longer stored label resolves
     * to that label, and the link stores it in full - or staying a "song
     * not found" row when no content carries the name.
     */
    fun confirmTagName(name: String, mode: PlayMode) {
        val uid = _capturedUid.value ?: return
        val restoreSongs = _capturedRestoreSongs.value
        // A scan-to-assign's target song, if the capture came from one: it
        // is linked once this save settles (below).
        val assignEntry = _scanAssignEntry.value
        _capturedUid.value = null
        _capturedTagData.value = null
        _capturedRestoreSongs.value = emptyList()
        viewModelScope.launch {
            val saved = repository.addTag(uid, name)
            event(if (saved != null) "Tag added!" else "That tag is already registered.")
            val tag = saved ?: repository.tagByUid(uid)
            // The card's songs restored onto a tag that came back empty -
            // the card payload's list when it carried one, else the
            // remembered per-uid list. Names land in their carried order;
            // each resolves to the live content entry its name points at
            // (a name the payload's 50-char name cap wrote as a prefix
            // resolves to the longer stored label), and the link stores
            // that entry's full label - or the carried name, when nothing
            // resolves (the tag's "song not found" row).
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
            if (saved != null) _selectedTagId.value = saved.id
            // The scan-to-assign's song is assigned once its tag is saved -
            // the tag it was scanned against, looked up by uid when the tag
            // already existed.
            assignEntry?.let { entry ->
                _scanAssignEntry.value = null
                tag?.let { linkEntryToTag(it, entry) }
            }
        }
    }

    fun removeTag(tag: TagEntity) {
        viewModelScope.launch {
            repository.deleteTag(tag)
            if (_selectedTagId.value == tag.id) _selectedTagId.value = null
            event("Tag removed")
        }
    }

    /** Renames a registered tag (the tag detail page's edit dialog). */
    fun renameTag(tag: TagEntity, name: String) {
        viewModelScope.launch {
            repository.renameTag(tag.uid, name)
            event("Tag renamed")
        }
    }

    // ---- settings: entries ----

    /** Persist the SAF grant, then store the picked file as an entry. */
    fun addFileEntry(uri: Uri, type: EntryType, displayName: String?) {
        viewModelScope.launch {
            if (!persistReadGrant(uri)) return@launch
            val added = repository.addEntryUnique(type, displayName ?: "Untitled", uri.toString())
            event(
                if (added == null) {
                    "That song is already in your content list."
                } else if (type == EntryType.AUDIO) {
                    "Song added!"
                } else {
                    "Content added"
                },
            )
        }
    }

    /**
     * Adds a picked folder tree: persists the SAF grant on the tree, walks
     * it recursively (subfolders included), and stores every supported
     * audio file found as an AUDIO entry - skipping files whose content uri
     * is already stored, so re-adding a folder (or a folder whose songs were
     * added earlier) adds no duplicate rows. A folder without supported audio
     * adds nothing and reports so. The walk is guarded the way [rescanFolder]
     * guards its own: when the tree can no longer be read (e.g. the SAF grant
     * was revoked after the add) the add must report the failure, not crash
     * the app process over an uncaught provider exception - the folder row
     * is already stored by then, so the Rescan affordance stays available.
     */
    fun addFolderEntry(treeUri: Uri) {
        viewModelScope.launch {
            if (!persistReadGrant(treeUri)) return@launch
            // Remember the folder so it can be rescanned later for new songs.
            // The label query is a ContentResolver read, so keep it off the main thread.
            val label = withContext(Dispatchers.IO) { folderDisplayName(treeUri) }
            val folder = repository.rememberFolder(treeUri.toString(), label)
            val scanned = try {
                withContext(Dispatchers.IO) { scanFolderAudioFiles(treeUri) }
            } catch (e: Exception) {
                // The per-node attribution (which folder uri threw) is logged
                // by scanFolderAudioFiles' seam; this is the top-level name.
                Log.e(TAG, "Failed to read the songs of folder $treeUri", e)
                null
            }
            if (scanned == null) {
                event("Couldn't read that folder's songs.")
                return@launch
            }
            if (scanned.isEmpty()) {
                event("No songs found in that folder.")
                return@launch
            }
            storeFolderSongs(folder, scanned)
        }
    }

    /**
     * Stores a picked folder's scanned songs (the already stored ones are
     * skipped, so re-adding a folder adds no duplicates) and reports the
     * outcome.
     */
    private suspend fun storeFolderSongs(folder: FolderEntity, scanned: List<ScannedSong>) {
        val fresh = scanned.count {
            repository.addEntryUnique(EntryType.AUDIO, it.displayName, it.uri, folderId = folder.id) != null
        }
        event(
            when {
                fresh == 0 ->
                    if (scanned.size == 1) {
                        "The song from the folder is already in your content list."
                    } else {
                        "All ${scanned.size} songs from the folder are already in your content list."
                    }
                fresh < scanned.size -> "Added $fresh of ${scanned.size} songs from the folder."
                else -> "Added ${scanned.size} ${if (scanned.size == 1) "song" else "songs"} from the folder."
            },
        )
    }

    /**
     * Rescans a folder that was added earlier: re-walks the tree (every
     * subfolder included) and stores only the supported audio files added
     * since the last scan - files whose content uri is already stored are
     * skipped (uri/payload dedup), so a rescan of an unchanged folder adds
     * nothing. The folder's own read grant was persisted at add time, so the
     * tree is still readable; an unreadable folder simply rescans to nothing.
     */
    fun rescanFolder(folder: FolderEntity) {
        viewModelScope.launch {
            val stored = repository.storedEntryPayloads()
            val newSongs = try {
                withContext(Dispatchers.IO) {
                    rescanNewSongs(folder.uri, stored) { folderUri ->
                        folderChildrenAttributed(Uri.parse(folderUri))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rescan folder ${folder.uri}", e)
                emptyList()
            }
            newSongs.forEach {
                repository.addEntryUnique(EntryType.AUDIO, it.displayName, it.uri, folderId = folder.id)
            }
            event(
                when {
                    newSongs.isEmpty() -> "Nothing new in that folder."
                    newSongs.size == 1 -> "Added 1 new song from that folder."
                    else -> "Added ${newSongs.size} new songs from that folder."
                },
            )
            // The folder page (if it is open on this folder) shows the new
            // songs.
            if (selectedFolder?.id == folder.id) refreshFolderSongs()
        }
    }

    // ---- settings: folder removal ----

    /**
     * A folder awaiting its removal confirmation: the payloads of the songs
     * imported from it (identified by re-walking the tree - the rescan
     * precedent, minus the new-only filter - so the removal matches exactly
     * what the add stored) plus how many of those are still stored, or
     * nulls when the tree can no longer be read and the song count is
     * undeterminable - then only the folder row itself can be removed.
     */
    data class FolderRemovalPreview(
        val folder: FolderEntity,
        val importedPayloads: Set<String>?,
        val songCount: Int?,
    )

    private val _folderRemovalPreview = MutableStateFlow<FolderRemovalPreview?>(null)
    val folderRemovalPreview: StateFlow<FolderRemovalPreview?> = _folderRemovalPreview.asStateFlow()

    private var removalJob: Job? = null

    /**
     * Prepares the removal confirmation for [folder]: re-walks its tree to
     * identify the imported songs (the same walk the add used), then opens
     * the confirmation with the song count - or with a plain "the tree can
     * no longer be read" notice when the walk fails. The dialog is
     * state-driven ([folderRemovalPreview]), so the async walk settles
     * before the confirmation appears. A request made while a walk is in
     * flight or the preview is open is ignored; cancel or confirm clears
     * the preview before a new removal can be requested.
     */
    fun requestFolderRemoval(folder: FolderEntity) {
        if (_folderRemovalPreview.value != null || removalJob?.isActive == true) return
        removalJob = viewModelScope.launch {
            val walked: List<ScannedSong>? =
                walkFolderTree("Failed to read the songs of folder ${folder.uri} for removal", folder.uri)
            _folderRemovalPreview.value =
                if (walked == null) {
                    FolderRemovalPreview(folder, null, null)
                } else {
                    val payloads = walked.map { it.uri }.toSet()
                    FolderRemovalPreview(folder, payloads, repository.countEntriesByPayloads(payloads))
                }
        }
    }

    /**
     * Confirms a folder removal prepared by [requestFolderRemoval]: the
     * folder row and the imported songs (the stored rows the walk
     * identified) leave the app in one transaction. When the tree could not
     * be read, only the folder row is removed - the songs that can no longer
     * be identified stay. No grants are released: folder-imported songs
     * never held per-entry grants (only the tree's own grant was persisted
     * at add time), and the data model tracks none per entry.
     */
    fun confirmFolderRemoval() {
        val preview = _folderRemovalPreview.value ?: return
        _folderRemovalPreview.value = null
        viewModelScope.launch {
            val removed = repository.removeFolder(preview.folder, preview.importedPayloads.orEmpty())
            event(
                when {
                    preview.importedPayloads == null ->
                        "Removed the folder. Its songs couldn't be read, so they were left in place."
                    removed == 0 -> "Removed the folder."
                    else -> "Removed the folder and $removed imported song${if (removed == 1) "" else "s"}."
                },
            )
        }
    }

    fun cancelFolderRemoval() {
        _folderRemovalPreview.value = null
    }

    // ---- settings: the folder detail page ----

    /**
     * The folder detail page's song list: the stored content rows the
     * folder's walk identified (the same walk the rescan and the removal
     * use), or nothing + unreadable when the tree can no longer be read.
     * Null while the walk is in flight.
     */
    data class FolderSongsState(
        val entries: List<EntryEntity>,
        val readable: Boolean = true,
    )

    private val _folderSongs = MutableStateFlow<FolderSongsState?>(null)
    val folderSongs: StateFlow<FolderSongsState?> = _folderSongs.asStateFlow()

    private var selectedFolder: FolderEntity? = null
    private var folderSongsJob: Job? = null

    /**
     * Recomputes the folder detail page's song list for [folder]: the walk
     * (the same one the rescan and the removal use) identifies the imported
     * payloads, and the stored rows that match become the page's song list.
     * Re-selecting cancels any in-flight walk; a walk that fails (the tree
     * can no longer be read) settles to the unreadable state.
     */
    fun selectFolder(folder: FolderEntity) {
        selectedFolder = folder
        refreshFolderSongs()
    }

    /** Recomputes the current folder page's song list (a rescan's new rows need it). */
    private fun refreshFolderSongs() {
        val folder = selectedFolder ?: return
        folderSongsJob?.cancel()
        _folderSongs.value = null
        folderSongsJob = viewModelScope.launch {
            val payloads: Set<String>? =
                walkFolderTree("Failed to read the songs of folder ${folder.uri} for the folder page", folder.uri)
                    ?.let { songs -> songs.map { it.uri }.toSet() }
            // The folder (or the page) may have changed while the walk ran.
            if (selectedFolder?.id != folder.id) return@launch
            _folderSongs.value = payloads?.let {
                FolderSongsState(entries = repository.entriesByPayloads(it))
            } ?: FolderSongsState(entries = emptyList(), readable = false)
        }
    }

    /**
     * The label for a picked folder: the `_display_name` of the folder's own
     * row, read at the folder's own tree-scoped document uri (see the
     * [folderChildren] seam's grammar note - the tree uri itself is NOT a
     * listing uri, so the label is a single-row document query, not a
     * listing read - and the treeless document uri is not authorized under
     * the tree grant). The provider may still answer empty (or throw, e.g.
     * on a revoked grant), so the read is guarded and any provider failure
     * falls back to the uri string instead of aborting the add.
     */
    private fun folderDisplayName(treeUri: Uri): String =
        try {
            context.contentResolver
                .query(
                    Uri.parse(documentUri(treeUri, treeIdOf(treeUri), folderDocIdOf(treeUri))),
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null,
                    null,
                    null,
                )
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                }
                ?.ifEmpty { null }
                ?: treeUri.toString()
        } catch (_: Exception) {
            treeUri.toString()
        }

    /**
     * Test seam: the documents provider's folder listing (a folder uri to
     * its direct children), replaced in unit tests with a fake tree.
     *
     * The SAF picker persists a grant on the picked tree uri
     * (`tree/<treeId>`), and the framework's permission check authorizes
     * ONLY the tree-scoped shapes under that grant: every treeless
     * `document/<docId>(/children)` query against the tree's provider is
     * rejected with `SecurityException: Permission Denial ... requires
     * that you obtain access using ACTION_OPEN_DOCUMENT` (device-proven on
     * Android 14 - that denial is what made the pre-fix treeless seam
     * import zero songs for EVERY folder and surface the generic "Couldn't
     * read that folder's songs."). So this seam lists every folder - the
     * tree's root and each subfolder alike - at the tree-scoped
     * `tree/<treeId>/document/<docId>/children` under the grant's own
     * treeId, and a folder's node uri is its own tree-scoped document uri
     * `tree/<treeId>/document/<docId>` - never the bare tree uri (the
     * provider's matcher has no entry for a bare tree uri) and never a
     * treeless
     * `document/` uri (the permission layer denies it under the tree
     * grant).
     *
     * A picked tree's docIds contain `/` and `:` (e.g.
     * `primary:Music/Album/song.mp3`), so every uri this seam builds carries
     * the treeId and the docId each percent-encoded into ONE path segment
     * (`Uri.encode(id, null)` + `encodedPath`), and a stored payload is
     * openable under the tree grant alone (device-proven: the treeless
     * `document/<docId>` payload fails with a SecurityException under the
     * same grant, the tree-scoped one opens).
     *
     * The provider's cursor carries the DocumentsContract columns
     * `document_id`, `_display_name` and `mime_type` - the constants
     * [DocumentsContract.Document.COLUMN_DOCUMENT_ID],
     * [DocumentsContract.Document.COLUMN_DISPLAY_NAME] and
     * [DocumentsContract.Document.COLUMN_MIME_TYPE] (NOT the `_id`/
     * `_mime_type` names the treeless seam read - the real provider's
     * cursor has no such columns, so that read crashed on the first row -
     * device-proven), and rows are directories when their `mime_type` is
     * `vnd.android.document/directory` ([DocumentsContract.Document.MIME_TYPE_DIR]).
     *
     * The tree uri's docId is its second path segment; a trailing
     * `:openable` picker suffix on that segment (android.net.Uri has no
     * `:`-fragment parsing, so it rides in the path) is stripped before the
     * query - the provider answers only the bare docId. The strip applies
     * to the picker's tree uri ONLY: the tree-scoped node uris this seam
     * builds never carry it, and a raw docId may legitimately end in
     * `:openable` (legal on ext4), so a subfolder's docId is used verbatim.
     * The folders table stores the picker uri verbatim (normalization is at
     * query time only).
     */
    internal var folderChildren: (Uri) -> List<FolderChild> = { folderUri ->
        val treeId = treeIdOf(folderUri)
        val docId = folderDocIdOf(folderUri)
        context.contentResolver
            .query(
                listChildrenUri(folderUri, treeId, docId),
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )
            ?.use { cursor ->
                val docIdColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                buildList {
                    while (cursor.moveToNext()) {
                        val rawDocId = cursor.getString(docIdColumn) ?: continue
                        // The child's own tree-scoped document uri is the
                        // walk node for a subfolder AND the stored payload
                        // for a file - one uri shape for both, openable
                        // under the tree grant alone.
                        add(
                            FolderChild(
                                displayName = cursor.getString(nameColumn) ?: "",
                                isDirectory = cursor.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR,
                                childUri = documentUri(folderUri, treeId, rawDocId),
                            ),
                        )
                    }
                }
            }
            ?: emptyList()
    }

    /**
     * The grant's own treeId: the picker's tree uri carries it as its
     * second path segment (a trailing `:openable` picker suffix on that
     * segment is stripped - the provider answers only the bare docId), and
     * every tree-scoped node uri this seam builds carries the same
     * (already-stripped) treeId as its second segment, verbatim.
     */
    private fun treeIdOf(folderUri: Uri): String {
        val segments = folderUri.pathSegments
        if (segments.firstOrNull() != "tree") return ""
        val treeId = segments.getOrNull(1) ?: return ""
        return if (segments.size == 2) treeId.removeSuffix(":openable") else treeId
    }

    /** A folder's children listing uri `scheme://authority/tree/<treeId>/document/<docId>/children`, the treeId and the docId each percent-encoded into a single path segment. */
    private fun listChildrenUri(folderUri: Uri, treeId: String, docId: String): Uri =
        Uri.Builder()
            .scheme(folderUri.scheme)
            .authority(folderUri.authority)
            .encodedPath("/tree/${Uri.encode(treeId, null)}/document/${Uri.encode(docId, null)}/children")
            .build()

    /** A child's own uri `scheme://authority/tree/<treeId>/document/<docId>` built from [folderUri]'s scheme + authority, the treeId and the docId each percent-encoded into a single path segment. It is the walk node for a subfolder AND the stored payload for a file - openable under the tree grant alone. */
    private fun documentUri(folderUri: Uri, treeId: String, docId: String): String =
        Uri.Builder()
            .scheme(folderUri.scheme)
            .authority(folderUri.authority)
            .encodedPath("/tree/${Uri.encode(treeId, null)}/document/${Uri.encode(docId, null)}")
            .build()
            .toString()

    /**
     * The [folderChildren] seam with per-node failure attribution: a query
     * that throws names the folder uri that failed and the exception, so a
     * scan or rescan failure is diagnosable from the log instead of a bare
     * generic event. The exception is rethrown unchanged.
     */
    private fun folderChildrenAttributed(folderUri: Uri): List<FolderChild> =
        try {
            folderChildren(folderUri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list the children of $folderUri", e)
            throw e
        }

    /** Walks [treeUri] through the attributed [folderChildren] seam, keeping the supported audio only. */
    private fun scanFolderAudioFiles(treeUri: Uri): List<ScannedSong> =
        collectAudioFiles(treeUri.toString()) { folderUri ->
            folderChildrenAttributed(Uri.parse(folderUri))
        }

    /**
     * The guarded tree walk behind the folder reads: the full
     * [collectAudioFiles] walk through the attributed seam, settling to
     * null (and logging [what] with the exception) when the tree can no
     * longer be read - a revoked grant makes the provider query throw.
     */
    private suspend fun walkFolderTree(what: String, folderUri: String): List<ScannedSong>? =
        try {
            withContext(Dispatchers.IO) {
                collectAudioFiles(folderUri) { childUri ->
                    folderChildrenAttributed(Uri.parse(childUri))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, what, e)
            null
        }

    /**
     * The v3 -> v4 backfill (see [com.lumi.player.data.LumiDatabase.MIGRATION_3_4]):
     * content rows stored before the folder marker existed are stamped with
     * the folder that imported them, re-walking each remembered folder with
     * the same tree walk the folder page uses. Best-effort by design: a
     * folder whose tree can no longer be read (a revoked grant) is logged
     * and skipped, so its songs keep no marker and appear in the individual
     * content list; a file deleted from the tree since the add is the same
     * (its row keeps no marker). Rows already stamped (by an add after the
     * marker existed) are never re-stamped.
     */
    internal suspend fun backfillFolderEntries() {
        val folders = repository.observeFolders().first()
        if (folders.isEmpty()) return
        folders.forEach { folder ->
            val payloads: Set<String>? =
                walkFolderTree("Failed to backfill the songs of folder ${folder.uri}", folder.uri)
                    ?.let { songs -> songs.map { it.uri }.toSet() }
            if (!payloads.isNullOrEmpty()) {
                repository.markEntriesFromFolder(folder.id, payloads)
            }
        }
    }

    /**
     * Links an already-stored content entry to the end of the tag's song
     * list - the in-app add-song flow's single-song path: a tag's list is
     * an assignment over the app's existing content (individual or
     * folder-imported alike), so the pick links the song's stored row
     * instead of storing a new one (no second grant, no duplicate content
     * row). When the tag already plays the song (its list carries an entry
     * with the same label) the link is reported instead of silently
     * duplicating. [linkEntriesToTag] runs this same one-song core in
     * order, so a bulk save links each checked song through the very same
     * path.
     */
    fun linkEntryToTag(tag: TagEntity, entry: EntryEntity) {
        viewModelScope.launch { linkOneEntryToTag(tag, entry) }
    }

    /**
     * Links several already-stored content entries to a tag in order - the
     * add-song dialog's Save committing its checked selection: every song
     * links through the existing single-song link path (one link per
     * song, each appended after the previous), but each write completes
     * before the next starts, so the appends land in the selection's
     * order - fire-and-forget siblings would race on the link-list count
     * and the songs could land out of the order they were checked in.
     */
    fun linkEntriesToTag(tag: TagEntity, entries: List<EntryEntity>) {
        if (entries.isEmpty()) return
        viewModelScope.launch {
            entries.forEach { linkOneEntryToTag(tag, it) }
        }
    }

    /** The one-song link (blocking: its write commits before returning). */
    private suspend fun linkOneEntryToTag(tag: TagEntity, entry: EntryEntity) {
        // The guard matches on the EXISTING songs' names (a tag song is
        // identified by name): a second pick of the same song - or of a
        // duplicate-named row - must not duplicate the link. A "song not
        // found" row carries the deleted song's snapshot name, so re-adding
        // a re-imported song with that name is a refusal, not a duplicate:
        // the tag already shows that song (as the missing row), and the
        // re-imported entry plays it (name-based resolution) - removing the
        // missing row first is what re-links the new entry.
        if (repository.entriesForTag(tag.id).any { it.name == entry.label }) {
            event("That song is already on the tag.")
            return
        }
        val linked = repository.linkEntryToTag(tag.id, entry.id)
        event(if (linked) "Song added to the tag" else "Could not add the song to that tag.")
    }

    /**
     * Removes one song from one tag - the inverse of [linkEntryToTag]: the
     * tag's LINK to [row] goes, and nothing else. For a song that still
     * exists the song stays in the content list (its folder membership
     * untouched), its links to any other tags are untouched, and the
     * remaining songs keep their persisted order (the play order of what
     * stays is unchanged). For a song that has ALREADY been
     * deleted from the library (the tag's "song not found" row) the dead
     * link itself goes: the missing row leaves the list.
     */
    fun unlinkSongFromTag(tag: TagEntity, row: TagSongRow) {
        viewModelScope.launch {
            val removed = repository.unlinkLinkFromTag(tag.id, row.linkId)
            event(if (removed) "Song removed from the tag" else "Could not remove the song from that tag.")
        }
    }

    /**
     * Reorders the tag's song list - the tag detail page's drag reorder:
     * the persisted link order is replaced with [ordered] through the same
     * transactional seam the add-song flow appends through, so the play
     * order (the link order) follows the reordered order. [ordered] is the
     * page's full row list (rows whose song is missing among them), so a
     * persisted reorder keeps every link, missing songs at their new
     * positions included. A list that is
     * unchanged (a drag that ends where it started) writes nothing.
     */
    fun reorderTagSongs(tag: TagEntity, ordered: List<TagSongRow>) {
        viewModelScope.launch {
            // The link's OWN id is the stable identity of a row (the entry
            // reference is only a provenance hint - a re-imported entry
            // changes nothing about the row), so the reorder addresses links
            // by their stored ids.
            val ids = ordered.map { it.linkId }
            if (repository.entriesForTag(tag.id).map { it.linkId } == ids) return@launch
            var accepted = false
            repository.updateTagEntries(tag.id) { current ->
                // The list may have changed between the drag and the write
                // (a song added or removed in flight); only a permutation of
                // the current set is a reorder - anything else keeps the
                // current order.
                if (current.toSet() == ids.toSet()) {
                    accepted = true
                    ids
                } else current
            }
            if (accepted) event("Order saved.")
        }
    }

    /** Keeps the read grant on [uri] forever; false when it cannot. */
    private suspend fun persistReadGrant(uri: Uri): Boolean =
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            true
        } catch (e: Exception) {
            event("Could not keep access to that file.")
            false
        }

    fun removeEntry(entry: EntryEntity) {
        viewModelScope.launch {
            repository.deleteEntry(entry)
            event("Removed ${entry.label}")
        }
    }

    // ---- helpers ----

    private fun event(message: String) {
        _uiEvents.tryEmit(message)
    }

    private fun EntryEntity.toSpec(): EntrySpec =
        EntrySpec(type = type, label = label, payload = payload)
}

/** Factory: the app instance supplies the repository. */
fun lumiViewModelFactory(app: LumiApp): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        LumiViewModel(app, app.repository) as T
}

/** Log tag: folder read failures are attributed here (the failing uri + the exception). */
private const val TAG = "LumiViewModel"
