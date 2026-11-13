package com.lumi.player.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumi.player.BuildConfig
import com.lumi.player.data.EntryEntity
import com.lumi.player.data.LumiThemeMode
import com.lumi.player.data.PlayMode
import com.lumi.player.data.TagEntity

/**
 * The settings menu: one clickable row per setting plus an app-version
 * footer. Clicking a row navigates into that setting's configuration screen.
 * The now-playing strip (D-06) tops the list while an entry is loaded.
 * Appearance is an everyday setting and a top-level row; the less-everyday
 * settings (keep screen on, audio level, auto-lock after idle, screen
 * lock) live in the Advanced options page, not on the menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsMenuPane(
    scrollState: ScrollState,
    playMode: PlayMode,
    nowPlayingLabel: String?,
    isPlaying: Boolean,
    tags: List<TagEntity>,
    entries: List<EntryEntity>,
    themeMode: LumiThemeMode,
    onPlayMode: () -> Unit,
    onTags: () -> Unit,
    onContent: () -> Unit,
    onAppearance: () -> Unit,
    onAdvanced: () -> Unit,
    onUpdate: () -> Unit,
    updateState: UpdateState,
    onBack: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Settings", style = MaterialTheme.typography.titleLarge)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (nowPlayingLabel != null) {
                NowPlayingStrip(
                    label = nowPlayingLabel,
                    isPlaying = isPlaying,
                    mode = playMode,
                )
            }
            MenuRow(
                icon = Icons.Default.PlayArrow,
                chipBackground = scheme.primaryContainer,
                chipContent = scheme.onPrimaryContainer,
                title = "Play mode",
                subtitle = when (playMode) {
                    PlayMode.CONTINUOUS -> "Continuous"
                    PlayMode.PROXIMITY -> "Proximity"
                },
                onClick = onPlayMode,
            )
            MenuRow(
                icon = Icons.Filled.Nfc,
                chipBackground = scheme.secondaryContainer,
                chipContent = scheme.onSecondaryContainer,
                title = "NFC tags",
                subtitle = if (tags.isEmpty()) "No tags yet" else "${tags.size} registered",
                onClick = onTags,
            )
            MenuRow(
                icon = Icons.Filled.Folder,
                chipBackground = scheme.tertiaryContainer,
                chipContent = scheme.onTertiaryContainer,
                title = "Content",
                subtitle = if (entries.isEmpty()) "No songs yet" else "${entries.size} songs",
                onClick = onContent,
            )
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
            // The advanced options replace the old System row: the less-
            // everyday device/display/lock settings live on their own page,
            // and the page is a plain list of option sections, so a future
            // advanced option is a plain addition there.
            MenuRow(
                icon = Icons.Filled.Lock,
                chipBackground = scheme.secondaryContainer,
                chipContent = scheme.onSecondaryContainer,
                title = "Advanced options",
                subtitle = "Display, audio level, and screen lock",
                onClick = onAdvanced,
            )
            // The app's self-update (the app is sideloaded - there is no
            // store): check the public GitHub releases for a newer build
            // and install the newest release's APK. The subtitle tracks the
            // live update state (the pane shows the full state + actions).
            MenuRow(
                icon = Icons.Filled.SystemUpdate,
                chipBackground = scheme.primaryContainer,
                chipContent = scheme.onPrimaryContainer,
                title = "Update",
                subtitle = updateState.subtitle(),
                onClick = onUpdate,
            )
            Text(
                text = "lumi v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The settings "Update" menu row's subtitle: a live one-liner for the
 * current [UpdateState] (the pane shows the full state + the actions).
 */
private fun UpdateState.subtitle(): String =
    when (this) {
        UpdateState.Idle -> "Check for a newer version"
        UpdateState.Checking -> "Checking…"
        is UpdateState.UpToDate -> "Up to date (v$currentVersion)"
        is UpdateState.UpdateAvailable -> "Update available: v$latestVersion"
        is UpdateState.Downloading -> "Downloading v$latestVersion…"
        is UpdateState.ReadyToInstall -> "v$latestVersion ready to install"
        is UpdateState.Error -> "Check for updates"
    }

/**
 * The now-playing strip (D-06): a one-line context reminder at the top of
 * the settings menu — what's playing, whether it is, and in which mode.
 * The title auto-scrolls (marquee) when it is too long to fit.
 */
@Composable
private fun NowPlayingStrip(label: String, isPlaying: Boolean, mode: PlayMode) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainerLow, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconChip(
            icon = Icons.Filled.MusicNote,
            background = scheme.primaryContainer,
            content = scheme.onPrimaryContainer,
            size = 36.dp,
            iconSize = 19.dp,
        )
        Spacer(Modifier.width(10.dp))
        MarqueeText(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(if (isPlaying) scheme.secondary else scheme.outlineVariant, CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .background(scheme.secondaryContainer, RoundedCornerShape(500.dp))
                .padding(horizontal = 9.dp, vertical = 4.dp),
        ) {
            Text(
                text = when (mode) {
                    PlayMode.CONTINUOUS -> "CONTINUOUS"
                    PlayMode.PROXIMITY -> "PROXIMITY"
                },
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSecondaryContainer,
                letterSpacing = 0.5.sp,
            )
        }
    }
}
