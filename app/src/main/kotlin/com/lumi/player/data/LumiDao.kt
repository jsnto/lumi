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

    @Query("SELECT * FROM tags ORDER BY createdAt ASC, id ASC")
    fun observeTags(): Flow<List<TagEntity>>

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
     * Deletes the stored content rows whose payload is in [payloads]; tag
     * links to the deleted rows cascade off with them, the same way a
     * single-entry deletion does.
     */
    @Query("DELETE FROM entries WHERE payload IN (:payloads)")
    suspend fun deleteEntriesByPayloads(payloads: Set<String>): Int

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

    /** A tag's entries in link order (one join instead of a query per link). */
    @Query(
        "SELECT e.* FROM entries e " +
            "JOIN tag_links l ON e.id = l.entryId " +
            "WHERE l.tagId = :tagId " +
            "ORDER BY l.sortOrder ASC, l.id ASC",
    )
    suspend fun entriesForTag(tagId: Long): List<EntryEntity>

    @Query("DELETE FROM tag_links WHERE tagId = :tagId")
    suspend fun clearLinksForTag(tagId: Long)

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

    // ---- remembered tag playback positions ----

    @Query("SELECT * FROM tag_positions WHERE tag_uid = :uid LIMIT 1")
    suspend fun positionByUid(uid: String): TagPositionEntity?

    @Query("SELECT * FROM tag_positions ORDER BY last_played_at ASC, tag_uid ASC")
    suspend fun allPositions(): List<TagPositionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPosition(position: TagPositionEntity)

    @Query("DELETE FROM tag_positions WHERE tag_uid = :uid")
    suspend fun deletePosition(uid: String)
}
