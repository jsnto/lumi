package com.lumi.player.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.Tag
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import com.lumi.player.data.EntryEntity
import com.lumi.player.data.PlayMode
import com.lumi.player.data.PositionRestore
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // ---- NFC registration flow ----

    private val _registering = MutableStateFlow(false)
    val registering: StateFlow<Boolean> = _registering.asStateFlow()

    // ---- edit scan: the "Scan NFC" row of the NFC tags pane ----

    /** Whether an edit scan (a scan aimed at an EXISTING tag's details) is in progress. */
    private val _editScanActive = MutableStateFlow(false)
    val editScanActive: StateFlow<Boolean> = _editScanActive.asStateFlow()

    /** The tag a settled edit scan resolved to; consumed by the settings screen (it opens the edit dialog). */
    private val _pendingEditTag = MutableStateFlow<TagEntity?>(null)
    val pendingEditTag: StateFlow<TagEntity?> = _pendingEditTag.asStateFlow()

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
    }

    // ---- NFC events from the activity ----

    /**
     * A tag was detected (a foreground-dispatch read in either play mode
     * or a tap delivered by intent on cold start, a registration capture,
     * or an edit-scan read).
     * [tag] is the raw NFC tag; during registration it is kept (for a
     * later write) and its lumi payload read for form prefill.
     */
    fun onTagDetected(uid: String, tag: Tag? = null) {
        if (_editScanActive.value) {
            // An edit scan was in progress: settle it. A registered tag
            // resolves to its edit flow (the settings screen consumes
            // [pendingEditTag] and opens the edit dialog); an unknown one
            // reports without starting a registration. Either way the scan
            // is over, so further reads are normal playback reads.
            _editScanActive.value = false
            viewModelScope.launch {
                val existing = repository.tagByUid(uid)
                if (existing != null) {
                    _pendingEditTag.value = existing
                } else {
                    event("That tag isn't registered yet.")
                }
            }
            return
        }
        if (_registering.value) {
            // We were waiting to register a tag: capture it.
            _registering.value = false
            capture(uid, tag)
            return
        }
        val paused = PlayerStateHolder.state.value.status == PlaybackStatus.PAUSED
        if (policy.activeTagUid == uid && !paused) return
        viewModelScope.launch {
            val tag = repository.tagByUid(uid) ?: return@launch
            when (policy.onTagDetected(uid, paused)) {
                NfcAction.StartPlayback -> {
                    // This read made [uid] the active tag (start or switch);
                    // let the activity seed its presence record with it.
                    _tagActivated.tryEmit(uid)
                    val entries = repository.entriesForTag(tag.id)
                    if (entries.isEmpty()) {
                        event("Nothing is linked to ${tag.name.ifEmpty { "this tag" }} yet.")
                    } else {
                        startPlayer(entries.map { it.toSpec() }, uid)
                    }
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
     * to the service so playback resumes where it stopped.
     */
    private suspend fun startPlayer(specs: List<EntrySpec>, uid: String) {
        val intent = Intent(context, PlayerService::class.java).apply {
            action = PlayerService.ACTION_START
            putExtra(PlayerService.EXTRA_ENTRIES, ArrayList(specs))
            putExtra(PlayerService.EXTRA_TAG_UID, uid)
            when (val restore = decideRestore(repository.positionFor(uid), fingerprintOf(specs), specs.size)) {
                is PositionRestore.StartFresh -> Unit
                is PositionRestore.SeekTo -> {
                    putExtra(PlayerService.EXTRA_START_ENTRY_INDEX, restore.entryIndex)
                    putExtra(PlayerService.EXTRA_START_POSITION_MS, restore.positionMs)
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

    // ---- settings: tags ----

    fun startTagRegistration() {
        _capturedUid.value = null
        _editScanActive.value = false
        _registering.value = true
    }

    fun cancelTagRegistration() {
        _registering.value = false
    }

    /**
     * Starts an edit scan (the NFC tags pane's "Scan NFC" row): the next
     * tag read resolves to a registered tag's edit flow instead of driving
     * playback or a registration capture. Reuses the read path registration
     * already uses - the foreground-dispatch read into [onTagDetected] - so
     * only the flag checked there differs. Starting here cancels a
     * registration scan in flight.
     */
    fun startTagEditScan() {
        _registering.value = false
        _editScanActive.value = true
    }

    fun cancelTagEditScan() {
        _editScanActive.value = false
    }

    /** Consumes the resolved edit-scan target (the settings screen has opened the edit dialog). */
    fun consumePendingEditTag() {
        _pendingEditTag.value = null
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
            repository.rememberFolder(treeUri.toString(), label)
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
                repository.addEntryUnique(EntryType.AUDIO, it.displayName, it.uri) != null
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
                repository.addEntryUnique(EntryType.AUDIO, it.displayName, it.uri)
            }
            event(
                when {
                    newSongs.isEmpty() -> "Nothing new in that folder."
                    newSongs.size == 1 -> "Added 1 new song from that folder."
                    else -> "Added ${newSongs.size} new songs from that folder."
                },
            )
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

    /**
     * The label for a picked folder: the first `_display_name` row of the
     * tree query, or the uri itself when no name can be read. On the real
     * documents provider a `:tree` listing includes the tree's own root row
     * first, so the label is the folder's own name (a provider that lists no
     * root row yields the first child's name instead - cosmetic). Querying
     * a SAF tree uri lists the root's children, so an empty root (no direct
     * children) yields a zero-row cursor - reading it unguarded threw
     * (CursorIndexOutOfBoundsException) and crashed the add, so the read is
     * guarded and any provider failure falls back to the uri string instead
     * of aborting the add.
     */
    private fun folderDisplayName(treeUri: Uri): String =
        try {
            context.contentResolver
                .query(canonicalTreeUri(treeUri), arrayOf("_display_name"), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    cursor.getString(cursor.getColumnIndexOrThrow("_display_name"))
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
     * The documents provider's uri grammar is strict, and a picked tree's
     * docIds contain `/` and `:` (e.g. `primary:Music/Album/song.mp3`), so
     * every uri this seam builds must carry the docId percent-encoded into
     * ONE path segment:
     * - a file's document uri is `scheme://authority/document/<docId>` with
     *   exactly two path segments - a raw docId splits the path into extra
     *   segments, the provider's uri parse throws
     *   (`IllegalArgumentException: Invalid document id`), and a stored
     *   payload would be a uri the player cannot open;
     * - a subfolder's children are listed at the tree's CHILD uri
     *   `scheme://authority/child/<treeSeg>:tree/<docId>`, never at the
     *   subfolder's document uri (which answers with the folder's own single
     *   row, not its children);
     * - querying the tree uri itself lists the root's children AND the
     *   tree's own root row (`_id` = the tree's own segment, dir flag) -
     *   that row is skipped: its children are the very rows of the listing,
     *   so recursing into it would re-list the root through an invalid uri.
     * The document-provider protocol constants are kept as inline literals
     * on purpose (the stub jar now carries `DocumentsContract`, see
     * AGENTS.md): the "_id" / "_display_name" / "_flags" columns and the
     * directory flag value 1.
     */
    internal var folderChildren: (Uri) -> List<FolderChild> = { folderUri ->
        // A tree uri's ":openable" picker suffix (if the picker appends one)
        // rides in its last path segment: the provider answers only the
        // canonical ":tree" shape, so query it and derive the tree segment
        // (root-row skip + child-uri treeId) from the canonical form.
        val queryUri = canonicalTreeUri(folderUri)
        context.contentResolver
            .query(queryUri, null, null, null, null)
            ?.use { cursor ->
                val docId = cursor.getColumnIndexOrThrow("_id")
                val name = cursor.getColumnIndexOrThrow("_display_name")
                val flags = cursor.getColumnIndexOrThrow("_flags")
                // The tree's own document-id segment (with its ":tree"
                // suffix, no ":openable"): path segment 2 of both the
                // tree uri (/tree/<seg>) and the child uri
                // (/child/<seg>/<parentId>) this seam recurses with.
                val treeSeg = queryUri.pathSegments.getOrNull(1)
                val listingTheTree = folderUri.pathSegments.getOrNull(0) == "tree"
                buildList {
                    while (cursor.moveToNext()) {
                        val rawDocId = cursor.getString(docId)
                        // The tree's own root row: listed with the root's
                        // children, but its children are those same rows -
                        // recursing into it is both invalid and a loop.
                        if (rawDocId == null || (listingTheTree && rawDocId == treeSeg)) continue
                        val isDirectory = (cursor.getLong(flags) and DOCUMENT_FLAG_DIR) != 0L
                        add(
                            FolderChild(
                                displayName = cursor.getString(name) ?: "",
                                isDirectory = isDirectory,
                                childUri = if (isDirectory) {
                                    // A subfolder's children are listed at the
                                    // tree's child uri, never at the
                                    // subfolder's document uri (which answers
                                    // with the folder's own single row).
                                    childDocumentsUri(folderUri, treeSeg, rawDocId)
                                } else {
                                    // A fresh builder: the parent's ":tree"
                                    // suffix must not leak into the child's
                                    // document uri.
                                    documentUri(folderUri, rawDocId)
                                },
                            ),
                        )
                    }
                }
            }
            ?: emptyList()
    }

    /** A tree uri's canonical form: a trailing ":openable" on its last path segment (the picker's suffix, which android.net.Uri keeps in the path) stripped so the provider receives the `:tree` shape. Non-tree uris and tree uris without the suffix pass through unchanged; the ":tree" suffix is never stripped. */
    private fun canonicalTreeUri(treeUri: Uri): Uri {
        if (treeUri.pathSegments.getOrNull(0) != "tree") return treeUri
        val lastSegment = treeUri.pathSegments.lastOrNull() ?: return treeUri
        if (!lastSegment.endsWith(":openable")) return treeUri
        return Uri.Builder()
            .scheme(treeUri.scheme)
            .authority(treeUri.authority)
            .encodedPath("/tree/${Uri.encode(lastSegment.removeSuffix(":openable"), null)}")
            .build()
    }

    /** A file's document uri `scheme://authority/document/<docId>` built from [folderUri]'s scheme + authority, the docId percent-encoded into a single path segment. */
    private fun documentUri(folderUri: Uri, docId: String): String =
        Uri.Builder()
            .scheme(folderUri.scheme)
            .authority(folderUri.authority)
            .encodedPath("/document/${Uri.encode(docId, null)}")
            .build()
            .toString()

    /** A folder-children listing uri `scheme://authority/child/<treeSeg>:tree/<docId>` for the folder [docId] inside [folderUri]'s tree, the docId percent-encoded into a single path segment. */
    private fun childDocumentsUri(folderUri: Uri, treeSeg: String?, docId: String): String =
        Uri.Builder()
            .scheme(folderUri.scheme)
            .authority(folderUri.authority)
            .encodedPath("/child/${Uri.encode(treeSeg.orEmpty(), null)}/${Uri.encode(docId, null)}")
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
     * Adds a freshly picked song file onto a tag: persists the SAF grant,
     * then stores the entry and appends it to the tag's song list in one
     * transaction, so the tag's list gains the new song with a single pick.
     * The song list is associated by the tag's UID in the database, so no
     * tag write is involved.
     */
    fun addSongToTag(uri: Uri, tagId: Long, displayName: String?) {
        viewModelScope.launch {
            if (!persistReadGrant(uri)) return@launch
            val entry = repository.addEntryToTag(tagId, EntryType.AUDIO, displayName ?: "Untitled", uri.toString())
            event(if (entry != null) "Song added to the tag" else "Could not add the song to that tag.")
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

/** DocumentsContract.Document.FLAG_DIR_DIR (kept as an inline literal; see AGENTS.md). */
private const val DOCUMENT_FLAG_DIR = 1L

/** Log tag: folder read failures are attributed here (the failing uri + the exception). */
private const val TAG = "LumiViewModel"
