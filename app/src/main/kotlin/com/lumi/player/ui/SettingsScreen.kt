package com.lumi.player.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.ModeEdit
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumi.player.BuildConfig
import com.lumi.player.data.EntryEntity
import com.lumi.player.data.EntryType
import com.lumi.player.data.ContentSortDirection
import com.lumi.player.data.FolderEntity
import com.lumi.player.data.FolderTreeLevel
import com.lumi.player.data.PlayMode
import com.lumi.player.data.TagEntity
import com.lumi.player.data.TagSongOption
import com.lumi.player.data.formatDuration
import com.lumi.player.data.folderTreeStructure
import com.lumi.player.data.sortedByTitle
import com.lumi.player.data.sortedTree
import com.lumi.player.data.tagSongOptions
import com.lumi.player.nfc.LumiTagData
import com.lumi.player.nfc.LumiTagNdef
import com.lumi.player.player.PlayerStateHolder
import kotlin.math.roundToInt

/** Which settings sub-screen the settings screen shows. */
private enum class SettingsPane {
    Menu,
    PlayMode,
    Tags,
    Content,
    TagDetail,
    FolderDetail,
    EntryDetail,
}

/**
 * Parent-facing settings, laid out like the polex settings menu: the settings
 * screen is a list of buttons, one per setting (play mode, NFC tags, content,
 * keep screen on); clicking a row navigates into that setting's own
 * configuration screen.
 *
 * Back navigation unwinds one level at a time: the tag detail page is a
 * child of the NFC tags pane, and the folder detail page is a child of the
 * content pane, so each one's system back and back arrow take it back to
 * the list it lives under (and its removed-while-open fallback lands there
 * too); every other sub-screen's system back and back arrow go back to the
 * menu, and from the menu the host's back handling (the NavHost in [LumiRoot])
 * returns to the player screen. The
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
    val playMode by viewModel.playMode.collectAsState(PlayMode.CONTINUOUS)
    val keepScreenOn by viewModel.keepScreenOn.collectAsState(false)
    val scanActive by viewModel.scanActive.collectAsState(false)
    val pendingTagProfile by viewModel.pendingTagProfile.collectAsState(null)
    val capturedUid by viewModel.capturedUid.collectAsState(null)
    val capturedTagData by viewModel.capturedTagData.collectAsState(null)
    val capturedTagWritable by viewModel.capturedTagWritable.collectAsState(false)
    // The content list's title sort direction (the content pane's sort
    // control; descending by default).
    val contentSort by viewModel.contentSortDirection.collectAsState(ContentSortDirection.DESCENDING)
    // The song an assign-to-tag scan targets (the scan dialog's copy names
    // it; null for the NFC tags pane's plain scan).
    val scanAssignEntry by viewModel.scanAssignEntry.collectAsState(null)
    // The selected tag's live song list (the tag detail page's plumbing).
    val linked by viewModel.linkedEntries.collectAsState(emptyList())
    val folderRemoval by viewModel.folderRemovalPreview.collectAsState(null)
    // The selected folder's song list (the folder detail page's plumbing;
    // null while the folder's walk is in flight).
    val folderSongs by viewModel.folderSongs.collectAsState(null)

    var deleteTag by remember { mutableStateOf<TagEntity?>(null) }
    var deleteEntry by remember { mutableStateOf<EntryEntity?>(null) }
    // The tag detail page's per-song removal confirmation: the tag + the
    // song a row's remove action asked to drop (the tag's link to that one
    // song goes; the song stays in the content list).
    var removeSong by remember { mutableStateOf<Pair<TagEntity, EntryEntity>?>(null) }
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

    // The entry whose profile page is showing (set when a song row in the
    // content pane or a folder page's song row is tapped; cleared when the
    // entry is removed or the page leaves it).
    var entryDetailId by remember { mutableStateOf<Long?>(null) }

    // The tag detail page's in-app add-song list: the tag the list is for,
    // while it is open. A pick links one of the app's existing content
    // entries (individual or folder-imported) to that tag - the list
    // never launches the file picker.
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

    // System back must unwind the settings screen itself (one level at a
    // time) before the host can pop settings away entirely: the tag detail
    // page unwinds to the NFC tags list it lives under, the folder detail
    // page to the content pane it lives under, and every other sub-screen
    // to the menu; only when the menu is showing is the default back
    // handler allowed through.
    BackHandler(enabled = pane != SettingsPane.Menu) {
        pane = when (pane) {
            SettingsPane.TagDetail -> SettingsPane.Tags
            SettingsPane.FolderDetail -> SettingsPane.Content
            SettingsPane.EntryDetail -> SettingsPane.Content
            else -> SettingsPane.Menu
        }
    }

    when (pane) {
        SettingsPane.Menu -> SettingsMenuPane(
            scrollState = menuScroll,
            playMode = playMode,
            keepScreenOn = keepScreenOn,
            onKeepScreenOnChange = { viewModel.setKeepScreenOn(it) },
            nowPlayingLabel = playState.currentLabel,
            isPlaying = playState.isPlaying,
            tags = tags,
            entries = entries,
            onPlayMode = { pane = SettingsPane.PlayMode },
            onTags = { pane = SettingsPane.Tags },
            onContent = { pane = SettingsPane.Content },
            onBack = onBack,
        )
        SettingsPane.PlayMode -> PlayModePane(
            viewModel = viewModel,
            playMode = playMode,
            onBack = { pane = SettingsPane.Menu },
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
            onPlayFolder = { viewModel.playFolder(it) },
            onPlayEntry = { viewModel.playEntry(it) },
            onEntryOpened = { entry ->
                entryDetailId = entry.id
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
                    onBack = { pane = SettingsPane.Content },
                    onRescan = { viewModel.rescanFolder(folder) },
                    onRemove = { viewModel.requestFolderRemoval(folder) },
                    onEntryOpened = { entry ->
                        entryDetailId = entry.id
                        pane = SettingsPane.EntryDetail
                    },
                )
            }
        }
        SettingsPane.EntryDetail -> {
            // The entry may have been removed while the page is open (the
            // content pane's row-level delete does this); when it is gone,
            // fall back to the content pane the song's row lived in rather
            // than hold a page for a song that no longer exists.
            val entry = entries.firstOrNull { it.id == entryDetailId }
            if (entry == null) {
                LaunchedEffect(Unit) {
                    entryDetailId = null
                    pane = SettingsPane.Content
                }
            } else {
                EntryDetailPane(
                    viewModel = viewModel,
                    entry = entry,
                    onBack = { pane = SettingsPane.Content },
                )
            }
        }
    }

    // ---------- dialogs (overlay any pane) ----------

    // The tag detail page's in-app add-song list (see [addSongPickerTagId]).
    addSongPickerTagId?.let { id ->
        val tag = tags.firstOrNull { t -> t.id == id }
        if (tag != null) {
            AddSongPickerDialog(
                options = tagSongOptions(entries, linked),
                onPick = { option ->
                    viewModel.linkEntryToTag(tag, option.entry)
                    // A fresh link closes the list; a song the tag already
                    // plays keeps it open (the view model reports the pick
                    // with a toast instead of a duplicate link).
                    if (!option.alreadyOnTag) addSongPickerTagId = null
                },
                onDismiss = { addSongPickerTagId = null },
            )
        }
    }

    // The unified scan in progress: any NFC tag - a registered one opens
    // its profile page, an unregistered one is registered. A scan-to-assign
    // (the song profile's own scan) instead links its target song to the
    // scanned tag, and the copy names the song it will assign.
    if (scanActive) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelTagScan() },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(28.dp),
            icon = { NfcAnchor() },
            title = {
                Text("Scan a tag", style = MaterialTheme.typography.headlineSmall)
            },
            text = {
                val target = scanAssignEntry
                Text(
                    if (target != null) {
                        "Hold the back of the phone against any NFC tag to add “${target.label}” to it."
                    } else {
                        "Hold the back of the phone against any NFC tag…"
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
            writable = capturedTagWritable,
            defaultMode = playMode,
            onDismiss = { viewModel.discardCaptured() },
            onSave = { name, mode -> viewModel.confirmTagName(name, mode, writeToTag = false) },
            onSaveAndWrite = { name, mode -> viewModel.confirmTagName(name, mode, writeToTag = true) },
        )
    }

    // The tag's edit dialog (the tag detail page's edit action): the name
    // is the only stored detail - the UID is hardware-derived and stays
    // read-only. Saving goes through the view model's rename path, the same
    // one the tag payload write uses.
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
            text = { Text("“${entry.label}” will be removed from the app and from every tag.") },
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
    // song goes; the song stays in the content list.
    removeSong?.let { (tag, entry) ->
        AlertDialog(
            onDismissRequest = { removeSong = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(28.dp),
            title = {
                Text("Remove song from the tag?", style = MaterialTheme.typography.headlineSmall)
            },
            text = {
                Text(
                    "“${entry.label}” will be removed from ${tag.name.ifEmpty { tag.uid }}. " +
                        "It stays in the content list.",
                )
            },
            confirmButton = {
                DestructivePillButton(
                    label = "Remove",
                    onClick = {
                        viewModel.unlinkEntryFromTag(tag, entry)
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

// ---------- the menu (one button per setting) ----------

/**
 * The settings menu: one clickable row per setting plus an app-version
 * footer. Clicking a row navigates into that setting's configuration screen.
 * The now-playing strip (D-06) tops the list while an entry is loaded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsMenuPane(
    scrollState: ScrollState,
    playMode: PlayMode,
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    nowPlayingLabel: String?,
    isPlaying: Boolean,
    tags: List<TagEntity>,
    entries: List<EntryEntity>,
    onPlayMode: () -> Unit,
    onTags: () -> Unit,
    onContent: () -> Unit,
    onBack: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Settings", style = MaterialTheme.typography.titleLarge)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (nowPlayingLabel != null) {
                NowPlayingStrip(
                    label = nowPlayingLabel,
                    isPlaying = isPlaying,
                    mode = playMode,
                )
            }
            MenuRow(
                icon = Icons.Default.PlayArrow,
                chipBackground = scheme.primaryContainer,
                chipContent = scheme.onPrimaryContainer,
                title = "Play mode",
                subtitle = when (playMode) {
                    PlayMode.CONTINUOUS -> "Continuous"
                    PlayMode.PROXIMITY -> "Proximity"
                },
                onClick = onPlayMode,
            )
            MenuRow(
                icon = Icons.Filled.Nfc,
                chipBackground = scheme.secondaryContainer,
                chipContent = scheme.onSecondaryContainer,
                title = "NFC tags",
                subtitle = if (tags.isEmpty()) "No tags yet" else "${tags.size} registered",
                onClick = onTags,
            )
            MenuRow(
                icon = Icons.Filled.Folder,
                chipBackground = scheme.tertiaryContainer,
                chipContent = scheme.onTertiaryContainer,
                title = "Content",
                subtitle = if (entries.isEmpty()) "No songs yet" else "${entries.size} songs",
                onClick = onContent,
            )
            KeepScreenOnRow(
                checked = keepScreenOn,
                onCheckedChange = onKeepScreenOnChange,
            )
            Text(
                text = "lumi v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A settings button: a tonal icon chip, title, subtitle, chevron — a quiet
 * color-coding of the app's jobs (D-03): play = peach, tags = teal,
 * content = violet. 56+ dp min height, 48 dp targets (D-08).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuRow(
    icon: ImageVector,
    chipBackground: Color,
    chipContent: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconChip(
                icon = icon,
                background = chipBackground,
                content = chipContent,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/**
 * The keep-screen-on toggle: a checkbox row instead of the menu's chevron
 * rows, because the setting is a single on/off choice with no configuration
 * sub-screen.
 */
