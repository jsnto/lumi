package com.lumi.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumi.player.data.PlayMode

/** Play mode: pick continuous or proximity behaviour. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayModePane(
    viewModel: LumiViewModel,
    playMode: PlayMode,
    onBack: () -> Unit,
) {
    Scaffold(topBar = { PaneTopAppBar("Play mode", onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
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
                    detail = "Plays while the tag stays near; pauses when it's removed and resumes when you read it again.",
                    selected = playMode == PlayMode.PROXIMITY,
                    onSelect = { viewModel.setPlayMode(PlayMode.PROXIMITY) },
                )
            }
        }
    }
}
