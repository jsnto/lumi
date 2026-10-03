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
 *
 * [folderId] marks a row imported from a folder (that folder's [FolderEntity.id]):
 * such songs stay OUT of the individual content list - they are visible only
 * inside their folder's page. Rows the user added one by one carry no marker
 * (`folderId == null`); a folder's removal clears the marker off its rows so
 * the songs that are not deleted degrade into the individual list.
 */
@Entity(tableName = "entries")
data class EntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val label: String,
    val payload: String,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "folder_id") val folderId: Long? = null,
)

/**
 * A folder tree the user added as content, remembered so it can be rescanned
 * for audio files added since the last scan. [uri] is the picked tree's own
 * content uri - the scan root - and is unique: re-picking the same folder
 * does not create a second row.
 */
@Entity(tableName = "folders", indices = [Index(value = ["uri"], unique = true)])
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val label: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Ordered association from a tag to a song. A tag's content list is the
 * set of links sorted by [sortOrder].
 *
 * A tag song is identified by NAME, not by the linked entry row: [songName]
 * is a snapshot of the song's display name taken when the link was made,
 * and the row is playable while a content entry with that name exists
 * (evaluated live - a song deleted from the library and later re-imported
 * under the same name becomes playable again without re-adding it). A
 * name that resolves to no content shows on the tag's page as the "song
 * not found" row, rendering its name from this snapshot.
 *
 * The reference to the ENTRY row ([entryId]) is NOT a foreign key and is
 * only a provenance hint (the entry the link was made from): deleting an
 * entry must neither be blocked nor cascade, so a link survives its
 * entry's deletion - the song keeps its place on the tag's list as a
 * "song not found" row instead of vanishing from it. (An ON DELETE NO
 * ACTION foreign key would not work: it blocks the entry's deletion while
 * a link still references it.) It is null for links that were restored
 * from a name list without a live entry (a card payload's song list, a
 * remembered per-uid song list). The link to the tag row cascades off it,
 * as before: deleting a tag takes its song list with it.
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
    ],
    indices = [Index(value = ["tagId"])],
)
data class TagLinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tagId: Long,
    val entryId: Long?,
    val songName: String,
    val sortOrder: Int,
)

/**
 * The last saved name and song list for a card uid - the per-card memory
 * that survives the tag row's deletion. Written on registration (the
 * naming dialog's save) and on a successful card write, keyed by the
 * card's NFC uid (the [TagEntity.uid] string), it is the restore source
 * for a re-scanned card whose payload carries no song list (a card
 * written before the payload carried songs, or one never written):
 * re-scanning the same card after its tag was deleted and saving it again
 * restores the remembered name and songs. Deleting a tag row never
 * destroys the record - the table carries no foreign key to tags.
 *
 * [songs] is the ordered song-name list as a JSON array string (the same
 * shape the tag payload's song list decodes to).
 */
@Entity(tableName = "tag_memory")
data class TagMemoryEntity(
    @PrimaryKey val uid: String,
    val name: String,
    val songs: String,
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

        /** "1" when the user wants the screen to stay on while the app is open. */
        const val KEEP_SCREEN_ON = "keep_screen_on"

        /** "asc"/"desc": the content list's title sort direction; unset sorts ascending. */
        const val CONTENT_SORT_DIRECTION = "content_sort_direction"

        /** "system"/"light"/"dark": the app's appearance mode; unset follows the system. */
        const val THEME_MODE = "theme_mode"

        /**
         * Minutes of idle (no active playback) after which the phone's
         * screen is locked; "0" (off) by default.
         */
        const val AUTO_LOCK_MINUTES = "auto_lock_minutes"

        /** "off"/"normalize": whether the app normalizes playback loudness. */
        const val AUDIO_LEVEL_MODE = "audio_level_mode"

        /** "-18"/"-16"/"-14": the target integrated loudness (LUFS) for normalization. */
        const val AUDIO_LEVEL_TARGET_LUFS = "audio_level_target_lufs"

        /** "1" when the first-time setup wizard has been finished. */
        const val SETUP_COMPLETE = "setup_complete"
    }
}

/**
 * The measured loudness of one content entry (the on-device R128 read),
 * keyed by the entry's id and CASCADEing off it (the entry's removal takes
 * its measurement with it). [lufs] / [truePeakDbTp] are null when the audio
 * carries NO loudness (it is silent, or shorter than one 400 ms window) -
 * the row is still recorded: "measured, nothing to normalize" is a stable
 * state, and a re-measure on a content change overwrites it.
 */
@Entity(
    tableName = "entry_loudness",
    foreignKeys = [
        ForeignKey(
            entity = EntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entry_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EntryLoudnessEntity(
    @PrimaryKey
    @ColumnInfo(name = "entry_id") val entryId: Long,
    @ColumnInfo(name = "lufs") val lufs: Double?,
    @ColumnInfo(name = "true_peak_db_tp") val truePeakDbTp: Double?,
    val fingerprint: String,
    @ColumnInfo(name = "measured_at") val measuredAt: Long,
)