@Composable
private fun KeepScreenOnRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconChip(
                icon = Icons.Filled.BrightnessHigh,
                background = scheme.primaryContainer,
                content = scheme.onPrimaryContainer,
                size = 36.dp,
                iconSize = 18.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Keep screen on", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Keep the display on while the app is open.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.testTag("keep-screen-on"),
            )
        }
    }
}

/**
 * The now-playing strip (D-06): a one-line context reminder at the top of
 * the settings menu — what's playing, whether it is, and in which mode.
 */
@Composable
private fun NowPlayingStrip(label: String, isPlaying: Boolean, mode: PlayMode) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainerLow, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconChip(
            icon = Icons.Filled.MusicNote,
            background = scheme.primaryContainer,
            content = scheme.onPrimaryContainer,
            size = 36.dp,
            iconSize = 19.dp,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(if (isPlaying) scheme.secondary else scheme.outlineVariant, CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .background(scheme.secondaryContainer, RoundedCornerShape(500.dp))
                .padding(horizontal = 9.dp, vertical = 4.dp),
        ) {
            Text(
                text = when (mode) {
                    PlayMode.CONTINUOUS -> "CONTINUOUS"
                    PlayMode.PROXIMITY -> "PROXIMITY"
                },
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSecondaryContainer,
                letterSpacing = 0.5.sp,
            )
        }
    }
}

