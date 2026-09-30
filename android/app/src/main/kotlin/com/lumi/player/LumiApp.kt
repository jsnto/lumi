package com.lumi.player

import android.app.Application
import com.lumi.player.data.AudioLevelSettings
import com.lumi.player.data.LoudnessStore
import com.lumi.player.data.LumiDatabase
import com.lumi.player.data.LumiRepository
import com.lumi.player.data.TagPositionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Application entry point. Owns the process-wide database and repository. */
class LumiApp : Application() {

    val database: LumiDatabase by lazy { LumiDatabase.create(this) }

    val repository: LumiRepository by lazy { LumiRepository(database) }

    /** Store of remembered per-tag playback positions (LRU, see [TagPositionStore]). */
    val positionStore: TagPositionStore by lazy { TagPositionStore(database) }

    /** The loudness cache the player service reads (see [LoudnessStore]). */
    val loudnessStore: LoudnessStore by lazy { LoudnessStore(database) }

    /**
     * The current audio level setting, live in memory: the player service
     * reads it on every track load without touching the database (the
     * app-singleton seam, like [positionStore]). Kept live from the settings
     * table by the app-lifetime collector in [onCreate].
     */
    @Volatile
    var audioLevel: AudioLevelSettings = AudioLevelSettings.DEFAULT
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        loudnessStore.attach(appScope)
        appScope.launch {
            repository.observeAudioLevelSettings().collect { audioLevel = it }
        }
    }

    /**
     * Cancels the app-lifetime collectors (the audio level's settings
     * mirror): in production the process is ending here anyway, and in
     * Robolectric the next test must not run with the previous app's Room
     * flows still posting onto the shared main looper (that poisons the
     * next test's Compose idling and its async Room round trips).
     */
    override fun onTerminate() {
        super.onTerminate()
        appScope.cancel()
    }
}
