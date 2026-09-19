package com.lumi.player.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lumi.player.BuildConfig
import com.lumi.player.data.EntryEntity
import com.lumi.player.data.EntryType
import com.lumi.player.data.PlayMode
import com.lumi.player.data.TagEntity
import com.lumi.player.nfc.LumiTagData
import com.lumi.player.nfc.LumiTagNdef

/** Which settings sub-screen the settings screen shows. */
private enum class SettingsPane {
    Menu,
    PlayMode,
    Reread,
    Tags,
    Content,
    Link,
}

/**
 * Parent-facing settings, laid out like the polex settings menu: the settings
 * screen is a list of buttons, one per setting (play mode, tag re-read wait,
 * NFC tags, content, tag-to-content linking); clicking a row navigates into that setting's own
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
    val playMode by viewModel.playMode.collectAsState(PlayMode.CONTINUOUS)
    val nfcRereadSeconds by viewModel.nfcRereadSeconds.collectAsState(15L)
    val registering by viewModel.registering.collectAsState(false)
    val scanning by viewModel.scanning.collectAsState(false)
    val capturedUid by viewModel.capturedUid.collectAsState(null)
    val capturedTagData by viewModel.capturedTagData.collectAsState(null)
    val capturedTagWritable by viewModel.capturedTagWritable.collectAsState(false)
    val effectiveSelectedId by viewModel.effectiveSelectedTagId.collectAsState(null)
    val linked by viewModel.linkedEntries.collectAsState(emptyList())

    var deleteTag by remember { mutableStateOf<TagEntity?>(null) }
    var deleteEntry by remember { mutableStateOf<EntryEntity?>(null) }

    // One shared OpenDocument launcher: both "Add content" (Content pane) and
    // "Add song to this tag" (Link pane) launch it, and the pending tag id
    // set right before the launch decides which side receives the result.
    var pendingTagPickId by remember { mutableStateOf<Long?>(null) }

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
            nfcRereadSeconds = nfcRereadSeconds,
            tags = tags,
            entries = entries,
            onPlayMode = { pane = SettingsPane.PlayMode },
            onReread = { pane = SettingsPane.Reread },
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
        SettingsPane.Reread -> RereadPane(
            viewModel = viewModel,
            rereadSeconds = nfcRereadSeconds,
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
            onBack = { pane = SettingsPane.Menu },
            onAddContent = {
                pendingTagPickId = null
                audioPicker.launch(arrayOf("audio/*"))
            },
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
            title = { Text("Point a tag at a song list") },
            text = { Text("Hold the back of the phone against the tag…") },
            confirmButton = {
                TextButton(onClick = { viewModel.cancelTagScan() }) { Text("Cancel") }
            },
        )
    }

    if (registering) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelTagRegistration() },
            title = { Text("Register a tag") },
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
            title = { Text("Remove tag?") },
            text = { Text("“${tag.name.ifEmpty { tag.uid }}” and its links will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeTag(tag)
                    deleteTag = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTag = null }) { Text("Cancel") }
            },
        )
    }

    deleteEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteEntry = null },
            title = { Text("Remove content?") },
            text = { Text("“${entry.label}” will be removed from the app and from every tag.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeEntry(entry)
                    deleteEntry = null
                }) { Text("Remove") }
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsMenuPane(
    scrollState: ScrollState,
    playMode: PlayMode,
    nfcRereadSeconds: Long,
    tags: List<TagEntity>,
    entries: List<EntryEntity>,
    onPlayMode: () -> Unit,
    onReread: () -> Unit,
    onTags: () -> Unit,
    onContent: () -> Unit,
    onLink: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MenuRow(
                icon = Icons.Default.PlayArrow,
                title = "Play mode",
                subtitle = when (playMode) {
                    PlayMode.CONTINUOUS -> "Continuous"
                    PlayMode.PROXIMITY -> "Proximity"
                },
                onClick = onPlayMode,
            )
            MenuRow(
                icon = Icons.Default.Refresh,
                title = "Tag re-read wait",
                subtitle = "$nfcRereadSeconds s until a removed tag pauses",
                onClick = onReread,
            )
            MenuRow(
                icon = Icons.Default.LocationOn,
                title = "NFC tags",
                subtitle = if (tags.isEmpty()) "No tags yet" else "${tags.size} registered",
                onClick = onTags,
            )
            MenuRow(
                icon = Icons.Default.List,
                title = "Content",
                subtitle = if (entries.isEmpty()) "No songs yet" else "${entries.size} songs",
                onClick = onContent,
            )
            MenuRow(
                icon = Icons.Default.Star,
                title = "Tag to content",
                subtitle = "Choose what each tag plays",
                onClick = onLink,
            )
            Text(
                text = "lumi v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A settings button: icon, title, subtitle, chevron (polex-style row). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
        }
    }
}

// ---------- sub-screens (one per setting) ----------

/** Shared top bar for the settings sub-screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaneTopAppBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold) },
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

/** Tag re-read wait: how long a removed tag may go unreported (proximity). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RereadPane(
    viewModel: LumiViewModel,
    rereadSeconds: Long,
    onBack: () -> Unit,
) {
    Scaffold(topBar = { PaneTopAppBar("Tag re-read wait", onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard("Tag re-read wait") {
                Text(
                    text = "In proximity mode, how many seconds a tag may go " +
                        "unreported before its music pauses. The longer the " +
                        "wait, the more patient the app is with a tag held " +
                        "still.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Wait", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { viewModel.setNfcRereadSeconds(rereadSeconds - 1) },
                        enabled = rereadSeconds > 1,
                    ) {
                        Text("\u2212")
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "${rereadSeconds} s",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.width(16.dp))
                    OutlinedButton(
                        onClick = { viewModel.setNfcRereadSeconds(rereadSeconds + 1) },
                        enabled = rereadSeconds < 120,
                    ) {
                        Text("+")
                    }
                }
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
            SectionCard("NFC tags") {
                OutlinedButton(onClick = { viewModel.startTagRegistration() }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Register a tag")
                }
                if (tags.isEmpty()) {
                    Text(
                        text = "No tags yet. Tap a tag on the back of the phone to register it.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                tags.forEach { tag ->
                    SettingRow(
                        leading = "🏷️",
                        title = tag.name.ifEmpty { "Tag" },
                        detail = tag.uid,
                        trailing = {
                            IconButton(onClick = { onTagDeleted(tag) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete tag")
                            }
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
    onBack: () -> Unit,
    onAddContent: () -> Unit,
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
            SectionCard("Content") {
                OutlinedButton(onClick = onAddContent) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add content")
                }
                if (entries.isEmpty()) {
                    Text(
                        text = "Nothing here yet. Add a song.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                entries.forEach { entry ->
                    SettingRow(
                        leading = EntryPresentation.emoji(EntryType.fromStorage(entry.type)),
                        title = entry.label,
                        detail = detailFor(entry.type, entry.payload),
                        trailing = {
                            IconButton(onClick = { onEntryDeleted(entry) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
            SectionCard("Tag to content") {
                OutlinedButton(onClick = { viewModel.startTagScan() }) {
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
                    val effectiveId = effectiveSelectedId
                    if (effectiveId == null) {
                        Text(
                            text = "Pick a tag above.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        OutlinedButton(onClick = { onAddSong(effectiveId) }) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Add song to this tag")
                        }
                        if (linked.isEmpty()) {
                            Text(
                                text = "This tag plays nothing yet.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        linked.forEachIndexed { index, entry ->
                            SettingRow(
                                leading = EntryPresentation.emoji(EntryType.fromStorage(entry.type)),
                                title = entry.label,
                                trailing = {
                                    IconButton(
                                        onClick = { viewModel.moveLinked(effectiveId, index, -1) },
                                        enabled = index > 0,
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                                    }
                                    IconButton(
                                        onClick = { viewModel.moveLinked(effectiveId, index, 1) },
                                        enabled = index < linked.size - 1,
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                                    }
                                    IconButton(onClick = { viewModel.removeLinked(effectiveId, entry.id) }) {
                                        Icon(Icons.Default.Close, contentDescription = "Remove")
                                    }
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
        title = { Text("Tag captured") },
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
                    TextButton(onClick = { onSave(name, mode) }) { Text("Save") }
                    TextButton(onClick = { onSaveAndWrite(name, mode) }) {
                        Text("Save and write to tag")
                    }
                }
            } else {
                TextButton(onClick = { onSave(name, mode) }) { Text("Save") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Card with a bold section title and its content. */
@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
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
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

/** A selectable play-mode row. */
@Composable
private fun ModeRow(
    title: String,
    detail: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onSelect,
        label = {
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A settings row: emoji, title, optional detail, trailing actions. */
@Composable
private fun SettingRow(
    leading: String,
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(leading, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
        trailing?.invoke()
    }
}

private fun detailFor(type: String, payload: String): String =
    when (EntryType.fromStorage(type)) {
        EntryType.AUDIO -> "music file"
        EntryType.VIDEO -> "video file"
        EntryType.URL -> "link"
    }
