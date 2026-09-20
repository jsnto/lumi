package com.lumi.player.ui

import android.content.ClipData
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MusicNote
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
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
    Link,
}

/**
 * Parent-facing settings, laid out like the polex settings menu: the settings
 * screen is a list of buttons, one per setting (play mode, NFC tags, content,
 * tag-to-content linking); clicking a row navigates into that setting's own
 * configuration screen.
 *
 * Back navigation unwinds one level at a time: system back and the back arrow
 * take a sub-screen back to the menu, and from the menu the host's back
 * handling (the NavHost in [LumiRoot]) returns to the player screen. The
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
    val registering by viewModel.registering.collectAsState(false)
    val scanning by viewModel.scanning.collectAsState(false)
    val capturedUid by viewModel.capturedUid.collectAsState(null)
    val capturedTagData by viewModel.capturedTagData.collectAsState(null)
    val capturedTagWritable by viewModel.capturedTagWritable.collectAsState(false)
    val effectiveSelectedId by viewModel.effectiveSelectedTagId.collectAsState(null)
    val linked by viewModel.linkedEntries.collectAsState(emptyList())

    var deleteTag by remember { mutableStateOf<TagEntity?>(null) }
    var deleteEntry by remember { mutableStateOf<EntryEntity?>(null) }

    // The settings menu's now-playing strip (D-06): the current entry and
    // the active play mode, so parents never have to go back to the player
    // to know what is playing. IDLE (no entry loaded) shows no strip.
    val playState by PlayerStateHolder.state.collectAsState()

    // One shared OpenDocument launcher: both "Add content" (Content pane) and
    // "Add song to this tag" (Link pane) launch it, and the pending tag id
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

    // System back must unwind the settings screen itself (sub-screen → menu)
    // before the host can pop settings away entirely; only when the menu is
    // showing is the default back handler allowed through.
    BackHandler(enabled = pane != SettingsPane.Menu) {
        pane = SettingsPane.Menu
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
            onLink = { pane = SettingsPane.Link },
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
            onTagDeleted = { deleteTag = it },
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
            onEntryDeleted = { deleteEntry = it },
        )
        SettingsPane.Link -> LinkPane(
            viewModel = viewModel,
            tags = tags,
            entries = entries,
            effectiveSelectedId = effectiveSelectedId,
            linked = linked,
            onBack = { pane = SettingsPane.Menu },
            onAddSong = { tagId ->
                pendingTagPickId = tagId
                audioPicker.launch(arrayOf("audio/*"))
            },
        )
    }

    // ---------- dialogs (overlay any pane) ----------

    if (scanning) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelTagScan() },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(28.dp),
            icon = { NfcAnchor() },
            title = {
                Text(
                    "Point a tag at a song list",
                    style = MaterialTheme.typography.headlineSmall,
                )
            },
            text = { Text("Hold the back of the phone against the tag…") },
            confirmButton = {
                TextButton(onClick = { viewModel.cancelTagScan() }) { Text("Cancel") }
            },
        )
    }

    if (registering) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelTagRegistration() },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(28.dp),
            icon = { NfcAnchor() },
            title = {
                Text("Register a tag", style = MaterialTheme.typography.headlineSmall)
            },
            text = { Text("Hold the back of the phone against any NFC tag…") },
            confirmButton = {
                TextButton(onClick = { viewModel.cancelTagRegistration() }) { Text("Cancel") }
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
    onLink: () -> Unit,
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
            MenuRow(
                icon = Icons.Default.LocationOn,
                chipBackground = scheme.secondaryContainer,
                chipContent = scheme.onSecondaryContainer,
                title = "Tag to content",
                subtitle = "Choose what each tag plays",
                onClick = onLink,
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
 * color-coding of the app's four jobs (D-03): play = peach, tags = teal,
 * content = violet, link = teal. 56+ dp min height, 48 dp targets (D-08).
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

/** NFC tags: register and manage tags. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagsPane(
    viewModel: LumiViewModel,
    tags: List<TagEntity>,
    onBack: () -> Unit,
    onTagDeleted: (TagEntity) -> Unit,
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
            // The old outlined "+ Register a tag" pill is now the + button on
            // the section's title row.
            SectionCard(
                title = "NFC tags",
                addLabel = "Register a tag",
                onAdd = { viewModel.startTagRegistration() },
            ) {
                if (tags.isEmpty()) {
                    Text(
                        text = "No tags yet. Tap a tag on the back of the phone to register it.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                tags.forEach { tag ->
                    SettingRow(
                        leading = {
                            IconChip(
                                icon = Icons.Filled.Label,
                                background = MaterialTheme.colorScheme.secondaryContainer,
                                content = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        },
                        title = tag.name.ifEmpty { "Tag" },
                        detail = tag.uid,
                        trailing = {
                            SoftIconButton(
                                icon = Icons.Default.Delete,
                                contentDescription = "Delete tag",
                                onClick = { onTagDeleted(tag) },
                            )
                        },
                    )
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
                        },
                    )
                }
            }
        }
    }
}

/**
 * Tag to content: the scan-first workflow. The user scans the tag (or picks
 * one), sees the song list currently on that tag, and can add a new song
 * onto it with the "Add" button: the pick both stores the file and appends
 * it to the tag's list. The tag chips scroll horizontally and the "add
 * existing content" buttons flow, so neither list gets clipped no matter
 * how many tags or entries exist.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun LinkPane(
    viewModel: LumiViewModel,
    tags: List<TagEntity>,
    entries: List<EntryEntity>,
    effectiveSelectedId: Long?,
    linked: List<EntryEntity>,
    onBack: () -> Unit,
    onAddSong: (Long) -> Unit,
) {
    Scaffold(topBar = { PaneTopAppBar("Tag to content", onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // The old outlined "+ Add song to this tag" pill is now the +
            // button on the section's title row, shown only while a tag
            // exists and one is the effective selection.
            val effectiveId = effectiveSelectedId
            SectionCard(
                title = "Tag to content",
                addLabel = if (tags.isNotEmpty() && effectiveId != null) "Add song to this tag" else null,
                onAdd = if (tags.isNotEmpty() && effectiveId != null) {
                    { onAddSong(effectiveId) }
                } else {
                    null
                },
            ) {
                OutlinedButton(onClick = { viewModel.startTagScan() }) {
                    Icon(
                        Icons.Filled.Nfc,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Scan a tag to view its song list")
                }
                if (tags.isEmpty()) {
                    Text(
                        text = "Register a tag first, then link content to it.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        tags.forEach { tag ->
                            FilterChip(
                                selected = tag.id == effectiveSelectedId,
                                onClick = { viewModel.selectTag(tag.id) },
                                label = {
                                    Text(tag.name.ifEmpty { tag.uid.takeLast(4) })
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (effectiveId == null) {
                        Text(
                            text = "Pick a tag above.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        if (linked.isEmpty()) {
                            Text(
                                text = "This tag plays nothing yet.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (linked.size > 1) {
                            Text(
                                text = "Drag a song to reorder.",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        // Drag reordering: every row is a drag source (a long press
                        // lifts it, the platform drag ghost follows the finger) and
                        // a drop target. On drop, the target row's index becomes
                        // the dragged row's new position, persisted through the
                        // same updateTagEntries path as the up/down buttons. The
                        // dragged row is tracked in [dragIndex]: the drop event
                        // only exposes the payload's mime types, not the source.
                        var dragIndex by remember { mutableStateOf<Int?>(null) }
                        var dropTargetIndex by remember { mutableStateOf<Int?>(null) }
                        val dragGhostColor = MaterialTheme.colorScheme.primary
                        linked.forEachIndexed { index, entry ->
                            val transferData = remember(entry.id) {
                                DragAndDropTransferData(
                                    clipData = ClipData.newPlainText("lumi-entry", entry.label),
                                    localState = entry.id,
                                )
                            }
                            // Captured here (composable scope) because the
                            // drawWithContent lambda below runs at draw time,
                            // where MaterialTheme is not reachable.
                            val linkRowColors = MaterialTheme.colorScheme
                            SettingRow(
                                leading = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // Visible drag handle (D-07): the
                                        // row's reordering is now legible at a
                                        // glance instead of hidden in a long
                                        // press.
                                        Icon(
                                            imageVector = Icons.Filled.DragHandle,
                                            contentDescription = null,
                                            modifier = Modifier.size(22.dp),
                                            tint = MaterialTheme.colorScheme.outline,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        IconChip(
                                            icon = EntryPresentation.icon(EntryType.fromStorage(entry.type)),
                                            background = MaterialTheme.colorScheme.primaryContainer,
                                            content = MaterialTheme.colorScheme.onPrimaryContainer,
                                            size = 36.dp,
                                            iconSize = 19.dp,
                                        )
                                    }
                                },
                                title = entry.label,
                                modifier = Modifier
                                    .dragAndDropSource(
                                        drawDragDecoration = {
                                            drawCircle(
                                                color = dragGhostColor,
                                                radius = 24.dp.toPx(),
                                            )
                                        },
                                    ) {
                                        dragIndex = index
                                        startTransfer(transferData)
                                    }
                                    .dragAndDropTarget(
                                        shouldStartDragAndDrop = { true },
                                        target = object : DragAndDropTarget {
                                            override fun onEntered(event: DragAndDropEvent) {
                                                dropTargetIndex = index
                                            }

                                            override fun onExited(event: DragAndDropEvent) {
                                                if (dropTargetIndex == index) dropTargetIndex = null
                                            }

                                            override fun onDrop(event: DragAndDropEvent): Boolean {
                                                val from = dragIndex
                                                if (from != null && from != index) {
                                                    viewModel.reorderLinked(effectiveId, from, index)
                                                }
                                                if (from != null) {
                                                    dragIndex = null
                                                    dropTargetIndex = null
                                                }
                                                return from != null
                                            }

                                            override fun onEnded(event: DragAndDropEvent) {
                                                if (dropTargetIndex == index) dropTargetIndex = null
                                                if (dragIndex == index) dragIndex = null
                                            }
                                        },
                                    )
                                    .background(
                                        if (dropTargetIndex == index) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                        } else {
                                            Color.Transparent
                                        },
                                        RoundedCornerShape(16.dp),
                                    )
                                    // The settled drop-target state: a dashed
                                    // primary outline over the peach wash.
                                    .drawWithContent {
                                        drawContent()
                                        if (dropTargetIndex == index) {
                                            // The pruned compose mirror has no
                                            // PathEffect (no dashes) and no
                                            // bordered drawRoundRect, so the
                                            // drop-target outline is a solid
                                            // 2 dp primary stroke over the
                                            // same rounded rect.
                                            drawRoundRect(
                                                color = linkRowColors.primary,
                                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                                    16.dp.toPx(),
                                                    16.dp.toPx(),
                                                ),
                                                style = Stroke(
                                                    2.dp.toPx(),
                                                    4f,
                                                    StrokeCap.Butt,
                                                    StrokeJoin.Miter,
                                                    null,
                                                ),
                                            )
                                        }
                                    },
                                trailing = {
                                    // Stacked up/down (D-07): compact, one
                                    // above the other, keeping the row's
                                    // height down.
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        MoveIconButton(
                                            icon = Icons.Filled.KeyboardArrowUp,
                                            contentDescription = "Move up",
                                            enabled = index > 0,
                                            onClick = { viewModel.moveLinked(effectiveId, index, -1) },
                                        )
                                        MoveIconButton(
                                            icon = Icons.Filled.KeyboardArrowDown,
                                            contentDescription = "Move down",
                                            enabled = index < linked.size - 1,
                                            onClick = { viewModel.moveLinked(effectiveId, index, 1) },
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    SoftIconButton(
                                        icon = Icons.Default.Close,
                                        contentDescription = "Remove",
                                        onClick = { viewModel.removeLinked(effectiveId, entry.id) },
                                    )
                                },
                            )
                        }
                        if (entries.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Add to this tag:",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                entries.forEach { entry ->
                                    OutlinedButton(
                                        onClick = { viewModel.addLinked(effectiveId, entry.id) },
                                    ) {
                                        Text("+ ${entry.label}")
                                    }
                                }
                            }
                        }
                    }
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
 * Card with a bold section title and its content. When [addLabel] and [onAdd]
 * are given, a "+" button sits at the right end of the section's title row:
 * the section's single add action, kept one tap away and visually tied to
 * the section it adds to (the same title-left / action-right row language the
 * menu rows and setting rows use).
 */
@Composable
private fun SectionCard(
    title: String,
    addLabel: String? = null,
    onAdd: (() -> Unit)? = null,
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

/**
 * One half of the link editor's stacked move control (D-07): a compact 34 x
 * 26 dp target (smaller than the 48 dp rule on purpose — two of them stack
 * in the row's trailing slot). Disabled at reduced opacity at the list
 * ends. The content description and the enabled/disabled state are the
 * semantic contract the reorder tests drive.
 */
@Composable
private fun MoveIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(width = 34.dp, height = 26.dp)
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
            }
            .background(
                scheme.surfaceContainerHigh.copy(alpha = if (enabled) 1f else 0.45f),
                RoundedCornerShape(10.dp),
            )
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}

private fun detailFor(type: String, payload: String): String =
    when (EntryType.fromStorage(type)) {
        EntryType.AUDIO -> "music file"
        EntryType.VIDEO -> "video file"
        EntryType.URL -> "link"
    }
