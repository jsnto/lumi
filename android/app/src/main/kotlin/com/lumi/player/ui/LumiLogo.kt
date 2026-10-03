package com.lumi.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The record face's cream (the launcher icon's own #FFF3E0). */
val LumiLogoFace = Color(0xFFFFF3E0)

/**
 * The app's lumi logo: the "sunny record face" - the launcher icon's own
 * concentric design (an accent ring, the cream face, the accent spindle),
 * drawn flat as circles so no square or box shows around it. [accent] is
 * the color the face sits in (the launcher's orange, the front screen's
 * blob color), so the same design reads in both places.
 */
@Composable
fun LumiLogo(
    accent: Color,
    modifier: Modifier = Modifier,
    size: Dp = 124.dp,
) {
    // The launcher icon's own ratios: the face is ~0.61 of the full face
    // diameter, the spindle ~0.29 of the face.
    val face = size * 0.61f
    val dot = face * 0.29f
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.size(face).background(LumiLogoFace, CircleShape))
        Box(Modifier.size(dot).background(accent, CircleShape))
    }
}
