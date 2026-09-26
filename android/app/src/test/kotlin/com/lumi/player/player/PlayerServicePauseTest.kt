package com.lumi.player.player

import android.content.Intent
import androidx.media3.exoplayer.ExoPlayer
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioral tests for the proximity-mode pause in [PlayerService]: the
 * dedicated [PlayerService.ACTION_PAUSE] command must pause playback without
 * ending the session (the queue and active tag are kept so a re-read of the
 * tag can RESUME), must be idempotent (the presence poll issues it on every
 * confirmed tag loss and may fire while already paused), and a pause against
 * an empty queue must stop the service instead of leaving a zombie instance.
 *
 * Drives the real service through its public intent interface
 * ([Service.onStartCommand]); the ExoPlayer instance is only read back to
 * observe the playback state the service sets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumiApp::class)
class PlayerServicePauseTest {

    private var startId = 0

    @Before
    fun resetState() {
        PlayerStateHolder.update { PlayerUiState() }
    }

    private fun send(
        controller: org.robolectric.android.controller.ServiceController<PlayerService>,
        action: String,
    ) {
        controller.withIntent(Intent().setAction(action)).startCommand(0, ++startId)
    }

    private fun playerOf(service: PlayerService): ExoPlayer {
        val field = PlayerService::class.java.getDeclaredField("player")
        field.isAccessible = true
        return field.get(service) as ExoPlayer
    }

    @Test
    fun pauseOfPlayingSessionPausesKeepsTheSession_andIsIdempotent() {
        val entries = ArrayList(
            listOf(EntrySpec(EntryType.AUDIO.storageValue, "Song A", "content://file/A")),
        )
        val start = Intent()
            .setAction(PlayerService.ACTION_START)
            .putParcelableArrayListExtra(PlayerService.EXTRA_ENTRIES, entries)
            .putExtra(PlayerService.EXTRA_TAG_UID, "TAG-A")
        val controller = Robolectric.buildService(PlayerService::class.java, start)
        val service = controller.create().get()
        controller.startCommand(0, ++startId)

        try {
            assertTrue("started queue should be ready to play", playerOf(service).playWhenReady)
            PlayerStateHolder.update {
                it.copy(status = PlaybackStatus.PLAYING, activeTagUid = "TAG-A")
            }

            // The tag leaves range in proximity mode: the presence poll sends
            // the dedicated PAUSE.
            send(controller, PlayerService.ACTION_PAUSE)
            assertFalse("pause should stop playback", playerOf(service).playWhenReady)

            // The session is kept, not torn down: the tag stays active in the
            // shared state, so a re-read of it takes the RESUME path instead
            // of starting a fresh queue.
            assertEquals(
                "pause must not clear the active tag (the re-read must resume)",
                "TAG-A",
                PlayerStateHolder.state.value.activeTagUid,
            )

            // The poll re-fires on the next confirmed loss while the session
            // is already paused: a no-op, never a toggle back on.
            send(controller, PlayerService.ACTION_PAUSE)
            assertFalse("a repeated pause must stay paused", playerOf(service).playWhenReady)

            // Bringing the tag back resumes the SAME session.
            send(controller, PlayerService.ACTION_RESUME)
            assertTrue("the re-read of the removed tag must resume the paused session",
                playerOf(service).playWhenReady)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun pauseWithEmptyQueueStopsInsteadOfLeavingZombieService() {
        // The holder still says PLAYING from a previous service instance that
        // died (e.g. the FGS limit or a swiped notification) while the
        // process lived on; the tag's presence poll sends PAUSE to a fresh,
        // empty-queue service instance.
        PlayerStateHolder.update { PlayerUiState().copy(status = PlaybackStatus.PLAYING) }
        val controller = Robolectric.buildService(PlayerService::class.java)
        val service = controller.create().get()

        send(controller, PlayerService.ACTION_PAUSE)

        val state = PlayerStateHolder.state.value
        assertEquals(
            "pause on an empty queue must run the stop path (status back to IDLE)",
            PlaybackStatus.IDLE,
            state.status,
        )
    }
}
