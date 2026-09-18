package com.lumi.player.ui

import android.app.Application
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import com.lumi.player.data.PlayMode
import com.lumi.player.player.PlayerService
import com.lumi.player.player.PlayerStateHolder
import com.lumi.player.player.PlayerUiState
import com.lumi.player.player.PlaybackStatus
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
import java.util.concurrent.TimeUnit

/**
 * Behavioral coverage for the always-listening NFC receive path on the main
 * page. Drives the real [MainActivity] under Robolectric with the radio on,
 * asserts foreground dispatch (the same self PendingIntent path the
 * tag-registration flow uses) is the active receive mechanism, then delivers
 * tag intents the way the system's foreground dispatch would
 * ([ActivityController.newIntent] -> [MainActivity.onNewIntent] ->
 * [MainActivity.handleNfcIntent]) to verify a registered tag read
 * starts/switches playback, overrides a user pause, and that the
 * mode-specific tag-loss semantics hold (proximity stops on absence,
 * continuous keeps playing). Also pins that a tag capture during a
 * registration session ends the session while leaving the receive path live,
 * that the receive path survives pause/resume, and that a consumed tag
 * intent is not reprocessed on a later resume.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NfcAlwaysListeningUiTest {

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
     * activity itself, arriving as an onNewIntent.
     */
    private fun sendTag(controller: ActivityController<MainActivity>, uid: String) {
        controller.newIntent(Intent(Intent.ACTION_MAIN).putExtra(NfcAdapter.EXTRA_TAG, tagWithUid(uid)))
        compose.waitForIdle()
    }

    /**
     * Idles the main looper until [cond] holds (pumping across real background
     * hops like Room queries) or [timeoutMs] elapses.
     */
    private fun idleUntil(cond: () -> Boolean, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
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

    private fun setupDb(
        mode: PlayMode = PlayMode.CONTINUOUS,
        tagA: Boolean = true,
        tagB: Boolean = false,
    ) {
        runBlocking {
            app().repository.setPlayMode(mode)
            if (tagA) {
                val tag = app().repository.addTag(UID_A, "Tag A")!!
                val entry = app().repository.addEntry(EntryType.AUDIO, "Song A", "content://a")
                app().repository.updateTagEntries(tag.id) { it + entry.id }
            }
            if (tagB) {
                val tag = app().repository.addTag(UID_B, "Tag B")!!
                val entry = app().repository.addEntry(EntryType.AUDIO, "Song B", "content://b")
                app().repository.updateTagEntries(tag.id) { it + entry.id }
            }
        }
    }

    // ---- main page is always listening ----

    @Test
    fun mainPage_foregroundDispatchIsTheActiveReceivePath_whenNotRegistering() {
        setupDb()
        startMainActivity()
        assertTrue(
            "foreground dispatch must be enabled on the idle main page",
            NfcTestState.foregroundDispatchEnabled,
        )
    }

    // ---- reads on the main page drive playback ----

    @Test
    fun mainPage_readOfRegisteredTag_startsPlayback() {
        setupDb()
        val controller = startMainActivity()
        sendTag(controller, UID_A)
        val intent = nextServiceIntent()
        assertNotNull("a tag read must start the player service", intent)
        assertEquals(PlayerService.ACTION_START, intent!!.action)
        assertEquals(UID_A, intent.getStringExtra(PlayerService.EXTRA_TAG_UID))
        @Suppress("DEPRECATION")
        val entries = intent.getParcelableArrayListExtra<com.lumi.player.player.EntrySpec>(
            PlayerService.EXTRA_ENTRIES,
        )
        assertTrue("the tag's linked entries must be queued", (entries?.size ?: 0) > 0)
    }

    @Test
    fun mainPage_readOfDifferentRegisteredTag_switchesPlayback() {
        setupDb(tagA = true, tagB = true)
        val controller = startMainActivity()
        sendTag(controller, UID_A)
        val first = nextServiceIntent()
        assertEquals(PlayerService.ACTION_START, first!!.action)
        assertEquals(UID_A, first.getStringExtra(PlayerService.EXTRA_TAG_UID))
        sendTag(controller, UID_B)
        val second = nextServiceIntent()
        assertNotNull("a different registered tag must switch playback", second)
        assertEquals(PlayerService.ACTION_START, second!!.action)
        assertEquals(UID_B, second.getStringExtra(PlayerService.EXTRA_TAG_UID))
    }

    @Test
    fun mainPage_rereadOfPausedActiveTag_resumesInsteadOfRestarting() {
        setupDb()
        // The tag's list is already playing but the user paused it.
        PlayerStateHolder.update { PlayerUiState().copy(status = PlaybackStatus.PAUSED, activeTagUid = UID_A) }
        val controller = startMainActivity()
        sendTag(controller, UID_A)
        val intent = nextServiceIntent()
        assertNotNull("a re-read of the paused active tag must command the service", intent)
        assertEquals(
            "reading a paused tag must override the pause with a resume, not restart",
            PlayerService.ACTION_RESUME,
            intent!!.action,
        )
    }

    // ---- tag-loss semantics per mode ----

    @Test
    fun proximityMode_tagRemoved_stopsPlayback() {
        setupDb(mode = PlayMode.PROXIMITY)
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PLAYING, activeTagUid = UID_A)
        }
        val controller = startMainActivity()
        sendTag(controller, UID_A) // in-range re-read of the active tag is
        // ignored by the policy (no restart) but refreshes the presence
        // record; the tag is still physically in range.
        // The tag leaves range: reports stop. The presence watcher must
        // derive tag loss and stop playback.
        ShadowLooper.idleMainLooper(PRESENCE_TIMEOUT_MS_OVERSHOOT_MS, TimeUnit.MILLISECONDS)
        val intent = nextServiceIntent(1_000)
        assertNotNull(
            "proximity mode must stop playback when the active tag leaves range",
            intent,
        )
        assertEquals(PlayerService.ACTION_STOP, intent!!.action)
        // Robolectric tracks the foreground-service start but does not run
        // the service body; drive the stop command the way the system would
        // and verify the shared state settles to idle.
        val service = Robolectric.buildService(
            PlayerService::class.java,
            Intent().setAction(PlayerService.ACTION_STOP),
        ).create()
        service.startCommand(0, 1)
        assertEquals(PlaybackStatus.IDLE, PlayerStateHolder.state.value.status)
        service.destroy()
    }

    @Test
    fun proximityMode_unrelatedTagRead_doesNotMaskActiveTagRemoval() {
        // Presence record regression: only the ACTIVE registered tag's reads
        // refresh the presence record. Tag A (registered) is playing in
        // proximity mode; the user waves unregistered tag B in range, then
        // both tags leave. Stop-on-removal must still fire on A's absence:
        // if B's read clobbered the record, the watcher would report B lost
        // (ignored by the policy) and A's removal would go undetected, so no
        // stop command would ever be issued.
        setupDb(mode = PlayMode.PROXIMITY) // tag A only; UID_B is unregistered
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PLAYING, activeTagUid = UID_A)
        }
        val controller = startMainActivity()
        sendTag(controller, UID_A) // seeds the presence record (A is active)
        sendTag(controller, UID_B) // an unrelated read: must not touch the record
        // Both tags now leave range: no further reports arrive.
        ShadowLooper.idleMainLooper(PRESENCE_TIMEOUT_MS_OVERSHOOT_MS, TimeUnit.MILLISECONDS)
        val intent = nextServiceIntent(1_000)
        assertNotNull(
            "an unrelated tag read must not mask the active tag's removal: " +
                "proximity mode must stop playback after the active tag leaves range",
            intent,
        )
        assertEquals(PlayerService.ACTION_STOP, intent!!.action)
    }

    @Test
    fun continuousMode_tagRemoved_keepsPlaying() {
        setupDb(mode = PlayMode.CONTINUOUS)
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PLAYING, activeTagUid = UID_A)
        }
        val controller = startMainActivity()
        sendTag(controller, UID_A) // in-range re-read of the active tag:
        // presence refreshed, no restart command expected
        // The tag leaves range in continuous mode: nothing may stop playback.
        ShadowLooper.idleMainLooper(PRESENCE_TIMEOUT_MS_OVERSHOOT_MS, TimeUnit.MILLISECONDS)
        assertNull(
            "continuous mode must keep playing after the tag leaves range (no stop command)",
            shadowOf(app() as Application).nextStartedService,
        )
        assertEquals(PlaybackStatus.PLAYING, PlayerStateHolder.state.value.status)
    }

    // ---- registration flow: capture ends the session, receive path stays live ----

    @Test
    fun registrationSession_capturesPresentedTag_andStaysListening() {
        setupDb()
        val controller = startMainActivity()
        val viewModel = viewModelOf(controller.get())
        assertTrue("starts with foreground dispatch live", NfcTestState.foregroundDispatchEnabled)

        viewModel.startTagRegistration()
        assertTrue("dispatch must stay live while registering", NfcTestState.foregroundDispatchEnabled)

        // A tag is presented while registering; it arrives via the
        // foreground-dispatch intent exactly like a main-page read.
        sendTag(controller, UID_A)
        idleUntil({ !viewModel.registering.value })
        compose.waitForIdle()
        assertFalse("a captured tag must end the registration", viewModel.registering.value)
        assertEquals(UID_A, viewModel.capturedUid.value)
        assertTrue(
            "the receive path must stay live after the registration session",
            NfcTestState.foregroundDispatchEnabled,
        )
    }

    @Test
    fun nfcReceiveIsDisabledOnPause_andReenabledOnResume() {
        setupDb()
        val controller = startMainActivity()
        assertTrue("foreground dispatch is live while the main page is resumed", NfcTestState.foregroundDispatchEnabled)
        controller.pause()
        assertFalse("the receive path must be disabled while paused", NfcTestState.foregroundDispatchEnabled)
        controller.resume()
        compose.waitForIdle()
        assertTrue("the receive path must be live again after resume", NfcTestState.foregroundDispatchEnabled)
    }

    // ---- intent handling ----

    @Test
    fun mainPage_coldStartTagIntent_startsPlayback() {
        // A tag tapped while the app is closed arrives with the launch
        // intent (TECH_DISCOVERED dispatch) and is handled in onResume via
        // handleNfcIntent - the intent path must start playback too.
        setupDb()
        val tagIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .putExtra(NfcAdapter.EXTRA_TAG, tagWithUid(UID_A))
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        controller.get().setIntent(tagIntent)
        controller.resume()
        compose.waitForIdle()
        val intent = nextServiceIntent()
        assertNotNull("a cold-start tag intent must start the player service", intent)
        assertEquals(PlayerService.ACTION_START, intent!!.action)
        assertEquals(UID_A, intent.getStringExtra(PlayerService.EXTRA_TAG_UID))
        controller.destroy()
    }

    @Test
    fun consumedTagIntent_isNotReprocessedOnLaterResume() {
        // A tag intent must be consumed when handled: backgrounding and
        // reopening the app must not re-deliver the stale tag (which would
        // resume a user pause that NFC reading alone should override).
        setupDb()
        val controller = startMainActivity()
        sendTag(controller, UID_A)
        val first = nextServiceIntent()
        assertEquals(PlayerService.ACTION_START, first!!.action)
        controller.pause()
        controller.resume()
        compose.waitForIdle()
        ShadowLooper.idleMainLooper()
        assertNull(
            "a consumed tag intent must not start playback again on resume",
            shadowOf(app() as Application).nextStartedService,
        )
    }

    companion object {
        const val UID_A = "04A1B2C3"
        const val UID_B = "04DEADBEEF"

        // MainActivity PRESENCE_TIMEOUT_MS (2s) plus a margin for the watcher tick.
        private const val PRESENCE_TIMEOUT_MS_OVERSHOOT_MS = 3_000L
    }
}
