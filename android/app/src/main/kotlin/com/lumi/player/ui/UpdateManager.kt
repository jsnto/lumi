package com.lumi.player.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.lumi.player.BuildConfig
import com.lumi.player.data.LatestRelease
import com.lumi.player.data.parseLatestRelease
import com.lumi.player.data.pickApkAsset
import com.lumi.player.data.releaseIsNewer
import com.lumi.player.data.releaseVersion
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The settings "Update" area's engine: checks the public GitHub releases
 * of the app's own repository for a newer build, downloads the newest
 * release's APK asset to the app's storage with progress, and hands it to
 * the platform's installer (gated on the phone's "install unknown apps"
 * permission).
 *
 * The app is sideloaded as an APK (there is no store), so this is the only
 * update path. The version ordering and the response parse are the pure
 * domain in `data/UpdateCheck.kt` ([parseLatestRelease], [releaseVersion],
 * [releaseIsNewer], [pickApkAsset]); this class is the
 * Android-aware plumbing around them (the OkHttp fetch + streamed
 * download, the [FileProvider] uri, the install intent, and the
 * [android.Manifest.permission.REQUEST_INSTALL_PACKAGES] gate).
 *
 * The pure domain is what the unit tests pin; the network + install here
 * are driven by the UI (see [UpdatePane]) and never unit-tested with a
 * live network.
 */
