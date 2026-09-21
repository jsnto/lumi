package com.lumi.player.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import com.lumi.player.data.FolderChild
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
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Behavioral coverage for adding a whole folder: the content pane's
 * Folders section offers an "Add folder" + button on its title row, and
 * picking a folder tree
 * stores every supported audio file found at any subfolder depth as an
 * AUDIO entry, one per file, with the file's own document uri as the
 * payload. A folder without supported audio adds nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FolderAddUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private fun app(): LumiApp = ApplicationProvider.getApplicationContext()

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
        check(cond()) { "timed out waiting for the folder scan to settle" }
    }

    @Test
    fun contentPaneOffersAnAddFolderButtonBesideAddContent() {
        openContentPane()
        // The "Add content" pill is now the + button on the section's title
        // row, so it is found by its content description.
        compose.onNodeWithContentDescription("Add content").assertExists()
        // The folder button anchors the Folders section's title row as a
        // + button (found by its content description); on the small test
        // screen it may sit below the viewport, so existence is the
        // layout assertion.
        compose.onNodeWithContentDescription("Add folder").assertExists()
    }

    @Test
    fun addFolderStoresEveryAudioFileAtEveryDepth() {
        val viewModel = openContentPane()

        // A fake documents tree: the root holds a song, a subfolder (with
        // its own song deeper down) and an unsupported file.
        viewModel.folderChildren = { folderUri ->
            when (folderUri.toString()) {
                TREE_URI -> listOf(
                    FolderChild("song-a.mp3", isDirectory = false, childUri = "content://docs/document/audio:100"),
                    FolderChild("albums", isDirectory = true, childUri = "content://docs/document/audio:101"),
                    FolderChild("readme.txt", isDirectory = false, childUri = "content://docs/document/audio:102"),
                )
                "content://docs/document/audio:101" -> listOf(
                    FolderChild("song-b.flac", isDirectory = false, childUri = "content://docs/document/audio:200"),
                )
                else -> emptyList()
            }
        }

        // Collect the UI events on the view model's own (main) context so
        // the "Added" event is observed in order with the scan.
        val events = mutableListOf<String>()
        viewModel.viewModelScope.launch { viewModel.uiEvents.collect { e: String -> events += e } }

        viewModel.addFolderEntry(Uri.parse(TREE_URI))
        idleUntil({ events.any { it.startsWith("Added") } })

        val entries = dbAndGet { app().repository.observeEntries().first() }
        assertEquals(
            "the folder's audio files become content, unsupported files skipped",
            listOf("song-a.mp3", "song-b.flac"),
            entries.map { it.label },
        )
        assertEquals(listOf("audio", "audio"), entries.map { it.type })
        // Each entry's payload is the file's own document uri, so playback
        // opens the file directly.
        assertEquals(
            "content://docs/document/audio:100",
            entries.first { it.label == "song-a.mp3" }.payload,
        )
        assertEquals(
            "content://docs/document/audio:200",
            entries.first { it.label == "song-b.flac" }.payload,
        )

        // The imported songs stay OUT of the individual content list: the
        // pane shows the folder's row (the tree uri as its label - no
        // provider is registered to name it) and the individual section's
        // empty state.
        compose.waitForIdle()
        compose.onNodeWithText(TREE_URI).assertExists()
        compose.onNodeWithText("Nothing here yet. Add a song.").assertExists()
    }

    @Test
    fun reAddingTheSameFolderAddsNoDuplicateRows() {
        val viewModel = openContentPane()
        viewModel.folderChildren = { folderUri ->
            if (folderUri.toString() == TREE_URI) {
                listOf(
                    FolderChild("song-a.mp3", isDirectory = false, childUri = "content://docs/document/audio:100"),
                    FolderChild("song-b.mp3", isDirectory = false, childUri = "content://docs/document/audio:101"),
                )
            } else {
                emptyList()
            }
        }

        val events = mutableListOf<String>()
        viewModel.viewModelScope.launch { viewModel.uiEvents.collect { e: String -> events += e } }

        viewModel.addFolderEntry(Uri.parse(TREE_URI))
        idleUntil({ events.any { it.startsWith("Added") } })

        // Picking the same folder again must not duplicate its songs.
        viewModel.addFolderEntry(Uri.parse(TREE_URI))
        idleUntil({ events.any { it.startsWith("All 2 songs") } })

        val entries = dbAndGet { app().repository.observeEntries().first() }
        assertEquals(
            "the folder's songs appear once, not twice, after the second pick",
            listOf("song-a.mp3", "song-b.mp3"),
            entries.map { it.label },
        )
    }

    @Test
    fun reAddingASingleSongFolderUsesTheSingularCopyAndAddsNoDuplicateRow() {
        val viewModel = openContentPane()
        viewModel.folderChildren = { folderUri ->
            if (folderUri.toString() == TREE_URI) {
                listOf(FolderChild("song-a.mp3", isDirectory = false, childUri = "content://docs/document/audio:100"))
            } else {
                emptyList()
            }
        }

        val events = mutableListOf<String>()
        viewModel.viewModelScope.launch { viewModel.uiEvents.collect { e: String -> events += e } }

        viewModel.addFolderEntry(Uri.parse(TREE_URI))
        idleUntil({ events.any { it == "Added 1 song from the folder." } })

        // Re-picking the one-song folder keeps the copy singular in both
        // branches: the add event and the already-stored event.
        viewModel.addFolderEntry(Uri.parse(TREE_URI))
        idleUntil({ events.any { it == "The song from the folder is already in your content list." } })

        val entries = dbAndGet { app().repository.observeEntries().first() }
        assertEquals(
            "the one song appears once, not twice, after the second pick",
            listOf("song-a.mp3"),
            entries.map { it.label },
        )
    }

    @Test
    fun folderContainingAnAlreadyAddedFileAddsNoDuplicateRow() {
        val viewModel = openContentPane()
        // song-a was already added through the file picker; the later folder
        // pick contains it again.
        val events = mutableListOf<String>()
        viewModel.viewModelScope.launch { viewModel.uiEvents.collect { e: String -> events += e } }
        viewModel.addFileEntry(Uri.parse("content://docs/document/audio:100"), EntryType.AUDIO, "song-a.mp3")
        idleUntil({ events.any { it == "Song added!" } })

        viewModel.folderChildren = { folderUri ->
            if (folderUri.toString() == TREE_URI) {
                listOf(
                    FolderChild("song-a.mp3", isDirectory = false, childUri = "content://docs/document/audio:100"),
                    FolderChild("song-b.mp3", isDirectory = false, childUri = "content://docs/document/audio:101"),
                )
            } else {
                emptyList()
            }
        }

        viewModel.addFolderEntry(Uri.parse(TREE_URI))
        idleUntil({ events.any { it == "Added 1 of 2 songs from the folder." } })

        val entries = dbAndGet { app().repository.observeEntries().first() }
        assertEquals(
            "the folder adds only the file it brought that was not stored yet",
            listOf("song-a.mp3", "song-b.mp3"),
            entries.map { it.label },
        )
    }

    @Test
    fun rePickingTheSameFileThroughTheFilePickerAddsNoDuplicateRow() {
        val viewModel = openContentPane()

        val events = mutableListOf<String>()
        viewModel.viewModelScope.launch { viewModel.uiEvents.collect { e: String -> events += e } }

        val fileUri = Uri.parse("content://docs/document/audio:100")
        viewModel.addFileEntry(fileUri, EntryType.AUDIO, "song-a.mp3")
        idleUntil({ events.any { it == "Song added!" } })

        // Picking the very same file again must not create a second row.
        viewModel.addFileEntry(fileUri, EntryType.AUDIO, "song-a.mp3")
        idleUntil({ events.any { it == "That song is already in your content list." } })

        val entries = dbAndGet { app().repository.observeEntries().first() }
        assertEquals(
            "the file appears once, not twice, after the second pick",
            listOf("song-a.mp3"),
            entries.map { it.label },
        )
    }

    /**
     * A documents provider that answers every query with a zero-row cursor:
     * a folder whose root has no direct children. The label read (the
     * folder's own tree-scoped document row) and the root listing (the
     * folder's tree-scoped children listing) can both answer a zero-row
     * cursor, so the guarded reads are what let the add complete - and add
     * nothing.
     */
    private fun registerEmptyTreeProvider() {
        val provider = object : ContentProvider() {
            override fun onCreate(): Boolean = true
            override fun query(
                uri: Uri,
                projection: Array<out String>?,
                selection: String?,
                selectionArgs: Array<out String>?,
                sortOrder: String?,
            ): Cursor? = MatrixCursor(arrayOf("document_id", "_display_name", "flags", "mime_type"))
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
            override fun update(
                uri: Uri,
                values: ContentValues?,
                selection: String?,
                selectionArgs: Array<out String>?,
            ): Int = 0
        }
        ShadowContentResolver.registerProviderInternal("docs", provider)
    }

    @Test
    fun addFolderWhoseRootHasNoDirectChildrenDoesNotCrash() {
        val viewModel = openContentPane()
        registerEmptyTreeProvider()

        val events = mutableListOf<String>()
        viewModel.viewModelScope.launch { viewModel.uiEvents.collect { e: String -> events += e } }

        // The crash input: the label read and the root listing both see a
        // zero-row cursor (no name, no direct children). Must complete -
        // and add nothing.
        viewModel.addFolderEntry(Uri.parse(TREE_URI))
        idleUntil({ events.any { it.startsWith("No songs found") } })

        // The folder is remembered (so it stays rescannable) with the tree
        // uri as its label fallback (the folder's own row is empty), and no
        // entries were stored.
        val folders = dbAndGet { app().repository.observeFolders().first() }
        assertEquals("the empty folder is remembered once", 1, folders.size)
        assertEquals(TREE_URI, folders.single().uri)
        assertEquals("no name readable from the empty tree, so the uri is the label", TREE_URI, folders.single().label)
        val entries = dbAndGet { app().repository.observeEntries().first() }
        assertTrue("an empty folder adds no entries", entries.isEmpty())
    }

    @Test
    fun addFolderWithoutSupportedAudioAddsNothing() {
        val viewModel = openContentPane()
        viewModel.folderChildren = { folderUri ->
            if (folderUri.toString() == TREE_URI) {
                listOf(
                    FolderChild("notes.txt", isDirectory = false, childUri = "content://docs/document/audio:102"),
                    FolderChild("empty-sub", isDirectory = true, childUri = "content://docs/document/audio:103"),
                )
            } else {
                emptyList()
            }
        }

        val events = mutableListOf<String>()
        viewModel.viewModelScope.launch { viewModel.uiEvents.collect { e: String -> events += e } }

        viewModel.addFolderEntry(Uri.parse(TREE_URI))
        idleUntil({ events.any { it.startsWith("No songs found") } })

        val entries = dbAndGet { app().repository.observeEntries().first() }
        assertTrue("a folder without supported audio must add nothing", entries.isEmpty())
    }

    companion object {
        /** A documents tree uri (folder selected with ":tree" access). */
        const val TREE_URI = "content://docs/document/audio%3A1:tree"
    }
}
