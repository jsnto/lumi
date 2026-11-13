package com.lumi.player.player

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.media3.exoplayer.ExoPlayer
import com.lumi.player.LumiApp
import com.lumi.player.core.EntrySpec
import com.lumi.player.data.EntryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The service's pending pause: a pause that reaches the service with no
 * session to pause (the empty-queue stop - the screen-off's or a tag
 * loss's) must not let a start dispatched in flight begin playback on the
 * locked device. The flag is process-wide (the empty-queue stop ends the
 * instance that armed it before the arriving start is processed),
 * suppresses a start only while the screen is still off (a suppressed start
 * leaves it armed), and is cleared by the first start that finds the screen
 * back on.
 *
 * Drives the real service through its public intent interface
 * ([Service.onStartCommand]): the empty-queue pause command arms the flag
 * the way the screen-off's pause would, the PowerManager reading is faked
 * through its shadow, and the ExoPlayer instance is only read back to
 * observe the playback state the service sets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumiApp::class)
class PlayerServicePendingPauseTest {

    private var startId = 0

    @Before
    fun resetState() {
        PlayerStateHolder.update { PlayerUiState() }
        PlayerService.pendingScreenOffPause = false
    }

    private fun sendPause(
        controller: org.robolectric.android.controller.ServiceController<PlayerService>,
    ) {
        controller
            .withIntent(Intent().setAction(PlayerService.ACTION_PAUSE))
            .startCommand(0, ++startId)
    }

    private fun sendStart(
        controller: org.robolectric.android.controller.ServiceController<PlayerService>,
    ) {
        val entries = ArrayList(
            listOf(EntrySpec(EntryType.AUDIO.storageValue, "Song A", "content://file/A")),
        )
        controller
            .withIntent(
                Intent()
                    .setAction(PlayerService.ACTION_START)
                    .putParcelableArrayListExtra(PlayerService.EXTRA_ENTRIES, entries)
                    .putExtra(PlayerService.EXTRA_TAG_UID, "TAG-A"),
            )
            .startCommand(0, ++startId)
    }

    private fun playerOf(service: PlayerService): ExoPlayer? {
        val field = PlayerService::class.java.getDeclaredField("player")
        field.isAccessible = true
        return field.get(service) as? ExoPlayer
    }

    private fun setScreen(service: PlayerService, interactive: Boolean) {
        shadowOf(
            service.getSystemService(Context.POWER_SERVICE) as PowerManager,
        ).setIsInteractive(interactive)
    }

    @Test
    fun aStartWhilePendingAndTheScreenIsOffIsSuppressed() {
        val controller = Robolectric.buildService(PlayerService::class.java)
        val service = controller.create().get()
        setScreen(service, interactive = false)

        // The screen-off pause reaches the service while the tag's start is
        // still in flight (no session yet): the empty-queue pause arms the
        // pending pause and stops itself...
        sendPause(controller)
        assertTrue(
            "the empty-queue pause arms the pending pause",
            PlayerService.pendingScreenOffPause,
        )

        // ...and the then-landing start must not begin playback on the
        // locked device.
        sendStart(controller)

        assertNull(
            "a suppressed start must not build a player",
            playerOf(service),
        )
        val state = PlayerStateHolder.state.value
        assertEquals(
            "a suppressed start leaves no session",
            PlaybackStatus.IDLE,
            state.status,
        )
        assertNull("a suppressed start leaves no current entry", state.currentLabel)
        assertTrue(
            "a suppressed start leaves the pending pause armed",
            PlayerService.pendingScreenOffPause,
        )
        controller.destroy()
    }

    @Test
    fun aStartAfterTheScreenComesBackProceedsAndClearsThePendingPause() {
        val controller = Robolectric.buildService(PlayerService::class.java)
        val service = controller.create().get()
        setScreen(service, interactive = false)

        // The same screen-off pause with no session arms the flag...
        sendPause(controller)

        // ...but the screen is back on before the start lands: normal start
        // behavior resumes and the flag is cleared.
        setScreen(service, interactive = true)
        sendStart(controller)

        val player = playerOf(service)
        assertTrue(
            "a start after the screen is back must play",
            player != null && player.playWhenReady,
        )
        assertFalse(
            "the start after the screen is back clears the pending pause",
            PlayerService.pendingScreenOffPause,
        )
        controller.destroy()
    }
}
