package com.lumi.player

import android.app.Application
import com.lumi.player.data.LumiDatabase
import com.lumi.player.data.LumiRepository

/** Application entry point. Owns the process-wide database and repository. */
class LumiApp : Application() {

    val database: LumiDatabase by lazy { LumiDatabase.create(this) }

    val repository: LumiRepository by lazy { LumiRepository(database) }
}
