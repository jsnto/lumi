package com.lumi.player.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.lumi.player.data.EntryType

/**
 * How each entry type is presented on the screens: the emoji shown for it
 * and the accent color it is drawn in. One source of truth so the front
 * screen and the settings list never describe the same type differently.
 */
object EntryPresentation {

    fun emoji(type: EntryType): String = when (type) {
        EntryType.AUDIO -> "🎵"
        EntryType.VIDEO -> "🎬"
        EntryType.URL -> "🔗"
    }

    /**
     * The vector icon shown in a type's tonal chip (D-03 / D-05): a small
     * note for music, a star for video, a tag for links. The emoji stays the
     * face of the big blob; the chip is the quiet label.
     */
    fun icon(type: EntryType): ImageVector = when (type) {
        EntryType.AUDIO -> Icons.Filled.MusicNote
        EntryType.VIDEO -> Icons.Filled.Star
        EntryType.URL -> Icons.Filled.Label
    }

    fun color(type: EntryType): Color = when (type) {
        // Bright candy accents: vivid enough to read in the light scheme's
        // warm gradient AND the dark scheme's cocoa night.
        EntryType.AUDIO -> Color(0xFFFF8A3D)
        EntryType.VIDEO -> Color(0xFFB388FF)
        EntryType.URL -> Color(0xFF4DD0E1)
    }
}
