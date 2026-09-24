package com.lumi.player.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** The outcome of one song download. */
sealed interface SongDownloadResult {
    /** The saved file's content URI (inside [SongDownload.MUSIC_SUBFOLDER]). */
    data class Success(val uri: Uri) : SongDownloadResult

    /** The download (or the storage target) failed; retryable. */
    data object Failure : SongDownloadResult
}

/**
 * Downloads a song from [url] into the public music folder's
 * [SongDownload.MUSIC_SUBFOLDER] subfolder ("Music/lumi/", created by the
 * MediaProvider as part of the insert - see [SongDownload.insertValuesFor]).
 *
 * The mechanism is the platform's scoped-storage path: a MediaStore insert
 * whose output stream the download streams into - the same family of
 * platform storage the app uses for its content (MediaStore), without any
 * new runtime permission on API 29+. On API 26-28 the insert needs
 * WRITE_EXTERNAL_STORAGE, which the app does not request: there the insert
 * fails and the caller surfaces the retryable failure state instead of
 * crashing.
 *
 * [onProgress] is called with `null` while the total size is unknown
 * (indeterminate) and with the 0..1 fraction once the connection reports a
 * Content-Length (determinate). It is invoked from the download thread.
 *
 * A failed download deletes the half-inserted row, so a failed attempt
 * never leaves an empty file in the subfolder.
 */
class SongDownloader(
    private val resolver: ContentResolver,
    // Finite timeouts: a host that never completes the handshake, or that
    // stalls the body after answering, fails the download (the retryable
    // state) instead of hanging it in flight.
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    // Test seam: the HTTP open (tests substitute a fake body + size).
    private val openUrl: (String) -> OpenedUrl = { url ->
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.connect()
        // A final code of 300+ is not the resource: a 3xx the platform did
        // not follow (cross-protocol redirects are not auto-followed) or a
        // 4xx/5xx refusal - streaming its body would save a stub (or
        // nothing) as the "song".
        if (connection.responseCode >= 300) {
            throw java.io.IOException("HTTP " + connection.responseCode)
        }
        OpenedUrl(connection.inputStream, connection.contentLength.toLong())
    },
) {
    /** The opened URL's body and its total size in bytes (-1 when the connection does not report one). */
    data class OpenedUrl(val body: InputStream, val contentLength: Long)

    suspend fun download(songName: String, url: String, onProgress: (Float?) -> Unit): SongDownloadResult =
        withContext(Dispatchers.IO) {
            val insertUri = try {
                resolver.insert(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    SongDownload.insertValuesFor(songName, url),
                )
            } catch (e: Exception) {
                // Storage unavailable (e.g. no grant below API 29): the
                // failure is retryable, nothing was inserted to clean up.
                return@withContext SongDownloadResult.Failure
            } ?: return@withContext SongDownloadResult.Failure
            try {
                val opened = openUrl(url)
                onProgress(if (opened.contentLength > 0) 0f else null)
                val out = resolver.openOutputStream(insertUri)
                    ?: return@withContext cleanup(insertUri) { SongDownloadResult.Failure }
                out.use { stream ->
                    opened.body.use { body ->
                        val total = opened.contentLength
                        val buffer = ByteArray(64 * 1024)
                        var received = 0L
                        while (true) {
                            val n = body.read(buffer)
                            if (n < 0) break
                            stream.write(buffer, 0, n)
                            received += n
                            if (total > 0) {
                                onProgress((received / total).toFloat().coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                SongDownloadResult.Success(insertUri)
            } catch (e: Exception) {
                cleanup(insertUri) { SongDownloadResult.Failure }
            }
        }

    /** [block]'s result, deleting the half-inserted row first on the failure path. */
    private fun cleanup(insertUri: Uri, block: () -> SongDownloadResult): SongDownloadResult {
        val result = block()
        if (result is SongDownloadResult.Failure) {
            try {
                resolver.delete(insertUri, null, null)
            } catch (e: Exception) {
                // The row (if any) stays; the download is failed either way.
            }
        }
        return result
    }
}
