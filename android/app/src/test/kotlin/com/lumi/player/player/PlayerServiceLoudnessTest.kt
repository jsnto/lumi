package com.lumi.player.player

import android.content.Intent
import com.lumi.player.LumiApp
import com.lumi.player.data.AudioLevelMode
import com.lumi.player.data.AudioLevelSettings
import com.lumi.player.data.EntryType

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * The player service's audio level wiring: the [LoudnessProcessor] the
 * [LumiRenderersFactory] installs in the sink chain gets the per-track gain
 * from the app's audio level setting and the entry's stored loudness (the
 * [LumiApp]'s loudness snapshot - no database read on the playback path):
 * OFF mode is the exact pass-through, a measured track gets its gain (with
 * the limiter only for a boost), an unmeasured or silent track plays at its
 * recorded level (0 dB), a loud track is attenuated without the limiter,
 * and a settings flip applies to the NEXT track, not the playing one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumiApp::class)
class PlayerServiceLoudnessTest {

    private val app: LumiApp get() = RuntimeEnvironment.getApplication() as LumiApp
    private val repo get() = app.repository

    private var startId = 0

    @Before
    fun resetState() {
        PlayerStateHolder.update { PlayerUiState() }
        pumpMainLooper()
    }

    private fun pumpMainLooper(times: Int = 40) {
        repeat(times) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
    }

    private fun <T> dbAndGet(block: suspend () -> T): T {
        var result: T? = null
        Thread { result = runBlocking { block() } }.also { it.start() }.apply { join() }
        @Suppress("UNCHECK_CAST")
        return result as T
    }

    /** Stores a song and returns its entry (the payload the gain is keyed by). */
    private fun storeSong(label: String, payload: String): com.lumi.player.data.EntryEntity {
        val entry = dbAndGet { repo.addEntryUnique(EntryType.AUDIO, label, payload) }
        check(entry != null) { "the song is stored" }
        pumpMainLooper()
        return entry
    }

