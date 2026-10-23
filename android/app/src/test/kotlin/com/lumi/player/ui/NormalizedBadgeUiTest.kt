package com.lumi.player.ui

import android.net.Uri
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.viewModelScope
import com.lumi.player.LumiApp
import com.lumi.player.data.AudioLevelMode
import com.lumi.player.data.EntryType
import com.lumi.player.data.FolderChild
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.lumi.player.ui.testutil.idleUntil
import com.lumi.player.ui.testutil.app
import com.lumi.player.ui.testutil.dbAndGet
import com.lumi.player.ui.testutil.openContentPane
import com.lumi.player.ui.testutil.openTagPage
import com.lumi.player.ui.testutil.viewModelOf

/**
 * The song lists badge the entries the app will actually play
 * volume-normalized - the content entries carrying a stored loudness
 * READING (the app's own measurement; the badge follows the existing
 * loudness data, it does not invent a new pipeline) AND the audio level's
 * mode enabled (OFF - the default - applies no gain, so nothing is badged
 * while it holds). A song is badged on every surface that lists tracks:
 * the content pane's rows (a measured song with the mode on badges, a
 * measured song with the mode off does not, an unmeasured one does not,
 * and a silent file's measurement - no reading - badges no song), the tag
 * pages' song rows, and the folder pages' song rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
    qualifiers = "w412dp-h900dp",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NormalizedBadgeUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        markSetupCompleteForTests()
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private fun storeSongs(vararg labels: String): List<Long> {
        return dbAndGet {
            labels.mapIndexed { index, label ->
                app().repository.addEntry(EntryType.AUDIO, label, "content://badge/$index").id
            }
        }
    }

    /** Records the app's loudness measurement for [entryId] (the data the badge follows). */
    private fun recordLoudness(entryId: Long, lufs: Double?) {
        dbAndGet { app().repository.recordLoudness(entryId, lufs, lufs?.minus(1.0), "fp-$entryId") }
    }

    /** Sets the app's audio level mode (the badge requires it enabled). */
    private fun setAudioLevelMode(mode: AudioLevelMode) {
        dbAndGet { app().repository.setAudioLevelMode(mode) }
    }

    private fun badgeCount(): Int =
        compose.onAllNodesWithContentDescription("Normalized").fetchSemanticsNodes().size

    @Test
    fun aMeasuredSongIsBadgedInTheContentPane() {
        compose.openContentPane()
        setAudioLevelMode(AudioLevelMode.NORMALIZE)
        val ids = storeSongs("alpha", "bravo")
        // The app measured alpha (it has a loudness reading); bravo was
        // never measured.
        recordLoudness(ids[0], lufs = -14.0)
        compose.idleUntil(
            cond = {
                compose.onNodeWithText("alpha").isDisplayed() &&
                    compose.onNodeWithText("bravo").isDisplayed()
            },
            message = "the content rows to settle",
        )
        compose.waitForIdle()
        // Exactly one badge: the measured song's (alpha's) row carries it,
        // the unmeasured one's row does not.
        compose.onNodeWithContentDescription("Normalized").assertExists()
        assertEquals(1, badgeCount())
    }

    @Test
    fun aMeasuredSongIsNotBadgedWhileTheModeIsOff() {
        compose.openContentPane()
        val ids = storeSongs("alpha", "bravo")
        // The mode stays at its default (OFF - no gain is applied at
        // playback), so even the stored reading is not normalized.
        recordLoudness(ids[0], lufs = -14.0)
        compose.idleUntil(
            cond = {
                compose.onNodeWithText("alpha").isDisplayed() &&
                    compose.onNodeWithText("bravo").isDisplayed()
            },
            message = "the content rows to settle",
        )
        compose.waitForIdle()
        assertEquals(0, badgeCount())
    }

    @Test
    fun theAudioLevelModeTogglesTheBadgeLive() {
        compose.openContentPane()
        val ids = storeSongs("alpha", "bravo")
        recordLoudness(ids[0], lufs = -14.0)
        compose.idleUntil(
            cond = {
                compose.onNodeWithText("alpha").isDisplayed() &&
                    compose.onNodeWithText("bravo").isDisplayed()
            },
            message = "the content rows to settle",
        )
        compose.waitForIdle()
        // Mode off (the default): the reading alone is not normalized.
        assertEquals(0, badgeCount())
        // Turning the mode on badges the measured song live ...
        setAudioLevelMode(AudioLevelMode.NORMALIZE)
        compose.idleUntil(
            cond = { badgeCount() == 1 },
            message = "the badge to appear with the mode on",
        )
        compose.waitForIdle()
        // ... and turning it off again removes it.
        setAudioLevelMode(AudioLevelMode.OFF)
        compose.idleUntil(
            cond = { badgeCount() == 0 },
            message = "the badge to disappear with the mode off",
        )
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Normalized").assertDoesNotExist()
    }

    @Test
    fun aSilentSongIsNotBadgedInTheContentPane() {
        compose.openContentPane()
        setAudioLevelMode(AudioLevelMode.NORMALIZE)
        val ids = storeSongs("silent", "loud")
        // silent's measurement settled with NO loudness reading (a silent
        // file - nothing to normalize); loud's measurement carries a
        // reading.
        recordLoudness(ids[0], lufs = null)
        recordLoudness(ids[1], lufs = -18.0)
        compose.idleUntil(
            cond = {
                compose.onNodeWithText("silent").isDisplayed() &&
                    compose.onNodeWithText("loud").isDisplayed()
            },
            message = "the content rows to settle",
        )
        compose.waitForIdle()
        // Only the reading-bearing song badges: the silent one's stored
        // row carries no reading, so it is not normalized.
        assertEquals(1, badgeCount())
    }

    @Test
    fun noBadgesWithoutAnyMeasurements() {
        compose.openContentPane()
        setAudioLevelMode(AudioLevelMode.NORMALIZE)
        storeSongs("alpha", "bravo")
        compose.idleUntil(
            cond = {
                compose.onNodeWithText("alpha").isDisplayed() &&
                    compose.onNodeWithText("bravo").isDisplayed()
            },
            message = "the content rows to settle",
        )
        compose.waitForIdle()
        assertEquals(0, badgeCount())
    }

    @Test
    fun aMeasuredSongIsBadgedOnTheTagPage() {
        val tagId = dbAndGet { app().repository.addTag("E731954D", "Bunny")?.id ?: 0L }
        val songIds = storeSongs("First song", "Second song")
        dbAndGet {
            songIds.forEach { app().repository.linkEntryToTag(tagId, it) }
        }
        // Only the tag's first song was measured.
        recordLoudness(songIds[0], lufs = -14.0)
        setAudioLevelMode(AudioLevelMode.NORMALIZE)

        compose.openTagPage("Bunny")
        compose.idleUntil(
            cond = {
                compose.onNodeWithText("First song").isDisplayed() &&
                    compose.onNodeWithText("Second song").isDisplayed()
            },
            message = "the tag's song rows to settle",
        )
        compose.waitForIdle()
        assertEquals(1, badgeCount())
    }

    @Test
    fun aMeasuredSongIsBadgedOnTheFolderPage() {
        val activity = compose.openContentPane()
        val viewModel = viewModelOf(activity)
        val events = mutableListOf<String>()
        viewModel.viewModelScope.launch {
            viewModel.uiEvents.collect { e: String -> events += e }
        }
        // The folder's walk (faked - the SAF picker cannot run) answers two
        // files.
        viewModel.folderChildren = { _ ->
            listOf(
                FolderChild("songA.mp3", isDirectory = false, "content://docs/doc/songA"),
                FolderChild("songB.mp3", isDirectory = false, "content://docs/doc/songB"),
            )
        }
        viewModel.addFolderEntry(Uri.parse(FOLDER_TREE_URI))
        compose.idleUntil(
            cond = { events.any { it == "Added 2 songs from the folder." } },
            message = "the folder add to settle",
        )
        compose.waitForIdle()
        val entryIds = dbAndGet { app().repository.observeEntries().first() }
            .associate { it.label to it.id }
        // Only songA was measured.
        recordLoudness(entryIds.getValue("songA.mp3"), lufs = -14.0)
        setAudioLevelMode(AudioLevelMode.NORMALIZE)

        // Open the folder's own page (the folder row is the pane's row).
        compose.onNodeWithText(FOLDER_TREE_URI).performScrollTo()
        compose.onNodeWithText(FOLDER_TREE_URI).performClick()
        compose.idleUntil(
            cond = {
                compose.onNodeWithText("songA.mp3").isDisplayed() &&
                    compose.onNodeWithText("songB.mp3").isDisplayed()
            },
            message = "the folder page's song rows to settle",
        )
        compose.waitForIdle()
        assertEquals(1, badgeCount())
    }

    private companion object {
        const val FOLDER_TREE_URI = "content://docs/document/audio%3A1:tree"
    }
}