/** A tonal icon chip: the settled treatment for list/menu row icons (D-03). */
@Composable
private fun IconChip(
    icon: ImageVector,
    background: Color,
    content: Color,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .background(background, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = content,
        )
    }
}

// ---------- sub-screens (one per setting) ----------

/** Shared top bar for the settings sub-screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaneTopAppBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
    )
}

/** Play mode: pick continuous or proximity behaviour. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayModePane(
    viewModel: LumiViewModel,
    playMode: PlayMode,
    onBack: () -> Unit,
) {
    Scaffold(topBar = { PaneTopAppBar("Play mode", onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard("Play mode") {
                ModeRow(
                    title = "Continuous",
                    detail = "Keeps playing until you tap a different tag.",
                    selected = playMode == PlayMode.CONTINUOUS,
                    onSelect = { viewModel.setPlayMode(PlayMode.CONTINUOUS) },
                )
                ModeRow(
                    title = "Proximity",
                    detail = "Plays while the tag stays near; pauses when it's removed and resumes when you read it again.",
                    selected = playMode == PlayMode.PROXIMITY,
                    onSelect = { viewModel.setPlayMode(PlayMode.PROXIMITY) },
                )
            }
        }
    }
}

/**
 * NFC tags: the pane's single scan entry point sits ABOVE the tag list as
 * a prominent action (a filled button with the NFC icon, not a row of the
 * list): arming it, the next tag read either opens a registered tag's
 * profile page (the per-tag page where the tag can be renamed, given
 * songs, or deleted from its danger zone) or takes an unregistered tag
 * into the registration flow (capture, naming dialog, optional
 * write-to-tag). Tapping a tag row's body opens the tapped tag's detail
 * page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagsPane(
    viewModel: LumiViewModel,
    tags: List<TagEntity>,
    onBack: () -> Unit,
    onTagOpened: (TagEntity) -> Unit,
) {
    Scaffold(topBar = { PaneTopAppBar("NFC tags", onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // The single scan entry point: a prominent filled action (NFC
            // icon + label) above the tag list, separated from it by the
            // column's spacing - it reads as the pane's action, not as
            // another row of the list. It arms the unified scan (the same
            // read path playback uses: the foreground-dispatch read into
            // LumiViewModel.onTagDetected).
            Button(
                onClick = { viewModel.startTagScan() },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            ) {
                Icon(
                    Icons.Filled.Nfc,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text("Scan NFC", style = MaterialTheme.typography.titleMedium)
            }
            SectionCard(title = "NFC tags") {
                if (tags.isEmpty()) {
                    Text(
                        text = "No tags yet. Scan a tag to add it.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                tags.forEach { tag ->
                    SettingRow(
                        // The row body is the detail page's entry point.
                        modifier = Modifier.clickable { onTagOpened(tag) },
                        leading = {
                            IconChip(
                                icon = Icons.Filled.Label,
                                background = MaterialTheme.colorScheme.secondaryContainer,
                                content = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        },
                        title = tag.name.ifEmpty { "Tag" },
                        detail = tag.uid,
                    )
                }
            }
        }
    }
}

/**
 * One registered tag, opened by tapping its row in the NFC tags pane. The
 * page shows the tag's details (name + UID) with an edit action that opens
 * the edit dialog (the name is the only editable detail; the UID is
 * hardware-derived and read-only), its current song list - in the order the
 * songs were added, reorderable by dragging a row's handle, and each row
 * individually removable (the row's remove action unlinks that ONE song
 * from THIS tag - the song stays in the content list) - with an add-song
 * action aimed at THIS tag, and ends in a danger zone whose delete removes
 * the tag - and only the tag and its links.
 *
 * The song list reuses the shared selection plumbing: opening the page
 * selects the tag, so [linked] (the selected tag's entries) is this tag's
 * list. The add-song action opens the in-app content list, where a pick
 * links an already-stored content entry to the tag
 * ([LumiViewModel.linkEntryToTag]) - no file picker, no duplicated song.
 * A completed drag reorders the list and persists it through
 * [LumiViewModel.reorderTagSongs] - the play order is the link order, so
 * the persisted order is the play order. A row's remove action confirms
 * ([LumiViewModel.unlinkEntryFromTag], the inverse of the add-song link)
 * and drops only that tag's link to that one song: the song stays in the
 * content list, and if the list empties the page falls back to the
 * "This tag plays nothing yet." state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagDetailPane(
    viewModel: LumiViewModel,
    tag: TagEntity,
    linked: List<EntryEntity>,
    onBack: () -> Unit,
    onAddSong: () -> Unit,
    onEdit: (TagEntity) -> Unit,
    onDelete: (TagEntity) -> Unit,
    onRemoveSong: (EntryEntity) -> Unit,
) {
    // Pointing the shared selection at this tag makes the observed [linked]
    // list this tag's song list.
    LaunchedEffect(tag.id) { viewModel.selectTag(tag.id) }
    val scheme = MaterialTheme.colorScheme
    Scaffold(topBar = { PaneTopAppBar(tag.name.ifEmpty { "Tag" }, onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // The edit action sits on the section's title row (the settled
            // title-row action language, like the Songs section's add
            // button) and opens the edit dialog: the name is the only
            // stored detail, the UID is hardware-derived and read-only.
            SectionCard(
                title = "Tag details",
                trailing = {
                    SoftIconButton(
                        icon = Icons.Filled.ModeEdit,
                        contentDescription = "Edit tag",
                        onClick = { onEdit(tag) },
                    )
                },
            ) {
                SettingRow(
                    leading = {
                        IconChip(
                            icon = Icons.Filled.Label,
                            background = scheme.secondaryContainer,
                            content = scheme.onSecondaryContainer,
                        )
                    },
                    title = tag.name.ifEmpty { "Tag" },
                    detail = tag.uid,
                )
            }
            // The + button on this section's title row opens the in-app
            // content list: the pick links one of the app's existing content
            // entries (individual or folder-imported) to THIS tag - no file
            // picker, so nothing is stored twice. The rows keep the settled
            // row presentation (icon chip, label, detail) and end in a drag
            // handle that reorders the list (the persisted link order - the
            // play order - follows the drag).
            SectionCard(
                title = "Songs",
                addLabel = "Add song",
                onAdd = { onAddSong() },
            ) {
                if (linked.isEmpty()) {
                    Text(
                        text = "This tag plays nothing yet.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    ReorderableSongList(
                        songs = linked,
                        onReordered = { viewModel.reorderTagSongs(tag, it) },
                        onRemoveSong = onRemoveSong,
                    )
                }
            }
            // The danger zone: a visually separated, clearly destructive
            // section. The detail copy states the effect the confirm will
            // carry out - the tag and its song links go; the songs stay in
            // the content list (deleteTag never deletes entries).
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = scheme.errorContainer),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Danger zone",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onErrorContainer,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconChip(
                            icon = Icons.Default.Delete,
                            background = scheme.error,
                            content = scheme.onError,
                            size = 36.dp,
                            iconSize = 19.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Delete tag",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                color = scheme.onErrorContainer,
                            )
                            Text(
                                "Removes the tag and its song links. Its songs stay in the content list.",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onErrorContainer,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        DestructivePillButton("Delete", onClick = { onDelete(tag) })
                    }
                }
            }
        }
    }
}

/**
 * The tag detail page's song list, reorderable by dragging: each row ends in
 * a remove action (the row-level delete, which confirms and unlinks that ONE
 * song from THIS tag - the song stays in the content list) and a drag handle
 * that is pressed and dragged vertically. The two are separate regions, so
 * the remove never fights the drag gesture. While the drag is
 * held, the list previews the new order live - the held row follows the
 * pointer and swaps places as it crosses a row boundary - and a completed
 * drag persists the order (the tag links' sortOrder) through [onReordered].
 * A drag that ends where it started - or a plain tap on the handle -
 * persists nothing.
 *
 * The row pitch is measured from the laid-out rows (the gap between the tops
 * of the first two rows), so the pointer math tracks the layout instead of
 * assuming a row height. The page's vertical scroll never fights the drag:
 * once a move has started on the handle it is consumed by the drag, so a
 * drag scrolls nothing; the handle is the only row region that captures the
 * pointer, so a scroll started elsewhere on the page is untouched.
 */
