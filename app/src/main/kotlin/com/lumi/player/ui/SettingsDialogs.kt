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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
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
import com.lumi.player.data.EntryEntity
import com.lumi.player.data.EntryType
import com.lumi.player.data.FolderTreeLevel
import com.lumi.player.data.collapseKey
import com.lumi.player.data.PlayMode
import com.lumi.player.data.rowsInTreeOrder
import com.lumi.player.data.visibleRowCount
import com.lumi.player.nfc.LumiTagData
import com.lumi.player.nfc.LumiTagNdef

/**
 * The tag detail page's in-app add-song list: the app's existing content
 * (individual or folder-imported alike) laid out as the content folder
 * pages' TREE - the individual songs (the non-folder rows) at the top
 * level, and each added folder as a top-level folder row carrying the
 * folder's own imported structure (its subfolders and the songs nested
 * under them), with the tree's left-side connector guide lines and the
 * folder rows toggling their nested rows' display (show/hide) - the
 * per-level order follows the content list's sort. A song's duplicate
 * copies are offered once, as the first stored copy. The user CHECKS the
 * songs to add - tapping a row (or its checkbox) toggles the check - and
 * saves the whole selection at once with the Save button at the bottom of
 * the dialog: every checked song links the stored entry to the page's tag
 * through the single-song link path (one link per checked song), in the
 * tree's render order (the order the rows are laid out when nothing is
 * collapsed - collapsing is display-only and never changes what Save
 * commits), and the dialog closes. Cancelling or dismissing saves
 * nothing. A song the tag already plays is offered but flagged and stays
 * non-addable - its check never engages and the Save button (dimmed while
 * nothing is checked) can never link it a second time. The dialog never
 * launches the file picker, so nothing is stored twice.
 */
