package com.lumi.player.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFFE65100),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFCC80),
    onPrimaryContainer = Color(0xFF3E2723),
    secondary = Color(0xFF00695C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB2DFDB),
    onSecondaryContainer = Color(0xFF00372F),
    background = Color(0xFFFFF8E1),
    onBackground = Color(0xFF212121),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF212121),
    tertiary = Color(0xFF4527A0),
    onTertiary = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB74D),
    onPrimary = Color(0xFF3E2723),
    primaryContainer = Color(0xFF4E342E),
    onPrimaryContainer = Color(0xFFFFCC80),
    secondary = Color(0xFF80CBC4),
    onSecondary = Color(0xFF00372F),
    secondaryContainer = Color(0xFF004D40),
    onSecondaryContainer = Color(0xFFB2DFDB),
    background = Color(0xFF1A1A1A),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF212121),
    onSurface = Color(0xFFEDEDED),
    tertiary = Color(0xFFD1C4E9),
    onTertiary = Color(0xFF311B92),
)

@Composable
fun LumiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