@Composable
private fun ReorderableSongList(
    songs: List<EntryEntity>,
    onReordered: (List<EntryEntity>) -> Unit,
    onRemoveSong: (EntryEntity) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // The observed list, mirrored into state: the drag's pointer closure
    // outlives recompositions, so it must read the CURRENT observed list
    // through state - a plain capture of the [songs] parameter would go
    // stale (e.g. after a song is added) and a drag would reorder against
    // a list that is no longer the one on screen.
    var observed by remember { mutableStateOf(songs) }
    LaunchedEffect(songs) { observed = songs }

    // The in-flight drag: the row being held, the order it is previewing, the
    // held row's offset past its last crossed boundary, and the indices the
    // drag started at and currently sits at (-1 while no drag is in flight).
    var draggedId by remember { mutableStateOf<Long?>(null) }
    var order by remember { mutableStateOf<List<EntryEntity>>(emptyList()) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    var dragCurrentIndex by remember { mutableIntStateOf(-1) }
    // The order the last completed drag wrote, shown until the observed
    // list echoes it back - so releasing a drag never snaps the list back
    // to the pre-drag order for a frame while the write lands. The
    // baseline is the list the drag previewed from (captured when the
    // drag's snapshot is taken): an observed list that settles to
    // something that is neither the baseline nor the pending order means
    // the write did not land as dragged (the list changed in flight),
    // and the provisional order is dropped so the page shows the
    // observed list.
    var pending by remember { mutableStateOf<List<EntryEntity>?>(null) }
    var pendingBaseline by remember { mutableStateOf<List<EntryEntity>?>(null) }
    LaunchedEffect(pending, pendingBaseline, observed) {
        val pendingOrder = pending
        if (pendingOrder != null) {
            val settled = observed
            if (settled != pendingOrder && settled != pendingBaseline) {
                pending = null
                pendingBaseline = null
            }
        }
    }

    // The measured row tops (root coordinates) and the row pitch - the gap
    // between the first two laid-out rows, which scrolling cannot distort -
    // both state so the pointer closure reads the current layout.
    val rowTops = remember { mutableMapOf<Long, Float>() }
    val pitch = remember { mutableFloatStateOf(0f) }

    fun displayedList(): List<EntryEntity> {
        val pendingOrder = pending
        return when {
            draggedId != null -> order
            pendingOrder != null && pendingOrder != observed -> pendingOrder
            else -> observed
        }
    }
    val shown = displayedList()

    fun handleDragged(entryId: Long, totalDeltaY: Float) {
        val pitchPx = pitch.floatValue
        if (pitchPx <= 0f) return
        val shown = displayedList()
        if (dragStartIndex < 0) {
            // First move of the drag: snapshot the order (the baseline the
            // pending order is echoed against) and mark the row.
            val start = shown.indexOfFirst { it.id == entryId }
            if (start < 0) return
            dragStartIndex = start
            dragCurrentIndex = start
            order = shown
            pendingBaseline = shown
            draggedId = entryId
        }
        val target = (dragStartIndex + (totalDeltaY / pitchPx).roundToInt())
            .coerceIn(0, shown.size - 1)
        if (target != dragCurrentIndex) {
            order = order.moved(dragCurrentIndex, target)
            dragCurrentIndex = target
        }
        dragOffsetPx = totalDeltaY - (dragCurrentIndex - dragStartIndex) * pitchPx
    }

    fun handleDragEnded() {
        if (dragStartIndex < 0) return
        if (dragCurrentIndex != dragStartIndex) {
            pending = order
            onReordered(order)
        }
        draggedId = null
        dragOffsetPx = 0f
        dragStartIndex = -1
        dragCurrentIndex = -1
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        shown.forEach { entry ->
            val isDragging = entry.id == draggedId
            SettingRow(
                leading = {
                    IconChip(
                        icon = EntryPresentation.icon(EntryType.fromStorage(entry.type)),
                        background = scheme.primaryContainer,
                        content = scheme.onPrimaryContainer,
                    )
                },
                title = entry.label,
                detail = detailFor(entry.type, entry.payload),
                modifier = Modifier
                    .graphicsLayer {
                        if (isDragging) translationY = dragOffsetPx
                    }
                    .onGloballyPositioned { coords ->
                        rowTops[entry.id] = coords.localToRoot(Offset.Zero).y
                        val tops = shown.mapNotNull { rowTops[it.id] }
                        if (draggedId == null && tops.size >= 2) {
                            pitch.floatValue = (tops[1] - tops[0]).coerceAtLeast(0f)
                        }
                    },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // The per-song removal (the content pane's row-level
                        // delete language): the tag's link to this one song
                        // goes; the song stays in the content list. A
                        // separate region from the drag handle, so the
                        // remove never fights the drag gesture.
                        SoftIconButton(
                            icon = Icons.Default.Delete,
                            contentDescription = "Remove ${entry.label} from the tag",
                            onClick = { onRemoveSong(entry) },
                        )
                        Spacer(Modifier.width(8.dp))
                        SongDragHandle(
                            entryId = entry.id,
                            contentDescription = "Reorder ${entry.label}",
                            onDrag = { id, delta -> handleDragged(id, delta) },
                            onDragEnd = { handleDragEnded() },
                        )
                    }
                },
            )
        }
    }
}

