package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.5 (§2, reality check b): the three-tier error law on
 * the JSON-endpoint chapters road, driven through the real
 * walk (chaptersFirst) against recorded-shape payloads on a
 * fake transport:
 *
 *  - a refusal page (403 HTML) is a hard error, never an
 *    empty "complete" list — the BrightNovel silent-zero;
 *  - a drifted envelope (declared array path gone) is the
 *    ShapeMiss tier;
 *  - rows present with the link field renamed is the
 *    RecordShapeMiss tier;
 *  - a literal [] with no declared total is an honest
 *    Ok(0)-complete;
 *  - a literal [] whose only hint is the spec's declared
 *    total is the no-chapters-yet tier, while a literal []
 *    against a total stated by the site itself is the
 *    suspicious-empty tier;
 *  - a valid 2-page walk whose page 2 fails ERRORS (never a
 *    truncate-commit);
 *  - a valid empty page 2 terminates the walk complete;
 *  - the single-shot (BrightNovel-shaped) road is strict
 *    too, through walkChapterPages.
 */
class V155EndpointWalkTierTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val base = "https://walk.example"
    private val host = "walk.example"
    private val bookUrl = "$base/book/walk-tale"

    private fun endpoint(page: Int) =
        "$base/api/books/walk-tale/chapters?page=$page"

    private val bookPage = """
        <html><head><title>Walk Tale</title></head><body>
        <h1>Walk Tale</h1>
        </body></html>
    """.trimIndent()

    private fun config(
        singleShot: Boolean = false,
        totalCount: Int? = null,
        totalCountPattern: String? = null
    ): DeclarativeConfig {
        val list = ListSpec(
            url = "$base/popular",
            results = "div.result",
            title = "a.t",
            link = "a.t@href"
        )
        return DeclarativeConfig(
            id = "walk-source",
            name = "Walk Source",
            lang = "en",
            baseUrl = base,
            search = list,
            popular = list,
            latest = null,
            details = DetailsSpec(
                title = "h1",
                chapters = ChapterListSpec(
                    list = "ul.chapters li a",
                    name = "@text",
                    link = "@href",
                    jsonNameField = "name",
                    jsonLinkField = "slug",
                    jsonLinkTemplate = "$base/book/walk-tale/{value}",
                    totalCount = totalCount,
                    totalCountPattern = totalCountPattern,
                    pagination = ChapterListPagination(
                        urlTemplate = if (singleShot) {
                            "$base/api/books/{bookSlug}/chapters?loaded=0"
                        } else {
                            "$base/api/books/{bookSlug}/chapters?page={page}"
                        },
                        bookIdPattern = "",
                        jsonArrayPath = "chapters",
                        pageSize = if (singleShot) 1000 else 2
                    )
                )
            ),
            chapter = ChapterSpec(content = "div.content")
        )
    }

    private fun source(
        config: DeclarativeConfig,
        pages: Map<String, Pair<Int, String>>,
        pageHtml: String = bookPage
    ): DeclarativeSource {
        val transport = FakeTransport { request ->
            when (request.url) {
                bookUrl -> FakeTransport.ok(pageHtml)
                else -> {
                    val scripted = pages[request.url]
                    if (scripted == null) {
                        AppResult.Ok(FakeTransport.response(404, "no"))
                    } else {
                        AppResult.Ok(
                            FakeTransport.response(
                                scripted.first, scripted.second
                            )
                        )
                    }
                }
            }
        }
        val context = testContext(
            transport,
            tmp.newFolder(),
            permissions = ExtensionPermissions(domains = listOf(host))
        )
        return DeclarativeSource(config, context)
    }

    private fun rowsJson(vararg slugs: String): String {
        val rows = slugs.joinToString(",") { slug ->
            "{\"name\":\"Chapter $slug\",\"slug\":\"$slug\"}"
        }
        return "{\"chapters\":[$rows]}"
    }

    private fun expectWalkError(
        config: DeclarativeConfig,
        pages: Map<String, Pair<Int, String>>,
        minCount: Int = 10,
        pageHtml: String = bookPage
    ): SourceException {
        val source = source(config, pages, pageHtml)
        try {
            source.chaptersFirst(
                SourceBook(id = bookUrl, title = "Walk Tale"), minCount
            )
            fail("the walk must error, not complete")
            throw AssertionError("unreachable")
        } catch (error: SourceException) {
            return error
        }
    }

    @Test
    fun `refusal page on page one is a hard error`() {
        val error = expectWalkError(
            config(),
            mapOf(endpoint(1) to (403 to "<html>One moment</html>"))
        )
        assertTrue(error.message!!.contains("HTTP 403"))
    }

    @Test
    fun `drifted envelope is the shape miss tier`() {
        // 2xx + parseable JSON, the declared path absent and
        // NO array anywhere for the envelope scan to find.
        val error = expectWalkError(
            config(),
            mapOf(
                endpoint(1) to (200 to
                    "{\"data\":{\"count\":3,\"note\":\"moved\"}}")
            )
        )
        assertTrue(
            error.message!!,
            error.message!!.contains(
                DeclarativeSource.ENDPOINT_SHAPE_MISS_MARKER
            )
        )
    }

    @Test
    fun `rows with a renamed link field are the record shape tier`() {
        val dropped = "{\"chapters\":[" +
            "{\"name\":\"Chapter 1\",\"href\":\"/book/walk-tale/1\"}," +
            "{\"name\":\"Chapter 2\",\"href\":\"/book/walk-tale/2\"}]}"
        val error = expectWalkError(
            config(),
            mapOf(endpoint(1) to (200 to dropped))
        )
        assertTrue(
            error.message!!,
            error.message!!.contains(
                DeclarativeSource.ENDPOINT_RECORD_SHAPE_MISS_MARKER
            )
        )
    }

    @Test
    fun `honest empty page completes with zero chapters`() {
        val source = source(
            config(),
            mapOf(endpoint(1) to (200 to "{\"chapters\":[]}"))
        )
        val chunk = source.chaptersFirst(
            SourceBook(id = bookUrl, title = "Walk Tale"), 10
        )
        assertTrue(chunk.complete)
        assertEquals(0, chunk.chapters.size)
    }

    @Test
    fun `empty page against only a spec-declared total has no chapters yet`() {
        val error = expectWalkError(
            config(totalCount = 12),
            mapOf(endpoint(1) to (200 to "{\"chapters\":[]}"))
        )
        assertTrue(
            error.message!!,
            error.message!!.contains(
                DeclarativeSource.ENDPOINT_NO_CHAPTERS_YET_MARKER
            )
        )
        assertFalse(
            error.message!!,
            error.message!!.contains(
                DeclarativeSource.ENDPOINT_SUSPICIOUS_EMPTY_MARKER
            )
        )
    }

    @Test
    fun `empty page against a book-page stated total is suspicious`() {
        val statedPage = """
            <html><head><title>Walk Tale</title></head><body>
            <h1>Walk Tale</h1><p>12 chapters</p>
            </body></html>
        """.trimIndent()
        val error = expectWalkError(
            config(totalCountPattern = "([\\d,]+)\\s+chapters"),
            mapOf(endpoint(1) to (200 to "{\"chapters\":[]}")),
            pageHtml = statedPage
        )
        assertTrue(
            error.message!!,
            error.message!!.contains(
                DeclarativeSource.ENDPOINT_SUSPICIOUS_EMPTY_MARKER
            )
        )
        assertFalse(
            error.message!!,
            error.message!!.contains(
                DeclarativeSource.ENDPOINT_NO_CHAPTERS_YET_MARKER
            )
        )
    }

    @Test
    fun `page two transport failure errors instead of truncate-committing`() {
        val error = expectWalkError(
            config(),
            mapOf(
                endpoint(1) to (200 to rowsJson("1", "2")),
                endpoint(2) to (500 to "boom"),
                endpoint(3) to (200 to "{\"chapters\":[]}"),
                endpoint(4) to (200 to "{\"chapters\":[]}")
            ),
            minCount = 3
        )
        assertTrue(error.message!!.contains("HTTP 500"))
    }

    @Test
    fun `valid empty page two terminates the walk complete`() {
        val source = source(
            config(),
            mapOf(
                endpoint(1) to (200 to rowsJson("1", "2")),
                endpoint(2) to (200 to "{\"chapters\":[]}"),
                endpoint(3) to (200 to "{\"chapters\":[]}"),
                endpoint(4) to (200 to "{\"chapters\":[]}")
            )
        )
        val chunk = source.chaptersFirst(
            SourceBook(id = bookUrl, title = "Walk Tale"), 10
        )
        assertTrue(chunk.complete)
        assertEquals(2, chunk.chapters.size)
    }

    // ── v1.5.6 (§1.4): the seven-fact diagnostic set ─────

    @Test
    fun `zero row page logs the seven diagnostic facts`() {
        DiagnosticLog.clear()
        val source = source(
            config(),
            mapOf(endpoint(1) to (200 to "{\"chapters\":[]}"))
        )
        val chunk = source.chaptersFirst(
            SourceBook(id = bookUrl, title = "Walk Tale"), 10
        )
        assertTrue(chunk.complete)
        assertEquals(0, chunk.chapters.size)
        val entries = DiagnosticLog.entries()
        // (i) + (ii): the request line and answering transport.
        val request = entries.firstOrNull {
            it.tag == "endpoint-walk" &&
                it.message.contains("endpoint request:")
        }
        assertTrue("request line missing; entries: $entries", request != null)
        assertTrue(request!!.message.contains("GET ${endpoint(1)}"))
        assertTrue(request.message.contains("body fields: -"))
        assertTrue(request.message.contains("headers:"))
        assertTrue(request.message.contains("Referer"))
        assertTrue(request.message.contains("transport: plain"))
        assertTrue(
            request.message.contains("in-context attempted: no")
        )
        // (iii) + (iv) + (vi): byte size, array provenance +
        // length, hint value + source on the zero-row line.
        val zero = entries.firstOrNull {
            it.tag == "endpoint-walk" &&
                it.message.contains("zero-row page (empty")
        }
        assertTrue("zero-row line missing; entries: $entries", zero != null)
        assertTrue(zero!!.message.contains("bytes="))
        assertTrue(zero.message.contains("array: declared path 'chapters'"))
        assertTrue(zero.message.contains("len=0"))
        assertTrue(zero.message.contains("hint: 0 (none)"))
        // No payload VALUE may ride in any entry (names only).
        assertTrue(
            entries.none { it.detail.contains("chapters") }
        )
        // (vii): the walk summary names the stop.
        val summary = entries.firstOrNull {
            it.tag == "endpoint-walk" &&
                it.message.contains("walk summary:")
        }
        assertTrue("walk summary missing; entries: $entries", summary != null)
        assertTrue(summary!!.message.contains("pages attempted=1"))
        assertTrue(summary.message.contains("stop=empty page"))
        assertTrue(summary.message.contains("rows=0"))
        assertTrue(summary.message.contains("stated total=none"))
        DiagnosticLog.clear()
    }

    @Test
    fun `records dropped page logs the drop split`() {
        DiagnosticLog.clear()
        val dropped = "{\"chapters\":[" +
            "{\"name\":\"Chapter 1\",\"href\":\"/book/walk-tale/1\"}," +
            "{\"name\":\"Chapter 2\",\"href\":\"/book/walk-tale/2\"}]}"
        val error = expectWalkError(
            config(),
            mapOf(endpoint(1) to (200 to dropped))
        )
        assertTrue(
            error.message!!,
            error.message!!.contains(
                DeclarativeSource.ENDPOINT_RECORD_SHAPE_MISS_MARKER
            )
        )
        val entries = DiagnosticLog.entries()
        // (iv) + (v): provenance + length + the drop split by
        // the mapper's rejection points (both records die at
        // the empty-link point: the spec reads `slug`).
        val zero = entries.firstOrNull {
            it.tag == "endpoint-walk" &&
                it.message.contains("zero-row page (records-dropped")
        }
        assertTrue("zero-row line missing; entries: $entries", zero != null)
        assertTrue(zero!!.message.contains("len=2"))
        assertTrue(zero.message.contains("drops:"))
        assertTrue(zero.message.contains("non-object=0"))
        assertTrue(zero.message.contains("empty-link=2"))
        assertTrue(zero.message.contains("cross-host=0"))
        val summary = entries.firstOrNull {
            it.message.contains("walk summary:")
        }
        assertTrue("walk summary missing; entries: $entries", summary != null)
        assertTrue(summary!!.message.contains("stop=page error"))
        DiagnosticLog.clear()
    }

    @Test
    fun `single shot road is strict too`() {
        val error = expectWalkError(
            config(singleShot = true),
            mapOf(
                "$base/api/books/walk-tale/chapters?loaded=0" to
                    (403 to "<html>refused</html>")
            )
        )
        assertTrue(error.message!!.contains("HTTP 403"))
    }
}
