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
import com.lumi.player.data.LumiThemeMode

/**
 * Appearance: pick how lumi looks - follow the phone's own light/dark
 * setting (the default), or force light or dark. The choice applies
 * app-wide through the theme, effective immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearancePane(
    viewModel: LumiViewModel,
    themeMode: LumiThemeMode,
    onBack: () -> Unit,
) {
    Scaffold(topBar = { PaneTopAppBar("Appearance", onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard("Appearance") {
                ModeRow(
                    title = "Follow system",
                    detail = "Uses the phone's own light or dark setting.",
                    selected = themeMode == LumiThemeMode.FOLLOW_SYSTEM,
                    onSelect = { viewModel.setThemeMode(LumiThemeMode.FOLLOW_SYSTEM) },
                )
                ModeRow(
                    title = "Light",
                    detail = "Bright and sunny, any time of day.",
                    selected = themeMode == LumiThemeMode.LIGHT,
                    onSelect = { viewModel.setThemeMode(LumiThemeMode.LIGHT) },
                )
                ModeRow(
                    title = "Dark",
                    detail = "Soft and easy on the eyes in the dark.",
                    selected = themeMode == LumiThemeMode.DARK,
                    onSelect = { viewModel.setThemeMode(LumiThemeMode.DARK) },
                )
            }
        }
    }
}
