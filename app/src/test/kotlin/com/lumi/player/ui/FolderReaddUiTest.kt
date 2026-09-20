package com.lumi.player.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric.buildActivity
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLooper

/**
 * Drives the captain's on-device sequence for a folder that already contains
 * audio, through the REAL documents-provider cursor path (no faked
 * [LumiViewModel.folderChildren] seam): add a folder whose tree holds songs
 * at the root AND one level down, reopen the app, rescan the remembered
 * folder, and re-add (re-pick) it - the audio entries must appear, exactly
 * once.
 *
 * A realistic documents provider is registered by authority and answers the
 * tree uri's children listing (the walk's root) and the subfolder's
 * children with real cursors, so the cursor parsing and the child document
 * uri construction run for real. The tree uri uses the same shape the SAF
 * picker returns (`/tree/<docId>:tree`), with a root docId that contains a
 * colon, like `primary:Music`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FolderReaddUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
        ShadowContentResolver.registerProviderInternal("docs", FakeDocumentsProvider())
    }

    private fun app(): LumiApp = ApplicationProvider.getApplicationContext()

    private var activityController: ActivityController<MainActivity> = buildActivity(MainActivity::class.java)

    /** Opens the Content pane and returns its (activity-scoped) view model. */
    private fun openContentPane(): LumiViewModel {
        val activity = activityController.setup().get()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Content").performClick()
        compose.waitForIdle()
        val field = MainActivity::class.java.getDeclaredField("viewModel")
        field.isAccessible = true
        return field.get(activity) as LumiViewModel
    }

    /** Simulates reopening the app: a fresh activity (and view model), the same database. */
    private fun reopenApp() {
        activityController.destroy()
        activityController = buildActivity(MainActivity::class.java)
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

    /** The captain's exact sequence: add, reopen, rescan, re-add - the audio must appear. */
    @Test
    fun addReopenRescanReAddKeepsTheFolderAudioVisible() {
        // Session 1: add the folder. The walk goes through the real cursor
        // code against the registered provider (songs at the root and one
        // level down; an unsupported file is skipped).
        val viewModel1 = openContentPane()
        val events1 = eventsOf(viewModel1)
        viewModel1.addFolderEntry(Uri.parse(TREE_URI))
        idleUntil({ events1.any { it == "Added 2 songs from the folder." } })

        val stored1 = dbAndGet { app().repository.observeEntries().first() }
        assertEquals(
            "the walk stores both songs (root and nested), unsupported file skipped",
            listOf("track 01.mp3", "deep 01.flac"),
            stored1.map { it.label },
        )
        assertEquals(listOf("audio", "audio"), stored1.map { it.type })
        assertTrue(
            "each entry's payload is the file's own document uri",
            stored1.all {
                it.payload.startsWith("content://docs/document/") &&
                    Uri.decode(it.payload.removePrefix("content://docs/document/"))
                        .startsWith("primary:Music/")
            },
        )
        val folders1 = dbAndGet { app().repository.observeFolders().first() }
        assertEquals("the folder is remembered once", 1, folders1.size)

        // Session 2: the app is reopened - fresh activity, fresh view model,
        // the same database. The stored audio must be visible in the content
        // pane. ("track 01.mp3" also names the remembered-folder row - the
        // label's first-child quirk - so existence is asserted over all nodes.)
        reopenApp()
        val viewModel2 = openContentPane()
        assertTrue(
            "the first song is visible in the content pane after the reopen",
            compose.onAllNodesWithText("track 01.mp3").fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "the nested song is visible in the content pane after the reopen",
            compose.onAllNodesWithText("deep 01.flac").fetchSemanticsNodes().isNotEmpty(),
        )

        // Rescan the remembered folder: it is unchanged, so nothing new.
        val events2 = eventsOf(viewModel2)
        val folder = dbAndGet { app().repository.observeFolders().first() }.single()
        assertEquals("the remembered folder kept its tree uri", TREE_URI, folder.uri)
        viewModel2.rescanFolder(folder)
        idleUntil({ events2.any { it == "Nothing new in that folder." } })

        // Re-add (re-pick) the already-known folder: every song is already
        // stored, so nothing is duplicated - and nothing is lost.
        viewModel2.addFolderEntry(Uri.parse(TREE_URI))
        idleUntil({ events2.any { it == "All 2 songs from the folder are already in your content list." } })

        val stored2 = dbAndGet { app().repository.observeEntries().first() }
        assertEquals(
            "the audio entries appear exactly once after rescan + re-add",
            listOf("track 01.mp3", "deep 01.flac"),
            stored2.map { it.label },
        )
        assertEquals("the re-add stored no duplicate folder row", 1, dbAndGet { app().repository.observeFolders().first() }.size)
        assertTrue(
            "the songs are still visible in the content pane after rescan + re-add",
            compose.onAllNodesWithText("track 01.mp3").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("deep 01.flac").fetchSemanticsNodes().isNotEmpty(),
        )
    }

    /**
     * The failure the guard exists for: the folder's tree can no longer be
     * read (e.g. its SAF grant was revoked after the add). The add path must
     * not crash the app process over it - it remembers the folder (so the
     * Rescan affordance stays) and reports the failure instead of storing
     * nothing silently.
     */
    @Test
    fun reAddingAFolderWhoseTreeReadFailsDoesNotCrash() {
        val viewModel = openContentPane()
        val events = eventsOf(viewModel)

        // The provider's tree query throws: the document provider denies the
        // listing the same way a revoked grant does.
        ShadowContentResolver.registerProviderInternal("docs", ThrowingTreeProvider())

        viewModel.addFolderEntry(Uri.parse(TREE_URI))
        idleUntil({ events.any { it == "Couldn't read that folder's songs." } })

        val folders = dbAndGet { app().repository.observeFolders().first() }
        assertEquals("the folder is still remembered for rescans", 1, folders.size)
        val entries = dbAndGet { app().repository.observeEntries().first() }
        assertTrue("a failed read stores no entries", entries.isEmpty())
    }

    /**
     * A realistic SAF documents provider for the tree `primary:Music`:
     *
     * ```
     * primary:Music/
     *   track 01.mp3     (file)
     *   Album/           (subfolder)
     *     deep 01.flac   (file)
     *   readme.txt       (file, not audio)
     * ```
     *
     * A query on the tree uri lists the root's children; a query on a
     * subfolder's document uri lists that subfolder's children; a query on
     * a file's document uri answers with the file's own row - the shape the
     * real ExternalStorageProvider takes.
     *
     * android.net.Uri has no `:`-fragment parsing, so the tree uri's
     * `:tree`/`:openable` suffix is part of the LAST PATH SEGMENT (the same
     * way the real picker's tree uris look) - the root docId is recovered by
     * stripping it, exactly as the system provider does.
     */
    private class FakeDocumentsProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        private val rootRows = arrayOf(
            row("primary:Music/track 01.mp3", "track 01.mp3", 0L),
            row("primary:Music/Album", "Album", 1L),
            row("primary:Music/readme.txt", "readme.txt", 0L),
        )
        private val albumRows = arrayOf(
            row("primary:Music/Album/deep 01.flac", "deep 01.flac", 0L),
        )

        private fun row(docId: String, displayName: String, flags: Long): Array<Any?> =
            arrayOf<Any?>(docId, displayName, flags)

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            val path = uri.encodedPath ?: return null
            val docId = when {
                path.startsWith("/tree/") ->
                    Uri.decode(path.removePrefix("/tree/"))
                        .removeSuffix(":tree")
                        .removeSuffix(":openable")
                path.startsWith("/document/") -> Uri.decode(path.removePrefix("/document/"))
                else -> return null
            }
            return when (docId) {
                "primary:Music" -> MatrixCursor(COLUMNS, rootRows.size).also { c ->
                    rootRows.forEach { c.addRow(it) }
                }
                "primary:Music/Album" -> MatrixCursor(COLUMNS, albumRows.size).also { c ->
                    albumRows.forEach { c.addRow(it) }
                }
                else -> MatrixCursor(COLUMNS).also { c ->
                    c.addRow(row(docId, docId.substringAfterLast('/'), 0L))
                }
            }
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        private companion object {
            val COLUMNS = arrayOf("_id", "_display_name", "_flags")
        }
    }

    /** A provider whose tree listing always throws (a revoked grant). */
    private class ThrowingTreeProvider : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = throw SecurityException("grant revoked")
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

    companion object {
        /** A documents tree uri in the shape the SAF picker returns. */
        const val TREE_URI = "content://docs/tree/primary%3AMusic:tree"
    }
}
