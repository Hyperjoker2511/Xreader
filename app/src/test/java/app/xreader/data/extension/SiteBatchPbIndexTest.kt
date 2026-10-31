package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeExtensionRepoDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.data.net.HttpTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Site-batch Phase A / WS1: the Keiyoushi extension-repo
 * import, verified against the real code paths.
 *
 * (a) GZIP — the capture arrived as gzip bytes; the reader
 *     must accept them whatever the transport did.
 * (b) WRAPPER — the real file's field 101 is ONE wrapper
 *     message holding every extension; the split-wrapper and
 *     unwrapped repeated-101 shapes must read too. The REAL
 *     capture (when present on disk) is fed through the
 *     reader as the ground truth: 1,440 extensions, 2,353
 *     sources, 496 English, 1,486 deduped sites.
 * (c) THE WALK — the literal user paste
 *     `https://github.com/keiyoushi/extensions/raw/repo/index.pb`
 *     through RepoLinkNormaliser + RepoManager.addRepoOrDetect,
 *     served by a FakeTransport: the .pb candidate must be
 *     asked for FIRST and, when it answers, ONLY.
 */
class SiteBatchPbIndexTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── (b) the synthetic wrapper shape ─────────────────────

    @Test
    fun `wrapper shape synthetic index parses`() {
        val index = PbIndexReader.read(PbIndexFixture.rawBytes())
        assertNotNull(index)
        assertEquals(PbIndexFixture.REPO_NAME, index!!.name)
        assertEquals(PbIndexFixture.SIGNING_KEY, index.signingKey)
        assertNull(index.extensionListUrl)
        assertEquals(2, index.extensions.size)
        val one = index.extensions[0]
        assertEquals(PbIndexFixture.EXT_ONE_NAME, one.name)
        assertEquals(PbIndexFixture.EXT_ONE_PKG, one.packageName)
        assertEquals(42L, one.versionCode)
        assertEquals("4.2", one.versionName)
        assertEquals(2, one.sources.size)
        assertEquals(PbIndexFixture.SITE_ONE, one.sources[0].homeUrl)
        assertEquals("en", one.sources[0].language)
        val two = index.extensions[1]
        assertEquals(1, two.sources.size)
        assertEquals(
            listOf("https://quill-mirror.example"),
            two.sources[0].mirrorUrls
        )
        // The detector dedupes the two-language lantern site.
        val foreign = RepoFormatDetector.detectPb(PbIndexFixture.rawBytes())
        assertNotNull(foreign)
        assertEquals(2, foreign!!.sites.size)
    }

    @Test
    fun `split wrapper across two field-101 payloads still yields every extension`() {
        val index = PbIndexReader.read(PbIndexFixture.splitWrapperBytes())
        assertNotNull(index)
        assertEquals(
            listOf(PbIndexFixture.EXT_ONE_NAME, PbIndexFixture.EXT_TWO_NAME),
            index!!.extensions.map { it.name }
        )
        assertEquals(3, index.extensions.sumOf { it.sources.size })
    }

    @Test
    fun `extensions written directly as repeated field-101 entries parse`() {
        val index = PbIndexReader.read(PbIndexFixture.repeatedExtensionBytes())
        assertNotNull(index)
        assertEquals(
            listOf(PbIndexFixture.EXT_ONE_NAME, PbIndexFixture.EXT_TWO_NAME),
            index!!.extensions.map { it.name }
        )
        assertEquals(3, index.extensions.sumOf { it.sources.size })
    }

    // ── (a) gzip ────────────────────────────────────────────

    @Test
    fun `gzipped synthetic index parses at the reader level`() {
        val gzipped = PbIndexFixture.gzippedBytes()
        // Sanity: the fixture really is gzip on the outside.
        assertEquals(0x1f.toByte(), gzipped[0])
        assertEquals(0x8b.toByte(), gzipped[1])
        val index = PbIndexReader.read(gzipped)
        assertNotNull("gzip-wrapped index.pb must parse", index)
        assertEquals(PbIndexFixture.REPO_NAME, index!!.name)
        assertEquals(2, index.extensions.size)
        assertEquals(3, index.extensions.sumOf { it.sources.size })
        val foreign = RepoFormatDetector.detectPb(gzipped)
        assertNotNull(foreign)
        assertEquals(2, foreign!!.sites.size)
    }

    @Test
    fun `corrupt gzip returns null instead of throwing`() {
        val corrupt = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 0x55, 0x66)
        assertNull(PbIndexReader.read(corrupt))
    }

    // ── (b) the REAL capture, when present on disk ──────────

    private fun captureFile(name: String): File? {
        var dir: File? =
            File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val base = dir ?: return null
            val candidate = File(base, "fixtures-capture/keiyoushi/$name")
            if (candidate.exists()) return candidate
            dir = base.parentFile
        }
        return null
    }

    private fun assertRealCatalogue(index: PbIndex?) {
        assertNotNull("the real Keiyoushi capture must parse", index)
        assertEquals("Keiyoushi", index!!.name)
        assertEquals(1440, index.extensions.size)
        assertEquals(2353, index.extensions.sumOf { it.sources.size })
        assertEquals(
            496,
            index.extensions.sumOf { ext ->
                ext.sources.count { it.language == "en" }
            }
        )
        assertTrue(index.extensions.all { it.name.isNotBlank() })
        assertTrue(index.extensions.all { it.packageName.isNotBlank() })
        assertTrue(
            index.extensions.all { ext ->
                ext.sources.all {
                    it.name.isNotBlank() &&
                        (it.homeUrl.startsWith("https://") ||
                            it.homeUrl.startsWith("http://"))
                }
            }
        )
    }

    @Test
    fun `real keiyoushi raw capture parses to the full catalogue`() {
        val raw = captureFile("index-raw.pb")
        assumeTrue("capture not on disk in this environment", raw != null)
        assertRealCatalogue(PbIndexReader.read(raw!!.readBytes()))
        val foreign = RepoFormatDetector.detectPb(raw.readBytes())
        assertNotNull(foreign)
        assertEquals("Keiyoushi", foreign!!.ecosystemName)
        // 2,353 sources dedupe to 1,486 distinct homeUrls.
        assertEquals(1486, foreign.sites.size)
    }

    @Test
    fun `real keiyoushi gzip wire capture parses to the same catalogue`() {
        val wire = captureFile("index.pb")
        assumeTrue("capture not on disk in this environment", wire != null)
        val bytes = wire!!.readBytes()
        assertEquals(0x1f.toByte(), bytes[0])
        assertEquals(0x8b.toByte(), bytes[1])
        assertRealCatalogue(PbIndexReader.read(bytes))
    }

    // ── (c) the walk ────────────────────────────────────────

    @Test
    fun `normaliser puts the keiyoushi pb file first`() {
        val candidates = RepoLinkNormaliser.candidates(KEIYOUSHI_PASTE)
        assertEquals(RAW_PB_URL, candidates.first())
    }

    private fun bytesResponse(code: Int, bytes: ByteArray) =
        HttpTransport.Response(
            code = code,
            headers = mapOf("Content-Length" to bytes.size.toString()),
            body = bytes.inputStream(),
            isPartialContent = false
        )

    /** A RepoManager whose transport serves [pbBytes] at the
     * raw index.pb URL and 404s everything else. */
    private fun managerServing(
        pbBytes: ByteArray,
        record: MutableList<String>
    ): RepoManager {
        val repoDao = FakeExtensionRepoDao()
        val extDao = FakeInstalledExtensionDao()
        val store = ExtensionStore(tmp.newFolder())
        val sessionStore = SessionStore(testVault())
        val transport = FakeTransport { request ->
            record += request.url
            if (request.url == RAW_PB_URL) {
                AppResult.Ok(bytesResponse(200, pbBytes))
            } else {
                AppResult.Ok(bytesResponse(404, ByteArray(0)))
            }
        }
        val service = ExtensionService(
            extDao, store,
            HostContextFactory(store, sessionStore, transport),
            FakeDexLoader()
        ) { FakeJsRuntime() }
        return RepoManager(
            repoDao, extDao, store, transport, service, sessionStore
        )
    }

    @Test
    fun `walk accepts a direct index pb link served raw`() = runBlocking {
        val requests = mutableListOf<String>()
        val manager = managerServing(PbIndexFixture.rawBytes(), requests)
        val outcome = manager.addRepoOrDetect("Fixture", KEIYOUSHI_PASTE)
        val foreign = (outcome as? RepoManager.AddRepoOutcome.Foreign)?.format
        assertNotNull("expected Foreign, got $outcome", foreign)
        assertEquals(PbIndexFixture.REPO_NAME, foreign!!.ecosystemName)
        assertEquals(2, foreign.sites.size)
        // The file itself was asked for first — and only.
        assertEquals(listOf(RAW_PB_URL), requests)
    }

    @Test
    fun `walk accepts a direct index pb link served gzipped`() = runBlocking {
        val requests = mutableListOf<String>()
        val manager = managerServing(PbIndexFixture.gzippedBytes(), requests)
        val outcome = manager.addRepoOrDetect("Fixture", KEIYOUSHI_PASTE)
        val foreign = (outcome as? RepoManager.AddRepoOutcome.Foreign)?.format
        assertNotNull("expected Foreign, got $outcome", foreign)
        assertEquals(PbIndexFixture.REPO_NAME, foreign!!.ecosystemName)
        assertEquals(2, foreign.sites.size)
        assertEquals(listOf(RAW_PB_URL), requests)
    }

    @Test
    fun `walk accepts the real keiyoushi gzip capture`() = runBlocking {
        val wire = captureFile("index.pb")
        assumeTrue("capture not on disk in this environment", wire != null)
        val requests = mutableListOf<String>()
        val manager = managerServing(wire!!.readBytes(), requests)
        val outcome = manager.addRepoOrDetect("Keiyoushi", KEIYOUSHI_PASTE)
        val foreign = (outcome as? RepoManager.AddRepoOutcome.Foreign)?.format
        assertNotNull("expected Foreign, got $outcome", foreign)
        assertEquals("Keiyoushi", foreign!!.ecosystemName)
        assertEquals(1486, foreign.sites.size)
        assertEquals(listOf(RAW_PB_URL), requests)
    }

    companion object {
        const val KEIYOUSHI_PASTE =
            "https://github.com/keiyoushi/extensions/raw/repo/index.pb"
        const val RAW_PB_URL =
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb"
    }
}
