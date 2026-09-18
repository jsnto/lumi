package com.lumi.player.player

import android.content.Intent
import androidx.media3.exoplayer.ExoPlayer
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * Wiring tests for the remembered-position restore in [PlayerService]: the
 * entry index / offset extras the view model passes with
 * [PlayerService.ACTION_START] must position the queue and seek the player
 * to the remembered offset once the first item is ready, with out-of-range
 * values clamped instead of crashing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumiApp::class)
class PlayerServicePositionRestoreTest {

    private var startId = 0

    @Before
    fun resetState() {
        PlayerStateHolder.update { PlayerUiState() }
    }

    private fun startService(
        entries: List<EntrySpec>,
        uid: String,
        startIndex: Int = 0,
        seekMs: Long = 0L,
    ): Pair<ServiceController<PlayerService>, PlayerService> {
        val intent = Intent()
            .setAction(PlayerService.ACTION_START)
            .putParcelableArrayListExtra(PlayerService.EXTRA_ENTRIES, ArrayList(entries))
            .putExtra(PlayerService.EXTRA_TAG_UID, uid)
        if (seekMs > 0) {
            intent.putExtra(PlayerService.EXTRA_START_ENTRY_INDEX, startIndex)
            intent.putExtra(PlayerService.EXTRA_START_POSITION_MS, seekMs)
        }
        val controller = Robolectric.buildService(PlayerService::class.java, intent)
        val service = controller.create().get()
        controller.startCommand(0, ++startId)
        return controller to service
    }

    private fun playerOf(service: PlayerService): ExoPlayer {
        val field = PlayerService::class.java.getDeclaredField("player")
        field.isAccessible = true
        return field.get(service) as ExoPlayer
    }

    private fun queueIndexOf(service: PlayerService): Any {
        val field = PlayerService::class.java.getDeclaredField("position")
        field.isAccessible = true
        return field.get(service)
    }

    @Test
    fun restoreExtrasSeekThePlayerToTheRememberedOffset() {
        val (controller, service) = startService(
            entries = listOf(
                EntrySpec(EntryType.AUDIO.storageValue, "A", "content://file/A"),
                EntrySpec(EntryType.AUDIO.storageValue, "B", "content://file/B"),
                EntrySpec(EntryType.AUDIO.storageValue, "C", "content://file/C"),
            ),
            uid = "TAG-A",
            startIndex = 2,
            seekMs = 12_345L,
        )
        try {
            assertEquals("queue must start at the remembered entry", 2, queueIndexOf(service))
            assertEquals(
                "player must be seeked to the remembered offset once ready",
                12_345L,
                playerOf(service).currentPosition,
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun startWithoutRestoreExtrasStartsAtTheBeginning() {
        val (controller, service) = startService(
            entries = listOf(EntrySpec(EntryType.AUDIO.storageValue, "A", "content://file/A")),
            uid = "TAG-A",
        )
        try {
            assertEquals(0, queueIndexOf(service))
            assertEquals(0L, playerOf(service).currentPosition)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun outOfRangeRestoreIndexIsClampedIntoTheQueue() {
        val (controller, service) = startService(
            entries = listOf(EntrySpec(EntryType.AUDIO.storageValue, "A", "content://file/A")),
            uid = "TAG-A",
            startIndex = 5,
            seekMs = 10_000L,
        )
        try {
            assertEquals(0, queueIndexOf(service))
        } finally {
            controller.destroy()
        }
    }
}
