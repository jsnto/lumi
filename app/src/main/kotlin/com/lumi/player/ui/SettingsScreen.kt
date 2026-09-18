package com.lumi.player.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lumi.player.data.EntryEntity
import com.lumi.player.data.EntryType
import com.lumi.player.data.PlayMode
import com.lumi.player.data.TagEntity

/**
 * Parent-facing settings, laid out as a single scrollable menu with one
 * section per settings group: play mode, NFC tag registration, audio content
 * registration, and tag-to-content linking.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: LumiViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val tags by viewModel.observeTags().collectAsState(emptyList())
    val entries by viewModel.observeEntries().collectAsState(emptyList())
    val playMode by viewModel.playMode.collectAsState(PlayMode.CONTINUOUS)
    val registering by viewModel.registering.collectAsState(false)
    val capturedUid by viewModel.capturedUid.collectAsState(null)
    val effectiveSelectedId by viewModel.effectiveSelectedTagId.collectAsState(null)
    val linked by viewModel.linkedEntries.collectAsState(emptyList())

    var deleteTag by remember { mutableStateOf<TagEntity?>(null) }
    var deleteEntry by remember { mutableStateOf<EntryEntity?>(null) }

    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) onFilePicked(viewModel, context, uri, EntryType.AUDIO) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PlayModeSection(viewModel, playMode)
            TagsSection(viewModel, tags) { deleteTag = it }
            ContentSection(viewModel, entries, onAddContent = { audioPicker.launch(arrayOf("audio/*")) }) {
                deleteEntry = it
            }
            LinkSection(viewModel, tags, entries, effectiveSelectedId, linked)
        }
    }

    // ---------- dialogs ----------

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
            onDismiss = { viewModel.discardCaptured() },
            onSave = { name -> viewModel.confirmTagName(name) },
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

// ---------- sections (one per settings group) ----------

/** Play mode: pick continuous or proximity behaviour. */
@Composable
private fun PlayModeSection(
    viewModel: LumiViewModel,
    playMode: PlayMode,
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
            detail = "Plays while the tag stays near; stops when it's removed.",
            selected = playMode == PlayMode.PROXIMITY,
            onSelect = { viewModel.setPlayMode(PlayMode.PROXIMITY) },
        )
    }
}

/** NFC tags: register and manage tags. */
@Composable
private fun TagsSection(
    viewModel: LumiViewModel,
    tags: List<TagEntity>,
    onTagDeleted: (TagEntity) -> Unit,
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

/** Content: register and manage audio entries. */
@Composable
private fun ContentSection(
    viewModel: LumiViewModel,
    entries: List<EntryEntity>,
    onAddContent: () -> Unit,
    onEntryDeleted: (EntryEntity) -> Unit,
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
                leading = emojiFor(entry.type),
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

/**
 * Tag to content: pick a tag, then order and edit the content it plays. The
 * tag chips scroll horizontally and the "add content" buttons flow, so
 * neither list gets clipped no matter how many tags or entries exist.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkSection(
    viewModel: LumiViewModel,
    tags: List<TagEntity>,
    entries: List<EntryEntity>,
    effectiveSelectedId: Long?,
    linked: List<EntryEntity>,
) {
    SectionCard("Tag to content") {
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
                if (linked.isEmpty()) {
                    Text(
                        text = "This tag plays nothing yet.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                linked.forEachIndexed { index, entry ->
                    SettingRow(
                        leading = emojiFor(entry.type),
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

private fun onFilePicked(
    viewModel: LumiViewModel,
    context: android.content.Context,
    uri: Uri,
    type: EntryType,
) {
    val name = context.contentResolver
        .query(uri, arrayOf("_display_name"), null, null, null)
        ?.use { cursor ->
            val index = cursor.getColumnIndex("_display_name")
            if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
        }
    viewModel.addFileEntry(uri, type, name)
}

/** Dialog asking for an optional friendly name for a freshly captured tag. */
@Composable
private fun TagNameDialog(
    uid: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tag captured") },
        text = {
            Column {
                Text("ID: $uid", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }) { Text("Save") }
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

private fun emojiFor(type: String): String = when (EntryType.fromStorage(type)) {
    EntryType.AUDIO -> "🎵"
    EntryType.VIDEO -> "🎬"
    EntryType.URL -> "🔗"
}

private fun detailFor(type: String, payload: String): String =
    when (EntryType.fromStorage(type)) {
        EntryType.AUDIO -> "music file"
        EntryType.VIDEO -> "video file"
        EntryType.URL -> "link"
    }
