package com.lumi.player.ui

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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
 * current entry (audio / video / url), what it is, and a single big button.
 */
@Composable
fun PlayerScreen(
    viewModel: LumiViewModel,
    modifier: Modifier = Modifier,
) {
    val playState by PlayerStateHolder.state.collectAsState()
    val scheme = MaterialTheme.colorScheme

    val type = playState.currentType?.let { EntryType.fromStorage(it) }
    val isComingSoon = playState.status == PlaybackStatus.COMING_SOON
    val idle = playState.status == PlaybackStatus.IDLE

    Column(
        modifier = modifier
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
        Spacer(Modifier.weight(1f))

        PlayingVisual(
            active = playState.isPlaying || isComingSoon,
            color = when (type) {
                EntryType.AUDIO -> Color(0xFFFF8A65)
                EntryType.VIDEO -> Color(0xFF9575CD)
                EntryType.URL -> Color(0xFF4DB6AC)
                null -> Color(0xFF64B5F6)
            },
        ) {
            Text(
                text = when (type) {
                    EntryType.AUDIO -> "🎵"
                    EntryType.VIDEO -> "🎬"
                    EntryType.URL -> "🔗"
                    null -> "✨"
                },
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

        if (isComingSoon) {
            Spacer(Modifier.size(12.dp))
            Text(
                text = "Coming soon — skipping ahead…",
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurfaceVariant,
            )
        } else if (type != null) {
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
                PlaybackStatus.COMING_SOON -> "Skip"
                PlaybackStatus.IDLE -> "Stop"
            },
            onClick = {
                val action = when (playState.status) {
                    PlaybackStatus.PLAYING, PlaybackStatus.PAUSED ->
                        PlayerService.ACTION_TOGGLE
                    PlaybackStatus.COMING_SOON ->
                        PlayerService.ACTION_NEXT
                    PlaybackStatus.IDLE ->
                        PlayerService.ACTION_STOP
                }
                viewModel.command(action)
            },
        )

        Spacer(Modifier.size(8.dp))
    }
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