    /** Waits until the app-singleton snapshot carries [payload]'s row. */
    private fun awaitSnapshot(payload: String) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!app.loudnessStore.snapshot().containsKey(payload) && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        assertTrue("the snapshot settled for $payload", app.loudnessStore.snapshot().containsKey(payload))
    }

    /** Waits until the app's audio level setting reaches [expected]. */
    private fun awaitAudioLevel(expected: AudioLevelSettings) {
        val deadline = System.currentTimeMillis() + 30_000
        while (app.audioLevel != expected && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        assertEquals("the app's audio level setting settled", expected, app.audioLevel)
    }

    private fun start(
        entries: List<EntrySpec>,
        startIndex: Int = 0,
    ): org.robolectric.android.controller.ServiceController<PlayerService> {
        val intent = Intent()
            .setAction(PlayerService.ACTION_START)
            .putParcelableArrayListExtra(PlayerService.EXTRA_ENTRIES, ArrayList(entries))
            .putExtra(PlayerService.EXTRA_TAG_UID, "TAG-A")
            .putExtra(PlayerService.EXTRA_START_ENTRY_INDEX, startIndex)
        val controller = Robolectric.buildService(PlayerService::class.java, intent)
        controller.create()
        controller.startCommand(0, ++startId)
        return controller
    }

    private fun sendAction(service: PlayerService, action: String) {
        val intent = Intent().setAction(action)
        service.onStartCommand(intent, 0, ++startId)
        pumpMainLooper()
    }

    private fun processorOf(service: PlayerService): LoudnessProcessor {
        val field = PlayerService::class.java.getDeclaredField("loudnessProcessor")
        field.isAccessible = true
        return field.get(service) as LoudnessProcessor
    }

    private fun assertGain(processor: LoudnessProcessor, gainDb: Double, limiter: Boolean) {
        assertEquals("the processor's gain", gainDb, processor.gainDb, 0.0001)
        assertEquals("the limiter state", limiter, processor.limiterEngaged)
    }

    @Test
    fun offModeIsTheExactPassThroughEvenForAMeasuredTrack() {
        // The app's default setting is OFF: nothing is applied, even when
        // the track carries a measurement.
        awaitAudioLevel(AudioLevelSettings.DEFAULT)
        val entry = storeSong("Song A", "content://media/audio/A")
        dbAndGet { repo.recordLoudness(entry.id, -20.0, -6.0, "fp-A") }
        awaitSnapshot(entry.payload)

        val controller = start(listOf(EntrySpec(EntryType.AUDIO.storageValue, "Song A", entry.payload)))
        val service = controller.get()
        try {
            val processor = processorOf(service)
            // The OFF state: the exact pass-through (no gain, no limiter -
            // the processor's bit-identical copy path).
            assertGain(processor, 0.0, false)
        } finally {
            sendAction(service, PlayerService.ACTION_STOP)
            controller.destroy()
        }
    }

    @Test
    fun normalizeModeAppliesThePerTrackGainWithTheLimiterForABooSt() {
        dbAndGet { repo.setAudioLevelMode(AudioLevelMode.NORMALIZE) }
        awaitAudioLevel(AudioLevelSettings(AudioLevelMode.NORMALIZE, -16.0))
        val entry = storeSong("Song A", "content://media/audio/A")
        // -20 LUFS at a -16 target: +4 dB; the peak (-6 dBTP) leaves headroom
        // (the ceiling -1 dBTP allows +5), so the gain is the full +4.
        dbAndGet { repo.recordLoudness(entry.id, -20.0, -6.0, "fp-A") }
        awaitSnapshot(entry.payload)

        val controller = start(listOf(EntrySpec(EntryType.AUDIO.storageValue, "Song A", entry.payload)))
        val service = controller.get()
        try {
            // A boosted track: the gain applies and the limiter engages
            // (the boost may drive the peaks over the ceiling, so the
            // soft clip bends them at -1 dBTP).
            assertGain(processorOf(service), 4.0, true)
        } finally {
            sendAction(service, PlayerService.ACTION_STOP)
            controller.destroy()
        }
    }

    @Test
    fun anUnmeasuredTrackPlaysAtItsRecordedLevel() {
        dbAndGet { repo.setAudioLevelMode(AudioLevelMode.NORMALIZE) }
        awaitAudioLevel(AudioLevelSettings(AudioLevelMode.NORMALIZE, -16.0))
        val entry = storeSong("Song A", "content://media/audio/A")
        // No measurement: the song plays exactly as recorded.
        pumpMainLooper()

        val controller = start(listOf(EntrySpec(EntryType.AUDIO.storageValue, "Song A", entry.payload)))
        val service = controller.get()
        try {
            assertGain(processorOf(service), 0.0, false)
        } finally {
            sendAction(service, PlayerService.ACTION_STOP)
            controller.destroy()
        }
    }

    @Test
    fun aLouderThanTargetTrackIsAttenuatedWithoutTheLimiter() {
        dbAndGet { repo.setAudioLevelMode(AudioLevelMode.NORMALIZE) }
        awaitAudioLevel(AudioLevelSettings(AudioLevelMode.NORMALIZE, -16.0))
        val entry = storeSong("Loud One", "content://media/audio/loud")
        // -12 LUFS at a -16 target: -4 dB of attenuation. A cut never needs
        // the limiter (it only bends gains that drive samples up).
        dbAndGet { repo.recordLoudness(entry.id, -12.0, -2.0, "fp-loud") }
        awaitSnapshot(entry.payload)

        val controller = start(listOf(EntrySpec(EntryType.AUDIO.storageValue, "Loud One", entry.payload)))
        val service = controller.get()
        try {
            assertGain(processorOf(service), -4.0, false)
        } finally {
            sendAction(service, PlayerService.ACTION_STOP)
            controller.destroy()
        }
    }

    @Test
    fun aSilentMeasurementPlaysAtItsRecordedLevel() {
        dbAndGet { repo.setAudioLevelMode(AudioLevelMode.NORMALIZE) }
        awaitAudioLevel(AudioLevelSettings(AudioLevelMode.NORMALIZE, -16.0))
        val entry = storeSong("Silence", "content://media/audio/silence")
        // The file is silent: the measurement settled with no loudness, and
        // nothing can be lifted.
        dbAndGet { repo.recordLoudness(entry.id, null, null, "fp-silence") }
        awaitSnapshot(entry.payload)

        val controller = start(listOf(EntrySpec(EntryType.AUDIO.storageValue, "Silence", entry.payload)))
        val service = controller.get()
        try {
            assertGain(processorOf(service), 0.0, false)
        } finally {
            sendAction(service, PlayerService.ACTION_STOP)
            controller.destroy()
        }
    }

    @Test
    fun theSettingsFlipAppliesToTheNextTrackNotThePlayingOne() {
        dbAndGet { repo.setAudioLevelMode(AudioLevelMode.NORMALIZE) }
        awaitAudioLevel(AudioLevelSettings(AudioLevelMode.NORMALIZE, -16.0))
        val a = storeSong("Song A", "content://media/audio/A")
        val b = storeSong("Song B", "content://media/audio/B")
        dbAndGet { repo.recordLoudness(a.id, -20.0, -6.0, "fp-A") }
        dbAndGet { repo.recordLoudness(b.id, -12.0, -2.0, "fp-B") }
        awaitSnapshot(a.payload)
        awaitSnapshot(b.payload)

        val controller = start(
            listOf(
                EntrySpec(EntryType.AUDIO.storageValue, "Song A", a.payload),
                EntrySpec(EntryType.AUDIO.storageValue, "Song B", b.payload),
            ),
        )
        val service = controller.get()
        try {
            // Track A loads with the normalize gain (+4 dB, limiter on).
            assertGain(processorOf(service), 4.0, true)

            // The user switches the audio level off mid-track: the PLAYING
            // track keeps its level (the gain is per-track, set at load
            // time - a live mid-track change would click and fight the
            // limiter).
            dbAndGet { repo.setAudioLevelMode(AudioLevelMode.OFF) }
            awaitAudioLevel(AudioLevelSettings(AudioLevelMode.OFF, -16.0))
            assertGain(processorOf(service), 4.0, true)

            // The NEXT track loads under the new setting: the pass-through.
            sendAction(service, PlayerService.ACTION_NEXT)
            assertGain(processorOf(service), 0.0, false)
        } finally {
            sendAction(service, PlayerService.ACTION_STOP)
            controller.destroy()
        }
    }
}
