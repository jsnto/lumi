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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * A fresh install (the first-time setup not yet finished - [setupComplete])
 * lands on the [SetupWizard] instead of the player; when the wizard ends,
 * the app's screens take over (the player's front screen, or the settings
 * screen's NFC tags pane when the tag setup was picked).
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
    setupComplete: Boolean = true,
) {
    val navController = rememberNavController()

    // Where the settings screen opens: the menu by default, or the NFC tags
    // pane when the first-time wizard's "Set up now" ends into it (the flag
    // is one-shot - a later open from the player starts at the menu).
    var settingsStartPane by remember { mutableStateOf(SettingsPane.Menu) }

    Scaffold(
        modifier = modifier,
    ) { padding ->
        if (!setupComplete) {
            SetupWizard(
                viewModel = viewModel,
                onFinish = { openNfcTags ->
                    if (openNfcTags) {
                        settingsStartPane = SettingsPane.Tags
                    }
                },
            )
        } else {
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
                        initialPane = settingsStartPane,
                        onBack = {
                            // The wizard's one-shot deep link is consumed here:
                            // a later open from the player starts at the menu.
                            // (Clearing it before the destination composed -
                            // in the navigation effect below - would race the
                            // composition's read and lose the deep link.)
                            settingsStartPane = SettingsPane.Menu
                            navController.popBackStack()
                        },
                    )
                }
            }

            // The wizard's NFC "Set up now" records its deep link BEFORE the
            // flag flips the tree over to the player, so this one-shot
            // navigation lands the fresh back stack on the settings screen's
            // NFC tags pane. The flag itself is cleared when the settings
            // screen is left (its onBack), not here: the destination reads
            // it at composition, a frame after this effect ran.
            LaunchedEffect(Unit) {
                if (settingsStartPane == SettingsPane.Tags) {
                    navController.navigate("settings")
                }
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
