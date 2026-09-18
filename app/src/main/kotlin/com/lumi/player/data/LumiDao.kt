package com.lumi.player.data

import androidx.room.Dao
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

    @Insert
    suspend fun insertTag(tag: TagEntity): Long

    @Delete
    suspend fun deleteTag(tag: TagEntity)

    // ---- entries ----

    @Query("SELECT * FROM entries ORDER BY createdAt ASC, id ASC")
    fun observeEntries(): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun entryById(id: Long): EntryEntity?

    @Insert
    suspend fun insertEntry(entry: EntryEntity): Long

    @Delete
    suspend fun deleteEntry(entry: EntryEntity)

    // ---- tag -> entry links (ordered) ----

    @Query("SELECT * FROM tag_links WHERE tagId = :tagId ORDER BY sortOrder ASC, id ASC")
    fun observeLinksForTag(tagId: Long): Flow<List<TagLinkEntity>>

    @Query("SELECT * FROM tag_links WHERE tagId = :tagId ORDER BY sortOrder ASC, id ASC")
    suspend fun linksForTag(tagId: Long): List<TagLinkEntity>

    @Query("DELETE FROM tag_links WHERE tagId = :tagId")
    suspend fun clearLinksForTag(tagId: Long)

    @Insert
    suspend fun insertLink(link: TagLinkEntity)

    // ---- settings ----

    @Query("SELECT * FROM settings")
    fun observeSettings(): Flow<List<SettingsEntity>>

    @Query("SELECT * FROM settings WHERE `key` = :key LIMIT 1")
    suspend fun setting(key: String): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSetting(setting: SettingsEntity)
}
