package com.lumi.player.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumi.player.data.EntryType
import com.lumi.player.player.PlayerService
import com.lumi.player.player.PlaybackStatus
import com.lumi.player.player.PlayerStateHolder

/**
 * Child-friendly front screen: one big friendly visual that represents the
 * current entry, what it is, and a single big button, plus a soft lock: a
 * dimming overlay that darkens the screen while the app underneath (playback
 * included) keeps working.
 *
 * Settled revamp (D-01..D-08): a deeper warm gradient with a soft glow behind
 * the blob; the "lumi" wordmark and the lock + settings chips share one
 * transparent top row; the entry type is a tonal chip; the single big control
 * is filled in primary with its action icon.
 */
@Composable
fun PlayerScreen(
    viewModel: LumiViewModel,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
) {
    val playState by PlayerStateHolder.state.collectAsState()
    val scheme = MaterialTheme.colorScheme
    val lockPopupSuppressed by viewModel.lockPopupSuppressed.collectAsState()

    // Soft-lock UI state. The lock is an overlay only: it never touches
    // playback, so the app underneath keeps working while it is engaged.
    var lockEngaged by remember { mutableStateOf(false) }
    var showLockDialog by remember { mutableStateOf(false) }
    val unlocker = remember { TripleTapUnlocker() }

    val type = playState.currentType?.let { EntryType.fromStorage(it) }
    val idle = playState.status == PlaybackStatus.IDLE
    // The blob's accent: the loaded entry's color, or the idle blue. It tints
    // the blob and its halo; the screen gradient stays warm.
    val blobColor = type?.let(EntryPresentation::color) ?: Color(0xFF64B5F6)

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            scheme.background,
                            scheme.primaryContainer.copy(alpha = 0.65f),
                            scheme.primaryContainer,
                        ),
                    )
                )
                .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Unified top row: wordmark left; lock + settings as two
            // outlined icon chips on the right (they used to sit in two
            // different places: the gear in the host top bar, the lock in the
            // body below it).
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "lumi",
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onSurface,
                    letterSpacing = 0.3.sp,
                    modifier = Modifier.padding(start = 4.dp),
                )
                Spacer(Modifier.weight(1f))
                OutlinedIconChip(
                    icon = Icons.Filled.Lock,
                    contentDescription = "Lock the screen",
                    onClick = {
                        if (lockPopupSuppressed) {
                            unlocker.reset()
                            lockEngaged = true
                        } else {
                            showLockDialog = true
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                OutlinedIconChip(
                    icon = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    onClick = onOpenSettings,
                )
            }

            Spacer(Modifier.weight(1f))

            PlayingVisual(
                active = playState.isPlaying,
                color = blobColor,
            ) {
                Text(
                    text = type?.let(EntryPresentation::emoji) ?: "✨",
                    fontSize = 96.sp,
                )
            }

            Spacer(Modifier.size(28.dp))

            AnimatedContent(
                targetState = playState.currentLabel
                    ?: if (idle) "Tap a tag to start!" else "Ready",
                label = "lumi-label",
                transitionSpec = {
                    fadeIn(animationSpec = tween(250)) togetherWith fadeOut(animationSpec = tween(250))
                },
            ) { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.headlineMedium,
                    color = scheme.onBackground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2,
                )
            }

            if (type != null) {
                Spacer(Modifier.size(14.dp))
                TypeChip(
                    type = type,
                    label = when (type) {
                        EntryType.AUDIO -> "music"
                        EntryType.VIDEO -> "video"
                        EntryType.URL -> "link"
                    },
                )
            }

            Spacer(Modifier.weight(1f))

            BigControlButton(
                enabled = !idle,
                label = when (playState.status) {
                    PlaybackStatus.PLAYING -> "Pause"
                    PlaybackStatus.PAUSED -> "Play"
                    PlaybackStatus.IDLE -> "Stop"
                },
                icon = when (playState.status) {
                    PlaybackStatus.PLAYING -> Icons.Filled.Pause
                    PlaybackStatus.PAUSED -> Icons.Filled.PlayArrow
                    PlaybackStatus.IDLE -> Icons.Filled.Stop
                },
                onClick = {
                    val action = when (playState.status) {
                        PlaybackStatus.PLAYING, PlaybackStatus.PAUSED ->
                            PlayerService.ACTION_TOGGLE
                        PlaybackStatus.IDLE ->
                            PlayerService.ACTION_STOP
                    }
                    viewModel.command(action)
                },
            )
        }

        if (lockEngaged) {
            LockOverlay(
                onTap = {
                    // Tapping the dimmed overlay [TripleTapUnlocker.REQUIRED_TAPS]
                    // times (with the taps close enough together) unlocks.
                    if (unlocker.recordTap(SystemClock.uptimeMillis())) {
                        lockEngaged = false
                    }
                },
            )
        }
    }

    if (showLockDialog) {
        LockPromptDialog(
            onConfirm = { donShowAgain ->
                if (donShowAgain) viewModel.setLockPopupSuppressed(true)
                unlocker.reset()
                lockEngaged = true
                showLockDialog = false
            },
            onDismiss = { showLockDialog = false },
        )
    }
}

