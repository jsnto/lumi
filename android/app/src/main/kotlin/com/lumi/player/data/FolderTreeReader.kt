package com.lumi.player.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a picked folder tree through the SAF documents provider: the
 * [folderChildren] seam lists one folder's direct children at the
 * tree-scoped document-uri shape the tree grant authorizes, and the
 * guarded walks ([walkFolderTree]) re-walk the whole tree best-effort.
 * The folder add, rescan, removal, and folder-page song list (in
 * [com.lumi.player.ui.LumiViewModel]) all read through it.
 *
 * Failure attribution (TAG "LumiViewModel") stays here: a query that
 * throws names the folder uri that failed and the exception, so a scan,
 * rescan, or removal failure is diagnosable from the log instead of a
 * bare generic event.
 */
class FolderTreeReader(private val context: Context) {

    /**
     * Test seam: the documents provider's folder listing (a folder uri to
     * its direct children), replaced in unit tests with a fake tree (the
     * view model delegates its own seam to this one).
     *
     * The SAF picker persists a grant on the picked tree uri
     * (`tree/<treeId>`), and the framework's permission check authorizes
     * ONLY the tree-scoped shapes under that grant: every treeless
     * `document/<docId>(/children)` query against the tree's provider is
     * rejected with `SecurityException: Permission Denial ... requires
     * that you obtain access using ACTION_OPEN_DOCUMENT` (device-proven on
     * Android 14 - that denial is what made the pre-fix treeless seam
     * import zero songs for EVERY folder and surface the generic "Couldn't
     * read that folder's songs."). So this seam lists every folder - the
     * tree's root and each subfolder alike - at the tree-scoped
     * `tree/<treeId>/document/<docId>/children` under the grant's own
     * treeId, and a folder's node uri is its own tree-scoped document uri
     * `tree/<treeId>/document/<docId>` - never the bare tree uri (the
     * provider's matcher has no entry for a bare tree uri) and never a
     * treeless
     * `document/` uri (the permission layer denies it under the tree
     * grant).
     *
     * A picked tree's docIds contain `/` and `:` (e.g.
     * `primary:Music/Album/song.mp3`), so every uri this seam builds carries
     * the treeId and the docId each percent-encoded into ONE path segment
     * (`Uri.encode(id, null)` + `encodedPath`), and a stored payload is
     * openable under the tree grant alone (device-proven: the treeless
     * `document/<docId>` payload fails with a SecurityException under the
     * same grant, the tree-scoped one opens).
     *
     * The provider's cursor carries the DocumentsContract columns
     * `document_id`, `_display_name` and `mime_type` - the constants
     * [DocumentsContract.Document.COLUMN_DOCUMENT_ID],
     * [DocumentsContract.Document.COLUMN_DISPLAY_NAME] and
     * [DocumentsContract.Document.COLUMN_MIME_TYPE] (NOT the `_id`/
     * `_mime_type` names the treeless seam read - the real provider's
     * cursor has no such columns, so that read crashed on the first row -
     * device-proven), and rows are directories when their `mime_type` is
     * `vnd.android.document/directory` ([DocumentsContract.Document.MIME_TYPE_DIR]).
     *
     * The tree uri's docId is its second path segment; a trailing
     * `:openable` picker suffix on that segment (android.net.Uri has no
     * `:`-fragment parsing, so it rides in the path) is stripped before the
     * query - the provider answers only the bare docId. The strip applies
     * to the picker's tree uri ONLY: the tree-scoped node uris this seam
     * builds never carry it, and a raw docId may legitimately end in
     * `:openable` (legal on ext4), so a subfolder's docId is used verbatim.
     * The folders table stores the picker uri verbatim (normalization is at
     * query time only).
     */
    var folderChildren: (Uri) -> List<FolderChild> = { folderUri ->
        val treeId = treeIdOf(folderUri)
        val docId = folderDocIdOf(folderUri)
        context.contentResolver
            .query(
                listChildrenUri(folderUri, treeId, docId),
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )
            ?.use { cursor ->
                val docIdColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                buildList {
                    while (cursor.moveToNext()) {
                        val rawDocId = cursor.getString(docIdColumn) ?: continue
                        // The child's own tree-scoped document uri is the
                        // walk node for a subfolder AND the stored payload
                        // for a file - one uri shape for both, openable
                        // under the tree grant alone.
                        add(
                            FolderChild(
                                displayName = cursor.getString(nameColumn) ?: "",
                                isDirectory = cursor.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR,
                                childUri = documentUri(folderUri, treeId, rawDocId),
                            ),
                        )
                    }
                }
            }
            ?: emptyList()
    }

