package com.lumi.player.data

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.IOException

/**
 * The production loudness read's thread contract (the regression for the
 * on-device "couldn't measure … for audio level" failure): ExoPlayer
 * verifies that every application-thread API call ([ExoPlayer.setMediaItem],
 * [ExoPlayer.prepare], [ExoPlayer.release]) runs on the looper the player
 * was built with, and the [ExoPlayer.Builder] built from a [Context] pins
 * that to the MAIN looper. The view model launches the read from a
 * background dispatcher (`withContext(Dispatchers.IO)`), so the pre-fix
 * code drove the player from a worker thread: [ExoPlayer.setMediaItem]
 * threw `IllegalStateException: Player is accessed on the wrong thread`
 * before any decode started (and the `finally`'s [ExoPlayer.release]
 * threw the same violation again, which is the exception the on-device
 * logcat showed), and EVERY entry settled unmeasured - "0 of N songs
 * measured", the file never opened.
 *
 * This test drives the REAL [LoudnessMeasurer] the way the view model
 * drives it (from [Dispatchers.IO]) against a real file on disk. The read
 * must get PAST player setup (no thread violation) and actually run; the
 * run then settles one of two ways, either of which proves the contract:
 * it either completes (under Robolectric the shadowed decode path can
 * carry the tiny file to END quickly) or it settles in its own timeout
 * (a decode that cannot finish under the test runtime). A thread
 * violation (the pre-fix failure) or a decode-failure exception would
 * settle the read a different way, far earlier.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LoudnessMeasurerTest {

    @Test
    fun measureLaunchedFromABackgroundDispatcherDoesNotViolateThePlayersThreadContract() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File.createTempFile("lumi-measure", ".wav").apply { deleteOnExit() }
        file.writeBytes(tinyValidWav())

        val measurer = LoudnessMeasurer(context, timeoutMs = 800)
        val settled = CompletableDeferred<Exception?>()

        // The production shape: the view model's measureEntryBestEffort
        // runs the seam inside withContext(Dispatchers.IO).
        Thread {
            runBlocking {
                val outcome = try {
                    withContext(Dispatchers.IO) { measurer.measure(Uri.fromFile(file)) }
                    null
                } catch (e: Exception) {
                    e
                }
                settled.complete(outcome)
            }
        }.start()

        // The read's player setup runs on the main looper (the player's
        // application thread); pump it while the 800 ms timeout (scheduled
        // on the real IO scheduler) bounds the read.
        val deadline = System.currentTimeMillis() + 5_000
        var finished = false
        while (!finished && System.currentTimeMillis() < deadline) {
            if (settled.isCompleted) {
                finished = true
            } else {
                ShadowLooper.idleMainLooper()
                Thread.sleep(10)
            }
        }

        assertTrue("the read settled within the timeout window", finished)
        val failure = settled.getCompleted()
        // Two acceptable settlements, both proving the read got past player
        // setup and ran: the decode finished (the shadowed codec path can
        // carry the tiny file to END) or the read ran until its own
        // timeout bounded it. Anything else - in particular the pre-fix
        // thread violation - is the failure this test exists to catch.
        assertTrue(
            "the read ran (player setup and prepare succeeded on the " +
                "application thread) until it either settled with a result " +
                "or its own timeout bounded it: ${failure}",
            failure == null ||
                (failure is IOException && failure.message?.contains("timed out") == true),
        )
        assertFalse(
            "the read did NOT die on the player's thread contract (the pre-fix " +
                "failure: IllegalStateException 'Player is accessed on the wrong thread')",
            failure is IllegalStateException && failure.message?.contains("wrong thread") == true,
        )
    }

    /**
     * A tiny valid PCM WAV (mono, 16-bit, 8 kHz, ~200 ms of silence): a
     * header ExoPlayer's WavExtractor accepts, so any failure the read
     * settles to is a property of the read, not of the file.
     */
    private fun tinyValidWav(sampleCount: Int = 1600): ByteArray {
        val sampleRate = 8000
        val dataSize = sampleCount * 2 // 16-bit mono
        val header = ByteArray(44)
        header.setString(0, "RIFF")
        header.putIntLe(4, 36 + dataSize)
        header.setString(8, "WAVE")
        header.setString(12, "fmt ")
        header.putIntLe(16, 16)
        header.putShortLe(20, 1) // PCM
        header.putShortLe(22, 1) // mono
        header.putIntLe(24, sampleRate)
        header.putIntLe(28, sampleRate * 2) // byte rate
        header.putShortLe(32, 2) // block align
        header.putShortLe(34, 16) // bits per sample
        header.setString(36, "data")
        header.putIntLe(40, dataSize)
        return header + ByteArray(dataSize)
    }

    private fun ByteArray.setString(offset: Int, value: String) {
        for (i in value.indices) this[offset + i] = value[i].code.toByte()
    }

    private fun ByteArray.putIntLe(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
        this[offset + 2] = (value ushr 16).toByte()
        this[offset + 3] = (value ushr 24).toByte()
    }

    private fun ByteArray.putShortLe(offset: Int, value: Short) {
        this[offset] = value.toByte()
        this[offset + 1] = (value.toInt() ushr 8).toByte()
    }
}