/** Moves the item at [from] to index [to] (the other items keep their order). */
private fun List<EntryEntity>.moved(from: Int, to: Int): List<EntryEntity> {
    if (from == to || from < 0 || from >= size || to < 0 || to >= size) return this
    val item = this[from]
    val copy = toMutableList()
    copy.removeAt(from)
    copy.add(to, item)
    return copy
}

/**
 * A song row's drag handle: the settled 40 dp soft circle (the row-level
 * action buttons' own treatment) carrying the drag grip. Pressing it and
 * moving the pointer vertically drags the row the handle renders at the
 * press: every move is reported to [onDrag] (with that row's id) as the
 * CUMULATIVE vertical delta (pixels, up negative - measured from the press
 * point in the handle's own coordinates) and [onDragEnd] fires when the
 * pointer lifts; the press-drag-lift cycle repeats for the life of the
 * node (the list reuses the node positionally across reorders), so the
 * handle is ready for the next gesture on whatever row it now shows. A
 * plain tap reports nothing - no delta, no reorder.
 *
 * The handle consumes every move from the first one on, so the page's
 * vertical scroll does not run while the handle is held.
 */
@Composable
private fun SongDragHandle(
    entryId: Long,
    contentDescription: String,
    onDrag: (entryId: Long, totalDeltaY: Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val rowId = rememberUpdatedState(entryId)
    Box(
        modifier = Modifier
            .size(40.dp)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        // Press and hold: report the cumulative vertical delta
                        // (from the press point, in the handle's own coordinates)
                        // on every move; end the drag when the pointer lifts.
                        val down = awaitFirstDown()
                        val id = rowId.value
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            onDrag(id, change.position.y - down.position.y)
                            change.consume()
                        }
                        onDragEnd()
                    }
                }
            }
            .semantics { this.contentDescription = contentDescription }
            .background(scheme.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.DragHandle,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = scheme.onSurfaceVariant,
        )
    }
}

/**
 * One added folder, opened by tapping its row in the content pane. The
 * page shows the folder's details (label + the picked tree uri), its
 * imported songs (the stored content rows the folder's walk identified -
 * the same walk the rescan and the removal use), a rescan action that
 * re-walks the tree for new songs, and ends in a danger zone whose delete
 * removes the folder FROM THE APP - and only the folder and its imported
 * songs; the music files on the phone are untouched.
 *
 * The page is a child of the content pane: its back (the in-pane arrow AND
 * system back) unwinds one level to the content pane, and the content pane
 * still goes straight to the menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderDetailPane(
    viewModel: LumiViewModel,
    folder: FolderEntity,
    songs: LumiViewModel.FolderSongsState?,
    sortDirection: ContentSortDirection,
    onBack: () -> Unit,
    onRescan: () -> Unit,
    onRemove: (FolderEntity) -> Unit,
    onEntryOpened: (EntryEntity) -> Unit,
) {
    // The page's song list: selecting the folder walks its tree and settles
    // the observed [songs] state (null while the walk is in flight).
    LaunchedEffect(folder.id) { viewModel.selectFolder(folder) }
    val scheme = MaterialTheme.colorScheme
    // The page's tree: the stored rows arranged as the picked folder's own
    // structure (a song's payload is the tree's content uri, whose document
    // id encodes the full path below the picked root) - rebuilt only when
    // the folder or its song list changes.
    val tree = remember(folder.id, songs) {
        songs?.entries?.let { folderTreeStructure(Uri.parse(folder.uri), it) }
    }
    // The sort control's per-level order, applied to the built tree.
    val level = remember(tree, sortDirection) { tree?.sortedTree(sortDirection) }
    Scaffold(topBar = { PaneTopAppBar(folder.label, onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = "Folder details") {
                SettingRow(
                    leading = {
                        IconChip(
                            icon = Icons.Filled.Folder,
                            background = scheme.tertiaryContainer,
                            content = scheme.onTertiaryContainer,
                        )
                    },
                    title = folder.label,
                    detail = folder.uri,
                )
            }
            // The sort control: the content pane's own title sort, applied
            // per level of the tree (subfolders first, then songs, at each
            // depth). The pick persists through the shared setting.
            ContentSortRow(
                direction = sortDirection,
                onDirectionChange = { viewModel.setContentSortDirection(it) },
                caption = "Applies to the folders and the songs at each level below.",
            )
            // The rescan action sits on this section's title row (the
            // settled title-row action language): it re-walks the tree and
            // adds the songs the folder gained since the last scan.
            SectionCard(
                title = "Songs",
                trailing = {
                    SoftIconButton(
                        icon = Icons.Default.Refresh,
                        contentDescription = "Rescan",
                        onClick = onRescan,
                    )
                },
            ) {
                when {
                    songs == null -> Text(
                        text = "Reading that folder's songs…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    !songs.readable -> Text(
                        text = "That folder's songs can't be read right now, so they can't be listed.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    songs.entries.isEmpty() -> Text(
                        text = "No songs imported from that folder yet.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> level?.let { FolderTreeRows(it, 0, scheme, onEntryOpened) }
                }
            }
            // The danger zone: a visually separated, clearly destructive
            // section. The detail copy states the effect the confirm will
            // carry out - the folder and its imported songs leave the app's
            // content list; the music files on the phone are not touched.
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = scheme.errorContainer),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Danger zone",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onErrorContainer,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconChip(
                            icon = Icons.Default.Delete,
                            background = scheme.error,
                            content = scheme.onError,
                            size = 36.dp,
                            iconSize = 19.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Delete folder from the app",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                color = scheme.onErrorContainer,
                            )
                            Text(
                                "Removes the folder and its imported songs from the app. The music files stay on the phone.",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onErrorContainer,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        DestructivePillButton("Delete", onClick = { onRemove(folder) })
                    }
                }
            }
        }
    }
}

/**
 * One level of the folder page's song tree, mirroring the structure the
 * import walked: the subfolders (each carrying its own nested levels) come
 * first, then the songs sitting directly at this depth - a row per node,
 * indented one step per subfolder down. Folder rows are display-only; a
 * song row's body opens the song's profile page (the content pane's own
 * language).
 */
