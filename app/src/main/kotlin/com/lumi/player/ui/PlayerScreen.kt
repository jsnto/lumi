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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
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
 */
@Composable
fun PlayerScreen(
    viewModel: LumiViewModel,
    modifier: Modifier = Modifier,
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

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            scheme.background,
                            scheme.primaryContainer.copy(alpha = 0.55f),
                            scheme.primaryContainer,
                        ),
                    )
                )
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Soft-lock entry, kept at the top so it is always on screen:
            // engage directly when the pre-lock prompt was suppressed,
            // otherwise ask via the popup first.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(
                    onClick = {
                        if (lockPopupSuppressed) {
                            unlocker.reset()
                            lockEngaged = true
                        } else {
                            showLockDialog = true
                        }
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "Lock the screen",
                        tint = scheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            PlayingVisual(
                active = playState.isPlaying,
                color = type?.let(EntryPresentation::color) ?: Color(0xFF64B5F6),
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
                Spacer(Modifier.size(12.dp))
                Text(
                    text = when (type) {
                        EntryType.AUDIO -> "music"
                        EntryType.VIDEO -> "video"
                        EntryType.URL -> "link"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurfaceVariant,
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
            text = "🔒 Tap the screen 3 times to unlock",
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
    var donShowAgain by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lock the screen?") },
        text = {
            Column {
                Text(
                    text = "The screen will darken, but lumi keeps working: music keeps " +
                        "playing. Tap the screen 3 times to unlock.",
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
            TextButton(onClick = { onConfirm(donShowAgain) }) { Text("Lock") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** The big animated blob that "dances" while something is playing. */
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

    Box(
        modifier = modifier
            .size(260.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(260.dp)
                .background(color.copy(alpha = 0.3f), CircleShape),
        )
        Box(
            modifier = Modifier
                .size(200.dp)
                .shadow(20.dp, shape = CircleShape, clip = false)
                .background(color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
private fun BigControlButton(
    enabled: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .width(240.dp)
            .height(72.dp)
            .shadow(12.dp, shape = RoundedCornerShape(36.dp), clip = false),
        shape = RoundedCornerShape(36.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}
