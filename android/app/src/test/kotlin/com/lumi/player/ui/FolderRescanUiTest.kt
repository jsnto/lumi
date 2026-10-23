package com.lumi.player.ui

import android.net.Uri
import java.io.FileNotFoundException
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import com.lumi.player.LumiApp
import com.lumi.player.data.FolderChild
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.lumi.player.ui.testutil.idleUntil
import com.lumi.player.ui.testutil.app
import com.lumi.player.ui.testutil.dbAndGet
import com.lumi.player.ui.testutil.openContentPane
import com.lumi.player.ui.testutil.viewModelOf

/**
 * Behavioral coverage for rescanning an added folder: adding a folder tree
 * remembers it in the content pane's "Folders" section (each row opens the
 * folder's detail page, which carries the "Rescan" affordance), and
 * rescanning re-walks the tree and stores only the audio files added since
 * the last scan - files already stored are skipped, and a rescan of an
 * unchanged folder adds nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FolderRescanUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        markSetupCompleteForTests()
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private fun eventsOf(viewModel: LumiViewModel): MutableList<String> {
        val events = mutableListOf<String>()
        viewModel.viewModelScope.launch { viewModel.uiEvents.collect { e: String -> events += e } }
        return events
    }

    @Test
    fun addingAFolderRemembersItWithARescanAffordance() {
        val activity = compose.openContentPane()
        val viewModel = viewModelOf(activity)
        viewModel.folderChildren = { folderUri ->
            if (folderUri.toString() == TREE_URI) {
                listOf(FolderChild("song-a.mp3", isDirectory = false, childUri = "content://docs/document/audio:100"))
            } else emptyList()
        }

        val events = eventsOf(viewModel)
        viewModel.addFolderEntry(Uri.parse(TREE_URI))
        compose.idleUntil({ events.any { it.startsWith("Added") } })

        // The folder is now listed in the "Folders" section; its rescan
        // affordance lives on the folder's detail page. On the small test
        // screen the section sits below the viewport, so existence is the
        // layout assertion, and the page is opened by tapping the row.
        compose.waitForIdle()
        compose.onNodeWithText("Folders").assertExists()
        compose.onNodeWithText("Tap to view its songs").assertExists()
        compose.onNodeWithText(TREE_URI).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Folder details").assertExists()
        compose.onNodeWithContentDescription("Rescan").assertExists()
    }

    @Test
    fun rescanPicksUpASongAddedSinceTheLastScan() {
        val activity = compose.openContentPane()
        val viewModel = viewModelOf(activity)
        // First the folder holds one song; a rescan later finds a new one.
        viewModel.folderChildren = { folderUri ->
            if (folderUri.toString() == TREE_URI) {
                listOf(FolderChild("song-a.mp3", isDirectory = false, childUri = "content://docs/document/audio:100"))
            } else emptyList()
        }

        val events = eventsOf(viewModel)
        viewModel.addFolderEntry(Uri.parse(TREE_URI))
        compose.idleUntil({ events.any { it.startsWith("Added") } })

        // A new file appears in the folder after the initial scan.
        viewModel.folderChildren = { folderUri ->
            if (folderUri.toString() == TREE_URI) {
                listOf(
                    FolderChild("song-a.mp3", isDirectory = false, childUri = "content://docs/document/audio:100"),
                    FolderChild("song-new.mp3", isDirectory = false, childUri = "content://docs/document/audio:199"),
                )
            } else emptyList()
        }

        val folder = dbAndGet { app().repository.observeFolders().first() }.single()
        viewModel.rescanFolder(folder)
        compose.idleUntil({ events.any { it == "Added 1 new song from that folder." } })

        val entries = dbAndGet { app().repository.observeEntries().first() }
        assertEquals(
            "the rescan stores the new song alongside the original, no duplicate",
            listOf("song-a.mp3", "song-new.mp3"),
            entries.map { it.label },
        )
    }

    @Test
    fun rescanOfAnUnchangedFolderAddsNothing() {
        val activity = compose.openContentPane()
        val viewModel = viewModelOf(activity)
        viewModel.folderChildren = { folderUri ->
            if (folderUri.toString() == TREE_URI) {
                listOf(FolderChild("song-a.mp3", isDirectory = false, childUri = "content://docs/document/audio:100"))
            } else emptyList()
        }

        val events = eventsOf(viewModel)
        viewModel.addFolderEntry(Uri.parse(TREE_URI))
        compose.idleUntil({ events.any { it.startsWith("Added") } })

        // Rescan with the folder unchanged: nothing new is found.
        val folder = dbAndGet { app().repository.observeFolders().first() }.single()
        viewModel.rescanFolder(folder)
        compose.idleUntil({ events.any { it == "Nothing new in that folder." } })

        val entries = dbAndGet { app().repository.observeEntries().first() }
        assertTrue("an unchanged rescan adds no rows", entries.map { it.label } == listOf("song-a.mp3"))
    }

    @Test
    fun rescanOfAFolderWithARevokedGrantAddsNothingAndDoesNotCrash() {
        val activity = compose.openContentPane()
        val viewModel = viewModelOf(activity)
        viewModel.folderChildren = { folderUri ->
            if (folderUri.toString() == TREE_URI) {
                listOf(FolderChild("song-a.mp3", isDirectory = false, childUri = "content://docs/document/audio:100"))
            } else emptyList()
        }

        val events = eventsOf(viewModel)
        viewModel.addFolderEntry(Uri.parse(TREE_URI))
        compose.idleUntil({ events.any { it.startsWith("Added") } })

        // The user revokes the folder's read grant: the document provider
        // query throws instead of returning an empty list.
        viewModel.folderChildren = { _ -> throw FileNotFoundException("grant revoked") }

        val folder = dbAndGet { app().repository.observeFolders().first() }.single()
        viewModel.rescanFolder(folder)
        compose.idleUntil({ events.any { it == "Nothing new in that folder." } })

        val entries = dbAndGet { app().repository.observeEntries().first() }
        assertTrue(
            "a revoked-grant rescan adds no rows",
            entries.map { it.label } == listOf("song-a.mp3"),
        )
    }

    companion object {
        /** A documents tree uri (folder selected with ":tree" access). */
        const val TREE_URI = "content://docs/document/audio%3A1:tree"
    }
}
