package com.lumi.player.data

import com.lumi.player.LumiApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v4 -> v5 migration coverage ([LumiDatabase.MIGRATION_4_5]): a
 * pre-existing v4 database - where tag_links STILL carried the entries
 * CASCADE foreign key - is opened with the v5 schema, and the migration
 * must preserve every existing link row verbatim (the tag's song list
 * survives the upgrade), backfill each link's song-name snapshot from the
 * entry's label (a link whose entry is already gone backfills to the empty
 * name, which the UI renders as the generic "Song not found" marker),
 * create the per-card memory table, and the new link semantics must hold
 * on the migrated database: a deleted entry's link stays behind as a
 * "song not found" row (rendering its snapshot name), and a deleted tag
 * still takes its links with it (the tags foreign key stayed CASCADE).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumiApp::class)
class DatabaseMigrationTest {

    private val app: LumiApp get() = RuntimeEnvironment.getApplication() as LumiApp
    private val repo: LumiRepository get() = app.repository

    /** The tag's rows as song names (the snapshot name backfilled at migration). */
    private fun names(rows: List<TagSongRow>): List<String> = rows.map { it.name }

    /**
     * Creates a v4-shaped database at the app's database path: one tag,
     * three entries, the tag's three links (link order 3, 1, 2 - not
     * storage order) - and marks it as schema version 4, the state a
     * real pre-upgrade install has.
     */
    private fun installV4Database() {
        val file = app.getDatabasePath("lumi.db")
        file.parentFile?.mkdirs()
        val raw = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        raw.execSQL(
            "CREATE TABLE tags (" +
                "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                "uid TEXT NOT NULL, " +
                "name TEXT NOT NULL, " +
                "createdAt INTEGER NOT NULL)",
        )
        raw.execSQL("CREATE UNIQUE INDEX index_tags_uid ON tags (uid)")
        raw.execSQL(
            "CREATE TABLE entries (" +
                "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                "type TEXT NOT NULL, " +
                "label TEXT NOT NULL, " +
                "payload TEXT NOT NULL, " +
                "createdAt INTEGER NOT NULL, " +
                "folder_id INTEGER)",
        )
        // The v4 shape: the entries foreign key is STILL CASCADE.
        raw.execSQL(
            "CREATE TABLE tag_links (" +
                "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                "tagId INTEGER NOT NULL, " +
                "entryId INTEGER NOT NULL, " +
                "sortOrder INTEGER NOT NULL, " +
                "FOREIGN KEY(tagId) REFERENCES tags(id) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(entryId) REFERENCES entries(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        raw.execSQL("CREATE INDEX IF NOT EXISTS index_tag_links_tagId ON tag_links (tagId)")
        raw.execSQL("CREATE INDEX IF NOT EXISTS index_tag_links_entryId ON tag_links (entryId)")
        raw.execSQL(
            "CREATE TABLE settings (key TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY (key))",
        )
        raw.execSQL(
            "CREATE TABLE tag_positions (" +
                "tag_uid TEXT NOT NULL, " +
                "position_ms INTEGER NOT NULL, " +
                "entry_index INTEGER NOT NULL, " +
                "queue_fingerprint TEXT NOT NULL, " +
                "last_played_at INTEGER NOT NULL, " +
                "PRIMARY KEY (tag_uid))",
        )
        raw.execSQL(
            "CREATE TABLE folders (" +
                "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                "uri TEXT NOT NULL, " +
                "label TEXT NOT NULL, " +
                "createdAt INTEGER NOT NULL)",
        )
        raw.execSQL("CREATE UNIQUE INDEX index_folders_uri ON folders (uri)")
        raw.execSQL(
            "INSERT INTO tags (uid, name, createdAt) VALUES ('AABBCC', 'Porch', 1000)",
        )
        raw.execSQL(
            "INSERT INTO entries (type, label, payload, createdAt) " +
                "VALUES ('audio', 'Alpha', 'content://a', 1000), " +
                "       ('audio', 'Beta', 'content://b', 1000), " +
                "       ('audio', 'Gamma', 'content://c', 1000)",
        )
        // Three links, in a link order that is not the entries' storage
        // order (the upgrade must not re-sort them either).
        raw.execSQL(
            "INSERT INTO tag_links (tagId, entryId, sortOrder) " +
                "VALUES (1, 3, 0), (1, 1, 1), (1, 2, 2)",
        )
        raw.execSQL("PRAGMA user_version=4")
        raw.close()
    }

    @Test
    fun migratingAv4DatabasePreservesEveryLinkRow() = runBlocking {
        installV4Database()

        // Opening the app's database runs the v4 -> v5 migration.
        val db = app.database
        val tag = repo.tagByUid("AABBCC")
        assertEquals("the tag row survived the migration", 1, tag?.id?.toInt())

        // EVERY existing link row survived the upgrade verbatim: the link
        // ids, the target entries, and the (deliberately non-storage)
        // sort order.
        val rows = repo.entriesForTag(tag!!.id)
        assertEquals("all three link rows survive", 3, rows.size)
        assertEquals(listOf(3L, 1L, 2L), rows.map { it.entryId })
        assertEquals(listOf(0, 1, 2), rows.map { it.sortOrder })
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.linkId })
        // The song-name snapshot was backfilled from each entry's label
        // (the v5 column the v4 rows did not have)...
        assertEquals(listOf("Gamma", "Alpha", "Beta"), names(rows))
        // ...and the rows resolve to their entries through the live name
        // lookup the same way.
        assertEquals(listOf("Gamma", "Alpha", "Beta"), rows.map { it.entry?.label })
        // The surrounding data survived too.
        assertEquals(3, repo.observeEntries().first().size)
        assertEquals(1, repo.observeTags().first().size)

        // The per-card memory table was created (empty on a fresh upgrade -
        // the memory a card never wrote or registered does not exist).
        assertEquals(
            "the memory table is empty on a migrated database",
            null,
            repo.memoryForUid("AABBCC"),
        )

        // The new semantics hold on the migrated database: deleting an
        // entry leaves its link behind, at its original sort position,
        // rendering its snapshot name (the v4 CASCADE would have dropped
        // it).
        val beta = repo.observeEntries().first().single { it.label == "Beta" }
        repo.deleteEntry(beta)
        val after = repo.entriesForTag(tag.id)
        assertEquals("the dead link keeps its row", 3, after.size)
        assertEquals(listOf("Gamma", "Alpha", "Beta"), names(after))
        assertEquals(beta.id, after[2].entryId)
        assertNull("the missing row carries no entry", after[2].entry)

        // And deleting the tag still takes its links with it (the tags
        // foreign key stayed CASCADE through the rebuild).
        repo.deleteTag(tag)
        assertEquals(emptyList<TagSongRow>(), repo.entriesForTag(tag.id))
        db.close()
    }
}
