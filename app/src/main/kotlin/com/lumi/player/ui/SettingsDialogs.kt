package com.lumi.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumi.player.data.PlayMode
import com.lumi.player.data.TagSongOption
import com.lumi.player.nfc.LumiTagData
import com.lumi.player.nfc.LumiTagNdef

/**
 * The tag detail page's in-app add-song list: the app's existing content
 * (individual or folder-imported alike), collapsed to one row per song
 * label (a song's duplicate copies are offered once, as the first stored
 * copy). The user CHECKS the songs to add - tapping a row (or its
 * checkbox) toggles the check - and saves the whole selection at once
 * with the Save button at the bottom of the dialog: every checked song
 * links the stored entry to the page's tag through the single-song link
 * path (one link per checked song), and the dialog closes. Cancelling or
 * dismissing saves nothing. A song the tag already plays is offered but
 * flagged and stays non-addable - its check never engages and the Save
 * button (dimmed while nothing is checkable-and-checked) can never link
 * it a second time. The dialog never launches the file picker, so
 * nothing is stored twice.
 */
@Composable
internal fun AddSongPickerDialog(
    options: List<TagSongOption>,
    onSave: (List<TagSongOption>) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // The checked song rows (the first stored copy's entry id per row):
    // the selection the Save button at the bottom commits at once. A
    // read-only Set held in observed state: every toggle reassigns a fresh
    // set (the copy-on-write below), so the state write is what re-composes.
    var checkedIds: Set<Long> by remember { mutableStateOf(emptySet()) }
    val checkedOptions = options.filter { !it.alreadyOnTag && it.entry.id in checkedIds }
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
                            // Addable rows toggle their check; the flagged
                            // rows (the tag already plays the song) are
                            // non-addable and carry no click at all.
                            .then(
                                if (option.alreadyOnTag) {
                                    Modifier
                                } else {
                                    Modifier.clickable {
                                        val ids = HashSet<Long>(checkedIds)
                                        if (!ids.remove(option.entry.id)) ids.add(option.entry.id)
                                        checkedIds = ids
                                    }
                                },
                            )
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
                            // The settled already-on-this-tag marking: the
                            // song the tag already plays is offered, not
                            // addable.
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = scheme.onSurfaceVariant,
                            )
                        } else {
                            Spacer(Modifier.width(8.dp))
                            Checkbox(
                                checked = option.entry.id in checkedIds,
                                onCheckedChange = {
                                    val ids = HashSet<Long>(checkedIds)
                                    if (!ids.remove(option.entry.id)) ids.add(option.entry.id)
                                    checkedIds = ids
                                },
                                modifier = Modifier.testTag("add-song-check-${option.entry.label}"),
                            )
                        }
                    }
                }
            }
        },
        // The selection's commit at the bottom of the dialog: every
        // checked song links to the tag at once. Dimmed (and inert) while
        // nothing is checked - there is nothing to save.
        confirmButton = {
            TonalPillButton(
                label = "Save",
                onClick = { onSave(checkedOptions) },
                enabled = checkedOptions.isNotEmpty(),
            )
        },
        // Cancelling saves nothing: the selection is dropped with the
        // dialog.
        dismissButton = {
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
internal fun TagNameDialog(
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
internal fun TagEditDialog(
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