@Composable
private fun FolderTreeRows(
    level: FolderTreeLevel,
    depth: Int,
    scheme: ColorScheme,
    onEntryOpened: (EntryEntity) -> Unit,
) {
    val indent = 16.dp * depth
    level.folders.forEach { node ->
        SettingRow(
            modifier = Modifier.padding(start = indent),
            leading = {
                IconChip(
                    icon = Icons.Filled.Folder,
                    background = scheme.tertiaryContainer,
                    content = scheme.onTertiaryContainer,
                )
            },
            title = node.displayName,
        )
        FolderTreeRows(node.children, depth + 1, scheme, onEntryOpened)
    }
    level.songs.forEach { entry ->
        SettingRow(
            // The row body opens the song's profile page (the content
            // pane's own language).
            modifier = Modifier
                .padding(start = indent)
                .clickable { onEntryOpened(entry) },
            leading = {
                IconChip(
                    icon = EntryPresentation.icon(EntryType.fromStorage(entry.type)),
                    background = scheme.primaryContainer,
                    content = scheme.onPrimaryContainer,
                )
            },
            title = entry.label,
            detail = detailFor(entry.type, entry.payload),
        )
    }
}

/**
 * One song, opened by tapping its row (an individual content row in the
 * content pane or a song row on a folder's page). The page shows the
 * song's details - its title and type, the source uri, and the file's own
 * metadata (artist, album, length) where the file carries it - the tags
 * the song is currently assigned to (live: a fresh link - a pick or a
 * scan - joins the list, a removal from a tag's page drops it), and an
 * assign-to-tag action with two options: picking among the app's
 * registered NFC tags (a pick links the stored entry to that tag's song
 * list through the tag-creation flow's own mechanism,
 * [LumiViewModel.linkEntryToTag]; a tag that already plays the song is
 * flagged "Already on this tag" and a re-pick is a reported no-op - no
 * duplicate link; with no registered tags the option is disabled with a
 * clear message), or scanning a physical tag (the app's existing
 * foreground-dispatch read path armed for this song,
 * [LumiViewModel.startTagScanForEntry]: a read of a registered tag links
 * the song to it, and a read of an unregistered one goes through the
 * registration flow first and links the song once the tag is saved -
 * available with no registered tags, since scanning a new tag registers
 * it and then assigns).
 *
 * The page is a child of the content pane: its back (the in-pane arrow and
 * system back) unwinds one level to the content pane, and the content pane
 * still goes straight to the menu from there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryDetailPane(
    viewModel: LumiViewModel,
    entry: EntryEntity,
    onBack: () -> Unit,
) {
    // Points the shared selection at this song: its media metadata
    // (artist / album / duration) loads best-effort - null while the read
    // is in flight, and a read failure settles to the placeholders below.
    LaunchedEffect(entry.id) { viewModel.selectEntry(entry) }
    val metadata by viewModel.songMetadata.collectAsState(null)
    // The tags that already play this song (live - a fresh link re-emits
    // it, so the "already on this tag" flags update as picks land).
    val tags by viewModel.observeTags().collectAsState(emptyList())
    val linkedTags by viewModel.observeTagsForEntry(entry.id).collectAsState(emptyList())
    val scheme = MaterialTheme.colorScheme
    var showAssignDialog by remember { mutableStateOf(false) }
    Scaffold(topBar = { PaneTopAppBar(entry.label, onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = "Song details") {
                SettingRow(
                    leading = {
                        IconChip(
                            icon = EntryPresentation.icon(EntryType.fromStorage(entry.type)),
                            background = scheme.primaryContainer,
                            content = scheme.onPrimaryContainer,
                        )
                    },
                    title = entry.label,
                    detail = detailFor(entry.type, entry.payload),
                )
                // The file's own metadata, best-effort: a placeholder for a
                // field the file does not carry (or could not be read).
                MetadataField("Type", detailFor(entry.type, entry.payload))
                MetadataField("Artist", metadata?.artist ?: "—")
                MetadataField("Album", metadata?.album ?: "—")
                MetadataField(
                    "Length",
                    metadata?.durationMs?.let { formatDuration(it) } ?: "—",
                )
                MetadataField("Source", entry.payload)
            }
            // The tags this song is currently assigned to, live: a fresh
            // link (a pick below or a scan) re-emits the observed list and
            // joins the section; a removal from a tag's page drops the row.
            SectionCard(title = "Assigned tags") {
                if (linkedTags.isEmpty()) {
                    Text(
                        "This song is on no tags yet.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    linkedTags.forEach { tag ->
                        SettingRow(
                            leading = {
                                IconChip(
                                    icon = Icons.Filled.Label,
                                    background = scheme.secondaryContainer,
                                    content = scheme.onSecondaryContainer,
                                )
                            },
                            title = tag.name.ifEmpty { "Tag" },
                            detail = tag.uid,
                        )
                    }
                }
            }
            // Assign this song to one of the registered NFC tags: the
            // stored entry is linked to the tag's ordered song list (no new
            // content row, the existing grant is reused) and plays with
            // that tag's list. Disabled with a clear message when no tag is
            // registered yet. The second option scans a physical tag instead
            // (the app's existing read path armed for this song): a
            // registered tag is linked directly, an unknown one is
            // registered first and the song is linked once saved - so the
            // option stays available with no registered tags.
            SectionCard(title = "Assign to a tag") {
                if (tags.isEmpty()) {
                    Text(
                        "No tags registered yet. Scan a tag below to add this song to it.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Button(
                        onClick = { showAssignDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = scheme.primaryContainer,
                            contentColor = scheme.onPrimaryContainer,
                        ),
                    ) {
                        Icon(
                            Icons.Filled.Label,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Choose a tag",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                FilledTonalButton(
                    onClick = { viewModel.startTagScanForEntry(entry) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.secondaryContainer,
                        contentColor = scheme.onSecondaryContainer,
                    ),
                ) {
                    Icon(
                        Icons.Filled.Nfc,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Scan a tag to assign",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
    if (showAssignDialog) {
        AlertDialog(
            onDismissRequest = { showAssignDialog = false },
            containerColor = scheme.surface,
            shape = RoundedCornerShape(28.dp),
            title = { Text("Assign to a tag", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "Add “${entry.label}” to one of your tags; it will play with that tag's list.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    tags.forEach { tag ->
                        val alreadyOnTag = linkedTags.any { it.id == tag.id }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable {
                                    viewModel.linkEntryToTag(tag, entry)
                                    // A fresh link closes the list; a tag
                                    // that already plays the song keeps it
                                    // open (the view model reports the pick
                                    // with its own toast rather than a
                                    // duplicate link).
                                    if (!alreadyOnTag) showAssignDialog = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconChip(
                                icon = Icons.Filled.Label,
                                background = scheme.secondaryContainer,
                                content = scheme.onSecondaryContainer,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    tag.name.ifEmpty { tag.uid },
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (alreadyOnTag) "Already on this tag" else tag.uid,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (alreadyOnTag) {
                                        scheme.onSurface
                                    } else {
                                        scheme.onSurfaceVariant
                                    },
                                )
                            }
                            if (alreadyOnTag) {
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = scheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAssignDialog = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * One detail field of the song profile: a label column and a single-line
 * value (or the "—" placeholder for a field the file does not carry).
 */
