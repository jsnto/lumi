package com.lumi.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumi.player.data.TagEntity

/**
 * NFC tags: the pane's single scan entry point sits ABOVE the tag list as
 * a prominent action (a filled button with the NFC icon, not a row of the
 * list): arming it, the next tag read either opens a registered tag's
 * profile page (the per-tag page where the tag can be renamed, given
 * songs, or deleted from its danger zone) or takes an unregistered tag
 * into the registration flow (capture, naming dialog, optional
 * write-to-tag). Tapping a tag row's body opens the tapped tag's detail
 * page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagsPane(
    viewModel: LumiViewModel,
    tags: List<TagEntity>,
    onBack: () -> Unit,
    onTagOpened: (TagEntity) -> Unit,
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
            // The single scan entry point: a prominent filled action (NFC
            // icon + label) above the tag list, separated from it by the
            // column's spacing - it reads as the pane's action, not as
            // another row of the list. It arms the unified scan (the same
            // read path playback uses: the foreground-dispatch read into
            // LumiViewModel.onTagDetected).
            Button(
                onClick = { viewModel.startTagScan() },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            ) {
                Icon(
                    Icons.Filled.Nfc,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text("Scan NFC", style = MaterialTheme.typography.titleMedium)
            }
            SectionCard(title = "NFC tags") {
                if (tags.isEmpty()) {
                    Text(
                        text = "No tags yet. Scan a tag to add it.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                tags.forEach { tag ->
                    SettingRow(
                        // The row body is the detail page's entry point.
                        modifier = Modifier.clickable { onTagOpened(tag) },
                        leading = {
                            IconChip(
                                icon = Icons.Filled.Label,
                                background = MaterialTheme.colorScheme.secondaryContainer,
                                content = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        },
                        title = tag.name.ifEmpty { "Tag" },
                        detail = tag.uid,
                    )
                }
            }
        }
    }
}
