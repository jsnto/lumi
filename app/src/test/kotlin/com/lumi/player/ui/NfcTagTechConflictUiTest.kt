package com.lumi.player.ui

import android.app.Application
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcA
import androidx.test.core.app.ApplicationProvider
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
import java.util.concurrent.atomic.AtomicInteger
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
 * Coverage for the tag-tech single-connection-slot conflict that kept the
 * proximity fast path from ever coming up on the Samsung A52 (device-proven
 * via logcat): a [Tag] can hold at most ONE connected TagTechnology, and
 * the framework's connect() succeeds at the binder level but then refuses
 * to take the tag's connection slot when it is already occupied
 * (IOException "Only one TagTechnology can be connected at a time"). On the
 * device the slot was still held on delivery - by the NFC stack's own
 * dispatch/identification connection, by a stale app session on the same
 * Tag instance, or by the app's own previous poll torn down on the
 * activity restart the tag tap triggered - so BOTH the Mifare connect and
 * the generic connect failed on every delivery, the 15 s absence-timeout
 * fallback owned every tag, a tag HELD in range stopped after ~15 s
 * (violating "keeps playing while held"), and a removed tag took ~15 s to
 * pause.
 *
 * The fix (see [com.lumi.player.ui.MainActivity]): (1) every session
 * establisher RELEASES the tag's slot (an unconditional close() on a tech
 * instance of the tag - the framework's close() has no ownership check and
 * resets the tag-level connection state, bytecode-verified) before its own
 * connect(); (2) a re-configuration while a poll for the still-active tag
 * is running keeps that poll instead of tearing it down and racing the
 * cancelled loop's teardown (whose finally closes the slot) with the fresh
 * establish's connect(); (3) a re-delivery of the tag already being polled
 * is a no-op (same-uid skip).
 *
 * Robolectric sharp edge (why the tests are shaped the way they are): the
 * API-35 jar's PUBLIC [android.nfc.tech.BasicTagTechnology.connect] /
 * close / reconnect are instrumentation no-ops - their instrumented
 * ("$$robo$$") bodies never execute through the public entry - while the
 * slot field, the slot primitives (setConnectedTechnology /
 * setTechnologyDisconnected), checkConnected, getTagService and transceive
 * DO run. The tests below therefore drive the REAL AOSP method bodies
 * (the exact bytecode the device executes) via reflection for the
 * mechanism tests, and pin the app-level race guard through the activity's
 * session seams with a fake (the Robolectric-runnable shape) for the
 * end-to-end behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NfcTagTechConflictUiTest {

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

    /**
     * A controllable INfcTag binder. connect/reconnect succeed while the
     * tag is in the field (status 0 = success); transceive answers with a
     * success result carrying 16 bytes. Tag.getTagService() re-checks
     * isTagUpToDate on the binder before every operation.
     */
    private class TagBinder {
        private val resultCtor = Class.forName("android.nfc.TransceiveResult")
            .getDeclaredConstructor(Int::class.javaPrimitiveType!!, ByteArray::class.java)
            .apply { isAccessible = true }

        private val nfcTagCls = Class.forName("android.nfc.INfcTag")

        private val handler = java.lang.reflect.InvocationHandler { _, method, _ ->
            when (method.name) {
                "connect", "reconnect" -> 0
                "isTagUpToDate" -> true
                "isPresent" -> true
                "getMaxTransceiveLength" -> 255
                // 0 = RESULT_SUCCESS: getResponseOrThrow() returns the data.
                "transceive" -> resultCtor.newInstance(0, ByteArray(16))
                else -> null
            }
        }

        val proxy: Any = java.lang.reflect.Proxy.newProxyInstance(
            nfcTagCls.classLoader,
            arrayOf(nfcTagCls),
            handler,
        )
    }

    /**
     * Builds a synthetic [Tag] the way the NFC stack's foreground dispatch
     * delivers one, carrying a REAL tech list and (optionally) a
     * pre-occupied connection slot:
     * - [mifare] = true: [NfcA, MifareUltralight] (tech codes 1, 9) - the
     *   on-device tag's shape (uid 04A18E52D82A81: SAK 0, a 10-byte
     *   Ultralight UID);
     * - [mifare] = false: [NfcA, IsoDep] (tech codes 1, 3) - an NfcA tag
     *   such as the NTAG215.
     * [occupiedBy] pre-occupies the tag's single connection slot with the
     * given tech code - the state the device was in when the tag arrived
     * (the NFC stack's own connection, or a stale session on the same Tag
     * instance).
     */
    private fun syntheticTag(
        uid: String,
        mifare: Boolean,
        occupiedBy: Int,
        binder: TagBinder,
    ): Tag {
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
        val tag = ctor.newInstance(
            ByteArray(uid.length / 2) {
                uid.substring(it * 2, it * 2 + 2).toInt(16).toByte()
            },
            if (mifare) intArrayOf(TECH_NFCA, TECH_MIFARE_UL) else intArrayOf(TECH_NFCA, TECH_IODEP),
            arrayOf(
                android.os.Bundle().apply { putShort("sak", 0) },
                android.os.Bundle(),
            ),
            0,
            0L,
            binder.proxy,
        ) as Tag
        if (occupiedBy >= 0) {
            // The real framework (loaded by Robolectric at runtime) exposes
            // the tag's single connection slot; the stub SDK does not, so
            // occupy it reflectively.
            tag.javaClass.getDeclaredMethod(
                "setConnectedTechnology",
                Int::class.javaPrimitiveType!!,
            ).invoke(tag, occupiedBy)
        }
        return tag
    }

    private fun slotOf(tag: Tag): Int =
        tag.javaClass.getDeclaredMethod("getConnectedTechnology").invoke(tag) as Int

    /**
     * The REAL AOSP method body of a [android.nfc.tech.BasicTagTechnology]
     * method, looked up by suffix: the Robolectric jar runs the original
     * bytecode under an instrumented ("$$robo$$") private copy, and that
     * copy DOES execute when invoked directly even though the public entry
     * is a no-op. This is the exact bytecode the device runs.
     */
    private fun realBody(nameSuffix: String): java.lang.reflect.Method {
        val btt = Class.forName("android.nfc.tech.BasicTagTechnology")
        return btt.declaredMethods.first {
            java.lang.reflect.Modifier.isPrivate(it.modifiers) &&
                it.parameterCount == 0 &&
                it.returnType == Void.TYPE &&
                it.name.endsWith("\$$nameSuffix")
        }.apply { isAccessible = true }
    }

    private fun presencePollJobOf(activity: MainActivity): kotlinx.coroutines.Job? {
        val field = MainActivity::class.java.getDeclaredField("presencePollJob")
        field.isAccessible = true
        return field.get(activity) as kotlinx.coroutines.Job?
    }

    /**
     * Replaces the activity's generic-session seam: every tag then gets
     * [session], with each establish call counted in [establishes].
     */
    private fun withCountingGenericSession(
        activity: MainActivity,
        session: FakeGenericSession,
        establishes: AtomicInteger,
    ) {
        val field = MainActivity::class.java.getDeclaredField("establishGenericPresenceSession")
        field.isAccessible = true
        val factory: (Tag) -> GenericPresenceSession? = {
            establishes.incrementAndGet()
            session
        }
        field.set(activity, factory)
    }

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

    /**
     * Delivers the tag to the resumed activity the way the NFC stack's
     * foreground dispatch would: an intent carrying EXTRA_TAG to the
     * activity itself, arriving as onNewIntent.
     */
    private fun sendTag(controller: ActivityController<MainActivity>, uid: String) {
        val tag = syntheticTag(uid, mifare = false, occupiedBy = -1, TagBinder())
        controller.newIntent(Intent(Intent.ACTION_MAIN).putExtra(NfcAdapter.EXTRA_TAG, tag))
        compose.waitForIdle()
    }

    /**
     * Waits until the fast-path establisher has been called [count] times.
     * The establish happens on a real IO dispatcher inside the poll job, so
     * the call lands after the delivery's main-thread handling.
     */
    private fun waitForEstablishes(establishes: AtomicInteger, count: Int, timeoutMs: Long = 3_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (establishes.get() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            ShadowLooper.idleMainLooper()
        }
    }

    /** Consumes the next started-service intent matching [until], pumping until it arrives. */
    private fun nextServiceIntent(
        until: (Intent?) -> Boolean,
        timeoutMs: Long = 5_000,
    ): Intent? {
        var intent: Intent? = null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!until(intent) && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            intent = shadowOf(app() as Application).nextStartedService
            Thread.sleep(10)
        }
        return intent
    }

    private fun setupDb(mode: PlayMode) {
        runBlocking {
            app().repository.setPlayMode(mode)
            val tag = app().repository.addTag(UID_ON_DEVICE, "Tag 1")!!
            val entry = app().repository.addEntry(EntryType.AUDIO, "Song", "content://song")
            app().repository.updateTagEntries(tag.id) { it + entry.id }
        }
    }

    // ---- mechanism: the exact device conflict, on the exact device bytecode ----

    @Test
    fun slotOccupiedByAnotherConnection_connectThrowsTheDeviceConflict() {
        // The tag's list is irrelevant here: this is the framework-level
        // conflict the app hit on every delivery.
        // Mifare-shaped tag (the on-device "1" tag) whose slot the stack
        // left held by a DIFFERENT tech (IsoDep): the Mifare connect must
        // refuse with the exact device exception.
        val mifareTag = syntheticTag(
            UID_ON_DEVICE,
            mifare = true,
            occupiedBy = TECH_IODEP,
            TagBinder(),
        )
        val mifare = MifareUltralight.get(mifareTag)
        assertNotNull("the tag must expose a Mifare Ultralight tech", mifare)
        try {
            realBody("connect").invoke(mifare)
            throw AssertionError(
                "an occupied slot must refuse the connect (device: " +
                    "IOException 'Only one TagTechnology can be connected at a time.')"
            )
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val ex = e.targetException
            assertTrue(
                "the connect must throw the single-slot conflict, got: $ex",
                ex is IOException && ex.message == "Only one TagTechnology can be connected at a time.",
            )
            assertEquals(
                "the refused connect must not take the slot",
                TECH_IODEP,
                slotOf(mifareTag),
            )
        }
        // Same for the NfcA tech (the NTAG215 shape): the device log showed
        // BOTH connects refused on every delivery.
        val nfcATag = syntheticTag(
            UID_ON_DEVICE,
            mifare = false,
            occupiedBy = TECH_MIFARE_UL,
            TagBinder(),
        )
        val nfca = NfcA.get(nfcATag)
        assertNotNull("the tag must expose an NfcA tech", nfca)
        try {
            realBody("connect").invoke(nfca)
            throw AssertionError("an occupied slot must refuse the NfcA connect")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertTrue(
                "the NfcA connect must hit the same single-slot conflict",
                e.targetException is IOException &&
                    e.targetException.message ==
                    "Only one TagTechnology can be connected at a time.",
            )
        }
    }

    @Test
    fun closeOnAnyTechInstanceReleasesTheSlot_thenConnectAcquiresIt() {
        // The on-device delivery state: the tag's single slot is held by a
        // stale connection (an IsoDep session the NFC stack left behind).
        // The app is about to run its NfcA fast path. The fix's
        // release-before-connect (MainActivity.releaseExistingTagConnection)
        // is an UNCONDITIONAL close() on a tech instance of the tag - the
        // framework's close() has no ownership check: it resets the
        // tag-level connection state (bytecode-verified) and re-activates
        // the RF session, whoever held the slot.
        val tag = syntheticTag(
            UID_ON_DEVICE,
            mifare = false,
            occupiedBy = TECH_IODEP,
            TagBinder(),
        )
        val nfca = NfcA.get(tag)!!
        // The release: close() on the tech the APP is about to connect
        // (NfcA), even though the slot is held by ANOTHER tech (IsoDep).
        realBody("close").invoke(nfca)
        assertEquals(
            "close() on any tech instance of the tag must release the " +
                "tag-level slot (the fix's release step)",
            -1,
            slotOf(tag),
        )
        // The app's own connect then takes the slot, and the session reads.
        realBody("connect").invoke(nfca)
        assertEquals(
            "the app's connect must acquire the released slot",
            TECH_NFCA,
            slotOf(tag),
        )
        val page = nfca.transceive(byteArrayOf(0x93.toByte(), 0x20.toByte()))
        assertEquals(
            "the freshly connected session must answer reads",
            16,
            page.size,
        )
    }

    @Test
    fun slotPrimitives_refuseASecondHolder_andReleaseClears() {
        // The guard the device's connect() turns into the single-slot
        // IOException (bytecode: setConnectedTechnology returns false when
        // the slot is not -1). releaseExistingTagConnection's release
        // relies on setTechnologyDisconnected (what the framework's
        // close() calls) clearing it.
        val tag = syntheticTag(UID_ON_DEVICE, mifare = false, occupiedBy = -1, TagBinder())
        val set = tag.javaClass.getDeclaredMethod(
            "setConnectedTechnology",
            Int::class.javaPrimitiveType!!,
        )
        assertEquals("a fresh tag's slot is unconnected", -1, slotOf(tag))
        assertTrue("the first holder takes the slot", set.invoke(tag, TECH_MIFARE_UL) as Boolean)
        assertEquals(TECH_MIFARE_UL, slotOf(tag))
        assertFalse(
            "a second holder must be refused while the slot is held",
            set.invoke(tag, TECH_NFCA) as Boolean,
        )
        assertEquals("the refused holder must not displace the first", TECH_MIFARE_UL, slotOf(tag))
        tag.javaClass.getDeclaredMethod("setTechnologyDisconnected").invoke(tag)
        assertEquals(
            "the release (what the framework's close() calls) must clear the slot",
            -1,
            slotOf(tag),
        )
    }

    // ---- end to end: the captain's observed behavior, with the on-device
    // ---- trigger (a tag tap that restarts the activity mid-hold) ----

    @Test
    fun proximityMode_tagTapRestartsActivity_heldTagKeepsPlaying_pastAbsenceWindow_thenRemovalPauses() {
        setupDb(PlayMode.PROXIMITY)
        // The tag's list is already playing (the captain's hold scenario).
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PLAYING, activeTagUid = UID_ON_DEVICE)
        }
        val controller = startMainActivity()
        val activity = controller.get()
        val session = FakeGenericSession()
        val establishes = AtomicInteger(0)
        withCountingGenericSession(activity, session, establishes)
        // The activating presentation: the re-read of the active tag starts
        // the generic fast path (the synthetic tag carries no Mifare tech,
        // so the Mifare fast path cannot run - the NTAG215 shape).
        sendTag(controller, UID_ON_DEVICE)
        waitForEstablishes(establishes, 1)
        assertTrue(
            "the active tag's presence poll must be running",
            presencePollJobOf(activity)?.isActive == true,
        )
        assertEquals("the fast path must be established exactly once", 1, establishes.get())
        // The on-device trigger: the app backgrounds (onPause tears the
        // poll down - the correct teardown), then tapping the tag in the
        // background makes the NFC stack RESTART the activity (TAG
        // DISCOVERED): onNewIntent + onResume, device-proven. The resume
        // re-configuration must re-establish the fast path for the carried
        // active tag, and the onNewIntent half of the same race (a fresh
        // delivery of the tag whose poll just came up) must be a NO-OP -
        // the same-uid skip - instead of tearing the fresh poll down and
        // racing its teardown (whose finally closes the tag's single
        // connection slot) with yet another establish's connect().
        controller.pause()
        controller.stop()
        controller.resume()
        // The onNewIntent half of the restart race: a fresh delivery of
        // the tag whose poll is already running.
        sendTag(controller, UID_ON_DEVICE)
        waitForEstablishes(establishes, 2)
        ShadowLooper.idleMainLooper()
        // Give the (skipped) re-delivery a moment to have run its
        // same-uid check: it must not schedule a third establish.
        Thread.sleep(300)
        ShadowLooper.idleMainLooper()
        assertEquals(
            "the resume re-establishes the fast path once, and the " +
                "restart's re-delivery of the tag whose poll is already " +
                "running must be a no-op (pre-fix: every (re-)delivery " +
                "tore the running poll down and re-established it, racing " +
                "the stale loop's slot release with the fresh connect - " +
                "the device's 'Only one TagTechnology' kill)",
            2,
            establishes.get(),
        )
        assertTrue(
            "the fast path must be running after the restart",
            presencePollJobOf(activity)?.isActive == true,
        )
        // Hold the tag in range past the 15 s absence window: the fast
        // path refreshes the presence record every probe, so the fallback
        // stays quiet and playback continues.
        val deadline = System.currentTimeMillis() + 4_500
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
            ShadowLooper.idleMainLooper(1_000, TimeUnit.MILLISECONDS)
        }
        assertNull(
            "a tag HELD in range must keep playing past the 15 s absence " +
                "window (pre-fix on the device: the restart killed the fast " +
                "path and the fallback false-paused the present tag after " +
                "~15 s)",
            shadowOf(app() as Application).nextStartedService,
        )
        assertEquals(PlaybackStatus.PLAYING, PlayerStateHolder.state.value.status)
        // Now lift the tag out of the field: the probes stop answering and
        // no fresh session can read it - two strikes confirm the removal
        // through the tag-lost route (well inside the 15 s fallback).
        session.probeThrows = true
        val pause = nextServiceIntent({ it?.action == PlayerService.ACTION_PAUSE }, 3_000)
        assertNotNull(
            "a confirmed fast-path removal must pause playback through the " +
                "tag-lost route within the debounce window (not the 15 s " +
                "fallback)",
            pause,
        )
        assertEquals(PlayerService.ACTION_PAUSE, pause!!.action)
        // The fast path cleared the presence record: the fallback poll must
        // not issue a second pause for the same removal.
        PlayerStateHolder.update { it.copy(status = PlaybackStatus.PAUSED) }
        ShadowLooper.idleMainLooper(17_000, TimeUnit.MILLISECONDS)
        assertNull(
            "a confirmed fast-path removal must not be double-reported by " +
                "the absence-timeout fallback",
            shadowOf(app() as Application).nextStartedService,
        )
        assertTrue(
            "the poll must end after the removal",
            presencePollJobOf(activity) == null ||
                presencePollJobOf(activity)!!.isCompleted,
        )
        assertTrue(
            "the session must be closed when the poll ends",
            session.closeCount >= 1,
        )
    }

    /**
     * Drives a re-configuration of the NFC stack while the activity stays
     * resumed the way a scan toggle does (LumiViewModel.startTagScan flips
     * the scanActive flow, whose collector calls reconfigureNfc).
     */
    @Test
    fun proximityMode_nfcReconfiguredWhilePollRunning_keepsTheRunningPoll() {
        setupDb(PlayMode.PROXIMITY)
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PLAYING, activeTagUid = UID_ON_DEVICE)
        }
        val controller = startMainActivity()
        val activity = controller.get()
        val session = FakeGenericSession()
        val establishes = AtomicInteger(0)
        withCountingGenericSession(activity, session, establishes)
        sendTag(controller, UID_ON_DEVICE)
        waitForEstablishes(establishes, 1)
        val firstJob = presencePollJobOf(activity)!!
        assertTrue("the active tag's presence poll must be running", firstJob.isActive)
        assertEquals("the fast path must be established exactly once", 1, establishes.get())
        val viewModelField = MainActivity::class.java.getDeclaredField("viewModel")
        viewModelField.isAccessible = true
        val viewModel = viewModelField.get(activity) as com.lumi.player.ui.LumiViewModel
        // The activity's NFC re-configuration fires while it stays resumed
        // (a scan toggle - startTagScan flips scanActive and the collector
        // reconfigures the foreground dispatch): the running poll for the
        // still-active tag must be KEPT, not torn down and re-established
        // (the re-establish races the cancelled loop's teardown - whose
        // finally closes the tag's single connection slot - with the fresh
        // establish's connect()).
        val startScan = LumiViewModel::class.java.getDeclaredMethod("startTagScan")
        startScan.isAccessible = true
        startScan.invoke(viewModel)
        ShadowLooper.idleMainLooper()
        assertEquals(
            "an NFC re-configuration while the tag's poll is running must " +
                "keep the running poll (pre-fix: it tore the poll down and " +
                "re-established it, racing the stale loop's slot release " +
                "with the fresh connect)",
            1,
            establishes.get(),
        )
        assertTrue(
            "the original poll job must still be running",
            firstJob.isActive,
        )
    }

    companion object {
        // Tech codes as stored in the Tag tech list: 1 = NfcA, 3 = IsoDep,
        // 9 = Mifare Ultralight (bytecode-verified: NfcA.get checks
        // hasTech(1), IsoDep.get checks hasTech(3), MifareUltralight uses
        // 9).
        private const val TECH_NFCA = 1
        private const val TECH_IODEP = 3
        private const val TECH_MIFARE_UL = 9

        // The on-device tag's real UID (the "1" tag in the round-r1 logcat:
        // 10-byte Ultralight UID, Mifare Ultralight tech list).
        const val UID_ON_DEVICE = "04A18E52D82A81"
    }
}
