package com.lumi.player.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.nfc.Tag
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lumi.player.LumiApp
import com.lumi.player.data.AutoLockDelay
import com.lumi.player.data.AudioLevelMode
import com.lumi.player.data.ContentSortDirection
import com.lumi.player.data.EntryEntity
import com.lumi.player.data.EntryType
import com.lumi.player.data.FolderChild
import com.lumi.player.data.FolderEntity
import com.lumi.player.data.LoudnessResult
import com.lumi.player.data.LoudnessStatus
import com.lumi.player.data.LumiRepository
import com.lumi.player.data.LumiThemeMode
import com.lumi.player.data.PlayMode
import com.lumi.player.data.PositionRestore
import com.lumi.player.data.SongDownloadResult
import com.lumi.player.data.SongDownloader
import com.lumi.player.data.SongMetadata
import com.lumi.player.data.TagEntity
import com.lumi.player.data.TagSongRow
import com.lumi.player.data.TagSyncState
import com.lumi.player.data.decideRestore
import com.lumi.player.data.fingerprintOf
import com.lumi.player.nfc.LumiTagData
import com.lumi.player.nfc.TagCardDetails
import com.lumi.player.nfc.TagInspect
import com.lumi.player.nfc.TagPayloadRead
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Single view model for the whole app (front screen + settings): the
 * [PlaybackPolicy] for the active play mode, the NFC read dispatch (a scan
 * read settles the armed scan, a playback read drives the policy), and the
 * playback commands. Everything else is delegated to the collaborators
 * constructed here - [settings], [loudnessOrchestrator],
 * [folderContentManager], [tagLinkManager], [nfcCoordinator], and
 * [autoLockEngine] - through same-named surfaces, so the UI and the test
 * suite never touch the collaborators directly (each collaborator's own
 * KDoc carries the behavior details).
 */
