package com.lumi.player.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Single access point for the local store. All persistence is on-device;
 * there is no server.
 */
class LumiRepository(private val db: LumiDatabase) {

    private val dao: LumiDao get() = db.dao()

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
            val byId = entries.associateBy { it.id }
            links.mapNotNull { byId[it.entryId] }
        }

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

    /** Renames a registered tag; a no-op when the UID is unknown. */
    suspend fun renameTag(uid: String, name: String) {
        dao.tagByUid(uid.trim())?.let { dao.renameTag(it.id, name.trim()) }
    }

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

    /**
     * Stores a new entry, or returns null when an entry with the same
     * payload (the content uri) is already stored - so re-picking a file,
     * or re-adding a folder whose songs are already in the content list,
     * adds no duplicate rows.
     */
    suspend fun addEntryUnique(type: EntryType, label: String, payload: String): EntryEntity? {
        if (dao.entryByPayload(payload) != null) return null
        return addEntry(type, label, payload)
    }

    /**
     * Stores a new entry and appends it to the end of [tagId]'s song list in
     * one Room transaction, so "adding a song onto a tag" never leaves a
     * linked but missing row or a stored but unlinked row. The link order is
     * read through the same join query the rest of the app observes, so the
     * append lands exactly where the current list ends. Returns null when
     * the tag no longer exists (it was deleted while the pick was in flight).
     */
    suspend fun addEntryToTag(tagId: Long, type: EntryType, label: String, payload: String): EntryEntity? {
        return db.withTransaction {
            if (dao.tagById(tagId) == null) return@withTransaction null
            val entry = addEntry(type, label, payload)
            updateTagEntries(tagId) { it + entry.id }
            entry
        }
    }

    suspend fun deleteEntry(entry: EntryEntity) = dao.deleteEntry(entry)

    // ---- added folders (rescannable content) ----

    /**
     * Remembers a picked folder tree so it can be rescanned later. Returns the
     * stored folder; re-remembering the same tree uri is a no-op that returns
     * the existing row, so re-picking a folder adds no duplicate.
     */
    suspend fun rememberFolder(uri: String, label: String): FolderEntity {
        dao.folderByUri(uri)?.let { return it }
        val id = dao.insertFolder(FolderEntity(uri = uri, label = label))
        return checkNotNull(dao.folderById(id))
    }

    fun observeFolders(): Flow<List<FolderEntity>> = dao.observeFolders()

    suspend fun deleteFolder(folder: FolderEntity) = dao.deleteFolder(folder)

    /** The set of every stored entry's payload (content uris). */
    suspend fun storedEntryPayloads(): Set<String> = dao.allEntryPayloads().toSet()

    // ---- links ----

    /** The tag's current song list in link order (the scan-first read). */
    suspend fun entriesForTag(tagId: Long): List<EntryEntity> = dao.entriesForTag(tagId)

    /** Reads and replaces the tag's ordered content list in one Room transaction. */
    suspend fun updateTagEntries(tagId: Long, transform: (List<Long>) -> List<Long>): List<Long> {
        return db.withTransaction {
            val next = transform(entriesForTag(tagId).map { it.id })
            dao.replaceLinksForTag(
                tagId,
                next.mapIndexed { index, entryId ->
                    TagLinkEntity(tagId = tagId, entryId = entryId, sortOrder = index)
                },
            )
            next
        }
    }

    // ---- remembered playback positions ----

    /** The saved last-played position row for [uid], if any. */
    suspend fun positionFor(uid: String): TagPositionEntity? = dao.positionByUid(uid.trim())

    // ---- settings ----

    suspend fun setPlayMode(mode: PlayMode) {
        dao.upsertSetting(SettingsEntity(SettingsEntity.PLAY_MODE, mode.storageValue))
    }

    /** Whether the user asked not to show the soft-lock prompt again. */
    fun observeLockPopupSuppressed(): Flow<Boolean> =
        dao.observeSettings().map { rows ->
            rows.any { it.key == SettingsEntity.LOCK_POPUP_SUPPRESSED && it.value == "1" }
        }

    suspend fun setLockPopupSuppressed(suppressed: Boolean) {
        dao.upsertSetting(
            SettingsEntity(
                SettingsEntity.LOCK_POPUP_SUPPRESSED,
                if (suppressed) "1" else "0",
            ),
        )
    }

    // ---- settings: nfc re-read wait ----

    /**
     * How many seconds of absence of reports on the active tag (proximity
     * mode) count as the tag having been removed. Unset or invalid stored
     * values fall back to the default.
     */
    fun observeNfcRereadSeconds(): Flow<Long> =
        dao.observeSettings().map { rows ->
            rows.firstOrNull { it.key == SettingsEntity.NFC_REREAD_SECONDS }
                ?.value
                ?.toLongOrNull()
                ?.takeIf { it in NFC_REREAD_SECONDS_RANGE }
                ?: NFC_REREAD_SECONDS_DEFAULT
        }

    suspend fun setNfcRereadSeconds(seconds: Long) {
        dao.upsertSetting(
            SettingsEntity(SettingsEntity.NFC_REREAD_SECONDS, seconds.coerceIn(NFC_REREAD_SECONDS_RANGE).toString()),
        )
    }

    companion object {
        /** Default absence window (seconds) before a removed tag pauses playback. */
        const val NFC_REREAD_SECONDS_DEFAULT = 15L

        /** Inclusive range the re-read wait (seconds) is allowed to take. */
        val NFC_REREAD_SECONDS_RANGE = 1L..120L
    }
}
