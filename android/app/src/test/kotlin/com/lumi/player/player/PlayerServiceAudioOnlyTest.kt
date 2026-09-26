package com.lumi.player.player

import android.content.Intent
import android.net.Uri
import androidx.media3.common.PlaybackException
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioral tests for the audio-only playback change: legacy non-audio
 * (video / url) entries can still exist in the database, but the service
 * must skip them instead of showing the old "coming soon" placeholder, stop
 * cleanly when the queue holds no audio at all, and still honor the
 * playback-error guard when an all-audio queue keeps failing.
 *
 * Drives the real service through its public intent interface
 * ([android.app.Service.onStartCommand]) and the [Player.Listener] callbacks
 * it exposes; the ExoPlayer instance is read back to observe what the
 * service loaded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumiApp::class)
class PlayerServiceAudioOnlyTest {

    private var startId = 0

    @Before
    fun resetState() {
        PlayerStateHolder.update { PlayerUiState() }
    }

    private fun start(
        entries: List<EntrySpec>,
        startIndex: Int = 0,
    ): org.robolectric.android.controller.ServiceController<PlayerService> {
        val intent = Intent()
            .setAction(PlayerService.ACTION_START)
            .putParcelableArrayListExtra(PlayerService.EXTRA_ENTRIES, ArrayList(entries))
            .putExtra(PlayerService.EXTRA_TAG_UID, "TAG-A")
            .putExtra(PlayerService.EXTRA_START_ENTRY_INDEX, startIndex)
        val controller = Robolectric.buildService(PlayerService::class.java, intent)
        controller.create()
        controller.startCommand(0, ++startId)
        return controller
    }

    private fun playerOf(service: PlayerService): androidx.media3.exoplayer.ExoPlayer {
        val field = PlayerService::class.java.getDeclaredField("player")
        field.isAccessible = true
        return field.get(service) as androidx.media3.exoplayer.ExoPlayer
    }

    @Test
    fun mixedQueueSkipsLegacyVideoAndUrlAndPlaysTheAudioEntry() {
        // Legacy rows from before the audio-only change: video and url first,
        // audio last. The service must skip over the non-audio entries and
        // load the audio one - no COMING_SOON state, no waiting ticker.
        val controller = start(
            listOf(
                EntrySpec(EntryType.VIDEO.storageValue, "Old Clip", "content://media/video/clip"),
                EntrySpec(EntryType.URL.storageValue, "Toy Site", "https://example.com/toy"),
                EntrySpec(EntryType.AUDIO.storageValue, "Song A", "content://media/audio/A"),
            ),
        )
        val service = controller.get()

        try {
            val p = playerOf(service)
            assertEquals(
                "non-audio entries must be skipped and the audio entry loaded",
                Uri.parse("content://media/audio/A"),
                p.currentMediaItem?.localConfiguration?.uri,
            )
            assertTrue("skipped entries must leave the audio entry ready to play", p.playWhenReady)

            val s = PlayerStateHolder.state.value
            assertEquals("Song A", s.currentLabel)
            assertEquals(EntryType.AUDIO.storageValue, s.currentType)
            assertEquals("the tag must still be the active tag", "TAG-A", s.activeTagUid)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun queueWithOnlyLegacyNonAudioEntriesStopsInsteadOfSpinning() {
        // A tag whose whole list is legacy video/url rows has nothing the
        // audio-only player can play: the service must run the stop path.
        val controller = start(
            listOf(
                EntrySpec(EntryType.VIDEO.storageValue, "Old Clip", "content://media/video/clip"),
                EntrySpec(EntryType.URL.storageValue, "Toy Site", "https://example.com/toy"),
            ),
        )
        val service = controller.get()

        try {
            val s = PlayerStateHolder.state.value
            assertEquals(
                "a queue with no audio must run the stop path, got ${s.status}",
                PlaybackStatus.IDLE,
                s.status,
            )
            assertNull("stop path must clear the active tag", s.activeTagUid)
            assertNull("stop path must clear the label", s.currentLabel)
            assertEquals(
                "no non-audio media may be left loaded on the player",
                0,
                playerOf(service).mediaItemCount,
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun allCorruptAudioQueueHitsTheErrorGuardInsteadOfRepreparingForever() {
        // Adversarial: every item in the queue fails. The error counter may
        // only be reset when playback actually starts, so repeated
        // prepare/error cycles on the same corrupt items must accumulate
        // until the guard stops the service - not spin without throttle.
        val entries = listOf(
            EntrySpec(EntryType.AUDIO.storageValue, "Broken One", "content://media/audio/broken1"),
            EntrySpec(EntryType.AUDIO.storageValue, "Broken Two", "content://media/audio/broken2"),
        )
        val controller = start(entries)
        val service = controller.get()

        try {
            fun failCurrentFile() = service.onPlayerError(
                PlaybackException(
                    "corrupt file",
                    null,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                ),
            )

            // First failure: one of two items failed - keep going (advance),
            // the service must NOT stop on the first error of a longer list.
            failCurrentFile()
            val afterFirst = PlayerStateHolder.state.value
            assertNotNull(
                "first error of a 2-item queue must advance, not stop " +
                    "(active tag still set; status=${afterFirst.status})",
                afterFirst.activeTagUid,
            )

            // Second failure: every item has now failed - the guard must stop.
            failCurrentFile()
            val afterSecond = PlayerStateHolder.state.value
            assertEquals(
                "once every item has failed the guard must stop, got ${afterSecond.status}",
                PlaybackStatus.IDLE,
                afterSecond.status,
            )
            assertNull("guard stop must clear the active tag", afterSecond.activeTagUid)
            assertNull("guard stop must clear the label", afterSecond.currentLabel)
        } finally {
            controller.destroy()
        }
    }
}
