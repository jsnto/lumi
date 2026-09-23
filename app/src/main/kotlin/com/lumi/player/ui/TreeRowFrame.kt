package com.lumi.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp

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
