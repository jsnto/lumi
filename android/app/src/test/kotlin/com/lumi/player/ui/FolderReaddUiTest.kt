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
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import com.lumi.player.ui.testutil.idleUntil
import com.lumi.player.ui.testutil.app
import com.lumi.player.ui.testutil.dbAndGet
import com.lumi.player.ui.testutil.clickMenuRow
import com.lumi.player.ui.testutil.clickSettings
import com.lumi.player.ui.testutil.viewModelOf

/**
 * Drives the captain's on-device sequence for a folder that already contains
 * audio, through the REAL documents-provider cursor path (no faked
 * [LumiViewModel.folderChildren] seam): add a folder whose tree holds songs
 * at the root AND one level down, reopen the app, rescan the remembered
 * folder, and re-add (re-pick) it - the audio entries must appear, exactly
 * once. Also pins the backfill (the migration's best-effort half): rows
 * stored before the folder-origin marker existed get marked on app start
 * when the tree is still readable, and degrade into the individual content
 * list (visible, not lost) when it is not.
 *
 * A realistic documents provider is registered by authority and answers the
 * folder's tree-scoped children listings (`tree/<treeId>/document/<docId>/children`
 * - the walk's root and each subfolder's listing) with real cursors, so the
 * cursor parsing and the child document uri construction run for real. The
 * tree uri uses the same shape the SAF picker returns (`/tree/<docId>`),
 * with a root docId that contains a colon, like `primary:Music`.
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
        markSetupCompleteForTests()
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
        ShadowContentResolver.registerProviderInternal("docs", FakeDocumentsProvider())
    }

    private var activityController: ActivityController<MainActivity> = buildActivity(MainActivity::class.java)

    /** Whether [activityController]'s activity has been created (and its view model with it). */
    private var activityCreated = false

    /** Opens the Content pane and returns its (activity-scoped) view model. */
    private fun openContentPane(): LumiViewModel {
        // The activity (and with it the view model's init) is created on the
        // first open or by [reopenApp]; a controller whose activity exists is
        // reused instead of set up twice.
        val activity =
            if (activityCreated) {
                activityController.get()
            } else {
                activityController.setup()
                activityCreated = true
                activityController.get()
            }
        compose.clickSettings()
        compose.clickMenuRow("Content")
        return viewModelOf(activity)
    }

    /** Simulates reopening the app: a fresh, created activity (so the view model's init runs - including the backfill - and a fresh view model), the same database. */
    private fun reopenApp() {
        activityController.destroy()
        activityController = buildActivity(MainActivity::class.java).setup()
        activityCreated = true
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
        compose.idleUntil({ events1.any { it == "Added 2 songs from the folder." } })

        val stored1 = dbAndGet { app().repository.observeEntries().first() }
        assertEquals(
            "the walk stores both songs (root and nested), unsupported file skipped",
            listOf("track 01.mp3", "deep 01.flac"),
            stored1.map { it.label },
        )
        assertEquals(listOf("audio", "audio"), stored1.map { it.type })
        assertTrue(
            "each entry's payload is the file's own tree-scoped document uri (openable under the tree grant alone)",
            stored1.all {
                it.payload.startsWith("content://docs/tree/primary%3AMusic/document/") &&
                    Uri.decode(it.payload.removePrefix("content://docs/tree/primary%3AMusic/document/"))
                        .startsWith("primary:Music/")
            },
        )
        val folders1 = dbAndGet { app().repository.observeFolders().first() }
        assertEquals("the folder is remembered once", 1, folders1.size)

        // Session 2: the app is reopened - fresh activity, fresh view
        // model, the same database. The stored audio must be visible - in
        // the folder's own page: the folder-imported songs stay OUT of the
        // individual content list (that list now holds individually added
        // content only). (The remembered-folder row carries the folder's own
        // name - the label read at the folder's own document uri - so the
        // folder's row text is unique on the pane.)
        reopenApp()
        val viewModel2 = openContentPane()
        // The individual content list holds no folder-imported rows...
        compose.onNodeWithText("Nothing here yet. Add a song.").assertExists()
        // ...the folder's page does (the row opens it).
        compose.onNodeWithText("Music").performClick()
        compose.waitForIdle()
        // The page's song list settles after the folder's walk (async).
        compose.idleUntil({ compose.onAllNodesWithText("track 01.mp3").fetchSemanticsNodes().isNotEmpty() })
        assertTrue(
            "the first song is visible on the folder's page after the reopen",
            compose.onAllNodesWithText("track 01.mp3").fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "the nested song is visible on the folder's page after the reopen",
            compose.onAllNodesWithText("deep 01.flac").fetchSemanticsNodes().isNotEmpty(),
        )
        // The page's back unwinds one level to the content pane, where the
        // rescan / re-add sequence runs.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()

        // Rescan the remembered folder: it is unchanged, so nothing new.
        val events2 = eventsOf(viewModel2)
        val folder = dbAndGet { app().repository.observeFolders().first() }.single()
        assertEquals("the remembered folder kept its tree uri", TREE_URI, folder.uri)
        viewModel2.rescanFolder(folder)
        compose.idleUntil({ events2.any { it == "Nothing new in that folder." } })

        // Re-add (re-pick) the already-known folder: every song is already
        // stored, so nothing is duplicated - and nothing is lost.
        viewModel2.addFolderEntry(Uri.parse(TREE_URI))
        compose.idleUntil({ events2.any { it == "All 2 songs from the folder are already in your content list." } })

        val stored2 = dbAndGet { app().repository.observeEntries().first() }
        assertEquals(
            "the audio entries appear exactly once after rescan + re-add",
            listOf("track 01.mp3", "deep 01.flac"),
            stored2.map { it.label },
        )
        assertEquals("the re-add stored no duplicate folder row", 1, dbAndGet { app().repository.observeFolders().first() }.size)
        // The songs are still visible on the folder's page after rescan +
        // re-add (and still OUT of the individual content list).
        compose.onNodeWithText("Nothing here yet. Add a song.").assertExists()
        compose.onNodeWithText("Music").performClick()
        compose.waitForIdle()
        compose.idleUntil({ compose.onAllNodesWithText("track 01.mp3").fetchSemanticsNodes().isNotEmpty() })
        assertTrue(
            "the songs are still visible on the folder's page after rescan + re-add",
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

        // The provider's folder query throws: the document provider denies
        // the listing the same way a revoked grant does.
        ShadowContentResolver.registerProviderInternal("docs", ThrowingTreeProvider())

        viewModel.addFolderEntry(Uri.parse(TREE_URI))
        compose.idleUntil({ events.any { it == "Couldn't read that folder's songs." } })

        val folders = dbAndGet { app().repository.observeFolders().first() }
        assertEquals("the folder is still remembered for rescans", 1, folders.size)
        val entries = dbAndGet { app().repository.observeEntries().first() }
        assertTrue("a failed read stores no entries", entries.isEmpty())
    }

    /**
     * Backfill (the migration's best-effort half): rows stored before the
     * folder-origin marker existed (the v3 rows - a remembered folder whose
     * songs carry no marker) get marked on app start by re-walking the
     * folder's tree through the same seam the folder page uses. The songs
     * leave the individual content list (they live on the folder's page
     * now) - no data loss, just a corrected origin.
     */
    @Test
    fun theBackfillMarksExistingFolderRowsFromAPreMarkerDatabase() {
        // Session 1: seed the pre-marker state - a remembered folder and
        // its stored songs WITHOUT the marker, exactly as the v3 rows look
        // after the schema migration.
        val folderId = dbAndGet {
            val folder = app().repository.rememberFolder(TREE_URI, "Music")
            app().repository.addEntry(EntryType.AUDIO, "track 01.mp3", TRACK_PAYLOAD)
            app().repository.addEntry(EntryType.AUDIO, "deep 01.flac", DEEP_PAYLOAD)
            folder.id
        }

        // Session 2: the app reopens with the tree readable - the view
        // model's init backfill walks the folder and marks the rows.
        ShadowContentResolver.registerProviderInternal("docs", FakeDocumentsProvider())
        reopenApp()

        compose.idleUntil({
            dbAndGet { app().repository.entriesByPayloads(setOf(TRACK_PAYLOAD, DEEP_PAYLOAD)) }
                .all { it.folderId != null }
        })
        val marked = dbAndGet { app().repository.entriesByPayloads(setOf(TRACK_PAYLOAD, DEEP_PAYLOAD)) }
        assertEquals("the backfill keeps both songs", 2, marked.size)
        assertTrue(
            "the backfill stamps the songs with the folder's id",
            marked.all { it.folderId == folderId },
        )
        assertTrue(
            "the marked songs left the individual content list",
            dbAndGet { app().repository.observeIndividualEntries().first() }.isEmpty(),
        )

        // And the pane reflects it: the folder's row opens the page that
        // lists both songs, while the individual content list is empty.
        openContentPane()
        compose.onNodeWithText("Nothing here yet. Add a song.").assertExists()
        compose.onNodeWithText("Music").performClick()
        compose.waitForIdle()
        compose.idleUntil({ compose.onAllNodesWithText("track 01.mp3").fetchSemanticsNodes().isNotEmpty() })
        assertTrue(
            "the folder's page lists both backfilled songs",
            compose.onAllNodesWithText("track 01.mp3").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("deep 01.flac").fetchSemanticsNodes().isNotEmpty(),
        )
    }

    /**
     * Backfill degradation: a remembered folder whose tree can no longer be
     * read (a revoked grant) is left as-is - its rows keep the unmarked
     * state, so its songs show in the individual content list (visible, not
     * lost), and the app does not crash over it.
     */
    @Test
    fun theBackfillLeavesUnreadableFolderRowsInTheIndividualList() {
        dbAndGet {
            app().repository.rememberFolder(TREE_URI, "Music")
            app().repository.addEntry(EntryType.AUDIO, "track 01.mp3", TRACK_PAYLOAD)
            app().repository.addEntry(EntryType.AUDIO, "deep 01.flac", DEEP_PAYLOAD)
            true
        }

        // The tree's grant is gone by the time the app reopens: the
        // backfill's walk throws, and the rows stay unmarked.
        ShadowContentResolver.registerProviderInternal("docs", ThrowingTreeProvider())
        reopenApp()

        val individual = dbAndGet { app().repository.observeIndividualEntries().first() }
        assertEquals(
            "the unreadable folder's songs stay in the individual list (degraded, not lost)",
            listOf("track 01.mp3", "deep 01.flac"),
            individual.map { it.label },
        )

        // And the pane shows them there (the individual section, below the
        // Folders section - existence is the layout assertion).
        openContentPane()
        compose.onNodeWithText("track 01.mp3").assertExists()
        compose.onNodeWithText("deep 01.flac").assertExists()
    }

    /**
     * A realistic SAF documents provider for the tree `primary:Music`:
     *
     * ```
     * primary:Music/
     *   track 01.mp3     (file, audio/mpeg)
     *   Album/           (subfolder)
     *     deep 01.flac   (file, audio/x-flac)
     *   readme.txt       (file, not audio)
     * ```
     *
     * It answers the real SAF tree-grant grammar: the tree-scoped
     * `tree/<treeId>/document/<docId>` with the document's own single row
     * (files AND directories, gated by the enforceTree descendant check),
     * and `tree/<treeId>/document/<docId>/children` with the document's
     * direct children only (no root row). Directory rows carry the
     * `vnd.android.document/directory` mime type - the way the real
     * providers mark directories. The treeless `document/...` shapes the
     * pre-fix seam queried are refused with the SAF permission layer's
     * SecurityException - the device-proven denial.
     */
    private class FakeDocumentsProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        private val rootChildren = arrayOf(
            row("primary:Music/track 01.mp3", "track 01.mp3", 0L, "audio/mpeg"),
            row("primary:Music/Album", "Album", 0L, MIME_DIR),
            row("primary:Music/readme.txt", "readme.txt", 0L, "text/plain"),
        )
        private val albumChildren = arrayOf(
            row("primary:Music/Album/deep 01.flac", "deep 01.flac", 0L, "audio/x-flac"),
        )

        private fun row(docId: String, displayName: String, flags: Long, mime: String): Array<Any?> =
            arrayOf<Any?>(docId, displayName, flags, mime)

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            val segments = uri.pathSegments
            return when (val first = segments.getOrNull(0)) {
                // The tree-scoped shapes: the only ones the SAF permission
                // layer authorizes under the tree grant (device-proven on
                // Android 14).
                "tree" -> {
                    val treeId = segments.getOrNull(1)
                    if (segments.getOrNull(2) != "document" || segments.size !in intArrayOf(4, 5)) {
                        throw UnsupportedOperationException("Unsupported Uri $uri")
                    }
                    val docId = segments.getOrNull(3)
                    if (treeId == null || docId == null || !(docId == treeId || docId.startsWith("$treeId/"))) {
                        throw SecurityException("Not a descendant of $uri")
                    }
                    if (segments.size == 4) {
                        // The document's own single row (the label read).
                        MatrixCursor(COLUMNS).also { c ->
                            c.addRow(
                                when (docId) {
                                    "primary:Music" -> row("primary:Music", "Music", 0L, MIME_DIR)
                                    "primary:Music/Album" -> row("primary:Music/Album", "Album", 0L, MIME_DIR)
                                    else -> row(
                                        docId,
                                        docId.substringAfterLast('/'),
                                        0L,
                                        "audio/mpeg",
                                    )
                                },
                            )
                        }
                    } else {
                        // The document's direct children only (no root row).
                        when (docId) {
                            "primary:Music" -> MatrixCursor(COLUMNS, rootChildren.size).also { c ->
                                rootChildren.forEach { c.addRow(it) }
                            }
                            "primary:Music/Album" -> MatrixCursor(COLUMNS, albumChildren.size).also { c ->
                                albumChildren.forEach { c.addRow(it) }
                            }
                            else -> MatrixCursor(COLUMNS)
                        }
                    }
                }
                // The treeless shapes the pre-fix seam queried: the SAF
                // permission layer denies them under the tree grant - the
                // device-proven rejection.
                "document" -> throw SecurityException(
                    "Permission Denial: reading $uri requires that you obtain access using ACTION_OPEN_DOCUMENT or related APIs",
                )
                // The tree uri itself and any child/... listing have no
                // matcher entry in the real provider - the fixed seam never
                // issues them.
                else -> throw UnsupportedOperationException("Unsupported Uri $uri")
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
            const val MIME_DIR = "vnd.android.document/directory"
            val COLUMNS = arrayOf("document_id", "_display_name", "flags", "mime_type")
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
        const val TREE_URI = "content://docs/tree/primary%3AMusic"

        /** The walked payload of the root song (its tree-scoped document uri). */
        const val TRACK_PAYLOAD =
            "content://docs/tree/primary%3AMusic/document/primary%3AMusic%2Ftrack%2001.mp3"

        /** The walked payload of the nested song (its tree-scoped document uri). */
        const val DEEP_PAYLOAD =
            "content://docs/tree/primary%3AMusic/document/primary%3AMusic%2FAlbum%2Fdeep%2001.flac"
    }
}
