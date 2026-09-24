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
import com.lumi.player.data.LumiThemeMode

/**
 * The child-friendly "Sunlit amber" family (lumi's sun-blob identity) as
 * modern Material 3 tonal roles: bright, warm, high-contrast candy colors
 * over soft containers, generously rounded surfaces, and plenty of
 * whitespace. Both palettes are designed for children: the light scheme is
 * the settled "Sunlit amber" B token set (the "Sunlit amber" palette,
 * D-01), and the dark scheme is a designed warm night palette (deep cocoa,
 * not grey, with bright candy accents) - never an inversion of the light
 * one.
 *
 * [LumiTheme] applies the mode the user picked in the settings screen's
 * "Appearance" choice ([LumiThemeMode]): follow the system (the phone's own
 * light/dark setting - the default), or force light or dark. The choice is
 * effective immediately on change (no app restart required).
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFFF5761E),
    onPrimary = Color(0xFF3A1502),
    primaryContainer = Color(0xFFFFE3C8),
    onPrimaryContainer = Color(0xFF381502),
    secondary = Color(0xFF00897B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC9EBE1),
    onSecondaryContainer = Color(0xFF00342F),
    tertiary = Color(0xFF7C4DFF),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE9E1FB),
    onTertiaryContainer = Color(0xFF21005D),
    background = Color(0xFFFFFBF2),
    onBackground = Color(0xFF2B1708),
    surface = Color(0xFFFFFEFB),
    onSurface = Color(0xFF2B1708),
    surfaceContainerLow = Color(0xFFFAF3E7),
    surfaceContainer = Color(0xFFF4EBDC),
    surfaceContainerHigh = Color(0xFFEDE2CF),
    onSurfaceVariant = Color(0xFF5B4B3F),
    outline = Color(0xFF7A6250),
    outlineVariant = Color(0xFFD4BFAF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFA24D),
    onPrimary = Color(0xFF3A1502),
    primaryContainer = Color(0xFF8A3A05),
    onPrimaryContainer = Color(0xFFFFDDBF),
    secondary = Color(0xFF4DD0C4),
    onSecondary = Color(0xFF00372F),
    secondaryContainer = Color(0xFF0E4F47),
    onSecondaryContainer = Color(0xFFBDF3EA),
    tertiary = Color(0xFFB39DFF),
    onTertiary = Color(0xFF2A1563),
    tertiaryContainer = Color(0xFF4F378B),
    onTertiaryContainer = Color(0xFFE8DEFF),
    background = Color(0xFF140F0A),
    onBackground = Color(0xFFF6ECDF),
    surface = Color(0xFF1C150F),
    onSurface = Color(0xFFF6ECDF),
    surfaceContainerLow = Color(0xFF221A13),
    surfaceContainer = Color(0xFF291F17),
    surfaceContainerHigh = Color(0xFF34281F),
    onSurfaceVariant = Color(0xFFD9C6B1),
    outline = Color(0xFFA08A72),
    outlineVariant = Color(0xFF4E3F32),
)

/**
 * Generous corner radii for the soft, toy-like look: every card, chip and
 * button leans rounded.
 */
private val LumiShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/**
 * Friendly, readable typography with a pushed weight hierarchy (D-02,
 * settled): the wordmark and the player label run ExtraBold, dialog titles
 * and top-bar titles Bold, row titles Bold, and body text runs a touch
 * heavier so it reads easily at arm's length. Sizes are unchanged so
 * layouts keep their current geometry.
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
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Medium,
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
fun LumiTheme(
    themeMode: LumiThemeMode = LumiThemeMode.FOLLOW_SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        LumiThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        LumiThemeMode.LIGHT -> false
        LumiThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = LumiShapes,
        typography = LumiTypography,
        content = content,
    )
}