@Composable
internal fun AddSongPickerDialog(
    tree: FolderTreeLevel,
    alreadyOnTagLabels: Set<String>,
    onSave: (List<EntryEntity>) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // The checked song rows (the first stored copy's entry id per row):
    // the selection the Save button at the bottom commits at once. A
    // read-only Set held in observed state: every toggle reassigns a fresh
    // set (the copy-on-write below), so the state write is what re-composes.
    var checkedIds: Set<Long> by remember { mutableStateOf(emptySet()) }
    // The collapsed folder rows' keys (the tree's show/hide state): the
    // rows' name paths (from the tree's root), except the top-level rows,
    // which carry the added folder's id (their labels can collide, so the
    // label alone would not name one node). Display state only -
    // collapsing hides rows, it never changes the save order.
    var collapsed: Set<List<String>> by remember { mutableStateOf(emptySet()) }
    // Every song row of the tree in its render order (folders first,
    // nested rows right after their folder's row, the level's songs after):
    // the Save button commits the checked rows in THIS order - the flat
    // list's own "the dialog's row order" rule, carried over to the tree.
    val rowsInOrder = tree.rowsInTreeOrder()
    val checkedOptions = rowsInOrder.filter { it.label !in alreadyOnTagLabels && it.id in checkedIds }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = scheme.surface,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Add a song", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Pick the songs to add to the tag; they will play with its list.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (rowsInOrder.isEmpty()) {
                    Text(
                        "No songs in your content yet. Add one in the Content setting first.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    // The tree's rows under a single non-spaced Column: the
                    // rows' own 12 dp bottom spacing is the only inter-row
                    // spacing (the connector rails run through it), the
                    // same grouping the content folder pages use.
                    val remaining = intArrayOf(tree.visibleRowCount(collapsed))
                    Column {
                        PickerTreeRows(
                            level = tree,
                            depth = 0,
                            path = emptyList(),
                            checkedIds = checkedIds,
                            collapsed = collapsed,
                            onToggleChecked = { id ->
                                val ids = HashSet<Long>(checkedIds)
                                if (!ids.remove(id)) ids.add(id)
                                checkedIds = ids
                            },
                            onToggleCollapsed = { path ->
                                val paths = HashSet<List<String>>(collapsed)
                                if (!paths.remove(path)) paths.add(path)
                                collapsed = paths
                            },
                            alreadyOnTagLabels = alreadyOnTagLabels,
                            remaining = remaining,
                        )
                    }
                }
            }
        },
        // The selection's commit at the bottom of the dialog: every
        // checked song links to the tag at once, in the tree's render
        // order. Dimmed (and inert) while nothing is checked - there is
        // nothing to save. The selection is read at CLICK time (not
        // snapshotted at composition): the button's action can be
        // invoked through a semantics node that lags a pending
        // recomposition by a frame (a check landing just before the save
        // would otherwise commit the pre-check selection).
        confirmButton = {
            TonalPillButton(
                label = "Save",
                onClick = {
                    val selection = rowsInOrder.filter {
                        it.label !in alreadyOnTagLabels && it.id in checkedIds
                    }
                    onSave(selection)
                },
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
 * One level of the add-song picker's content tree: the folder rows (each
 * toggling its nested rows' display - a chevron pointing right while
 * hidden, down while shown) and the song rows (tapping a row - or its
 * checkbox - toggles the song's check; a song the tag already plays is
 * flagged and stays non-addable) - each in the tree's [TreeRowFrame]
 * (the left-side connector guide lines shared with the content folder
 * pages' tree). The [remaining] counter drives the rail's last-row
 * spacing: the tree's last displayed row carries no bottom spacing, so no
 * rail dangles past the tree.
 */
@Composable
private fun PickerTreeRows(
    level: FolderTreeLevel,
    depth: Int,
    path: List<String>,
    checkedIds: Set<Long>,
    collapsed: Set<List<String>>,
    onToggleChecked: (Long) -> Unit,
    onToggleCollapsed: (List<String>) -> Unit,
    alreadyOnTagLabels: Set<String>,
    remaining: IntArray,
) {
    val scheme = MaterialTheme.colorScheme
    level.folders.forEach { node ->
        // The row's show/hide key: the name path, except the top-level
        // rows, which key by the added folder's id (their labels can
        // collide - the recursion keeps the name path).
        val nodeKey = node.collapseKey(path)
        val isCollapsed = nodeKey in collapsed
        val hasVisibleBranch = !isCollapsed &&
            (node.children.folders.isNotEmpty() || node.children.songs.isNotEmpty())
        val isLast = remaining[0] == 1
        remaining[0]--
        TreeRowFrame(depth = depth, hasVisibleBranch = hasVisibleBranch, isLast = isLast) {
            SettingRow(
                modifier = Modifier.clickable { onToggleCollapsed(nodeKey) },
                leading = {
                    IconChip(
                        icon = Icons.Filled.Folder,
                        background = scheme.tertiaryContainer,
                        content = scheme.onTertiaryContainer,
                    )
                },
                title = node.displayName,
                trailing = {
                    Icon(
                        imageVector = if (isCollapsed) {
                            Icons.Filled.KeyboardArrowRight
                        } else {
                            Icons.Filled.KeyboardArrowDown
                        },
                        contentDescription = if (isCollapsed) {
                            "Expand ${node.displayName}"
                        } else {
                            "Collapse ${node.displayName}"
                        },
                        modifier = Modifier.size(20.dp),
                        tint = scheme.onSurfaceVariant,
                    )
                },
            )
        }
        if (!isCollapsed) {
            PickerTreeRows(
                level = node.children,
                depth = depth + 1,
                path = path + node.displayName,
                checkedIds = checkedIds,
                collapsed = collapsed,
                onToggleChecked = onToggleChecked,
                onToggleCollapsed = onToggleCollapsed,
                alreadyOnTagLabels = alreadyOnTagLabels,
                remaining = remaining,
            )
        }
    }
    level.songs.forEach { entry ->
        val flagged = entry.label in alreadyOnTagLabels
        val isLast = remaining[0] == 1
        remaining[0]--
        TreeRowFrame(depth = depth, hasVisibleBranch = false, isLast = isLast) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    // Addable rows toggle their check; the flagged rows
                    // (the tag already plays the song) are non-addable and
                    // carry no click at all.
                    .then(
                        if (flagged) {
                            Modifier
                        } else {
                            Modifier.clickable { onToggleChecked(entry.id) }
                        },
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconChip(
                    icon = EntryPresentation.icon(EntryType.fromStorage(entry.type)),
                    background = scheme.primaryContainer,
                    content = scheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (flagged) "Already on this tag"
                        else detailFor(entry.type, entry.payload),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (flagged) scheme.onSurface else scheme.onSurfaceVariant,
                    )
                }
                if (flagged) {
                    // The settled already-on-this-tag marking: the song the
                    // tag already plays is offered, not addable.
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
                        checked = entry.id in checkedIds,
                        onCheckedChange = { onToggleChecked(entry.id) },
                        modifier = Modifier.testTag("add-song-check-${entry.label}"),
                    )
                }
            }
        }
    }
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
