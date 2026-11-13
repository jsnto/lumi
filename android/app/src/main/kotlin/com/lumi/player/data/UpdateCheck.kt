package com.lumi.player.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The app's self-update support (pure, JVM-testable, no network, no
 * Android): how a dotted version string compares to another, and how the
 * public GitHub `/releases/latest` response is parsed into the two facts
 * the settings "Update" area needs (the release's tag and the APK asset
 * to download).
 *
 * The app is sideloaded as an APK (there is no store), so "update to
 * latest" means fetching the newest public release of the `jsnto/lumi`
 * repository and installing its APK asset. The network + download +
 * install plumbing lives in [com.lumi.player.ui.UpdateManager]; this file
 * is the version/ordering truth and the response shape it feeds.
 */

/**
 * Compares two dotted version strings NUMERICALLY, per dot-separated
 * component, so that `0.10.0 > 0.9.0` (a plain string compare would say
 * the opposite: `"0.10.0" < "0.9.0"` because `"1" < "9"` at index 1).
 *
 * - A missing trailing component counts as `0`, so `"1.0" == "1.0.0"` and
 *   `"1.0.1" > "1.0"`.
 * - A component that is not a plain, non-negative integer (for example a
 *   pre-release suffix such as `"2rc"`) falls back to a string compare for
 *   THAT component only, so mixed shapes still order deterministically.
 *
 * The result is the sign of (a - b): negative when [a] is older, zero when
 * the versions are equal, positive when [a] is newer.
 */
fun compareVersions(a: String, b: String): Int {
    val pa = a.split(".")
    val pb = b.split(".")
    val n = maxOf(pa.size, pb.size)
    for (i in 0 until n) {
        val ca = pa.getOrNull(i) ?: "0"
        val cb = pb.getOrNull(i) ?: "0"
        val result = compareVersionComponent(ca, cb)
        if (result != 0) return result
    }
    return 0
}

/**
 * Compares one version component: numerically when both sides are plain
 * non-negative integers (the common case, and what makes `10 > 9`),
 * otherwise by string order so non-numeric suffixes still compare.
 */
private fun compareVersionComponent(a: String, b: String): Int {
    val na = a.toIntOrNull()
    val nb = b.toIntOrNull()
    return if (na != null && nb != null) na.compareTo(nb) else a.compareTo(b)
}

/**
 * Strips a release tag's leading version marker: `"v0.2.0"` -> `"0.2.0"`,
 * `"V0.2.0"` -> `"0.2.0"`, `"0.2.0"` -> `"0.2.0"` (a tag with no marker is
 * its own version). Surrounding whitespace is trimmed. A tag with no
 * marker is returned unchanged.
 */
fun releaseVersion(tag: String): String {
    val trimmed = tag.trim()
    return when {
        trimmed.startsWith("v", ignoreCase = true) -> trimmed.drop(1)
        else -> trimmed
    }
}

/**
 * Whether the release [tag] (its version, [releaseVersion]) is STRICTLY
 * newer than [currentVersion]. Equal (the running build IS the latest) or
 * older is not an update, so the update button stays disabled.
 */
fun releaseIsNewer(tag: String, currentVersion: String): Boolean =
    compareVersions(releaseVersion(tag), currentVersion) > 0

/**
 * One asset of a GitHub release: its file name and the url to download it
 * from. (The API response carries many more fields per asset; the parser
 * ignores the ones the app does not use.)
 */
@Serializable
data class ReleaseAsset(
    val name: String,
    @SerialName("browser_download_url")
    val downloadUrl: String,
)

/**
 * The public GitHub `/releases/latest` response, narrowed to the fields
 * the app uses: the release's tag and its assets. Unknown fields (the
 * dozens of metadata fields the API returns) are ignored on parse, and a
 * response with no `assets` field at all reads as an empty list.
 */
@Serializable
data class LatestRelease(
    @SerialName("tag_name")
    val tagName: String,
    val assets: List<ReleaseAsset> = emptyList(),
)

/** The JSON the release parser accepts (unknown fields tolerated). */
private val releaseJson = Json { ignoreUnknownKeys = true }

/**
 * Parses a `/releases/latest` response body into a [LatestRelease]. The
 * body is the shape GitHub returns (a `tag_name` string and an `assets`
 * array of objects carrying, among other fields, `name` and
 * `browser_download_url`). Throws on a body that is not a release object
 * (the caller turns that into a plain "couldn't check" message).
 */
fun parseLatestRelease(json: String): LatestRelease =
    releaseJson.decodeFromString(LatestRelease.serializer(), json)

/**
 * Picks the asset to install: the first asset whose name ends in `.apk`
 * (case-insensitive). The file name is NOT hard-coded - a release with a
 * differently named APK still installs. `null` when the release carries no
 * `.apk` asset (the caller reports "no APK to install").
 */
fun pickApkAsset(release: LatestRelease): ReleaseAsset? =
    release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
