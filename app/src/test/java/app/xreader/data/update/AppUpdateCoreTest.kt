package app.xreader.data.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.xreader.data.prefs.UpdatePrefs
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 pins (2026-11-01): the in-app updater's pure core.
 *
 * The rules being pinned are the user's own: the card shows
 * a SHORT plain-words summary only (the release body's
 * `## What's new` bullets), the check and the card each
 * happen at most once a day, and an install is offered
 * only for a download whose SHA-256 matches the release's
 * published checksum (asset first, body second, fail
 * closed when neither exists).
 */
class AppUpdateCoreTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    private fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()

    // ── Release parsing ────────────────────────────────────

    private val apkBytes = "fake-apk-bytes-for-xreader-1.3.5".toByteArray()
    private val apkSha = Sha256.ofBytes(apkBytes)

    private fun releaseJson(
        tag: String = "v1.3.5",
        body: String = defaultBody(),
        assets: String = defaultAssets()
    ): String = """
        {
          "url": "https://api.github.com/repos/Hyperjoker2511/Xreader/releases/1",
          "html_url": "https://github.com/Hyperjoker2511/Xreader/releases/tag/$tag",
          "tag_name": "$tag",
          "name": "Xreader $tag",
          "draft": false,
          "prerelease": false,
          "body": ${body.toJsonString()},
          "assets": [$assets]
        }
    """.trimIndent()

    private fun defaultBody(): String =
        "## What's new\n" +
            "- Manga reading is here\n" +
            "- Search is faster\n" +
            "- Sign-ins fixed\n" +
            "\n" +
            "## Details\n" +
            "Lots of technical detail a card should never show.\n" +
            "\n" +
            "SHA-256: $apkSha\n"

    private fun defaultAssets(): String = """
        {
          "name": "Xreader-v1.3.5.apk",
          "browser_download_url": "https://github.com/Hyperjoker2511/Xreader/releases/download/v1.3.5/Xreader-v1.3.5.apk",
          "size": 57915742
        },
        {
          "name": "SHA256SUMS.txt",
          "browser_download_url": "https://github.com/Hyperjoker2511/Xreader/releases/download/v1.3.5/SHA256SUMS.txt",
          "size": 88
        }
    """.trimIndent()

    private fun String.toJsonString(): String =
        "\"" + replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n") + "\""

    @Test
    fun `parse reads tag, apk asset and checksum asset`() {
        val release = ReleaseParsing.parseLatestRelease(releaseJson())
        assertNotNull(release)
        release!!
        assertEquals("v1.3.5", release.tagName)
        assertEquals("1.3.5", release.version)
        assertEquals("Xreader-v1.3.5.apk", release.apk.name)
        assertEquals(57915742L, release.apk.sizeBytes)
        assertEquals("SHA256SUMS.txt", release.checksumAsset?.name)
        assertTrue(release.htmlUrl.endsWith("/tag/v1.3.5"))
    }

    @Test
    fun `parse refuses a draft and a release without an apk`() {
        assertNull(
            ReleaseParsing.parseLatestRelease(
                releaseJson().replace("\"draft\": false", "\"draft\": true")
            )
        )
        assertNull(
            ReleaseParsing.parseLatestRelease(
                releaseJson(
                    assets = """
                        {
                          "name": "SHA256SUMS.txt",
                          "browser_download_url": "https://example.com/SHA256SUMS.txt",
                          "size": 88
                        }
                    """.trimIndent()
                )
            )
        )
        assertNull(ReleaseParsing.parseLatestRelease("not json"))
    }

    @Test
    fun `parse picks the xreader apk when several are attached`() {
        val release = ReleaseParsing.parseLatestRelease(
            releaseJson(
                assets = """
                    {
                      "name": "sample.apk",
                      "browser_download_url": "https://example.com/sample.apk",
                      "size": 999999999
                    },
                    {
                      "name": "Xreader-v1.3.5.apk",
                      "browser_download_url": "https://example.com/Xreader-v1.3.5.apk",
                      "size": 100
                    }
                """.trimIndent()
            )
        )
        assertEquals("Xreader-v1.3.5.apk", release?.apk?.name)
    }

    // ── Short summary (the card's text rule) ───────────────

    @Test
    fun `summary takes only the what's new bullets`() {
        val summary = ReleaseParsing.shortSummary(defaultBody())
        assertEquals(
            listOf("Manga reading is here", "Search is faster", "Sign-ins fixed"),
            summary
        )
    }

    @Test
    fun `summary is empty without the section and capped at four`() {
        assertEquals(
            emptyList<String>(),
            ReleaseParsing.shortSummary("## Details\n- Not a summary\n")
        )
        val five = "## What's new\n" + (1..5).joinToString("\n") { "- Line $it" }
        assertEquals(4, ReleaseParsing.shortSummary(five).size)
    }

    @Test
    fun `summary strips markdown noise`() {
        val body = "## What's new\n- **Bold** claim with [a link](https://x.y)\n"
        assertEquals(
            listOf("Bold claim with a link"),
            ReleaseParsing.shortSummary(body)
        )
    }

    // ── Version comparison ─────────────────────────────────

    @Test
    fun `version compare is tolerant and ordered`() {
        assertTrue(VersionNames.isNewer("v1.3.5", "1.3.4"))
        assertTrue(VersionNames.isNewer("1.4", "1.3.9"))
        assertTrue(VersionNames.isNewer("2.0.0", "1.99.99"))
        assertFalse(VersionNames.isNewer("1.3.4", "1.3.4"))
        assertFalse(VersionNames.isNewer("1.3.4", "v1.3.5"))
        assertFalse(VersionNames.isNewer("1.3", "1.3.1"))
        assertEquals(0, VersionNames.compare("v1.3.5", "1.3.5"))
        // A pre-release suffix is older than the plain release.
        assertTrue(VersionNames.isNewer("1.3.5", "1.3.5-beta"))
        assertFalse(VersionNames.isNewer("1.3.5-beta", "1.3.5"))
    }

    // ── Once-a-day gate ────────────────────────────────────

    @Test
    fun `auto check is due once per calendar day`() {
        val morning = ms("2026-11-01T08:00:00Z")
        val evening = ms("2026-11-01T21:30:00Z")
        val tomorrow = ms("2026-11-02T00:00:01Z")
        assertTrue(UpdateGate.dueForAutoCheck(true, null, morning, utc))
        assertFalse(
            UpdateGate.dueForAutoCheck(
                true, UpdateGate.dayOf(morning, utc), evening, utc
            )
        )
        assertTrue(
            UpdateGate.dueForAutoCheck(
                true, UpdateGate.dayOf(morning, utc), tomorrow, utc
            )
        )
        // The switch off means never due.
        assertFalse(UpdateGate.dueForAutoCheck(false, null, morning, utc))
    }

    @Test
    fun `card shows at most once per calendar day`() {
        val morning = ms("2026-11-01T08:00:00Z")
        val evening = ms("2026-11-01T21:30:00Z")
        val tomorrow = ms("2026-11-02T09:00:00Z")
        assertTrue(UpdateGate.mayShowCard(true, null, morning, utc))
        assertFalse(
            UpdateGate.mayShowCard(
                true, UpdateGate.dayOf(morning, utc), evening, utc
            )
        )
        assertTrue(
            UpdateGate.mayShowCard(
                true, UpdateGate.dayOf(morning, utc), tomorrow, utc
            )
        )
        assertFalse(UpdateGate.mayShowCard(false, null, morning, utc))
    }

    // ── Checksum extraction ────────────────────────────────

    @Test
    fun `checksum comes from the asset line naming the apk`() {
        val sums = "0".repeat(64) + "  other-file.zip\n" +
            apkSha + "  Xreader-v1.3.5.apk\n"
        assertEquals(
            apkSha,
            ReleaseParsing.extractChecksum(sums, null, "Xreader-v1.3.5.apk")
        )
    }

    @Test
    fun `checksum falls back to the body's sha line`() {
        assertEquals(
            apkSha,
            ReleaseParsing.extractChecksum(null, defaultBody(), "Xreader-v1.3.5.apk")
        )
        // A lone digest in a checksum file is used as-is.
        assertEquals(
            apkSha,
            ReleaseParsing.extractChecksum(apkSha, null, "Xreader-v1.3.5.apk")
        )
        // Nothing published anywhere: fail closed.
        assertNull(
            ReleaseParsing.extractChecksum(null, "no hashes here", "a.apk")
        )
        assertNull(ReleaseParsing.extractChecksum("", "", "a.apk"))
    }

    // ── SHA-256 ────────────────────────────────────────────

    @Test
    fun `sha256 matches the known abc vector and verifies files`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.ofBytes("abc".toByteArray())
        )
        val dir = Files.createTempDirectory("xr-update-test").toFile()
        val file = File(dir, "a.apk").apply { writeBytes(apkBytes) }
        assertTrue(Sha256.matches(file, apkSha))
        assertTrue(Sha256.matches(file, apkSha.uppercase()))
        assertFalse(Sha256.matches(file, "0".repeat(64)))
        assertFalse(Sha256.matches(file, "not-a-digest"))
        dir.deleteRecursively()
    }

    // ── Repository: check + verified download ──────────────

    private class FakeDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences
        ): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    private class FakeHttp(
        var latestPayload: String?,
        var checksumText: String?,
        var downloadBytes: ByteArray?
    ) : UpdateHttp {
        override suspend fun getText(url: String): String? = when {
            url == AppUpdateRepository.LATEST_RELEASE_URL -> latestPayload
            url.endsWith("SHA256SUMS.txt") -> checksumText
            else -> null
        }

        override suspend fun download(
            url: String,
            target: File,
            onProgress: (Long, Long) -> Unit
        ): Boolean {
            val bytes = downloadBytes ?: return false
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            onProgress(bytes.size.toLong(), bytes.size.toLong())
            return true
        }
    }

    private fun repo(
        http: FakeHttp,
        prefs: UpdatePrefs,
        installed: String = "1.3.4",
        now: Long = ms("2026-11-01T08:00:00Z")
    ) = AppUpdateRepository(
        http = http,
        prefs = prefs,
        installedVersion = { installed },
        nowMs = { now },
        zone = utc
    )

    @Test
    fun `check reports available, caches, and stamps the day`() = runTest {
        val prefs = UpdatePrefs(FakeDataStore())
        val http = FakeHttp(releaseJson(), null, null)
        val outcome = repo(http, prefs).checkNow()
        val available = outcome as? AppUpdateRepository.CheckOutcome.Available
        assertNotNull(available)
        assertEquals("1.3.5", available!!.release.version)
        assertEquals("2026-11-01", prefs.lastCheckDay())
        assertTrue(prefs.cachedReleaseJson().contains("v1.3.5"))
    }

    @Test
    fun `check reports up to date when installed is newest`() = runTest {
        val prefs = UpdatePrefs(FakeDataStore())
        val http = FakeHttp(releaseJson(), null, null)
        val outcome = repo(http, prefs, installed = "1.3.5").checkNow()
        assertEquals(AppUpdateRepository.CheckOutcome.UpToDate, outcome)
    }

    @Test
    fun `download verifies against the checksum asset and keeps the file`() =
        runTest {
            val prefs = UpdatePrefs(FakeDataStore())
            val sums = "$apkSha  Xreader-v1.3.5.apk\n"
            val http = FakeHttp(releaseJson(), sums, apkBytes)
            val repository = repo(http, prefs)
            val release = ReleaseParsing.parseLatestRelease(releaseJson())!!
            val dir = Files.createTempDirectory("xr-update-dl").toFile()
            val outcome = repository.downloadAndVerify(release, dir) { _, _ -> }
            val ready = outcome as? AppUpdateRepository.DownloadOutcome.Ready
            assertNotNull(ready)
            assertTrue(ready!!.file.isFile)
            assertTrue(ready.file.readBytes().contentEquals(apkBytes))
            dir.deleteRecursively()
        }

    @Test
    fun `download with a wrong checksum deletes the file`() = runTest {
        val prefs = UpdatePrefs(FakeDataStore())
        val sums = "${"f".repeat(64)}  Xreader-v1.3.5.apk\n"
        val http = FakeHttp(releaseJson(), sums, apkBytes)
        val repository = repo(http, prefs)
        val release = ReleaseParsing.parseLatestRelease(releaseJson())!!
        val dir = Files.createTempDirectory("xr-update-bad").toFile()
        val outcome = repository.downloadAndVerify(release, dir) { _, _ -> }
        assertTrue(
            outcome is AppUpdateRepository.DownloadOutcome.ChecksumMismatch
        )
        assertFalse(File(dir, "xreader-1.3.5.apk").exists())
        dir.deleteRecursively()
    }

    @Test
    fun `download fails closed when no checksum is published`() = runTest {
        val prefs = UpdatePrefs(FakeDataStore())
        val bareBody = "## What's new\n- Something\n"
        val payload = releaseJson(body = bareBody)
        val http = FakeHttp(payload, null, apkBytes)
        val repository = repo(http, prefs)
        val release = ReleaseParsing.parseLatestRelease(payload)!!
        val dir = Files.createTempDirectory("xr-update-none").toFile()
        val outcome = repository.downloadAndVerify(release, dir) { _, _ -> }
        assertTrue(
            outcome is AppUpdateRepository.DownloadOutcome.ChecksumMissing
        )
        dir.deleteRecursively()
    }

    // ── What's New rendering ───────────────────────────────

    @Test
    fun `notes format shapes headings bullets and paragraphs`() {
        val lines = ReleaseNotesFormat.lines(
            "# Title\n\n- One\n- Two\n\nA paragraph.\n\n## Sub\nMore."
        )
        assertEquals(
            listOf(
                NoteLine.Heading("Title", 1),
                NoteLine.Gap,
                NoteLine.Bullet("One"),
                NoteLine.Bullet("Two"),
                NoteLine.Gap,
                NoteLine.Paragraph("A paragraph."),
                NoteLine.Gap,
                NoteLine.Heading("Sub", 2),
                NoteLine.Paragraph("More.")
            ),
            lines
        )
    }
}
