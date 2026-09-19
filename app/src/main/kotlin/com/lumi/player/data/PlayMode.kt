package com.lumi.player.data

/**
 * How the app reacts to NFC tags.
 *
 * [CONTINUOUS]: reading a tag starts playback and it keeps going after the
 * tag is removed, until a different tag is read (or the user pauses).
 * [PROXIMITY]: playback continues only while the tag stays in the reader's
 * range; removing the tag PAUSES playback (the tag stays active), so a
 * re-read of the same tag resumes the paused list instead of restarting it.
 */
enum class PlayMode(val storageValue: String) {
    CONTINUOUS("continuous"),
    PROXIMITY("proximity");

    companion object {
        fun fromStorage(value: String): PlayMode =
            entries.firstOrNull { it.storageValue.equals(value, ignoreCase = true) } ?: CONTINUOUS
    }
}
