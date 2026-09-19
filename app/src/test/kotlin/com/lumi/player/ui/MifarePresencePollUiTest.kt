package com.lumi.player.ui

import android.app.Application
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import com.lumi.player.data.PlayMode
import com.lumi.player.nfc.PresenceReader
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Behavioral coverage for the proximity-mode continuous-reading fast path:
 * the Tag handle delivered by the always-listening foreground-dispatch read
 * is held and, for the active tag, read continuously in the background
 * ([com.lumi.player.nfc.MifarePresencePoller]). Drives the real
 * [MainActivity] under Robolectric and replaces the activity's
 * session-establishment seam with fake readers: a reader that always
 * answers (the tag is still in range) and one that always fails (the tag
 * left). Pins that a confirmed removal (two failed reads) pauses through
 * the existing route well before the absence-timeout fallback would, that
 * a still-present tag keeps the presence record fresh, that a tag with no
 * Mifare Ultralight session falls back to the absence timeout, and that a
 * new tag presentation / a playback stop cancel the poll.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MifarePresencePollUiTest {

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

    private fun viewModelOf(activity: MainActivity): LumiViewModel {
        val field = MainActivity::class.java.getDeclaredField("viewModel")
        field.isAccessible = true
        return field.get(activity) as LumiViewModel
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
     * Replaces the activity's session-establishment seam: every tag then
     * gets [reader] (or no session at all when [reader] is null).
     */
    private fun withSessionReader(activity: MainActivity, reader: PresenceReader?) {
        val field = MainActivity::class.java.getDeclaredField("establishPresenceSession")
        field.isAccessible = true
        val factory: (Tag) -> PresenceReader? = { reader }
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

    private val alwaysAbsentReader = PresenceReader { throw IOException("tag out of range") }
    private val alwaysPresentReader = PresenceReader { /* page 0 answered */ }

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
    fun proximityMode_ultralightTagLeavesRange_fastPathConfirmsRemovalAndPauses() {
        setupDb(mode = PlayMode.PROXIMITY)
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PLAYING, activeTagUid = UID_A)
        }
        val controller = startMainActivity()
        val activity = controller.get()
        withSessionReader(activity, alwaysAbsentReader)
        // The tag's re-read while presented starts the continuous-reading
        // poll; the tag then leaves range, so every read fails.
        sendTag(controller, UID_A)
        // The fast path confirms the removal in ~100 ms (a failed read, the
        // retry delay, the failed retry) and pauses through the existing
        // route - far sooner than the ~16 s absence-timeout fallback.
        val deadline = System.currentTimeMillis() + 3_000
        var intent: Intent? = null
        while (intent == null && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            intent = shadowOf(app() as Application).nextStartedService
            Thread.sleep(10)
        }
        assertNotNull(
            "the continuous read must confirm the removal and pause playback",
            intent,
        )
        assertEquals(PlayerService.ACTION_PAUSE, intent!!.action)
        // The fast path cleared the presence record: the fallback poll must
        // not issue a second pause for the same removal.
        PlayerStateHolder.update { it.copy(status = PlaybackStatus.PAUSED) }
        ShadowLooper.idleMainLooper(17_000, TimeUnit.MILLISECONDS)
        assertNull(
            "a confirmed fast-path removal must not be double-reported by the " +
                "absence-timeout fallback",
            shadowOf(app() as Application).nextStartedService,
        )
    }

    @Test
    fun proximityMode_ultralightTagStillPresent_continuousReadsRefreshPresenceRecord() {
        setupDb(mode = PlayMode.PROXIMITY)
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PLAYING, activeTagUid = UID_A)
        }
        val controller = startMainActivity()
        val activity = controller.get()
        withSessionReader(activity, alwaysPresentReader)
        sendTag(controller, UID_A) // re-read of the active tag: starts the poll
        assertTrue(
            "a Mifare Ultralight active tag must run the continuous-reading poll",
            presencePollJobOf(activity)?.isActive == true,
        )
        val seededAt = lastSeenAtOf(activity)
        // The poll reads on a real background thread every 300 ms and
        // refreshes the presence record on the main thread. The refresh is
        // stamped with the CURRENT virtual clock at the moment it is
        // posted, so advance the virtual clock (with a duration) between
        // real-time pumps until a refresh lands - a plain idle would
        // process the refresh at the same virtual instant it was posted.
        val deadline = System.currentTimeMillis() + 3_000
        while (lastSeenAtOf(activity) <= seededAt && System.currentTimeMillis() < deadline) {
            Thread.sleep(150)
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        }
        assertTrue(
            "a successful continuous read must refresh the presence record, " +
                "keeping the absence-timeout fallback quiet while the tag answers",
            lastSeenAtOf(activity) > seededAt,
        )
        assertNull("a still-present tag must keep playing", shadowOf(app() as Application).nextStartedService)
        assertEquals(PlaybackStatus.PLAYING, PlayerStateHolder.state.value.status)
        controller.pause() // tears the poll down
        assertFalse("pausing the activity must stop the continuous-reading poll",
            presencePollJobOf(activity)?.isActive == true)
    }

    @Test
    fun proximityMode_tagWithoutAnUltralightSession_fallsBackToAbsenceTimeout() {
        setupDb(mode = PlayMode.PROXIMITY)
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PLAYING, activeTagUid = UID_A)
        }
        val controller = startMainActivity()
        val activity = controller.get()
        // The synthetic tag carries no tech list, so the default
        // session-establishment finds no Mifare Ultralight: no fast-path
        // poll may run. The session check happens on a real background
        // thread, so pump in real time until the (never-started) job has
        // settled.
        sendTag(controller, UID_A)
        val deadline = System.currentTimeMillis() + 3_000
        while (presencePollJobOf(activity)!!.isActive && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
        assertFalse(
            "a tag without a Mifare Ultralight session must not run the " +
                "continuous-reading poll",
            presencePollJobOf(activity)!!.isActive,
        )
        // ...and the absence-timeout fallback must still detect the removal.
        ShadowLooper.idleMainLooper(17_000, TimeUnit.MILLISECONDS)
        val intent = nextServiceIntent(1_000)
        assertNotNull(
            "the absence-timeout fallback must still pause when a " +
                "non-Ultralight tag leaves range",
            intent,
        )
        assertEquals(PlayerService.ACTION_PAUSE, intent!!.action)
    }

    @Test
    fun proximityMode_deadSessionReadFailureIsTreatedAsStrike_notACrash() {
        setupDb(mode = PlayMode.PROXIMITY)
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PLAYING, activeTagUid = UID_A)
        }
        val controller = startMainActivity()
        val activity = controller.get()
        // A live read can fail with a non-IO runtime exception when the
        // NFC session dies mid-read (e.g. the NFC service drops while the
        // app stays in the foreground). The read seam must map that to a
        // "tag out of range" strike so the loop degrades to the two-strike
        // pause path instead of escaping the poll (and crashing the app).
        withSessionReader(activity, PresenceReader { throw RuntimeException("dead session") })
        sendTag(controller, UID_A)
        val intent = nextServiceIntent(3_000)
        assertNotNull(
            "a dead-session read failure must degrade to the two-strike pause " +
                "path instead of killing the poll",
            intent,
        )
        assertEquals(PlayerService.ACTION_PAUSE, intent!!.action)
    }

    @Test
    fun newTagPresentation_cancelsThePreviousTagsPoll() {
        setupDb(mode = PlayMode.PROXIMITY)
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PLAYING, activeTagUid = UID_A)
        }
        val controller = startMainActivity()
        val activity = controller.get()
        withSessionReader(activity, alwaysPresentReader)
        sendTag(controller, UID_A)
        val firstJob = presencePollJobOf(activity)!!
        assertTrue("the active tag's poll must be running", firstJob.isActive)
        // An unrelated tag (not even registered) is presented: its read
        // must cancel A's poll.
        sendTag(controller, UID_B)
        ShadowLooper.idleMainLooper()
        assertTrue(
            "a new tag presentation must cancel the previous tag's poll",
            firstJob.isCancelled,
        )
    }

    @Test
    fun playbackStopped_stopsTheContinuousPoll() {
        setupDb(mode = PlayMode.PROXIMITY)
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PLAYING, activeTagUid = UID_A)
        }
        val controller = startMainActivity()
        val activity = controller.get()
        withSessionReader(activity, alwaysPresentReader)
        sendTag(controller, UID_A)
        val firstJob = presencePollJobOf(activity)!!
        // The service stops on its own: the shared state settles to IDLE and
        // the activity must tear the fast-path poll down with the fallback.
        PlayerStateHolder.update { it.copy(status = PlaybackStatus.IDLE, activeTagUid = null) }
        compose.waitForIdle()
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()
        assertTrue(
            "stopping playback must cancel the continuous-reading poll",
            firstJob.isCancelled,
        )
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
