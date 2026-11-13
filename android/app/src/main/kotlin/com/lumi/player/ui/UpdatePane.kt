package com.lumi.player.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lumi.player.BuildConfig

/**
 * The settings "Update" area: whether the running build is the newest
 * public release of the app's own repository, and (when it is not) the
 * actions to download and install the newest one. The app is sideloaded as
 * an APK (there is no store), so the check reads the public GitHub
 * releases page and the update installs the newest release's APK asset
 * through the platform's own installer.
 *
 * The pane is presentational: it renders the [UpdateState] the view model
 * publishes and the download's [progress], and it calls the view model's
 * actions. The engine (the fetch, the download, the [FileProvider] uri,
 * the install intent, and the "install unknown apps" gate) is
 * [UpdateManager]; the version ordering and the response parse are the
 * pure domain in `data/UpdateCheck.kt`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpdatePane(
    viewModel: LumiViewModel,
    state: UpdateState,
    progress: Float,
    onBack: () -> Unit,
) {
    // The install + the "install unknown apps" settings are Activity-bound
    // (startActivity), so the pane hands the activity to the view model's
    // install actions (LocalContext.current is the hosting activity).
    val activity = LocalContext.current as? Activity
    val scheme = MaterialTheme.colorScheme
    Scaffold(topBar = { PaneTopAppBar("Update", onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard("Update") {
                // The running build (BuildConfig.VERSION_NAME - the version
                // the check compares against).
                SettingRow(
                    leading = {
                        IconChip(
                            icon = Icons.Filled.SystemUpdate,
                            background = scheme.primaryContainer,
                            content = scheme.onPrimaryContainer,
                            size = 36.dp,
                            iconSize = 18.dp,
                        )
                    },
                    title = "Current version",
                    detail = "lumi v${BuildConfig.VERSION_NAME}",
                )
                UpdateStatus(
                    state = state,
                    progress = progress,
                )
            }
            // Check the releases page for a newer build. It is live except
            // while a check or a download is in flight and while an APK is
            // already downloaded and awaiting install (re-checking there
            // would lose the ready-to-install state, so the Install button
            // is the live action).
            Button(
                onClick = { viewModel.checkForUpdate() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.primaryContainer,
                    contentColor = scheme.onPrimaryContainer,
                ),
                enabled = state !is UpdateState.Checking &&
                    state !is UpdateState.Downloading &&
                    state !is UpdateState.ReadyToInstall,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            ) {
                Icon(
                    Icons.Filled.SystemUpdate,
                    contentDescription = null,
                    modifier = Modifier.width(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text("Check for latest version", style = MaterialTheme.typography.titleMedium)
            }
            // Download the newest release's APK (enabled only while a newer
            // release is known to exist - the check's UpdateAvailable).
            Button(
                onClick = { viewModel.updateToLatest() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.primaryContainer,
                    contentColor = scheme.onPrimaryContainer,
                ),
                enabled = state is UpdateState.UpdateAvailable,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            ) {
                Icon(
                    Icons.Filled.SystemUpdate,
                    contentDescription = null,
                    modifier = Modifier.width(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text("Update to latest", style = MaterialTheme.typography.titleMedium)
            }
            // Install the downloaded APK (enabled only once the download has
            // settled ReadyToInstall): gated on the phone's "install unknown
            // apps" permission, which the engine opens for the user when it
            // is missing.
            Button(
                onClick = { activity?.let { viewModel.requestInstall(it) } },
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.primaryContainer,
                    contentColor = scheme.onPrimaryContainer,
                ),
                enabled = state is UpdateState.ReadyToInstall && activity != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            ) {
                Icon(
                    Icons.Filled.SystemUpdate,
                    contentDescription = null,
                    modifier = Modifier.width(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text("Install now", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/**
 * The area's status copy for the current [UpdateState]: the plain result
 * of a check (up to date / an update is available / a failure's message),
 * the in-flight check, and the download's live progress bar + percentage.
 */
@Composable
private fun UpdateStatus(state: UpdateState, progress: Float) {
    val scheme = MaterialTheme.colorScheme
    when (state) {
        UpdateState.Idle -> Text(
            "Check the releases page to see if a newer version is out.",
            style = MaterialTheme.typography.bodyMedium,
        )
        UpdateState.Checking -> Text(
            "Checking for a newer version\u2026",
            style = MaterialTheme.typography.bodyMedium,
        )
        is UpdateState.UpToDate -> Text(
            "You're up to date (current version v${state.latestVersion}).",
            style = MaterialTheme.typography.bodyMedium,
        )
        is UpdateState.UpdateAvailable -> Text(
            "Update available: v${state.latestVersion} (you have v${state.currentVersion}).",
            style = MaterialTheme.typography.bodyMedium,
        )
        is UpdateState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Downloading v${state.latestVersion}\u2026 ${percent(progress)}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        is UpdateState.ReadyToInstall -> Text(
            "Downloaded v${state.latestVersion}. Install it to update.",
            style = MaterialTheme.typography.bodyMedium,
        )
        is UpdateState.Error -> Text(
            state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.error,
        )
    }
}

/** The download's fraction (0..1) as a percentage label. */
private fun percent(progress: Float): String = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%"
