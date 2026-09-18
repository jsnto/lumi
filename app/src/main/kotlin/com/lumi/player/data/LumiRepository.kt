package com.lumi.player.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * Single access point for the local store. All persistence is on-device;
 * there is no server.
 */
class LumiRepository(private val dao: LumiDao) {

    // ---- observation ----

    fun observeTags(): Flow<List<TagEntity>> = dao.observeTags()

    fun observeEntries(): Flow<List<EntryEntity>> = dao.observeEntries()

    fun observePlayMode(): Flow<PlayMode> =
        dao.observeSettings().map { rows ->
            rows.firstOrNull { it.key == SettingsEntity.PLAY_MODE }
                ?.let { PlayMode.fromStorage(it.value) }
                ?: PlayMode.CONTINUOUS
        }

    /** Ordered content list for a tag, updated live as links or entries change. */
    fun observeEntriesForTag(tagId: Long): Flow<List<EntryEntity>> =
        dao.observeLinksForTag(tagId).combine(dao.observeEntries()) { links, entries ->
            links.mapNotNull { link -> entries.firstOrNull { it.id == link.entryId } }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeEntriesForTag(tagIdFlow: Flow<Long>): Flow<List<EntryEntity>> =
        tagIdFlow.flatMapLatest { observeEntriesForTag(it) }

    // ---- tags ----

    /**
     * Registers a tag. Returns the stored tag, or null when the UID is
     * already registered or the UID is empty.
     */
    suspend fun addTag(uid: String, name: String): TagEntity? {
        val cleanUid = uid.trim()
        if (cleanUid.isEmpty()) return null
        if (dao.tagByUid(cleanUid) != null) return null
        dao.insertTag(TagEntity(uid = cleanUid, name = name.trim()))
        return dao.tagByUid(cleanUid)
    }

    suspend fun tagByUid(uid: String): TagEntity? = dao.tagByUid(uid.trim())

    suspend fun deleteTag(tag: TagEntity) = dao.deleteTag(tag)

    // ---- entries ----

    suspend fun addEntry(type: EntryType, label: String, payload: String): EntryEntity {
        val id = dao.insertEntry(
            EntryEntity(
                type = type.storageValue,
                label = label.trim().ifEmpty { "Untitled" },
                payload = payload,
            )
        )
        return checkNotNull(dao.entryById(id))
    }

    suspend fun deleteEntry(entry: EntryEntity) = dao.deleteEntry(entry)

    // ---- links ----

    suspend fun entriesForTag(tagId: Long): List<EntryEntity> {
        val links = dao.linksForTag(tagId)
        return links.mapNotNull { link -> dao.entryById(link.entryId) }
    }

    /** Replaces the tag's ordered content list with the given entry ids. */
    suspend fun setTagEntries(tagId: Long, entryIds: List<Long>) {
        dao.clearLinksForTag(tagId)
        entryIds.forEachIndexed { index, entryId ->
            dao.insertLink(TagLinkEntity(tagId = tagId, entryId = entryId, sortOrder = index))
        }
    }

    // ---- settings ----

    suspend fun setPlayMode(mode: PlayMode) {
        dao.upsertSetting(SettingsEntity(SettingsEntity.PLAY_MODE, mode.storageValue))
    }
}
