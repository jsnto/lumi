package com.lumi.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.lumi.player.data.EntryEntity
import com.lumi.player.data.FolderTreeLevel
import com.lumi.player.data.collapseKey

/** The tree row's per-depth rail column (the indent step). */
internal val TREE_RAIL_STEP_Dp = 16.dp

/** The tree row's connector lines' width. */
internal val TREE_RAIL_WIDTH_Dp = 1.dp

/** The tree row's horizontal stub from a rail out to its row. */
internal val TREE_STUB_WIDTH_Dp = 8.dp

/**
 * One row of a tree, carrying the tree's left-side connectors: a 1 dp
 * vertical rail in each of the levels' rail columns above the row (every
 * rail spans the row's full frame height, so the rails run continuously
 * down a column from the first nested row to the last), plus - for a row
 * nested at least one level - a short horizontal stub from the innermost
 * rail out to the row. A folder row with displayed children also carries
 * its children's rail from its own vertical center down (its first
 * child's row picks the line up at its top), so each branch's line starts
 * where its folder row sits. The row's content indents one rail step per
 * level: the rails occupy the indent, replacing the bare padding.
 *
 * Shared by the content folder pages' tree and the tag add-song picker's
 * content tree - the same connector-line layout for both.
 *
 * The row's bottom spacing (12 dp, the section's own row spacing) lives
 * inside the row's frame and is the row's only inter-row spacing - the
 * caller groups the tree's rows under a single non-spaced [Column], so the
 * section's own 12 dp row spacing cannot also sit between the rows and
 * break the rails, which the row draws only inside its own frame; the
 * tree's last row carries no bottom spacing, so no rail dangles past the
 * tree.
 */
@Composable
internal fun TreeRowFrame(
    depth: Int,
    hasVisibleBranch: Boolean,
    isLast: Boolean,
    content: @Composable () -> Unit,
) {
    val railColor = MaterialTheme.colorScheme.outlineVariant
    val bottomSpacing = if (isLast) 0.dp else 12.dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val step = TREE_RAIL_STEP_Dp.toPx()
                // The row's content centers in the frame minus its bottom
                // spacing (the spacing is below the content, inside the
                // frame).
                val contentCenterY = (size.height - bottomSpacing.toPx()) / 2f
                // The vertical rails of the levels above this row: each
                // spans the frame's full height, so the line is continuous
                // through the row and into the next row's frame.
                for (level in 0 until depth) {
                    val x = level * step
                    drawLine(railColor, Offset(x, 0f), Offset(x, size.height), TREE_RAIL_WIDTH_Dp.toPx())
                }
                // The row's own branch rail (a folder row's children's
                // rail): from the row's vertical center to the frame's
                // bottom - the first child's row continues it from its top.
                if (hasVisibleBranch) {
                    val x = depth * step
                    drawLine(railColor, Offset(x, contentCenterY), Offset(x, size.height), TREE_RAIL_WIDTH_Dp.toPx())
                }
                // The horizontal stub from the innermost rail to the row
                // itself.
                if (depth > 0) {
                    val x = (depth - 1) * step
                    drawLine(railColor, Offset(x, contentCenterY), Offset(x + TREE_STUB_WIDTH_Dp.toPx(), contentCenterY), TREE_RAIL_WIDTH_Dp.toPx())
                }
            }
            .padding(bottom = bottomSpacing)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (depth > 0) {
                Spacer(Modifier.width(TREE_RAIL_STEP_Dp * depth))
            }
            content()
        }
    }
}

/**
 * One level of a content tree laid out the way the content folder pages
 * lay it out: the subfolders lead, then the level's songs - one row per
 * node, each in the tree's [TreeRowFrame] (the left connector lines,
 * indented one rail step per subfolder down).
 *
 * This is the SHARED walker of the two trees that render a [FolderTreeLevel]
 * (the content folder pages' tree and the tag add-song picker's content
 * tree): it owns the recursion, the subfolder rows' show/hide state (the
 * collapse key - the subfolder's identifying path from the tree's root -
 * is the row's tap target, the chevron trailing it points down while the
 * rows are displayed and right while they are hidden), and the [remaining]
 * counter (the tree's visible row count, decremented row by row) that lets
 * every row know whether it is the tree's last one (the last row carries
 * no bottom spacing, so no connector line dangles past it). The caller
 * supplies the SONG ROW's content ([songRow]); the song row halves are
 * deliberately different (the folder page's rows open the song's profile
 * and carry the play/pause button, the picker's rows carry the check), so
 * only the folder row halves and the walk itself are shared - keeping the
 * two trees' folder rows and connector layout in lockstep.
 *
 * A subfolder's row toggles the display of the rows nested under it (its
 * direct songs and any nested subfolders' content); a subfolder with no
 * displayed children carries no dangling branch rail. The caller groups
 * the whole tree under a single non-spaced [Column]: the rows' own 12 dp
 * bottom spacing is the only inter-row spacing (the rails run through it).
 */
@Composable
internal fun TreeLevelRows(
    level: FolderTreeLevel,
    depth: Int,
    path: List<String>,
    collapsed: Set<List<String>>,
    onToggleCollapsed: (List<String>) -> Unit,
    remaining: IntArray,
    songRow: @Composable (EntryEntity) -> Unit,
) {
    val scheme: ColorScheme = MaterialTheme.colorScheme
    level.folders.forEach { node ->
        // The row's show/hide key - the subfolder's identifying path from
        // the tree's root (siblings carry distinct names, so the key names
        // one node): the recursion passes it down as the children's path.
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
                    // while the rows are displayed and right while they
                    // are hidden (display-only - the row body is the tap
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
            TreeLevelRows(
                level = node.children,
                depth = depth + 1,
                path = nodeKey,
                collapsed = collapsed,
                onToggleCollapsed = onToggleCollapsed,
                remaining = remaining,
                songRow = songRow,
            )
        }
    }
    level.songs.forEach { entry ->
        val isLast = remaining[0] == 1
        remaining[0]--
        TreeRowFrame(depth = depth, hasVisibleBranch = false, isLast = isLast) {
            songRow(entry)
        }
    }
}
