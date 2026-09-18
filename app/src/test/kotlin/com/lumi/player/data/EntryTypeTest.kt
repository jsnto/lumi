package com.lumi.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

class EntryTypeTest {

    @Test
    fun fromStorage_parsesKnownTypesCaseInsensitively() {
        assertEquals(EntryType.AUDIO, EntryType.fromStorage("audio"))
        assertEquals(EntryType.AUDIO, EntryType.fromStorage("AUDIO"))
        assertEquals(EntryType.VIDEO, EntryType.fromStorage("video"))
        assertEquals(EntryType.URL, EntryType.fromStorage("url"))
        assertEquals(EntryType.URL, EntryType.fromStorage("Url"))
    }

    @Test
    fun fromStorage_fallsBackToAudioForUnknown() {
        assertEquals(EntryType.AUDIO, EntryType.fromStorage("bogus"))
        assertEquals(EntryType.AUDIO, EntryType.fromStorage(""))
    }
}

class PlayModeTest {

    @Test
    fun fromStorage_parsesKnownModes() {
        assertEquals(PlayMode.CONTINUOUS, PlayMode.fromStorage("continuous"))
        assertEquals(PlayMode.PROXIMITY, PlayMode.fromStorage("PROXIMITY"))
    }

    @Test
    fun fromStorage_defaultsToContinuousForUnknown() {
        assertEquals(PlayMode.CONTINUOUS, PlayMode.fromStorage("unknown"))
        assertEquals(PlayMode.CONTINUOUS, PlayMode.fromStorage(""))
    }
}
