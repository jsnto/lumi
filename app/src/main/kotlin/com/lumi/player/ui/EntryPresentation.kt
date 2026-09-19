package com.lumi.player.ui

import androidx.compose.ui.graphics.Color
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

    fun color(type: EntryType): Color = when (type) {
        EntryType.AUDIO -> Color(0xFFFF8A65)
        EntryType.VIDEO -> Color(0xFF9575CD)
        EntryType.URL -> Color(0xFF4DB6AC)
    }
}