/**
 * The dimming overlay shown while the front screen is soft-locked. It only
 * darkens the screen and forwards taps; the app underneath (playback
 * included) is untouched and keeps working.
 */
@Composable
private fun LockOverlay(onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { _ -> onTap() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "🔒 Tap the screen 3 times fast to unlock",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
    }
}

/**
 * The pre-lock popup: explains how the lock works and how to unlock it, with
 * a checkbox to stop showing it again (persisted via
 * [LumiViewModel.setLockPopupSuppressed]).
 */
@Composable
private fun LockPromptDialog(
    onConfirm: (donShowAgain: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var donShowAgain by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = scheme.surface,
        shape = RoundedCornerShape(28.dp),
        title = {
            Text("Lock the screen?", style = MaterialTheme.typography.headlineSmall)
        },
        text = {
            Column {
                Text(
                    text = "The screen will darken, but lumi keeps working: music keeps " +
                        "playing. Tap the screen 3 times fast to unlock.",
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = donShowAgain,
                        onCheckedChange = { donShowAgain = it },
                        modifier = Modifier.testTag("lock-dont-show-again"),
                    )
                    Text("Don't show this again")
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = { onConfirm(donShowAgain) },
                shape = RoundedCornerShape(500.dp),
            ) {
                Text("Lock", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * The big animated blob that "dances" while something is playing: a soft
 * halo (radial shading), a faint ring, and the core with a sun-like
 * highlight. The pulse (0.94 -> 1.06 over 900 ms) is kept exactly.
 */
@Composable
fun PlayingVisual(
    active: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "lumi-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "lumi-pulse-scale",
    )
    val scale = if (active) pulse else 1f
    val density = androidx.compose.ui.platform.LocalDensity.current
    val haloPx = with(density) { 296.dp.toPx() }
    val corePx = with(density) { 200.dp.toPx() }

    Box(
        modifier = modifier
            .size(260.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        contentAlignment = Alignment.Center,
    ) {
        // Soft halo: the blob's tint fading out into the gradient.
        Box(
            modifier = Modifier
                .size(296.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(color.copy(alpha = 0.24f), color.copy(alpha = 0.1f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(haloPx / 2f, haloPx / 2f),
                        radius = haloPx / 2f,
                    ),
                ),
        )
        // Faint ring just outside the core.
        Box(
            modifier = Modifier
                .size(224.dp)
                .background(color.copy(alpha = 0.16f), CircleShape),
        )
        Box(
            modifier = Modifier
                .size(200.dp)
                .shadow(18.dp, shape = CircleShape, clip = false)
                .background(color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // Sun-like highlight toward the upper left.
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.38f), Color.Transparent),
                            center = androidx.compose.ui.geometry.Offset(corePx * 0.38f, corePx * 0.32f),
                            radius = corePx * 0.8f,
                        ),
                    ),
            )
            content()
        }
    }
}

/**
 * A 40 dp outlined icon chip on the player's transparent top row, inside a
 * 48 dp tap target.
 */
@Composable
private fun OutlinedIconChip(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(scheme.surface.copy(alpha = 0.5f), CircleShape)
                .border(1.5.dp, scheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp),
                tint = scheme.onSurface,
            )
        }
    }
}

/**
 * The entry-type label as a tonal chip with a small icon (D-05) instead of
 * plain text: a mint stadium with note / star / tag.
 */
@Composable
private fun TypeChip(type: EntryType, label: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .background(scheme.secondaryContainer, RoundedCornerShape(500.dp))
            .padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = EntryPresentation.icon(type),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = scheme.onSecondaryContainer,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSecondaryContainer,
        )
    }
}

/**
 * The single big control (D-04): filled in primary with its action icon —
 * pause / play is the app's most-tapped control, so it reads as the primary
 * action. 260 dp stadium, 72 dp tall; disabled at 45% when idle.
 */
@Composable
private fun BigControlButton(
    enabled: Boolean,
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .width(260.dp)
            .height(72.dp)
            .shadow(16.dp, shape = RoundedCornerShape(36.dp), clip = false),
        shape = RoundedCornerShape(36.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = scheme.primary,
            contentColor = scheme.onPrimary,
            disabledContainerColor = scheme.primary.copy(alpha = 0.45f),
            disabledContentColor = scheme.onPrimary,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.headlineSmall,
            )
        }
    }
}
