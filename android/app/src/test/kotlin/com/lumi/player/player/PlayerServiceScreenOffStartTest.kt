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
 * The startQueue screen-off gate: no playback begins on a locked device. A
 * new-queue start that lands while the device is non-interactive is refused
 * and any paused session is left exactly as-is (the screen-off's pause
 * already owns it - the refusal never stops it), while a fresh shell with
 * no session self-stops like every other empty-queue command. A start
 * dispatched in the milliseconds before isInteractive flips is caught by
 * the receiver's delayed recheck pause (a bounded sliver, accepted by
 * design - pinned in [com.lumi.player.device.ScreenOffReceiverTest]).
 *
 * Drives the real service through its public intent interface
 * ([Service.onStartCommand]); the PowerManager reading is faked through
 * its shadow and the ExoPlayer instance is only read back to observe the
 * playback state the service sets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumiApp::class)
class PlayerServiceScreenOffStartTest {

    private var startId = 0

    @Before
    fun resetState() {
        PlayerStateHolder.update { PlayerUiState() }
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
        label: String,
        uid: String,
    ) {
        val entries = ArrayList(
            listOf(EntrySpec(EntryType.AUDIO.storageValue, label, "content://file/$uid")),
        )
        controller
            .withIntent(
                Intent()
                    .setAction(PlayerService.ACTION_START)
                    .putParcelableArrayListExtra(PlayerService.EXTRA_ENTRIES, entries)
                    .putExtra(PlayerService.EXTRA_TAG_UID, uid),
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
    fun aStartWhileNonInteractiveWithNoSessionIsRefusedAndPlaysNothing() {
        // The tag's start was in flight when the screen went dark: the
        // receiver skipped its pause (no session), and the then-landing
        // start must not begin playback on the locked device.
        val controller = Robolectric.buildService(PlayerService::class.java)
        val service = controller.create().get()
        setScreen(service, interactive = false)

        sendStart(controller, label = "Song A", uid = "TAG-A")

        assertNull("a refused start must not build a player", playerOf(service))
        val state = PlayerStateHolder.state.value
        assertEquals(
            "a refused start leaves no session",
            PlaybackStatus.IDLE,
            state.status,
        )
        assertNull("a refused start leaves no current entry", state.currentLabel)
        controller.destroy()
    }

    @Test
    fun aRacingStartWhileNonInteractiveLeavesThePausedSessionUntouched() {
        // A session is playing when the screen goes dark...
        val controller = Robolectric.buildService(PlayerService::class.java)
        val service = controller.create().get()
        setScreen(service, interactive = true)
        sendStart(controller, label = "Song A", uid = "TAG-A")
        val player = playerOf(service)
        assertTrue(
            "the start while the screen is on should be ready to play",
            player != null && player.playWhenReady,
        )
        // ...the screen-off's pause lands on it...
        sendPause(controller)
        assertFalse(
            "the screen-off's pause pauses the session",
            player!!.playWhenReady,
        )
        // ...and the screen goes dark.
        setScreen(service, interactive = false)

        // The tag-switch start dispatched before the lock settles then
        // lands on the paused session: refused, the session untouched.
        sendStart(controller, label = "Song B", uid = "TAG-B")

        assertFalse(
            "a refused racing start must not start playback",
            player!!.playWhenReady,
        )
        val state = PlayerStateHolder.state.value
        assertEquals(
            "a refused racing start keeps the paused session's tag",
            "TAG-A",
            state.activeTagUid,
        )
        assertEquals(
            "a refused racing start keeps the paused session's entry",
            "Song A",
            state.currentLabel,
        )
        controller.destroy()
    }

    @Test
    fun aStartWhileInteractiveStartsNormally() {
        // The screen is on: the gate does not interfere with an ordinary
        // start.
        val controller = Robolectric.buildService(PlayerService::class.java)
        val service = controller.create().get()
        setScreen(service, interactive = true)

        sendStart(controller, label = "Song A", uid = "TAG-A")

        val player = playerOf(service)
        assertTrue(
            "a start while the screen is on must play",
            player != null && player.playWhenReady,
        )
        controller.destroy()
    }
}
