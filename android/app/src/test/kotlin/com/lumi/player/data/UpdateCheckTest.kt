package com.lumi.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's self-update support (pure, no network, no Android): the
 * dotted version ordering (NUMERIC per component, so `0.10.0 > 0.9.0` -
 * never a string compare), the release-tag -> version normalization (a
 * leading `v`/`V` stripped), and the parse of a GitHub `/releases/latest`
 * response (a fixture mimicking the API, including the `assets` array).
 *
 * The network fetch + download + install live in
 * [com.lumi.player.ui.UpdateManager]; this is the ordering + parse truth
 * it feeds, pinned here without a live network.
 */
class UpdateCheckTest {

    // ---- compareVersions (numeric per component) ----

    @Test
    fun `two digit component beats one digit numerically - not by string`() {
        // The exact case the spec calls out: a string compare says
        // "0.10.0" < "0.9.0" (because "1" < "9" at index 1); numerically
        // 10 > 9.
        assertTrue("0.10.0 must be newer than 0.9.0", compareVersions("0.10.0", "0.9.0") > 0)
        assertTrue("0.9.0 must be older than 0.10.0", compareVersions("0.9.0", "0.10.0") < 0)
        assertEquals(0, compareVersions("0.9.0", "0.9.0"))
    }

    @Test
    fun `equal versions compare zero`() {
        assertEquals(0, compareVersions("1.2.3", "1.2.3"))
        assertEquals(0, compareVersions("0.1.0", "0.1.0"))
    }

    @Test
    fun `major and minor components compare numerically`() {
        assertTrue(compareVersions("2.0.0", "1.9.9") > 0)
        assertTrue(compareVersions("1.10.0", "1.9.9") > 0)
        assertTrue(compareVersions("1.9.0", "1.10.0") < 0)
        assertTrue(compareVersions("10.0.0", "9.0.0") > 0)
        assertTrue(compareVersions("9.0.0", "10.0.0") < 0)
    }

    @Test
    fun `patch component compares numerically`() {
        assertTrue(compareVersions("0.1.2", "0.1.10") < 0) // 2 < 10 (string would say 2 > 10)
        assertTrue(compareVersions("0.1.10", "0.1.2") > 0)
        assertTrue(compareVersions("0.2.0", "0.1.9") > 0)
    }

    @Test
    fun `a missing trailing component counts as zero`() {
        assertEquals("1.0 == 1.0.0", 0, compareVersions("1.0", "1.0.0"))
        assertEquals("1.0.0 == 1.0", 0, compareVersions("1.0.0", "1.0"))
        assertTrue("1.0.1 > 1.0", compareVersions("1.0.1", "1.0") > 0)
        assertTrue("1.0 < 1.0.1", compareVersions("1.0", "1.0.1") < 0)
    }

    @Test
    fun `the first differing component decides`() {
        assertTrue(compareVersions("1.2.9", "1.3.0") < 0)
        assertTrue(compareVersions("1.3.0", "1.2.9") > 0)
        assertTrue(compareVersions("1.2.3", "1.2.30") < 0) // 3 < 30
    }

    @Test
    fun `non-numeric components fall back to string order for that component only`() {
        // "0.2.0rc1" vs "0.2.0": the third component "0rc1" is not an int,
        // so it compares by string; the ordering is still deterministic and
        // the numeric prefix is honored.
        assertTrue(compareVersions("0.3.0", "0.2.0rc1") > 0)
        assertTrue(compareVersions("0.2.0rc1", "0.1.0") > 0) // 2 > 1 at the minor
    }

    // ---- releaseVersion (strip the leading v) ----

    @Test
    fun `a leading v is stripped`() {
        assertEquals("0.2.0", releaseVersion("v0.2.0"))
        assertEquals("0.10.0", releaseVersion("v0.10.0"))
    }

    @Test
    fun `a leading V is stripped (case-insensitive marker)`() {
        assertEquals("0.2.0", releaseVersion("V0.2.0"))
    }

