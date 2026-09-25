package com.lumi.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.lumi.player.data.AutoLockDelay
import com.lumi.player.data.LumiThemeMode

/**
 * The System setting: the device/display settings grouped off the main
 * settings menu - the keep-screen-on toggle (moved here from the menu,
 * keeping its look and test tag), the new auto-lock-after-idle choice with
 * the phone's own screen-lock permission state, and the appearance row
 * (moved here from the menu, opening the same appearance sub-screen as
 * before).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SystemPane(
    viewModel: LumiViewModel,
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    autoLockDelay: AutoLockDelay,
    onAutoLockDelayChange: (AutoLockDelay) -> Unit,
    deviceAdminActive: Boolean,
    onEnableDeviceAdmin: () -> Unit,
    themeMode: LumiThemeMode,
    onAppearance: () -> Unit,
    onBack: () -> Unit,
) {
    // The phone can revoke the screen-lock permission behind the app's back
    // (the user disables it in the phone's own settings), so the page
    // refreshes the state when it opens.
    LaunchedEffect(Unit) {
        viewModel.refreshDeviceAdminState()
    }
    val scheme = MaterialTheme.colorScheme
    Scaffold(topBar = { PaneTopAppBar("System", onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            KeepScreenOnRow(
                checked = keepScreenOn,
                onCheckedChange = onKeepScreenOnChange,
            )
            SectionCard("Auto-lock after idle") {
                // The minute ladder: off plus a few minutes of idle (no song
                // actively playing) before the screen is locked.
                AutoLockDelay.entries.forEach { option ->
                    ModeRow(
                        title = option.ladderTitle(),
                        detail = option.ladderDetail(),
                        selected = autoLockDelay == option,
                        onSelect = { onAutoLockDelayChange(option) },
                    )
                }
                // The phone's own screen-lock permission (a device admin):
                // the setting stays visible and usable either way, but the
                // lock can only happen while the admin is active - so the
                // page prompts to enable it when it is not, and says so
                // plainly when it is.
                if (deviceAdminActive) {
                    SettingRow(
                        leading = {
                            IconChip(
                                icon = Icons.Filled.Lock,
                                background = scheme.secondaryContainer,
                                content = scheme.onSecondaryContainer,
                                size = 36.dp,
                                iconSize = 18.dp,
                            )
                        },
                        title = "Screen lock enabled",
                        detail = "lumi can lock the screen after the idle time.",
                    )
                } else {
                    SettingRow(
                        leading = {
                            IconChip(
                                icon = Icons.Filled.Lock,
                                background = scheme.errorContainer,
                                content = scheme.onErrorContainer,
                                size = 36.dp,
                                iconSize = 18.dp,
                            )
                        },
                        title = "Screen lock not enabled",
                        detail = "lumi needs the phone's screen-lock permission to lock the screen after music stops.",
                        trailing = {
                            Button(
                                onClick = onEnableDeviceAdmin,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = scheme.primaryContainer,
                                    contentColor = scheme.onPrimaryContainer,
                                ),
                            ) {
                                Text("Enable screen lock")
                            }
                        },
                    )
                }
            }
            MenuRow(
                icon = Icons.Filled.Palette,
                chipBackground = scheme.tertiaryContainer,
                chipContent = scheme.onTertiaryContainer,
                title = "Appearance",
                subtitle = when (themeMode) {
                    LumiThemeMode.FOLLOW_SYSTEM -> "Follow system"
                    LumiThemeMode.LIGHT -> "Light"
                    LumiThemeMode.DARK -> "Dark"
                },
                onClick = onAppearance,
            )
        }
    }
}

/** The ladder row's title: "Off" or "After N minutes". */
private fun AutoLockDelay.ladderTitle(): String =
    if (this == AutoLockDelay.OFF) "Off" else "After $minutes minutes"

/** The ladder row's detail: what the option does once music stops. */
private fun AutoLockDelay.ladderDetail(): String =
    if (this == AutoLockDelay.OFF) {
        "Never lock the screen."
    } else {
        "Locks the screen $minutes minutes after the last song stops or pauses."
    }

/**
 * The keep-screen-on toggle (moved from the settings menu into the System
 * page, keeping its look and test tag): a checkbox row instead of a chevron
 * row, because the setting is a single on/off choice with no configuration
 * sub-screen.
 */
@Composable
private fun KeepScreenOnRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconChip(
                icon = Icons.Filled.BrightnessHigh,
                background = scheme.primaryContainer,
                content = scheme.onPrimaryContainer,
                size = 36.dp,
                iconSize = 18.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Keep screen on", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Keep the display on while the app is open.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.testTag("keep-screen-on"),
            )
        }
    }
}