    /**
     * The label for a picked folder: the `_display_name` of the folder's own
     * row, read at the folder's own tree-scoped document uri (see the
     * [folderChildren] seam's grammar note - the tree uri itself is NOT a
     * listing uri, so the label is a single-row document query, not a
     * listing read - and the treeless document uri is not authorized under
     * the tree grant). The provider may still answer empty (or throw, e.g.
     * on a revoked grant), so the read is guarded and any provider failure
     * falls back to the uri string instead of aborting the add.
     */
    fun folderDisplayName(treeUri: Uri): String =
        try {
            context.contentResolver
                .query(
                    Uri.parse(documentUri(treeUri, treeIdOf(treeUri), folderDocIdOf(treeUri))),
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null,
                    null,
                    null,
                )
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                }
                ?.ifEmpty { null }
                ?: treeUri.toString()
        } catch (_: Exception) {
            treeUri.toString()
        }

    /**
     * The grant's own treeId: the picker's tree uri carries it as its
     * second path segment (a trailing `:openable` picker suffix on that
     * segment is stripped - the provider answers only the bare docId), and
     * every tree-scoped node uri this seam builds carries the same
     * (already-stripped) treeId as its second segment, verbatim.
     */
    private fun treeIdOf(folderUri: Uri): String {
        val segments = folderUri.pathSegments
        if (segments.firstOrNull() != "tree") return ""
        val treeId = segments.getOrNull(1) ?: return ""
        return if (segments.size == 2) treeId.removeSuffix(":openable") else treeId
    }

    /** A folder's children listing uri `scheme://authority/tree/<treeId>/document/<docId>/children`, the treeId and the docId each percent-encoded into a single path segment. */
    private fun listChildrenUri(folderUri: Uri, treeId: String, docId: String): Uri =
        Uri.Builder()
            .scheme(folderUri.scheme)
            .authority(folderUri.authority)
            .encodedPath("/tree/${Uri.encode(treeId, null)}/document/${Uri.encode(docId, null)}/children")
            .build()

    /** A child's own uri `scheme://authority/tree/<treeId>/document/<docId>` built from [folderUri]'s scheme + authority, the treeId and the docId each percent-encoded into a single path segment. It is the walk node for a subfolder AND the stored payload for a file - openable under the tree grant alone. */
    private fun documentUri(folderUri: Uri, treeId: String, docId: String): String =
        Uri.Builder()
            .scheme(folderUri.scheme)
            .authority(folderUri.authority)
            .encodedPath("/tree/${Uri.encode(treeId, null)}/document/${Uri.encode(docId, null)}")
            .build()
            .toString()

    /**
     * The [folderChildren] seam with per-node failure attribution: a query
     * that throws names the folder uri that failed and the exception, so a
     * scan or rescan failure is diagnosable from the log instead of a bare
     * generic event. The exception is rethrown unchanged.
     */
    fun folderChildrenAttributed(folderUri: Uri): List<FolderChild> =
        try {
            folderChildren(folderUri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list the children of $folderUri", e)
            throw e
        }

    /** Walks [treeUri] through the attributed [folderChildren] seam, keeping the supported audio only. */
    fun scanFolderAudioFiles(treeUri: Uri): List<ScannedSong> =
        collectAudioFiles(treeUri.toString()) { folderUri ->
            folderChildrenAttributed(Uri.parse(folderUri))
        }

    /**
     * The guarded tree walk behind the folder reads: the full
     * [collectAudioFiles] walk through the attributed seam, settling to
     * null (and logging [what] with the exception) when the tree can no
     * longer be read - a revoked grant makes the provider query throw.
     */
    suspend fun walkFolderTree(what: String, folderUri: String): List<ScannedSong>? =
        try {
            withContext(Dispatchers.IO) {
                collectAudioFiles(folderUri) { childUri ->
                    folderChildrenAttributed(Uri.parse(childUri))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, what, e)
            null
        }

    private companion object {
        // Same tag as the view model's own log lines: the failure
        // attribution (which folder uri threw, with the exception) is
        // read from logcat under one tag.
        private const val TAG = "LumiViewModel"
    }
}
