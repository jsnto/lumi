package com.lumi.player.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.lumi.player.data.EntryEntity
import com.lumi.player.data.EntryType
import com.lumi.player.data.FolderChild
import com.lumi.player.data.FolderEntity
import com.lumi.player.data.FolderTreeReader
import com.lumi.player.data.LumiRepository
import com.lumi.player.data.ScannedSong
import com.lumi.player.data.rescanNewSongs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's folder-content management behind [LumiViewModel]'s folder
 * surface: adding a picked folder tree (the SAF grant, the recursive
 * walk, the uri/payload dedup), the rescan for new songs, the removal
 * confirmation (re-walk + count + transactional delete), the folder
 * detail page's song list, and the v3 -> v4 backfill of pre-marker rows.
 * Every tree read goes through [folderTreeReader] (the SAF documents
 * provider's tree-scoped listing seam), whose [folderChildren] seam unit
 * tests replace through the view model's same-named delegate. The
 * [LumiViewModel] keeps same-named delegates for every surface here, so
 * the UI and the test suite never call this class directly (the UI names
 * the nested state types, but never constructs or calls the class).
 */
class FolderContentManager(
    private val context: Context,
    private val repository: LumiRepository,
    private val onEvent: (String) -> Unit,
    private val scope: CoroutineScope,
    /**
     * The audio level's on-add measurement hook (the [LumiViewModel] wires
     * it to the loudness orchestrator): freshly stored audio is measured
     * right away, best-effort, so the audio level works the moment it is
     * turned on (a failure leaves the song unmeasured at its recorded
     * level).
     */
    private val measureNewEntry: (EntryEntity) -> Unit,
) {

    /**
     * The picked-folder tree reader (the SAF documents provider's
     * tree-scoped listing seam and the guarded tree walks behind the
     * folder add, rescan, removal, and folder-page song list): unit tests
     * replace its seam through the [folderChildren] delegate below.
     */
    private val folderTreeReader = FolderTreeReader(context)

    // ---- add ----

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
        scope.launch {
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
                onEvent("Couldn't read that folder's songs.")
                return@launch
            }
            if (scanned.isEmpty()) {
                onEvent("No songs found in that folder.")
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
        onEvent(
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
        added.forEach { measureNewEntry(it) }
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
        scope.launch {
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
            onEvent(
                when {
                    newSongs.isEmpty() -> "Nothing new in that folder."
                    newSongs.size == 1 -> "Added 1 new song from that folder."
                    else -> "Added ${newSongs.size} new songs from that folder."
                },
            )
            // Newly stored audio is measured right away, best-effort.
            added.forEach { measureNewEntry(it) }
            // The folder page (if it is open on this folder) shows the new
            // songs.
            if (selectedFolder?.id == folder.id) refreshFolderSongs()
        }
    }

    // ---- removal ----

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
        removalJob = scope.launch {
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
        scope.launch {
            val removed = repository.removeFolder(preview.folder, preview.importedPayloads.orEmpty())
            onEvent(
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

    // ---- the folder detail page ----

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
        folderSongsJob = scope.launch {
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

    // ---- the v3 -> v4 backfill ----

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
     * Best-effort v3 -> v4 backfill at startup: content rows stored before
     * the folder marker existed are stamped with the folder that imported
     * them (the same tree walk the folder page uses); a folder that can
     * no longer be read degrades to its songs appearing in the
     * individual content list (its rows keep no marker).
     */
    init {
        scope.launch { backfillFolderEntries() }
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
            onEvent("Could not keep access to that file.")
            false
        }

    private companion object {
        /** Log tag: folder read failures are attributed here (the failing uri + the exception). */
        private const val TAG = "LumiViewModel"
    }
}
