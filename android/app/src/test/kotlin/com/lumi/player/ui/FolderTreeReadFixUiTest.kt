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
import com.lumi.player.LumiApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import com.lumi.player.ui.testutil.idleUntil
import com.lumi.player.ui.testutil.app
import com.lumi.player.ui.testutil.dbAndGet
import com.lumi.player.ui.testutil.openContentPane
import com.lumi.player.ui.testutil.viewModelOf

/**
 * The on-device read failure the folder-import fix targets, pinned against
 * a provider that enforces the REAL SAF tree-grant grammar (the framework's
 * permission check + AOSP `android.provider.DocumentsProvider`, API 26-35).
 *
 * The pre-fix seam queried the treeless `document/<docId>(/children)`
 * shapes - the label read, the root listing and every subfolder listing -
 * and read the `_id`/`_mime_type` columns. The SAF permission layer
 * authorizes ONLY the tree-scoped shapes under the tree grant: every
 * treeless query is rejected with
 * `SecurityException: Permission Denial ... requires that you obtain
 * access using ACTION_OPEN_DOCUMENT` (device-proven on Android 14), so
 * EVERY folder add imported zero songs and surfaced as the generic
 * "Couldn't read that folder's songs."; and the real provider's cursor
 * exposes the `document_id`/`_display_name`/`mime_type` columns, so the
 * `_id`/`_mime_type` read would crash on the first row anyway. On top of
 * that, the real providers mark directories by `mime_type`
 * (`vnd.android.document/directory`), not by a flags bit - a flags check
 * misreads every subfolder as a file and every thumbnailable file as a
 * subfolder.
 *
 * [StrictDocumentsProvider] encodes that grammar (the tree-scoped shapes
 * answer, gated by the enforceTree descendant check; the treeless shapes
 * are refused with the permission layer's SecurityException; the bare tree
 * uri and any `child/...` uri are refused with
 * `UnsupportedOperationException`), is registered by authority, and drives
 * the PRODUCTION [LumiViewModel.folderChildren] seam (NOT replaced)
 * against it. The counterfactual tests pin the rejection of the treeless
 * uri shapes; the import tests prove the fixed seam walks the same tree
 * end-to-end (root files, subfolder files, the stored tree-scoped payload
 * shape, and the rescan no-op).
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
        markSetupCompleteForTests()
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private fun eventsOf(viewModel: LumiViewModel): MutableList<String> {
        val events = mutableListOf<String>()
        viewModel.viewModelScope.launch { viewModel.uiEvents.collect { e: String -> events += e } }
        return events
    }

    /**
     * The captain's case, pinned against the real tree-grant grammar: a
     * .mp3-only folder (including a subfolder) adds cleanly through the
     * production cursor path, every song is stored exactly once, and the
     * stored payload is a well-formed tree-scoped document uri.
     */
    @Test
    fun aFolderOfMp3FilesImportsEverySongIncludingSubfolders() {
        val activity = compose.openContentPane()
        val viewModel = viewModelOf(activity)
        val events = eventsOf(viewModel)

        // The strict provider is registered by authority, so the production
        // seam's queries (the tree-scoped label read, root listing and
        // subfolder listing) hit its grammar.
        ShadowContentResolver.registerProviderInternal("docs", StrictDocumentsProvider(withSubfolder = true))

        viewModel.addFolderEntry(Uri.parse(TREE_URI))
        compose.idleUntil({ events.any { it == "Added 3 songs from the folder." } })

        val entries = dbAndGet { app().repository.observeEntries().first() }
        assertEquals(
            listOf("track 01.mp3", "track 02.mp3", "track 03.mp3"),
            entries.map { it.label },
        )

        // The stored payload is a well-formed tree-scoped document uri -
        // four path segments, the treeId and the docId each
        // percent-encoded into one - so the player can open it under the
        // tree grant alone (the treeless payload the pre-fix seam stored
        // is denied by the permission layer under the same grant).
        assertEquals(
            "content://docs/tree/primary%3AMusic/document/primary%3AMusic%2Ftrack%2001.mp3",
            entries.first().payload,
        )
        val payload = Uri.parse(entries.first().payload)
        assertEquals(4, payload.pathSegments.size)
        assertEquals("tree", payload.pathSegments[0])
        assertEquals("primary:Music", payload.pathSegments[1])
        assertEquals("document", payload.pathSegments[2])
        assertEquals("primary:Music/track 01.mp3", payload.pathSegments[3])

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
        val activity = compose.openContentPane()
        val viewModel = viewModelOf(activity)
        val events = eventsOf(viewModel)

        ShadowContentResolver.registerProviderInternal("docs", StrictDocumentsProvider(withSubfolder = false))

        viewModel.addFolderEntry(Uri.parse(TREE_URI))
        compose.idleUntil({ events.any { it == "Added 2 songs from the folder." } })

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
        val activity = compose.openContentPane()
        val viewModel = viewModelOf(activity)
        val events = eventsOf(viewModel)

        ShadowContentResolver.registerProviderInternal("docs", StrictDocumentsProvider(withSubfolder = true))

        viewModel.addFolderEntry(Uri.parse(TREE_URI_OPENABLE))
        compose.idleUntil({ events.any { it == "Added 3 songs from the folder." } })

        val entries = dbAndGet { app().repository.observeEntries().first() }
        assertEquals(
            listOf("track 01.mp3", "track 02.mp3", "track 03.mp3"),
            entries.map { it.label },
        )
        assertEquals(
            "content://docs/tree/primary%3AMusic/document/primary%3AMusic%2Ftrack%2001.mp3",
            entries.first().payload,
        )
        val payload = Uri.parse(entries.first().payload)
        assertEquals(4, payload.pathSegments.size)

        // The folder row stores the picker uri verbatim; the label is the
        // folder's own name, read through the canonical tree-scoped
        // document uri.
        val folders = dbAndGet { app().repository.observeFolders().first() }
        assertEquals(1, folders.size)
        assertEquals(TREE_URI_OPENABLE, folders.single().uri)
        assertEquals("Music", folders.single().label)
    }

    /**
     * A subfolder whose docId legitimately ends in `:openable` (legal on
     * ext4) must be walked uncorrupted: the picker appends the suffix only
     * to the tree uri's docId segment, so the seam must use the subfolder
     * node uri's docId verbatim. Stripping it there (the pre-fix behavior)
     * would list the children at
     * `tree/primary:Music/document/primary:Music/box/children`, which this
     * provider refuses, and the whole walk would surface the generic
     * "Couldn't read that folder's songs."
     */
    @Test
    fun aSubfolderWhoseDocIdEndsInOpenableIsWalkedUncorrupted() {
        val activity = compose.openContentPane()
        val viewModel = viewModelOf(activity)
        val events = eventsOf(viewModel)

        ShadowContentResolver.registerProviderInternal("docs", OpenableSubfolderDocumentsProvider())

        viewModel.addFolderEntry(Uri.parse(TREE_URI))
        compose.idleUntil({ events.any { it == "Added 1 song from the folder." } })

        val entries = dbAndGet { app().repository.observeEntries().first() }
        assertEquals(listOf("track 01.mp3"), entries.map { it.label })
        // The stored payload keeps the subfolder's `:openable` docId
        // (percent-encoded) - the walk queried it verbatim, uncorrupted.
        assertEquals(
            "content://docs/tree/primary%3AMusic/document/primary%3AMusic%2Fbox%3Aopenable%2Ftrack%2001.mp3",
            entries.first().payload,
        )
    }

    /**
     * The rescan goes through the same uri grammar: a rescan of the unchanged
     * folder answers the tree and child listings again and adds nothing.
     */
    @Test
    fun rescanOfAnUnchangedFolderGoesThroughTheSameGrammar() {
        val activity = compose.openContentPane()
        val viewModel = viewModelOf(activity)
        val events = eventsOf(viewModel)

        ShadowContentResolver.registerProviderInternal("docs", StrictDocumentsProvider(withSubfolder = true))

        viewModel.addFolderEntry(Uri.parse(TREE_URI))
        compose.idleUntil({ events.any { it == "Added 3 songs from the folder." } })

        val folder = dbAndGet { app().repository.observeFolders().first() }.single()
        viewModel.rescanFolder(folder)
        compose.idleUntil({ events.any { it == "Nothing new in that folder." } })

        val entries = dbAndGet { app().repository.observeEntries().first() }
        assertEquals(3, entries.size)
    }

    /**
     * The counterfactuals: the uri shapes the SAF permission layer does NOT
     * authorize under the tree grant - or the provider's matcher does not
     * parse - are rejected. The treeless `document/...` shapes, what the
     * pre-fix seam queried, are the device-proven failure: denied with the
     * permission layer's SecurityException before a single song is
     * imported.
     */
    @Test
    fun theTreelessUriShapesAreRejectedUnderTheTreeGrant() {
        val provider = StrictDocumentsProvider(withSubfolder = true)

        // The pre-fix label read: the treeless single-row document uri.
        // The SAF permission layer denies it under the tree grant
        // (device-proven on Android 14: SecurityException "Permission
        // Denial ... requires that you obtain access using
        // ACTION_OPEN_DOCUMENT").
        assertThrows(SecurityException::class.java) {
            provider.query(Uri.parse("content://docs/document/primary%3AMusic"), null, null, null, null)
        }
        // The pre-fix root listing: the treeless children uri - the pre-fix
        // seam's first query for EVERY folder.
        assertThrows(SecurityException::class.java) {
            provider.query(Uri.parse("content://docs/document/primary%3AMusic/children"), null, null, null, null)
        }
        // The pre-fix subfolder listing: the treeless subfolder children
        // uri - the pre-fix seam's first query for EVERY folder with a
        // subfolder.
        assertThrows(SecurityException::class.java) {
            provider.query(Uri.parse("content://docs/document/primary%3AMusic%2FAlbums/children"), null, null, null, null)
        }
        // The bare tree uri has no matcher entry at all - the provider
        // refuses it with the generic unsupported-uri rejection.
        assertThrows(UnsupportedOperationException::class.java) {
            provider.query(Uri.parse("content://docs/tree/primary%3AMusic"), null, null, null, null)
        }
        // A child/... listing has no matcher entry either.
        assertThrows(UnsupportedOperationException::class.java) {
            provider.query(Uri.parse("content://docs/child/primary%3AMusic/primary%3AMusic%2FAlbums"), null, null, null, null)
        }
        // And the tree-scoped shapes DO answer under the grant's own treeId
        // (gated by the descendant check: a docId outside the tree is
        // refused with the enforceTree SecurityException).
        assertNotNull(provider.query(Uri.parse("content://docs/tree/primary%3AMusic/document/primary%3AMusic"), null, null, null, null))
        assertNotNull(provider.query(Uri.parse("content://docs/tree/primary%3AMusic/document/primary%3AMusic/children"), null, null, null, null))
        assertThrows(SecurityException::class.java) {
            provider.query(Uri.parse("content://docs/tree/primary%3AMusic%2FOther/document/primary%3AMusic"), null, null, null, null)
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
        val activity = compose.openContentPane()
        val viewModel = viewModelOf(activity)
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
        compose.idleUntil({ events.any { it == "Couldn't read that folder's songs." } })

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
     * The provider that encodes the real SAF tree-grant grammar for the
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
     * - `tree/<treeId>/document/<docId>` (exactly four path segments)
     *   answers with the document's OWN single row - files AND directories
     *   alike;
     * - `tree/<treeId>/document/<docId>/children` answers with the
     *   document's DIRECT CHILDREN ONLY - files and subfolders alike, NO
     *   root row (a directory row's `mime_type` is
     *   `vnd.android.document/directory` - the way the real providers mark
     *   directories);
     * - both tree-scoped shapes are gated by the stock provider's
     *   `enforceTree` descendant-containment check (the docId must be the
     *   tree itself or a descendant of it) - any other docId is refused
     *   with `SecurityException`;
     * - the treeless `document/<docId>(/children)` shapes are refused with
     *   `SecurityException` - the SAF permission layer's "Permission
     *   Denial ... requires that you obtain access using
     *   ACTION_OPEN_DOCUMENT", which the pre-fix seam hit on the real
     *   device for EVERY folder;
     * - the tree uri itself (`tree/<treeId>`), any `child/...` listing uri,
     *   and any other shape have no matcher entry at all - the real matcher
     *   falls through to the `UnsupportedOperationException("Unsupported
     *   Uri ...")` it throws for shapes it does not parse;
     * - an unknown docId is refused with the real
     *   `FileNotFoundException("Unable to find document id: ...")`.
     *
     * The rows carry the real provider's column names: `document_id`,
     * `_display_name`, `flags`, `mime_type` (the DocumentsContract
     * constants) - NOT the `_id`/`_mime_type` names the pre-fix seam read.
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
            return when (val first = segments.getOrNull(0)) {
                // The tree-scoped shapes: the only ones the SAF permission
                // layer authorizes under the tree grant (device-proven on
                // Android 14). The matcher accepts exactly
                // tree/<treeId>/document/<docId>(/children), gated by
                // enforceTree.
                "tree" -> {
                    val treeId = segments.getOrNull(1)
                    if (segments.getOrNull(2) != "document" || segments.size !in intArrayOf(4, 5)) {
                        // The bare tree uri (and every other tree shape)
                        // has no matcher entry at all - the real matcher
                        // rejects it before enforceTree ever runs.
                        throw UnsupportedOperationException("Unsupported Uri $uri")
                    }
                    val docId = segments.getOrNull(3)
                    if (treeId == null || docId == null || !(docId == treeId || docId.startsWith("$treeId/"))) {
                        throw SecurityException("Not a descendant of $uri")
                    }
                    if (segments.size == 4) singleRow(docId) else childrenOf(docId)
                }
                // The treeless shapes: the SAF permission layer denies them
                // under the tree grant before the provider is ever asked -
                // the device-proven rejection the pre-fix seam hit for
                // EVERY folder.
                "document" -> throw SecurityException(
                    "Permission Denial: reading $uri requires that you obtain access using ACTION_OPEN_DOCUMENT or related APIs",
                )
                // The pre-fix child/... shapes: no matcher entry at all.
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

            val COLUMNS = arrayOf("document_id", "_display_name", "flags", "mime_type")
        }
    }

    /**
     * The real documents-provider grammar for a tree whose only child is a
     * subfolder whose docId ends in `:openable`:
     *
     * ```
     * primary:Music/
     *   [box:openable/    (subfolder; its docId ends in ":openable")]
     *     track 01.mp3    (file, audio/mpeg)
     * ```
     *
     * `tree/primary:Music/document/primary:Music/box:openable/children`
     * answers the subfolder's file; every other docId (in particular
     * `primary:Music/box`, what a suffix-strip of the subfolder's node uri
     * would build) is refused, so the walk only succeeds when the
     * subfolder's docId is used verbatim.
     */
    private class OpenableSubfolderDocumentsProvider : ContentProvider() {

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
                    val treeId = segments.getOrNull(1)
                    if (segments.getOrNull(2) != "document" || segments.size !in intArrayOf(4, 5)) {
                        throw UnsupportedOperationException("Unsupported Uri $uri")
                    }
                    val docId = segments.getOrNull(3)
                    if (treeId == null || docId == null || !(docId == treeId || docId.startsWith("$treeId/"))) {
                        throw SecurityException("Not a descendant of $uri")
                    }
                    if (segments.size == 4) singleRow(docId) else childrenOf(docId)
                }
                "document" -> throw SecurityException(
                    "Permission Denial: reading $uri requires that you obtain access using ACTION_OPEN_DOCUMENT or related APIs",
                )
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
            return MatrixCursor(COLUMNS).also { cursor ->
                when (docId) {
                    ROOT_DOC_ID -> cursor.addRow(row(BOX_DOC_ID, "box:openable", 0L, MIME_DIR))
                    BOX_DOC_ID ->
                        cursor.addRow(row("$BOX_DOC_ID/track 01.mp3", "track 01.mp3", 0L, "audio/mpeg"))
                    else -> throw FileNotFoundException("Unable to find document id: $docId")
                }
            }
        }

        private fun rowFor(docId: String): Array<Any?> =
            when (docId) {
                ROOT_DOC_ID -> row(docId, "Music", 0L, MIME_DIR)
                BOX_DOC_ID -> row(docId, "box:openable", 0L, MIME_DIR)
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
            const val BOX_DOC_ID = "primary:Music/box:openable"
            const val MIME_DIR = "vnd.android.document/directory"

            val COLUMNS = arrayOf("document_id", "_display_name", "flags", "mime_type")
        }
    }

    companion object {
        /** The documents tree uri in the shape the SAF picker returns. */
        const val TREE_URI = "content://docs/tree/primary%3AMusic"

        /** The same tree with the picker's ":openable" suffix on the docId segment. */
        const val TREE_URI_OPENABLE = "content://docs/tree/primary%3AMusic%3Aopenable"
    }
}
