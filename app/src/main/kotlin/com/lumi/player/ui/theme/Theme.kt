package com.lumi.player.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Warm, playful palette (lumi's sun-blob identity) with modern Material 3
 * tonal roles: soft containers, rounded surfaces, plenty of whitespace.
 * The light scheme is the settled "Sunlit amber" palette (D-01): a cleaner,
 * sunnier push of the same warm family.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFFF5761E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE3C8),
    onPrimaryContainer = Color(0xFF381502),
    secondary = Color(0xFF2F6D68),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC9EBE1),
    onSecondaryContainer = Color(0xFF00342F),
    tertiary = Color(0xFF7A5BC0),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE9E1FB),
    onTertiaryContainer = Color(0xFF21005D),
    background = Color(0xFFFFFBF2),
    onBackground = Color(0xFF221810),
    surface = Color(0xFFFFFEFB),
    onSurface = Color(0xFF221810),
    surfaceContainerLow = Color(0xFFFAF3E7),
    surfaceContainer = Color(0xFFF4EBDC),
    surfaceContainerHigh = Color(0xFFEDE2CF),
    onSurfaceVariant = Color(0xFF5B4B3F),
    outline = Color(0xFF857164),
    outlineVariant = Color(0xFFD4BFAF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB384),
    onPrimary = Color(0xFF542400),
    primaryContainer = Color(0xFF8A3A05),
    onPrimaryContainer = Color(0xFFFFDDBF),
    secondary = Color(0xFF83D5CB),
    onSecondary = Color(0xFF00372F),
    secondaryContainer = Color(0xFF004F47),
    onSecondaryContainer = Color(0xFFB9E9E0),
    tertiary = Color(0xFFD0BCFF),
    onTertiary = Color(0xFF38206A),
    tertiaryContainer = Color(0xFF4F378B),
    onTertiaryContainer = Color(0xFFE8DEFF),
    background = Color(0xFF181310),
    onBackground = Color(0xFFF2E9DF),
    surface = Color(0xFF1E1813),
    onSurface = Color(0xFFF2E9DF),
    surfaceContainerLow = Color(0xFF231C15),
    surfaceContainer = Color(0xFF29221B),
    surfaceContainerHigh = Color(0xFF332B23),
    onSurfaceVariant = Color(0xFFCBC0B5),
    outline = Color(0xFF948275),
    outlineVariant = Color(0xFF4A3E34),
)

/** Generous corner radii for the modern, soft look. */
private val LumiShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * Friendly typography with a pushed weight hierarchy (D-02, settled): the
 * wordmark and the player label run ExtraBold, dialog titles and top-bar
 * titles Bold, row titles Bold. Sizes are unchanged so layouts keep their
 * current geometry.
 */
private val LumiTypography = Typography().copy(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.ExtraBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.ExtraBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
)

@Composable
fun LumiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = LumiShapes,
        typography = LumiTypography,
        content = content,
    )
}
