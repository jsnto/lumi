package com.lumi.player.data

/**
 * The kind of content a tag entry points at.
 *
 * v1 fully implements [AUDIO]. [VIDEO] and [URL] are kept representable so
 * existing rows in the database still parse, but the UI only registers and
 * plays [AUDIO] entries; their actions are not wired up until re-added.
 *
 * New entry types should be added here; [fromStorage] keeps the app running
 * if an unknown type appears in the database.
 */
enum class EntryType(val storageValue: String) {
    AUDIO("audio"),
    VIDEO("video"),
    URL("url");

    companion object {
        fun fromStorage(value: String): EntryType =
            entries.firstOrNull { it.storageValue.equals(value, ignoreCase = true) } ?: AUDIO
    }
}
