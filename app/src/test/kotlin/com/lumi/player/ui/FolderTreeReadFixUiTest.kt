package com.lumi.player.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper

/**
 * The on-device read failure the folder-import fix targets, pinned against
 * a provider that enforces the REAL documents-provider uri grammar.
 *
 * The pre-fix seam built child uris with a raw docId
 * (`Uri.Builder().path("document/$docId")` leaves the real docId's `/` raw -
 * e.g. `document/primary%3AMusic/track%2001.mp3` parses to THREE path
 * segments) and recursed into the tree's own root row. The device's
 * `DocumentsProvider` takes the docId from exactly the second path segment
 * (`segmentCount != 2` -> `IllegalArgumentException: Invalid document id`)
 * and lists a subfolder's children at the tree's `child/<treeId>:tree/<docId>`
 * uri, never at the subfolder's document uri (which answers with the
 * folder's own single row). Every folder scan therefore died on the first
 * recursion - even a flat folder of .mp3 files - and surfaced as the
 * generic "Couldn't read that folder's songs."
 *
 * [StrictDocumentsProvider] encodes that grammar and is registered by
 * authority; the PRODUCTION [LumiViewModel.folderChildren] seam (NOT
 * replaced) is driven against it. The counterfactual tests pin the
 * rejection of the old uri shapes; the import tests prove the fixed seam
 * walks the same tree end-to-end (root files, subfolder files, the stored
 * payload shape, and the rescan no-op).
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FolderTreeReadFixUiTest {

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

    /**
     * The captain's case, pinned against the real provider's grammar: a
     * .mp3-only folder (including a subfolder) adds cleanly through the
     * production cursor path, every song is stored exactly once, and the
     * stored payload is a well-formed document uri.
     */
    @Test
    fun aFolderOfMp3FilesImportsEverySongIncludingSubfolders() {
        val viewModel = openContentPane()
        val events = eventsOf(viewModel)

        // The strict provider is registered by authority, so the production
        // seam's queries (tree, child, document uris) hit its grammar.
        ShadowContentResolver.registerProviderInternal("docs", StrictDocumentsProvider(withSubfolder = true))

        viewModel.addFolderEntry(Uri.parse(TREE_URI))
        idleUntil({ events.any { it == "Added 3 songs from the folder." } })

        val entries = dbAndGet { app().repository.observeEntries().first() }
        assertEquals(
            listOf("track 01.mp3", "track 02.mp3", "track 03.mp3"),
            entries.map { it.label },
        )

        // The stored payload is a well-formed document uri - exactly two
        // path segments, the docId percent-encoded into one - so the player
        // can open it (the pre-fix payload was a three-segment uri no
        // documents provider can parse).
        assertEquals(
            "content://docs/document/primary%3AMusic%2Ftrack%2001.mp3",
            entries.first().payload,
        )
        val payload = Uri.parse(entries.first().payload)
        assertEquals(2, payload.pathSegments.size)
        assertEquals("document", payload.pathSegments[0])
        assertEquals("primary:Music/track 01.mp3", payload.pathSegments[1])

        // The folder is remembered for rescans.
        val folders = dbAndGet { app().repository.observeFolders().first() }
        assertEquals(1, folders.size)
        assertEquals(TREE_URI, folders.single().uri)
    }

    /**
     * The flat variant - no subfolders at all: before the fix the scan still
     * died on the tree's own root row (its "children" listing uri is invalid)
     * and reported the generic error; the root row is now skipped, so a
     * flat folder of .mp3 files imports cleanly.
     */
    @Test
    fun aFlatFolderOfMp3FilesImportsEverySong() {
        val viewModel = openContentPane()
        val events = eventsOf(viewModel)

        ShadowContentResolver.registerProviderInternal("docs", StrictDocumentsProvider(withSubfolder = false))

        viewModel.addFolderEntry(Uri.parse(TREE_URI))
        idleUntil({ events.any { it == "Added 2 songs from the folder." } })

        val entries = dbAndGet { app().repository.observeEntries().first() }
        assertEquals(listOf("track 01.mp3", "track 02.mp3"), entries.map { it.label })
    }

    /**
     * The picker may append ":openable" to the tree uri's last path segment
     * (after the ":tree" suffix - android.net.Uri has no ':'-fragment
     * parsing, so it rides in the path). The strict provider answers ONLY
     * the canonical ":tree" shape - the tree query and the subfolder's
     * child uri are both refused unless the seam strips the suffix before
     * querying and before building the child uri - so this imports only if
     * the normalization happens. The folders table still stores the picker
     * uri verbatim (normalization is at query time only), and the label
     * read goes through the canonical shape too (a raw query would be
     * refused and the label would fall back to the uri string).
     */
    @Test
    fun aTreeUriWithAnOpenableSuffixImportsThroughTheCanonicalShape() {
        val viewModel = openContentPane()
        val events = eventsOf(viewModel)

        ShadowContentResolver.registerProviderInternal("docs", StrictDocumentsProvider(withSubfolder = true))

        viewModel.addFolderEntry(Uri.parse(TREE_URI_OPENABLE))
        idleUntil({ events.any { it == "Added 3 songs from the folder." } })

        val entries = dbAndGet { app().repository.observeEntries().first() }
        assertEquals(
            listOf("track 01.mp3", "track 02.mp3", "track 03.mp3"),
            entries.map { it.label },
        )
        assertEquals(
            "content://docs/document/primary%3AMusic%2Ftrack%2001.mp3",
            entries.first().payload,
        )
        val payload = Uri.parse(entries.first().payload)
        assertEquals(2, payload.pathSegments.size)

        // The folder row stores the picker uri verbatim; the label is the
        // folder's own name, read through the canonical ":tree" shape.
        val folders = dbAndGet { app().repository.observeFolders().first() }
        assertEquals(1, folders.size)
        assertEquals(TREE_URI_OPENABLE, folders.single().uri)
        assertEquals("Music", folders.single().label)
    }

    /**
     * The rescan goes through the same uri grammar: a rescan of the unchanged
     * folder answers the tree and child listings again and adds nothing.
     */
    @Test
    fun rescanOfAnUnchangedFolderGoesThroughTheSameGrammar() {
        val viewModel = openContentPane()
        val events = eventsOf(viewModel)

        ShadowContentResolver.registerProviderInternal("docs", StrictDocumentsProvider(withSubfolder = true))

        viewModel.addFolderEntry(Uri.parse(TREE_URI))
        idleUntil({ events.any { it == "Added 3 songs from the folder." } })

        val folder = dbAndGet { app().repository.observeFolders().first() }.single()
        viewModel.rescanFolder(folder)
        idleUntil({ events.any { it == "Nothing new in that folder." } })

        val entries = dbAndGet { app().repository.observeEntries().first() }
        assertEquals(3, entries.size)
    }

    /**
     * The counterfactual: the uri shapes the PRE-FIX seam built are rejected
     * by the provider's grammar. That rejection (not a revoked grant) is
     * what the captain's .mp3 folder threw on the device.
     */
    @Test
    fun theOldUnencodedUriShapesAreRejectedByTheProvider() {
        val provider = StrictDocumentsProvider(withSubfolder = true)

        // The old subfolder listing uri: a raw docId with a '/' splits the
        // path into extra segments (the real getDocumentIdFromUri demands
        // exactly two).
        assertThrows(IllegalArgumentException::class.java) {
            provider.query(Uri.parse("content://docs/document/primary%3AMusic/Albums"), null, null, null, null)
        }
        // The old file document uri, same shape.
        assertThrows(IllegalArgumentException::class.java) {
            provider.query(Uri.parse("content://docs/document/primary%3AMusic/track%2001.mp3"), null, null, null, null)
        }
        // The tree's own root row is not a document: its uri is refused too.
        assertThrows(FileNotFoundException::class.java) {
            provider.query(Uri.parse("content://docs/document/primary%3AMusic%3Atree"), null, null, null, null)
        }
    }

    /**
     * Checklist (d): when a folder can't be read, logcat carries the
     * attribution line - TAG "LumiViewModel" naming the failing folder uri
     * together with the exception - while the user-facing event keeps its
     * pinned generic copy. Driven through the production cursor path
     * against a provider that denies the listing (a revoked grant).
     */
    @Test
    fun aFailedFolderReadIsAttributedInTheLog() {
        val viewModel = openContentPane()
        val events = eventsOf(viewModel)

        ShadowContentResolver.registerProviderInternal(
            "docs",
            object : ContentProvider() {
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
            },
        )

        viewModel.addFolderEntry(Uri.parse(TREE_URI))
        idleUntil({ events.any { it == "Couldn't read that folder's songs." } })

        // The per-node attribution names the failing folder uri AND carries
        // the exception (the top-level line names the tree uri too).
        val perNode = ShadowLog.getLogsForTag("LumiViewModel")
            .firstOrNull { it.msg == "Failed to list the children of $TREE_URI" }
        assertNotNull("the log names the failing folder uri", perNode)
        assertEquals("grant revoked", perNode!!.throwable?.message)
        assertEquals(Log.ERROR, perNode.type)
        assertTrue(
            "the top-level line names the tree uri too",
            ShadowLog.getLogsForTag("LumiViewModel").any { it.msg == "Failed to read the songs of folder $TREE_URI" },
        )
    }

    /**
     * The provider that encodes the real documents-provider grammar for the
     * tree `primary:Music`:
     *
     * ```
     * primary:Music/
     *   track 01.mp3       (file)
     *   track 02.mp3       (file)
     *   [Albums/           (subfolder, withSubfolder only)]
     *     [track 03.mp3    (file)]
     *   [readme.txt        (file, not audio)]
     * ```
     *
     * - the tree uri (`/tree/<treeId>:tree`) lists the root's children AND
     *   the tree's own root row first (the real `:tree` listing shape);
     * - a subfolder's children are listed ONLY at the tree's child uri
     *   (`/child/<treeId>:tree/<docId>`);
     * - a document uri (`/document/<docId>`) answers with the document's
     *   own single row and demands EXACTLY two path segments - a raw docId
     *   (a real one contains `/`) splits the path and is refused with the
     *   same `IllegalArgumentException` the device's `getDocumentIdFromUri`
     *   throws;
     * - anything else is refused, like the real provider refuses uris it
     *   cannot parse.
     */
    private class StrictDocumentsProvider(private val withSubfolder: Boolean) : ContentProvider() {

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            val segments = uri.pathSegments
            return when (segments.getOrNull(0)) {
                "tree" -> {
                    if (segments.size != 2 || segments[1] != "primary:Music:tree") {
                        throw IllegalArgumentException("strict: unsupported tree uri $uri")
                    }
                    MatrixCursor(COLUMNS).also { cursor ->
                        // The tree's own root row: the real :tree listing
                        // includes it first; its _id is the tree's own
                        // segment.
                        cursor.addRow(row("primary:Music:tree", "Music", 1L))
                        cursor.addRow(row("primary:Music/track 01.mp3", "track 01.mp3", 0L))
                        cursor.addRow(row("primary:Music/track 02.mp3", "track 02.mp3", 0L))
                        if (withSubfolder) {
                            cursor.addRow(row("primary:Music/Albums", "Albums", 1L))
                            cursor.addRow(row("primary:Music/readme.txt", "readme.txt", 0L))
                        }
                    }
                }
                "child" -> {
                    if (segments.size != 3 || segments[1] != "primary:Music:tree") {
                        throw IllegalArgumentException("strict: unsupported child uri $uri")
                    }
                    when (segments[2]) {
                        "primary:Music/Albums" ->
                            if (!withSubfolder) {
                                throw FileNotFoundException("strict: unable to find document id: ${segments[2]}")
                            } else {
                                MatrixCursor(COLUMNS).also { cursor ->
                                    cursor.addRow(row("primary:Music/Albums/track 03.mp3", "track 03.mp3", 0L))
                                }
                            }
                        else -> throw FileNotFoundException("strict: unable to find document id: ${segments[2]}")
                    }
                }
                "document" -> {
                    // getDocumentIdFromUri: the docId is the second segment
                    // and ONLY the second segment.
                    if (segments.size != 2) {
                        throw IllegalArgumentException("Invalid document id: $uri")
                    }
                    when (segments[1]) {
                        "primary:Music" -> MatrixCursor(COLUMNS).also { cursor ->
                            cursor.addRow(row("primary:Music", "Music", 1L))
                        }
                        else -> throw FileNotFoundException("strict: unable to find document id: ${segments[1]}")
                    }
                }
                else -> throw IllegalArgumentException("strict: unsupported uri $uri")
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

        private fun row(docId: String, displayName: String, flags: Long): Array<Any?> =
            arrayOf<Any?>(docId, displayName, flags)

        private companion object {
            val COLUMNS = arrayOf("_id", "_display_name", "_flags")
        }
    }

    companion object {
        /** The documents tree uri in the shape the SAF picker returns. */
        const val TREE_URI = "content://docs/tree/primary%3AMusic:tree"

        /** The same tree with the picker's ":openable" suffix on the last segment. */
        const val TREE_URI_OPENABLE = "content://docs/tree/primary%3AMusic%3Atree:openable"
    }
}
