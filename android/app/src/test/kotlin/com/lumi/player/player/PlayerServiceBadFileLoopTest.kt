package com.lumi.player.player

import android.content.Intent
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * The round-3 on-device failure: a queue of unplayable files (the test tones
 * had a corrupt WAV header, so ExoPlayer refused each one with
 * PARSING_CONTAINER_MALFORMED) made the player loop file-to-file forever at
 * ~45 cycles/s - the title flashed between the entries and no audio was ever
 * produced.
 *
 * Two service behaviours pinned here:
 *  1. A queue in which EVERY entry keeps failing must stop the session
 *     (the `consecutiveErrors >= queue.size` guard), not loop. Pre-fix, the
 *     per-cycle BUFFERING state reset the guard before the error could be
 *     counted, so it never tripped (the log showed thousands of errors and
 *     no stop).
 *  2. An error-driven advance must not override a pause: pre-fix
 *     [PlayerService.loadCurrent] re-armed `playWhenReady = true` on every
 *     cycle, so an ACTION_PAUSE issued while the loop was running was
 *     clobbered within one cycle and the loop kept going.
 *
 * The tests drive the real service through its intent interface and feed it
 * the exact listener event sequence the device produced for a bad file
 * (BUFFERING - the failed prepare - then the error, per entry).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumiApp::class)
class PlayerServiceBadFileLoopTest {

    private var startId = 0

    @Before
    fun resetState() {
        PlayerStateHolder.update { PlayerUiState() }
    }

    private fun startService(
        labels: List<String>,
        controller: ServiceController<PlayerService>? = null,
    ): ServiceController<PlayerService> {
        val entries = ArrayList(
            labels.map { EntrySpec(EntryType.AUDIO.storageValue, it, "content://x/$it") },
        )
        val start = Intent()
            .setAction(PlayerService.ACTION_START)
            .putParcelableArrayListExtra(PlayerService.EXTRA_ENTRIES, entries)
            .putExtra(PlayerService.EXTRA_TAG_UID, "TAG-A")
        val built = controller ?: Robolectric.buildService(PlayerService::class.java, start)
        built.create()
        built.startCommand(0, ++startId)
        return built
    }

    /** The exact error ExoPlayer raised for the corrupt tones on-device. */
    private fun badFileError() =
        PlaybackException(
            "Expected block size: 2; got: 1",
            null,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        )

    /** One bad-file cycle as the device reported it: the prepare fails... */
    private fun failedPrepare(service: PlayerService) {
        service.onPlaybackStateChanged(Player.STATE_BUFFERING)
    }

    /** ...and the load error lands. */
    private fun failedLoad(service: PlayerService) {
        service.onPlayerError(badFileError())
    }

    @Test
    fun queueOfUnplayableFilesStopsInsteadOfLoopingForever() {
        val controller = startService(listOf("song_a.wav", "song_d.wav"))
        val service = controller.get()
        try {
            // Entry A: the prepare fails and the load errors; the service
            // advances to B.
            failedPrepare(service)
            failedLoad(service)

            // Entry B: same. Pre-fix, this BUFFERING event reset
            // consecutiveErrors to zero (the derived status is PLAYING
            // while a prepare is in flight), so B's error only advanced
            // again and the queue would cycle A -> B -> A -> ... forever.
            failedPrepare(service)
            failedLoad(service)

            val state = PlayerStateHolder.state.value
            assertEquals(
                "a queue where every entry keeps failing must stop the session",
                PlaybackStatus.IDLE,
                state.status,
            )
            assertNull("the session stop clears the active tag", state.activeTagUid)
            assertNull("the stopped session shows no current song", state.currentLabel)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun errorAdvanceDoesNotOverrideAPausedSession() {
        // Three entries so the session survives the pause and the in-flight
        // error (a two-entry queue would already be at the stop threshold).
        val controller = startService(listOf("song_a.wav", "song_b.wav", "song_c.wav"))
        val service = controller.get()
        try {
            // Entry A fails; the service advances to B.
            failedPrepare(service)
            failedLoad(service)

            // The tag leaves range mid-error: the presence poll pauses the
            // session without ending it.
            controller
                .withIntent(Intent().setAction(PlayerService.ACTION_PAUSE))
                .startCommand(0, ++startId)

            // The in-flight prepare of B fails; the advance to C must not
            // bring playback back on. Pre-fix, loadCurrent set
            // playWhenReady = true on that cycle, overriding the pause.
            failedPrepare(service)
            failedLoad(service)

            val playerField = PlayerService::class.java.getDeclaredField("player")
            playerField.isAccessible = true
            val player = playerField.get(service) as androidx.media3.exoplayer.ExoPlayer
            assertFalse(
                "an error-driven advance must keep the paused session paused",
                player.playWhenReady,
            )

            // The third consecutive failure still stops the session.
            failedPrepare(service)
            failedLoad(service)
            assertEquals(
                "the guard still stops an all-bad queue while paused",
                PlaybackStatus.IDLE,
                PlayerStateHolder.state.value.status,
            )
        } finally {
            controller.destroy()
        }
    }
}
