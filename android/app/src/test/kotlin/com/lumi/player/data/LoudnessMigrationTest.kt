package com.lumi.player.data

import com.lumi.player.LumiApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v5 -> v6 migration coverage ([LumiDatabase.MIGRATION_5_6]): a
 * pre-existing v5 database is opened with the v6 schema, and the migration
 * must create the per-entry loudness table (empty on a fresh upgrade - no
 * measurement has happened yet) while preserving every other table and row,
 * and the new table's semantics must hold: a recorded measurement round
 * trips, and an entry's deletion takes its measurement with it (the CASCADE
 * foreign key), so the player never applies a stale gain to a removed song.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumiApp::class)
class LoudnessMigrationTest {

    private val app: LumiApp get() = RuntimeEnvironment.getApplication() as LumiApp
    private val repo: LumiRepository get() = app.repository

    /**
     * Creates a v5-shaped database at the app's database path: one tag,
     * three entries, the tag's two links (with the v5 song-name snapshot
     * column and the entries-less foreign key shape), a per-card memory row -
     * and marks it as schema version 5, the state a real pre-upgrade install
     * has.
     */
    private fun installV5Database() {
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
        // The v5 shape: the entries foreign key is GONE (a deleted entry's
        // link survives as the "song not found" row), the nullable entryId
        // is a provenance hint, and the song-name snapshot exists.
        raw.execSQL(
            "CREATE TABLE tag_links (" +
                "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                "tagId INTEGER NOT NULL, " +
                "entryId INTEGER, " +
                "songName TEXT NOT NULL, " +
                "sortOrder INTEGER NOT NULL, " +
                "FOREIGN KEY(tagId) REFERENCES tags(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        raw.execSQL("CREATE INDEX IF NOT EXISTS index_tag_links_tagId ON tag_links (tagId)")
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
            "CREATE TABLE tag_memory (" +
                "uid TEXT NOT NULL PRIMARY KEY, " +
                "name TEXT NOT NULL, " +
                "songs TEXT NOT NULL)",
        )
        raw.execSQL(
            "INSERT INTO tags (uid, name, createdAt) VALUES ('AABBCC', 'Porch', 1000)",
        )
        raw.execSQL(
            "INSERT INTO entries (type, label, payload, createdAt) " +
                "VALUES ('audio', 'Alpha', 'content://a', 1000), " +
                "       ('audio', 'Beta', 'content://b', 1000), " +
                "       ('url', 'Site', 'https://site', 1000)",
        )
        raw.execSQL(
            "INSERT INTO tag_links (tagId, entryId, songName, sortOrder) " +
                "VALUES (1, 1, 'Alpha', 0), (1, 2, 'Beta', 1)",
        )
        raw.execSQL(
            "INSERT INTO tag_memory (uid, name, songs) VALUES ('AABBCC', 'Porch', '[]')",
        )
        raw.execSQL("PRAGMA user_version=5")
        raw.close()
    }

    @Test
    fun migratingAv5DatabaseCreatesTheLoudnessTableAndKeepsEverything() = runBlocking {
        installV5Database()

        // Opening the app's database runs the v5 -> v6 migration.
        val db = app.database

        // The loudness table exists (an empty query answers, not a
        // no-such-table error) and is empty on a fresh upgrade - no
        // measurement has happened yet.
        val entries = repo.observeEntries().first()
        assertEquals("the entries survived the migration", 3, entries.size)
        assertNull(
            "no loudness row exists before any measurement",
            repo.loudnessForEntry(entries.first().id),
        )
        assertEquals(
            "the status counts zero of two audio songs (the url entry is not audio)",
            LoudnessStatus(0, 2),
            repo.observeLoudnessStatus().first(),
        )

        // The surrounding data survived too.
        val tag = repo.tagByUid("AABBCC")
        assertEquals(1, tag?.id?.toInt())
        assertEquals(2, repo.entriesForTag(tag!!.id).size)
        assertNotNull("the per-card memory survived the migration", repo.memoryForUid("AABBCC"))

        // A recorded measurement round trips (lufs and peak, the
        // fingerprint, the timestamp), and an overwrite replaces it.
        val alpha = entries.first { it.label == "Alpha" }
        repo.recordLoudness(alpha.id, -16.2, -1.5, "fp-1")
        var row = repo.loudnessForEntry(alpha.id)
        assertEquals(-16.2, row?.lufs!!, 0.0001)
        assertEquals(-1.5, row.truePeakDbTp!!, 0.0001)
        assertEquals("fp-1", row.fingerprint)
        assertTrue("a fresh measurement is timestamped", row.measuredAt > 0)
        repo.recordLoudness(alpha.id, -14.0, -0.5, "fp-2")
        row = repo.loudnessForEntry(alpha.id)
        assertEquals("the overwrite replaces the row", "fp-2", row?.fingerprint)
        assertEquals(-14.0, row?.lufs!!, 0.0001)
        assertEquals(
            "one of two audio songs is measured now",
            LoudnessStatus(1, 2),
            repo.observeLoudnessStatus().first(),
        )

        // A silent file's measurement (no loudness) still counts as
        // measured: the read settled, there is just nothing to normalize.
        val beta = entries.first { it.label == "Beta" }
        repo.recordLoudness(beta.id, null, null, "fp-silent")
        assertEquals(null, repo.loudnessForEntry(beta.id)?.lufs)
        assertEquals(
            LoudnessStatus(2, 2),
            repo.observeLoudnessStatus().first(),
        )

        // The CASCADE foreign key: an entry's deletion takes its
        // measurement with it (a stale gain must never survive the song).
        repo.deleteEntry(alpha)
        assertNull("the entry's loudness row is gone with the entry", repo.loudnessForEntry(alpha.id))
        assertEquals(
            "only the surviving entry's measurement counts",
            LoudnessStatus(1, 1),
            repo.observeLoudnessStatus().first(),
        )

        // And the v5 link semantics are intact after the upgrade (a
        // deleted entry's link still survives - the migration only ADDED a
        // table, it never touched tag_links).
        assertEquals(2, repo.entriesForTag(tag.id).size)
        db.close()
    }
}
