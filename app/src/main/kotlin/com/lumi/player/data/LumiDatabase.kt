package com.lumi.player.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context

@Database(
    entities = [TagEntity::class, EntryEntity::class, TagLinkEntity::class, SettingsEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class LumiDatabase : RoomDatabase() {

    abstract fun dao(): LumiDao

    companion object {
        private const val NAME = "lumi.db"

        fun create(context: Context): LumiDatabase =
            Room.databaseBuilder(context, LumiDatabase::class.java, NAME)
                .build()
    }
}