class UpdateManager(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Test seam: the HTTP client (a real [OkHttpClient] by default). */
    private val client: OkHttpClient = OkHttpClient(),
    /** Surfaces a plain message (a toast) to the UI - the view model's event sink. */
    private val onEvent: (String) -> Unit,
) {

    // ---- state (the pane renders this) ----

    /**
     * The area's lifecycle (see [UpdateState]). The pane reads it to pick
     * its copy and which buttons are live.
     */
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)

    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /**
     * The download's progress, `0f` to `1f` (meaningful only while the
     * state is [UpdateState.Downloading]; a response with no known length
     * jumps straight to `1f` when it lands).
     */
    private val _progress = MutableStateFlow(0f)

    val progress: StateFlow<Float> = _progress.asStateFlow()

    /**
     * Whether the user went to the phone's "install unknown apps" settings
     * and has not yet come back: set when [requestInstall] has to open
     * those settings instead of installing, consumed (and acted on) by
     * [onActivityResumed] when the activity returns.
     */
    @Volatile
    private var awaitingPermission = false

    // ---- the check ----

    /**
     * Checks the releases page for a newer build. The running version is
     * [BuildConfig.VERSION_NAME]; the newest release's tag (a leading
     * `v`/`V` stripped) is compared to it NUMERICALLY per component
     * (never by string, so `0.10.0 > 0.9.0`). The result settles to
     * [UpdateState.UpToDate] (equal or older) or [UpdateState.UpdateAvailable]
     * (strictly newer), or [UpdateState.Error] on a network failure, no
     * published release, or an unexpected response.
     */
    fun checkForLatest() {
        if (_state.value is UpdateState.Checking) return
        _state.value = UpdateState.Checking
        scope.launch {
            try {
                val release = fetchLatestRelease()
                val current = BuildConfig.VERSION_NAME
                val latest = releaseVersion(release.tagName)
                _state.value = if (releaseIsNewer(release.tagName, current)) {
                    UpdateState.UpdateAvailable(current, latest, release)
                } else {
                    UpdateState.UpToDate(current, latest)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Error(checkErrorMessage(e))
            }
        }
    }

    // ---- the download + install ----

    /**
     * Downloads the newest release's APK asset (the [pickApkAsset] pick -
     * the first `.apk` in the release's assets, never a hard-coded file
     name) into the app's storage, reporting [progress]. On success the
     * state settles to [UpdateState.ReadyToInstall] with the APK's path;
     * on a missing asset or a download failure it settles to
     * [UpdateState.Error]. Only callable while [UpdateState.UpdateAvailable].
     */
    fun updateToLatest() {
        val available = _state.value as? UpdateState.UpdateAvailable ?: return
        val asset = pickApkAsset(available.release)
        if (asset == null) {
            _state.value = UpdateState.Error("That release has no APK to install.")
            return
        }
        _state.value = UpdateState.Downloading(available.latestVersion)
        _progress.value = 0f
        scope.launch {
            try {
                val file = downloadTo(asset.downloadUrl, apkFile()) { p -> _progress.value = p }
                _state.value = UpdateState.ReadyToInstall(available.latestVersion, file.absolutePath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Error(downloadErrorMessage(e))
            }
        }
    }

    /**
     * The user tapped the install action while [UpdateState.ReadyToInstall]:
     * when the phone's "install unknown apps" permission is granted, launch
     * the platform installer over the APK's [FileProvider] uri; when it is
     * not, explain the requirement in a plain message and open the phone's
     * own install-settings screen (the download is kept and
     * [onActivityResumed] offers the retry path on the return).
     */
    fun requestInstall(activity: Activity) {
        val ready = _state.value as? UpdateState.ReadyToInstall ?: return
        val uri = installUri(ready.apkPath)
        if (uri == null) {
            onEvent("The downloaded file is missing. Download it again to install.")
            return
        }
        if (canInstallPackages()) {
            activity.startActivity(installIntent(uri))
        } else {
            awaitingPermission = true
            onEvent(
                "Android requires the \"install unknown apps\" permission for this app. " +
                    "Allow it in the phone's settings, then come back and tap Install again.",
            )
            runCatching { activity.startActivity(manageInstallSettingsIntent()) }
        }
    }

    /**
     * Called from the activity's onResume (the return from the install
     * settings, or any resume). Only acts when [requestInstall] sent the
     * user to the settings screen: if the permission is now granted the
     * install is launched automatically (the kept APK), otherwise the
     * download is kept and the retry path (the Install button) is offered.
     */
    fun onActivityResumed(activity: Activity) {
        if (!awaitingPermission) return
        awaitingPermission = false
        val ready = _state.value as? UpdateState.ReadyToInstall ?: return
        if (canInstallPackages()) {
            installUri(ready.apkPath)?.let { activity.startActivity(installIntent(it)) }
        } else {
            onEvent(
                "The \"install unknown apps\" permission is still off. " +
                    "The download is kept - tap Install to try again.",
            )
        }
    }

    // ---- the platform seams (kept here so the pane stays presentational) ----

    /** Whether the phone allows this app to install packages (API 26+; the app's min). */
    fun canInstallPackages(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** The APK's [FileProvider] uri (the installer's grant), or `null` when the file is gone. */
    fun installUri(apkPath: String): Uri? {
        val file = File(apkPath)
        if (!file.exists()) return null
        return runCatching {
            FileProvider.getUriForFile(context, fileProviderAuthority, file)
        }.getOrNull()
    }

    private fun installIntent(uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /** The phone's own "install unknown apps" screen for this app (API 26+).
     *  The action is [ACTION_MANAGE_APP_INSTALL_SETTINGS] - the pruned compile
     *  stub omits the `Settings` constant, so the literal value the platform
     *  broadcasts. */
    private fun manageInstallSettingsIntent(): Intent =
        Intent(ACTION_MANAGE_APP_INSTALL_SETTINGS).apply {
            data = Uri.parse("package:" + context.packageName)
        }

    private val fileProviderAuthority: String
        get() = context.packageName + FILE_PROVIDER_AUTHORITY_SUFFIX

    /** Where a downloaded APK lives (the app-internal folder the provider exposes). */
    private fun apkFile(): File {
        val dir = File(context.filesDir, "updates")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "lumi-update.apk")
    }

    // ---- the network (the pure parse lives in data) ----

    /**
     * Fetches the `/releases/latest` response and parses it (on the IO
     * dispatcher; the body parse is the pure [parseLatestRelease]). A 404
     * (no published release) is its own error, and any other non-2xx
     * answer or network failure is an IO error.
     */
    private suspend fun fetchLatestRelease(): LatestRelease =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "lumi")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> parseLatestRelease(body)
                    response.code == 404 -> throw NoReleasesException()
                    else -> throw IOException("GitHub answered with code ${response.code}")
                }
            }
        }

    /**
     * Downloads [url] to [target] (on the IO dispatcher), streaming it and
     * reporting [onProgress] (`0f`..`1f`; a response with no known length
     * reports `1f` when it lands). A non-2xx answer or an empty body is an
     * IO error.
     */
    private suspend fun downloadTo(url: String, target: File, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("User-Agent", "lumi").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("The download answered with code ${response.code}")
                }
                val body = response.body ?: throw IOException("The download came back empty")
                val total = body.contentLength()
                var received = 0L
                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                target.outputStream().use { out ->
                    body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n == -1) break
                            out.write(buffer, 0, n)
                            received += n
                            if (total > 0) onProgress((received.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                if (total <= 0) onProgress(1f)
                target
            }
        }

    // ---- plain error copy (a failure never crashes; it is a message) ----

    private fun checkErrorMessage(e: Throwable): String =
        when (e) {
            is NoReleasesException -> "There are no published releases yet. Check back later."
            is IOException -> "Couldn't reach the releases page. Check the connection and try again."
            else -> "Couldn't check for updates. Try again."
        }

    private fun downloadErrorMessage(e: Throwable): String =
        when (e) {
            is IOException -> "The download didn't finish. Check the connection and try again."
            else -> "The download didn't finish. Try again."
        }

    /** No published release exists (the GitHub API answers 404 for /releases/latest). */
    private class NoReleasesException : IOException("No releases published yet")

    private companion object {
        /** The app's own public releases, newest first (unauthenticated; the repo is public). */
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/jsnto/lumi/releases/latest"

        /** The FileProvider authority (the manifest's `${applicationId}.update.fileprovider`). */
        const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".update.fileprovider"

        /** The APK's mime type (the install intent's data type). */
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"

        /** The download's stream-buffer size (64 KiB). */
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024

        /**
         * The "install unknown apps" settings action
         * (`android.provider.Settings.ACTION_MANAGE_APP_INSTALL_SETTINGS`,
         * API 26+). The pruned compile stub omits that `Settings` constant,
         * so the literal value the platform uses.
         */
        const val ACTION_MANAGE_APP_INSTALL_SETTINGS =
            "android.settings.MANAGE_APP_INSTALL_SETTINGS"
    }
}

