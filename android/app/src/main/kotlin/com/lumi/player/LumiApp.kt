package com.lumi.player

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.lumi.player.data.MAX_REMEMBERED_TAGS
import com.lumi.player.data.AudioLevelSettings
import com.lumi.player.data.LoudnessStore
import com.lumi.player.data.LumiDatabase
import com.lumi.player.data.LumiRepository
import com.lumi.player.data.TagPositionStore
import com.lumi.player.device.ScreenOffReceiver
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

    /**
     * The current remembered-positions count (how many tags' last played
     * position the LRU keeps - the Advanced options page's setting), live
     * in memory: the position store reads it on every save without touching
     * the database (the app-singleton seam, like [audioLevel]). Kept live
     * from the settings table by the app-lifetime collector in [onCreate].
     */
    @Volatile
    var rememberedPositions: Int = MAX_REMEMBERED_TAGS
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * The app's screen-off receiver, also registered at runtime: recent
     * platform builds send the system's SCREEN_OFF broadcast with
     * FLAG_RECEIVER_REGISTERED_ONLY (device-verified on the API 37 Pixel 7
     * Pro - the static manifest receiver is excluded from resolution and
     * only runtime-registered receivers get it), so while the app process
     * is alive the runtime registration is the surface that catches it.
     * The manifest entry stays as well (it is the reliable surface on
     * builds that still deliver to static receivers); both funnel into the
     * same (idempotent) decision logic, so a double delivery pauses once.
     * The foreground PlayerService keeps the process alive while a song
     * plays, so the registration is up whenever there is anything to pause.
     */
    private var screenOffReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        screenOffReceiver = ScreenOffReceiver().also {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(it, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(it, filter)
            }
        }
        loudnessStore.attach(appScope)
        appScope.launch {
            repository.observeAudioLevelSettings().collect { audioLevel = it }
        }
        appScope.launch {
            repository.observeRememberedPositions().collect { rememberedPositions = it }
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
