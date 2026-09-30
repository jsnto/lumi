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
import com.lumi.player.data.AudioLevelSettings
import com.lumi.player.data.EntryType
import com.lumi.player.data.EntryEntity
import com.lumi.player.data.ContentSortDirection
import com.lumi.player.data.LoudnessMeasurer
import com.lumi.player.data.LoudnessResult
import com.lumi.player.data.LoudnessStatus
import com.lumi.player.data.LumiThemeMode
import com.lumi.player.data.PlayMode
import com.lumi.player.data.PositionRestore
import com.lumi.player.data.SongDownloadResult
import com.lumi.player.data.SongDownloader
import com.lumi.player.data.SongMetadata
import com.lumi.player.data.TagEntity
import com.lumi.player.data.TagSongRow
import com.lumi.player.data.FolderChild
import com.lumi.player.data.FolderEntity
import com.lumi.player.data.FolderTreeReader
import com.lumi.player.data.LumiRepository
import com.lumi.player.data.ScannedSong
import com.lumi.player.data.TagSyncState
import com.lumi.player.data.decideRestore
import com.lumi.player.data.fingerprintOf
import com.lumi.player.data.rescanNewSongs
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        repository.observePlayMode().stateIn(viewModelScope, SharingStarted.Eagerly, PlayMode.PROXIMITY)

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

    /**
     * The NFC tag session behind this surface: the scan arm states (the
     * plain scan, the scan-to-assign, the card-write and card-compare
     * scans), the settle of an armed scan by the next tag read, the
     * registration capture + save, the per-uid card details and per-song
     * download urls observed per presentation, and the phone's NFC
     * usability flags. The flows below delegate here; [onTagDetected]
     * keeps the read dispatch (it consults
     * [NfcTagCoordinator.settleIfScanning] first), and the scan/capture
     * actions and physical seams further down the class forward to it.
     */
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

    /**
     * Re-publishes the phone's NFC usability (the activity re-checks on
     * every resume + the radio's state-changed broadcast) - see
     * [NfcTagCoordinator.nfcMissing].
     */
    fun setNfcUsability(missing: Boolean, disabled: Boolean) {
        nfcCoordinator.setNfcUsability(missing, disabled)
    }

    // ---- song downloads (the tag page's download icon, Music/lumi) ----

    /**
     * The download state of one tag song row (keyed by the row's link id -
     * the row's stable identity; no entry means idle).
     */
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

    /**
     * The selected tag's "Save to NFC tag" in-sync indicator (DERIVED, not
     * event-driven): the tag's current system data (its name + its
     * ordered song display names - exactly what the write path composes)
     * against its last known tag state (the per-uid saved memory). Any
     * tag edit (a rename, a song added/removed/reordered) flips it to
     * [TagSyncState.Differs] at once; a successful write - or a compare
     * scan of a matching card (which records the memory) - flips it to
     * [TagSyncState.InSync] when the data matches; a tag with no memory
     * for its uid reads [TagSyncState.Unknown].
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
        if (tag != null) nfcCoordinator.inspectTagDetails(uid, tag)
        // While a tag scan is in progress the read settles the armed scan
        // (the card-write scan, then the compare scan, then the plain
        // scan) and never reaches the playback path (see
        // [nfcCoordinator.settleIfScanning]).
        if (nfcCoordinator.settleIfScanning(uid, tag)) return
        // The plain read's own NDEF read: the card's per-song download
        // urls publish from it (a read that could not connect keeps the
        // last known urls).
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
                // (that would hold the policy, and arm the presence poll,
                // for a list that never plays).
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

    // ---- settings: appearance (theme mode) ----

    /**
     * The app's appearance mode (the settings screen's "Appearance"
     * choice): follow the system (the default - the phone's own light/dark
     * setting), or force light / dark. Persisted in the database; the
     * activity applies it app-wide through the theme, effective immediately
     * on change (no app restart required).
     */
    val themeMode: StateFlow<LumiThemeMode> =
        repository.observeThemeMode()
            .stateIn(viewModelScope, SharingStarted.Eagerly, LumiThemeMode.FOLLOW_SYSTEM)

    fun setThemeMode(mode: LumiThemeMode) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    // ---- settings: audio level ----

    /**
     * The audio level mode (OFF: every song plays at its recorded level;
     * NORMALIZE: each measured song gets the per-track gain that brings its
     * loudness to the target level). Off by default: no gain is applied
     * until the user turns normalization on. The pick persists in the
     * database (the service reads it live through the app's in-memory
     * [com.lumi.player.LumiApp.audioLevel] snapshot, no database read on the
     * playback path).
     */
    val audioLevelMode: StateFlow<AudioLevelMode> =
        repository.observeAudioLevelMode()
            .stateIn(viewModelScope, SharingStarted.Eagerly, AudioLevelMode.OFF)

    fun setAudioLevelMode(mode: AudioLevelMode) {
        viewModelScope.launch { repository.setAudioLevelMode(mode) }
    }

    /**
     * The target integrated loudness (LUFS) normalization aims for: one of
     * the picker's targets (AudioLevelSettings.TARGETS - -18 / -16 / -14,
     * the default -16). The pick persists in the database.
     */
    val audioLevelTargetLufs: StateFlow<Double> =
        repository.observeAudioLevelTargetLufs()
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                AudioLevelSettings.DEFAULT_TARGET_LUFS,
            )

    fun setAudioLevelTargetLufs(targetLufs: Double) {
        viewModelScope.launch { repository.setAudioLevelTargetLufs(targetLufs) }
    }

    // ---- settings: auto-lock after idle ----

    /**
     * How many minutes of idle - no song actively playing (stopped or
     * paused both count as idle) - count before the phone's screen is
     * locked, so a phone left with the app open does not drain its battery
     * doing nothing. Off by default: the screen is never locked
     * automatically. The choice persists in the database like the other
     * settings.
     */
    val autoLockDelay: StateFlow<AutoLockDelay> =
        repository.observeAutoLockDelay()
            .stateIn(viewModelScope, SharingStarted.Eagerly, AutoLockDelay.OFF)

    fun setAutoLockDelay(delay: AutoLockDelay) {
        viewModelScope.launch { repository.setAutoLockDelay(delay) }
        // The running stretch follows the new choice immediately: the
        // timer re-arms (or disarms) against the current playback state.
        autoLockEngine.applyAutoLockDelayToTimer(delay)
    }

    /**
     * The auto-lock engine (the System setting's "Auto-lock after idle"): the
     * idle timer's decision logic (pure - the wall clock is fed in through
     * the engine's own [clock] seam) driven from the shared player state,
     * so the idle stretch outlives a settings pane leaving and coming back
     * and the lock mechanism is a device admin (the engine's real
     * [lockDevice] seam is DevicePolicyManager.lockNow, only valid while
     * the admin is active). The engine owns its own collectors (the stored
     * delay, the shared player state, and the tick loop) and reads the
     * [autoLockDelay] and [keepScreenOn] flows passed to it - both declared
     * above, so property-initializer order keeps them initialized before
     * the engine is constructed; the engine's own init-ordering sharp edge
     * (an inline first emission during construction) lives inside
     * [AutoLockEngine].
     */
    private val autoLockEngine =
        AutoLockEngine(context, autoLockDelay, keepScreenOn, viewModelScope)

    // The engine's auto-lock surface, delegated (tests reach the seams
    // through the view model): the device-admin state check, the actual
    // screen lock, the wall clock the idle timer reads, and the foreground
    // gate the tick's lock applies against.
    internal var isDeviceAdminActive: (ComponentName) -> Boolean
        get() = autoLockEngine.isDeviceAdminActive
        set(value) { autoLockEngine.isDeviceAdminActive = value }

    internal var lockDevice: () -> Unit
        get() = autoLockEngine.lockDevice
        set(value) { autoLockEngine.lockDevice = value }

    internal var clock: () -> Long
        get() = autoLockEngine.clock
        set(value) { autoLockEngine.clock = value }

    /** Whether the host activity is in the foreground right now
     * ([MainActivity] keeps it in sync from onResume / onPause): the
     * auto-lock may lock the screen only while the app is in the
     * foreground. */
    internal var appInForeground: Boolean
        get() = autoLockEngine.appInForeground
        set(value) { autoLockEngine.appInForeground = value }

    /** The app's own screen-lock device admin (the auto-lock's consent target). */
    val deviceAdminComponent: ComponentName
        get() = autoLockEngine.deviceAdminComponent

    /**
     * Whether the app's screen-lock device admin is active right now. The
     * lock can only happen while it is active; the setting stays visible
     * and usable either way (the Advanced options page prompts to enable it
     * when it is not, and the app keeps working without the admin).
     */
    val deviceAdminActive: StateFlow<Boolean>
        get() = autoLockEngine.deviceAdminActive

    /**
     * The auto-lock's claim on the screen (the lock wins over keep
     * screen-on): set when the idle timeout fires and the lock is
     * applied, cleared when music plays again - until then the app's own
     * keep-screen-on flag must not keep the (locked) screen awake.
     */
    val autoLockKeepScreenSuppressed: StateFlow<Boolean>
        get() = autoLockEngine.autoLockKeepScreenSuppressed

    /**
     * Whether the window should carry FLAG_KEEP_SCREEN_ON right now: the
     * user's keep-screen-on choice AND the auto-lock not having claimed the
     * screen (the lock wins over keep-screen-on until music plays again).
     */
    val keepScreenOnEffective: StateFlow<Boolean>
        get() = autoLockEngine.keepScreenOnEffective

    /**
     * One idle check: the production loop calls this every second; tests
     * drive it with a faked clock. Returns true when the idle timeout fired
     * on this tick - and the lock is applied only while the activity is in
     * the foreground (an elapsed stretch is held while backgrounded, see
     * [AutoLockEngine.runAutoLockTick]).
     */
    internal fun runAutoLockTick(): Boolean = autoLockEngine.runAutoLockTick()

    /**
     * Re-checks the screen-lock admin's real state with the platform:
     * called when the Advanced options page opens (a revocation can happen
     * behind the app's back) and after the enable flow's result comes back.
     */
    fun refreshDeviceAdminState() {
        autoLockEngine.refreshDeviceAdminState()
    }

    /**
     * The platform's device-admin enable intent: launching it opens the
     * phone's own consent screen - the app is being granted the ability to
     * lock the screen, so the explanation states exactly that. The result
     * is fed back through [onDeviceAdminResult].
     */
    fun deviceAdminIntent(): Intent = autoLockEngine.deviceAdminIntent()

    /** The device-admin enable flow's result: the platform's real state is
     * the authority, so re-check it (a cancel and a grant both land here). */
    fun onDeviceAdminResult(resultCode: Int) {
        autoLockEngine.onDeviceAdminResult(resultCode)
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

    // ---- audio level: the measurement (loudness normalization) ----

    /**
     * The audio level's measurement status ("N of M songs measured", the
     * settings pane's status row): the app's audio entries against the
     * stored measurements (a silent file's measurement still counts as
     * measured - the read settled, there is just nothing to normalize).
     */
    val loudnessStatus: StateFlow<LoudnessStatus> =
        repository.observeLoudnessStatus()
            .stateIn(viewModelScope, SharingStarted.Eagerly, LoudnessStatus(0, 0))

    /** True while an explicit "Normalize now" pass is running. */
    private val _normalizing = MutableStateFlow(false)

    val normalizing: StateFlow<Boolean> = _normalizing.asStateFlow()

    private var normalizeJob: Job? = null

    /**
     * The single measurement slot: every loudness read - the on-add
     * measurements and the explicit pass alike - holds it for the full
     * read, so at most one entry decodes at a time; a folder add's burst
     * of fresh songs queues behind the in-flight read, one decode at a
     * time.
     */
    private val measurementLock = Mutex()

    /**
     * The explicit "Normalize now" pass (the audio level settings pane's
     * action): measures every audio entry that has no measurement yet, or
     * whose content changed since the last measurement (the content
     * fingerprint no longer matches the stored one). Best-effort per entry:
     * a failure leaves that entry unmeasured with its own event and the
     * pass keeps going. A second pass while one is running is ignored, so
     * a slow earlier pass can never run against a newer selection; the
     * live progress is the [loudnessStatus] row, plus the pass's own
     * start / completion events (the app's usual event language).
     */
    fun normalizeNow() {
        if (normalizeJob?.isActive == true) return
        normalizeJob = viewModelScope.launch {
            _normalizing.value = true
            try {
                val pending = repository.observeEntries().first().filter {
                    EntryType.fromStorage(it.type) == EntryType.AUDIO && isStaleForLoudness(it)
                }
                if (pending.isEmpty()) {
                    event("Everything is already measured.")
                    return@launch
                }
                event(
                    if (pending.size == 1) {
                        "Measuring 1 song for audio level…"
                    } else {
                        "Measuring ${pending.size} songs for audio level…"
                    },
                )
                var failed = 0
                for (entry in pending) {
                    if (!measureEntryBestEffort(entry, reportEvent = true)) failed++
                }
                event(
                    if (failed > 0) {
                        "Audio level ready - $failed " +
                            "${if (failed == 1) "song" else "songs"} couldn't be measured."
                    } else {
                        "Audio level ready."
                    },
                )
            } finally {
                _normalizing.value = false
            }
        }
    }

    /**
     * Best-effort measurement of one freshly added audio entry (the
     * on-add measurement: a song added to the content list is measured
     * right away, so the audio level works the moment it is turned on).
     * A failure leaves the entry unmeasured with its own event (the song
     * plays at its recorded level; "Normalize now" can retry it).
     */
    fun measureEntry(entry: EntryEntity) {
        viewModelScope.launch {
            measureEntryBestEffort(entry, reportEvent = true)
        }
    }

    /**
     * The measurement pass for [entry]: the content fingerprint check
     * first (an unchanged file keeps its stored measurement - no re-read),
     * then the best-effort read (off the main thread and time-limited - any
     * failure settles to unmeasured, with an event when [reportEvent]). The
     * whole pass holds the single [measurementLock], so on-add measurements
     * and the explicit pass queue behind each other - never two decodes at
     * once. Returns whether the entry now has a stored measurement.
     */
    private suspend fun measureEntryBestEffort(entry: EntryEntity, reportEvent: Boolean): Boolean =
        measurementLock.withLock {
            val fingerprint = fingerprintOf(listOf(entry.toSpec()))
            val existing = repository.loudnessForEntry(entry.id)
            if (existing != null && existing.fingerprint == fingerprint) return@withLock true
            val result = try {
                withTimeout(LoudnessMeasurer.MEASURE_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { loudnessMeasurer(Uri.parse(entry.payload)) }
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Measuring the loudness of ${entry.label} timed out", e)
                if (reportEvent) event("Couldn't measure “${entry.label}” for audio level.")
                return@withLock false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't measure the loudness of ${entry.label}", e)
                if (reportEvent) event("Couldn't measure “${entry.label}” for audio level.")
                return@withLock false
            }
            repository.recordLoudness(entry.id, result.lufs, result.truePeakDbTp, fingerprint)
            true
        }

    /** Whether [entry]'s stored measurement (if any) matches its content now. */
    private suspend fun isStaleForLoudness(entry: EntryEntity): Boolean {
        val row = repository.loudnessForEntry(entry.id)
        return row == null || row.fingerprint != fingerprintOf(listOf(entry.toSpec()))
    }

    /**
     * Test seam: the loudness measurement read, replaced in unit tests.
     * The default decodes the file at decode speed (the [LoudnessMeasurer]:
     * a background ExoPlayer with the meter's [LoudnessMeterProcessor] and
     * the [MeterAudioSink] that discards the audio) and is time-limited
     * (a stalled decode settles into a failure); a read failure surfaces as
     * the exception the caller settles to unmeasured.
     */
    internal var loudnessMeasurer: suspend (Uri) -> LoudnessResult = { uri ->
        LoudnessMeasurer(context).measure(uri)
    }

    // ---- settings: tags ----

    /**
     * Arms a tag scan (the NFC tags pane's "Scan NFC" action) - the scan
     * and its settle live in [nfcCoordinator] (see
     * [NfcTagCoordinator.startScan]).
     */
    fun startTagScan() {
        nfcCoordinator.startScan()
    }

    /** Arms the unified scan targeting [entry] (the song profile's "Scan a tag to assign" action) - see [NfcTagCoordinator.startScanForEntry]. */
    fun startTagScanForEntry(entry: EntryEntity) {
        nfcCoordinator.startScanForEntry(entry)
    }

    /** Arms the card-write scan (the tag details page's "Save to NFC tag" section's write action) - see [NfcTagCoordinator.startCardWrite]. */
    fun startCardWrite(tag: TagEntity) {
        nfcCoordinator.startCardWrite(tag)
    }

    /** Arms the COMPARE scan (the save section's "Scan to compare" action) - see [NfcTagCoordinator.startCardCompare]. */
    fun startCardCompare(tag: TagEntity) {
        nfcCoordinator.startCardCompare(tag)
    }

    fun cancelTagScan() {
        nfcCoordinator.cancelScan()
    }

    /** Consumes the resolved scan target (the settings screen has opened the tag's profile page). */
    fun consumePendingTagProfile() {
        nfcCoordinator.consumePendingTagProfile()
    }

    /**
     * Records a tag captured during a registration session - see
     * [NfcTagCoordinator.capture].
     */
    internal fun capture(uid: String, tag: Tag? = null) {
        nfcCoordinator.capture(uid, tag)
    }

    /** Test seam: capture with an explicit payload (no live tag). */
    internal fun captureForTest(uid: String, data: LumiTagData?) {
        nfcCoordinator.captureForTest(uid, data)
    }

    /** Test seam: the physical card write, replaced in unit tests. */
    internal var tagWriter: suspend (Tag?, LumiTagData) -> Boolean
        get() = nfcCoordinator.tagWriter
        set(value) { nfcCoordinator.tagWriter = value }

    /**
     * Test seam: the physical tag inspect (payload + writability in one
     * NDEF session), replaced in unit tests.
     */
    internal var tagInspector: (Tag?, LumiTagData) -> TagInspect
        get() = nfcCoordinator.tagInspector
        set(value) { nfcCoordinator.tagInspector = value }

    /**
     * Test seam: the card's lumi payload read for the per-song download
     * urls (a presentation's best-effort read; an answered read is the
     * card's payload, a failed read keeps the last known urls), replaced
     * in unit tests.
     */
    internal var songPayloadReader: suspend (Tag?) -> TagPayloadRead
        get() = nfcCoordinator.songPayloadReader
        set(value) { nfcCoordinator.songPayloadReader = value }

    /**
     * Test seam: the song download (the Music/lumi MediaStore write),
     * replaced in unit tests.
     */
    internal var songDownloader: suspend (
        songName: String,
        url: String,
        onProgress: (Float?) -> Unit,
    ) -> SongDownloadResult = { name, url, onProgress ->
        SongDownloader(context.contentResolver).download(name, url, onProgress)
    }

    /**
     * Downloads the song a tag row points at ([url] is the card's url for
     * [songName]; the page has already checked the download-icon predicate
     * - present, valid, not in content) into the public music folder's
     * lumi subfolder. A download already in flight for the row is ignored
     * (no double start); a failure lands in the row's retryable failed
     * state.
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

    fun discardCaptured() {
        nfcCoordinator.discardCaptured()
    }

    /** Saves the captured tag under [name] - see [NfcTagCoordinator.confirmTagName]. */
    fun confirmTagName(name: String) {
        nfcCoordinator.confirmTagName(name)
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

    /**
     * The picked-folder tree reader (the SAF documents provider's
     * tree-scoped listing seam and the guarded tree walks behind the
     * folder add, rescan, removal, and folder-page song list): unit tests
     * replace its seam through the [folderChildren] delegate below.
     */
    private val folderTreeReader = FolderTreeReader(context)

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
            // Fresh audio is measured right away, best-effort (the audio
            // level works the moment it is turned on; a failure leaves the
            // song unmeasured at its recorded level).
            if (added != null && type == EntryType.AUDIO) measureEntry(added)
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
            val label = withContext(Dispatchers.IO) { folderTreeReader.folderDisplayName(treeUri) }
            val folder = repository.rememberFolder(treeUri.toString(), label)
            val scanned = try {
                withContext(Dispatchers.IO) { folderTreeReader.scanFolderAudioFiles(treeUri) }
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
        val added = scanned.mapNotNull {
            repository.addEntryUnique(EntryType.AUDIO, it.displayName, it.uri, folderId = folder.id)
        }
        event(
            when {
                added.isEmpty() ->
                    if (scanned.size == 1) {
                        "The song from the folder is already in your content list."
                    } else {
                        "All ${scanned.size} songs from the folder are already in your content list."
                    }
                added.size < scanned.size -> "Added ${added.size} of ${scanned.size} songs from the folder."
                else -> "Added ${scanned.size} ${if (scanned.size == 1) "song" else "songs"} from the folder."
            },
        )
        // Freshly stored audio is measured right away, best-effort.
        added.forEach { measureEntry(it) }
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
                        folderTreeReader.folderChildrenAttributed(Uri.parse(folderUri))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rescan folder ${folder.uri}", e)
                emptyList()
            }
            val added = newSongs.mapNotNull {
                repository.addEntryUnique(EntryType.AUDIO, it.displayName, it.uri, folderId = folder.id)
            }
            event(
                when {
                    newSongs.isEmpty() -> "Nothing new in that folder."
                    newSongs.size == 1 -> "Added 1 new song from that folder."
                    else -> "Added ${newSongs.size} new songs from that folder."
                },
            )
            // Newly stored audio is measured right away, best-effort.
            added.forEach { measureEntry(it) }
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
                folderTreeReader.walkFolderTree("Failed to read the songs of folder ${folder.uri} for removal", folder.uri)
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
                folderTreeReader.walkFolderTree("Failed to read the songs of folder ${folder.uri} for the folder page", folder.uri)
                    ?.let { songs -> songs.map { it.uri }.toSet() }
            // The folder (or the page) may have changed while the walk ran.
            if (selectedFolder?.id != folder.id) return@launch
            _folderSongs.value = payloads?.let {
                FolderSongsState(entries = repository.entriesByPayloads(it))
            } ?: FolderSongsState(entries = emptyList(), readable = false)
        }
    }

    /**
     * Test seam: the documents provider's folder listing (a folder uri to
     * its direct children), replaced in unit tests with a fake tree -
     * delegated to [folderTreeReader], whose [folderChildren] KDoc carries
     * the SAF tree-grant uri grammar this seam enforces.
     */
    internal var folderChildren: (Uri) -> List<FolderChild>
        get() = folderTreeReader.folderChildren
        set(value) { folderTreeReader.folderChildren = value }

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
                folderTreeReader.walkFolderTree("Failed to backfill the songs of folder ${folder.uri}", folder.uri)
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
