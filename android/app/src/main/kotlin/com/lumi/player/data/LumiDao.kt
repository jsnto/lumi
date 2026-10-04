package com.lumi.player.data

import androidx.room.Dao
import androidx.room.Transaction
import androidx.room.Insert
import androidx.room.Delete
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LumiDao {

    // ---- tags ----

    /**
     * The tags pane's observed list: name order, case-insensitively, with
     * a stable tie-break for equal names (creation time, then id).
     */
    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE ASC, createdAt ASC, id ASC")
    fun observeTags(): Flow<List<TagEntity>>

    /** A single tag row, live (the save-section in-sync indicator's tag side). */
    @Query("SELECT * FROM tags WHERE id = :id")
    fun observeTagById(id: Long): Flow<TagEntity?>

    @Query("SELECT * FROM tags WHERE uid = :uid LIMIT 1")
    suspend fun tagByUid(uid: String): TagEntity?

    @Query("SELECT * FROM tags WHERE id = :id LIMIT 1")
    suspend fun tagById(id: Long): TagEntity?

    @Insert
    suspend fun insertTag(tag: TagEntity): Long

    @Query("UPDATE tags SET name = :name WHERE id = :id")
    suspend fun renameTag(id: Long, name: String)

    @Delete
    suspend fun deleteTag(tag: TagEntity)

    // ---- entries ----

    @Query("SELECT * FROM entries ORDER BY createdAt ASC, id ASC")
    fun observeEntries(): Flow<List<EntryEntity>>

    /** The individual content list: the stored rows with no folder marker (folder-imported songs stay out of it). */
    @Query("SELECT * FROM entries WHERE folder_id IS NULL ORDER BY createdAt ASC, id ASC")
    fun observeIndividualEntries(): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun entryById(id: Long): EntryEntity?

    @Query("SELECT * FROM entries WHERE payload = :payload LIMIT 1")
    suspend fun entryByPayload(payload: String): EntryEntity?

    @Insert
    suspend fun insertEntry(entry: EntryEntity): Long

    @Delete
    suspend fun deleteEntry(entry: EntryEntity)

    /** The stored content rows whose payload is in [payloads] (a folder removal's match). */
    @Query("SELECT COUNT(*) FROM entries WHERE payload IN (:payloads)")
    suspend fun countEntriesByPayloads(payloads: Set<String>): Int

    /** The stored content rows whose payload is in [payloads] (the per-clause result of the folder detail page's song list - the caller, [LumiRepository.entriesByPayloads], re-sorts the concatenated clauses into the content list's order). */
    @Query("SELECT * FROM entries WHERE payload IN (:payloads) ORDER BY createdAt ASC, id ASC")
    suspend fun entriesByPayloads(payloads: Set<String>): List<EntryEntity>

    /**
     * Deletes the stored content rows whose payload is in [payloads]; a
     * tag's link to a deleted row survives the deletion (tag_links carries
     * no foreign key to entries - the song's name snapshot is what the tag
     * keeps), so the song holds its place on the tag's list as a "song not
     * found" row.
     */
    @Query("DELETE FROM entries WHERE payload IN (:payloads)")
    suspend fun deleteEntriesByPayloads(payloads: Set<String>): Int

    /**
     * Marks the stored rows whose payload is in [payloads] as imported from
     * [folderId], WITHOUT re-marking rows that already carry a folder marker
     * (a song shared by two folders keeps the marker of the folder that
     * stored it first). Drives the folder-import stamping and the v3 -> v4
     * best-effort backfill of rows stored before the marker existed.
     */
    @Query("UPDATE entries SET folder_id = :folderId WHERE payload IN (:payloads) AND folder_id IS NULL")
    suspend fun markEntriesFromFolder(folderId: Long, payloads: Set<String>): Int

    /**
     * Clears the folder marker off [folderId]'s rows (a folder's removal): the
     * songs that survive the removal (a shared song another folder still
     * imports) degrade into the individual content list.
     */
    @Query("UPDATE entries SET folder_id = NULL WHERE folder_id = :folderId")
    suspend fun unmarkEntriesFromFolder(folderId: Long): Int

    // ---- added folders (rescannable content) ----

    @Query("SELECT * FROM folders ORDER BY createdAt ASC, id ASC")
    fun observeFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE id = :id LIMIT 1")
    suspend fun folderById(id: Long): FolderEntity?

    @Query("SELECT * FROM folders WHERE uri = :uri LIMIT 1")
    suspend fun folderByUri(uri: String): FolderEntity?

    @Insert
    suspend fun insertFolder(folder: FolderEntity): Long

    /** Removes a remembered folder row (its songs are removed separately by payload match). */
    @Delete
    suspend fun deleteFolder(folder: FolderEntity)

    /** Every stored entry's payload, so a rescan can skip already-stored files. */
    @Query("SELECT payload FROM entries")
    suspend fun allEntryPayloads(): List<String>

    // ---- tag -> entry links (ordered) ----

    @Query("SELECT * FROM tag_links WHERE tagId = :tagId ORDER BY sortOrder ASC, id ASC")
    fun observeLinksForTag(tagId: Long): Flow<List<TagLinkEntity>>

    /** A tag's links in link order (the rows of the left join). */
    @Query("SELECT * FROM tag_links WHERE tagId = :tagId ORDER BY sortOrder ASC, id ASC")
    suspend fun linksForTag(tagId: Long): List<TagLinkEntity>

    /** All entries in the content list's order (the right side of the tag song rows' name resolution - the first stored copy of a name wins). */
    @Query("SELECT * FROM entries ORDER BY createdAt ASC, id ASC")
    suspend fun entries(): List<EntryEntity>

    /** The first stored content entry with the display name [label], in the content list's order (a tag song's live resolution). */
    @Query("SELECT * FROM entries WHERE label = :label ORDER BY createdAt ASC, id ASC LIMIT 1")
    suspend fun entryByLabel(label: String): EntryEntity?

    @Query("DELETE FROM tag_links WHERE tagId = :tagId")
    suspend fun clearLinksForTag(tagId: Long)

    /** Whether the tag already links a song with the display name [songName] (the add-song flows' "already on the tag" guard - a tag song is identified by name). */
    @Query("SELECT COUNT(*) FROM tag_links WHERE tagId = :tagId AND songName = :songName")
    suspend fun linkCountBySongName(tagId: Long, songName: String): Int

    /** The tags that play a song with the entry's display name (the song profile's assign-to-tag flags - a tag song is identified by name, so a tag that links the name plays this song), live. */
    @Query(
        "SELECT t.* FROM tags t " +
            "JOIN tag_links l ON t.id = l.tagId " +
            "WHERE l.songName = (SELECT label FROM entries WHERE id = :entryId) " +
            "ORDER BY t.createdAt ASC, t.id ASC",
    )
    fun observeTagsForEntry(entryId: Long): Flow<List<TagEntity>>

    /** A tag's current link count (the sortOrder of a freshly appended link). */
    @Query("SELECT COUNT(*) FROM tag_links WHERE tagId = :tagId")
    suspend fun linkCountForTag(tagId: Long): Int

    @Insert
    suspend fun insertLink(link: TagLinkEntity)

    /** Atomically replaces a tag's ordered link list in a single transaction. */
    @Transaction
    suspend fun replaceLinksForTag(tagId: Long, links: List<TagLinkEntity>) {
        clearLinksForTag(tagId)
        links.forEach { insertLink(it) }
    }

    // ---- settings ----

    @Query("SELECT * FROM settings")
    fun observeSettings(): Flow<List<SettingsEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSetting(setting: SettingsEntity)

    // ---- per-card memory (the last saved name + songs, survives tag deletion) ----

    @Query("SELECT * FROM tag_memory WHERE uid = :uid LIMIT 1")
    suspend fun memoryByUid(uid: String): TagMemoryEntity?

    /** The per-uid memory row, live (the save-section in-sync indicator's saved side). */
    @Query("SELECT * FROM tag_memory WHERE uid = :uid")
    fun observeMemoryByUid(uid: String): Flow<TagMemoryEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemory(memory: TagMemoryEntity)

    /** Clears the per-uid memory record (a compare scan of a card that carries no lumi data). */
    @Query("DELETE FROM tag_memory WHERE uid = :uid")
    suspend fun deleteMemoryByUid(uid: String)

    // ---- remembered tag playback positions ----

    @Query("SELECT * FROM tag_positions WHERE tag_uid = :uid LIMIT 1")
    suspend fun positionByUid(uid: String): TagPositionEntity?

    @Query("SELECT * FROM tag_positions ORDER BY last_played_at ASC, tag_uid ASC")
    suspend fun allPositions(): List<TagPositionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPosition(position: TagPositionEntity)

    @Query("DELETE FROM tag_positions WHERE tag_uid = :uid")
    suspend fun deletePosition(uid: String)

    // ---- per-entry loudness (the R128 measurement cache) ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntryLoudness(entry: EntryLoudnessEntity): Long

    @Query("SELECT * FROM entry_loudness WHERE entry_id = :entryId LIMIT 1")
    suspend fun entryLoudness(entryId: Long): EntryLoudnessEntity?

    /** The measurement rows, live (the audio level status row's measured count). */
    @Query("SELECT * FROM entry_loudness")
    fun observeEntryLoudness(): Flow<List<EntryLoudnessEntity>>

    /**
     * The measurement rows joined to their content rows' payload: the
     * [LoudnessStore]'s service snapshot (keyed by the payload the player
     * actually loads - a payload shared by two entries maps to the same
     * measurement).
     */
    @Query(
        "SELECT l.entry_id, l.lufs, l.true_peak_db_tp, l.fingerprint, l.measured_at, e.payload " +
            "FROM entry_loudness l JOIN entries e ON e.id = l.entry_id",
    )
    fun observeEntryLoudnessWithPayload(): Flow<List<EntryLoudnessPayload>>
}
