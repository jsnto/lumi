package com.lumi.player.ui

import android.app.Application
import android.content.Intent
import android.nfc.Tag
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import com.lumi.player.data.PlayMode
import com.lumi.player.nfc.GenericPresenceSession
import com.lumi.player.player.PlayerService
import com.lumi.player.player.PlayerStateHolder
import com.lumi.player.player.PlayerUiState
import com.lumi.player.player.PlaybackStatus
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Behavioral coverage for the proximity-mode GENERIC fast path: a tag the
 * Mifare fast path cannot read keeps a tag-tech session (NfcA/IsoDep) alive
 * and is probed with a real low-level read every tick
 * ([com.lumi.player.nfc.GenericPresencePoller]) so a non-Mifare tag HELD
 * STATIONARY keeps the presence record fresh instead of false-pausing after
 * the absence window. Drives the real [MainActivity] under Robolectric and
 * replaces the presence engine's generic-session seam with a fake probe:
 * one that
 * keeps answering (the tag is still on the antenna) and one that stops
 * answering (the tag was lifted). Pins that a still-answering tag keeps
 * playing and refreshes the presence record, that a tag that stops answering
 * pauses through the existing tag-lost route (after the two-strike debounce)
 * without a fallback double-report, that a new tag presentation cancels the
 * loop, and that the session is closed when the loop ends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GenericPresencePollUiTest {

    @get:Rule
    val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()

    @Before
    fun setUp() {
        markSetupCompleteForTests()
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
        PlayerStateHolder.update { PlayerUiState() }
    }

    /**
     * Replaces the presence engine's generic-session seam: every tag then
     * gets [session] (or no generic session at all when [session] is null).
     */
    private fun withGenericSession(activity: MainActivity, session: GenericPresenceSession?) {
        activity.presenceEngine.establishGenericPresenceSession = { session }
    }

    private fun presencePollJobOf(activity: MainActivity): Job? =
        activity.presenceEngine.presencePollJob

    private fun lastSeenAtOf(activity: MainActivity): Long =
        activity.presenceEngine.lastSeenAt

    /** A fake tag-tech session whose probe the test can make fail. */
    private class FakeGenericSession : GenericPresenceSession {
        @Volatile var probeThrows = false
        @Volatile var closeCount = 0
        override fun probe() {
            if (probeThrows) throw IOException("tag out of range")
        }
        override fun close() {
            closeCount++
        }
    }

    private fun setupDb(
        mode: PlayMode = PlayMode.CONTINUOUS,
        tagA: Boolean = true,
    ) {
        runBlocking {
            app().repository.setPlayMode(mode)
            if (tagA) {
                val tag = app().repository.addTag(UID_A, "Tag A")!!
                val entry = app().repository.addEntry(EntryType.AUDIO, "Song A", "content://a")
                app().repository.linkEntryToTag(tag.id, entry.id)
            }
        }
    }

    @Test
    fun proximityMode_nonMifareTagStillProbing_genericLoopKeepsPresenceRecordFresh() {
        setupDb(mode = PlayMode.PROXIMITY)
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PLAYING, activeTagUid = UID_A)
        }
        val controller = compose.setupMainActivity()
        val activity = controller.get()
        val session = FakeGenericSession()
        withGenericSession(activity, session)
        // The synthetic tag carries no Mifare Ultralight tech, so the
        // Mifare fast path cannot run; the re-read of the active tag must
        // start the generic fast path instead.
        compose.sendTagWithUid(controller, UID_A)
        assertTrue(
            "a non-Mifare active tag with a connectable session must run the " +
                "generic presence poll",
            presencePollJobOf(activity)?.isActive == true,
        )
        val seededAt = lastSeenAtOf(activity)
        // The loop probes the tag on a real background thread every
        // 300 ms and refreshes the presence record on the main thread. The
        // refresh is stamped with the CURRENT virtual clock at the moment it
        // is posted, so advance the virtual clock (with a duration) between
        // real-time pumps until a refresh lands - a plain idle would process
        // the refresh at the same virtual instant it was posted.
        val deadline = System.currentTimeMillis() + 3_000
        while (lastSeenAtOf(activity) <= seededAt && System.currentTimeMillis() < deadline) {
            Thread.sleep(150)
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        }
        assertTrue(
            "a still-answering tag must refresh the presence record, keeping " +
                "the absence-timeout fallback quiet while the tag is on the " +
                "antenna (no false pause for a held-still non-Mifare tag)",
            lastSeenAtOf(activity) > seededAt,
        )
        assertNull("a still-answering tag must keep playing", shadowOf(app() as Application).nextStartedService)
        assertEquals(PlaybackStatus.PLAYING, PlayerStateHolder.state.value.status)
        val job = presencePollJobOf(activity)!!
        controller.pause() // tears the poll down
        // The loop runs on a real background thread; give its finally block
        // (the session close) a moment to run after the cancel lands. The
        // activity's field is nulled on teardown, so assert on the held
        // reference.
        Thread.sleep(500)
        assertFalse("pausing the activity must stop the generic presence poll",
            job.isActive)
        assertTrue("the generic session must be closed when the poll ends", session.closeCount >= 1)
    }

    @Test
    fun proximityMode_nonMifareTagLifted_genericLoopPausesThroughTheTagLostRoute() {
        setupDb(mode = PlayMode.PROXIMITY)
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PLAYING, activeTagUid = UID_A)
        }
        val controller = compose.setupMainActivity()
        val activity = controller.get()
        val session = FakeGenericSession()
        withGenericSession(activity, session)
        compose.sendTagWithUid(controller, UID_A)
        assertTrue("the generic presence poll must be running",
            presencePollJobOf(activity)?.isActive == true)
        // The tag is lifted: the probes stop answering, and two failed
        // probes in a row confirm the removal (the two-strike debounce).
        session.probeThrows = true
        val intent = nextServiceIntent(3_000)
        assertNotNull(
            "a confirmed generic-path removal must pause playback through the " +
                "tag-lost route",
            intent,
        )
        assertEquals(PlayerService.ACTION_PAUSE, intent!!.action)
        // The fast path cleared the presence record: the fallback poll must
        // not issue a second pause for the same removal.
        PlayerStateHolder.update { it.copy(status = PlaybackStatus.PAUSED) }
        ShadowLooper.idleMainLooper(17_000, TimeUnit.MILLISECONDS)
        assertNull(
            "a confirmed generic-path removal must not be double-reported by " +
                "the absence-timeout fallback",
            shadowOf(app() as Application).nextStartedService,
        )
        assertTrue("the poll must end after the removal",
            presencePollJobOf(activity)?.isCompleted == true)
        assertTrue("the generic session must be closed when the poll ends", session.closeCount >= 1)
    }

    @Test
    fun newTagPresentation_cancelsThePreviousTagsGenericPoll() {
        setupDb(mode = PlayMode.PROXIMITY)
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PLAYING, activeTagUid = UID_A)
        }
        val controller = compose.setupMainActivity()
        val activity = controller.get()
        withGenericSession(activity, FakeGenericSession())
        compose.sendTagWithUid(controller, UID_A)
        val firstJob = presencePollJobOf(activity)!!
        assertTrue("the active tag's generic poll must be running", firstJob.isActive)
        // An unrelated tag (not even registered) is presented: its read
        // must cancel A's poll.
        compose.sendTagWithUid(controller, UID_B)
        ShadowLooper.idleMainLooper()
        assertTrue("a new tag presentation must cancel the previous tag's poll",
            firstJob.isCancelled)
    }

    @Test
    fun noGenericSession_fallsBackToAbsenceTimeout() {
        setupDb(mode = PlayMode.PROXIMITY)
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PLAYING, activeTagUid = UID_A)
        }
        val controller = compose.setupMainActivity()
        val activity = controller.get()
        withGenericSession(activity, null)
        // No Mifare session and no generic session: no fast-path poll may
        // run, and the absence-timeout fallback must still detect the
        // removal.
        compose.sendTagWithUid(controller, UID_A)
        val deadline = System.currentTimeMillis() + 3_000
        while (presencePollJobOf(activity)!!.isActive && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
        assertFalse(
            "a tag with no Mifare and no generic session must not run a " +
                "fast-path poll",
            presencePollJobOf(activity)!!.isActive,
        )
        ShadowLooper.idleMainLooper(17_000, TimeUnit.MILLISECONDS)
        val intent = shadowOf(app() as Application).nextStartedService
        assertNotNull(
            "the absence-timeout fallback must still pause when such a tag " +
                "leaves range",
            intent,
        )
        assertEquals(PlayerService.ACTION_PAUSE, intent!!.action)
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
        const val UID_B = "04DEADBEEF"
    }
}
