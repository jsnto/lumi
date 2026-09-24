package com.lumi.player.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context

@Database(
    entities = [
        TagEntity::class,
        EntryEntity::class,
        TagLinkEntity::class,
        TagMemoryEntity::class,
        SettingsEntity::class,
        TagPositionEntity::class,
        FolderEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class LumiDatabase : RoomDatabase() {

    abstract fun dao(): LumiDao

    companion object {
        private const val NAME = "lumi.db"

        /**
         * v3 -> v4: the entries table gains the folder marker column (the
         * folder-imported songs' origin; `NULL` = added individually). Rows
         * stored before the column existed keep `NULL` until the view model's
         * best-effort backfill re-walks the remembered folders and stamps the
         * rows it finds (see [com.lumi.player.ui.LumiViewModel.backfillFolderEntries]);
         * a folder that can no longer be read degrades to its songs appearing
         * in the individual content list.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE entries ADD COLUMN folder_id INTEGER")
            }
        }

        /**
         * v4 -> v5: the tag_links table's entries foreign key (CASCADE)
         * is removed entirely, so a tag's link to an entry SURVIVES the
         * entry's deletion (the link is the tag's "song not found" row,
         * not a silent drop) and deleting an entry is never blocked by
         * its links. (An ON DELETE NO ACTION foreign key would not work:
         * it blocks the parent's deletion while a child still references
         * it.) In the same rebuild the links gain the song's display-name
         * snapshot (a tag song is identified by name, so a row whose entry
         * is gone still renders its name) - backfilled from the entries
         * table for every existing link - and the entry reference becomes
         * a nullable provenance hint. The per-card memory table (the last
         * saved name + song list per card uid, the restore source that
         * survives the tag row's deletion) is created here. SQLite cannot
         * change a foreign key in place, so the table is rebuilt (the
         * classic rename procedure, with foreign key enforcement off for
         * the rename) and EVERY existing link row is carried across - the
         * migration preserves the data, it never wipes it.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("PRAGMA foreign_keys=OFF")
                database.execSQL(
                    "CREATE TABLE tag_links_new (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "tagId INTEGER NOT NULL, " +
                        "entryId INTEGER, " +
                        "songName TEXT NOT NULL, " +
                        "sortOrder INTEGER NOT NULL, " +
                        "FOREIGN KEY(tagId) REFERENCES tags(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_tag_links_new_tagId ON tag_links_new (tagId)",
                )
                database.execSQL(
                    "INSERT INTO tag_links_new (id, tagId, entryId, songName, sortOrder) " +
                        "SELECT l.id, l.tagId, l.entryId, " +
                        "COALESCE((SELECT e.label FROM entries e WHERE e.id = l.entryId LIMIT 1), ''), " +
                        "l.sortOrder FROM tag_links l",
                )
                database.execSQL("DROP TABLE tag_links")
                database.execSQL("ALTER TABLE tag_links_new RENAME TO tag_links")
                database.execSQL("PRAGMA foreign_keys=ON")
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS tag_memory (" +
                        "uid TEXT NOT NULL PRIMARY KEY, " +
                        "name TEXT NOT NULL, " +
                        "songs TEXT NOT NULL)",
                )
            }
        }

        fun create(context: Context): LumiDatabase =
            Room.databaseBuilder(context, LumiDatabase::class.java, NAME)
                // v3 -> v4 (the folder marker column) and v4 -> v5 (the
                // tag_links entries foreign key dropped + the song-name
                // snapshot + the per-card memory table - the link survives)
                // are real migrations; the remembered-positions table (v2)
                // and the folders table (v3) still have nothing worth
                // migrating, so databases older than v3 stay destructive.
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigration()
                .build()
    }
}
