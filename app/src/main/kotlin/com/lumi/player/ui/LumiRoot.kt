package com.lumi.player.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

/**
 * Root of the app: the child-facing front screen plus parent settings, with
 * the NFC-availability prompt layered on top. The prompt states are owned by
 * [MainActivity]; they are recomposed here so the dialog can appear over any
 * screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LumiRoot(
    viewModel: LumiViewModel,
    modifier: Modifier = Modifier,
    nfcMissing: Boolean = false,
    nfcDisabled: Boolean = false,
    showNfcPrompt: Boolean = false,
    onEnableNfc: () -> Unit = {},
    onDismissNfcPrompt: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val onPlayerScreen = backStackEntry?.destination?.route == "player"

    Scaffold(
        modifier = modifier,
        topBar = {
            if (onPlayerScreen) {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            "lumi",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    actions = {
                        IconButton(onClick = { navController.navigate("settings") }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "player",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            composable("player") {
                PlayerScreen(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            composable("settings") {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                )
            }
        }

        if (showNfcPrompt) {
            if (nfcMissing) {
                AlertDialog(
                    onDismissRequest = onDismissNfcPrompt,
                    title = { Text("NFC not available") },
                    text = {
                        Text("This device doesn't have NFC hardware, so lumi can't read tags.")
                    },
                    confirmButton = {
                        TextButton(onClick = onDismissNfcPrompt) { Text("OK") }
                    },
                )
            } else if (nfcDisabled) {
                AlertDialog(
                    onDismissRequest = onDismissNfcPrompt,
                    title = { Text("NFC is off") },
                    text = {
                        Text("lumi needs NFC to read tags. Turn it on to use the app.")
                    },
                    confirmButton = {
                        TextButton(onClick = onEnableNfc) { Text("Enable NFC") }
                    },
                    dismissButton = {
                        TextButton(onClick = onDismissNfcPrompt) { Text("Later") }
                    },
                )
            }
        }
    }
}
