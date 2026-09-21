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
        SettingsEntity::class,
        TagPositionEntity::class,
        FolderEntity::class,
    ],
    version = 4,
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

        fun create(context: Context): LumiDatabase =
            Room.databaseBuilder(context, LumiDatabase::class.java, NAME)
                // v3 -> v4 is a real migration (the folder marker column); the
                // remembered-positions table (v2) and the folders table (v3)
                // still have nothing worth migrating, so older databases stay
                // destructive.
                .addMigrations(MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build()
    }
}