/**
 * The settings "Update" area's lifecycle. From an unchecked [Idle], a
 * check settles to [UpToDate] (the running build is the newest) or
 * [UpdateAvailable] (a newer release exists and can be downloaded); the
 * download runs through [Downloading] (with a separate progress flow) to
 * [ReadyToInstall] (the APK is on disk, ready for the platform installer);
 * any step can settle to [Error] with a plain user-facing message (a
 * network failure, no published release, or no APK asset).
 */
sealed class UpdateState {
    /** No check has been made yet. */
    object Idle : UpdateState()

    /** A check is in flight. */
    object Checking : UpdateState()

    /** Checked; the running build is the newest ([currentVersion] == [latestVersion]). */
    data class UpToDate(val currentVersion: String, val latestVersion: String) : UpdateState()

    /** Checked; [latestVersion] is newer than [currentVersion] and [release] carries its APK. */
    data class UpdateAvailable(
        val currentVersion: String,
        val latestVersion: String,
        val release: LatestRelease,
    ) : UpdateState()

    /** Downloading [latestVersion] (the progress is the manager's [com.lumi.player.ui.UpdateManager.progress]). */
    data class Downloading(val latestVersion: String) : UpdateState()

    /** [latestVersion] is downloaded to [apkPath]; install it to update. */
    data class ReadyToInstall(val latestVersion: String, val apkPath: String) : UpdateState()

    /** A step failed; [message] is the plain user-facing copy. */
    data class Error(val message: String) : UpdateState()
}
