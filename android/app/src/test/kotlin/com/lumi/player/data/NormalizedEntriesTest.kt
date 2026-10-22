package com.lumi.player.data

import com.lumi.player.LumiApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The song lists' normalized badge follows what the app will actually play
 * volume-normalized: a stored loudness READING and the audio level's mode
 * enabled. The mode defaults to OFF (no gain is applied there), so a
 * reading alone is not normalized; a silent file's measurement (stored,
 * but with no loudness reading - nothing to normalize) is never one, and
 * the flow is live in both directions (a measurement recorded after the
 * first emission, and a mode flip either way, appear in the next one).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumiApp::class)
class NormalizedEntriesTest {

    private fun repository(): LumiRepository =
        (RuntimeEnvironment.getApplication() as LumiApp).repository

    @Test
    fun modeOffWithReadingsNormalizesNothing() = runBlocking {
        val repo = repository()
        val measured = repo.addEntry(EntryType.AUDIO, "measured", "content://n/0").id
        val silent = repo.addEntry(EntryType.AUDIO, "silent", "content://n/1").id

        // The mode defaults to OFF: no gain is applied at playback, so
        // even a stored reading is not normalized.
        repo.recordLoudness(measured, -14.0, -1.0, "fp-measured")
        repo.recordLoudness(silent, null, null, "fp-silent")

        assertEquals(emptySet<Long>(), repo.observeNormalizedEntryIds().first())
    }

    @Test
    fun modeOnNormalizesOnlyTheEntriesWithAReading() = runBlocking {
        val repo = repository()
        val measured = repo.addEntry(EntryType.AUDIO, "measured", "content://n/0").id
        val silent = repo.addEntry(EntryType.AUDIO, "silent", "content://n/1").id
        val unmeasured = repo.addEntry(EntryType.AUDIO, "unmeasured", "content://n/2").id

        repo.setAudioLevelMode(AudioLevelMode.NORMALIZE)

        // No measurements yet (mode on): nothing is normalized.
        assertEquals(emptySet<Long>(), repo.observeNormalizedEntryIds().first())

        // measured gets a reading; silent's measurement settles with no
        // reading (a silent file).
        repo.recordLoudness(measured, -14.0, -1.0, "fp-measured")
        repo.recordLoudness(silent, null, null, "fp-silent")

        // The mode is on: the reading-bearing entry is normalized - the
        // silent file's row carries no reading, so it never is.
        assertEquals(setOf(measured), repo.observeNormalizedEntryIds().first())

        // A later measurement makes the entry normalized live.
        repo.recordLoudness(unmeasured, -18.0, -2.0, "fp-unmeasured")
        assertEquals(setOf(measured, unmeasured), repo.observeNormalizedEntryIds().first())

        // A re-measurement that settles silent (reading replaced by none)
        // un-normalizes the entry again.
        repo.recordLoudness(measured, null, null, "fp-measured-silent")
        assertEquals(setOf(unmeasured), repo.observeNormalizedEntryIds().first())

        // Flipping the mode back to OFF un-normalizes everything, live ...
        repo.setAudioLevelMode(AudioLevelMode.OFF)
        assertEquals(emptySet<Long>(), repo.observeNormalizedEntryIds().first())

        // ... and back on restores the set.
        repo.setAudioLevelMode(AudioLevelMode.NORMALIZE)
        assertEquals(setOf(unmeasured), repo.observeNormalizedEntryIds().first())
    }
}