    @Test
    fun `a tag with no marker is its own version`() {
        assertEquals("0.2.0", releaseVersion("0.2.0"))
        assertEquals("1.2.3", releaseVersion("1.2.3"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("0.2.0", releaseVersion(" v0.2.0 "))
        assertEquals("0.2.0", releaseVersion("  0.2.0  "))
    }

    // ---- releaseIsNewer (strictly newer) ----

    @Test
    fun `a strictly newer tag is an update`() {
        assertTrue(releaseIsNewer("v0.2.0", "0.1.0"))
        assertTrue(releaseIsNewer("v0.10.0", "0.9.0")) // numeric, not string
        assertTrue(releaseIsNewer("1.0.0", "0.9.9"))
    }

    @Test
    fun `an equal tag is not an update`() {
        assertFalse("the running build IS the latest", releaseIsNewer("v0.2.0", "0.2.0"))
        assertFalse(releaseIsNewer("0.2.0", "0.2.0"))
    }

    @Test
    fun `an older tag is not an update`() {
        assertFalse(releaseIsNewer("v0.1.0", "0.2.0"))
        assertFalse(releaseIsNewer("v0.9.0", "0.10.0")) // numeric, not string
    }

    // ---- parseLatestRelease (the GitHub /releases/latest shape) ----

    @Test
    fun `a realistic release fixture parses its tag and assets`() {
        val release = parseLatestRelease(FIXTURE_LATEST_RELEASE)
        assertEquals("v0.2.0", release.tagName)
        assertEquals(2, release.assets.size)
        // The APK asset (the one the update downloads).
        val apk = release.assets.first { it.name.endsWith(".apk") }
        assertEquals("lumi-app-release.apk", apk.name)
        assertEquals(
            "https://github.com/jsnto/lumi/releases/download/v0.2.0/lumi-app-release.apk",
            apk.downloadUrl,
        )
        // A non-APK asset (a checksum) is also carried.
        val sha = release.assets.first { it.name.endsWith(".sha256") }
        assertEquals("lumi-app-release.apk.sha256", sha.name)
    }

    @Test
    fun `unknown top-level and per-asset fields are ignored`() {
        // The fixture carries the API's many metadata fields (id, author,
        // html_url, content_type, size, ...); the parser reads only
        // tag_name + assets[].name + assets[].browser_download_url and
        // tolerates the rest.
        val release = parseLatestRelease(FIXTURE_LATEST_RELEASE)
        assertEquals("v0.2.0", release.tagName)
        assertEquals(2, release.assets.size)
    }

    @Test
    fun `a release with no assets field reads an empty list`() {
        val release = parseLatestRelease("""{"tag_name":"v0.3.0"}""")
        assertEquals("v0.3.0", release.tagName)
        assertTrue(release.assets.isEmpty())
    }

    @Test
    fun `a release with an explicit empty assets array reads an empty list`() {
        val release = parseLatestRelease("""{"tag_name":"v0.3.0","assets":[]}""")
        assertTrue(release.assets.isEmpty())
    }

    // ---- pickApkAsset (the install pick) ----

    @Test
    fun `pickApkAsset selects the apk asset`() {
        val release = parseLatestRelease(FIXTURE_LATEST_RELEASE)
        val apk = pickApkAsset(release)
        assertEquals("lumi-app-release.apk", apk?.name)
        assertTrue(apk?.name?.endsWith(".apk") == true)
    }

    @Test
    fun `pickApkAsset selects the first apk when several exist`() {
        val json = """
            {
              "tag_name": "v1.0.0",
              "assets": [
                {"name": "lumi-debug.apk", "browser_download_url": "https://x/debug.apk"},
                {"name": "lumi-release.apk", "browser_download_url": "https://x/release.apk"}
              ]
            }
        """.trimIndent()
        val apk = pickApkAsset(parseLatestRelease(json))
        assertEquals("lumi-debug.apk", apk?.name)
    }

    @Test
    fun `pickApkAsset matches the extension case-insensitively`() {
        val json = """
            {"tag_name":"v1.0.0","assets":[{"name":"LUMI-APP.APK","browser_download_url":"https://x/a.apk"}]}
        """.trimIndent()
        val apk = pickApkAsset(parseLatestRelease(json))
        assertEquals("LUMI-APP.APK", apk?.name)
    }

    @Test
    fun `pickApkAsset is null when there is no apk asset`() {
        val json = """
            {"tag_name":"v1.0.0","assets":[{"name":"checksums.txt","browser_download_url":"https://x/txt"}]}
        """.trimIndent()
        assertNull(pickApkAsset(parseLatestRelease(json)))
    }

    @Test
    fun `pickApkAsset is null for a release with no assets`() {
        assertNull(pickApkAsset(parseLatestRelease("""{"tag_name":"v1.0.0"}""")))
    }

    /**
     * A fixture mimicking the public GitHub `/releases/latest` response
     * (the shape the API returns): the `tag_name`, the many metadata
     * fields the parser must ignore, and an `assets` array whose entries
     * carry `name` + `browser_download_url` among other fields.
     */
    private companion object {
        val FIXTURE_LATEST_RELEASE = """
            {
              "url": "https://api.github.com/repos/jsnto/lumi/releases/1000",
              "assets_url": "https://api.github.com/repos/jsnto/lumi/releases/1000/assets",
              "upload_url": "https://uploads.github.com/repos/jsnto/lumi/releases/1000/assets{?name,label}",
              "html_url": "https://github.com/jsnto/lumi/releases/tag/v0.2.0",
              "id": 1000,
              "author": {"login": "jsnto", "id": 1},
              "node_id": "RE_kwDOX1000",
              "tag_name": "v0.2.0",
              "target_commitish": "main",
              "name": "lumi v0.2.0",
              "draft": false,
              "prerelease": false,
              "created_at": "2026-11-01T12:00:00Z",
              "published_at": "2026-11-01T12:05:00Z",
              "assets": [
                {
                  "url": "https://api.github.com/repos/jsnto/lumi/releases/assets/2000",
                  "id": 2000,
                  "node_id": "RA_kwDOX2000",
                  "name": "lumi-app-release.apk",
                  "label": null,
                  "content_type": "application/vnd.android.package-archive",
                  "size": 19209989,
                  "download_count": 3,
                  "created_at": "2026-11-01T12:04:00Z",
                  "updated_at": "2026-11-01T12:04:00Z",
                  "browser_download_url": "https://github.com/jsnto/lumi/releases/download/v0.2.0/lumi-app-release.apk"
                },
                {
                  "url": "https://api.github.com/repos/jsnto/lumi/releases/assets/2001",
                  "id": 2001,
                  "node_id": "RA_kwDOX2001",
                  "name": "lumi-app-release.apk.sha256",
                  "label": null,
                  "content_type": "text/plain",
                  "size": 64,
                  "download_count": 1,
                  "created_at": "2026-11-01T12:04:00Z",
                  "updated_at": "2026-11-01T12:04:00Z",
                  "browser_download_url": "https://github.com/jsnto/lumi/releases/download/v0.2.0/lumi-app-release.apk.sha256"
                }
              ]
            }
        """.trimIndent()
    }
}
