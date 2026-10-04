package com.lumi.player.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.withTransaction
import com.lumi.player.player.EntrySpec

/** Default number of tags whose last played position is remembered (LRU). */
const val MAX_REMEMBERED_TAGS = 3

/**
 * Upper bound of the remembered-positions setting (the Advanced options
 * page's "Remembered playback positions"): how many tags' last played
 * position the LRU keeps. The setting clamps its count to 1..
 * [MAX_REMEMBERED_POSITIONS]; [MAX_REMEMBERED_TAGS] (3) is its default.
 */
const val MAX_REMEMBERED_POSITIONS = 20

/**
 * Reads the stored remembered-positions count. Unset or invalid values -
 * and a count outside the setting's 1..[MAX_REMEMBERED_POSITIONS] range -
 * fall back to the default ([MAX_REMEMBERED_TAGS]).
 */
fun rememberedPositionsFromStored(value: String?): Int =
    value?.trim()?.toIntOrNull()?.takeIf { it in 1..MAX_REMEMBERED_POSITIONS }
        ?: MAX_REMEMBERED_TAGS

/** The settings-table value a remembered-positions count stores under. */
fun rememberedPositionsStoredValue(count: Int): String =
    count.coerceIn(1, MAX_REMEMBERED_POSITIONS).toString()

/**
 * The last played position of an NFC tag's content list.
 *
 * The position is per tag, not per file: [queueFingerprint] identifies the
 * exact linked list the position was saved against, so a parent swapping the
 * audio file linked to a tag resets the tag to the beginning (see
 * [decideRestore]).
 */
@Entity(tableName = "tag_positions", primaryKeys = ["tag_uid"])
data class TagPositionEntity(
    @ColumnInfo(name = "tag_uid") val tagUid: String,
    /** Offset in milliseconds within the entry at [entryIndex]. */
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    /** Index of the entry in the tag's linked list the offset applies to. */
    @ColumnInfo(name = "entry_index") val entryIndex: Int,
    @ColumnInfo(name = "queue_fingerprint") val queueFingerprint: String,
    @ColumnInfo(name = "last_played_at") val lastPlayedAt: Long,
)

/** How a tag's content list should start. */
sealed interface PositionRestore {
    /** Start from the first entry at the beginning. */
    data object StartFresh : PositionRestore

    /** Start at [entryIndex] and seek to [positionMs] within that entry. */
    data class SeekTo(val entryIndex: Int, val positionMs: Long) : PositionRestore
}

/**
 * Decides how to start a tag's list from its saved [saved] position.
 *
 * A saved row only applies when it was saved against the same linked list
 * (a changed list — e.g. the parent swapping the audio file — resets the
 * tag to the beginning) and the saved entry still exists in the list at
 * that index. A position of zero is the beginning and needs no seek.
 */
fun decideRestore(
    saved: TagPositionEntity?,
    queueFingerprint: String,
    queueSize: Int,
): PositionRestore {
    if (saved == null) return PositionRestore.StartFresh
    if (saved.queueFingerprint != queueFingerprint) return PositionRestore.StartFresh
    if (saved.entryIndex !in 0 until queueSize) return PositionRestore.StartFresh
    return if (saved.positionMs > 0) {
        PositionRestore.SeekTo(saved.entryIndex, saved.positionMs)
    } else {
        PositionRestore.StartFresh
    }
}

/**
 * Stable identifier of a tag's linked content list. Changes when the
 * entries or their order change, so a position saved against an older list
 * is recognized as stale.
 */
fun fingerprintOf(entries: List<EntrySpec>): String =
    entries.joinToString("|") { "${it.type}:${it.payload}" }.hashCode().toString(16)

/**
 * LRU eviction: the UIDs to delete so that, once [incoming] is stored, at
 * most [capacity] rows remain. The least-recently played tag (oldest
 * [TagPositionEntity.lastPlayedAt]) is evicted first. Storing a row for a
 * tag that is already remembered never evicts.
 */
fun lruEvictions(
    existing: List<TagPositionEntity>,
    incoming: TagPositionEntity,
    capacity: Int = MAX_REMEMBERED_TAGS,
): List<String> {
    val others = existing.filter { it.tagUid != incoming.tagUid }
    val toFree = others.size - (capacity - 1)
    if (toFree <= 0) return emptyList()
    return others
        .sortedBy { it.lastPlayedAt }
        .take(toFree)
        .map { it.tagUid }
}

/** Room-backed store for [TagPositionEntity] rows with LRU capacity. */
class TagPositionStore(private val db: LumiDatabase) {

    private val dao: LumiDao get() = db.dao()

    /**
     * Records [entity], evicting the oldest rows so at most [capacity] rows
     * remain. The caller passes the user's configured remembered-positions
     * count (the Advanced options page's setting); the default is the
     * hard-coded capacity the setting replaces.
     */
    suspend fun remember(entity: TagPositionEntity, capacity: Int = MAX_REMEMBERED_TAGS) {
        db.withTransaction {
            lruEvictions(dao.allPositions(), entity, capacity).forEach { dao.deletePosition(it) }
            dao.upsertPosition(entity)
        }
    }
}
