package com.lumi.player.data

/**
 * How the app reacts to NFC tags.
 *
 * [CONTINUOUS]: reading a tag starts playback and it keeps going after the
 * tag is removed, until a different tag is read (or the user pauses).
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
