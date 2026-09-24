package com.lumi.player.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * The song download's storage path (Robolectric, a fake `media` provider -
 * the real one is unobservable in unit tests). Pins: the insert targets the
 * public music folder's `Music/lumi/` RELATIVE_PATH (the folder the
 * provider creates on insert - the folder-creation contract), the bytes are
 * streamed into the inserted row's output stream (a stream the mirror
 * answers natively; where the mirror keeps the bytes is not observable in
 * unit tests - on the device it is the MediaProvider's own file handle),
 * the progress is determinate when the size is known (0 to 1) and
 * indeterminate otherwise, and a failed download deletes the half-inserted
 * row (no empty files behind in the subfolder).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SongDownloaderTest {

    private lateinit var provider: FakeMediaProvider
    private lateinit var resolver: android.content.ContentResolver

    @Before
    fun setUp() {
        provider = FakeMediaProvider()
        // The mirror's real openAssetFileDescriptor validates the incoming
        // uri through the provider's context (the AOSP body runs): a
        // provider registered by authority carries no context, so the
        // test attaches one (reflection on the framework field).
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val field = ContentProvider::class.java.getDeclaredField("mContext")
        field.isAccessible = true
        field.set(provider, ctx)
        ShadowContentResolver.registerProviderInternal("media", provider)
        resolver = ctx.contentResolver
    }

    @Test
    fun aSuccessfulDownload_insertsIntoLumiAndStreamsTheBytes() {
        val body = ByteArray(5) { it.toByte() }
        val progress = mutableListOf<Float?>()
        val result = runBlocking {
            SongDownloader(resolver) { url ->
                SongDownloader.OpenedUrl(ByteArrayInputStream(body), body.size.toLong())
            }.download("Porch song", "https://cdn.example.com/x/porch.mp3") { progress += it }
        }
        assertTrue("the download succeeds", result is SongDownloadResult.Success)
        assertEquals("the saved file is the inserted row", provider.insertedUri(), (result as SongDownloadResult.Success).uri)
        assertEquals("exactly one insert", 1, provider.inserted.size)
        val values = provider.inserted.single()
        assertEquals(
            "the insert targets the public music folder's lumi subfolder",
            "Music/lumi/",
            values.getAsString(MediaStore.Audio.Media.RELATIVE_PATH),
        )
        assertEquals(
            "the file is named after the song + the url's audio extension",
            "Porch song.mp3",
            values.getAsString(MediaStore.Audio.Media.DISPLAY_NAME),
        )
        assertEquals("audio/mpeg", values.getAsString(MediaStore.Audio.Media.MIME_TYPE))
        assertEquals("no failed download, no cleanup", emptyList<Uri>(), provider.deleted)
        // The size is known: the progress is determinate, starting at 0
        // and settling at 1.
        assertTrue("0f is reported first", progress.first() == 0f)
        assertTrue("1f is the last report", progress.last() == 1f)
        progress.forEach { it?.let { f -> assertTrue(f in 0f..1f) } }
    }

    @Test
    fun anUnknownSizeIsAnIndeterminateProgress() {
        val body = ByteArray(3) { 1 }
        val progress = mutableListOf<Float?>()
        val result = runBlocking {
            SongDownloader(resolver) { url ->
                SongDownloader.OpenedUrl(ByteArrayInputStream(body), -1L)
            }.download("Porch song", "https://cdn.example.com/stream/1") { progress += it }
        }
        assertTrue("the download succeeds", result is SongDownloadResult.Success)
        assertEquals("the only progress report is the indeterminate start", listOf(null), progress)
    }

    @Test
    fun aFailedDownload_deletesTheHalfInsertedRow() {
        val result = runBlocking {
            SongDownloader(resolver) { url ->
                throw IOException("HTTP 404")
            }.download("Porch song", "https://cdn.example.com/gone.mp3") { }
        }
        assertTrue("the download is reported as failed", result is SongDownloadResult.Failure)
        assertEquals("the half-inserted row is cleaned up", 1, provider.deleted.size)
        assertEquals("the cleaned-up row is the inserted one", provider.insertedUri(), provider.deleted.single())
    }

    @Test
    fun anUnavailableStorageTarget_isAFailure() {
        provider.insertShouldThrow = true
        val result = runBlocking {
            SongDownloader(resolver) { url ->
                SongDownloader.OpenedUrl(ByteArrayInputStream(ByteArray(1)), 1L)
            }.download("Porch song", "https://cdn.example.com/x/porch.mp3") { }
        }
        assertTrue("a storage failure is a retryable failure", result is SongDownloadResult.Failure)
        assertEquals("nothing was inserted, so nothing is cleaned up", 0, provider.deleted.size)
    }

    @Test
    fun aBrokenStreamIsAFailure_withCleanup() {
        val result = runBlocking {
            SongDownloader(resolver) { url ->
                SongDownloader.OpenedUrl(
                    object : java.io.InputStream() {
                        override fun read(): Int = throw IOException("mid-stream")
                    },
                    -1L,
                )
            }.download("Porch song", "https://cdn.example.com/x/porch.mp3") { }
        }
        assertTrue("a mid-stream failure is a failure", result is SongDownloadResult.Failure)
        assertEquals(1, provider.deleted.size)
    }

    @Test
    fun aFinalRedirectCodeIsAFailure_notStreamedAsTheSong() {
        // A 3xx the platform does not follow (a cross-protocol redirect -
        // HttpURLConnection only auto-follows same-protocol ones) is a
        // final code of 300+: streaming its body would save a stub (or
        // nothing) as the "song", so the download fails and the
        // half-inserted row is cleaned up.
        val server = RawHttpServer { out ->
            out.write(
                (
                    "HTTP/1.1 301 Moved Permanently\r\n" +
                        "Location: https://cdn.example.com/elsewhere/song.mp3\r\n" +
                        "Content-Length: 0\r\n" +
                        "\r\n"
                ).toByteArray(),
            )
            out.flush()
        }
        try {
            val result = runBlocking {
                SongDownloader(resolver)
                    .download(
                        "Porch song",
                        "http://127.0.0.1:" + server.port + "/song.mp3",
                    ) { }
            }
            assertTrue("a 3xx final code fails the download", result is SongDownloadResult.Failure)
            assertEquals("the half-inserted row is cleaned up", 1, provider.deleted.size)
        } finally {
            server.release()
        }
    }

    @Test
    fun aStalledBodySettlesIntoAFailure_insteadOfHanging() {
        // A host that accepts the connection, answers the headers, and
        // never sends the body: the read timeout (the constructor's - here
        // a short one, the default is 30s) must settle the download into
        // the retryable failure state instead of leaving it in flight
        // forever.
        val server = RawHttpServer { out ->
            out.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Length: 1024\r\n" +
                        "\r\n"
                ).toByteArray(),
            )
            out.flush()
            // Deliberately send NO body: the client's read must time out.
        }
        val progress = mutableListOf<Float?>()
        try {
            val result = runBlocking {
                SongDownloader(resolver, connectTimeoutMs = 5_000, readTimeoutMs = 500)
                    .download(
                        "Porch song",
                        "http://127.0.0.1:" + server.port + "/song.mp3",
                    ) { progress += it }
            }
            assertTrue("a stalled body fails the download instead of hanging", result is SongDownloadResult.Failure)
            assertTrue("the row was in flight (determinate 0) before the stall", progress.first() == 0f)
            assertEquals("the half-inserted row is cleaned up", 1, provider.deleted.size)
        } finally {
            server.release()
        }
    }

    /**
     * A one-shot raw-socket HTTP responder on 127.0.0.1 (the JDK's
     * `com.sun.net.httpserver` is not on the unit-test compile classpath):
     * accepts one request, consumes its header block, hands the socket's
     * output stream to [respond] (which writes the response headers and
     * may or may not then send a body), and holds the connection open
     * until [release] (the stalled-body case needs the socket to stay
     * half-open).
     */
    private class RawHttpServer(private val respond: (java.io.OutputStream) -> Unit) {
        private val serverSocket =
            java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val port: Int = serverSocket.localPort
        @Volatile
        private var released = false
        private val worker = thread(isDaemon = true) {
            try {
                serverSocket.use { server ->
                    val client = server.accept()
                    try {
                        val reader = java.io.BufferedReader(client.getInputStream().reader())
                        while (true) {
                            val line = reader.readLine()
                            if (line == null || line.isEmpty()) break
                        }
                        respond(client.getOutputStream())
                        // Hold the socket open until release(): the
                        // stalled-body case needs the connection to stay
                        // half-open (a closed socket would EOF the client's
                        // read instead of timing it out).
                        while (!released) Thread.sleep(10)
                    } finally {
                        client.close()
                    }
                }
            } catch (e: Exception) {
                // One-shot test server: a torn-down socket just ends the
                // worker (daemon) thread.
            }
        }
        fun release() {
            released = true
        }
    }

    private class FakeMediaProvider : ContentProvider() {
        val inserted = mutableListOf<ContentValues>()
        val deleted = mutableListOf<Uri>()
        var insertShouldThrow = false

        override fun onCreate(): Boolean = true

        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            if (insertShouldThrow) throw SecurityException("no storage permission")
            inserted += values?.let { ContentValues(it) } ?: ContentValues()
            return Uri.parse("content://media/external/audio/media/" + inserted.size)
        }

        fun insertedUri(): Uri = Uri.parse("content://media/external/audio/media/" + inserted.size)

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor = MatrixCursor(projection)

        override fun getType(uri: Uri): String? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deleted += uri
            return 1
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }
}
