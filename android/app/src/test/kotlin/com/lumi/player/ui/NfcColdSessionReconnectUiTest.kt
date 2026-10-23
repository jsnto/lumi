package com.lumi.player.ui

import android.app.Application
import android.content.Intent
import android.nfc.Tag
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import com.lumi.player.data.PlayMode
import com.lumi.player.nfc.GenericPresenceSession
import com.lumi.player.nfc.PresenceReader
import com.lumi.player.player.PlayerService
import com.lumi.player.player.PlayerStateHolder
import com.lumi.player.player.PlayerUiState
import com.lumi.player.player.PlaybackStatus
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import com.lumi.player.ui.testutil.syntheticTag
import com.lumi.player.ui.testutil.sendTagWithUid
import com.lumi.player.ui.testutil.app
import com.lumi.player.ui.testutil.setupMainActivity

/**
 * Regression for the on-device proximity stop: on a real device, a tag
 * HELD STILL in range goes cold - the phone's RF session for it drops while
 * the tag is physically still in the reader's field, so the fast-path
 * reads/probes fail and the two-strike debounce confirms "tag removed"
 * (~400-500 ms after the activating read), pausing playback even though
 * the tag never left.
 *
 * The fix under test: a fast-path read/probe is only a removal strike after
 * a FRESH session has tried and failed too
 * ([com.lumi.player.nfc.ReconnectingPresenceSession]) - a cold tag answers
 * a fresh connect + read and keeps playing; a genuinely removed tag answers
 * neither and still pauses.
 *
 * Drives the real [MainActivity] under Robolectric and replaces the
 * session-establishment seams with factories: the first call (the initial
 * session) is cold (every read fails), later calls (the reconnects) return
 * a healthy session (the tag is still in the field) - or nothing at all
 * (the tag genuinely left).
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NfcColdSessionReconnectUiTest {

    @get:Rule
    val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()

    @Before
    fun setUp() {
        markSetupCompleteForTests()
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
        PlayerStateHolder.update { PlayerUiState() }
    }

    private fun withPresenceSessionFactory(activity: MainActivity, factory: (Tag) -> PresenceReader?) {
        activity.presenceEngine.establishPresenceSession = factory
    }

    private fun withGenericSessionFactory(activity: MainActivity, factory: (Tag) -> GenericPresenceSession?) {
        activity.presenceEngine.establishGenericPresenceSession = factory
    }

    private fun presencePollJobOf(activity: MainActivity): kotlinx.coroutines.Job? =
        activity.presenceEngine.presencePollJob

    /** A session that is cold from the start: every read fails. */
    private val coldReader = PresenceReader { throw IOException("session cold (tag out of RF range?)") }

    /** A session that always answers (the tag is in the field). */
    private val healthyReader = PresenceReader { /* page 0 answered */ }

    private class FakeGenericSession(private val fail: Boolean) : GenericPresenceSession {
        override fun probe() {
            if (fail) throw IOException("session cold (tag out of RF range?)")
        }

        override fun close() {
            // no-op
        }
    }

    private fun setupDb() {
        runBlocking {
            app().repository.setPlayMode(PlayMode.PROXIMITY)
            val tag = app().repository.addTag(UID_A, "Tag A")!!
            val entry = app().repository.addEntry(EntryType.AUDIO, "Song A", "content://a")
            app().repository.linkEntryToTag(tag.id, entry.id)
        }
    }

    @Test
    fun mifareSessionGoesCold_tagStillInRange_reconnectKeepsPlayback_notAPause() {
        setupDb()
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PLAYING, activeTagUid = UID_A)
        }
        val controller = compose.setupMainActivity()
        val activity = controller.get()
        // The initial session goes cold (a stationary tag under foreground
        // dispatch); every reconnect establishes a fresh session that
        // answers - the tag never left the field.
        val calls = AtomicInteger(0)
        withPresenceSessionFactory(activity) {
            calls.incrementAndGet()
            if (calls.get() == 1) coldReader else healthyReader
        }
        compose.sendTagWithUid(controller, UID_A) // re-read of the active tag: starts the poll
        // Let the poll run through several read cycles in real time (the
        // cold read, the reconnect, and the healthy reads that follow).
        pump(2_500)
        assertTrue(
            "a reconnectable cold session must keep the continuous-reading poll running",
            presencePollJobOf(activity)?.isActive == true,
        )
        assertEquals(
            "the reconnect must have re-established a fresh session",
            2, calls.get(),
        )
        assertNull(
            "a tag that answers a fresh session is still in range: no pause " +
                "(the on-device bug paused here ~500 ms after the activating read)",
            shadowOf(app() as Application).nextStartedService,
        )
        assertEquals(PlaybackStatus.PLAYING, PlayerStateHolder.state.value.status)
        // And it must hold: a still-present tag keeps playing well past the
        // old two-strike window.
        pump(2_000)
        assertNull("a held still tag that answers fresh sessions must keep playing",
            shadowOf(app() as Application).nextStartedService)
        assertEquals(PlaybackStatus.PLAYING, PlayerStateHolder.state.value.status)
    }

    @Test
    fun mifareSessionGoesCold_andNoFreshSessionAvailable_pauseStillFires() {
        setupDb()
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PLAYING, activeTagUid = UID_A)
        }
        val controller = compose.setupMainActivity()
        val activity = controller.get()
        // The initial session is cold and every reconnect fails to
        // establish a session (the tag genuinely left the field): the
        // fresh-session attempt must not mask the removal - the strike
        // stands and the two-strike debounce still pauses.
        val calls = AtomicInteger(0)
        withPresenceSessionFactory(activity) {
            calls.incrementAndGet()
            if (calls.get() == 1) coldReader else null
        }
        compose.sendTagWithUid(controller, UID_A)
        val intent = nextServiceIntent(5_000)
        assertNotNull(
            "a tag that answers neither its session nor a fresh session must still pause",
            intent,
        )
        assertEquals(PlayerService.ACTION_PAUSE, intent!!.action)
        assertTrue(
            "the removal must only stand after a fresh session was attempted",
            calls.get() >= 2,
        )
    }

    @Test
    fun genericSessionGoesCold_tagStillInRange_reconnectKeepsPlayback_notAPause() {
        setupDb()
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PLAYING, activeTagUid = UID_A)
        }
        val controller = compose.setupMainActivity()
        val activity = controller.get()
        // The synthetic tag carries no tech list, so the default Mifare seam
        // finds no session and the generic fast path owns it. Its initial
        // session goes cold; every reconnect establishes a fresh session
        // that answers - the tag never left the field.
        val calls = AtomicInteger(0)
        withGenericSessionFactory(activity) {
            calls.incrementAndGet()
            if (calls.get() == 1) FakeGenericSession(fail = true) else FakeGenericSession(fail = false)
        }
        compose.sendTagWithUid(controller, UID_A)
        pump(2_500)
        assertTrue(
            "a reconnectable cold generic session must keep the probe poll running",
            presencePollJobOf(activity)?.isActive == true,
        )
        assertEquals("the reconnect must have re-established a fresh session", 2, calls.get())
        assertNull(
            "a non-Mifare tag that answers a fresh session is still in range: " +
                "no pause",
            shadowOf(app() as Application).nextStartedService,
        )
        assertEquals(PlaybackStatus.PLAYING, PlayerStateHolder.state.value.status)
        pump(2_000)
        assertNull("a held still non-Mifare tag that answers fresh sessions must keep playing",
            shadowOf(app() as Application).nextStartedService)
        assertEquals(PlaybackStatus.PLAYING, PlayerStateHolder.state.value.status)
    }

    /** Pumps the main looper (advancing the virtual clock) for ~[timeoutMs] of real time. */
    private fun pump(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        }
    }

    /** Consumes the next started-service intent, pumping until it arrives. */
    private fun nextServiceIntent(timeoutMs: Long = 5_000): Intent? {
        var intent: Intent? = null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (intent == null && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            intent = shadowOf(app() as Application).nextStartedService
            Thread.sleep(10)
        }
        return intent
    }

    companion object {
        const val UID_A = "04A1B2C3"
    }
}
