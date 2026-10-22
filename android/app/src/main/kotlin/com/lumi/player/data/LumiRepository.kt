package com.lumi.player.data

import androidx.room.withTransaction
import com.lumi.player.nfc.LumiTagNdef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
                ?: PlayMode.PROXIMITY
        }

    /**
     * The tag's "Save to NFC tag" in-sync indicator, live (DERIVED, not
     * event-driven): the tag's current system data (its name + its
     * ordered song display names - exactly what the card-write path
     * composes) against its last known tag state (the per-uid saved
     * memory). Any tag edit (rename, song added/removed/reordered) flips
     * it to [TagSyncState.Differs] at once; a successful write or compare
     * (which records the memory) flips it back when the data matches.
     */
    fun observeTagSync(tagId: Long): Flow<TagSyncState> =
        dao.observeTagById(tagId).flatMapLatest { tag ->
            if (tag == null) {
                flowOf(TagSyncState.Unknown)
            } else {
                dao.observeLinksForTag(tagId).combine(dao.observeMemoryByUid(tag.uid)) { links, memory ->
                    tagSyncState(
                        tag.name,
                        links.map { it.songName },
                        memory?.let { TagMemory(it.name, TagMemory.songsFromJson(it.songs)) },
                    )
                }
            }
        }

    /**
     * A tag's ordered song list, updated live as links or content change.
     * Name-based semantics (a tag song is identified by NAME, not by the
     * linked entry row): every link comes back at its original sort
     * position, and each resolves to the FIRST stored content entry with
     * the link's song name - or to nothing, when no content entry carries
     * the name any more ([TagSongRow.entry] is `null`, the tag's "song not
     * found" row). A song deleted from the library and later re-imported
     * under the same name resolves to the re-imported entry and plays
     * again, without re-adding it to the tag.
     */
    fun observeEntriesForTag(tagId: Long): Flow<List<TagSongRow>> =
        dao.observeLinksForTag(tagId).combine(dao.observeEntries()) { links, entries ->
            // The content list's order (createdAt, id): the first stored
            // copy of a name is the one the row resolves to (groupBy keeps
            // each name's copies in that order; a second stored copy never
            // shadows the first).
            val byName = entries.groupBy { it.label }.mapValues { it.value.first() }
            links.map { link ->
                TagSongRow(
                    linkId = link.id,
                    entryId = link.entryId,
                    sortOrder = link.sortOrder,
                    name = link.songName,
                    entry = byName[link.songName],
                )
            }
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

    /**
     * The first stored content entry with the display name [label] - a tag
     * song's name resolution, first stored copy wins. An exact stored name
     * wins outright; a name of exactly the payload's 50-char clamp
     * ([LumiTagNdef.MAX_SONG_NAME_LENGTH]) that no entry carries exactly -
     * the payload clamps longer stored labels to their own prefix at write
     * time, so such a name can only be a shortened longer label - resolves
     * to the stored entry whose label starts with it, and only when
     * exactly one does (zero or several keep the name unresolved: the
     * tag's visible "song not found" row, never a guess); a name under the
     * clamp that no entry carries exactly resolves to nothing - a short
     * name is never a clamp product, so a prefix hit would silently link
     * a different song.
     */
    suspend fun entryByLabel(label: String): EntryEntity? {
        if (label.isBlank()) return null
        dao.entryByLabel(label)?.let { return it }
        if (label.length != LumiTagNdef.MAX_SONG_NAME_LENGTH) return null
        val prefixMatches = dao.entries().filter { it.label.startsWith(label) }
        return if (prefixMatches.size == 1) prefixMatches.single() else null
    }

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
     * existing content, not a store of new files). The link stores the
     * entry's display name as the song's identity (a tag song is identified
     * by name, resolved live against the content) plus the entry as the
     * provenance hint. Returns false when the tag or the entry no longer
     * exists (deleted while the pick was in flight) or when the tag already
     * links a song with the entry's name (a second pick of the same song -
     * or of a duplicate-named row - must not duplicate the link).
     */
    suspend fun linkEntryToTag(tagId: Long, entryId: Long): Boolean {
        return db.withTransaction {
            val tag = dao.tagById(tagId) ?: return@withTransaction false
            val entry = dao.entryById(entryId) ?: return@withTransaction false
            if (dao.linkCountBySongName(tag.id, entry.label) > 0) return@withTransaction false
            dao.insertLink(
                TagLinkEntity(
                    tagId = tag.id,
                    entryId = entry.id,
                    songName = entry.label,
                    sortOrder = dao.linkCountForTag(tag.id),
                )
            )
            true
        }
    }

    /**
     * Appends a name-only link to the end of [tagId]'s song list - the
     * restore path (a re-scanned card's remembered or carried song list
     * landing on a freshly re-registered tag). [songName] is the song's
     * identity; [entryId] is the entry the name currently resolves to, when
     * it resolves to any (the provenance hint), and null when the name
     * shows as "song not found". A no-op (null) when the tag is gone.
     */
    suspend fun insertLinkByName(tagId: Long, songName: String, entryId: Long?): TagEntity? {
        if (dao.tagById(tagId) == null) return null
        dao.insertLink(
            TagLinkEntity(
                tagId = tagId,
                entryId = entryId,
                songName = songName,
                sortOrder = dao.linkCountForTag(tagId),
            ),
        )
        return dao.tagById(tagId)
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
     * untouched - and a tag's link to a deleted row survives the deletion
     * (tag_links carries no foreign key to entries): the song keeps its place on
     * the tag's list as a "song not found" row. Returns the number of
     * content rows deleted (0 for an empty set - e.g. a folder whose tree
     * can no longer be read, where only the folder row is removed). A row
     * that survives the deletion (a shared song another folder also
     * imports) is UNMARKED in the same transaction, so it degrades into the
     * individual content list.
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

    /**
     * A tag's current song list in link order - name-based semantics (see
     * [observeEntriesForTag]): every link comes back at its original sort
     * position, resolved to the first stored content entry with its song
     * name, or to nothing (the tag's "song not found" row) when no content
     * entry carries the name.
     */
    suspend fun entriesForTag(tagId: Long): List<TagSongRow> {
        val byName = dao.entries().groupBy { it.label }.mapValues { it.value.first() }
        return dao.linksForTag(tagId).map { link ->
            TagSongRow(
                linkId = link.id,
                entryId = link.entryId,
                sortOrder = link.sortOrder,
                name = link.songName,
                entry = byName[link.songName],
            )
        }
    }

    /** The tag's ordered song NAMES (its links' name snapshots, in link
     * order, "song not found" names among them) - the card payload's song
     * list and the per-card memory's song list. */
    suspend fun songNamesForTag(tagId: Long): List<String> =
        dao.linksForTag(tagId).map { it.songName }

    /**
     * Unlinks ONE link from ONE tag - the inverse of [linkEntryToTag]:
     * only that link (addressed by its stored link row's id) goes. For a
     * song that still exists the song stays in the content list (its folder
     * membership untouched), its links to any other tags are untouched, and
     * the remaining songs keep their relative order - their sortOrder values
     * are renumbered densely to 0..n-1 in the current read order, so a later
     * append (sortOrder = the tag's link count) still lands at the end.
     * For a song whose name resolves to no content (the "song not found"
     * row) the dead link is what goes: the removal cleans the missing row
     * out of the list. Returns false when the tag no longer exists (deleted
     * while the removal was in flight) or when it carries no such link.
     */
    suspend fun unlinkLinkFromTag(tagId: Long, linkId: Long): Boolean {
        return db.withTransaction {
            if (dao.tagById(tagId) == null) return@withTransaction false
            val current = dao.linksForTag(tagId)
            if (current.none { it.id == linkId }) return@withTransaction false
            val next = current.filterNot { it.id == linkId }
            dao.replaceLinksForTag(
                tagId,
                next.mapIndexed { index, link ->
                    TagLinkEntity(
                        tagId = tagId,
                        entryId = link.entryId,
                        songName = link.songName,
                        sortOrder = index,
                    )
                },
            )
            true
        }
    }

    /**
     * Reads and replaces the tag's ordered song list in one Room
     * transaction. The [transform] sees the CURRENT list's link ids in
     * link order - the dead (missing) links among them: a transform that
     * echoes them back keeps the missing rows at their positions, and a
     * reorder never drops a link whose song is missing. Returns the list
     * the transform settled on.
     */
    suspend fun updateTagEntries(tagId: Long, transform: (List<Long>) -> List<Long>): List<Long> {
        return db.withTransaction {
            val current = dao.linksForTag(tagId)
            val byLink = current.associateBy { it.id }
            val next = transform(current.map { it.id })
            // Only a permutation of the current link set is a reorder; a
            // list that changed in flight (a link added or removed between
            // the read and the write) leaves the stored links untouched.
            if (next.size != current.size || next.toSet() != byLink.keys || next.distinct().size != next.size) {
                return@withTransaction current.map { it.id }
            }
            dao.replaceLinksForTag(
                tagId,
                next.mapIndexed { index, linkId ->
                    byLink.getValue(linkId).let {
                        TagLinkEntity(
                            tagId = tagId,
                            entryId = it.entryId,
                            songName = it.songName,
                            sortOrder = index,
                        )
                    }
                },
            )
            next
        }
    }

    // ---- per-card memory (survives the tag row's deletion) ----

    /**
     * The last saved name + song list for the card uid [uid] (the card's
     * NFC uid string, the [TagEntity.uid] shape), if one was remembered -
     * the restore source for a re-scanned card whose payload carries no
     * song list.
     */
    suspend fun memoryForUid(uid: String): TagMemory? {
        val row = dao.memoryByUid(uid.trim()) ?: return null
        return TagMemory(name = row.name, songs = TagMemory.songsFromJson(row.songs))
    }

    /**
     * Remembers the last saved name + song list for the card uid [uid] -
     * written on registration and on a successful card write. Deleting a
     * tag row never touches the record (the table carries no foreign key
     * to tags), so re-scanning the same card later can restore it.
     */
    suspend fun rememberTag(uid: String, name: String, songs: List<String>) {
        dao.upsertMemory(
            TagMemoryEntity(
                uid = uid.trim(),
                name = name,
                songs = TagMemory.songsToJson(songs),
            ),
        )
    }

    /**
     * Clears the per-uid memory record (the save section's compare scan of
     * a card that carries no lumi data): the indicator goes [TagSyncState.Unknown] -
     * nothing saved or compared yet.
     */
    suspend fun forgetTag(uid: String) {
        dao.deleteMemoryByUid(uid.trim())
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

    // ---- settings: remembered playback positions ----

    /**
     * How many tags' last played position the LRU remembers (the Advanced
     * options page's "Remembered playback positions"). Unset or invalid
     * stored values - and a count outside the setting's range - fall back
     * to the default ([MAX_REMEMBERED_TAGS]); a stored count is clamped to
     * the setting's range.
     */
    fun observeRememberedPositions(): Flow<Int> =
        dao.observeSettings().map { rows ->
            rememberedPositionsFromStored(
                rows.firstOrNull { it.key == SettingsEntity.REMEMBERED_POSITIONS }?.value,
            )
        }

    suspend fun setRememberedPositions(count: Int) {
        dao.upsertSetting(
            SettingsEntity(SettingsEntity.REMEMBERED_POSITIONS, rememberedPositionsStoredValue(count)),
        )
    }

    // ---- settings: content sort direction ----

    /**
     * The content list's title sort direction. Unset or invalid stored
     * values fall back to ascending - the content list's default order.
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

    // ---- settings: appearance (theme mode) ----

    /**
     * The app's appearance mode (the settings screen's "Appearance" choice).
     * Unset or invalid stored values fall back to follow-system - the
     * default: the app tracks the phone's own light/dark setting.
     */
    fun observeThemeMode(): Flow<LumiThemeMode> =
        dao.observeSettings().map { rows ->
            themeModeFromStored(
                rows.firstOrNull { it.key == SettingsEntity.THEME_MODE }?.value,
            )
        }

    suspend fun setThemeMode(mode: LumiThemeMode) {
        dao.upsertSetting(
            SettingsEntity(SettingsEntity.THEME_MODE, mode.storedValue()),
        )
    }

    // ---- settings: auto-lock after idle ----

    /**
     * How many minutes of idle (no active playback) count before the
     * phone's screen is locked. Unset or invalid stored values fall back to
     * off (0) - the default: the screen is never locked automatically.
     */
    fun observeAutoLockDelay(): Flow<AutoLockDelay> =
        dao.observeSettings().map { rows ->
            autoLockDelayFromStored(
                rows.firstOrNull { it.key == SettingsEntity.AUTO_LOCK_MINUTES }?.value,
            )
        }

    suspend fun setAutoLockDelay(delay: AutoLockDelay) {
        dao.upsertSetting(
            SettingsEntity(SettingsEntity.AUTO_LOCK_MINUTES, delay.storedValue()),
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

    // ---- settings: first-time setup ----

    /**
     * Whether the first-time setup wizard has been finished. Unset (a
     * fresh install) means the wizard is due; the wizard itself records
     * the completion.
     */
    fun observeSetupComplete(): Flow<Boolean> =
        dao.observeSettings().map { rows ->
            rows.any { it.key == SettingsEntity.SETUP_COMPLETE && it.value == "1" }
        }

    suspend fun setSetupComplete(complete: Boolean) {
        dao.upsertSetting(
            SettingsEntity(SettingsEntity.SETUP_COMPLETE, if (complete) "1" else "0"),
        )
    }

    // ---- settings: audio level ----

    /**
     * The current audio level mode. Unset or invalid stored values fall back
     * to OFF - the default: the app never applies a gain.
     */
    fun observeAudioLevelMode(): Flow<AudioLevelMode> =
        dao.observeSettings().map { rows ->
            AudioLevelMode.fromStorage(
                rows.firstOrNull { it.key == SettingsEntity.AUDIO_LEVEL_MODE }?.value,
            )
        }

    /**
     * The target integrated loudness (LUFS) normalization aims for. Unset or
     * invalid stored values fall back to the default target (-16 LUFS).
     */
    fun observeAudioLevelTargetLufs(): Flow<Double> =
        dao.observeSettings().map { rows ->
            audioLevelTargetLufsFromStored(
                rows.firstOrNull { it.key == SettingsEntity.AUDIO_LEVEL_TARGET_LUFS }?.value,
            )
        }

    /** The audio level setting (the mode + the target) in one flow. */
    fun observeAudioLevelSettings(): Flow<AudioLevelSettings> =
        combine(observeAudioLevelMode(), observeAudioLevelTargetLufs()) { mode, targetLufs ->
            AudioLevelSettings(mode, targetLufs)
        }

    suspend fun setAudioLevelMode(mode: AudioLevelMode) {
        dao.upsertSetting(
            SettingsEntity(SettingsEntity.AUDIO_LEVEL_MODE, mode.storageValue),
        )
    }

    suspend fun setAudioLevelTargetLufs(targetLufs: Double) {
        dao.upsertSetting(
            SettingsEntity(SettingsEntity.AUDIO_LEVEL_TARGET_LUFS, targetLufs.toInt().toString()),
        )
    }

    // ---- per-entry loudness (the measurement cache) ----

    /** The measurement for [entryId] (null when never measured). */
    suspend fun loudnessForEntry(entryId: Long): EntryLoudnessEntity? =
        dao.entryLoudness(entryId)

    /**
     * Records (or replaces) the measured loudness of [entryId].
     */
    suspend fun recordLoudness(
        entryId: Long,
        lufs: Double?,
        truePeakDbTp: Double?,
        fingerprint: String,
    ) {
        dao.upsertEntryLoudness(
            EntryLoudnessEntity(entryId, lufs, truePeakDbTp, fingerprint, System.currentTimeMillis()),
        )
    }

    /**
     * The audio level's measurement status: how many of the app's audio
     * entries carry a stored measurement (the settings pane's "N of M songs
     * measured" row). A silent file's measurement (no loudness) still counts
     * as measured: the read settled, there is just nothing to normalize.
     */
    fun observeLoudnessStatus(): Flow<LoudnessStatus> =
        combine(dao.observeEntries(), dao.observeEntryLoudness()) { entries, measurements ->
            val audioIds = entries
                .filter { EntryType.fromStorage(it.type) == EntryType.AUDIO }
                .mapTo(HashSet()) { it.id }
            LoudnessStatus(measurements.count { it.entryId in audioIds }, audioIds.size)
        }

    /**
     * The ids of the content entries the app will actually play
     * volume-normalized, live: the entries carrying a stored loudness
     * READING (a silent file's stored row carries no reading and is not
     * normalized) AND the audio level's mode enabled (OFF - the default -
     * applies no gain, so nothing is normalized while it holds): the song
     * lists' rows badge the entries in this set.
     */
    fun observeNormalizedEntryIds(): Flow<Set<Long>> =
        combine(
            dao.observeNormalizedEntryIds().map { ids -> ids.toSet() },
            observeAudioLevelMode(),
        ) { ids, mode ->
            if (mode == AudioLevelMode.OFF) emptySet() else ids
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
