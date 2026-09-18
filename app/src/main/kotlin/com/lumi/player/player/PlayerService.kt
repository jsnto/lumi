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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.lumi.player.R
import com.lumi.player.data.EntryType
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Foreground service that plays a tag's content list with ExoPlayer and keeps
 * a notification with pause / next / stop controls.
 *
 * v1 plays AUDIO entries; VIDEO and URL entries are represented (the front
 * screen shows them) but their action is a "coming soon" placeholder, so the
 * service shows a COMING_SOON state and auto-advances after a short pause.
 */
class PlayerService : Service(), Player.Listener {

    private var player: ExoPlayer? = null
    private var queue: List<EntrySpec> = emptyList()
    private var position: Int = 0
    /** Consecutive load/playback errors; guards against looping on a bad file. */
    private var consecutiveErrors: Int = 0
    private val main = Handler(Looper.getMainLooper())
    private val comingSoonTicker = Runnable { advance() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                @Suppress("DEPRECATION") // getParcelableArrayListExtra(Class) needs API 33; min is 26.
                val entries = intent.getParcelableArrayListExtra<EntrySpec>(EXTRA_ENTRIES)
                    ?: emptyList()
                if (entries.isEmpty()) {
                    stopPlayback()
                } else {
                    startQueue(entries)
                }
            }

            ACTION_TOGGLE -> togglePlayPause()
            ACTION_NEXT -> {
                if (queue.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                main.removeCallbacks(comingSoonTicker)
                advance()
            }

            ACTION_STOP -> stopPlayback()
            else -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(comingSoonTicker)
        player?.release()
        player = null
        super.onDestroy()
    }

    // ---- queue handling ----

    private fun startQueue(entries: List<EntrySpec>) {
        queue = entries
        position = 0
        consecutiveErrors = 0
        startForegroundWithNotification()
        ensurePlayer()
        loadCurrent()
    }

    private fun ensurePlayer(): ExoPlayer {
        return player ?: ExoPlayer.Builder(this).build().also {
            it.addListener(this)
            player = it
        }
    }

    private fun loadCurrent() {
        val p = ensurePlayer()
        val spec = queue.getOrNull(position) ?: return stopPlayback()
        PlayerStateHolder.update {
            it.copy(
                currentLabel = spec.label,
                currentType = spec.type,
                queueSize = queue.size,
            )
        }
        when (EntryType.fromStorage(spec.type)) {
            EntryType.AUDIO -> {
                main.removeCallbacks(comingSoonTicker)
                consecutiveErrors = 0
                p.setMediaItem(MediaItem.fromUri(spec.payload))
                p.playWhenReady = true
                p.prepare()
            }

            // VIDEO and URL: representable in v1, action deferred.
            else -> {
                p.stop()
                p.clearMediaItems()
                PlayerStateHolder.update { it.copy(status = PlaybackStatus.COMING_SOON) }
                main.removeCallbacks(comingSoonTicker)
                main.postDelayed(comingSoonTicker, COMING_SOON_DELAY_MS)
            }
        }
    }

    /** Move to the next entry, looping the list (Toonies-style continuous play). */
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
            PlaybackStatus.COMING_SOON -> {
                main.removeCallbacks(comingSoonTicker)
                advance()
            }

            PlaybackStatus.IDLE -> Unit
            else -> p.playWhenReady = !p.playWhenReady
        }
    }

    private fun stopPlayback() {
        main.removeCallbacks(comingSoonTicker)
        player?.pause()
        player?.clearMediaItems()
        PlayerStateHolder.update {
            it.copy(status = PlaybackStatus.IDLE, currentLabel = null, currentType = null)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- Player.Listener ----

    override fun onPlaybackStateChanged(state: Int) {
        val p = player ?: return
        when (state) {
            Player.STATE_ENDED -> advance()
            else -> {
                val status = when {
                    p.isPlaying -> PlaybackStatus.PLAYING
                    p.playbackState == Player.STATE_READY && !p.playWhenReady ->
                        PlaybackStatus.PAUSED
                    p.mediaItemCount == 0 -> PlaybackStatus.IDLE
                    else -> PlaybackStatus.PLAYING
                }
                PlayerStateHolder.update { it.copy(status = status) }
                refreshNotification()
            }
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        val p = player ?: return
        if (!playWhenReady && p.playbackState == Player.STATE_READY) {
            PlayerStateHolder.update { it.copy(status = PlaybackStatus.PAUSED) }
            refreshNotification()
        }
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
            .setContentTitle(if (s.status == PlaybackStatus.COMING_SOON) "lumi" else "lumi is playing")
            .setContentText(s.currentLabel ?: "Tap a tag to start")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(actionIconPause(isPlaying), if (isPlaying) "Pause" else "Play", actionIntent(ACTION_TOGGLE))
            .addAction(actionIconNext(), "Next", actionIntent(ACTION_NEXT))
            .addAction(actionIconStop(), "Stop", actionIntent(ACTION_STOP))
            .build()
    }

    private fun actionIconPause(playing: Boolean): Int = R.drawable.ic_stat_lumi
    private fun actionIconNext(): Int = R.drawable.ic_stat_lumi
    private fun actionIconStop(): Int = R.drawable.ic_stat_lumi

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
        private const val COMING_SOON_DELAY_MS = 3_000L

        const val ACTION_START = "com.lumi.player.action.START"
        const val ACTION_TOGGLE = "com.lumi.player.action.TOGGLE"
        const val ACTION_NEXT = "com.lumi.player.action.NEXT"
        const val ACTION_STOP = "com.lumi.player.action.STOP"
        const val EXTRA_ENTRIES = "com.lumi.player.extra.ENTRIES"
    }
}
