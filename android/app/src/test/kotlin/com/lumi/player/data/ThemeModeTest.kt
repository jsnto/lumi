package com.lumi.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The appearance mode's persistence semantics: the stored value's parsing
 * and round trip. Unset or invalid values fall back to follow-system - the
 * default that keeps an existing user on the phone's own light/dark
 * setting.
 */
class ThemeModeTest {

    @Test
    fun unsetOrInvalidStoredValuesFallBackToFollowSystem() {
        assertEquals(LumiThemeMode.FOLLOW_SYSTEM, themeModeFromStored(null))
        assertEquals(LumiThemeMode.FOLLOW_SYSTEM, themeModeFromStored(""))
        assertEquals(LumiThemeMode.FOLLOW_SYSTEM, themeModeFromStored("   "))
        assertEquals(LumiThemeMode.FOLLOW_SYSTEM, themeModeFromStored("garbage"))
        // "system" is the stored spelling of the default itself.
        assertEquals(LumiThemeMode.FOLLOW_SYSTEM, themeModeFromStored("system"))
    }

    @Test
    fun lightAndDarkStoredValuesParse() {
        assertEquals(LumiThemeMode.LIGHT, themeModeFromStored("light"))
        assertEquals(LumiThemeMode.DARK, themeModeFromStored("dark"))
        // Parsing is tolerant of case and surrounding whitespace.
        assertEquals(LumiThemeMode.DARK, themeModeFromStored("Dark"))
        assertEquals(LumiThemeMode.LIGHT, themeModeFromStored(" light "))
    }

    @Test
    fun storedValuesRoundTrip() {
        for (mode in LumiThemeMode.entries) {
            assertEquals(mode, themeModeFromStored(mode.storedValue()))
        }
        assertEquals("system", LumiThemeMode.FOLLOW_SYSTEM.storedValue())
        assertEquals("light", LumiThemeMode.LIGHT.storedValue())
        assertEquals("dark", LumiThemeMode.DARK.storedValue())
    }
}
