package com.lumi.player.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import com.lumi.player.data.EntryEntity
import com.lumi.player.data.PlayMode
import com.lumi.player.data.TagEntity
import com.lumi.player.data.LumiRepository
import com.lumi.player.player.EntrySpec
import com.lumi.player.player.NfcAction
import com.lumi.player.player.PlaybackPolicy
import com.lumi.player.player.PlayerService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    fun observeTags(): kotlinx.coroutines.flow.Flow<List<TagEntity>> = repository.observeTags()

    fun observeEntries(): kotlinx.coroutines.flow.Flow<List<EntryEntity>> = repository.observeEntries()

    // ---- NFC registration flow ----

    private val _registering = MutableStateFlow(false)
    val registering: StateFlow<Boolean> = _registering.asStateFlow()

    private val _capturedUid = MutableStateFlow<String?>(null)
    val capturedUid: StateFlow<String?> = _capturedUid.asStateFlow()

    // ---- transient UI events (toasts/snackbars) ----

    private val _uiEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val uiEvents: SharedFlow<String> = _uiEvents

    // ---- link editor: selected tag + its live entry list ----

    private val _selectedTagId = MutableStateFlow<Long?>(null)
    val selectedTagId: StateFlow<Long?> = _selectedTagId.asStateFlow()

    fun selectTag(id: Long?) {
        _selectedTagId.value = id
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val linkedEntries: StateFlow<List<EntryEntity>> =
        _selectedTagId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else repository.observeEntriesForTag(id)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // Rebuild the policy when the mode changes (fresh active-tag state).
        viewModelScope.launch {
            playMode.collect { mode ->
                policy = PlaybackPolicy(mode)
            }
        }
    }

    // ---- NFC events from the activity ----

    /**
     * A tag was detected (foreground-dispatch tap in continuous mode, or
     * reader-mode enter event in proximity mode / registration capture).
     */
    fun onTagDetected(uid: String) {
        if (_registering.value) {
            // We were waiting to register a tag: capture it.
            _capturedUid.value = uid
            _registering.value = false
            return
        }
        if (policy.activeTagUid == uid) return
        viewModelScope.launch {
            val tag = repository.tagByUid(uid) ?: return@launch
            when (policy.onTagDetected(uid)) {
                NfcAction.StartPlayback -> {
                    val entries = repository.entriesForTag(tag.id)
                    if (entries.isEmpty()) {
                        event("Nothing is linked to ${tag.name.ifEmpty { "this tag" }} yet.")
                    } else {
                        startPlayer(entries.map { it.toSpec() })
                    }
                }

                NfcAction.Ignore -> Unit // re-tap of the tag already playing
                NfcAction.StopPlayback -> Unit
            }
        }
    }

    /** The tag left the reader range (proximity mode). */
    fun onTagLost(uid: String) {
        if (policy.onTagLost(uid) == NfcAction.StopPlayback) {
            stopPlayer()
        }
    }

    /** The service reported playback stopped on its own. */
    fun onPlaybackStopped() {
        policy.onPlaybackStopped()
    }

    // ---- player commands ----

    private fun startPlayer(specs: List<EntrySpec>) {
        val intent = Intent(context, PlayerService::class.java).apply {
            action = PlayerService.ACTION_START
            putExtra(PlayerService.EXTRA_ENTRIES, ArrayList(specs))
        }
        ContextCompat.startForegroundService(context, intent)
    }

    private fun stopPlayer() {
        val intent = Intent(context, PlayerService::class.java).apply {
            action = PlayerService.ACTION_STOP
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

    // ---- settings: tags ----

    fun startTagRegistration() {
        _capturedUid.value = null
        _registering.value = true
    }

    fun cancelTagRegistration() {
        _registering.value = false
    }

    fun discardCaptured() {
        _capturedUid.value = null
    }

    fun confirmTagName(name: String) {
        val uid = _capturedUid.value ?: return
        _capturedUid.value = null
        viewModelScope.launch {
            val saved = repository.addTag(uid, name)
            event(if (saved != null) "Tag added!" else "That tag is already registered.")
            if (saved != null) _selectedTagId.value = saved.id
        }
    }

    fun removeTag(tag: TagEntity) {
        viewModelScope.launch {
            repository.deleteTag(tag)
            if (_selectedTagId.value == tag.id) _selectedTagId.value = null
            event("Tag removed")
        }
    }

    // ---- settings: entries ----

    /** Persist the SAF grant, then store the picked file as an entry. */
    fun addFileEntry(uri: Uri, type: EntryType, displayName: String?) {
        viewModelScope.launch {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (e: Exception) {
                event("Could not keep access to that file.")
                return@launch
            }
            repository.addEntry(type, displayName ?: "Untitled", uri.toString())
            event(if (type == EntryType.AUDIO) "Song added!" else "${type.name} entry added (plays in a future version)")
        }
    }

    fun addUrlEntry(url: String) {
        val clean = url.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            repository.addEntry(EntryType.URL, clean, clean)
            event("Link added (opens in a future version)")
        }
    }

    fun removeEntry(entry: EntryEntity) {
        viewModelScope.launch {
            repository.deleteEntry(entry)
            event("Removed ${entry.label}")
        }
    }

    // ---- settings: links ----

    fun addLinked(tagId: Long, entryId: Long) {
        viewModelScope.launch {
            repository.updateTagEntries(tagId) { current -> current + entryId }
        }
    }

    fun removeLinked(tagId: Long, entryId: Long) {
        viewModelScope.launch {
            repository.updateTagEntries(tagId) { current -> current.filter { it != entryId } }
        }
    }

    fun moveLinked(tagId: Long, index: Int, by: Int) {
        viewModelScope.launch {
            repository.updateTagEntries(tagId) { current ->
                val target = index + by
                if (index !in current.indices || target !in current.indices) {
                    current
                } else {
                    val moved = current[index]
                    current.toMutableList().apply {
                        removeAt(index)
                        add(target, moved)
                    }
                }
            }
        }
    }

    // ---- helpers ----

    private fun event(message: String) {
        _uiEvents.tryEmit(message)
    }

    private fun EntryEntity.toSpec(): EntrySpec =
        EntrySpec(type = type, label = label, payload = payload)

    override fun onCleared() {
        super.onCleared()
    }
}

/** Factory: the app instance supplies the repository. */
fun lumiViewModelFactory(app: LumiApp): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        LumiViewModel(app, app.repository) as T
}
