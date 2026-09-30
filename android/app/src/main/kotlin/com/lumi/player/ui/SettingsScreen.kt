package com.lumi.player.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lumi.player.data.AutoLockDelay
import com.lumi.player.data.AudioLevelMode
import com.lumi.player.data.AudioLevelSettings
import com.lumi.player.data.ContentSortDirection
import com.lumi.player.data.EntryEntity
import com.lumi.player.data.EntryType
import com.lumi.player.data.LoudnessStatus
import com.lumi.player.data.LumiThemeMode
import com.lumi.player.data.PlayMode
import com.lumi.player.data.TagEntity
import com.lumi.player.data.TagSongRow
import com.lumi.player.data.sortedTree
import com.lumi.player.data.tagSongPickerTree
import com.lumi.player.player.PlayerStateHolder

/** Which settings sub-screen the settings screen shows. */
private enum class SettingsPane {
    Menu,
    PlayMode,
    AudioLevel,
    Tags,
    Content,
    System,
    TagDetail,
    FolderDetail,
    EntryDetail,
    Appearance,
}

/**
 * Parent-facing settings, laid out like the polex settings menu: the settings
 * screen is a list of buttons, one per setting (play mode, NFC tags, content,
 * system); clicking a row navigates into that setting's own configuration
 * screen. The System page groups the device/display settings - keep screen
 * on, auto-lock after idle, and appearance (the last a row into its own
 * sub-screen, a child of the System page).
 *
 * Back navigation unwinds one level at a time: the tag detail page is a
 * child of the NFC tags pane and the folder detail page is a child of the
 * content pane, so each one's system back and back arrow take it back to
 * the list it lives under (and its removed-while-open fallback lands there
 * too); the song's profile unwinds to the pane its row was tapped in (the
 * folder's own page when opened from a folder's tree, the content pane
 * when opened from content); every other sub-screen's system back and back
 * arrow go back to the menu, and from the menu the host's back handling (the
 * NavHost in [LumiRoot]) returns to the player screen. The
 * [SettingsPane] state and the menu's scroll position live here at the top
 * level, so returning from a sub-screen lands in the menu at the exact scroll
 * position it was left at, with every dialog and selection intact.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: LumiViewModel,
    onBack: () -> Unit,
) {
    var pane by remember { mutableStateOf(SettingsPane.Menu) }
    // The menu list's scroll position lives at this level so leaving for a
    // sub-screen and coming back preserves it.
    val menuScroll = remember { ScrollState(0) }

    val context = LocalContext.current

    val tags by viewModel.observeTags().collectAsState(emptyList())
    val entries by viewModel.observeEntries().collectAsState(emptyList())
    // The individual content list: the folder-imported rows stay out of it
    // (they are visible only inside their folder's own page).
    val individualEntries by viewModel.observeIndividualEntries().collectAsState(emptyList())
    val folders by viewModel.observeFolders().collectAsState(emptyList())
    val playMode by viewModel.playMode.collectAsState(PlayMode.PROXIMITY)
    // The audio level (the menu's "Audio level" row; the service reads the
    // live value through the app's in-memory snapshot).
    val audioLevelMode by viewModel.audioLevelMode.collectAsState(AudioLevelMode.OFF)
    val audioLevelTargetLufs by viewModel.audioLevelTargetLufs
        .collectAsState(AudioLevelSettings.DEFAULT_TARGET_LUFS)
    // The audio level's measurement status (the pane's status row + the
    // "Normalize now" pass's live progress).
    val loudnessStatus by viewModel.loudnessStatus.collectAsState(LoudnessStatus(0, 0))
    val normalizing by viewModel.normalizing.collectAsState(false)
    val keepScreenOn by viewModel.keepScreenOn.collectAsState(false)
    val scanActive by viewModel.scanActive.collectAsState(false)
    val pendingTagProfile by viewModel.pendingTagProfile.collectAsState(null)
    val capturedUid by viewModel.capturedUid.collectAsState(null)
    val capturedTagData by viewModel.capturedTagData.collectAsState(null)
    // The content list's title sort direction (the content pane's sort
    // control; ascending by default).
    val contentSort by viewModel.contentSortDirection.collectAsState(ContentSortDirection.ASCENDING)
    // The app's appearance mode (the System page's "Appearance" row; the
    // theme follows it app-wide, effective immediately on change).
    val themeMode by viewModel.themeMode.collectAsState(LumiThemeMode.FOLLOW_SYSTEM)
    // The auto-lock idle delay (the System page's "Auto-lock after idle"
    // choice; off by default).
    val autoLockDelay by viewModel.autoLockDelay.collectAsState(AutoLockDelay.OFF)
    // Whether the app's screen-lock device admin is active (the System
    // page's enable prompt).
    val deviceAdminActive by viewModel.deviceAdminActive.collectAsState(false)
    // The song an assign-to-tag scan targets (the scan dialog's copy names
    // it; null for the NFC tags pane's plain scan).
    val scanAssignEntry by viewModel.scanAssignEntry.collectAsState(null)
    // The tag whose data a card-write scan will save to the tag's own
    // tag (the tag details page's "Save to NFC tag"; the scan dialog's
    // copy asks for the tag's own tag to hold).
    val cardWriteTarget by viewModel.cardWriteTarget.collectAsState(null)
    // The tag whose own tag a compare scan will read (the tag details
    // page's "Save to NFC tag" section's "Scan to compare"; the scan
    // dialog's copy asks for the tag's own tag to read).
    val cardCompareTarget by viewModel.cardCompareTarget.collectAsState(null)
    // The selected tag's live song list (the tag detail page's plumbing).
    val linked by viewModel.linkedEntries.collectAsState(emptyList())
    val folderRemoval by viewModel.folderRemovalPreview.collectAsState(null)
    // The selected folder's song list (the folder detail page's plumbing;
    // null while the folder's walk is in flight).
    val folderSongs by viewModel.folderSongs.collectAsState(null)

    var deleteTag by remember { mutableStateOf<TagEntity?>(null) }
    var deleteEntry by remember { mutableStateOf<EntryEntity?>(null) }
    // The tag detail page's per-song removal confirmation: the tag + the
    // row a row's remove action asked to drop (the tag's link to that one
    // song goes; the song stays in the content list - or the dead link
    // itself goes for a "Song not found" row).
    var removeSong by remember { mutableStateOf<Pair<TagEntity, TagSongRow>?>(null) }
    // The tag whose edit dialog is showing (set by the tag detail page's
    // edit action on the Tag details section).
    var editTag by remember { mutableStateOf<TagEntity?>(null) }

    // The tag whose detail page is showing (set when a tag row in the NFC
    // tags pane is tapped, or when a scan settles on a registered tag;
    // cleared when the tag is removed or the page leaves it).
    var tagDetailId by remember { mutableStateOf<Long?>(null) }

    // The folder whose detail page is showing (set when a folder row in the
    // content pane is tapped; cleared when the folder is removed or the
    // page leaves it).
    var folderDetailId by remember { mutableStateOf<Long?>(null) }

    // The folder pages' collapsed subfolders (the tree's show/hide state):
    // each remembered folder id maps to an observed set of the paths -
    // chains of name segments from the picked root - of the subfolders
    // whose rows are hidden. It lives here, like the pane state and the
    // menu scroll, so leaving a folder's page and coming back keeps its
    // tree exactly as it was left. (The sets are observed state: a toggle
    // must re-compose the page's tree.)
    val folderCollapsed = remember {
        mutableMapOf<Long, MutableState<Set<List<String>>>>()
    }

    // The entry whose profile page is showing (set when a song row in the
    // content pane or a folder page's song row is tapped; cleared when the
    // entry is removed or the page leaves it).
    var entryDetailId by remember { mutableStateOf<Long?>(null) }

    // The pane the song's profile was opened from: the back gesture
    // (system AND the in-pane arrow) unwinds the profile to ITS opener -
    // the folder's own page when the song was opened from that folder's
    // tree, the content pane when opened from the content pane's own rows -
    // instead of always the content pane. (A tag detail page that opens
    // the profile would record itself the same way.)
    var entryDetailOpener by remember { mutableStateOf<SettingsPane?>(null) }

    // The tag detail page's in-app add-song list: the tag the list is for,
    // while it is open. Saving the checked selection links each of the
    // app's existing content entries the user checked (individual or
    // folder-imported) to that tag - the list never launches the file
    // picker.
    var addSongPickerTagId by remember { mutableStateOf<Long?>(null) }

    // A settled scan hands its resolved (registered) tag to the detail
    // page (which overlays any pane); the result is consumed so a later
    // scan can re-trigger.
    LaunchedEffect(pendingTagProfile) {
        pendingTagProfile?.let {
            tagDetailId = it.id
            pane = SettingsPane.TagDetail
            viewModel.consumePendingTagProfile()
        }
    }

    // The settings menu's now-playing strip (D-06): the current entry and
    // the active play mode, so parents never have to go back to the player
    // to know what is playing. IDLE (no entry loaded) shows no strip.
    val playState by PlayerStateHolder.state.collectAsState()

    // The one and only file picker: the Content pane's "Add content" launches
    // it (a pick stores a new individual content row). The tag detail page's
    // add-song no longer uses it - it links the app's existing content
    // through the in-app list instead.
    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onFilePicked(viewModel, context, uri, EntryType.AUDIO)
    }

    // The folder picker is its own launcher: OpenDocumentTree is a different
    // contract from the shared OpenDocument above, so it owns its own request
    // key and callback. Only the Content pane's "Add folder" uses it: the
    // picked tree is granted read access and scanned recursively.
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) viewModel.addFolderEntry(uri)
    }

    // The device-admin enable launcher (the System page's "Enable screen
    // lock" action): launches the platform's own consent screen for the
    // app's screen-lock admin. The result is fed back to the view model,
    // which re-checks the admin's real state with the platform.
    val deviceAdminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onDeviceAdminResult(result.resultCode)
    }

    // System back must unwind the settings screen itself (one level at a
    // time) before the host can pop settings away entirely: the tag detail
    // page unwinds to the NFC tags list it lives under, the folder detail
    // page to the content pane it lives under, the appearance page to the
    // System page it lives under, the song's profile to the pane it was
    // opened from (the opener recorded when the row was tapped), and every
    // other sub-screen to the menu; only when the menu is showing is the
    // default back handler allowed through.
    BackHandler(enabled = pane != SettingsPane.Menu) {
        pane = when (pane) {
            SettingsPane.TagDetail -> SettingsPane.Tags
            SettingsPane.FolderDetail -> SettingsPane.Content
            SettingsPane.Appearance -> SettingsPane.System
            SettingsPane.EntryDetail -> {
                val opener = entryDetailOpener
                entryDetailOpener = null
                opener ?: SettingsPane.Content
            }
            else -> SettingsPane.Menu
        }
    }

    when (pane) {
        SettingsPane.Menu -> SettingsMenuPane(
            scrollState = menuScroll,
            playMode = playMode,
            nowPlayingLabel = playState.currentLabel,
            isPlaying = playState.isPlaying,
            tags = tags,
            entries = entries,
            audioLevelMode = audioLevelMode,
            audioLevelTargetLufs = audioLevelTargetLufs,
            onPlayMode = { pane = SettingsPane.PlayMode },
            onAudioLevel = { pane = SettingsPane.AudioLevel },
            onTags = { pane = SettingsPane.Tags },
            onContent = { pane = SettingsPane.Content },
            onSystem = { pane = SettingsPane.System },
            onBack = onBack,
        )
        SettingsPane.System -> SystemPane(
            viewModel = viewModel,
            keepScreenOn = keepScreenOn,
            onKeepScreenOnChange = { viewModel.setKeepScreenOn(it) },
            autoLockDelay = autoLockDelay,
            onAutoLockDelayChange = { viewModel.setAutoLockDelay(it) },
            deviceAdminActive = deviceAdminActive,
            onEnableDeviceAdmin = { deviceAdminLauncher.launch(viewModel.deviceAdminIntent()) },
            themeMode = themeMode,
            onAppearance = { pane = SettingsPane.Appearance },
            onBack = { pane = SettingsPane.Menu },
        )
        SettingsPane.PlayMode -> PlayModePane(
            viewModel = viewModel,
            playMode = playMode,
            onBack = { pane = SettingsPane.Menu },
        )
        SettingsPane.AudioLevel -> AudioLevelPane(
            viewModel = viewModel,
            mode = audioLevelMode,
            targetLufs = audioLevelTargetLufs,
            status = loudnessStatus,
            normalizing = normalizing,
            onBack = { pane = SettingsPane.Menu },
        )
        SettingsPane.Appearance -> AppearancePane(
            viewModel = viewModel,
            themeMode = themeMode,
            onBack = { pane = SettingsPane.System },
        )
        SettingsPane.Tags -> TagsPane(
            viewModel = viewModel,
            tags = tags,
            onBack = { pane = SettingsPane.Menu },
            onTagOpened = { tag ->
                tagDetailId = tag.id
                pane = SettingsPane.TagDetail
            },
        )
        SettingsPane.Content -> ContentPane(
            individualEntries = individualEntries,
            folders = folders,
            sortDirection = contentSort,
            onSortDirectionChange = { viewModel.setContentSortDirection(it) },
            onBack = { pane = SettingsPane.Menu },
            onAddContent = { audioPicker.launch(arrayOf("audio/*")) },
            onAddFolder = { folderPicker.launch(null) },
            onFolderOpened = { folder ->
                folderDetailId = folder.id
                pane = SettingsPane.FolderDetail
            },
            onPlayEntry = { viewModel.playEntry(it) },
            onEntryOpened = { entry ->
                entryDetailId = entry.id
                entryDetailOpener = SettingsPane.Content
                pane = SettingsPane.EntryDetail
            },
            onEntryDeleted = { deleteEntry = it },
        )
        SettingsPane.TagDetail -> {
            // The tag may have been removed while the page is open (the
            // danger zone's own confirm does this); when it is gone, fall
            // back to the NFC tags list the tag's row lived in rather than
            // hold a page for a tag that no longer exists.
            val tag = tags.firstOrNull { it.id == tagDetailId }
            if (tag == null) {
                LaunchedEffect(Unit) {
                    tagDetailId = null
                    pane = SettingsPane.Tags
                }
            } else {
                TagDetailPane(
                    viewModel = viewModel,
                    tag = tag,
                    linked = linked,
                    onBack = { pane = SettingsPane.Tags },
                    onAddSong = {
                        // The page's add-song opens the in-app content list
                        // (the app's existing content, linked - no picker).
                        addSongPickerTagId = tag.id
                    },
                    onEdit = { editTag = it },
                    onDelete = { deleteTag = it },
                    onRemoveSong = { entry -> removeSong = tag to entry },
                )
            }
        }
        SettingsPane.FolderDetail -> {
            // The folder may have been removed while the page is open (the
            // danger zone's own confirm does this); when it is gone, fall
            // back to the content pane the folder's row lived in rather than
            // hold a page for a folder that no longer exists.
            val folder = folders.firstOrNull { it.id == folderDetailId }
            if (folder == null) {
                LaunchedEffect(Unit) {
                    folderDetailId = null
                    pane = SettingsPane.Content
                }
            } else {
                FolderDetailPane(
                    viewModel = viewModel,
                    folder = folder,
                    songs = folderSongs,
                    sortDirection = contentSort,
                    playState = playState,
                    collapsed = folderCollapsed.getOrPut(folder.id) { mutableStateOf(emptySet()) }.value,
                    onBack = { pane = SettingsPane.Content },
                    onRescan = { viewModel.rescanFolder(folder) },
                    onRemove = { viewModel.requestFolderRemoval(folder) },
                    onToggleCollapsed = { path ->
                        val state = folderCollapsed.getOrPut(folder.id) { mutableStateOf(emptySet()) }
                        // A plain mutable copy (the set operators resolve
                        // oddly against the pruned stdlib mirror here):
                        // remove the path if present, add it otherwise.
                        val next = java.util.HashSet<List<String>>(state.value)
                        if (!next.remove(path)) next.add(path)
                        state.value = next
                    },
                    onEntryOpened = { entry ->
                        entryDetailId = entry.id
                        entryDetailOpener = SettingsPane.FolderDetail
                        pane = SettingsPane.EntryDetail
                    },
                    onPlayEntry = { viewModel.playEntry(it) },
                    onTogglePlayPause = { viewModel.toggleCurrentPlayback() },
                )
            }
        }
        SettingsPane.EntryDetail -> {
            // The entry may have been removed while the page is open (the
            // content pane's row-level delete does this); when it is gone,
            // fall back to the pane the song was opened from rather than
            // hold a page for a song that no longer exists.
            val entry = entries.firstOrNull { it.id == entryDetailId }
            if (entry == null) {
                LaunchedEffect(Unit) {
                    entryDetailId = null
                    val opener = entryDetailOpener
                    entryDetailOpener = null
                    pane = opener ?: SettingsPane.Content
                }
            } else {
                EntryDetailPane(
                    viewModel = viewModel,
                    entry = entry,
                    onBack = {
                        // The in-pane arrow unwinds the same way the system
                        // back does: to the pane the song was opened from.
                        val opener = entryDetailOpener
                        entryDetailOpener = null
                        pane = opener ?: SettingsPane.Content
                    },
                )
            }
        }
    }

    // ---------- dialogs (overlay any pane) ----------

    // The tag detail page's in-app add-song list (see [addSongPickerTagId]):
    // the app's existing content laid out as the content folder pages' tree
    // (the individual songs at the top level, each added folder a
    // top-level folder row carrying its own imported structure - the
    // tree's left-side connector lines and the folder rows' show/hide),
    // the content sort's per-level order applied, a duplicate label
    // offered once (its first stored copy). The user checks the songs to
    // add and saves the selection at once - every checked song links
    // through the existing single-song link path (one link per checked
    // song, in the tree's render order, each committed before the next),
    // and the dialog closes. Cancelling or dismissing saves nothing.
    addSongPickerTagId?.let { id ->
        val tag = tags.firstOrNull { t -> t.id == id }
        if (tag != null) {
            val tree = tagSongPickerTree(entries, folders).sortedTree(contentSort)
            AddSongPickerDialog(
                tree = tree,
                alreadyOnTagLabels = linked.map { it.name }.toSet(),
                onSave = { selected ->
                    // The Save commits the whole checked selection at once:
                    // each song links through the existing single-song link
                    // path, in the tree's render order (each write before
                    // the next), and the dialog closes.
                    viewModel.linkEntriesToTag(tag, selected)
                    addSongPickerTagId = null
                },
                onDismiss = { addSongPickerTagId = null },
            )
        }
    }

    // The unified scan in progress: any NFC tag - a registered one opens
    // its profile page, an unregistered one is registered. A scan-to-assign
    // (the song profile's own scan) instead links its target song to the
    // scanned tag, and the copy names the song it will assign. A card-write
    // scan (the tag details page's "Save to NFC tag") writes the tag's
    // data to the presented tag only when its NFC ID matches the tag's,
    // and a compare scan (the section's "Scan to compare") reads the
    // presented tag's own contents the same way - the copy asks for the
    // tag's own tag in both cases.
    if (scanActive) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelTagScan() },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(28.dp),
            icon = { NfcAnchor() },
            title = {
                Text(
                    "Scan a tag",
                    style = MaterialTheme.typography.headlineSmall,
                )
            },
            text = {
                val target = scanAssignEntry
                Text(
                    when {
                        target != null -> "Hold the back of the phone against any NFC tag to add “${target.label}” to it."
                        cardCompareTarget != null -> "Hold the tag's own tag - the tag whose NFC ID matches the tag's - against the back of the phone to read what it carries. A tag with a different NFC ID is not recorded."
                        cardWriteTarget != null -> "Hold the tag's own tag - the tag whose NFC ID matches the tag's - against the back of the phone to save this tag's data to it. A tag with a different NFC ID is not written."
                        else -> "Hold the back of the phone against any NFC tag…"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.cancelTagScan() }) { Text("Cancel") }
            },
        )
    }

    if (capturedUid != null) {
        TagNameDialog(
            uid = capturedUid ?: "",
            prefill = capturedTagData,
            onDismiss = { viewModel.discardCaptured() },
            onSave = { name -> viewModel.confirmTagName(name) },
        )
    }

    // The tag's edit dialog (the tag detail page's edit action): the name
    // is the only stored detail - the UID is hardware-derived and stays
    // read-only. Saving goes through the view model's rename path, so the
    // page's displayed name follows the saved value.
    editTag?.let { tag ->
        TagEditDialog(
            uid = tag.uid,
            initialName = tag.name,
            onDismiss = { editTag = null },
            onSave = { name ->
                viewModel.renameTag(tag, name)
                editTag = null
            },
        )
    }

    deleteTag?.let { tag ->
        AlertDialog(
            onDismissRequest = { deleteTag = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(28.dp),
            title = {
                Text("Remove tag?", style = MaterialTheme.typography.headlineSmall)
            },
            text = { Text("“${tag.name.ifEmpty { tag.uid }}” and its links will be removed.") },
            confirmButton = {
                DestructivePillButton(
                    label = "Remove",
                    onClick = {
                        viewModel.removeTag(tag)
                        deleteTag = null
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { deleteTag = null }) { Text("Cancel") }
            },
        )
    }

    deleteEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteEntry = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(28.dp),
            title = {
                Text("Remove content?", style = MaterialTheme.typography.headlineSmall)
            },
            text = {
                // The tag's link to the entry SURVIVES the removal (tag_links
                // carries no foreign key to entries - the song's name snapshot
                // is what the tag keeps), so the dialog states the real
                // effect: the song leaves the content list and holds its
                // place on the tag's list as a "song not found" row.
                Text(
                    "“${entry.label}” will be removed from the app. " +
                        "Tags that play it keep a “song not found” row in its place.",
                )
            },
            confirmButton = {
                DestructivePillButton(
                    label = "Remove",
                    onClick = {
                        viewModel.removeEntry(entry)
                        deleteEntry = null
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { deleteEntry = null }) { Text("Cancel") }
            },
        )
    }

    // The tag detail page's per-song removal confirmation: the effect is
    // stated before the destructive confirm - the tag's link to that one
    // song goes; the song stays in the content list (for a "song not
    // found" row - a name that resolves to no content - the dead link
    // itself goes). The name quoted is the row's own (a missing row's
    // snapshot name, a live row's current label).
    removeSong?.let { (tag, row) ->
        val missing = row.entry == null
        // A blank snapshot name (a link stored before the name column
        // existed, whose song is gone) quotes the generic marker.
        val songName = row.name.ifBlank { "Song not found" }
        AlertDialog(
            onDismissRequest = { removeSong = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(28.dp),
            title = {
                Text("Remove song from the tag?", style = MaterialTheme.typography.headlineSmall)
            },
            text = {
                Text(
                    if (missing) {
                        "“$songName” will be removed from ${tag.name.ifEmpty { tag.uid }}. " +
                            "The song is already gone from the content list."
                    } else {
                        "“$songName” will be removed from ${tag.name.ifEmpty { tag.uid }}. " +
                            "It stays in the content list."
                    },
                )
            },
            confirmButton = {
                DestructivePillButton(
                    label = "Remove",
                    onClick = {
                        viewModel.unlinkSongFromTag(tag, row)
                        removeSong = null
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { removeSong = null }) { Text("Cancel") }
            },
        )
    }

    // The folder-removal confirmation: the async walk identifies the
    // imported songs (the rescan precedent) before the dialog appears, so
    // the copy states the song count - or plainly that it cannot be
    // determined when the tree can no longer be read. The destructive
    // confirm names the effect: the folder and its imported songs leave
    // the app's content list; the music files on the phone are untouched.
    folderRemoval?.let { preview ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelFolderRemoval() },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(28.dp),
            title = {
                Text("Remove folder?", style = MaterialTheme.typography.headlineSmall)
            },
            text = { Text(folderRemovalText(preview)) },
            confirmButton = {
                DestructivePillButton(
                    label = "Remove",
                    onClick = { viewModel.confirmFolderRemoval() },
                )
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelFolderRemoval() }) { Text("Cancel") }
            },
        )
    }
}

/**
 * The folder-removal confirmation copy: the effect is stated before the
 * destructive confirm - the folder and the songs imported from it leave the
 * app's content list, and the music files on the phone are not touched. When
 * the tree can no longer be read the count cannot be determined and the
 * confirmation says so plainly: the folder row is still removed, and the
 * songs that can no longer be identified stay.
 */
private fun folderRemovalText(preview: LumiViewModel.FolderRemovalPreview): String {
    val label = preview.folder.label
    return when (val count = preview.songCount) {
        null -> "That folder's songs can no longer be read, so the songs imported from it can't be identified and will stay in the content list. “$label” will be removed from the folder list. The music files themselves stay on the phone."
        0 -> "“$label” has no imported songs in the content list. It will be removed from the folder list. The music files themselves stay on the phone."
        else -> "“$label” and its $count imported song${if (count == 1) "" else "s"} will be removed from the content list. The music files themselves stay on the phone."
    }
}

private fun onFilePicked(
    viewModel: LumiViewModel,
    context: Context,
    uri: Uri,
    type: EntryType,
) {
    viewModel.addFileEntry(uri, type, displayNameFor(context, uri))
}

/** The file's display name from its content URI, or null when unknown. */
private fun displayNameFor(context: Context, uri: Uri): String? =
    context.contentResolver
        .query(uri, arrayOf("_display_name"), null, null, null)
        ?.use { cursor ->
            val index = cursor.getColumnIndex("_display_name")
            if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
        }
