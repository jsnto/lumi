package com.lumi.player.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumi.player.data.EntryType

/** A tonal icon chip: the settled treatment for list/menu row icons (D-03). */
@Composable
internal fun IconChip(
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

/** Shared top bar for the settings sub-screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PaneTopAppBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
    )
}

/**
 * Card with a bold section title and its content. When [addLabel] and [onAdd]
 * are given, a "+" button sits at the right end of the section's title row:
 * the section's single add action, kept one tap away and visually tied to
 * the section it adds to (the same title-left / action-right row language the
 * menu rows and setting rows use). [trailing] renders an optional extra
 * title-row action (e.g. the tag detail page's edit button) at the row's
 * right end.
 */
@Composable
internal fun SectionCard(
    title: String,
    addLabel: String? = null,
    onAdd: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
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
                trailing?.invoke()
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
internal fun ModeRow(
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
internal fun SettingRow(
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
internal fun SoftIconButton(
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

internal fun detailFor(type: String, payload: String): String =
    when (EntryType.fromStorage(type)) {
        EntryType.AUDIO -> "music file"
        EntryType.VIDEO -> "video file"
        EntryType.URL -> "link"
    }
