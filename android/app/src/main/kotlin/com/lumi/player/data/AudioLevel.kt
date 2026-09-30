package com.lumi.player.data

/**
 * Whether the app normalizes playback loudness.
 *
 * [OFF]: every song plays at its recorded level (the app never applies a
 * gain). [NORMALIZE]: each measured song gets a per-track gain so its loudness
 * lands at the target level (see [AudioLevelSettings.targetLufs]), capped by
 * the track's peak headroom.
 */
enum class AudioLevelMode(val storageValue: String) {
    OFF("off"),
    NORMALIZE("normalize");

    companion object {
        /**
         * Reads a stored audio level mode. A stored value - either one - wins;
         * unset or invalid stored values fall back to [OFF] (the app's
         * default: no gain is applied until the user turns normalization on).
         */
        fun fromStorage(value: String?): AudioLevelMode =
            entries.firstOrNull { it.storageValue.equals(value?.trim(), ignoreCase = true) }
                ?: OFF
    }
}

/**
 * The audio level setting: the mode plus the target integrated loudness
 * (LUFS) a measured song is brought to. Persisted in the generic settings
 * table under [SettingsEntity.AUDIO_LEVEL_MODE] /
 * [SettingsEntity.AUDIO_LEVEL_TARGET_LUFS] (no migration).
 */
data class AudioLevelSettings(
    val mode: AudioLevelMode,
    val targetLufs: Double,
) {
    companion object {
        /** The default target loudness: Spotify-style normal loudness. */
        const val DEFAULT_TARGET_LUFS = -16.0

        /** The target levels the picker offers (quieter -> louder). */
        val TARGETS = listOf(-18.0, -16.0, -14.0)

        val DEFAULT = AudioLevelSettings(AudioLevelMode.OFF, DEFAULT_TARGET_LUFS)
    }
}

/**
 * Reads a stored target loudness. A stored value from [TARGETS] wins (the
 * "-16" / "-18" / "-14" spellings, fractional or not); unset or invalid
 * stored values fall back to [DEFAULT_TARGET_LUFS].
 */
fun audioLevelTargetLufsFromStored(value: String?): Double =
    value?.trim()?.toDoubleOrNull()?.takeIf { it in AudioLevelSettings.TARGETS }
        ?: AudioLevelSettings.DEFAULT_TARGET_LUFS
