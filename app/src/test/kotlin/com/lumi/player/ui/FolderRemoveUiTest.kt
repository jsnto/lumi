package com.lumi.player.ui

import android.net.Uri
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.data.FolderChild
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.FileNotFoundException

/**
 * Behavioral coverage for removing an added folder: the Content pane's
 * Folders section offers a remove affordance next to the rescan action, and
 * removing a folder removes the folder row AND the songs imported from it
 * (matched by re-walking the tree - the rescan precedent - so removal
 * matches exactly what the add stored), behind a destructive confirmation
 * that states the effect: the folder and its imported songs leave the
 * app's content list, the music files on the device are not touched. When
 * the tree can no longer be read the count cannot be determined and the
 * confirmation says so plainly; only the folder row is then removed.
 *
 * The removal is driven through the view model (the confirmation dialog's
 * buttons are asserted by copy) with the faked [LumiViewModel.folderChildren]
 * seam, since the SAF picker and provider cannot run in Robolectric - the
 * same way the rescan tests drive their flows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FolderRemoveUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private fun app(): LumiApp = ApplicationProvider.getApplicationContext()

    /** Opens the Content pane and returns its (activity-scoped) view model. */
    private fun openContentPane(): LumiViewModel {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Content").performClick()
        compose.waitForIdle()
        val field = MainActivity::class.java.getDeclaredField("viewModel")
        field.isAccessible = true
        return field.get(activity) as LumiViewModel
    }

    /** Runs a suspend block on the app's database and returns its result. */
    private fun <T> dbAndGet(block: suspend () -> T): T {
        var result: T? = null
        Thread { result = runBlocking { block() } }.also { it.start() }.apply { join() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun idleUntil(cond: () -> Boolean, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        check(cond()) { "timed out waiting for the operation to settle" }
    }

    private fun eventsOf(viewModel: LumiViewModel): MutableList<String> {
        val events = mutableListOf<String>()
        viewModel.viewModelScope.launch { viewModel.uiEvents.collect { e: String -> events += e } }
        return events
    }

    private fun file(name: String, uri: String) = FolderChild(name, isDirectory = false, childUri = uri)

    /** Replaces the folder-children seam with the given single-folder tree. */
    private fun swapTree(viewModel: LumiViewModel, children: List<FolderChild>) {
        viewModel.folderChildren = { folderUri ->
            if (folderUri.toString() == TREE_URI) children else emptyList()
        }
    }

    /** Adds a folder through the view model until its add event settles. */
    private fun addFolder(viewModel: LumiViewModel, events: MutableList<String>, expected: String) {
        viewModel.addFolderEntry(Uri.parse(TREE_URI))
        idleUntil({ events.any { it == expected } })
    }

    @Test
    fun theFoldersSectionOffersARemoveAffordanceNextToRescan() {
        val viewModel = openContentPane()
        swapTree(viewModel, listOf(file("song-a.mp3", "c://x/a")))
        addFolder(viewModel, eventsOf(viewModel), "Added 1 song from the folder.")

        // On the small test screen the section sits below the viewport, so
        // existence is the layout assertion - the affordance is right next
        // to the rescan button it shares the row with.
        compose.waitForIdle()
        compose.onNodeWithText("Folders").assertExists()
        compose.onNodeWithText("Rescan").assertExists()
        compose.onNodeWithContentDescription("Remove folder").assertExists()
    }

    @Test
    fun removingAFolderRemovesTheFolderRowAndItsImportedSongs() {
        val viewModel = openContentPane()
        swapTree(viewModel, listOf(file("song-a.mp3", "c://x/a"), file("song-b.mp3", "c://x/b")))
        val events = eventsOf(viewModel)
        addFolder(viewModel, events, "Added 2 songs from the folder.")

        // Link one of the imported songs to a tag, so the cascade off the
        // deleted entry is observable.
        val tag = checkNotNull(dbAndGet { app().repository.addTag("uid-1", "Test tag") })
        val entryA = dbAndGet { app().repository.observeEntries().first() }.first { it.label == "song-a.mp3" }
        viewModel.addLinked(tag.id, entryA.id)
        idleUntil({ dbAndGet { app().repository.entriesForTag(tag.id) }.size == 1 })

        val folder = dbAndGet { app().repository.observeFolders().first() }.single()
        viewModel.requestFolderRemoval(folder)
        idleUntil({ viewModel.folderRemovalPreview.value != null })

        // The preview identified the imported songs (the walked payloads)
        // and counted how many are still stored.
        val preview = viewModel.folderRemovalPreview.value!!
        assertEquals(setOf("c://x/a", "c://x/b"), preview.importedPayloads)
        assertEquals(2, preview.songCount)

        // The confirmation states the effect before the destructive confirm.
        compose.waitForIdle()
        compose.onNodeWithText("Remove folder?").assertExists()
        compose.onNodeWithText(
            "and its 2 imported songs will be removed from the content list",
            substring = true,
        ).assertExists()
        compose.onNodeWithText("The music files themselves stay on the phone.", substring = true)
            .assertExists()

        viewModel.confirmFolderRemoval()
        idleUntil({ events.any { it == "Removed the folder and 2 imported songs." } })

        // The folder row and its imported songs are gone...
        assertTrue("the folder row is removed", dbAndGet { app().repository.observeFolders().first() }.isEmpty())
        assertTrue("the imported songs are removed", dbAndGet { app().repository.observeEntries().first() }.isEmpty())
        // ...and the tag link cascaded off the deleted entry (the tag row
        // itself stays, as a single-entry removal does).
        assertTrue(
            "the tag link cascaded off the deleted entry",
            dbAndGet { app().repository.entriesForTag(tag.id) }.isEmpty(),
        )
        assertEquals(1, dbAndGet { app().repository.observeTags().first() }.size)
    }

    @Test
    fun removingAnUnreadableFolderRemovesOnlyTheFolderRow() {
        val viewModel = openContentPane()
        swapTree(viewModel, listOf(file("song-a.mp3", "c://x/a")))
        val events = eventsOf(viewModel)
        addFolder(viewModel, events, "Added 1 song from the folder.")

        // The grant goes away after the add: the tree can no longer be read,
        // so the imported songs can no longer be identified either.
        viewModel.folderChildren = { throw FileNotFoundException("grant revoked") }

        val folder = dbAndGet { app().repository.observeFolders().first() }.single()
        viewModel.requestFolderRemoval(folder)
        idleUntil({ viewModel.folderRemovalPreview.value != null })

        // The preview plainly says the count is undeterminable.
        val preview = viewModel.folderRemovalPreview.value!!
        assertNull(preview.importedPayloads)
        assertNull(preview.songCount)
        compose.waitForIdle()
        compose.onNodeWithText("Remove folder?").assertExists()
        compose.onNodeWithText(
            "can no longer be read, so the songs imported from it can't be identified and will stay",
            substring = true,
        ).assertExists()

        viewModel.confirmFolderRemoval()
        idleUntil({ events.any { it == "Removed the folder. Its songs couldn't be read, so they were left in place." } })

        assertTrue("the folder row is removed", dbAndGet { app().repository.observeFolders().first() }.isEmpty())
        assertEquals(
            "the songs that can no longer be identified stay",
            listOf("song-a.mp3"),
            dbAndGet { app().repository.observeEntries().first() }.map { it.label },
        )
    }

    @Test
    fun removingAFolderWithNoImportedSongsRemovesOnlyTheFolderRow() {
        val viewModel = openContentPane()
        // The folder holds no supported audio: the add stored no songs.
        swapTree(viewModel, listOf(FolderChild("readme.txt", isDirectory = false, childUri = "c://x/readme.txt")))
        val events = eventsOf(viewModel)
        addFolder(viewModel, events, "No songs found in that folder.")

        val folder = dbAndGet { app().repository.observeFolders().first() }.single()
        viewModel.requestFolderRemoval(folder)
        idleUntil({ viewModel.folderRemovalPreview.value != null })

        assertEquals(0, viewModel.folderRemovalPreview.value!!.songCount)
        compose.waitForIdle()
        compose.onNodeWithText("has no imported songs in the content list", substring = true).assertExists()

        viewModel.confirmFolderRemoval()
        idleUntil({ events.any { it == "Removed the folder." } })

        assertTrue("the folder row is removed", dbAndGet { app().repository.observeFolders().first() }.isEmpty())
        assertTrue("no entries existed, none were touched", dbAndGet { app().repository.observeEntries().first() }.isEmpty())
    }

    @Test
    fun cancellingAFolderRemovalKeepsTheFolderAndItsSongs() {
        val viewModel = openContentPane()
        swapTree(viewModel, listOf(file("song-a.mp3", "c://x/a")))
        val events = eventsOf(viewModel)
        addFolder(viewModel, events, "Added 1 song from the folder.")

        val folder = dbAndGet { app().repository.observeFolders().first() }.single()
        viewModel.requestFolderRemoval(folder)
        idleUntil({ viewModel.folderRemovalPreview.value != null })

        viewModel.cancelFolderRemoval()
        assertTrue("the preview is dismissed", viewModel.folderRemovalPreview.value == null)
        assertEquals(
            "the folder stays",
            listOf(TREE_URI),
            dbAndGet { app().repository.observeFolders().first() }.map { it.uri },
        )
        assertEquals(
            "the imported song stays",
            listOf("song-a.mp3"),
            dbAndGet { app().repository.observeEntries().first() }.map { it.label },
        )
    }

    companion object {
        /** A documents tree uri (the shape the SAF picker returns). */
        const val TREE_URI = "content://docs/document/audio%3A1:tree"
    }
}
