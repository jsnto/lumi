package com.lumi.player.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.C
import com.lumi.player.LumiApp
import com.lumi.player.R
import com.lumi.player.core.EntrySpec
import com.lumi.player.core.LoudnessProcessor
import com.lumi.player.data.EntryType
import com.lumi.player.data.TagPositionEntity
import com.lumi.player.data.gainFor
import com.lumi.player.data.fingerprintOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Foreground service that plays a tag's content list with ExoPlayer and keeps
 * a notification with pause / next / stop controls.
 *
 * v1 plays AUDIO entries; non-audio entries that exist in the database
 * (video / url) are skipped over.
 */
class PlayerService : Service(), Player.Listener {

    /** Saves the active tag's position while actively playing. */
    private class PositionSaver(private val main: Handler, private val service: PlayerService) : Runnable {
        override fun run() {
            service.saveCurrentPosition()
            if (service.shouldKeepSavingPositions()) {
                main.postDelayed(this, POSITION_SAVE_INTERVAL_MS)
            }
        }
    }

    private var player: ExoPlayer? = null
    private var queue: List<EntrySpec> = emptyList()
    private var position: Int = 0
    /**
     * The audio level's playback processor, installed in the ExoPlayer's
     * sink chain by [renderersFactory] (the Media3 AudioProcessor hook): the
     * per-track gain from the on-device loudness measurement, plus the
     * soft-clip limiter on boosted tracks.
     */
    private val loudnessProcessor = LoudnessProcessor()
    /**
     * The renderers factory that installs [loudnessProcessor] in the sink.
     * Lazy: the service is instantiated reflectively WITHOUT its context
     * attached (the constructor must not touch [applicationContext] - it
     * is only needed when the player is built).
     */
    private val renderersFactory by lazy {
        LumiRenderersFactory(applicationContext, arrayOf(loudnessProcessor))
    }
    /** Consecutive load/playback errors; guards against looping on a bad file. */
    private var consecutiveErrors: Int = 0
    /**
     * The service's intent to play: true after a start/resume, false after a
     * pause/stop. [loadCurrent] honours it so an error-driven advance while
     * the session is paused does not override the pause (a bad-file loop
     * re-armed playback every cycle on-device and made the pause look like a
     * no-op).
     */
    private var wantsPlay: Boolean = false
    /** Remembered position to seek to once the first media item is ready. */
    private var pendingSeekMs: Long = 0
    private var scope: CoroutineScope? = null
    private val main = Handler(Looper.getMainLooper())
    /** Saves the active tag's position while actively playing. */
    private val positionSaver = PositionSaver(main, this)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                @Suppress("DEPRECATION") // getParcelableArrayListExtra(Class) needs API 33; min is 26.
                val entries = intent.getParcelableArrayListExtra<EntrySpec>(EXTRA_ENTRIES)
                    ?: emptyList()
                val uid = intent.getStringExtra(EXTRA_TAG_UID)
                if (entries.isEmpty()) {
                    stopPlayback()
                } else {
                    Log.i(TAG, "ACTION_START: ${entries.size} entries for uid=$uid")
                    val startIndex = intent.getIntExtra(EXTRA_START_ENTRY_INDEX, 0).coerceAtLeast(0)
                    val startSeekMs = intent.getLongExtra(EXTRA_START_POSITION_MS, 0L).coerceAtLeast(0)
                    startQueue(entries, uid, startIndex, startSeekMs)
                }
            }

            ACTION_TOGGLE -> togglePlayPause()
            ACTION_RESUME -> resumePlayback()
            ACTION_PAUSE -> pausePlayback()
            ACTION_NEXT -> {
                if (queue.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                advance()
            }

            ACTION_STOP -> stopPlayback()
            else -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(positionSaver)
        scope?.cancel()
        player?.release()
        player = null
        super.onDestroy()
    }

    // ---- queue handling ----

    private fun startQueue(
        entries: List<EntrySpec>,
        uid: String?,
        startIndex: Int,
        startSeekMs: Long,
    ) {
        // No playback begins on a locked device: refuse a new-queue start
        // while the screen is off and leave any paused session exactly
        // as-is (the screen-off's pause already owns it). A start
        // dispatched in the milliseconds before isInteractive flips is
        // caught by the receiver's delayed recheck pause (a bounded
        // sliver, accepted by design). A fresh shell has no session to
        // keep, so it self-stops like every other empty-queue command.
        if (!screenIsInteractive()) {
            Log.i(TAG, "ACTION_START refused: the screen is off")
            if (queue.isEmpty()) stopPlayback()
            return
        }
        queue = entries
        position = if (entries.isEmpty()) 0 else startIndex.coerceIn(0, entries.lastIndex)
        pendingSeekMs = startSeekMs
        consecutiveErrors = 0
        wantsPlay = true
        startForegroundWithNotification()
        PlayerStateHolder.update { it.copy(activeTagUid = uid) }
        ensurePlayer()
        loadCurrent()
    }

    private fun ensurePlayer(): ExoPlayer {
        return player ?: ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .build().also {
                it.addListener(this)
                player = it
            }
    }

    /** The PowerManager's reading of whether the screen is on. */
    private fun screenIsInteractive(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    /**
     * Applies the audio level's per-track level to the processor before the
     * track's audio flows: the gain from the app's audio level setting and
     * the entry's stored loudness (the [LumiApp]'s loudness snapshot - no
     * database read on the playback path). OFF mode, an unmeasured entry, or
     * a silent one all leave the processor at the exact pass-through.
     */
    private fun applyLoudness(spec: EntrySpec) {
        val app = applicationContext as? LumiApp ?: return
        val settings = app.audioLevel
        val sample = app.loudnessStore.snapshot()[spec.payload]
        val gainDb = gainFor(settings.mode, settings.targetLufs, sample)
        loudnessProcessor.setLevel(gainDb, engageLimiter = gainDb > 0.0)
        Log.i(
            TAG,
            "loudness: '${spec.label}' gain=${"%.2f".format(gainDb)} dB " +
                "(mode=${settings.mode}, target=${settings.targetLufs} LUFS, " +
                "measured=${sample?.lufs}, peak=${sample?.truePeakDbTp})",
        )
    }

    private fun loadCurrent() {
        val p = ensurePlayer()
        val spec = queue.getOrNull(position) ?: return stopPlayback()
        PlayerStateHolder.update {
            it.copy(
                currentLabel = spec.label,
                currentType = spec.type,
            )
        }
        when (EntryType.fromStorage(spec.type)) {
            EntryType.AUDIO -> {
                applyLoudness(spec)
                p.setMediaItem(MediaItem.fromUri(spec.payload))
                p.playWhenReady = wantsPlay
                p.prepare()
                armPositionSaver()
            }

            // Video / url entries are not playable in v1: skip them.
            else -> {
                Log.i(TAG, "skipping non-audio entry: ${spec.label}")
                if (queue.any { EntryType.fromStorage(it.type) == EntryType.AUDIO }) {
                    advance()
                } else {
                    stopPlayback()
                }
            }
        }
    }

    /** Move to the next entry, looping the list. */
    private fun advance() {
        if (queue.isEmpty()) return stopPlayback()
        position = (position + 1) % queue.size
        loadCurrent()
    }

    private fun togglePlayPause() {
        if (queue.isEmpty()) {
            stopSelf()
            return
        }
        val p = player ?: return
        when (PlayerStateHolder.state.value.status) {
            PlaybackStatus.IDLE -> Unit
            else -> {
                val play = !p.playWhenReady
                p.playWhenReady = play
                wantsPlay = play
                if (!play) saveCurrentPosition()
            }
        }
    }

    /** Idempotent: safe to run on every re-report of a tag still in range. */
    private fun resumePlayback() {
        if (queue.isEmpty()) return stopPlayback()
        Log.i(TAG, "ACTION_RESUME: resuming (playWhenReady=${player?.playWhenReady})")
        wantsPlay = true
        player?.playWhenReady = true
    }

    /**
     * Pauses playback without ending the session (proximity mode: the tag
     * left range). Idempotent: a no-op when already paused or idle, so the
     * presence poll can issue it on every confirmed tag loss.
     */
    private fun pausePlayback() {
        if (queue.isEmpty()) return stopPlayback()
        val p = player ?: return
        wantsPlay = false
        if (p.playWhenReady) {
            Log.i(TAG, "ACTION_PAUSE: pausing playback")
            p.playWhenReady = false
            saveCurrentPosition()
        } else {
            Log.i(TAG, "ACTION_PAUSE: already paused/idle, no-op")
        }
    }

    private fun stopPlayback() {
        Log.i(TAG, "stopPlayback: stopping the session")
        wantsPlay = false
        main.removeCallbacks(positionSaver)
        pendingSeekMs = 0
        saveCurrentPosition()
        player?.pause()
        player?.clearMediaItems()
        PlayerStateHolder.update {
            it.copy(
                status = PlaybackStatus.IDLE,
                currentLabel = null,
                currentType = null,
                activeTagUid = null,
            )
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- position remembering ----

    private fun shouldKeepSavingPositions(): Boolean {
        val p = player
        return queue.isNotEmpty() && p != null && p.isPlaying
    }

    private fun armPositionSaver() {
        main.removeCallbacks(positionSaver)
        main.post(positionSaver)
    }

    /**
     * Persists the current position of the active tag. A no-op unless an
     * audio entry is loaded and ready; the write happens off the way of
     * playback on the service's scope.
     */
    private fun saveCurrentPosition() {
        val uid = PlayerStateHolder.state.value.activeTagUid ?: return
        val p = player ?: return
        if (p.playbackState != Player.STATE_READY) return
        val spec = queue.getOrNull(position) ?: return
        if (EntryType.fromStorage(spec.type) != EntryType.AUDIO) return
        val app = applicationContext as? LumiApp ?: return
        val store = app.positionStore
        scope?.launch {
            store.remember(
                TagPositionEntity(
                    tagUid = uid,
                    positionMs = p.currentPosition.coerceAtLeast(0),
                    entryIndex = position,
                    queueFingerprint = fingerprintOf(queue),
                    lastPlayedAt = System.currentTimeMillis(),
                ),
                // The user's configured remembered-positions count (the
                // Advanced options page's setting, kept live on the app
                // singleton like the audio level - no database read on the
                // save path). A lower count evicts at the next store; a
                // higher one keeps all the rows already remembered.
                app.rememberedPositions,
            )
        }
    }

    // ---- Player.Listener ----

    override fun onPlaybackStateChanged(state: Int) {
        val p = player ?: return
        when (state) {
            Player.STATE_ENDED -> advance()
            else -> {
                applyPendingSeek(p)
                // A settings change that landed between [loadCurrent] and
                // ready is picked up here: the per-track level is re-resolved
                // once the item is ready.
                if (state == Player.STATE_READY) {
                    queue.getOrNull(position)?.let { applyLoudness(it) }
                }
                val status = when {
                    p.isPlaying -> PlaybackStatus.PLAYING
                    p.playbackState == Player.STATE_READY && !p.playWhenReady ->
                        PlaybackStatus.PAUSED
                    p.mediaItemCount == 0 -> PlaybackStatus.IDLE
                    else -> PlaybackStatus.PLAYING
                }
                PlayerStateHolder.update { it.copy(status = status) }
                // Reset the error guard only when audio is actually being
                // produced (STATE_READY + playing). A failed prepare stays
                // BUFFERING without ever becoming isPlaying, so a queue of
                // unplayable files cannot reset the guard on every error
                // cycle (that made the loop run forever on-device: the
                // per-cycle BUFFERING state kept clearing consecutiveErrors
                // before onPlayerError could count it).
                if (p.isPlaying) {
                    consecutiveErrors = 0
                    armPositionSaver()
                }
                refreshNotification()
            }
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        val p = player ?: return
        if (p.playbackState == Player.STATE_READY) {
            PlayerStateHolder.update {
                it.copy(status = if (playWhenReady) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED)
            }
            if (playWhenReady) {
                consecutiveErrors = 0
                armPositionSaver()
            }
            refreshNotification()
        }
    }

    /** Applies the remembered position once the first item is ready. */
    private fun applyPendingSeek(p: ExoPlayer) {
        val seekMs = pendingSeekMs
        if (seekMs <= 0) return
        pendingSeekMs = 0
        if (p.mediaItemCount == 0) return
        // A remembered position only applies when the entry is long enough
        // for it; otherwise start at the beginning.
        val target = when (val duration = p.duration) {
            C.TIME_UNSET -> seekMs
            else -> if (seekMs < duration) seekMs else 0L
        }
        p.seekTo(target)
    }

    override fun onPlayerError(error: PlaybackException) {
        // Skip unreadable/corrupt files so the list keeps going, but stop if
        // every item keeps failing (e.g. a queue of corrupt files).
        Log.w(TAG, "playback error: ${error.errorCodeName}; advancing")
        consecutiveErrors++
        if (consecutiveErrors >= queue.size) {
            stopPlayback()
        } else {
            advance()
        }
    }

    // ---- notification ----

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        val type = if (android.os.Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun refreshNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val s = PlayerStateHolder.state.value
        val isPlaying = s.isPlaying
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_lumi)
            .setContentTitle("lumi is playing")
            .setContentText(s.currentLabel ?: "Tap a tag to start")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(notificationActionIcon, if (isPlaying) "Pause" else "Play", actionIntent(ACTION_TOGGLE))
            .addAction(notificationActionIcon, "Next", actionIntent(ACTION_NEXT))
            .addAction(notificationActionIcon, "Stop", actionIntent(ACTION_STOP))
            .build()
    }

    private val notificationActionIcon: Int = R.drawable.ic_stat_lumi

    private fun actionIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, PlayerService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        private const val TAG = "PlayerService"
        private const val CHANNEL_ID = "lumi_playback"
        private const val NOTIFICATION_ID = 1
        private const val POSITION_SAVE_INTERVAL_MS = 5_000L

        const val ACTION_START = "com.lumi.player.action.START"
        const val ACTION_TOGGLE = "com.lumi.player.action.TOGGLE"
        const val ACTION_RESUME = "com.lumi.player.action.RESUME"
        const val ACTION_PAUSE = "com.lumi.player.action.PAUSE"
        const val ACTION_NEXT = "com.lumi.player.action.NEXT"
        const val ACTION_STOP = "com.lumi.player.action.STOP"
        const val EXTRA_ENTRIES = "com.lumi.player.extra.ENTRIES"
        const val EXTRA_TAG_UID = "com.lumi.player.extra.TAG_UID"

        /** Entry index to start at when restoring a remembered position. */
        const val EXTRA_START_ENTRY_INDEX = "com.lumi.player.extra.START_ENTRY_INDEX"
        /** Offset in ms to seek to within that entry. */
        const val EXTRA_START_POSITION_MS = "com.lumi.player.extra.START_POSITION_MS"
    }
}
