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
 * a provider that enforces the REAL documents-provider uri grammar (AOSP
 * `android.provider.DocumentsProvider`, API 26-35).
 *
 * The pre-fix seam listed the root at the tree uri itself
 * (`tree/<treeId>`) and subfolders at a `child/<treeSeg>:tree/<docId>`
 * listing uri, and decided a row was a directory from the `_flags` bit 0.
 * The real provider's matcher answers ONLY `document/<docId>`,
 * `document/<docId>/children` and the tree-scoped twins
 * `tree/<treeId>/document/<docId>(/children)`: the tree uri itself and any
 * `child/...` uri are refused with `UnsupportedOperationException` (and the
 * tree-scoped twins are gated by the stock provider's
 * descendant-containment check), so EVERY folder scan died at its first
 * query and surfaced as the generic "Couldn't read that folder's songs."
 * On top of that, the real providers mark directories by `_mime_type`
 * (`vnd.android.document/directory`), not by a flags bit - the pre-fix
 * flags check misread every subfolder as a file (never recursed) and every
 * thumbnailable file as a subfolder.
 *
 * [StrictDocumentsProvider] encodes that grammar and is registered by
 * authority; the PRODUCTION [LumiViewModel.folderChildren] seam (NOT
 * replaced) is driven against it. The counterfactual tests pin the
 * rejection of the pre-fix uri shapes; the import tests prove the fixed
 * seam walks the same tree end-to-end (root files, subfolder files, the
 * stored payload shape, and the rescan no-op).
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
     * The picker may append a ":openable" suffix to the tree uri's docId
     * path segment (android.net.Uri has no ':'-fragment parsing, so it rides
     * in the path). The strict provider answers ONLY the bare docId - the
     * children listing and the label read are both refused unless the seam
     * strips the suffix before querying - so this imports only if the
     * normalization happens. The folders table still stores the picker uri
     * verbatim (normalization is at query time only), and the label read
     * goes through the bare document uri (a raw query would be refused and
     * the label would fall back to the uri string).
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
     * The counterfactuals: the uri shapes the PRE-FIX seam built are
     * rejected by the provider's grammar. That rejection (not a revoked
     * grant) is what the captain's .mp3 folder threw on the device.
     */
    @Test
    fun theOldUriShapesAreRejectedByTheProvider() {
        val provider = StrictDocumentsProvider(withSubfolder = true)

        // The pre-fix root listing: the tree uri itself. The matcher has no
        // tree/* entry for a bare tree uri - the pre-fix seam's first query
        // for EVERY folder.
        assertThrows(UnsupportedOperationException::class.java) {
            provider.query(Uri.parse("content://docs/tree/primary%3AMusic"), null, null, null, null)
        }
        // The pre-fix subfolder listing: a child/... uri. No matcher entry
        // for it either - the pre-fix seam's first query for EVERY folder
        // with a subfolder.
        assertThrows(UnsupportedOperationException::class.java) {
            provider.query(Uri.parse("content://docs/child/primary%3AMusic/primary%3AMusic%2FAlbums"), null, null, null, null)
        }
        // A raw docId split by '/' into extra path segments is not a
        // document uri: the matcher takes the docId from exactly the second
        // segment, and a third segment that is not "children" is refused.
        assertThrows(UnsupportedOperationException::class.java) {
            provider.query(Uri.parse("content://docs/document/primary%3AMusic/Albums"), null, null, null, null)
        }
        // The tree-scoped twin: the matcher accepts it, but the stock
        // provider's enforceTree descendant check (isChildDocument, which
        // the stock providers answer false for) gates it - the reason the
        // seam lists at the plain document/ shapes instead.
        assertThrows(SecurityException::class.java) {
            provider.query(
                Uri.parse("content://docs/tree/primary%3AMusic/document/primary%3AMusic%2Ftrack%2001.mp3"),
                null, null, null, null,
            )
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
     *   track 01.mp3       (file, audio/mpeg)
     *   track 02.mp3       (file, audio/mpeg)
     *   [Albums/           (subfolder, withSubfolder only)]
     *     [track 03.mp3    (file)]
     *   [readme.txt        (file, not audio)]
     *   [photo.jpg         (file, image/jpeg, FLAGS BIT 0 SET -
     *                       FLAG_SUPPORTS_THUMBNAIL on API 26+; a flags-based
     *                       directory check would misread it as a subfolder)]
     * ```
     *
     * - `document/<docId>` (exactly two path segments) answers with the
     *   document's OWN single row - files AND directories alike;
     * - `document/<docId>/children` answers with the document's DIRECT
     *   CHILDREN ONLY - files and subfolders alike, NO root row (a directory
     *   row's `_mime_type` is `vnd.android.document/directory` - the way the
     *   real providers mark directories);
     * - the tree uri itself (`tree/<treeId>`), any `child/...` listing uri,
     *   and a `document/<docId>` uri with a third segment that is not
     *   "children" are refused with `UnsupportedOperationException`, the
     *   same `UnsupportedOperationException("Unsupported Uri ...")` the
     *   real matcher throws for shapes it does not parse;
     * - the tree-scoped twins (`tree/<treeId>/document/<docId>(/children)`,
     *   matcher-accepted) are refused with `SecurityException` - the stock
     *   provider's `enforceTree` descendant-containment check, which the
     *   stock providers answer `false` for - which is why the seam lists at
     *   the plain `document/` shapes;
     * - an unknown docId is refused with the real
     *   `FileNotFoundException("Unable to find document id: ...")`.
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
                "document" -> {
                    val docId = segments.getOrNull(1)
                    when {
                        // getDocumentIdFromUri: the docId is the second
                        // segment and ONLY the second segment.
                        segments.size == 2 -> singleRow(docId)
                        segments.size == 3 && segments[2] == "children" -> childrenOf(docId)
                        else -> throw UnsupportedOperationException("Unsupported Uri $uri")
                    }
                }
                // The tree-scoped twins: the matcher accepts these shapes, but
                // the stock provider's enforceTree descendant check (the
                // isChildDocument containment test, which the stock providers
                // answer false for) gates every query through them. A BARE
                // tree uri, though, has no matcher entry at all - the matcher
                // rejects it before enforceTree ever runs.
                "tree" ->
                    if (segments.size == 4 && segments.getOrNull(2) == "document") {
                        throw SecurityException("Not a descendant of $uri")
                    } else {
                        throw UnsupportedOperationException("Unsupported Uri $uri")
                    }
                // The pre-fix shapes: the tree uri itself and the child/...
                // listing have no matcher entry at all - the real matcher
                // falls through to the same rejection.
                else -> throw UnsupportedOperationException("Unsupported Uri $uri")
            }
        }

        private fun singleRow(docId: String?): Cursor {
            if (docId == null) {
                throw FileNotFoundException("Unable to find document id: $docId")
            }
            return MatrixCursor(COLUMNS).also { cursor ->
                cursor.addRow(rowFor(docId))
            }
        }

        private fun childrenOf(docId: String?): Cursor {
            if (docId == null) {
                throw FileNotFoundException("Unable to find document id: $docId")
            }
            return MatrixCursor(COLUMNS).also { cursor ->
                when (docId) {
                    ROOT_DOC_ID -> {
                        cursor.addRow(row("$ROOT_DOC_ID/track 01.mp3", "track 01.mp3", 0L, "audio/mpeg"))
                        cursor.addRow(row("$ROOT_DOC_ID/track 02.mp3", "track 02.mp3", 0L, "audio/mpeg"))
                        if (withSubfolder) {
                            cursor.addRow(row("$ROOT_DOC_ID/Albums", "Albums", 0L, MIME_DIR))
                            cursor.addRow(row("$ROOT_DOC_ID/readme.txt", "readme.txt", 0L, "text/plain"))
                        }
                        // FLAGS BIT 0 SET (FLAG_SUPPORTS_THUMBNAIL on
                        // API 26+): a directory check must come from the
                        // mime type, not this bit.
                        cursor.addRow(row("$ROOT_DOC_ID/photo.jpg", "photo.jpg", 1L, "image/jpeg"))
                    }
                    "$ROOT_DOC_ID/Albums" -> {
                        if (!withSubfolder) throw FileNotFoundException("Unable to find document id: $docId")
                        cursor.addRow(row("$ROOT_DOC_ID/Albums/track 03.mp3", "track 03.mp3", 0L, "audio/mpeg"))
                    }
                    // A file's children: the real providers refuse a
                    // children listing for a non-directory document.
                    else -> throw FileNotFoundException("Unable to find document id: $docId")
                }
            }
        }

        private fun rowFor(docId: String): Array<Any?> =
            when (docId) {
                ROOT_DOC_ID -> row(docId, "Music", 0L, MIME_DIR)
                "$ROOT_DOC_ID/Albums" -> row(docId, "Albums", 0L, MIME_DIR)
                else -> row(docId, docId.substringAfterLast('/'), 0L, "audio/mpeg")
            }

        private fun row(docId: String, displayName: String, flags: Long, mime: String): Array<Any?> =
            arrayOf<Any?>(docId, displayName, flags, mime)

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
            const val ROOT_DOC_ID = "primary:Music"
            const val MIME_DIR = "vnd.android.document/directory"

            val COLUMNS = arrayOf("_id", "_display_name", "_flags", "_mime_type")
        }
    }

    companion object {
        /** The documents tree uri in the shape the SAF picker returns. */
        const val TREE_URI = "content://docs/tree/primary%3AMusic"

        /** The same tree with the picker's ":openable" suffix on the docId segment. */
        const val TREE_URI_OPENABLE = "content://docs/tree/primary%3AMusic%3Aopenable"
    }
}
