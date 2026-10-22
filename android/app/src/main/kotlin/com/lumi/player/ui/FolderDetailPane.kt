package com.lumi.player.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lumi.player.data.ContentSortDirection
import com.lumi.player.data.EntryEntity
import com.lumi.player.data.EntryType
import com.lumi.player.data.FolderEntity
import com.lumi.player.data.FolderTreeLevel
import com.lumi.player.data.collapseKey
import com.lumi.player.data.folderTreeStructure
import com.lumi.player.data.sortedTree
import com.lumi.player.data.visibleRowCount
import com.lumi.player.player.PlayerUiState

/**
 * One added folder, opened by tapping its row in the content pane. The
 * page shows the folder's details (label + the picked tree uri), its
 * imported songs (the stored content rows the folder's walk identified -
 * the same walk the rescan and the removal use) as the picked folder's own
 * tree: the subfolders lead at every level, the songs follow, each row
 * carries the tree's left connector lines, each subfolder's row toggles
 * the rows nested under it (show/hide), and every song row carries a
 * play/pause button. A rescan action re-walks the tree for new songs, and
 * the page ends in a danger zone whose delete removes the folder FROM THE
 * APP - and only the folder and its imported songs; the music files on the
 * phone are untouched. A song row badges the entries the app will
 * actually play volume-normalized (the [normalized] ids).
 *
 * The page is a child of the content pane: its back (the in-pane arrow AND
 * system back) unwinds one level to the content pane, and the content pane
 * still goes straight to the menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FolderDetailPane(
    viewModel: LumiViewModel,
    folder: FolderEntity,
    songs: LumiViewModel.FolderSongsState?,
    normalized: Set<Long>,
    sortDirection: ContentSortDirection,
    playState: PlayerUiState,
    collapsed: Set<List<String>>,
    onBack: () -> Unit,
    onRescan: () -> Unit,
    onRemove: (FolderEntity) -> Unit,
    onToggleCollapsed: (List<String>) -> Unit,
    onEntryOpened: (EntryEntity) -> Unit,
    onPlayEntry: (EntryEntity) -> Unit,
    onTogglePlayPause: () -> Unit,
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
                    else -> level?.let { tree ->
                        // The rows the tree renders, in render order: the
                        // count lets every row know whether it is the
                        // tree's last one (the last row carries no bottom
                        // spacing, so no connector line dangles past it).
                        val remaining = intArrayOf(tree.visibleRowCount(collapsed))
                        Column {
                            FolderTreeRows(
                                level = tree,
                                depth = 0,
                                path = emptyList(),
                                scheme = scheme,
                                normalized = normalized,
                                collapsed = collapsed,
                                playState = playState,
                                onToggleCollapsed = onToggleCollapsed,
                                onPlayEntry = onPlayEntry,
                                onTogglePlayPause = onTogglePlayPause,
                                onEntryOpened = onEntryOpened,
                                remaining = remaining,
                            )
                        }
                    }
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
 * each carrying the tree's left connector lines and indented one rail step
 * per subfolder down ([TreeRowFrame]).
 *
 * The caller groups the whole tree under a single non-spaced [Column]: the
 * rows' own 12 dp bottom spacing is the only inter-row spacing, so the
 * section's own 12 dp row spacing cannot sit between the rows and break
 * the connector rails (each row draws its rails only inside its own
 * frame).
 *
 * A subfolder's row is tappable: it toggles the display of that
 * subfolder's rows (its direct songs and any nested subfolders' content) -
 * the toggle is keyed by the subfolder's path from the root ([path] plus
 * its display name; siblings carry distinct names, so the key names one
 * node). The songs directly in the picked root (depth zero) are always
 * displayed: no subfolder owns them. A song row's body opens the song's
 * profile page (the content pane's own language), and its play/pause
 * button is a separate region beside the body (the content pane's own
 * pattern), so it never fights the row tap.
 */
@Composable
private fun FolderTreeRows(
    level: FolderTreeLevel,
    depth: Int,
    path: List<String>,
    scheme: ColorScheme,
    normalized: Set<Long>,
    collapsed: Set<List<String>>,
    playState: PlayerUiState,
    onToggleCollapsed: (List<String>) -> Unit,
    onPlayEntry: (EntryEntity) -> Unit,
    onTogglePlayPause: () -> Unit,
    onEntryOpened: (EntryEntity) -> Unit,
    remaining: IntArray,
) {
    level.folders.forEach { node ->
        val nodeKey = node.collapseKey(path)
        val isCollapsed = nodeKey in collapsed
        // The folder's own branch rail (from its vertical center down, so
        // its first child's row continues it) is drawn only while the
        // children are actually displayed - a collapsed folder carries no
        // dangling line.
        val hasVisibleBranch =
            !isCollapsed &&
                (node.children.folders.isNotEmpty() || node.children.songs.isNotEmpty())
        val isLast = remaining[0] == 1
        remaining[0]--
        TreeRowFrame(depth = depth, hasVisibleBranch = hasVisibleBranch, isLast = isLast) {
            SettingRow(
                // The row body is the show/hide toggle: tapping it hides
                // (or restores) the rows nested under this subfolder.
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
                    // The state's own affordance: the chevron points down
                    // while the rows are displayed and right while they are
                    // hidden (display-only - the row body is the tap
                    // target).
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
            FolderTreeRows(
                level = node.children,
                depth = depth + 1,
                path = nodeKey,
                scheme = scheme,
                normalized = normalized,
                collapsed = collapsed,
                playState = playState,
                onToggleCollapsed = onToggleCollapsed,
                onPlayEntry = onPlayEntry,
                onTogglePlayPause = onTogglePlayPause,
                onEntryOpened = onEntryOpened,
                remaining = remaining,
            )
        }
    }
    level.songs.forEach { entry ->
        val isLast = remaining[0] == 1
        remaining[0]--
        TreeRowFrame(depth = depth, hasVisibleBranch = false, isLast = isLast) {
            SettingRow(
                // The row body opens the song's profile page (the content
                // pane's own language).
                modifier = Modifier.clickable { onEntryOpened(entry) },
                leading = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconChip(
                            icon = EntryPresentation.icon(EntryType.fromStorage(entry.type)),
                            background = scheme.primaryContainer,
                            content = scheme.onPrimaryContainer,
                        )
                        // The app has volume-normalized this entry (it
                        // carries a stored loudness reading).
                        if (entry.id in normalized) {
                            Spacer(Modifier.width(6.dp))
                            NormalizedBadge()
                        }
                    }
                },
                title = entry.label,
                detail = detailFor(entry.type, entry.payload),
                trailing = {
                    // The live play/pause state from the shared playback
                    // holder: while this song is the current track the
                    // button shows pause (while it plays) or play (while it
                    // is paused) and toggles it - pausing then resuming the
                    // SAME song; while any other song is current (or
                    // nothing plays) it starts this one.
                    val isCurrent = playState.currentLabel == entry.label
                    val isPlayingNow = isCurrent && playState.isPlaying
                    SoftIconButton(
                        icon = if (isPlayingNow) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlayingNow) "Pause ${entry.label}" else "Play ${entry.label}",
                        onClick = {
                            if (isCurrent) onTogglePlayPause() else onPlayEntry(entry)
                        },
                    )
                },
            )
        }
    }
}
