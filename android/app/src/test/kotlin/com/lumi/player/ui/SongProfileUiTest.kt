package com.lumi.player.ui

import android.net.Uri
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.isDisplayed
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import com.lumi.player.data.FolderChild
import com.lumi.player.data.SongMetadata
import java.io.IOException
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * The song profile page, opened by tapping a song row: the song's details
 * (the title, the type, the source uri, and the file's own metadata where
 * it carries any - a read failure settles to the placeholders) and the
 * assign-to-tag action. A pick links the stored entry to that tag's song
 * list (the tag-creation flow's own mechanism - the existing content row
 * is linked, no new row stored), a re-pick of a tag that already plays
 * the song is a reported no-op, and with no registered tags the action is
 * disabled with a clear message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
    qualifiers = "w412dp-h900dp",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SongProfileUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private fun app(): LumiApp = ApplicationProvider.getApplicationContext()

    private fun openContentPane(): Pair<MainActivity, LumiViewModel> {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Content").performClick()
        compose.waitForIdle()
        val field = MainActivity::class.java.getDeclaredField("viewModel")
        field.isAccessible = true
        return activity to field.get(activity) as LumiViewModel
    }

    /** Runs a suspend block on the app's database and returns its result. */
    private fun <T> dbAndGet(block: suspend () -> T): T {
        var result: T? = null
        Thread { result = runBlocking { block() } }.also { it.start() }.apply { join() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun idleUntil(cond: () -> Boolean, timeoutMs: Long = 60_000, message: String = "the profile to settle") {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            // Settle composition before re-checking the MERGED tree (the
            // sibling classes' own pattern): the merged tree only updates
            // once Compose has recomposed and produced a frame, and a poll
            // that idles only the looper can read a stale tree. The budget
            // is generous on purpose: the class JVM's first test pays the
            // Robolectric/native-graphics cold start (~9 s even when green),
            // and suite load has stretched a warm convergence to ~16 s - the
            // loop exits on the first satisfied condition, so the margin
            // costs nothing when green.
            compose.waitForIdle()
            Thread.sleep(10)
        }
        check(cond()) { "timed out waiting for $message" }
    }

    private fun eventsOf(viewModel: LumiViewModel): MutableList<String> {
        val events = mutableListOf<String>()
        viewModel.viewModelScope.launch {
            viewModel.uiEvents.collect { e -> events += e }
        }
        return events
    }

    /**
     * Clicks a text node once the MERGED semantics tree has settled on it
     * (the merged tree can lag a pending recomposition by a frame; the
     * dialog's button is clicked while the metadata read may still be
     * settling, so the click polls for the node first).
     */
    private fun clickOnceVisible(text: String) {
        idleUntil(
            cond = { compose.onNodeWithText(text).isDisplayed() },
            message = "'$text' to be visible in the merged tree",
        )
        compose.onNodeWithText(text).performClick()
    }

    /** Stores one song and opens its profile from the content pane. */
    private fun openSongProfile(label: String = "Second song") {
        dbAndGet {
            app().repository.addEntryUnique(EntryType.AUDIO, label, "content://docs/$label")
        }
        idleUntil(cond = { compose.onNodeWithText(label).isDisplayed() }, message = "the song's row")
        // The row body opens the profile.
        compose.onNodeWithText(label).performClick()
        idleUntil(cond = { compose.onNodeWithText("Song details").isDisplayed() }, message = "the profile page")
        compose.waitForIdle()
    }

    /** The stored entry (the tests store exactly one song). */
    private fun theEntry() = dbAndGet { app().repository.observeEntries().first() }.single()

    @Test
    fun tappingASongRowOpensItsProfileWithItsDetails() {
        val (_, viewModel) = openContentPane()
        // The file's metadata, read best-effort through the seam.
        viewModel.metadataReader = { SongMetadata(artist = "The Test Band", album = "Test Album", durationMs = 125_000) }
        openSongProfile()

        // The profile's own top bar is the song's title, and the details
        // section carries the title again with the type.
        idleUntil(
            cond = {
                try {
                    compose.onAllNodesWithText("Second song").assertCountEquals(2)
                    true
                } catch (e: AssertionError) {
                    false
                }
            },
            message = "the profile's title",
        )
        // The type appears where the profile carries it: the title row's
        // own type chip and the details' Type field.
        compose.onAllNodesWithText("music file").assertCountEquals(2)

        // The file's metadata, once the best-effort read settles.
        idleUntil(
            cond = { compose.onNodeWithText("The Test Band").isDisplayed() },
            message = "the metadata read",
        )
        compose.onNodeWithText("Test Album").assertExists()
        compose.onNodeWithText("2:05").assertExists()
        // The source path.
        compose.onNodeWithText("content://docs/Second song").assertExists()

        // The page is a child of the content pane: back unwinds one level
        // to the content pane.
        compose.onNodeWithContentDescription("Back").performClick()
        idleUntil(cond = { compose.onNodeWithText("Sort by title").isDisplayed() })
    }

    @Test
    fun aMetadataReadFailureSettlesToThePlaceholders() {
        val (_, viewModel) = openContentPane()
        // The file cannot be read: the read throws, and the page settles
        // to its placeholders rather than an error.
        viewModel.metadataReader = { throw IOException("unreadable") }
        openSongProfile()

        // Artist, album and length all settle to the placeholder.
        idleUntil(
            cond = {
                try {
                    compose.onAllNodesWithText("—").assertCountEquals(3)
                    true
                } catch (e: AssertionError) {
                    false
                }
            },
            message = "the metadata placeholders",
        )
    }

    @Test
    fun assignToTagLinksTheStoredEntryThroughTheExistingMechanism() {
        val (_, viewModel) = openContentPane()
        viewModel.metadataReader = { SongMetadata() }
        openSongProfile()

        val entry = theEntry()
        val tag = dbAndGet { app().repository.addTag(uid = "DEADBEEF00", name = "Porch") }!!
        val events = eventsOf(viewModel)

        // The assign action lists the registered tag; the tag does not
        // play the song yet, so it carries no flag.
        clickOnceVisible("Choose a tag")
        compose.waitForIdle()
        compose.onNodeWithText("Porch").assertExists()
        compose.onNodeWithText("Already on this tag").assertDoesNotExist()

        // The pick links the stored entry to the tag's song list and
        // closes the list.
        clickOnceVisible("Porch")
        idleUntil(
            cond = { dbAndGet { app().repository.entriesForTag(tag.id) }.map { it.entryId } == listOf(entry.id) },
            message = "the link",
        )
        compose.waitForIdle()
        idleUntil(cond = { events.any { it == "Song added to the tag" } }, message = "the visible report")
    }

    @Test
    fun reassigningToATagThatAlreadyPlaysTheSongIsANoOpWithAVisibleIndicator() {
        val (_, viewModel) = openContentPane()
        viewModel.metadataReader = { SongMetadata() }
        openSongProfile()

        val entry = theEntry()
        val tag = dbAndGet {
            val added = app().repository.addTag(uid = "DEADBEEF00", name = "Porch")
            app().repository.linkEntryToTag(added!!.id, entry.id)
            added
        }!!
        val events = eventsOf(viewModel)

        // The dialog now flags the tag: it already plays the song.
        clickOnceVisible("Choose a tag")
        compose.waitForIdle()
        compose.onNodeWithText("Already on this tag").assertExists()

        // Picking the flagged tag is a no-op: no duplicate link, the pick
        // is reported, and the list stays open. The flag text is the
        // dialog's own marker (the profile's assigned-tags section shows
        // the tag's name only), so the pick goes through it.
        clickOnceVisible("Already on this tag")
        idleUntil(cond = { events.any { it == "That song is already on the tag." } }, message = "the no-op report")
        assertEquals(
            "a re-pick must not duplicate the link",
            listOf(entry.id),
            dbAndGet { app().repository.entriesForTag(tag.id) }.map { it.entryId },
        )
        compose.onNodeWithText("Already on this tag").assertExists()
    }

    @Test
    fun withNoRegisteredTagsTheAssignActionIsDisabledWithAClearMessage() {
        val (_, viewModel) = openContentPane()
        viewModel.metadataReader = { SongMetadata() }
        openSongProfile()

        // The action is disabled with a clear message.
        compose.onNodeWithText("No tags registered yet. Scan a tag below to add this song to it.").assertExists()
        compose.onNodeWithText("Choose a tag").assertDoesNotExist()
        // Only the section title carries the action's copy.
        idleUntil(
            cond = {
                try {
                    compose.onAllNodesWithText("Assign to a tag").assertCountEquals(1)
                    true
                } catch (e: AssertionError) {
                    false
                }
            },
        )
    }

    @Test
    fun aFolderPageSongRowOpensTheProfile() {
        val (_, viewModel) = openContentPane()
        viewModel.metadataReader = { SongMetadata() }
        // A folder whose tree walk finds one file matching the stored
        // song's payload (the page lists the stored rows the walk
        // identified).
        viewModel.folderChildren = {
            listOf(FolderChild("folder-song.mp3", isDirectory = false, childUri = SONG_PAYLOAD))
        }
        dbAndGet {
            val song = checkNotNull(app().repository.addEntryUnique(EntryType.AUDIO, "Folder song", SONG_PAYLOAD))
            val folder = app().repository.rememberFolder("content://docs/tree:walkin", "Walkin")
            app().repository.markEntriesFromFolder(folder.id, setOf(song.payload))
        }
        idleUntil(cond = { compose.onNodeWithText("Walkin").isDisplayed() }, message = "the folder's row")
        clickOnceVisible("Walkin")
        idleUntil(cond = { compose.onNodeWithText("Folder song").isDisplayed() }, message = "the folder page's song row")
        compose.waitForIdle()

        // The song row's body opens the profile, like the content pane's.
        clickOnceVisible("Folder song")
        idleUntil(cond = { compose.onNodeWithText("Song details").isDisplayed() }, message = "the profile page")
        compose.onNodeWithText(SONG_PAYLOAD).assertExists()

        // Back unwinds one level to the pane the song was opened FROM -
        // the folder's own page, not the content pane - and the song's
        // own details are gone.
        compose.onNodeWithContentDescription("Back").performClick()
        idleUntil(cond = { compose.onNodeWithText("Folder details").isDisplayed() }, message = "the folder page")
        compose.onNodeWithText("Song details").assertDoesNotExist()

        // The second back then unwinds to the content pane.
        compose.onNodeWithContentDescription("Back").performClick()
        idleUntil(cond = { compose.onNodeWithText("Individual content").isDisplayed() }, message = "the content pane")
    }

    @Test
    @Config(sdk = [26])
    fun theDefaultMetadataReaderSettlesWithoutCrashing() {
        val app = ApplicationProvider.getApplicationContext<LumiApp>()
        val viewModel = LumiViewModel(app, app.repository)
        // Runs under the minSdk (API 26) so the version guard in the default
        // reader takes its below-API-29 branch (the native release()); an
        // unguarded close() there would throw NoSuchMethodError - an Error,
        // not an Exception - and mask the settle. The default reader (not
        // the test seam) does a real content-resolver read for this
        // synthetic uri; whatever it settles to - a value or a catchable
        // read failure - must not be an Error. runCatching catches even the
        // Error; the assertion below rejects it in either outcome.
        val result = runCatching {
            viewModel.metadataReader(Uri.parse("content://docs/ProfileSong"))
        }
        assertTrue(
            "the default reader must settle to a value or a catchable Exception, never an Error (a crash)",
            result.isSuccess || result.exceptionOrNull() is Exception,
        )
    }

    private companion object {
        /** The stored song's payload (the folder walk's file uri). */
        const val SONG_PAYLOAD = "content://docs/document/audio:555"
    }
}
