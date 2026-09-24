package com.lumi.player.data

/**
 * The app's appearance mode (the settings screen's "Appearance" choice):
 * follow the system (the phone's own light/dark setting - the default, so
 * an existing user is never switched unexpectedly), or force light or
 * dark. The choice persists in the app's key/value settings table and is
 * applied app-wide through the Compose theme, effective immediately on
 * change (no app restart required).
 */
enum class LumiThemeMode {
    FOLLOW_SYSTEM,
    LIGHT,
    DARK,
}

/**
 * Parses the stored appearance choice. Unset or invalid values fall back
 * to [LumiThemeMode.FOLLOW_SYSTEM] - the default: the app tracks the
 * phone's own light/dark setting, so a first launch looks exactly as
 * before the setting existed.
 */
fun themeModeFromStored(value: String?): LumiThemeMode =
    when (value?.trim()?.lowercase()) {
        "light" -> LumiThemeMode.LIGHT
        "dark" -> LumiThemeMode.DARK
        else -> LumiThemeMode.FOLLOW_SYSTEM
    }

/** The value [LumiThemeMode] is persisted with. */
fun LumiThemeMode.storedValue(): String =
    when (this) {
        LumiThemeMode.FOLLOW_SYSTEM -> "system"
        LumiThemeMode.LIGHT -> "light"
        LumiThemeMode.DARK -> "dark"
    }
