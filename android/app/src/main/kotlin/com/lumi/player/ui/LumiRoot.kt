package com.lumi.player.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

/**
 * Root of the app: the child-facing front screen plus parent settings, with
 * the NFC-availability prompt layered on top. The prompt states are owned by
 * [MainActivity]; they are recomposed here so the dialog can appear over any
 * screen.
 *
 * The player screen draws its own transparent top row (wordmark + lock +
 * settings chips), so this host carries no top bar of its own; each screen
 * owns its own header.
 */
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

    Scaffold(
        modifier = modifier,
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
                    onOpenSettings = { navController.navigate("settings") },
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
                    containerColor = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(28.dp),
                    icon = { NfcAnchor() },
                    title = {
                        Text(
                            "NFC not available",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    },
                    text = {
                        Text("This device doesn't have NFC hardware, so lumi can't read tags.")
                    },
                    confirmButton = {
                        TonalPillButton("OK", onClick = onDismissNfcPrompt)
                    },
                )
            } else if (nfcDisabled) {
                AlertDialog(
                    onDismissRequest = onDismissNfcPrompt,
                    containerColor = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(28.dp),
                    icon = { NfcAnchor() },
                    title = {
                        Text(
                            "NFC is off",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    },
                    text = {
                        Text("lumi needs NFC to read tags. Turn it on to use the app.")
                    },
                    confirmButton = {
                        TonalPillButton("Enable NFC", onClick = onEnableNfc)
                    },
                    dismissButton = {
                        TextButton(onClick = onDismissNfcPrompt) { Text("Later") }
                    },
                )
            }
        }
    }
}
