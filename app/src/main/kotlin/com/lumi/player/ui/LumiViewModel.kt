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
import com.lumi.player.data.FolderChild
import com.lumi.player.data.FolderEntity
import com.lumi.player.data.LumiRepository
import com.lumi.player.data.ScannedSong
import com.lumi.player.data.collectAudioFiles
import com.lumi.player.data.decideRestore
import com.lumi.player.data.fingerprintOf
import com.lumi.player.data.rescanNewSongs
import com.lumi.player.nfc.LumiTagData
import com.lumi.player.nfc.LumiTagNdef
import com.lumi.player.nfc.TagInspect
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

    // ---- NFC registration flow ----

    // ---- tag scan: the "Scan NFC" action of the NFC tags pane ----

    /**
     * Whether a tag scan is in progress: the next tag read settles it - a
     * registered tag resolves to its profile page, an unregistered one
     * enters the registration flow (capture + naming dialog, with the
     * optional write-to-tag).
     */
    private val _scanActive = MutableStateFlow(false)
    val scanActive: StateFlow<Boolean> = _scanActive.asStateFlow()

    /**
     * The registered tag a settled scan resolved to; consumed by the
     * settings screen (it opens the tag's profile page).
     */
    private val _pendingTagProfile = MutableStateFlow<TagEntity?>(null)
    val pendingTagProfile: StateFlow<TagEntity?> = _pendingTagProfile.asStateFlow()

    private val _capturedUid = MutableStateFlow<String?>(null)
    val capturedUid: StateFlow<String?> = _capturedUid.asStateFlow()

    /** The payload the captured tag carried (name + mode), if it had one. */
    private val _capturedTagData = MutableStateFlow<LumiTagData?>(null)
    val capturedTagData: StateFlow<LumiTagData?> = _capturedTagData.asStateFlow()

    /** Whether the captured tag is writable and fits the tag payload. */
    private val _capturedTagWritable = MutableStateFlow(false)
    val capturedTagWritable: StateFlow<Boolean> = _capturedTagWritable.asStateFlow()

    // The raw NFC tag of the last registration capture, kept so the payload
    // can be written to it after the user confirms the form. Writing must
    // happen while the app is in the foreground and the tag still in range
    // (the Android 10+ NFC write constraint); a tag that left range simply
    // fails the write and the tag is still saved.
    private var capturedTag: Tag? = null

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
    val selectedTagId: StateFlow<Long?> = _selectedTagId.asStateFlow()

    fun selectTag(id: Long?) {
        _selectedTagId.value = id
    }

    val effectiveSelectedTagId: StateFlow<Long?> =
        combine(_selectedTagId, repository.observeTags()) { selected, tags ->
            selected ?: tags.firstOrNull()?.id
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val linkedEntries: StateFlow<List<EntryEntity>> =
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
     * [tag] is the raw NFC tag; during registration it is kept (for a
     * later write) and its lumi payload read for form prefill.
     */
    fun onTagDetected(uid: String, tag: Tag? = null) {
        if (_scanActive.value) {
            // A tag scan was in progress (the NFC tags pane's "Scan NFC"
            // action): settle it. A registered tag resolves to its profile
            // page (the settings screen consumes [pendingTagProfile] and
            // opens the tag detail page); an unknown one goes into the
            // registration flow (capture + naming dialog, with the
            // optional write-to-tag). Either way the scan is over, so
            // further reads are normal playback reads.
            _scanActive.value = false
            viewModelScope.launch {
                val existing = repository.tagByUid(uid)
                if (existing != null) {
                    _pendingTagProfile.value = existing
                } else {
                    capture(uid, tag)
                }
            }
            return
        }
        val paused = PlayerStateHolder.state.value.status == PlaybackStatus.PAUSED
        if (policy.activeTagUid == uid && !paused) return
        viewModelScope.launch {
            val tag = repository.tagByUid(uid) ?: return@launch
            val entries = repository.entriesForTag(tag.id)
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
            if (uid != null) {
                when (val restore = decideRestore(repository.positionFor(uid), fingerprintOf(specs), specs.size)) {
                    is PositionRestore.StartFresh -> Unit
                    is PositionRestore.SeekTo -> {
                        putExtra(PlayerService.EXTRA_START_ENTRY_INDEX, restore.entryIndex)
                        putExtra(PlayerService.EXTRA_START_POSITION_MS, restore.positionMs)
                    }
                }
            }
        }
        ContextCompat.startForegroundService(context, intent)
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
     * the individual content follow it). Descending is the default - the
     * list shows Z->A until the user picks otherwise; the pick persists in
     * the database.
     */
    val contentSortDirection: StateFlow<ContentSortDirection> =
        repository.observeContentSortDirection()
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                ContentSortDirection.DESCENDING,
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
     * Plays every song of a folder in the order the rows were stored (the
     * folder row's play button): the folder's stored content rows become
     * the queue - no tree walk needed, the stored rows are the folder's
     * songs.
     */
    fun playFolder(folder: FolderEntity) {
        viewModelScope.launch {
            val songs = repository.entriesForFolder(folder.id)
            if (songs.isEmpty()) {
                event("That folder has no songs to play.")
                return@launch
            }
            startPlayer(songs.map { it.toSpec() }, uid = null)
        }
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
            val metadata: SongMetadata? = try {
                withTimeout(METADATA_READ_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { metadataReader(Uri.parse(entry.payload)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read the metadata of ${entry.label}", e)
                null
            }
            if (selectedEntryId == entry.id) _songMetadata.value = metadata
        }
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
     * dialog, optional write-to-tag). Reuses the app's only read path -
     * the foreground-dispatch read into [onTagDetected] - so only the
     * flag checked there differs.
     */
    fun startTagScan() {
        _capturedUid.value = null
        _scanActive.value = true
    }

    fun cancelTagScan() {
        _scanActive.value = false
    }

    /** Consumes the resolved scan target (the settings screen has opened the tag's profile page). */
    fun consumePendingTagProfile() {
        _pendingTagProfile.value = null
    }

    /**
     * Records a tag captured during a registration session, reading its
     * lumi payload (for form prefill) and its writability (for the optional
     * "write to tag" step).
     */
    internal fun capture(uid: String, tag: Tag? = null) {
        _capturedUid.value = uid
        capturedTag = tag
        if (tag == null) {
            _capturedTagData.value = null
            _capturedTagWritable.value = false
            return
        }
        viewModelScope.launch {
            // One NDEF session reads the payload and the writability.
            val inspected = withContext(Dispatchers.IO) {
                tagInspector(tag, LumiTagData(name = "", mode = playMode.value))
            }
            if (_capturedUid.value != uid) return@launch
            _capturedTagData.value = inspected.data
            _capturedTagWritable.value = inspected.writable
        }
    }

    /** Test seam: capture with explicit payload/writability (no live tag). */
    internal fun captureForTest(uid: String, data: LumiTagData?, writable: Boolean) {
        _capturedUid.value = uid
        capturedTag = null
        _capturedTagData.value = data
        _capturedTagWritable.value = writable
    }

    /** Test seam: the physical tag write, replaced in unit tests. */
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
        _capturedTagData.value = null
        _capturedTagWritable.value = false
        capturedTag = null
    }

    /**
     * Saves the captured tag under [name] and - when [writeToTag] and the
     * tag is still reachable - writes the name + mode back to the tag so
     * re-registering it later prefills the form. The mode only records what
     * the tag was registered with (and prefills the form on re-read); it
     * does NOT change the app's play mode. When the tag is already
     * registered and the write lands, the stored record's name is updated
     * to follow the written tag payload.
     */
    fun confirmTagName(name: String, mode: PlayMode, writeToTag: Boolean) {
        val uid = _capturedUid.value ?: return
        val tag = capturedTag
        _capturedUid.value = null
        _capturedTagData.value = null
        _capturedTagWritable.value = false
        capturedTag = null
        viewModelScope.launch {
            val saved = repository.addTag(uid, name)
            event(if (saved != null) "Tag added!" else "That tag is already registered.")
            if (saved != null) _selectedTagId.value = saved.id
            if (writeToTag) {
                // The DB stores the name trimmed; write it trimmed to the
                // tag as well so the two can never diverge.
                val written = withContext(Dispatchers.IO) {
                    tagWriter(tag, LumiTagData(name.trim(), mode))
                }
                if (written) {
                    if (saved == null) repository.renameTag(uid, name)
                    event("Name written to the tag.")
                } else {
                    event("Couldn't write to the tag - keep it on the phone and try again.")
                }
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
            val walked: List<ScannedSong>? = try {
                withContext(Dispatchers.IO) {
                    collectAudioFiles(folder.uri) { folderUri ->
                        folderChildrenAttributed(Uri.parse(folderUri))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read the songs of folder ${folder.uri} for removal", e)
                null
            }
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
            val payloads: Set<String>? = try {
                withContext(Dispatchers.IO) {
                    collectAudioFiles(folder.uri) { folderUri ->
                        folderChildrenAttributed(Uri.parse(folderUri))
                    }.map { it.uri }.toSet()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read the songs of folder ${folder.uri} for the folder page", e)
                null
            }
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

    /**
     * The docId of the folder a listing is asked for: the picker's tree
     * uri carries it as its second path segment (a trailing `:openable`
     * picker suffix on that segment is stripped - the provider answers
     * only the bare docId), and a tree-scoped node uri
     * (`tree/<treeId>/document/<docId>`) carries it as its fourth path
     * segment, verbatim (the percent-encoding is already undone by
     * [Uri.pathSegments]).
     *
     * The `:openable` strip applies to the picker's tree uri ONLY - the
     * picker appends it to the tree uri's docId segment and to nothing
     * else. The tree-scoped node uris this seam builds never carry it, so
     * a subfolder's docId is used verbatim: a raw docId may legitimately
     * end in `:openable` (legal on ext4), and stripping it there would
     * corrupt the children listing and lose a fully readable folder.
     */
    private fun folderDocIdOf(folderUri: Uri): String {
        val segments = folderUri.pathSegments
        return when {
            // A tree-scoped node uri: the docId is the fourth segment, verbatim.
            segments.size == 4 && segments.getOrNull(0) == "tree" && segments.getOrNull(2) == "document" ->
                segments.getOrNull(3).orEmpty()
            // The picker's tree uri: the docId is the second segment, with the
            // optional `:openable` picker suffix stripped.
            segments.getOrNull(0) == "tree" -> segments.getOrNull(1)?.removeSuffix(":openable").orEmpty()
            // The walk never builds any other uri; kept verbatim for the
            // tests' synthetic tree uris.
            else -> segments.getOrNull(1).orEmpty()
        }
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
            val payloads: Set<String>? = try {
                withContext(Dispatchers.IO) {
                    collectAudioFiles(folder.uri) { folderUri ->
                        folderChildrenAttributed(Uri.parse(folderUri))
                    }.map { it.uri }.toSet()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to backfill the songs of folder ${folder.uri}", e)
                null
            }
            if (!payloads.isNullOrEmpty()) {
                repository.markEntriesFromFolder(folder.id, payloads)
            }
        }
    }

    /**
     * Links an already-stored content entry to the end of the tag's song
     * list - the in-app add-song flow: a tag's list is an assignment over
     * the app's existing content (individual or folder-imported alike), so
     * the pick links the stored row instead of storing a new one. When the
     * tag already plays the song (its list carries an entry with the same
     * label) the pick is reported instead of silently duplicating the
     * link.
     */
    fun linkEntryToTag(tag: TagEntity, entry: EntryEntity) {
        viewModelScope.launch {
            if (repository.entriesForTag(tag.id).any { it.label == entry.label }) {
                event("That song is already on the tag.")
                return@launch
            }
            val linked = repository.linkEntryToTag(tag.id, entry.id)
            event(if (linked) "Song added to the tag" else "Could not add the song to that tag.")
        }
    }

    /**
     * Removes one song from one tag - the inverse of [linkEntryToTag]: the
     * tag's link to [entry] goes, and nothing else - the song stays in the
     * content list (its folder membership untouched), its links to any
     * other tags are untouched, and the remaining songs keep their
     * persisted order (the play order of what stays is unchanged).
     */
    fun unlinkEntryFromTag(tag: TagEntity, entry: EntryEntity) {
        viewModelScope.launch {
            val removed = repository.unlinkEntryFromTag(tag.id, entry.id)
            event(if (removed) "Song removed from the tag" else "Could not remove the song from that tag.")
        }
    }

    /**
     * Reorders the tag's song list - the tag detail page's drag reorder:
     * the persisted link order is replaced with [ordered] through the same
     * transactional seam the add-song flow appends through, so the play
     * order (the link order) follows the reordered order. A list that is
     * unchanged (a drag that ends where it started) writes nothing.
     */
    fun reorderTagSongs(tag: TagEntity, ordered: List<EntryEntity>) {
        viewModelScope.launch {
            val ids = ordered.map { it.id }
            if (repository.entriesForTag(tag.id).map { it.id } == ids) return@launch
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