class LumiViewModel(
    private val context: Context,
    val repository: LumiRepository,
) : ViewModel() {

    // ---- collaborators (the delegated surfaces below forward to them) ----

    /** The app's settings surface (every persisted setting's Room-backed [StateFlow] + setter). */
    private val settings =
        SettingsViewModel(repository, viewModelScope) { delay ->
            autoLockEngine.applyAutoLockDelayToTimer(delay)
        }

    /** The audio level's measurement orchestration (see [LoudnessOrchestrator]). */
    private val loudnessOrchestrator =
        LoudnessOrchestrator(context, repository, ::event, viewModelScope)

    /** The folder-content management (see [FolderContentManager]); the on-add measurement hook reaches the loudness orchestrator. */
    private val folderContentManager =
        FolderContentManager(
            context,
            repository,
            ::event,
            viewModelScope,
            measureNewEntry = { loudnessOrchestrator.measureEntry(it) },
        )

    /** The tag's song links (see [TagLinkManager]). */
    private val tagLinkManager = TagLinkManager(repository, ::event, viewModelScope)

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

    // ---- NFC tag session (delegated to [nfcCoordinator]) ----

    /** The NFC tag session behind this surface (the scan arms, the settle of an armed scan, the registration capture + save, the per-uid card details and download urls, the phone's NFC usability - see [NfcTagCoordinator]). */
    private val nfcCoordinator = NfcTagCoordinator(this, ::event)

    val scanActive: StateFlow<Boolean> get() = nfcCoordinator.scanActive
    val pendingTagProfile: StateFlow<TagEntity?> get() = nfcCoordinator.pendingTagProfile
    val scanAssignEntry: StateFlow<EntryEntity?> get() = nfcCoordinator.scanAssignEntry
    val cardWriteTarget: StateFlow<TagEntity?> get() = nfcCoordinator.cardWriteTarget
    val cardCompareTarget: StateFlow<TagEntity?> get() = nfcCoordinator.cardCompareTarget
    val tagCardDetails: StateFlow<Map<String, TagCardDetails>> get() = nfcCoordinator.tagCardDetails
    val tagSongUrls: StateFlow<Map<String, Map<String, String>>> get() = nfcCoordinator.tagSongUrls
    val nfcMissing: StateFlow<Boolean> get() = nfcCoordinator.nfcMissing
    val nfcDisabled: StateFlow<Boolean> get() = nfcCoordinator.nfcDisabled
    val capturedUid: StateFlow<String?> get() = nfcCoordinator.capturedUid
    val capturedTagData: StateFlow<LumiTagData?> get() = nfcCoordinator.capturedTagData
    val capturedRestoreSongs: StateFlow<List<String>> get() = nfcCoordinator.capturedRestoreSongs

    /** Re-publishes the phone's NFC usability (the activity re-checks on every resume + state change). */
    fun setNfcUsability(missing: Boolean, disabled: Boolean) {
        nfcCoordinator.setNfcUsability(missing, disabled)
    }

    // ---- song downloads (the tag page's download icon, Music/lumi) ----

    /** The download state of one tag song row (keyed by the row's link id - the row's stable identity; no entry means idle). */
    sealed interface SongDownloadState {
        /** Downloading: [progress] is the 0..1 fraction, or null while the total size is unknown. */
        data class InFlight(val progress: Float?) : SongDownloadState

        /** The download failed (retryable): the row's download action retries. */
        data object Failed : SongDownloadState
    }

    private val _songDownloadStates = MutableStateFlow<Map<Long, SongDownloadState>>(emptyMap())
    val songDownloadStates: StateFlow<Map<Long, SongDownloadState>> = _songDownloadStates.asStateFlow()

    // ---- transient UI events (toasts/snackbars) ----

    private val _uiEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val uiEvents: SharedFlow<String> = _uiEvents

    /** Emitted when a tag read makes a tag the ACTIVE one (start or switch) - the activity seeds its presence record here. */
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

    /**
     * The selected tag's "Save to NFC tag" in-sync indicator (DERIVED, not
     * event-driven): the tag's current system data against its last known
     * tag state (the per-uid saved memory). Any tag edit flips it to
     * [TagSyncState.Differs] at once; a matching write/compare scan flips
     * it to [TagSyncState.InSync]; no memory reads [TagSyncState.Unknown].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val tagSaveSync: StateFlow<TagSyncState> =
        effectiveSelectedTagId.flatMapLatest { id ->
            if (id == null) flowOf(TagSyncState.Unknown) else repository.observeTagSync(id)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, TagSyncState.Unknown)

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
     * or a tap delivered by intent on cold start). While a tag scan is in
     * progress the read settles the armed scan (it consults
     * [nfcCoordinator.settleIfScanning] first) and never reaches the
     * playback path.
     */
    fun onTagDetected(uid: String, tag: Tag? = null) {
        if (tag != null) nfcCoordinator.inspectTagDetails(uid, tag)
        if (nfcCoordinator.settleIfScanning(uid, tag)) return
        // The plain read publishes the card's per-song download urls (a
        // read that could not connect keeps the last known ones).
        if (tag != null) nfcCoordinator.readTagSongUrls(uid, tag)
        val paused = PlayerStateHolder.state.value.status == PlaybackStatus.PAUSED
        if (policy.activeTagUid == uid && !paused) return
        viewModelScope.launch {
            val tag = repository.tagByUid(uid) ?: return@launch
            // The rows resolve live by name: a name whose song has been
            // removed from the library contributes no spec to the queue
            // (and a list whose songs are all missing plays nothing, like
            // an empty one: it must not become the active tag).
            val rows = repository.entriesForTag(tag.id)
            val entries = rows.mapNotNull { it.entry }
            if (entries.isEmpty()) {
                // A tag that plays nothing (no links, or every linked song
                // gone from the library) must not become the active tag
                // (that would hold the policy and arm the presence poll for
                // a list that never plays).
                event(
                    if (rows.isEmpty()) {
                        "Nothing is linked to ${tag.name.ifEmpty { "this tag" }} yet."
                    } else {
                        "None of ${tag.name.ifEmpty { "this tag" }}'s songs are in the content list anymore."
                    },
                )
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

                // No-op read outcomes: a re-read of the tag already
                // playing (Ignore); a proximity pause is handled via
                // [onTagLost] instead of the read path.
                NfcAction.Ignore, NfcAction.PausePlayback -> Unit
            }
        }
    }

    /**
     * The tag left the reader range (proximity mode). Pauses rather than
     * stops: the tag stays active in the policy, so a re-read of it resumes
     * the paused list ("reading overrides pause").
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
     * Starts the service to play [specs] for tag [uid] (resuming the tag's
     * remembered position when it was saved against this exact linked
     * list). A null [uid] is manual playback: no remembered position
     * applies and the policy's active tag is cleared, so a later tag read
     * starts that tag's own list instead of resuming the manual queue.
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

    /** The remembered-position start extras (the entry index + position to seek to on the first ready state). */
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

    // ---- settings (all delegated to [settings]) ----

    /** The current play mode, persisted in the database. */
    val playMode: StateFlow<PlayMode> get() = settings.playMode
    fun setPlayMode(mode: PlayMode) = settings.setPlayMode(mode)

    /** Whether the user asked not to show the soft-lock prompt again. */
    val lockPopupSuppressed: StateFlow<Boolean> get() = settings.lockPopupSuppressed
    fun setLockPopupSuppressed(suppressed: Boolean) = settings.setLockPopupSuppressed(suppressed)

    /** Whether the user wants the screen to stay on while the app is open (off by default). */
    val keepScreenOn: StateFlow<Boolean> get() = settings.keepScreenOn
    fun setKeepScreenOn(on: Boolean) = settings.setKeepScreenOn(on)

    /** How many songs' last played position the per-tag LRU remembers (the Advanced options page's stepper). */
    val rememberedPositions: StateFlow<Int> get() = settings.rememberedPositions

    /** Whether the remembered-positions write is still in flight (the stepper disables its buttons for that duration). */
    val rememberedPositionsWriteInFlight: StateFlow<Boolean> get() = settings.rememberedPositionsWriteInFlight

    // Test seam for the write (see [SettingsViewModel.rememberedPositionsWriter]).
    internal var rememberedPositionsWriter: suspend (Int) -> Unit
        get() = settings.rememberedPositionsWriter
        set(value) { settings.rememberedPositionsWriter = value }

    fun setRememberedPositions(count: Int) = settings.setRememberedPositions(count)

    /** Whether the first-time setup wizard has been finished (seeded with a synchronous read of the stored gate). */
    val setupComplete: StateFlow<Boolean> get() = settings.setupComplete

    /** Records the wizard's completion (it never un-does itself) and resets the wizard's flow to its start. */
    fun completeSetup() = settings.completeSetup()

    /** The wizard's current step (a recreation mid-wizard keeps the step). */
    val wizardStep: StateFlow<SetupStep> get() = settings.wizardStep
    fun setWizardStep(step: SetupStep) = settings.setWizardStep(step)

    /** The wizard's folder pick in flight (its music step's button copy). */
    val wizardFolderPickInFlight: StateFlow<Boolean> get() = settings.wizardFolderPickInFlight
    fun setWizardFolderPickInFlight(inFlight: Boolean) = settings.setWizardFolderPickInFlight(inFlight)

    /** How many seconds of absence of reports on the active tag count as removal (proximity mode). */
    val nfcRereadSeconds: StateFlow<Long> get() = settings.nfcRereadSeconds
    fun setNfcRereadSeconds(seconds: Long) = settings.setNfcRereadSeconds(seconds)

    /** The content list's title sort direction (ascending is the default). */
    val contentSortDirection: StateFlow<ContentSortDirection> get() = settings.contentSortDirection
    fun setContentSortDirection(direction: ContentSortDirection) = settings.setContentSortDirection(direction)

    /** The app's appearance mode (follow the system, or force light / dark). */
    val themeMode: StateFlow<LumiThemeMode> get() = settings.themeMode
    fun setThemeMode(mode: LumiThemeMode) = settings.setThemeMode(mode)

    /** The audio level mode (OFF applies no gain; NORMALIZE applies the per-track gain). */
    val audioLevelMode: StateFlow<AudioLevelMode> get() = settings.audioLevelMode
    fun setAudioLevelMode(mode: AudioLevelMode) = settings.setAudioLevelMode(mode)

    /** The target integrated loudness (LUFS) normalization aims for (default -16). */
    val audioLevelTargetLufs: StateFlow<Double> get() = settings.audioLevelTargetLufs
    fun setAudioLevelTargetLufs(targetLufs: Double) = settings.setAudioLevelTargetLufs(targetLufs)

    /** How many minutes of idle count before the auto-lock (off by default). */
    val autoLockDelay: StateFlow<AutoLockDelay> get() = settings.autoLockDelay
    fun setAutoLockDelay(delay: AutoLockDelay) = settings.setAutoLockDelay(delay)

    /**
     * The auto-lock engine (the idle timer driven from the shared player
     * state, the device-admin lock mechanism - see [AutoLockEngine]). It
     * reads the [autoLockDelay] and [keepScreenOn] flows passed to it -
     * both declared above, so property-initializer order keeps them
     * initialized before the engine is constructed; the engine's own
     * init-ordering sharp edge (an inline first emission during
     * construction) lives inside [AutoLockEngine].
     */
    private val autoLockEngine =
        AutoLockEngine(context, autoLockDelay, keepScreenOn, viewModelScope)

    // The engine's auto-lock surface, delegated (tests reach the seams
    // through the view model).
    internal var isDeviceAdminActive: (ComponentName) -> Boolean
        get() = autoLockEngine.isDeviceAdminActive
        set(value) { autoLockEngine.isDeviceAdminActive = value }

    internal var lockDevice: () -> Unit
        get() = autoLockEngine.lockDevice
        set(value) { autoLockEngine.lockDevice = value }

    internal var clock: () -> Long
        get() = autoLockEngine.clock
        set(value) { autoLockEngine.clock = value }

    /** Whether the host activity is in the foreground right now ([MainActivity] keeps it in sync from onResume / onPause): the auto-lock may lock the screen only while in the foreground. */
    internal var appInForeground: Boolean
        get() = autoLockEngine.appInForeground
        set(value) { autoLockEngine.appInForeground = value }

    /** The app's own screen-lock device admin (the auto-lock's consent target). */
    val deviceAdminComponent: ComponentName get() = autoLockEngine.deviceAdminComponent

    /** Whether the screen-lock device admin is active right now (the lock needs it; the setting works without it). */
    val deviceAdminActive: StateFlow<Boolean> get() = autoLockEngine.deviceAdminActive

    /** The auto-lock's claim on the screen (the lock wins over keep screen-on: set on fire, cleared when music plays again). */
    val autoLockKeepScreenSuppressed: StateFlow<Boolean> get() = autoLockEngine.autoLockKeepScreenSuppressed

    /** Whether the window should carry FLAG_KEEP_SCREEN_ON right now (keep-screen-on AND the auto-lock not having claimed the screen). */
    val keepScreenOnEffective: StateFlow<Boolean> get() = autoLockEngine.keepScreenOnEffective

    /** One idle check (the production loop calls it every second; tests drive it with a faked clock). */
    internal fun runAutoLockTick(): Boolean = autoLockEngine.runAutoLockTick()

    /** Re-checks the screen-lock admin's real state with the platform (a revocation can happen behind the app's back). */
    fun refreshDeviceAdminState() = autoLockEngine.refreshDeviceAdminState()

    /** The platform's device-admin enable intent (launching it opens the phone's own consent screen). */
    fun deviceAdminIntent(): Intent = autoLockEngine.deviceAdminIntent()

    /** The device-admin enable flow's result: the platform's real state is the authority, so re-check it. */
    fun onDeviceAdminResult(resultCode: Int) = autoLockEngine.onDeviceAdminResult(resultCode)

    // ---- content: manual playback (the play buttons) ----

    /** Plays a single stored song (a content row's play button): that entry alone, unattached to any tag. */
    fun playEntry(entry: EntryEntity) {
        viewModelScope.launch {
            startPlayer(listOf(entry.toSpec()), uid = null)
        }
    }

    /**
     * Toggles the current song (the folder page's song-row play/pause
     * button): the service's own ACTION_TOGGLE - a paused current song
     * resumes the SAME song (no fresh start), a playing one pauses in
     * place.
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
     * best-effort (off the main thread, time-limited; any failure settles
     * to no metadata). Re-selecting cancels an in-flight read; a read that
     * settles after the page moved on writes nothing.
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

    /** The best-effort metadata read for [entry] (settles to no metadata on any failure). */
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
     * tests. The default reads the file's tags with a
     * [MediaMetadataRetriever] (a blocking content-resolver read -
     * [selectEntry] keeps it off the main thread and time-limited); any
     * read failure surfaces as the exception the caller settles to no
     * metadata. The pruned framework's metadata surface is
     * [MediaMetadataRetriever.extractMetadata] with the int
     * [MediaMetadataRetriever.METADATA_KEY_ARTIST]-style keys (a duration
     * comes back as its millisecond count's text). The retriever is
     * released with [MediaMetadataRetriever.close] on API 29+ and the
     * native [MediaMetadataRetriever.release] below -
     * [MediaMetadataRetriever.close] does not exist before API 29, and
     * minSdk is 26.
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

    // ---- audio level: the measurement (delegated to [loudnessOrchestrator]) ----

    /** The audio level's measurement status ("N of M songs measured", the settings pane's status row). */
    val loudnessStatus: StateFlow<LoudnessStatus> get() = loudnessOrchestrator.loudnessStatus

    /** The ids of the entries played volume-normalized (a stored reading AND the mode enabled), live. */
    val normalizedEntryIds: StateFlow<Set<Long>> get() = loudnessOrchestrator.normalizedEntryIds

    /** True while an explicit "Normalize now" pass is running. */
    val normalizing: StateFlow<Boolean> get() = loudnessOrchestrator.normalizing

    /** The explicit "Normalize now" pass (the audio level settings pane's action). */
    fun normalizeNow() = loudnessOrchestrator.normalizeNow()

    /** Best-effort measurement of one freshly added audio entry (the on-add measurement). */
    fun measureEntry(entry: EntryEntity) = loudnessOrchestrator.measureEntry(entry)

    /** Test seam: the loudness measurement read (see [LoudnessOrchestrator.loudnessMeasurer]). */
    internal var loudnessMeasurer: suspend (Uri) -> LoudnessResult
        get() = loudnessOrchestrator.loudnessMeasurer
        set(value) { loudnessOrchestrator.loudnessMeasurer = value }

    // ---- settings: tags ----

    /** Arms a tag scan (the NFC tags pane's "Scan NFC" action) - the settle lives in [nfcCoordinator]. */
    fun startTagScan() = nfcCoordinator.startScan()

    /** Arms the unified scan targeting [entry] (the song profile's "Scan a tag to assign" action). */
    fun startTagScanForEntry(entry: EntryEntity) = nfcCoordinator.startScanForEntry(entry)

    /** Arms the card-write scan (the tag details page's "Save to NFC tag" section's write action). */
    fun startCardWrite(tag: TagEntity) = nfcCoordinator.startCardWrite(tag)

    /** Arms the COMPARE scan (the save section's "Scan to compare" action). */
    fun startCardCompare(tag: TagEntity) = nfcCoordinator.startCardCompare(tag)

    fun cancelTagScan() = nfcCoordinator.cancelScan()

    /** Consumes the resolved scan target (the settings screen has opened the tag's profile page). */
    fun consumePendingTagProfile() = nfcCoordinator.consumePendingTagProfile()

    /** Records a tag captured during a registration session (see [NfcTagCoordinator.capture]). */
    internal fun capture(uid: String, tag: Tag? = null) = nfcCoordinator.capture(uid, tag)

    /** Test seam: capture with an explicit payload (no live tag). */
    internal fun captureForTest(uid: String, data: LumiTagData?) = nfcCoordinator.captureForTest(uid, data)

    /** Test seam: the physical card write, replaced in unit tests. */
    internal var tagWriter: suspend (Tag?, LumiTagData) -> Boolean
        get() = nfcCoordinator.tagWriter
        set(value) { nfcCoordinator.tagWriter = value }

    /** Test seam: the physical tag inspect (payload + writability in one NDEF session). */
    internal var tagInspector: (Tag?, LumiTagData) -> TagInspect
        get() = nfcCoordinator.tagInspector
        set(value) { nfcCoordinator.tagInspector = value }

    /** Test seam: the card's lumi payload read for the per-song download urls (an answered read is the card's payload). */
    internal var songPayloadReader: suspend (Tag?) -> TagPayloadRead
        get() = nfcCoordinator.songPayloadReader
        set(value) { nfcCoordinator.songPayloadReader = value }

    /** Test seam: the song download (the Music/lumi MediaStore write), replaced in unit tests. */
    internal var songDownloader: suspend (
        songName: String,
        url: String,
        onProgress: (Float?) -> Unit,
    ) -> SongDownloadResult = { name, url, onProgress ->
        SongDownloader(context.contentResolver).download(name, url, onProgress)
    }

    /**
     * Downloads the song a tag row points at ([url] is the card's url for
     * [songName]; the page has already checked the download-icon
     * predicate) into the public music folder's lumi subfolder. A download
     * already in flight for the row is ignored (no double start); a
     * failure lands in the row's retryable failed state.
     */
    fun downloadTagSong(linkId: Long, songName: String, url: String) {
        if (_songDownloadStates.value[linkId] is SongDownloadState.InFlight) return
        _songDownloadStates.value = _songDownloadStates.value + (linkId to SongDownloadState.InFlight(null))
        viewModelScope.launch {
            when (val result = songDownloader(songName, url) { progress ->
                _songDownloadStates.value = _songDownloadStates.value + (linkId to SongDownloadState.InFlight(progress))
            }) {
                is SongDownloadResult.Success -> {
                    _songDownloadStates.value = _songDownloadStates.value - linkId
                    event("Saved \"$songName\" to Music/lumi.")
                }

                is SongDownloadResult.Failure -> {
                    _songDownloadStates.value = _songDownloadStates.value + (linkId to SongDownloadState.Failed)
                    event("Couldn't download \"$songName\" - tap to try again.")
                }
            }
        }
    }

    fun discardCaptured() = nfcCoordinator.discardCaptured()

    /** Saves the captured tag under [name] (registration never writes the physical card). */
    fun confirmTagName(name: String) = nfcCoordinator.confirmTagName(name)

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

    /**
     * Persist the SAF grant, then store the picked file as an entry. Fresh
     * audio is measured right away, best-effort (a failure leaves the song
     * unmeasured at its recorded level).
     */
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
            if (added != null && type == EntryType.AUDIO) measureEntry(added)
        }
    }

    fun removeEntry(entry: EntryEntity) {
        viewModelScope.launch {
            repository.deleteEntry(entry)
            event("Removed ${entry.label}")
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

    // ---- folder content (delegated to [folderContentManager]) ----

    /** Adds a picked folder tree (the SAF grant, the recursive walk, the uri/payload dedup). */
    fun addFolderEntry(treeUri: Uri) = folderContentManager.addFolderEntry(treeUri)

    /** Rescans a folder that was added earlier for new songs. */
    fun rescanFolder(folder: FolderEntity) = folderContentManager.rescanFolder(folder)

    /** A folder awaiting its removal confirmation (the state-driven dialog target). */
    val folderRemovalPreview: StateFlow<FolderContentManager.FolderRemovalPreview?> get() = folderContentManager.folderRemovalPreview

    /**
     * Prepares the removal confirmation for [folder]: re-walks its tree to
     * identify the imported songs, then opens the confirmation with the
     * song count - or a "the tree can no longer be read" notice when the
     * walk fails.
     */
    fun requestFolderRemoval(folder: FolderEntity) = folderContentManager.requestFolderRemoval(folder)

    /** Confirms a folder removal prepared by [requestFolderRemoval]. */
    fun confirmFolderRemoval() = folderContentManager.confirmFolderRemoval()

    fun cancelFolderRemoval() = folderContentManager.cancelFolderRemoval()

    /** The folder detail page's song list (null while the walk is in flight). */
    val folderSongs: StateFlow<FolderContentManager.FolderSongsState?> get() = folderContentManager.folderSongs

    /** Recomputes the folder detail page's song list for [folder] (re-selecting cancels any in-flight walk). */
    fun selectFolder(folder: FolderEntity) = folderContentManager.selectFolder(folder)

    /**
     * The v3 -> v4 backfill (content rows stored before the folder marker
     * existed are stamped with the folder that imported them; best-effort -
     * a folder whose tree can no longer be read is logged and skipped).
     * The pass runs at startup, from the manager's own init.
     */
    internal suspend fun backfillFolderEntries() = folderContentManager.backfillFolderEntries()

    /** Test seam: the documents provider's folder listing (a folder uri to its direct children), replaced in unit tests. */
    internal var folderChildren: (Uri) -> List<FolderChild>
        get() = folderContentManager.folderChildren
        set(value) { folderContentManager.folderChildren = value }

    // ---- tag links (delegated to [tagLinkManager]) ----

    /** Links an already-stored content entry to the end of the tag's song list (an already-played song is reported, not duplicated). */
    fun linkEntryToTag(tag: TagEntity, entry: EntryEntity) = tagLinkManager.linkEntryToTag(tag, entry)

    /** Links several already-stored content entries to a tag in order (the add-song dialog's Save committing its checked selection). */
    fun linkEntriesToTag(tag: TagEntity, entries: List<EntryEntity>) = tagLinkManager.linkEntriesToTag(tag, entries)

    /** Removes one song from one tag (the tag's link goes, nothing else). */
    fun unlinkSongFromTag(tag: TagEntity, row: TagSongRow) = tagLinkManager.unlinkSongFromTag(tag, row)

    /** Reorders the tag's song list (the tag detail page's drag reorder; an unchanged list writes nothing). */
    fun reorderTagSongs(tag: TagEntity, ordered: List<TagSongRow>) = tagLinkManager.reorderTagSongs(tag, ordered)

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

/** Log tag: read failures are attributed here (the failing uri + the exception). */
private const val TAG = "LumiViewModel"
