package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeExtensionRepoDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.extensionapi.Source
import app.xreader.extensionapi.SourceException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.4 (finding #3 close-out, WS-2 items 11–14): the
 * embedded-JSON engine tolerances, each proven THROUGH THE
 * STORED PATH — the shipped BrightNovel definition installed
 * by [RepoManager.installBundledDefaults] and read back via
 * [ExtensionService.source] — against the documented payload
 * variants under src/test/resources/v154/ (provenance header
 * in each fixture; live capture is impossible from this
 * egress and the authenticated leg stays a device-proof
 * item).
 *
 * - Item 11: the page object wrapped as {"page": …} and as
 *   {"data": …} (the maintained extension reads props from
 *   root.page.props / root.data.props) still reads rows.
 * - Item 12: a data-page reachable only by the raw-body
 *   regex, and one reachable only after JSON-string
 *   unescape, still read rows.
 * - Item 13: the paginator named "series" (not "seriesList")
 *   reads rows; a cover object carrying only thumbnail_url
 *   binds its cover; an UNRELATED terminal "url" does NOT
 *   alias (the parent-segment guard).
 * - Item 14: a shape-missing payload leaves a DiagnosticLog
 *   record carrying the payload's KEY NAMES and provably no
 *   values (the fixture's secret-shaped value must be
 *   absent from every entry).
 */
class V154EnvelopeToleranceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private companion object {
        const val PACKAGE_ID = "bundled.bundled-brightnovel"
        const val SOURCE_ID = "bundled-brightnovel"
        const val SERIES_URL = "https://brightnovels.com/series"
    }

    private fun resourceText(name: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) {
            "missing test resource $name"
        }.readBytes().toString(Charsets.UTF_8)

    /** Install the bundled defaults over a transport serving
     * [pageHtml] at the catalogue URL and return the stored
     * path's BrightNovel source. */
    private suspend fun storedBrightNovel(pageHtml: String): Source {
        val store = ExtensionStore(tmp.newFolder())
        val extDao = FakeInstalledExtensionDao()
        val repoDao = FakeExtensionRepoDao()
        val sessionStore = SessionStore(testVault())
        val transport = FakeTransport { request ->
            if (request.url == SERIES_URL) {
                FakeTransport.ok(pageHtml)
            } else {
                AppResult.Ok(FakeTransport.response(404, ""))
            }
        }
        val service = ExtensionService(
            extDao, store,
            HostContextFactory(store, sessionStore, transport),
            FakeDexLoader()
        ) { FakeJsRuntime() }
        val manager = RepoManager(
            repoDao, extDao, store, transport, service, sessionStore
        )
        manager.installBundledDefaults()
        val row = extDao.getByPackageId(PACKAGE_ID)
            ?: throw AssertionError("no bundled BrightNovel row")
        val handle = (service.source(row.id, SOURCE_ID)
            as? AppResult.Ok)?.value
            ?: throw AssertionError("no handle for BrightNovel")
        return handle.source
    }

    private fun assertDullardAndSecondSon(source: Source) {
        val page = source.popular(1)
        assertEquals(2, page.books.size)
        assertEquals("The Dullard", page.books[0].title)
        assertEquals(
            "https://brightnovels.com/series/the-dullard",
            page.books[0].id
        )
        assertEquals("Second Son", page.books[1].title)
        assertEquals(
            "https://brightnovels.com/series/second-son",
            page.books[1].id
        )
    }

    // ── item 11: wrapped envelopes ────────────────────────

    @Test
    fun `page wrapped envelope reads rows through the stored path`() {
        runBlocking {
            val source = storedBrightNovel(
                resourceText("v154/brightnovel-series-page-envelope.html")
            )
            assertDullardAndSecondSon(source)
        }
    }

    @Test
    fun `data wrapped envelope reads rows through the stored path`() {
        runBlocking {
            val source = storedBrightNovel(
                resourceText("v154/brightnovel-series-data-envelope.html")
            )
            assertDullardAndSecondSon(source)
        }
    }

    // ── item 12: data-page extraction stages ──────────────

    @Test
    fun `regex only data page reads rows`() {
        runBlocking {
            val source = storedBrightNovel(
                resourceText("v154/brightnovel-series-regex-only.html")
            )
            assertDullardAndSecondSon(source)
        }
    }

    @Test
    fun `json escaped data page reads rows after unescape`() {
        runBlocking {
            val source = storedBrightNovel(
                resourceText("v154/brightnovel-series-unescape.html")
            )
            assertDullardAndSecondSon(source)
        }
    }

    // ── item 13: terminal-segment aliases ─────────────────

    @Test
    fun `series named paginator reads rows`() {
        runBlocking {
            val source = storedBrightNovel(
                resourceText("v154/brightnovel-series-series-alias.html")
            )
            assertDullardAndSecondSon(source)
        }
    }

    @Test
    fun `thumbnail only cover binds through the cover url alias`() {
        runBlocking {
            val source = storedBrightNovel(
                resourceText("v154/brightnovel-series-thumbnail-cover.html")
            )
            val page = source.popular(1)
            assertEquals(2, page.books.size)
            assertEquals(
                "https://brightnovels.com/covers/dullard-thumb.jpg",
                page.books[0].coverUrl
            )
            assertEquals(
                "https://brightnovels.com/covers/second-thumb.jpg",
                page.books[1].coverUrl
            )
        }
    }

    @Test
    fun `unrelated terminal url does not alias`() {
        val record = Json.parseToJsonElement(
            """{"link":{"thumbnail_url":"https://x/thumb.jpg"},""" +
                """"cover":{"thumbnail_url":"https://x/cover.jpg"}}"""
        ) as JsonObject
        // The guard: "url" aliases to "thumbnail_url" ONLY
        // under a "cover" parent segment.
        assertNull(EmbeddedJson.resolve(record, "link.url"))
        assertNull(EmbeddedJson.stringAt(record, "link.url"))
        assertEquals(
            "https://x/cover.jpg",
            (EmbeddedJson.resolve(record, "cover.url")
                as JsonPrimitive).content
        )
        assertEquals(
            "https://x/cover.jpg",
            EmbeddedJson.stringAt(record, "cover.url")
        )
    }

    // ── item 14: the diagnostics dividend ─────────────────

    @Test
    fun `shape miss logs the key names seen and no values`() {
        runBlocking {
            DiagnosticLog.clear()
            val source = storedBrightNovel(
                resourceText("v154/brightnovel-series-shape-miss.html")
            )
            val error = assertThrows(SourceException::class.java) {
                source.popular(1)
            }
            assertTrue(
                error.message?.contains(
                    DeclarativeSource.SHAPE_MISS_MARKER
                ) == true
            )
            val entries = DiagnosticLog.entries()
            assertTrue(
                "a shape-miss record must name the props keys seen; " +
                    "entries were: $entries",
                entries.any {
                    it.tag == "shape-miss" &&
                        it.message.contains("catalogueX")
                }
            )
            assertTrue(
                "no entry may carry a payload VALUE",
                entries.none {
                    it.message.contains("sk-live-9f8e7d6c5b4a") ||
                        it.detail.contains("sk-live-9f8e7d6c5b4a")
                }
            )
            DiagnosticLog.clear()
        }
    }
}
