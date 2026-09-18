package com.lumi.player.data

/**
 * How the app reacts to NFC tags.
 *
 * [CONTINUOUS]: a tag tap starts playback and it keeps going until a
 * different tag is tapped (re-tapping the same tag does not restart).
 * [PROXIMITY]: playback continues only while the tag stays in the reader's
 * range; removing the tag stops playback.
 */
enum class PlayMode(val storageValue: String) {
    CONTINUOUS("continuous"),
    PROXIMITY("proximity");

    companion object {
        fun fromStorage(value: String): PlayMode =
            entries.firstOrNull { it.storageValue.equals(value, ignoreCase = true) } ?: CONTINUOUS
    }
}
