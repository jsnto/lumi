package com.lumi.player.ui

import android.app.Application
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import com.lumi.player.data.PlayMode
import com.lumi.player.data.TagPositionEntity
import com.lumi.player.data.fingerprintOf
import com.lumi.player.player.EntrySpec
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
 * mode-specific tag-loss semantics hold (proximity pauses on absence and a
 * re-read of the removed tag resumes; continuous keeps playing). Also pins that the
 * ACTION_START start-up transient (the service sets the holder's active tag while
 * the status is still IDLE, before ExoPlayer is ready) is not mistaken for a stop,
 * that a tag capture during a
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
                app().repository.linkEntryToTag(tag.id, entry.id)
            }
            if (tagB) {
                val tag = app().repository.addTag(UID_B, "Tag B")!!
                val entry = app().repository.addEntry(EntryType.AUDIO, "Song B", "content://b")
                app().repository.linkEntryToTag(tag.id, entry.id)
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

    // ---- remembered-position restore wiring ----

    /** The tag's specs exactly as [LumiViewModel] builds them for the service. */
    private fun specsForUid(uid: String): List<EntrySpec> = runBlocking {
        val tag = app().repository.tagByUid(uid)!!
        app().repository.entriesForTag(tag.id).mapNotNull { row ->
            row.entry?.let { EntrySpec(type = it.type, label = it.label, payload = it.payload) }
        }
    }

    private fun rememberPosition(uid: String, fingerprint: String, ms: Long = 42_000L) = runBlocking {
        app().positionStore.remember(
            TagPositionEntity(
                tagUid = uid,
                positionMs = ms,
                entryIndex = 0,
                queueFingerprint = fingerprint,
                lastPlayedAt = System.currentTimeMillis(),
            ),
        )
    }

    @Test
    fun mainPage_tagWithSavedPosition_startsServiceAtTheRememberedOffset() {
        setupDb()
        rememberPosition(UID_A, fingerprintOf(specsForUid(UID_A)))
        val controller = startMainActivity()
        sendTag(controller, UID_A)
        val intent = nextServiceIntent()
        assertNotNull("a tag read must start the player service", intent)
        assertEquals(PlayerService.ACTION_START, intent!!.action)
        assertEquals(
            "a position saved against the same list must be passed as the start entry",
            0,
            intent.getIntExtra(PlayerService.EXTRA_START_ENTRY_INDEX, -1),
        )
        assertEquals(
            "the remembered offset must be passed to the service so it resumes there",
            42_000L,
            intent.getLongExtra(PlayerService.EXTRA_START_POSITION_MS, -1L),
        )
    }

    @Test
    fun mainPage_tagWithSavedPositionForAChangedList_startsFresh() {
        // Adversarial: the position row exists but was saved against a
        // different linked list (a parent swapped the file) - the service
        // must be told to start fresh, not seek into the wrong place.
        setupDb()
        rememberPosition(UID_A, fingerprint = "stale-fingerprint")
        val controller = startMainActivity()
        sendTag(controller, UID_A)
        val intent = nextServiceIntent()
        assertNotNull("a tag read must start the player service", intent)
        assertEquals(PlayerService.ACTION_START, intent!!.action)
        assertFalse(
            "a saved position for a changed list must not resume; start fresh",
            intent.hasExtra(PlayerService.EXTRA_START_POSITION_MS),
        )
    }

    // ---- tag-loss semantics per mode ----

    @Test
    fun proximityMode_tagRemoved_pausesPlaybackAndRereadResumes() {
        setupDb(mode = PlayMode.PROXIMITY)
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PLAYING, activeTagUid = UID_A)
        }
        val controller = startMainActivity()
        sendTag(controller, UID_A) // in-range re-read of the active tag is
        // ignored by the policy (no restart) but refreshes the presence
        // record; the tag is still physically in range.
        // The tag leaves range: reports stop. The 1-second presence poll
        // must derive tag loss once the absence outlives the grace period
        // and PAUSE playback - the session (and the active tag) is kept,
        // not torn down.
        ShadowLooper.idleMainLooper(PRESENCE_TIMEOUT_MS_OVERSHOOT_MS, TimeUnit.MILLISECONDS)
        val intent = nextServiceIntent(1_000)
        assertNotNull(
            "proximity mode must pause playback when the active tag leaves range",
            intent,
        )
        assertEquals(PlayerService.ACTION_PAUSE, intent!!.action)
        // Robolectric tracks the service command but does not run the
        // service body; settle the shared state to what the paused service
        // would report.
        PlayerStateHolder.update { it.copy(status = PlaybackStatus.PAUSED) }
        // Bringing the tag back must RESUME the paused list (reading a
        // paused active tag overrides the pause), not restart it.
        sendTag(controller, UID_A)
        val resume = nextServiceIntent(1_000)
        assertNotNull("a re-read of the removed active tag must resume playback", resume)
        assertEquals(PlayerService.ACTION_RESUME, resume!!.action)
    }

    @Test
    fun proximityMode_customRereadWait_usesTheConfiguredWindow() {
        setupDb(mode = PlayMode.PROXIMITY)
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PLAYING, activeTagUid = UID_A)
        }
        val controller = startMainActivity()
        val viewModel = viewModelOf(controller.get())
        // A 7-second wait replaces the 15-second default (the
        // `nfc_reread_seconds` setting): the Room write is async, so pump the
        // main looper until the view model's flow settles on it.
        viewModel.setNfcRereadSeconds(7L)
        idleUntil({ viewModel.nfcRereadSeconds.value == 7L })
        sendTag(controller, UID_A)
        // Six seconds of absence is under the configured 7-second window:
        // no pause yet.
        ShadowLooper.idleMainLooper(6_000, TimeUnit.MILLISECONDS)
        assertNull(
            "an absence under the configured re-read wait must not pause",
            shadowOf(app() as Application).nextStartedService,
        )
        // Crossing the configured window (the 1-second poll ticks at 7s
        // and 8s after the seed) must pause.
        ShadowLooper.idleMainLooper(3_000, TimeUnit.MILLISECONDS)
        val pause = nextServiceIntent(1_000)
        assertNotNull(
            "an absence past the configured re-read wait must pause",
            pause,
        )
        assertEquals(PlayerService.ACTION_PAUSE, pause!!.action)
    }

    @Test
    fun proximityMode_freshTagPresented_thenRemoved_thenBack_pausesAndResumes() {
        // The bug this pins: foreground dispatch may deliver a stationary
        // tag only once, and the activating read used to be the only one
        // that never seeded the presence record, so the poll had nothing to
        // time out and proximity mode never paused.
        setupDb(mode = PlayMode.PROXIMITY)
        val controller = startMainActivity()
        // Nothing is playing yet; the tag's first delivery starts playback
        // and must seed the presence record.
        sendTag(controller, UID_A)
        val start = nextServiceIntent()
        assertNotNull("a fresh tag read must start the player service", start)
        assertEquals(PlayerService.ACTION_START, start!!.action)
        // The tag leaves range after its single report: no further reports
        // arrive. The presence poll must derive tag loss and pause.
        ShadowLooper.idleMainLooper(PRESENCE_TIMEOUT_MS_OVERSHOOT_MS, TimeUnit.MILLISECONDS)
        val pause = nextServiceIntent(1_000)
        assertNotNull(
            "proximity mode must pause when the only reported tag leaves range",
            pause,
        )
        assertEquals(PlayerService.ACTION_PAUSE, pause!!.action)
        // Settle the state the way the paused service would, then bring the
        // tag back: the list is still the active tag's, so a fresh delivery
        // must RESUME the paused list - not restart it.
        PlayerStateHolder.update {
            it.copy(status = PlaybackStatus.PAUSED, activeTagUid = UID_A)
        }
        sendTag(controller, UID_A)
        val resume = nextServiceIntent(1_000)
        assertNotNull("bringing the tag back must resume playback", resume)
        assertEquals(PlayerService.ACTION_RESUME, resume!!.action)
    }

    @Test
    fun proximityMode_serviceStartTransientIdle_disownsNothing_removalPauses_rereadResumes() {
        // The on-device bug (2026-09-21, tag "hotel"): while the service
        // starts an ACTION_START it sets the holder's active tag while the
        // status is still IDLE (ExoPlayer is not ready yet). The IDLE
        // collector used to treat that transient as a stop: it cleared the
        // policy's active tag and tore the presence poll down mid-hold.
        // Result: the first removal paused nothing (the policy no longer
        // owned the tag, so onTagLost ignored the loss) and the re-hold
        // RESTARTED the queue from the top (the read took the StartPlayback
        // branch instead of the early-return / Resume).
        setupDb(mode = PlayMode.PROXIMITY)
        val controller = startMainActivity()
        sendTag(controller, UID_A)
        val start = nextServiceIntent()
        assertNotNull("a fresh tag read must start the player service", start)
        assertEquals(PlayerService.ACTION_START, start!!.action)
        // The service's startQueue sets the active tag on the holder while
        // the status is still IDLE - the exact start-up transient the real
        // service emits before ExoPlayer reaches READY. It must not be
        // treated as a stop.
        PlayerStateHolder.update { it.copy(activeTagUid = UID_A) }
        compose.waitForIdle()
        ShadowLooper.idleMainLooper()
        // ExoPlayer reaches READY: playback is running.
        PlayerStateHolder.update { it.copy(status = PlaybackStatus.PLAYING) }
        compose.waitForIdle()
        // The tag leaves range: the presence poll (which the transient must
        // not have torn down) must derive the loss and PAUSE.
        ShadowLooper.idleMainLooper(PRESENCE_TIMEOUT_MS_OVERSHOOT_MS, TimeUnit.MILLISECONDS)
        val pause = nextServiceIntent(1_000)
        assertNotNull(
            "the ACTION_START start-up transient (IDLE + active tag) must " +
                "not disown the tag: a removal must still pause playback",
            pause,
        )
        assertEquals(PlayerService.ACTION_PAUSE, pause!!.action)
        // Settle the state the way the paused service would.
        PlayerStateHolder.update { it.copy(status = PlaybackStatus.PAUSED) }
        // Bringing the tag back must RESUME the paused list, not restart it:
        // the policy still owns the tag, so the read takes the Resume branch.
        sendTag(controller, UID_A)
        val resume = nextServiceIntent(1_000)
        assertNotNull("the re-hold must command the service", resume)
        assertEquals(
            "a re-hold of the paused active tag must resume, not restart " +
                "the queue (no second ACTION_START)",
            PlayerService.ACTION_RESUME,
            resume!!.action,
        )
    }

    @Test
    fun proximityMode_presencePollChecksOnAOneSecondCadence() {
        // Pins the "poll every 1 second" cadence with the 15s grace period:
        // for a FRESH tag the presence record is seeded in the tagActivated
        // collector at the same instant the first poll tick is scheduled, so
        // the tick grid is anchored to the tag's last report: ticks at +1s,
        // +2s, ..., +16s. The 15s absence grace passes at the +16s tick -
        // nothing may pause earlier (a held-still tag is reported only once
        // per presentation and must keep playing the whole grace period,
        // and the first tick strictly past the grace is +16s), and the loss
        // must be reported by ~16s, not on a slower cadence. A 2s cadence
        // would land its first post-grace tick at +18s (not caught by the
        // second window), so this pins the 1s period.
        setupDb(mode = PlayMode.PROXIMITY)
        val controller = startMainActivity()
        sendTag(controller, UID_A)
        val start = nextServiceIntent()
        assertNotNull("a fresh tag read must start the player service", start)
        assertEquals(PlayerService.ACTION_START, start!!.action)
        // The tag now leaves range after its single report. No tick may
        // report the loss inside this window (it ends just past the 15s
        // grace, well before the +16s tick a 1s cadence lands on).
        ShadowLooper.idleMainLooper(15_600, TimeUnit.MILLISECONDS)
        assertNull(
            "the presence poll must not pause before a full tick period has " +
                "elapsed past the 15s grace period",
            shadowOf(app() as Application).nextStartedService,
        )
        // One more tick period: the +16s tick of the 1s cadence must report
        // the loss and pause.
        ShadowLooper.idleMainLooper(1_000, TimeUnit.MILLISECONDS)
        val pause = nextServiceIntent(500)
        assertNotNull(
            "the 1s-cadence presence poll must report the loss at the first " +
                "tick past the 15s grace (~16s), not on a slower schedule",
            pause,
        )
        assertEquals(PlayerService.ACTION_PAUSE, pause!!.action)
    }

    @Test
    fun proximityMode_stationaryTag_keepsPlayingWellPastTheOldTwoSecondTimeout() {
        // The reported bug: a tag held still is delivered once per
        // presentation and then goes quiet, so the absence-based removal
        // signal fires even though the tag is still physically in range.
        // With the old 2s grace the poll paused at ~3s; with the 15s grace
        // the same stationary tag must keep playing far past where the
        // pause used to land.
        setupDb(mode = PlayMode.PROXIMITY)
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PLAYING, activeTagUid = UID_A)
        }
        val controller = startMainActivity()
        sendTag(controller, UID_A) // the tag's report while it is presented
        // The tag is held still: no further reports arrive, but the tag is
        // still physically in range. Ten seconds elapse - far past the old
        // 2s timeout (which paused at ~3s) - and nothing may pause.
        ShadowLooper.idleMainLooper(10_000, TimeUnit.MILLISECONDS)
        assertNull(
            "a stationary tag in range must keep playing: the 15s grace " +
                "period must outlive the old 2s false-fire",
            shadowOf(app() as Application).nextStartedService,
        )
        assertEquals(PlaybackStatus.PLAYING, PlayerStateHolder.state.value.status)
        // The tag then leaves range: once the absence outlives the grace
        // the poll must derive the loss and pause.
        ShadowLooper.idleMainLooper(7_000, TimeUnit.MILLISECONDS)
        val pause = nextServiceIntent(1_000)
        assertNotNull(
            "the stationary tag that then leaves range must still pause " +
                "playback once the absence outlives the grace period",
            pause,
        )
        assertEquals(PlayerService.ACTION_PAUSE, pause!!.action)
    }

    @Test
    fun proximityMode_reReportedStationaryTag_refreshesGraceAndKeepsPlaying() {
        // A still-present tag re-reports when it moves or is re-presented:
        // each re-read of the active tag refreshes the presence record, so
        // as long as reports arrive within the 15s grace the tag counts as
        // present and playback never pauses.
        setupDb(mode = PlayMode.PROXIMITY)
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PLAYING, activeTagUid = UID_A)
        }
        val controller = startMainActivity()
        sendTag(controller, UID_A) // presentation: seeds the record
        ShadowLooper.idleMainLooper(6_000, TimeUnit.MILLISECONDS)
        sendTag(controller, UID_A) // the tag wobbles: re-reported, record refreshes
        ShadowLooper.idleMainLooper(6_000, TimeUnit.MILLISECONDS)
        sendTag(controller, UID_A) // another re-report, still in range
        ShadowLooper.idleMainLooper(6_000, TimeUnit.MILLISECONDS)
        // 18s since the first report, but each 6s gap is inside the grace:
        // the tag never left range, so nothing may have paused.
        assertNull(
            "re-reported tags keep the presence record fresh: playback must " +
                "keep going as long as reports arrive within the grace period",
            shadowOf(app() as Application).nextStartedService,
        )
    }

    @Test
    fun proximityMode_tagSwitched_thenRemoved_pausesPlayback() {
        // The tag-switch seed: tag A starts playback; tag B is then presented
        // and its read SWITCHES playback to B. B's activating read is the
        // only report the poll will ever see for B (a stationary tag is
        // delivered only once), so it must seed the presence record with B.
        // Before the seed moved to the view model's activation point this
        // read saw the stale active uid (A) and never seeded the record, so
        // when B left range the poll reported A's stale loss (ignored by
        // the policy) and playback of B's list never paused - proximity
        // behaved like continuous.
        setupDb(mode = PlayMode.PROXIMITY, tagA = true, tagB = true)
        val controller = startMainActivity()
        sendTag(controller, UID_A)
        val start = nextServiceIntent()
        assertNotNull("a fresh tag read must start the player service", start)
        assertEquals(PlayerService.ACTION_START, start!!.action)
        assertEquals(UID_A, start.getStringExtra(PlayerService.EXTRA_TAG_UID))
        sendTag(controller, UID_B)
        val switch = nextServiceIntent()
        assertNotNull("a different registered tag must switch playback", switch)
        assertEquals(PlayerService.ACTION_START, switch!!.action)
        assertEquals(UID_B, switch.getStringExtra(PlayerService.EXTRA_TAG_UID))
        // B now leaves range after its single report: no further reports
        // arrive. The presence poll must derive B's loss and pause.
        ShadowLooper.idleMainLooper(PRESENCE_TIMEOUT_MS_OVERSHOOT_MS, TimeUnit.MILLISECONDS)
        val pause = nextServiceIntent(1_000)
        assertNotNull(
            "proximity mode must pause when the tag playback was switched to " +
                "leaves range, even though its activating read is its only report",
            pause,
        )
        assertEquals(PlayerService.ACTION_PAUSE, pause!!.action)
        // Settle the state the way the paused service would.
        PlayerStateHolder.update {
            it.copy(status = PlaybackStatus.PAUSED, activeTagUid = UID_B)
        }
    }

    @Test
    fun proximityMode_switchedInWhilePlaying_tagRemoved_pausesPlayback() {
        // Mode-switch seed: tag A is playing in CONTINUOUS mode (its single
        // activating read was already delivered); the user switches to
        // PROXIMITY. reconfigureNfc resets the presence record and must
        // re-seed it from the carried active uid, otherwise the poll has
        // no record to time out and A's removal is never detected -
        // proximity behaves like continuous. Accepted tradeoff: a still-
        // present stationary tag, already reported once, pauses after the
        // timeout ("no further reports" is the only removal signal).
        setupDb(mode = PlayMode.CONTINUOUS)
        val controller = startMainActivity()
        sendTag(controller, UID_A)
        val start = nextServiceIntent()
        assertNotNull("a fresh tag read must start the player service", start)
        assertEquals(PlayerService.ACTION_START, start!!.action)
        // Settle the state the way the started service would.
        PlayerStateHolder.update {
            it.copy(status = PlaybackStatus.PLAYING, activeTagUid = UID_A)
        }
        val viewModel = viewModelOf(controller.get())
        viewModel.setPlayMode(PlayMode.PROXIMITY)
        idleUntil({ viewModel.playMode.value == PlayMode.PROXIMITY })
        compose.waitForIdle()
        ShadowLooper.idleMainLooper()
        // A leaves range after its single report: the presence poll,
        // re-seeded with A on the mode switch, must derive its loss and pause.
        ShadowLooper.idleMainLooper(PRESENCE_TIMEOUT_MS_OVERSHOOT_MS, TimeUnit.MILLISECONDS)
        val pause = nextServiceIntent(1_000)
        assertNotNull(
            "proximity mode switched in while a tag plays must pause when " +
                "that tag leaves range",
            pause,
        )
        assertEquals(PlayerService.ACTION_PAUSE, pause!!.action)
    }

    @Test
    fun proximityMode_unrelatedTagRead_doesNotMaskActiveTagRemoval() {
        // Presence record regression: only the ACTIVE registered tag's reads
        // refresh the presence record. Tag A (registered) is playing in
        // proximity mode; the user waves unregistered tag B in range, then
        // both tags leave. Pause-on-removal must still fire on A's absence:
        // if B's read clobbered the record, the poll would report B lost
        // (ignored by the policy) and A's removal would go undetected, so no
        // pause command would ever be issued.
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
                "proximity mode must pause playback after the active tag leaves range",
            intent,
        )
        assertEquals(PlayerService.ACTION_PAUSE, intent!!.action)
    }

    @Test
    fun proximityMode_playbackStopped_tearsDownPresencePoll_noPauseAfterStop() {
        // The poll is torn down when playback stops: with nothing playing,
        // a still-pending absence of the (already gone) tag must never issue
        // a command, so no background work survives a stop. It is re-armed
        // only when a tag next becomes active (covered by the fresh-tag
        // pause test).
        setupDb(mode = PlayMode.PROXIMITY)
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PLAYING, activeTagUid = UID_A)
        }
        val controller = startMainActivity()
        sendTag(controller, UID_A) // active-tag re-read refreshes the record
        // The service stops on its own (user pressed stop, the list ended):
        // the shared state settles to IDLE and the activity must tear the
        // presence poll down.
        PlayerStateHolder.update { it.copy(status = PlaybackStatus.IDLE, activeTagUid = null) }
        compose.waitForIdle()
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()
        // The absence persists long past the timeout: nothing may command.
        ShadowLooper.idleMainLooper(PRESENCE_TIMEOUT_MS_OVERSHOOT_MS, TimeUnit.MILLISECONDS)
        assertNull(
            "the presence poll must be torn down when playback stops: no " +
                "command may issue for a tag whose absence outlives a stop",
            shadowOf(app() as Application).nextStartedService,
        )
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

    // ---- tag scan: a captured (unregistered) tag ends the scan, receive path stays live ----

    @Test
    fun scanSession_capturesUnregisteredTag_andStaysListening() {
        // No tag is registered: the presented tag is unknown to the app,
        // so the scan settles into the registration flow (capture).
        setupDb(tagA = false)
        val controller = startMainActivity()
        val viewModel = viewModelOf(controller.get())
        assertTrue("starts with foreground dispatch live", NfcTestState.foregroundDispatchEnabled)

        viewModel.startTagScan()
        assertTrue("dispatch must stay live while scanning", NfcTestState.foregroundDispatchEnabled)

        // A tag is presented while scanning; it arrives via the
        // foreground-dispatch intent exactly like a main-page read. The
        // scan's settle does a Room lookup before the capture, so wait for
        // the capture itself, not just the flag.
        sendTag(controller, UID_A)
        idleUntil({ viewModel.capturedUid.value == UID_A })
        compose.waitForIdle()
        assertFalse("a captured tag must end the scan", viewModel.scanActive.value)
        assertEquals(UID_A, viewModel.capturedUid.value)
        assertTrue(
            "the receive path must stay live after the scan session",
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

        // The default 15s absence window (the `nfc_reread_seconds` setting
        // falls back to 15 when unset) plus a margin for the 1s poll ticks:
        // the first tick past the window lands at ~16s.
        private const val PRESENCE_TIMEOUT_MS_OVERSHOOT_MS = 17_000L
    }
}
