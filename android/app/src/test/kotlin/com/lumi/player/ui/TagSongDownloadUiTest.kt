package com.lumi.player.ui

import android.net.Uri
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import com.lumi.player.data.SongDownloadResult
import com.lumi.player.nfc.LumiTagData
import com.lumi.player.nfc.TagPayloadRead
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import com.lumi.player.ui.testutil.idleUntil
import com.lumi.player.ui.testutil.waitForText
import com.lumi.player.ui.testutil.syntheticTag
import com.lumi.player.ui.testutil.sendTagWithUid
import com.lumi.player.ui.testutil.app
import com.lumi.player.ui.testutil.dbAndGet
import com.lumi.player.ui.testutil.viewModelOf
import com.lumi.player.ui.testutil.setupMainActivity
import com.lumi.player.ui.testutil.clickSettings
import com.lumi.player.ui.testutil.clickMenuRow
import com.lumi.player.ui.testutil.clickTagRow

/**
 * The tag page's per-song download (Robolectric, the full activity; the
 * card side's "secret option" read through the `songPayloadReader` seam,
 * the download through the `songDownloader` seam): a song row whose song
 * is NOT in the content and whose last-presented card carried a valid
 * download url shows the download icon; a song that IS in the content
 * never shows it (even when the card carries a url); tapping it starts
 * the download into the public music folder's lumi subfolder (the row's
 * detail states the in-flight save; the view model tracks the per-row
 * progress); a finished download reports its event and the row returns
 * to the idle icon; a failed download settles into the visible,
 * retryable state (its own copy + the retry icon) and a retry that
 * succeeds reports the success event.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
    qualifiers = "w320dp-h700dp",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TagSongDownloadUiTest {

    @get:Rule
    val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()

    @Before
    fun setUp() {
        markSetupCompleteForTests()
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private fun tagLinks(tagId: Long): List<com.lumi.player.data.TagSongRow> =
        dbAndGet { app().repository.entriesForTag(tagId) }

    /**
     * A tag with three rows: one song IN the content (linked to a live
     * entry), one "song not found" (a link to a deleted entry), and one
     * more "song not found" (a second deleted entry) - the download
     * affordance's two input kinds plus the out-of-content control.
     */
    private fun seedTag(): Long {
        val tagId = dbAndGet { app().repository.addTag(TAG_UID, "Porch")?.id ?: 0L }
        val firstId = dbAndGet {
            app().repository.addEntry(EntryType.AUDIO, "First song", "content://first").id
        }
        val goneId = dbAndGet {
            app().repository.addEntry(EntryType.AUDIO, "Gone song", "content://gone").id
        }
        val quietId = dbAndGet {
            app().repository.addEntry(EntryType.AUDIO, "Quiet song", "content://quiet").id
        }
        dbAndGet {
            listOf(firstId, goneId, quietId).forEach { app().repository.linkEntryToTag(tagId, it) }
            app().repository.deleteEntry(
                app().repository.observeEntries().first().single { it.id == goneId },
            )
            app().repository.deleteEntry(
                app().repository.observeEntries().first().single { it.id == quietId },
            )
            true
        }
        return tagId
    }

    private fun openTagPage(name: String): ActivityController<MainActivity> {
        val controller = compose.setupMainActivity()
        compose.clickSettings()
        compose.clickMenuRow("NFC tags")
        compose.clickTagRow(name)
        return controller
    }

    // ---------- the icon's predicate, on the page ----------

    @Test
    fun theDownloadIconShowsOnlyForOutOfContentSongsWithAValidUrl() {
        val tagId = seedTag()
        val controller = openTagPage("Porch")
        val activity = controller.get()
        val viewModel = viewModelOf(activity)

        // The card's payload carries a url for BOTH the in-content song
        // and the gone song (and none for the quiet one): the icon must
        // surface only for the out-of-content, valid-url song.
        viewModel.songPayloadReader = { _ ->
            TagPayloadRead.Answered(
                LumiTagData(
                    name = "Porch",
                    songs = listOf("First song", "Gone song", "Quiet song"),
                    songUrls = mapOf(
                        "First song" to "https://cdn.example.com/first.mp3",
                        "Gone song" to "https://cdn.example.com/gone.mp3",
                    ),
                )
            )
        }
        // Drive the read the way the NFC stack's foreground dispatch does:
        // an EXTRA_TAG intent to the resumed activity.
        compose.sendTagWithUid(controller, TAG_UID)
        compose.idleUntil({ viewModel.tagSongUrls.value[TAG_UID]?.isEmpty() == false })

        compose.waitForText("First song")
        compose.waitForText("Gone song")
        compose.waitForIdle()

        // The gone song (out of content + a valid url) offers the download.
        compose.onNodeWithContentDescription("Download Gone song").assertExists()
        // The in-content song NEVER offers it, even with a url...
        compose.onNodeWithContentDescription("Download First song").assertDoesNotExist()
        // ...and the out-of-content song WITHOUT a url offers nothing.
        compose.onNodeWithContentDescription("Download Quiet song").assertDoesNotExist()

        // The invalid-url shape is refused too: a second read whose url is
        // not a song download url (an ftp link) keeps the icon hidden.
        viewModel.songPayloadReader = { _ ->
            TagPayloadRead.Answered(
                LumiTagData(
                    name = "Porch",
                    songs = listOf("First song", "Gone song", "Quiet song"),
                    songUrls = mapOf("Gone song" to "ftp://cdn.example.com/gone.mp3"),
                )
            )
        }
        compose.sendTagWithUid(controller, TAG_UID)
        compose.idleUntil({ viewModel.tagSongUrls.value[TAG_UID] == mapOf("Gone song" to "ftp://cdn.example.com/gone.mp3") })
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Download Gone song").assertDoesNotExist()
    }

    @Test
    fun aFailedReReadKeepsThePublishedUrls_andAWipedCardClearsThem() {
        // The read's own outcome drives the map: a re-presentation whose
        // read could not connect (the presence poll holds the tag's single
        // connection slot) keeps the published urls - no icon loss on the
        // re-tap - while a card that answers with no lumi record (its
        // record wiped) clears them.
        seedTag()
        val controller = openTagPage("Porch")
        val activity = controller.get()
        val viewModel = viewModelOf(activity)
        val urls = mapOf("Gone song" to "https://cdn.example.com/gone.mp3")

        viewModel.songPayloadReader = { _ ->
            TagPayloadRead.Answered(LumiTagData("Porch", listOf("Gone song"), urls))
        }
        compose.sendTagWithUid(controller, TAG_UID)
        compose.idleUntil({ viewModel.tagSongUrls.value[TAG_UID] == urls })
        compose.waitForText("Gone song")
        compose.onNodeWithContentDescription("Download Gone song").assertExists()

        // The re-tap whose read could not connect: the map keeps the urls,
        // the icon survives.
        viewModel.songPayloadReader = { _ -> TagPayloadRead.Failed }
        compose.sendTagWithUid(controller, TAG_UID)
        Thread.sleep(500)
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()
        assertEquals("a failed re-read keeps the last known urls", urls, viewModel.tagSongUrls.value[TAG_UID])
        compose.onNodeWithContentDescription("Download Gone song").assertExists()

        // The card answers with no lumi record (wiped): the map clears.
        viewModel.songPayloadReader = { _ -> TagPayloadRead.Answered(null) }
        compose.sendTagWithUid(controller, TAG_UID)
        compose.idleUntil({ viewModel.tagSongUrls.value[TAG_UID]?.isEmpty() == true })
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Download Gone song").assertDoesNotExist()
    }

    // ---------- the download: progress, success ----------

    @Test
    fun downloadingASongShowsProgressThenReportsTheSavedEvent() {
        val tagId = seedTag()
        val controller = openTagPage("Porch")
        val activity = controller.get()
        val viewModel = viewModelOf(activity)
        val events = mutableListOf<String>()
        viewModel.viewModelScope.launch { viewModel.uiEvents.collect { events += it } }

        viewModel.songPayloadReader = { _ ->
            TagPayloadRead.Answered(
                LumiTagData(
                    name = "Porch",
                    songs = listOf("Gone song"),
                    songUrls = mapOf("Gone song" to "https://cdn.example.com/gone.mp3"),
                )
            )
        }
        // The downloader seam: a controlled in-flight progress held by an
        // explicit gate (the test completes it after observing the InFlight
        // row; a delay would never come due on Robolectric's paused
        // looper), then the save into the inserted row.
        var downloads = 0
        var gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        viewModel.songDownloader = { songName, url, onProgress ->
            downloads += 1
            onProgress(null)
            onProgress(0.25f)
            gate.await()
            onProgress(1f)
            SongDownloadResult.Success(Uri.parse("content://media/external/audio/media/1"))
        }
        compose.sendTagWithUid(controller, TAG_UID)
        compose.idleUntil({ viewModel.tagSongUrls.value[TAG_UID]?.isEmpty() == false })
        compose.waitForText("Gone song")
        compose.waitForIdle()

        val goneLink = tagLinks(tagId).single { it.name == "Gone song" }.linkId
        compose.onNodeWithContentDescription("Download Gone song").performClick()
        compose.waitForIdle()

        // While the download runs the row states the in-flight save into
        // the music folder's lumi subfolder... the view model tracks the
        // row's progress determinately while it runs.
        compose.idleUntil({
            viewModel.songDownloadStates.value[goneLink] is LumiViewModel.SongDownloadState.InFlight &&
                try {
                    compose.onNodeWithText("downloading to Music/lumi...").assertExists()
                    true
                } catch (e: AssertionError) { false }
        })
        // ...and the in-flight report is the deterministic progress.
        (viewModel.songDownloadStates.value[goneLink] as? LumiViewModel.SongDownloadState.InFlight)
            ?.let { it.progress?.let { p -> assertTrue("determinate progress", p in 0f..1f) } }
        // The save finishes and the row's state settles.
        gate.complete(Unit)
        compose.idleUntil({ viewModel.songDownloadStates.value[goneLink] == null })

        // The finished download reports its event, and the row returns to
        // the idle download icon (the song is still out of content).
        assertTrue("the save is reported", events.contains("Saved \"Gone song\" to Music/lumi."))
        assertEquals("exactly one download ran", 1, downloads)
        compose.onNodeWithContentDescription("Download Gone song").assertExists()
    }

    // ---------- the failure: visible, retryable ----------

    @Test
    fun aFailedDownloadSettlesIntoTheRetryableStateAndTheRetryRuns() {
        val tagId = seedTag()
        val controller = openTagPage("Porch")
        val activity = controller.get()
        val viewModel = viewModelOf(activity)
        val events = mutableListOf<String>()
        viewModel.viewModelScope.launch { viewModel.uiEvents.collect { events += it } }

        viewModel.songPayloadReader = { _ ->
            TagPayloadRead.Answered(
                LumiTagData(
                    name = "Porch",
                    songs = listOf("Gone song"),
                    songUrls = mapOf("Gone song" to "https://cdn.example.com/gone.mp3"),
                )
            )
        }
        var succeedNext = false
        viewModel.songDownloader = { songName, url, onProgress ->
            onProgress(0.5f)
            if (succeedNext) {
                SongDownloadResult.Success(Uri.parse("content://media/external/audio/media/1"))
            } else {
                SongDownloadResult.Failure
            }
        }
        compose.sendTagWithUid(controller, TAG_UID)
        compose.idleUntil({ viewModel.tagSongUrls.value[TAG_UID]?.isEmpty() == false })
        compose.waitForText("Gone song")
        compose.waitForIdle()

        val goneLink = tagLinks(tagId).single { it.name == "Gone song" }.linkId

        // First attempt: the failure settles into the VISIBLE, RETRYABLE
        // state - its own copy on the row, the retry icon in place of the
        // download icon, and the failure event.
        compose.onNodeWithContentDescription("Download Gone song").performClick()
        compose.waitForIdle()
        compose.idleUntil({ viewModel.songDownloadStates.value[goneLink] is LumiViewModel.SongDownloadState.Failed })
        compose.onNodeWithText("download failed - tap the icon to try again").assertExists()
        compose.onNodeWithContentDescription("Retry downloading Gone song").assertExists()
        compose.onNodeWithContentDescription("Download Gone song").assertDoesNotExist()
        assertTrue(
            "the failure is reported with its retryable copy",
            events.contains("Couldn't download \"Gone song\" - tap to try again."),
        )

        // The retry: the SAME row's icon re-arms the download, and this
        // time it succeeds.
        succeedNext = true
        compose.onNodeWithContentDescription("Retry downloading Gone song").performClick()
        compose.waitForIdle()
        compose.idleUntil({ viewModel.songDownloadStates.value[goneLink] == null })
        assertTrue("the retry's save is reported", events.contains("Saved \"Gone song\" to Music/lumi."))
        // The row is back to the idle download icon.
        compose.onNodeWithContentDescription("Download Gone song").assertExists()
    }

    companion object {
        // A plain hex uid (the synthetic tag's uid bytes are hex-decoded
        // in tagWithUid - no colon separators, even length).
        const val TAG_UID = "04A1B2C3"
    }
}
