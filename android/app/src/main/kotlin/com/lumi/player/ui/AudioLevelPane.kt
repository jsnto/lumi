package com.lumi.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumi.player.data.AudioLevelMode
import com.lumi.player.data.LoudnessStatus

/**
 * Audio level: the playback volume setting (Off - play every song at the
 * level it was recorded at; Normalize - bring each measured song up to the
 * target level, the very loud ones staying a little above it so they never
 * clip), the target-level picker (-18 / -16 / -14 LUFS), and the
 * measurement status with the explicit "Normalize now" action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioLevelPane(
    viewModel: LumiViewModel,
    mode: AudioLevelMode,
    targetLufs: Double,
    status: LoudnessStatus,
    normalizing: Boolean,
    onBack: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Scaffold(topBar = { PaneTopAppBar("Audio level", onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard("Audio level") {
                ModeRow(
                    title = "Off",
                    detail = "Play songs at the level they were recorded at.",
                    selected = mode == AudioLevelMode.OFF,
                    onSelect = { viewModel.setAudioLevelMode(AudioLevelMode.OFF) },
                )
                ModeRow(
                    title = "Normalize",
                    detail = "Bring quiet songs up and loud songs down to the target level.",
                    selected = mode == AudioLevelMode.NORMALIZE,
                    onSelect = { viewModel.setAudioLevelMode(AudioLevelMode.NORMALIZE) },
                )
            }
            SectionCard("Target level") {
                ModeRow(
                    title = "-18 LUFS",
                    detail = "Quieter.",
                    selected = targetLufs == -18.0,
                    onSelect = { viewModel.setAudioLevelTargetLufs(-18.0) },
                )
                ModeRow(
                    title = "-16 LUFS",
                    detail = "Balanced - the default.",
                    selected = targetLufs == -16.0,
                    onSelect = { viewModel.setAudioLevelTargetLufs(-16.0) },
                )
                ModeRow(
                    title = "-14 LUFS",
                    detail = "Louder.",
                    selected = targetLufs == -14.0,
                    onSelect = { viewModel.setAudioLevelTargetLufs(-14.0) },
                )
            }
            SectionCard("Measured songs") {
                SettingRow(
                    leading = {
                        IconChip(
                            icon = Icons.Filled.MusicNote,
                            background = scheme.tertiaryContainer,
                            content = scheme.onTertiaryContainer,
                        )
                    },
                    title = status.text,
                    detail = if (mode == AudioLevelMode.OFF) {
                        "Measurements stay ready for when you turn Normalize on."
                    } else {
                        "Songs that are measured play at the target level."
                    },
                )
                // The explicit measurement pass (the pane's action): the
                // settled peach fill, like the NFC tags pane's scan button.
                Button(
                    onClick = { viewModel.normalizeNow() },
                    enabled = !normalizing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.primaryContainer,
                        contentColor = scheme.onPrimaryContainer,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                ) {
                    Text(if (normalizing) "Measuring…" else "Normalize now")
                }
            }
        }
    }
}
