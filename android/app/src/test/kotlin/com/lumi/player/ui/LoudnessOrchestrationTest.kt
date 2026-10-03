package com.lumi.player.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.viewModelScope
import com.lumi.player.LumiApp
import com.lumi.player.data.AudioLevelMode
import com.lumi.player.data.EntryEntity
import com.lumi.player.data.EntryType
import com.lumi.player.data.LoudnessResult
import com.lumi.player.data.LoudnessSample
import com.lumi.player.data.LoudnessStatus
import com.lumi.player.data.gainFor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.atomic.AtomicInteger

/**
 * The audio level's measurement orchestration (the view model side): the
 * explicit "Normalize now" pass (every unmeasured or stale audio entry,
 * best-effort, with the pass's own events), the fingerprint check (an
 * unchanged file keeps its stored measurement - no re-read), a second pass
 * while one is running is ignored, the single measurement slot (one
 * loudness decode at a time: on-add measurements queue behind each other
 * and behind an in-flight pass), a failure settles the entry to
 * unmeasured with its own event (the pass keeps going), a silent
 * measurement still counts as measured, and a freshly added song is
 * measured right away (the on-add hook).
 *
 * The measurement read itself is faked through the [LumiViewModel] seam
 * (the real read is the background-decode [LoudnessMeasurer] - a
 * device-side path).
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
class LoudnessOrchestrationTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        markSetupCompleteForTests()
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private val app: LumiApp get() = RuntimeEnvironment.getApplication() as LumiApp
    private val repo get() = app.repository

    /** The activity's view model (the real construction, the test seam
     *  reachable through it). */
    private fun viewModelOf(activity: MainActivity): LumiViewModel {
        return MainActivity::class.java.getDeclaredField("viewModel").apply {
            isAccessible = true
        }.get(activity) as LumiViewModel
    }

    private fun <T> dbAndGet(block: suspend () -> T): T {
        var result: T? = null
        Thread { result = runBlocking { block() } }.also { it.start() }.apply { join() }
        @Suppress("UNCHECK_CAST")
        return result as T
    }

    /**
     * Stores a song directly in the repository (bypassing the view
     * model's on-add measurement hook, so the pass under test is the only
     * reader).
     */
    private fun storeSong(label: String, payload: String): EntryEntity {
        val entry = dbAndGet { repo.addEntryUnique(EntryType.AUDIO, label, payload) }
        check(entry != null) { "the song is stored" }
        pumpMainLooper()
        return entry
    }

    private fun pumpMainLooper(times: Int = 40) {
        repeat(times) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
    }

    private fun idleUntil(cond: () -> Boolean, timeoutMs: Long = 30_000, message: String = "the operation to settle") {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        assertTrue("timed out waiting for $message", cond())
    }

    private fun eventsOf(viewModel: LumiViewModel): MutableList<String> {
        val events = mutableListOf<String>()
        viewModel.viewModelScope.launch { viewModel.uiEvents.collect { e: String -> events += e } }
        return events
    }

    @Test
    fun normalizeNowMeasuresEveryUnmeasuredAudioEntryAndReports() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val viewModel = viewModelOf(activity)
        val events = eventsOf(viewModel)
        val calls = AtomicInteger()
        viewModel.loudnessMeasurer = {
            calls.incrementAndGet()
            LoudnessResult(-16.0, -1.5)
        }
        storeSong("One", "content://one")
        storeSong("Two", "content://two")
        // A non-audio entry is not measured (the pass is audio-only).
        dbAndGet { repo.addEntry(EntryType.URL, "Site", "https://site") }
        pumpMainLooper()
        idleUntil({ viewModel.loudnessStatus.value == LoudnessStatus(0, 2) })

        viewModel.normalizeNow()
        idleUntil({ !viewModel.normalizing.value && calls.get() == 2 })
        // The Room flows settle after the last write lands: wait for the
        // status to catch up before asserting.
        idleUntil({ viewModel.loudnessStatus.value == LoudnessStatus(2, 2) })
        idleUntil({ events.any { it.startsWith("Audio level ready") } })

        assertEquals("only the audio entries are measured", 2, calls.get())
        assertEquals(
            "both songs carry a measurement now",
            LoudnessStatus(2, 2),
            viewModel.loudnessStatus.value,
        )
        assertTrue("the pass announces itself", events.contains("Measuring 2 songs for audio level…"))
        assertTrue("the pass settles", events.contains("Audio level ready."))

        // The stored rows carry the measurement (the url entry has none).
        val entries = dbAndGet { repo.observeEntries().first() }
        for (entry in entries.filter { EntryType.fromStorage(it.type) == EntryType.AUDIO }) {
            val row = dbAndGet { repo.loudnessForEntry(entry.id) }
            assertEquals(-16.0, row!!.lufs!!, 0.0001)
            assertEquals(-1.5, row.truePeakDbTp!!, 0.0001)
        }
        val site = entries.first { it.label == "Site" }
        assertEquals("the url entry is never measured", null, dbAndGet { repo.loudnessForEntry(site.id) })
        controller.destroy()
    }

    @Test
    fun aSecondNormalizeNowWhileOneIsRunningIsIgnored() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val viewModel = viewModelOf(activity)
        val calls = AtomicInteger()
        // The measurement hangs until the test releases it - the pass stays
        // in flight (the paused looper never advances a delay, so a gate,
        // not a delay, is the seam).
        val gate = CompletableDeferred<Unit>()
        viewModel.loudnessMeasurer = {
            calls.incrementAndGet()
            gate.await()
            LoudnessResult(-16.0, -1.0)
        }
        storeSong("One", "content://one")
        idleUntil({ viewModel.loudnessStatus.value == LoudnessStatus(0, 1) })

        viewModel.normalizeNow()
        idleUntil({ calls.get() == 1 })

        // While the first pass is in flight, a second request is a no-op.
        viewModel.normalizeNow()
        pumpMainLooper()
        assertEquals("the in-flight pass runs alone", 1, calls.get())

        gate.complete(Unit)
        idleUntil({ !viewModel.normalizing.value })
        idleUntil({ viewModel.loudnessStatus.value == LoudnessStatus(1, 1) })
        assertEquals("the single pass measured the song exactly once", 1, calls.get())
        controller.destroy()
    }

    @Test
    fun onAddMeasurementsQueueBehindTheInFlightOneAtATime() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val viewModel = viewModelOf(activity)
        val calls = AtomicInteger()
        val inFlight = AtomicInteger(0)
        val peakInFlight = AtomicInteger(0)
        // The first measurement hangs until the test releases it - the
        // second on-add measurement must queue behind it, not start beside
        // it (a folder add's burst is the same shape at folder scale).
        val firstGate = CompletableDeferred<Unit>()
        viewModel.loudnessMeasurer = {
            calls.incrementAndGet()
            val now = inFlight.incrementAndGet()
            peakInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            if (calls.get() == 1) firstGate.await()
            inFlight.decrementAndGet()
            LoudnessResult(-16.0, -1.0)
        }
        val one = storeSong("One", "content://one")
        val two = storeSong("Two", "content://two")

        viewModel.measureEntry(one)
        idleUntil({ calls.get() == 1 })
        viewModel.measureEntry(two)
        pumpMainLooper()
        assertEquals("the queued on-add measurement does not start beside the in-flight one", 1, calls.get())

        firstGate.complete(Unit)
        idleUntil({ calls.get() == 2 })
        idleUntil({ viewModel.loudnessStatus.value == LoudnessStatus(2, 2) })
        assertEquals("at most one loudness decode runs at a time", 1, peakInFlight.get())
        controller.destroy()
    }

    @Test
    fun anOnAddMeasurementQueuesBehindAnInFlightPassInsteadOfRunningBesideIt() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val viewModel = viewModelOf(activity)
        val calls = AtomicInteger()
        // The pass's first decode hangs until the test releases it.
        val gate = CompletableDeferred<Unit>()
        viewModel.loudnessMeasurer = {
            calls.incrementAndGet()
            if (calls.get() == 1) gate.await()
            LoudnessResult(-16.0, -1.0)
        }
        storeSong("One", "content://one")
        val two = storeSong("Two", "content://two")
        idleUntil({ viewModel.loudnessStatus.value == LoudnessStatus(0, 2) })

        // The explicit pass is mid-decode when the on-add hook fires for
        // the same song the pass is about to measure: it must queue behind
        // the pass's slot, and the pass's stored row makes its own read
        // redundant (one decode for the entry, not two).
        viewModel.normalizeNow()
        idleUntil({ calls.get() == 1 })
        viewModel.measureEntry(two)
        pumpMainLooper()
        assertEquals("the on-add measurement waits for the pass's slot", 1, calls.get())

        gate.complete(Unit)
        idleUntil({ !viewModel.normalizing.value && calls.get() == 2 })
        idleUntil({ viewModel.loudnessStatus.value == LoudnessStatus(2, 2) })
        assertEquals("two entries, two decodes - no parallel or duplicate read", 2, calls.get())
        controller.destroy()
    }

    @Test
    fun aFreshlyAddedSongIsMeasuredRightAwayWithoutAnExplicitPass() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val viewModel = viewModelOf(activity)
        val events = eventsOf(viewModel)
        val calls = AtomicInteger()
        viewModel.loudnessMeasurer = {
            calls.incrementAndGet()
            LoudnessResult(-15.0, -0.5)
        }
        val entry = storeSong("One", "content://one")

        // The on-add hook: a song added to the content list is measured
        // right away (the audio level works the moment it is turned on).
        viewModel.measureEntry(entry)
        idleUntil({ viewModel.loudnessStatus.value == LoudnessStatus(1, 1) })
        assertEquals("the on-add measurement ran", 1, calls.get())
        val row = dbAndGet { repo.loudnessForEntry(entry.id) }
        assertEquals(-15.0, row!!.lufs!!, 0.0001)
        controller.destroy()
    }

    @Test
    fun anUnchangedEntryKeepsItsStoredMeasurementAndIsNotReRead() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val viewModel = viewModelOf(activity)
        val events = eventsOf(viewModel)
        val calls = AtomicInteger()
        viewModel.loudnessMeasurer = {
            calls.incrementAndGet()
            LoudnessResult(-16.0, -1.0)
        }
        storeSong("One", "content://one")
        idleUntil({ viewModel.loudnessStatus.value == LoudnessStatus(0, 1) })

        viewModel.normalizeNow()
        idleUntil({ !viewModel.normalizing.value && calls.get() == 1 })
        assertEquals("the first pass measured the song", 1, calls.get())

        // The explicit pass again finds nothing stale: no re-read, its own
        // copy.
        viewModel.normalizeNow()
        idleUntil({ events.contains("Everything is already measured.") })
        assertEquals("an unchanged file is not re-read", 1, calls.get())
        controller.destroy()
    }

    @Test
    fun aChangedEntryIsReMeasuredAndAnUnchangedOneIsNot() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val viewModel = viewModelOf(activity)
        val events = eventsOf(viewModel)
        val calls = AtomicInteger()
        viewModel.loudnessMeasurer = {
            calls.incrementAndGet()
            LoudnessResult(-16.0, -1.0)
        }
        storeSong("One", "content://one")
        storeSong("Two", "content://two")
        idleUntil({ viewModel.loudnessStatus.value == LoudnessStatus(0, 2) })

        viewModel.normalizeNow()
        idleUntil({ calls.get() == 2 && !viewModel.normalizing.value })
        assertEquals(2, calls.get())

        // "One"'s content changed: its stored fingerprint no longer matches
        // the entry's content fingerprint, so it is stale again.
        val one = dbAndGet { repo.observeEntries().first() }.first { it.label == "One" }
        dbAndGet { repo.recordLoudness(one.id, -16.0, -1.0, "a-stale-fingerprint") }

        viewModel.normalizeNow()
        idleUntil({ calls.get() == 3 && !viewModel.normalizing.value })
        assertEquals("only the stale entry is re-measured", 3, calls.get())
        idleUntil({ events.any { it == "Audio level ready." } })

        // Now both are current: a further pass is a no-op.
        viewModel.normalizeNow()
        idleUntil({ events.lastOrNull() == "Everything is already measured." })
        assertEquals(3, calls.get())
        controller.destroy()
    }

    @Test
    fun aFailedMeasurementLeavesTheEntryUnmeasuredWithItsOwnEvent() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val viewModel = viewModelOf(activity)
        val events = eventsOf(viewModel)
        viewModel.loudnessMeasurer = { throw java.io.IOException("no decoder") }
        val entry = storeSong("One", "content://one")
        idleUntil({ viewModel.loudnessStatus.value == LoudnessStatus(0, 1) })

        // The on-add measurement fails: the entry stays unmeasured, with its
        // own event (the song plays at its recorded level).
        viewModel.measureEntry(entry)
        idleUntil({ events.any { it == "Couldn't measure “One” for audio level." } })
        assertEquals(LoudnessStatus(0, 1), viewModel.loudnessStatus.value)

        // The explicit pass retries and fails the same way: the pass
        // settles with its own copy.
        viewModel.normalizeNow()
        idleUntil({ !viewModel.normalizing.value })
        idleUntil({ events.any { it == "Audio level ready - 1 song couldn't be measured." } })
        assertEquals("the entry is still unmeasured", LoudnessStatus(0, 1), viewModel.loudnessStatus.value)
        controller.destroy()
    }

    @Test
    fun aSilentMeasurementStillCountsAsMeasured() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val viewModel = viewModelOf(activity)
        val events = eventsOf(viewModel)
        // A silent file: the read settles, there is just nothing to
        // normalize (null loudness).
        viewModel.loudnessMeasurer = { LoudnessResult(null, null) }
        val entry = storeSong("One", "content://one")
        idleUntil({ viewModel.loudnessStatus.value == LoudnessStatus(0, 1) })

        viewModel.measureEntry(entry)
        idleUntil({ viewModel.loudnessStatus.value == LoudnessStatus(1, 1) })
        val row = dbAndGet { repo.loudnessForEntry(entry.id) }
        assertEquals("the silence is stored as no loudness", null, row!!.lufs)
        assertNull(row.truePeakDbTp)
        // The gain of a silent track is 0 dB (nothing to lift): it plays at
        // its recorded level.
        val gain = gainFor(
            AudioLevelMode.NORMALIZE,
            -16.0,
            LoudnessSample(row.lufs, row.truePeakDbTp, row.fingerprint, row.measuredAt),
        )
        assertEquals(0.0, gain, 0.0001)
        controller.destroy()
    }
}
