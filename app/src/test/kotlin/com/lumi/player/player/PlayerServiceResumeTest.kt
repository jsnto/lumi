package com.lumi.player.player

import android.content.Intent
import androidx.media3.exoplayer.ExoPlayer
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioral tests for the NFC resume hardening in [PlayerService]: the
 * dedicated [PlayerService.ACTION_RESUME] command must be idempotent so a
 * reader-mode re-report of the same in-range tag can never burst toggles
 * (a paused user's resume must stay resumed), and a resume against an empty
 * queue must stop the service instead of leaving a zombie instance alive.
 *
 * Drives the real service through its public intent interface
 * ([Service.onStartCommand]); the ExoPlayer instance is only read back to
 * observe the playback state the service sets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumiApp::class)
class PlayerServiceResumeTest {

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
    fun resumeOfPausedTagIsIdempotentAcrossRepeatedReReports() {
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
            PlayerStateHolder.update { it.copy(status = PlaybackStatus.PLAYING) }

            // User pauses from the screen/notification: TOGGLE flips off.
            send(controller, PlayerService.ACTION_TOGGLE)
            assertFalse("toggle should pause playback", playerOf(service).playWhenReady)

            // The tag re-reads while paused: the view model sends the
            // dedicated RESUME. Re-reports of the same in-range tag send it
            // again and again - playback must stay resumed, never burst off.
            send(controller, PlayerService.ACTION_RESUME)
            assertTrue("first resume should start playback", playerOf(service).playWhenReady)
            send(controller, PlayerService.ACTION_RESUME)
            assertTrue(
                "re-report of the same tag must not toggle playback off",
                playerOf(service).playWhenReady,
            )
            send(controller, PlayerService.ACTION_RESUME)
            assertTrue("third re-report must keep playback resumed", playerOf(service).playWhenReady)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun resumeWithEmptyQueueStopsInsteadOfLeavingZombieService() {
        // The holder still says PAUSED from a previous service instance that
        // died (e.g. the FGS limit or a swiped notification) while the
        // process lived on; the tag is re-read and the view model sends
        // RESUME to a fresh, empty-queue service instance.
        PlayerStateHolder.update { PlayerUiState().copy(status = PlaybackStatus.PAUSED) }
        val controller = Robolectric.buildService(PlayerService::class.java)
        val service = controller.create().get()

        send(controller, PlayerService.ACTION_RESUME)

        val state = PlayerStateHolder.state.value
        assertTrue(
            "resume on an empty queue must run the stop path (status back to IDLE), " +
                "got ${state.status}",
            state.status == PlaybackStatus.IDLE,
        )
        assertNull("stop path must clear the active tag", state.activeTagUid)
        assertNull("stop path must clear the label", state.currentLabel)
    }
}
