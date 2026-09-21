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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.lumi.player.data.FolderEntity
import com.lumi.player.data.PlayMode
import com.lumi.player.data.TagEntity
import com.lumi.player.nfc.LumiTagData
import com.lumi.player.nfc.LumiTagNdef
import com.lumi.player.player.PlayerStateHolder

/** Which settings sub-screen the settings screen shows. */
private enum class SettingsPane {
    Menu,
    PlayMode,
    Tags,
    Content,
    TagDetail,
}

/**
 * Parent-facing settings, laid out like the polex settings menu: the settings
 * screen is a list of buttons, one per setting (play mode, NFC tags,
 * content); clicking a row navigates into that setting's own configuration
 * screen.
 *
 * Back navigation unwinds one level at a time: the tag detail page is a
 * child of the NFC tags pane, so its system back and back arrow take it back
 * to the NFC tags list (and its deleted-while-open fallback lands there too);
 * every other sub-screen's system back and back arrow go back to the menu,
 * and from the menu the host's back handling (the NavHost in [LumiRoot])
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
    val folders by viewModel.observeFolders().collectAsState(emptyList())
    val playMode by viewModel.playMode.collectAsState(PlayMode.CONTINUOUS)
    val keepScreenOn by viewModel.keepScreenOn.collectAsState(false)
    val scanActive by viewModel.scanActive.collectAsState(false)
    val pendingTagProfile by viewModel.pendingTagProfile.collectAsState(null)
    val capturedUid by viewModel.capturedUid.collectAsState(null)
    val capturedTagData by viewModel.capturedTagData.collectAsState(null)
    val capturedTagWritable by viewModel.capturedTagWritable.collectAsState(false)
    // The selected tag's live song list (the tag detail page's plumbing).
    val linked by viewModel.linkedEntries.collectAsState(emptyList())
    val folderRemoval by viewModel.folderRemovalPreview.collectAsState(null)

    var deleteTag by remember { mutableStateOf<TagEntity?>(null) }
    var deleteEntry by remember { mutableStateOf<EntryEntity?>(null) }
    // The tag whose edit dialog is showing (set by the tag detail page's
    // edit action on the Tag details section).
    var editTag by remember { mutableStateOf<TagEntity?>(null) }

    // The tag whose detail page is showing (set when a tag row in the NFC
    // tags pane is tapped, or when a scan settles on a registered tag;
    // cleared when the tag is removed or the page leaves it).
    var tagDetailId by remember { mutableStateOf<Long?>(null) }

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

    // One shared OpenDocument launcher: both "Add content" (Content pane) and
    // "Add song" (the tag detail page) launch it, and the pending tag id
    // set right before the launch decides which side receives the result.
    var pendingTagPickId by rememberSaveable { mutableStateOf<Long?>(null) }

    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val tagId = pendingTagPickId
        if (tagId != null) {
            viewModel.addSongToTag(uri, tagId, displayNameFor(context, uri))
        } else {
            onFilePicked(viewModel, context, uri, EntryType.AUDIO)
        }
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
    // page unwinds to the NFC tags list it lives under, every other
    // sub-screen to the menu; only when the menu is showing is the default
    // back handler allowed through.
    BackHandler(enabled = pane != SettingsPane.Menu) {
        pane = if (pane == SettingsPane.TagDetail) SettingsPane.Tags else SettingsPane.Menu
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
            entries = entries,
            folders = folders,
            onBack = { pane = SettingsPane.Menu },
            onAddContent = {
                pendingTagPickId = null
                audioPicker.launch(arrayOf("audio/*"))
            },
            onAddFolder = { folderPicker.launch(null) },
            onRescanFolder = { viewModel.rescanFolder(it) },
            onRemoveFolder = { viewModel.requestFolderRemoval(it) },
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
                    onAddSong = { tagId ->
                        pendingTagPickId = tagId
                        audioPicker.launch(arrayOf("audio/*"))
                    },
                    onEdit = { editTag = it },
                    onDelete = { deleteTag = it },
                )
            }
        }
    }

    // ---------- dialogs (overlay any pane) ----------

    // The unified scan in progress: any NFC tag - a registered one opens
    // its profile page, an unregistered one is registered.
    if (scanActive) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelTagScan() },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(28.dp),
            icon = { NfcAnchor() },
            title = {
                Text("Scan a tag", style = MaterialTheme.typography.headlineSmall)
            },
            text = { Text("Hold the back of the phone against any NFC tag…") },
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
 * hardware-derived and read-only), its current song list with an add-song
 * action aimed at THIS tag, and ends in a danger zone whose delete removes
 * the tag - and only the tag and its links.
 *
 * The song list reuses the shared selection plumbing: opening the page
 * selects the tag, so [linked] (the selected tag's entries) is this tag's
 * list, and the add-song pick goes through the same
 * [LumiViewModel.addSongToTag] path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagDetailPane(
    viewModel: LumiViewModel,
    tag: TagEntity,
    linked: List<EntryEntity>,
    onBack: () -> Unit,
    onAddSong: (Long) -> Unit,
    onEdit: (TagEntity) -> Unit,
    onDelete: (TagEntity) -> Unit,
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
            // The + button on this section's title row adds a song to THIS
            // tag: the pick stores the file and appends it to the tag's
            // list in one transaction (the shared add-song path).
            SectionCard(
                title = "Songs",
                addLabel = "Add song",
                onAdd = { onAddSong(tag.id) },
            ) {
                if (linked.isEmpty()) {
                    Text(
                        text = "This tag plays nothing yet.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                linked.forEach { entry ->
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

/** Content: register and manage audio entries. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentPane(
    entries: List<EntryEntity>,
    folders: List<FolderEntity>,
    onBack: () -> Unit,
    onAddContent: () -> Unit,
    onAddFolder: () -> Unit,
    onRescanFolder: (FolderEntity) -> Unit,
    onRemoveFolder: (FolderEntity) -> Unit,
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
            // The old outlined "+ Add content" pill is now the + button on
            // the section's title row.
            SectionCard(
                title = "Content",
                addLabel = "Add content",
                onAdd = onAddContent,
            ) {
                if (entries.isEmpty()) {
                    Text(
                        text = "Nothing here yet. Add a song.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                entries.forEach { entry ->
                    SettingRow(
                        leading = {
                            IconChip(
                                icon = EntryPresentation.icon(EntryType.fromStorage(entry.type)),
                                background = MaterialTheme.colorScheme.primaryContainer,
                                content = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        },
                        title = entry.label,
                        detail = detailFor(entry.type, entry.payload),
                        trailing = {
                            SoftIconButton(
                                icon = Icons.Default.Delete,
                                contentDescription = "Delete",
                                onClick = { onEntryDeleted(entry) },
                            )
                        },
                    )
                }
            }
            // "Add folder" joins its section's title row as the + button
            // (the settled design moves it off the standalone button), and
            // the folder rows get the violet folder chip (D-03).
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
                folders.forEach { folder ->
                    SettingRow(
                        leading = {
                            IconChip(
                                icon = Icons.Filled.Folder,
                                background = MaterialTheme.colorScheme.tertiaryContainer,
                                content = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        },
                        title = folder.label,
                        detail = "Rescan to pick up new songs.",
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = { onRescanFolder(folder) },
                                    shape = RoundedCornerShape(500.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Rescan", fontSize = 13.sp)
                                }
                                // The folder's removal: the settled row-level
                                // delete treatment (48 dp target, 40 dp soft
                                // circle), right next to the rescan action it
                                // shares the row with. The confirmation names
                                // the effect before anything is deleted.
                                SoftIconButton(
                                    icon = Icons.Default.Delete,
                                    contentDescription = "Remove folder",
                                    onClick = { onRemoveFolder(folder) },
                                )
                            }
                        },
                    )
                }
            }
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
