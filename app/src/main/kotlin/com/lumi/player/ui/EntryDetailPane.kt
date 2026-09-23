package com.lumi.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumi.player.data.EntryEntity
import com.lumi.player.data.EntryType
import com.lumi.player.data.formatDuration

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
 * The page is a child of the pane its row was tapped in: its back (the
 * in-pane arrow and system back) unwinds one level to that opener - the
 * folder's own page when the song was opened from a folder's tree, the
 * content pane when opened from the content pane - and that opener still
 * goes straight to the menu from there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EntryDetailPane(
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