@Composable
private fun MetadataField(label: String, value: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Content: register and manage audio content. The Folders section comes
 * first (a folder's imported songs live on the folder's own page, so the
 * folder is browsed as a unit), then the individual content - the songs
 * added one at a time. The folder-imported rows are NOT in the individual
 * list: they stay visible only inside their folder's page. Both sections
 * follow the sort control's title order (descending by default), and every
 * row carries a play button: a folder's plays all of its stored songs in
 * the order the rows were stored, a song's plays just that song. Tapping a
 * song row opens its profile page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentPane(
    individualEntries: List<EntryEntity>,
    folders: List<FolderEntity>,
    sortDirection: ContentSortDirection,
    onSortDirectionChange: (ContentSortDirection) -> Unit,
    onBack: () -> Unit,
    onAddContent: () -> Unit,
    onAddFolder: () -> Unit,
    onFolderOpened: (FolderEntity) -> Unit,
    onPlayFolder: (FolderEntity) -> Unit,
    onPlayEntry: (EntryEntity) -> Unit,
    onEntryOpened: (EntryEntity) -> Unit,
    onEntryDeleted: (EntryEntity) -> Unit,
) {
    Scaffold(topBar = { PaneTopAppBar("Content", onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // The sort control: both sections below follow this title
            // order. Descending (Z->A) is the list's default; a pick
            // persists through the view model.
            ContentSortRow(
                direction = sortDirection,
                onDirectionChange = onSortDirectionChange,
            )
            // The folders come first: their imported songs live on the
            // folder's own page, and the folder rows get the violet folder
            // chip (D-03). "Add folder" is the + button on the section's
            // title row. A row's play button plays every song the folder
            // holds, in the order the rows were stored.
            SectionCard(
                title = "Folders",
                addLabel = "Add folder",
                onAdd = onAddFolder,
            ) {
                if (folders.isEmpty()) {
                    Text(
                        text = "No folders added yet. Use “Add folder” above.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                folders.sortedByTitle({ it.label }, { it.id }, sortDirection).forEach { folder ->
                    SettingRow(
                        leading = {
                            IconChip(
                                icon = Icons.Filled.Folder,
                                background = MaterialTheme.colorScheme.tertiaryContainer,
                                content = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        },
                        // The row body is the folder page's entry point (the
                        // rescan and the removal live on the page now, not
                        // on the row).
                        modifier = Modifier.clickable { onFolderOpened(folder) },
                        title = folder.label,
                        detail = "Tap to view its songs",
                        trailing = {
                            SoftIconButton(
                                icon = Icons.Filled.PlayArrow,
                                contentDescription = "Play ${folder.label}",
                                onClick = { onPlayFolder(folder) },
                            )
                        },
                    )
                }
            }
            // The individual content: the songs added one at a time (the
            // folder-imported rows are NOT here - they stay visible only
            // inside their folder's page). The old outlined "+ Add content"
            // pill is the + button on the section's title row.
            SectionCard(
                title = "Individual content",
                addLabel = "Add content",
                onAdd = onAddContent,
            ) {
                if (individualEntries.isEmpty()) {
                    Text(
                        text = "Nothing here yet. Add a song.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                individualEntries.sortedByTitle({ it.label }, { it.id }, sortDirection).forEach { entry ->
                    SettingRow(
                        leading = {
                            IconChip(
                                icon = EntryPresentation.icon(EntryType.fromStorage(entry.type)),
                                background = MaterialTheme.colorScheme.primaryContainer,
                                content = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        },
                        // The row body opens the song's profile page (the
                        // play and delete buttons are separate regions, so
                        // they never fight the row tap).
                        modifier = Modifier.clickable { onEntryOpened(entry) },
                        title = entry.label,
                        detail = detailFor(entry.type, entry.payload),
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SoftIconButton(
                                    icon = Icons.Filled.PlayArrow,
                                    contentDescription = "Play ${entry.label}",
                                    onClick = { onPlayEntry(entry) },
                                )
                                Spacer(Modifier.width(8.dp))
                                SoftIconButton(
                                    icon = Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    onClick = { onEntryDeleted(entry) },
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * The content list's sort control: orders the sections below by title in
 * the chosen direction. Descending (Z->A) is the list's default order; a
 * pick persists through the view model. The chips carry the list's own
 * A-Z / Z-A copy. The folder detail page reuses the control (its own
 * [caption]) for the per-level order of its tree.
 */
@Composable
private fun ContentSortRow(
    direction: ContentSortDirection,
    onDirectionChange: (ContentSortDirection) -> Unit,
    caption: String = "Applies to the folders and the songs below.",
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Sort by title", style = MaterialTheme.typography.titleMedium)
                Text(
                    caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            FilterChip(
                selected = direction == ContentSortDirection.ASCENDING,
                onClick = { onDirectionChange(ContentSortDirection.ASCENDING) },
                border = playModeItemBorder,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = scheme.surfaceContainerLow,
                    labelColor = scheme.onSurface,
                    selectedContainerColor = scheme.primaryContainer,
                    selectedLabelColor = scheme.onPrimaryContainer,
                ),
                label = { Text("A-Z") },
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = direction == ContentSortDirection.DESCENDING,
                onClick = { onDirectionChange(ContentSortDirection.DESCENDING) },
                border = playModeItemBorder,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = scheme.surfaceContainerLow,
                    labelColor = scheme.onSurface,
                    selectedContainerColor = scheme.primaryContainer,
                    selectedLabelColor = scheme.onPrimaryContainer,
                ),
                label = { Text("Z-A") },
            )
        }
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

/**
 * The tag detail page's in-app add-song list: the app's existing content
 * (individual or folder-imported alike), collapsed to one row per song
 * label (a song's duplicate copies are offered once, as the first stored
 * copy). A pick links the stored entry to the page's tag - it never
 * launches the file picker, so nothing is stored twice - and a song the
 * tag already plays is offered but flagged (the pick is reported, never
 * silently duplicated).
 */
@Composable
private fun AddSongPickerDialog(
    options: List<TagSongOption>,
    onPick: (TagSongOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = scheme.surface,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Add a song", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (options.isEmpty()) {
                    Text(
                        "No songs in your content yet. Add one in the Content setting first.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable { onPick(option) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconChip(
                            icon = Icons.Filled.MusicNote,
                            background = scheme.primaryContainer,
                            content = scheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                option.entry.label,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (option.alreadyOnTag) "Already on this tag"
                                else detailFor(option.entry.type, option.entry.payload),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (option.alreadyOnTag) scheme.onSurface else scheme.onSurfaceVariant,
                            )
                        }
                        if (option.alreadyOnTag) {
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Dialog asking for a friendly name and the preferred play mode for a
 * freshly captured tag. Both fields are pre-filled from the tag's own
 * lumi payload when the tag carries one. When the tag is writable, an
 * extra "Save and write to tag" button offers to write the name + mode
 * back to the tag so re-registering it later pre-fills the form again.
 */
@Composable
private fun TagNameDialog(
    uid: String,
    prefill: LumiTagData?,
    writable: Boolean,
    defaultMode: PlayMode,
    onDismiss: () -> Unit,
    onSave: (String, PlayMode) -> Unit,
    onSaveAndWrite: (String, PlayMode) -> Unit,
) {
    var name by remember(uid) { mutableStateOf("") }
    var mode by remember(uid) { mutableStateOf(defaultMode) }
    var fieldsTouched by remember(uid) { mutableStateOf(false) }
    LaunchedEffect(uid, prefill) {
        if (fieldsTouched) return@LaunchedEffect
        prefill?.let {
            name = it.name
            mode = it.mode
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Tag captured", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                Text("ID: $uid", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(LumiTagNdef.MAX_NAME_LENGTH)
                        fieldsTouched = true
                    },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                ModeRow(
                    title = "Continuous",
                    detail = "Keeps playing until you tap a different tag.",
                    selected = mode == PlayMode.CONTINUOUS,
                    onSelect = {
                        mode = PlayMode.CONTINUOUS
                        fieldsTouched = true
                    },
                )
                ModeRow(
                    title = "Proximity",
                    detail = "Plays while the tag stays near.",
                    selected = mode == PlayMode.PROXIMITY,
                    onSelect = {
                        mode = PlayMode.PROXIMITY
                        fieldsTouched = true
                    },
                )
            }
        },
        confirmButton = {
            if (writable) {
                Row {
                    TonalPillButton("Save", onClick = { onSave(name, mode) })
                    TonalPillButton(
                        "Save and write to tag",
                        onClick = { onSaveAndWrite(name, mode) },
                    )
                }
            } else {
                TonalPillButton("Save", onClick = { onSave(name, mode) })
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Edit the details of a registered tag. The name is the only stored detail
 * (the UID is hardware-derived and stays read-only, shown as context);
 * saving goes through the view model's rename path, so the page's
 * displayed name follows the saved value.
 */
@Composable
private fun TagEditDialog(
    uid: String,
    initialName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Edit tag", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                Text("ID: $uid", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(LumiTagNdef.MAX_NAME_LENGTH) },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TonalPillButton("Save", onClick = { onSave(name) })
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Card with a bold section title and its content. When [addLabel] and [onAdd]
 * are given, a "+" button sits at the right end of the section's title row:
 * the section's single add action, kept one tap away and visually tied to
 * the section it adds to (the same title-left / action-right row language the
 * menu rows and setting rows use). [trailing] renders an optional extra
 * title-row action (e.g. the tag detail page's edit button) at the row's
 * right end.
 */
@Composable
private fun SectionCard(
    title: String,
    addLabel: String? = null,
    onAdd: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (onAdd != null && addLabel != null) {
                    // The section's single add action: a 40 dp peach circle on
                    // the title row (the settled treatment for the section +
                    // buttons), inside the 48 dp tap target. The click and the
                    // content description live on the 40 dp circle's own node
                    // (a nested semantics node would otherwise hide the
                    // button's click from matchers), so that node's bounds are
                    // the button's for the layout tests.
                    IconButton(onClick = onAdd) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(scheme.primaryContainer, CircleShape)
                                .semantics(mergeDescendants = true) {
                                    contentDescription = addLabel
                                }
                                .clickable { onAdd() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = scheme.onPrimaryContainer,
                            )
                        }
                    }
                }
                trailing?.invoke()
            }
            content()
        }
    }
}

/**
 * Border of the play-mode items: a transparent stroke the width [FilterChip]
 * would draw its default outline at, so the items render without a visible
 * border.
 */
internal val playModeItemBorderColor = Color.Transparent
internal val playModeItemBorder = BorderStroke(1.dp, playModeItemBorderColor)

/** A selectable play-mode row: borderless item, explicit peach + check when
 * selected (the settled look), 56+ dp tall. */
@Composable
private fun ModeRow(
    title: String,
    detail: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onSelect,
        border = playModeItemBorder,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = scheme.surfaceContainerLow,
            labelColor = scheme.onSurface,
            selectedContainerColor = scheme.primaryContainer,
            selectedLabelColor = scheme.onPrimaryContainer,
        ),
        label = {
            Row(
                modifier = Modifier.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    Text(detail, style = MaterialTheme.typography.bodySmall)
                }
                if (selected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = scheme.onPrimaryContainer,
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * A settings row: a tonal icon chip (via [leading]), title, optional detail,
 * trailing actions. 56 dp min height (D-08); row titles are 16 sp Bold
 * (D-02).
 */
@Composable
private fun SettingRow(
    leading: @Composable () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
        trailing?.invoke()
    }
}

/**
 * A 48 dp action button whose visual is a 40 dp soft circle (surface
 * container high) around a 20 dp icon: the settled treatment for row-level
 * delete / remove actions.
 */
@Composable
private fun SoftIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    IconButton(onClick = onClick) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(scheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp),
                tint = scheme.onSurfaceVariant,
            )
        }
    }
}

private fun detailFor(type: String, payload: String): String =
    when (EntryType.fromStorage(type)) {
        EntryType.AUDIO -> "music file"
        EntryType.VIDEO -> "video file"
        EntryType.URL -> "link"
    }
