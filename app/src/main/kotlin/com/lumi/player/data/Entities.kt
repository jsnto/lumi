package com.lumi.player.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A registered NFC tag, identified by its NFC UID. */
@Entity(tableName = "tags", indices = [Index(value = ["uid"], unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * A piece of content that can be assigned to a tag.
 *
 * [type] is the [EntryType.storageValue]; [payload] carries the type-specific
 * data (a file URI for audio/video, a URL for url entries).
 */
@Entity(tableName = "entries")
data class EntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val label: String,
    val payload: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Ordered association from a tag to an entry. A tag's content list is the set
 * of links sorted by [sortOrder].
 */
@Entity(
    tableName = "tag_links",
    foreignKeys = [
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = EntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tagId"]), Index(value = ["entryId"])],
)
data class TagLinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tagId: Long,
    val entryId: Long,
    val sortOrder: Int,
)

/** Simple key/value settings table. */
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey
    @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String,
) {
    companion object {
        const val PLAY_MODE = "play_mode"

        /** "1" when the user asked not to show the soft-lock prompt again. */
        const val LOCK_POPUP_SUPPRESSED = "lock_popup_suppressed"

        /**
         * Seconds of absence of reports on the active tag (proximity mode)
         * that count as the tag having been removed; "15" by default.
         */
        const val NFC_REREAD_SECONDS = "nfc_reread_seconds"
    }
}
