package com.lumi.player.ui

import android.app.Application
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import com.lumi.player.data.PlayMode
import com.lumi.player.nfc.GenericPresenceSession
import com.lumi.player.player.PlayerService
import com.lumi.player.player.PlayerStateHolder
import com.lumi.player.player.PlayerUiState
import com.lumi.player.player.PlaybackStatus
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Behavioral coverage for the proximity-mode GENERIC fast path: a tag the
 * Mifare fast path cannot read keeps a tag-tech connection (NfcA/IsoDep)
 * alive ([com.lumi.player.nfc.GenericPresencePoller]) so a non-Mifare tag
 * HELD STATIONARY keeps the presence record fresh instead of false-pausing
 * after the absence window. Drives the real [MainActivity] under Robolectric
 * and replaces the activity's generic-session seam with a fake connection:
 * one that stays connected (the tag is still on the antenna) and one that
 * drops (the tag was lifted). Pins that a still-connected tag keeps playing
 * and refreshes the presence record, that a dropped connection pauses
 * through the existing tag-lost route without a fallback double-report,
 * that a new tag presentation cancels the loop, and that the session is
 * closed when the loop ends.
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
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
        PlayerStateHolder.update { PlayerUiState() }
    }

    private fun app(): LumiApp = ApplicationProvider.getApplicationContext()

    private fun startMainActivity(): ActivityController<MainActivity> {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        compose.waitForIdle()
        return controller
    }

    private fun tagWithUid(uid: String): Tag {
        // The stub SDK omits Tag's test hooks; the real framework (loaded by
        // Robolectric at runtime) has the public six-arg constructor, so
        // construct the synthetic tag reflectively.
        val nfcTagCls = Class.forName("android.nfc.INfcTag")
        val ctor = Tag::class.java.getDeclaredConstructor(
            ByteArray::class.java,
            IntArray::class.java,
            Array<android.os.Bundle>::class.java,
            Int::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
            nfcTagCls,
        )
        ctor.isAccessible = true
        val tagService = java.lang.reflect.Proxy.newProxyInstance(
            nfcTagCls.classLoader,
            arrayOf(nfcTagCls),
        ) { _, _, _ -> null } as Any
        return ctor.newInstance(
            ByteArray(uid.length / 2) {
                uid.substring(it * 2, it * 2 + 2).toInt(16).toByte()
            },
            intArrayOf(),
            arrayOfNulls<android.os.Bundle>(0),
            0,
            0L,
            tagService,
        ) as Tag
    }

    /**
     * Delivers a tag to the resumed activity the way the NFC stack's
     * foreground dispatch would: an intent carrying EXTRA_TAG to the
     * activity itself, arriving as onNewIntent.
     */
    private fun sendTag(controller: ActivityController<MainActivity>, uid: String) {
        controller.newIntent(Intent(Intent.ACTION_MAIN).putExtra(NfcAdapter.EXTRA_TAG, tagWithUid(uid)))
        compose.waitForIdle()
    }

    /**
     * Replaces the activity's generic-session seam: every tag then gets
     * [session] (or no generic session at all when [session] is null).
     */
    private fun withGenericSession(activity: MainActivity, session: GenericPresenceSession?) {
        val field = MainActivity::class.java.getDeclaredField("establishGenericPresenceSession")
        field.isAccessible = true
        val factory: (Tag) -> GenericPresenceSession? = { session }
        field.set(activity, factory)
    }

    private fun presencePollJobOf(activity: MainActivity): Job? {
        val field = MainActivity::class.java.getDeclaredField("presencePollJob")
        field.isAccessible = true
        return field.get(activity) as Job?
    }

    private fun lastSeenAtOf(activity: MainActivity): Long {
        val field = MainActivity::class.java.getDeclaredField("lastSeenAt")
        field.isAccessible = true
        return field.get(activity) as Long
    }

    /** A fake tag-tech connection whose state the test can flip. */
    private class FakeGenericSession : GenericPresenceSession {
        @Volatile var connected = true
        @Volatile var closeCount = 0
        override val isConnected: Boolean get() = connected
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
                app().repository.updateTagEntries(tag.id) { it + entry.id }
            }
        }
    }

    @Test
    fun proximityMode_nonMifareTagStillConnected_genericLoopKeepsPresenceRecordFresh() {
        setupDb(mode = PlayMode.PROXIMITY)
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PLAYING, activeTagUid = UID_A)
        }
        val controller = startMainActivity()
        val activity = controller.get()
        val session = FakeGenericSession()
        withGenericSession(activity, session)
        // The synthetic tag carries no Mifare Ultralight tech, so the
        // Mifare fast path cannot run; the re-read of the active tag must
        // start the generic fast path instead.
        sendTag(controller, UID_A)
        assertTrue(
            "a non-Mifare active tag with a connectable session must run the " +
                "generic presence poll",
            presencePollJobOf(activity)?.isActive == true,
        )
        val seededAt = lastSeenAtOf(activity)
        // The loop checks the connection on a real background thread every
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
            "a still-connected tag must refresh the presence record, keeping " +
                "the absence-timeout fallback quiet while the tag is on the " +
                "antenna (no false pause for a held-still non-Mifare tag)",
            lastSeenAtOf(activity) > seededAt,
        )
        assertNull("a still-connected tag must keep playing", shadowOf(app() as Application).nextStartedService)
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
    fun proximityMode_nonMifareTagDisconnects_genericLoopPausesThroughTheTagLostRoute() {
        setupDb(mode = PlayMode.PROXIMITY)
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PLAYING, activeTagUid = UID_A)
        }
        val controller = startMainActivity()
        val activity = controller.get()
        val session = FakeGenericSession()
        withGenericSession(activity, session)
        sendTag(controller, UID_A)
        assertTrue("the generic presence poll must be running",
            presencePollJobOf(activity)?.isActive == true)
        // The tag is lifted: the connection drops at the next check.
        session.connected = false
        val intent = nextServiceIntent(3_000)
        assertNotNull(
            "a dropped generic connection must pause playback through the " +
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
        val controller = startMainActivity()
        val activity = controller.get()
        withGenericSession(activity, FakeGenericSession())
        sendTag(controller, UID_A)
        val firstJob = presencePollJobOf(activity)!!
        assertTrue("the active tag's generic poll must be running", firstJob.isActive)
        // An unrelated tag (not even registered) is presented: its read
        // must cancel A's poll.
        sendTag(controller, UID_B)
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
        val controller = startMainActivity()
        val activity = controller.get()
        withGenericSession(activity, null)
        // No Mifare session and no generic session: no fast-path poll may
        // run, and the absence-timeout fallback must still detect the
        // removal.
        sendTag(controller, UID_A)
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
