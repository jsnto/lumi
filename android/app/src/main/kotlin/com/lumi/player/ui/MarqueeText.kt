package com.lumi.player.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.pow

/**
 * One full marquee cycle, in milliseconds: the hold at the title's start
 * (its first screenful, readable at rest), then the sweep across the
 * available width.
 */
internal const val MARQUEE_CYCLE_MS = 6_000

/**
 * The fraction of the cycle the title HOLDS at its start before the sweep:
 * the title must be readable at rest before it scrolls.
 */
internal const val MARQUEE_HOLD_FRACTION = 0.34f

/**
 * The marquee's horizontal offset, in px, at [phase] (0..1) of the cycle
 * for a title that overflows its box by [scrollDistancePx]: the title
 * holds at its start (offset 0) for the first [holdFraction] of the cycle,
 * then eases out of the box to the left (ease-in-out cubic) until its tail
 * fills the box - the sweep's end - and the cycle wraps back to the start.
 * A [scrollDistancePx] of zero or less (the title fits) holds at 0 for the
 * whole cycle.
 */
internal fun marqueeOffsetPx(phase: Float, scrollDistancePx: Float, holdFraction: Float): Float {
    if (scrollDistancePx <= 0f) return 0f
    if (phase <= holdFraction) return 0f
    val progress = ((phase - holdFraction) / (1f - holdFraction)).coerceIn(0f, 1f)
    // ease-in-out cubic: the sweep starts and ends gently.
    val eased =
        if (progress < 0.5f) {
            4f * progress * progress * progress
        } else {
            1f - (2f * (1f - progress)).pow(3) * 0.5f
        }
    return -scrollDistancePx * eased
}

/**
 * A single-line title that auto-scrolls (marquee) only when it is too long
 * to fit: a fitting title renders statically (and is measured, so an
 * overflow - a title that no longer fits - flips into the scroll); an
 * overflowing title holds at its start, eases out of the box to the left
 * (its tail fills the box at the sweep's end), and repeats.
 *
 * The scroll's clipping is the box's own [clipToBounds] (the mirror's
 * [androidx.compose.ui.draw.clipToBounds]): the glyphs that leave the
 * box's width are not drawn past it.
 *
 * The box carries the title's own [contentDescription] while it scrolls:
 * the accessibility service reads the FULL title (not a clipped fragment),
 * and the surfaces that marquee a title (the now-playing surface, the
 * settings menu's now-playing strip, the content pane's song rows) observe
 * it through that description to pin the fit/scroll decision.
 */
@Composable
internal fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    var overflowing by remember(text) { mutableStateOf(false) }
    if (!overflowing) {
        // The fitting title: plain static text (no animation runs for it),
        // measured so a title that is too long to fit flips into the
        // scrolling marquee below.
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = 1,
            modifier = modifier,
            onTextLayout = { result ->
                overflowing = result.hasVisualOverflow
            },
        )
    } else {
        ScrollingMarquee(
            text = text,
            style = style,
            color = color,
            modifier = modifier,
        )
    }
}

/**
 * The scrolling state of [MarqueeText]: the title clipped to the box and
 * translated left by the cycle's offset ([marqueeOffsetPx]). The sweep's
 * distance is how far the title overflows the box - measured from the
 * title's own single-line layout (its visual width) against the box's
 * width - so the sweep ends exactly with the title's tail filling the box.
 * The box carries the FULL title's contentDescription (accessibility: the
 * service reads the whole title, not a clipped fragment).
 */
@Composable
private fun ScrollingMarquee(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier,
) {
    var availablePx by remember { mutableFloatStateOf(0f) }
    var titlePx by remember { mutableFloatStateOf(0f) }
    val scrollDistancePx = titlePx - availablePx
    val transition = rememberInfiniteTransition(label = "lumi-marquee")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(MARQUEE_CYCLE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "lumi-marquee-phase",
    )
    Box(
        modifier = modifier
            .clipToBounds()
            .semantics { contentDescription = text },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = 1,
            // The glyphs are laid out in full: the box's clip (not the
            // text's overflow) is what keeps them inside the box.
            overflow = TextOverflow.Visible,
            modifier = Modifier
                .onSizeChanged { availablePx = it.width.toFloat() }
                .graphicsLayer {
                    translationX = marqueeOffsetPx(phase, scrollDistancePx, MARQUEE_HOLD_FRACTION)
                },
            onTextLayout = { result ->
                // The visual width of the title's single line: the
                // unclamped line width the sweep's distance is measured
                // against (the layout's own size is clamped to the box).
                titlePx = result.multiParagraph.width
            },
        )
    }
}

