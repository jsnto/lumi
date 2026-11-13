package com.lumi.player.ui

import android.content.Intent
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import com.lumi.player.LumiApp
import com.lumi.player.ui.testutil.clickMenuRow
import com.lumi.player.ui.testutil.clickSettings
import com.lumi.player.ui.testutil.idleUntil
import com.lumi.player.ui.testutil.setupMainActivity
import com.lumi.player.ui.testutil.viewModelOf
import com.lumi.player.ui.testutil.waitForText
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowPackageManager
import java.util.Collections

/**
 * Behavioral coverage for the settings "Update" area driven against the
 * REAL product: the real [MainActivity] → the real settings menu → the
 * real [UpdatePane] → the real [UpdateManager] (its fetch, parse, the
 * streamed download, the [androidx.core.content.FileProvider] uri, and the
 * "install unknown apps" gate) → the real platform install intents.
 *
 * The one seam is the manager's own documented one (its `client`
 * constructor parameter, "a real [OkHttpClient] by default"): the client
 * routes the product's hardcoded `api.github.com` url over loopback to a
 * DISPOSABLE local stand-in of the releases API ([FakeReleasesApi]) that
 * serves GitHub-shaped responses, so every branch is drivable
 * deterministically while the product code (the url, the request, the
 * parse, the download, the state, the intents) runs unmodified. A real
 * `startActivity` on the real activity records the intent the shadow sees
 * (the install-settings jump and the installer launch are asserted on the
 * recorded intents).
 *
 * The pane's buttons sit low on a short screen, so the screen is tall
 * (900dp) and every row is visible without scrolling (no dialog is part of
 * this flow).
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
    qualifiers = "w412dp-h900dp",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpdatePaneUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        markSetupCompleteForTests()
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    // ------------------------------------------------------------------
    // The happy path: check → available update → streamed download → the
    // install-settings gate → the permission grant → the installer launch.
    // ------------------------------------------------------------------

    @Test
    fun updateAvailableDownloadsTheApkAndInstallsItAfterThePermissionGrant() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val api = FakeReleasesApi().also { it.start() }
        val apkBytes = ByteArray(APK_SIZE_BYTES) { (it % 251).toByte() }
        api.downloads[APK_NAME] = apkBytes
        api.latestBody = releaseJson(
            tag = "v0.3.0",
            assets = listOf(asset("lumi-app-debug.apk", "http://127.0.0.1:${api.port}/download/$APK_NAME")),
        )

        val activity = openUpdatePane(api, events)

        // The pane renders the running build and the unchecked state.
        compose.waitForText("Current version")
        compose.onNodeWithText("lumi v0.2.0").assertExists()
        compose.waitForText("Check the releases page to see if a newer version is out.")

        // The check fetches the (stand-in) releases page and settles to an
        // available update: the published v0.3.0 against the running 0.2.0.
        compose.onNodeWithText("Check for latest version").performClick()
        compose.waitForText("Update available: v0.3.0 (you have v0.2.0).")
        compose.onNodeWithText("Update to latest").assertHasClickAction()

        // The download streams the asset (the throttled transfer keeps the
        // Downloading state + progress observable), then settles ready.
        compose.onNodeWithText("Update to latest").performClick()
        compose.waitForText("Downloading v0.3.0", substring = true)
        compose.waitForText("Downloaded v0.3.0. Install it to update.")

        // The APK landed on disk with the asset's exact bytes, and the
        // progress flow reached the end of the transfer.
        val apkFile = File(activity.filesDir, "updates/$APK_NAME")
        check(apkFile.readBytes().contentEquals(apkBytes)) {
            "the downloaded APK must carry the served asset's bytes"
        }
        compose.idleUntil({ viewModelOf(activity).updateProgress.value == 1f }, "the progress to reach 1f")

        // The permission is still off: tapping Install explains the
        // requirement and opens the phone's own "install unknown apps"
        // screen for this app (the download is kept).
        compose.onNodeWithText("Install now").assertHasClickAction()
        compose.onNodeWithText("Install now").performClick()
        val shadow = shadowOf(activity)
        compose.idleUntil({ shadow.peekNextStartedActivity() != null }, "the install-settings intent to launch")
        val settingsIntent = shadow.getNextStartedActivity()
        check(settingsIntent.action == ACTION_MANAGE_APP_INSTALL_SETTINGS) {
            "the engine must open the phone's install-settings screen, got ${settingsIntent.action}"
        }
        check(settingsIntent.dataString == "package:com.lumi.player") {
            "the install-settings screen must be the one for this app, got ${settingsIntent.dataString}"
        }
        check(events.any { it.contains("install unknown apps") }) {
            "the plain message must name the missing permission, got $events"
        }

        // The user grants the permission and comes back: the activity's
        // onResume hook (the engine's own return path) launches the
        // installer over the kept APK's FileProvider uri.
        shadowOf(activity.packageManager).setCanRequestPackageInstalls(true)
        drive(activity, "onResume")
        compose.idleUntil(
            { shadow.peekNextStartedActivity() != null && shadow.peekNextStartedActivity() != settingsIntent },
            "the installer intent to launch",
        )
        val installIntent = shadow.getNextStartedActivity()
        check(installIntent.action == Intent.ACTION_VIEW) { "the installer takes an ACTION_VIEW, got ${installIntent.action}" }
        check(installIntent.type == "application/vnd.android.package-archive") {
            "the installer intent must type the APK, got ${installIntent.type}"
        }
        check((installIntent.dataString ?: "").startsWith("content://com.lumi.player.update.fileprovider/")) {
            "the installer intent must carry the FileProvider uri, got ${installIntent.dataString}"
        }
        check(installIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
            "the installer intent must grant the read on the uri, got flags ${installIntent.flags}"
        }
        api.stop()
    }

    // ------------------------------------------------------------------
    // The running build is AHEAD of the published release (the fix's case:
    // the only published release is v0.1.0 while this build is 0.2.0): the
    // check settles UpToDate and the copy names the RUNNING version - the
    // release's version is never mislabeled as the current one.
    // ------------------------------------------------------------------

    @Test
    fun upToDateWhenThePublishedReleaseIsOlderNamesTheRunningVersion() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val api = FakeReleasesApi().also { it.start() }
        api.latestBody = releaseJson(
            tag = "v0.1.0",
            assets = listOf(asset("lumi-app-debug.apk", "http://127.0.0.1:${api.port}/download/lumi-update.apk")),
        )

        val activity = openUpdatePane(api, events)
        compose.onNodeWithText("Check for latest version").performClick()

        // The published v0.1.0 is older than the running 0.2.0: UpToDate,
        // and the copy prints the RUNNING version (not the release's).
        compose.waitForText("You're up to date (current version v0.2.0).")
        // No update to take: the download and install actions stay disabled
        // (the mirror marks a disabled button with the Disabled semantics
        // property - it keeps the OnClick action on the node).
        assertSemanticsDisabled("Update to latest")
        assertSemanticsDisabled("Install now")

        // The menu row's live subtitle says the same once back on the menu.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.waitForText("Up to date (v0.2.0)")
        api.stop()
    }

    // ------------------------------------------------------------------
    // No published release exists: the GitHub API answers 404 for
    // /releases/latest - its own plain message, not a crash.
    // ------------------------------------------------------------------

    @Test
    fun noPublishedReleasesSurfacesThePlainMessage() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val api = FakeReleasesApi().also { it.start() }
        api.latestStatus = 404
        api.latestBody = """{"message":"Not Found","documentation_url":"https://docs.github.com"}"""

        val activity = openUpdatePane(api, events)
        compose.onNodeWithText("Check for latest version").performClick()
        compose.waitForText("There are no published releases yet. Check back later.")
        api.stop()
    }

    // ------------------------------------------------------------------
    // A newer release that carries no APK asset: the check reports the
    // update, and the download action refuses with its own message (no
    // write attempted).
    // ------------------------------------------------------------------

    @Test
    fun aNewerReleaseWithoutAnApkAssetRefusesTheDownload() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val api = FakeReleasesApi().also { it.start() }
        api.latestBody = releaseJson(
            tag = "v0.4.0",
            assets = listOf(asset("release-notes.txt", "http://127.0.0.1:${api.port}/download/release-notes.txt")),
        )

        val activity = openUpdatePane(api, events)
        compose.onNodeWithText("Check for latest version").performClick()
        compose.waitForText("Update available: v0.4.0 (you have v0.2.0).")
        compose.onNodeWithText("Update to latest").performClick()
        compose.waitForText("That release has no APK to install.")
        api.stop()
    }

    // ------------------------------------------------------------------
    // The releases page is unreachable (connection refused): the check
    // settles to the connection-failure message, never a crash.
    // ------------------------------------------------------------------

    @Test
    fun anUnreachableReleasesPageSurfacesTheConnectionMessage() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val port = deadPort()

        val controller = compose.setupMainActivity()
        val activity = controller.get()
        installTestUpdateManager(activity, port, events)
        compose.clickSettings()
        compose.clickMenuRow("Update")

        compose.onNodeWithText("Check for latest version").performClick()
        compose.waitForText("Couldn't reach the releases page. Check the connection and try again.")
    }

    // ------------------------------------------------------------------
    // The fixtures
    // ------------------------------------------------------------------

    /**
     * Stands up the real activity + settings menu + Update pane with the
     * view model's [UpdateManager] swapped for one whose client (the
     * manager's own test seam) routes over loopback to [api] - the
     * product's code paths (the hardcoded url, the parse, the download,
     * the state, the intents) all run unmodified.
     */
    private fun openUpdatePane(api: FakeReleasesApi, events: MutableList<String>): MainActivity {
        val controller = compose.setupMainActivity()
        val activity = controller.get()
        installTestUpdateManager(activity, api.port, events)
        compose.clickSettings()
        compose.clickMenuRow("Update")
        return activity
    }

    /** Swaps the view model's [UpdateManager] (the field it keeps) for one whose client routes to [port]. */
    private fun installTestUpdateManager(activity: MainActivity, port: Int, events: MutableList<String>) {
        val viewModel = viewModelOf(activity)
        val manager = UpdateManager(
            activity.applicationContext,
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            routingClient(port),
        ) { events += it }
        val field = LumiViewModel::class.java.getDeclaredField("updateManager")
        field.isAccessible = true
        field.set(viewModel, manager)
    }

    /**
     * The manager's documented client seam pointed at a loopback port: a
     * REAL [OkHttpClient] whose one interceptor re-routes every request
     * (the product builds the hardcoded `api.github.com` url; only the
     * network route is swapped).
     */
    private fun routingClient(port: Int): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val routed = chain.request().url.newBuilder()
                .scheme("http")
                .host("127.0.0.1")
                .port(port)
                .build()
            chain.proceed(chain.request().newBuilder().url(routed).build())
        }.build()

    /** A port that refuses connections (a bound-then-closed server socket). */
    private fun deadPort(): Int {
        ServerSocket(0).use { socket -> return socket.localPort }
    }

    /** Drives the activity's protected onResume (the engine's install-settings return hook). */
    private fun drive(activity: MainActivity, method: String) {
        val m = MainActivity::class.java.getDeclaredMethod(method)
        m.isAccessible = true
        m.invoke(activity)
    }

    /** The mirror keeps the OnClick action on a DISABLED button's node and marks it with the Disabled semantics property instead. */
    private fun assertSemanticsDisabled(text: String) {
        val node = compose.onNodeWithText(text).fetchSemanticsNode()
        check(node.config.any { it.key == SemanticsProperties.Disabled }) {
            "the '$text' button must be disabled (the Disabled semantics property is missing)"
        }
    }

    /** GitHub `/releases/latest`-shaped JSON (extra fields, like the real API's, must be tolerated). */
    private fun releaseJson(tag: String, assets: List<Pair<String, String>>): String =
        """{"tag_name":"$tag","name":"$tag","draft":false,"prerelease":false,"created_at":"2026-11-01T00:00:00Z","assets":[""" +
            assets.joinToString(",") { (name, url) -> """{"name":"$name","browser_download_url":"$url","size":1,"state":"uploaded"}""" } +
            "]}"

    /** One release asset: its file name + the url to download it from. */
    private fun asset(name: String, url: String): Pair<String, String> = name to url

    /**
     * The disposable local stand-in of the GitHub releases API: a minimal
     * HTTP/1.1 server on a private loopback port serving the
     * `/releases/latest` JSON (configurable status + body) and the download
     * assets, the download throttled in chunks so the download's progress
     * state is observable on the UI. One request at a time (the product's
     * requests are sequential), `Connection: close` per response.
     */
    private class FakeReleasesApi {
        private val serverSocket = ServerSocket(0)
        private var thread: Thread? = null

        val port: Int get() = serverSocket.localPort

        @Volatile var latestStatus: Int = 200
        @Volatile var latestBody: String = ""
        val downloads = HashMap<String, ByteArray>()

        fun start() {
            thread = Thread {
                while (true) {
                    val client = try {
                        serverSocket.accept()
                    } catch (e: java.net.SocketException) {
                        break // the socket was closed by stop()
                    }
                    handle(client)
                }
            }.also {
                it.isDaemon = true
                it.start()
            }
        }

        fun stop() {
            try {
                serverSocket.close()
            } catch (e: Exception) {
                // already closed
            }
            thread?.join(2_000)
        }

        private fun handle(client: Socket) {
            client.use { socket ->
                val input = socket.getInputStream()
                val head = StringBuilder()
                val buffer = ByteArray(1024)
                while (!head.toString().contains("\r\n\r\n")) {
                    val n = input.read(buffer)
                    if (n == -1) return
                    head.append(String(buffer, 0, n, Charsets.ISO_8859_1))
                }
                val path = head.toString().substringBefore("\r\n").split(" ").getOrNull(1) ?: "/"
                var status = 404
                var body: ByteArray = ByteArray(0)
                var contentType = "application/json"
                var throttle = false
                if (path == LATEST_PATH) {
                    status = latestStatus
                    body = latestBody.toByteArray()
                } else {
                    val data = downloads[path.removePrefix("/download/")]
                    if (data != null) {
                        status = 200
                        body = data
                        contentType = "application/vnd.android.package-archive"
                        throttle = true
                    }
                }
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 $status OK\r\n".toByteArray())
                output.write("Content-Type: $contentType\r\n".toByteArray())
                output.write("Content-Length: ${body.size}\r\n".toByteArray())
                output.write("Connection: close\r\n\r\n".toByteArray())
                output.flush()
                if (throttle) {
                    var offset = 0
                    while (offset < body.size) {
                        val chunk = minOf(CHUNK_BYTES, body.size - offset)
                        output.write(body, offset, chunk)
                        output.flush()
                        offset += chunk
                        Thread.sleep(CHUNK_DELAY_MS)
                    }
                } else {
                    output.write(body)
                    output.flush()
                }
            }
        }

        private companion object {
            const val LATEST_PATH = "/repos/jsnto/lumi/releases/latest"
            const val CHUNK_BYTES = 8 * 1024
            const val CHUNK_DELAY_MS = 15L
        }
    }

    private companion object {
        const val APK_NAME = "lumi-update.apk"
        const val APK_SIZE_BYTES = 256 * 1024
        const val ACTION_MANAGE_APP_INSTALL_SETTINGS = "android.settings.MANAGE_APP_INSTALL_SETTINGS"
    }
}
