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
 * The song lists' normalized badge follows the app's own loudness data:
 * the entries carrying a stored loudness READING are the normalized ones.
 * A silent file's measurement (stored, but with no loudness reading -
 * nothing to normalize) is not one of them, and the flow is live (a
 * measurement recorded after the first emission appears in the next one).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumiApp::class)
class NormalizedEntriesTest {

    private fun repository(): LumiRepository =
        (RuntimeEnvironment.getApplication() as LumiApp).repository

    @Test
    fun onlyEntriesWithALoudnessReadingAreNormalized() = runBlocking {
        val repo = repository()
        val measured = repo.addEntry(EntryType.AUDIO, "measured", "content://n/0").id
        val silent = repo.addEntry(EntryType.AUDIO, "silent", "content://n/1").id
        val unmeasured = repo.addEntry(EntryType.AUDIO, "unmeasured", "content://n/2").id

        // No measurements yet: nothing is normalized.
        assertEquals(emptySet<Long>(), repo.observeNormalizedEntryIds().first())

        // measured gets a reading; silent's measurement settles with no
        // reading (a silent file).
        repo.recordLoudness(measured, -14.0, -1.0, "fp-measured")
        repo.recordLoudness(silent, null, null, "fp-silent")

        assertEquals(setOf(measured), repo.observeNormalizedEntryIds().first())

        // A later measurement makes the entry normalized live.
        repo.recordLoudness(unmeasured, -18.0, -2.0, "fp-unmeasured")
        assertEquals(setOf(measured, unmeasured), repo.observeNormalizedEntryIds().first())

        // A re-measurement that settles silent (reading replaced by none)
        // un-normalizes the entry again.
        repo.recordLoudness(measured, null, null, "fp-measured-silent")
        assertEquals(setOf(unmeasured), repo.observeNormalizedEntryIds().first())
    }
}
