package com.lumi.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumi.player.data.ContentSortDirection
import com.lumi.player.data.EntryEntity
import com.lumi.player.data.EntryType
import com.lumi.player.data.FolderEntity
import com.lumi.player.data.sortedByTitle

/**
 * Content: register and manage audio content. The Folders section comes
 * first (a folder's imported songs live on the folder's own page, so the
 * folder is browsed as a unit), then the individual content - the songs
 * added one at a time. The folder-imported rows are NOT in the individual
 * list: they stay visible only inside their folder's page. Both sections
 * follow the sort control's title order (ascending by default). A folder
 * row opens the folder's own page (its songs' play/pause buttons live
 * there, not on the row); every individual row carries a play button that
 * plays just that song. Tapping a song row opens its profile page. A song
 * row badges the entries the app has volume-normalized (the [normalized]
 * ids) and marquee-scrolls a title that is too long to fit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContentPane(
    individualEntries: List<EntryEntity>,
    folders: List<FolderEntity>,
    normalized: Set<Long>,
    sortDirection: ContentSortDirection,
    onSortDirectionChange: (ContentSortDirection) -> Unit,
    onBack: () -> Unit,
    onAddContent: () -> Unit,
    onAddFolder: () -> Unit,
    onFolderOpened: (FolderEntity) -> Unit,
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
            // order. Ascending (A->Z) is the list's default; a pick
            // persists through the view model.
            ContentSortRow(
                direction = sortDirection,
                onDirectionChange = onSortDirectionChange,
            )
            // The folders come first: their imported songs live on the
            // folder's own page, and the folder rows get the violet folder
            // chip (D-03). "Add folder" is the + button on the section's
            // title row. A row opens the folder's own page - its songs' play
            // buttons live on that page's tree, not on the row.
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
                        // rescan, the removal, and the songs' play buttons
                        // live on the page now, not on the row).
                        modifier = Modifier.clickable { onFolderOpened(folder) },
                        title = folder.label,
                        detail = "Tap to view its songs",
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconChip(
                                    icon = EntryPresentation.icon(EntryType.fromStorage(entry.type)),
                                    background = MaterialTheme.colorScheme.primaryContainer,
                                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                // The app has volume-normalized this entry
                                // (it carries a stored loudness reading).
                                if (entry.id in normalized) {
                                    Spacer(Modifier.width(6.dp))
                                    NormalizedBadge()
                                }
                            }
                        },
                        // The row body opens the song's profile page (the
                        // play and delete buttons are separate regions, so
                        // they never fight the row tap). A title too long
                        // to fit marquee-scrolls instead of clipping.
                        modifier = Modifier.clickable { onEntryOpened(entry) },
                        title = entry.label,
                        detail = detailFor(entry.type, entry.payload),
                        titleMarquee = true,
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
 * the chosen direction. Ascending (A->Z) is the list's default order; a
 * pick persists through the view model. The chips carry the list's own
 * A-Z / Z-A copy. The folder detail page reuses the control (its own
 * [caption]) for the per-level order of its tree.
 */
@Composable
internal fun ContentSortRow(
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
