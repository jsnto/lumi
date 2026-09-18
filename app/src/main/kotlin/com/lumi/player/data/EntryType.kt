package com.lumi.player.data

/**
 * The kind of content a tag entry points at.
 *
 * v1 fully implements [AUDIO]. [VIDEO] and [URL] are representable and
 * registrable today so the data model, storage, and registration UI already
 * accommodate them; their actions (play video / open URL) are deferred and
 * shown as a "coming soon" placeholder.
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
