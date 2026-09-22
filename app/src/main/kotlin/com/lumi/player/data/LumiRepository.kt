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

    /** The individual content list: the stored rows with no folder marker. */
    fun observeIndividualEntries(): Flow<List<EntryEntity>> = dao.observeIndividualEntries()

    /** The tags that link the entry (the song profile's assign-to-tag flags), live. */
    fun observeTagsForEntry(entryId: Long): Flow<List<TagEntity>> = dao.observeTagsForEntry(entryId)

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

    /**
     * Stores a new entry. [folderId] marks the row as imported from that
     * folder (the folder-import path); individually added rows store with no
     * marker.
     */
    suspend fun addEntry(type: EntryType, label: String, payload: String, folderId: Long? = null): EntryEntity {
        val id = dao.insertEntry(
            EntryEntity(
                type = type.storageValue,
                label = label.trim().ifEmpty { "Untitled" },
                payload = payload,
                folderId = folderId,
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
    suspend fun addEntryUnique(type: EntryType, label: String, payload: String, folderId: Long? = null): EntryEntity? {
        if (dao.entryByPayload(payload) != null) return null
        return addEntry(type, label, payload, folderId)
    }

    /**
     * Links an ALREADY-STORED entry to the end of [tagId]'s song list - the
     * in-app add-song flow (a tag's list is an assignment over the app's
     * existing content, not a store of new files). Linking the existing row
     * means a song already on another tag links without copying its entry.
     * Returns false when the tag or the entry no longer exists (deleted
     * while the pick was in flight) or when the tag already links the entry
     * (a second pick of the same song must not duplicate the link).
     */
    suspend fun linkEntryToTag(tagId: Long, entryId: Long): Boolean {
        return db.withTransaction {
            if (dao.tagById(tagId) == null) return@withTransaction false
            if (dao.entryById(entryId) == null) return@withTransaction false
            if (dao.linkCount(tagId, entryId) > 0) return@withTransaction false
            dao.insertLink(
                TagLinkEntity(
                    tagId = tagId,
                    entryId = entryId,
                    sortOrder = dao.linkCountForTag(tagId),
                )
            )
            true
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

    /**
     * Marks the stored rows whose payload is in [payloads] as imported from
     * [folderId], without re-marking rows that already carry a folder marker
     * (the import-time stamp and the v3 -> v4 backfill both go through it).
     * The payloads are matched in [PAYLOAD_QUERY_BATCH]-sized IN-clauses
     * (the SQLite bound-variable cap). Returns the number of rows marked.
     */
    suspend fun markEntriesFromFolder(folderId: Long, payloads: Set<String>): Int =
        payloads
            .chunked(PAYLOAD_QUERY_BATCH)
            .sumOf { chunk -> dao.markEntriesFromFolder(folderId, chunk.toSet()) }


    /** The set of every stored entry's payload (content uris). */
    suspend fun storedEntryPayloads(): Set<String> = dao.allEntryPayloads().toSet()

    /**
     * The number of stored content rows whose payload is in [payloads] - the
     * rows a folder removal would delete (the same payload match the rescan
     * uses for its "already stored" filter). An empty set counts as zero
     * without a query.
     */
    suspend fun countEntriesByPayloads(payloads: Set<String>): Int =
        payloads
            .chunked(PAYLOAD_QUERY_BATCH)
            .sumOf { chunk -> dao.countEntriesByPayloads(chunk.toSet()) }

    /**
     * The stored content rows whose payload is in [payloads] - the folder
     * detail page's song list (the rows a folder's walk identified, in the
     * content list's order). An empty set returns nothing without a query.
     *
     * The payloads are matched in [PAYLOAD_QUERY_BATCH]-sized IN-clauses
     * (the SQLite bound-variable cap), and each clause answers its own rows
     * in (createdAt, id) order. A payload set that spans more than one
     * clause would otherwise come back clause-by-clause (in walk order), so
     * the concatenated rows are re-sorted here with the SAME (createdAt
     * ASC, id ASC) comparator to keep the content list's order intact
     * across the clause boundary.
     */
    suspend fun entriesByPayloads(payloads: Set<String>): List<EntryEntity> =
        payloads
            .chunked(PAYLOAD_QUERY_BATCH)
            .flatMap { chunk -> dao.entriesByPayloads(chunk.toSet()) }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))

    /**
     * Removes a remembered folder and the content rows imported from it in
     * ONE Room transaction, so a partial failure can't leave the folder
     * gone with its songs still stored (or the songs gone with the folder
     * still rescannable). [importedPayloads] are the content uris the
     * folder's walk identified - rows the folder didn't identify are
     * untouched - and tag links to the deleted rows cascade off them the
     * same way a single-entry removal does. Returns the number of content
     * rows deleted (0 for an empty set - e.g. a folder whose tree can no
     * longer be read, where only the folder row is removed). A row that
     * survives the deletion (a shared song another folder also imports) is
     * UNMARKED in the same transaction, so it degrades into the individual
     * content list.
     */
    suspend fun removeFolder(folder: FolderEntity, importedPayloads: Set<String>): Int =
        db.withTransaction {
            val removed = importedPayloads
                .chunked(PAYLOAD_QUERY_BATCH)
                .sumOf { chunk -> dao.deleteEntriesByPayloads(chunk.toSet()) }
            dao.unmarkEntriesFromFolder(folder.id)
            dao.deleteFolder(folder)
            removed
        }

    // ---- links ----

    /** The tag's current song list in link order (the scan-first read). */
    suspend fun entriesForTag(tagId: Long): List<EntryEntity> = dao.entriesForTag(tagId)

    /**
     * Unlinks ONE entry from ONE tag - the inverse of [linkEntryToTag]:
     * only the tag's link to the entry goes. The entry itself stays in the
     * content list (its folder membership untouched), its links to any
     * other tags are untouched, and the remaining songs keep their relative
     * link order - their sortOrder values are renumbered densely to 0..n-1
     * in the current read order, so a later append (sortOrder = the tag's
     * link count) still lands at the end. Returns false when the tag or the
     * entry no longer exists (deleted while the removal was in flight) or
     * when the tag does not link the entry.
     */
    suspend fun unlinkEntryFromTag(tagId: Long, entryId: Long): Boolean {
        return db.withTransaction {
            if (dao.tagById(tagId) == null) return@withTransaction false
            if (dao.entryById(entryId) == null) return@withTransaction false
            val current = dao.entriesForTag(tagId).map { it.id }
            if (current.isEmpty()) return@withTransaction false
            val next = current.filterNot { it == entryId }
            if (next == current) return@withTransaction false
            dao.replaceLinksForTag(
                tagId,
                next.mapIndexed { index, id ->
                    TagLinkEntity(tagId = tagId, entryId = id, sortOrder = index)
                },
            )
            true
        }
    }

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

    // ---- settings: content sort direction ----

    /**
     * The content list's title sort direction. Unset or invalid stored
     * values fall back to descending - the content list's default order.
     */
    fun observeContentSortDirection(): Flow<ContentSortDirection> =
        dao.observeSettings().map { rows ->
            contentSortDirectionFromStored(
                rows.firstOrNull { it.key == SettingsEntity.CONTENT_SORT_DIRECTION }?.value,
            )
        }

    suspend fun setContentSortDirection(direction: ContentSortDirection) {
        dao.upsertSetting(
            SettingsEntity(SettingsEntity.CONTENT_SORT_DIRECTION, direction.storedValue()),
        )
    }

    // ---- settings: keep screen on ----

    /**
     * Whether the user wants the screen to stay on while the app is open.
     * Unset or invalid stored values fall back to off - the default: the
     * screen follows the system timeout while the app is open.
     */
    fun observeKeepScreenOn(): Flow<Boolean> =
        dao.observeSettings().map { rows ->
            rows.any { it.key == SettingsEntity.KEEP_SCREEN_ON && it.value == "1" }
        }

    suspend fun setKeepScreenOn(on: Boolean) {
        dao.upsertSetting(
            SettingsEntity(SettingsEntity.KEEP_SCREEN_ON, if (on) "1" else "0"),
        )
    }

    companion object {
        /** Default absence window (seconds) before a removed tag pauses playback. */
        const val NFC_REREAD_SECONDS_DEFAULT = 15L

        /** Inclusive range the re-read wait (seconds) is allowed to take. */
        val NFC_REREAD_SECONDS_RANGE = 1L..120L

        /** Payload-set queries go in batches this large: SQLite before 3.32 (Android <= 11) caps bound variables at 999. */
        const val PAYLOAD_QUERY_BATCH = 900
    }
}
