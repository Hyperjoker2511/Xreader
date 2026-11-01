package app.xreader.data.extension

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.data.db.Chapter
import app.xreader.data.net.FetchLanes
import app.xreader.data.net.HttpTransport
import app.xreader.data.prefs.PerfTier
import app.xreader.extensionapi.HostResponse
import app.xreader.extensionapi.SourceBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * v1.3.3 (V132-CHECK items 1–3, 5, 16–24): the fix build's
 * engine proofs —
 *  S1  the Cloudflare "Attention Required" wall classifies
 *      as a check, and a wall-empty catalogue on a gated
 *      source offers the hand-off (never "came back empty");
 *  S2  lazy (data-src) covers resolve on the listing road;
 *  S3  a chapter list must PROVE chapter 1 — the full walk
 *      and the progressive first chunk both follow the
 *      TOC's own first-page link when the landing window
 *      starts late;
 *  S5  JSON list pages fetch in bounded parallel batches,
 *      and a walk cut by the time budget reports itself
 *      truncated instead of silently partial;
 *  A5  a rendered fetch that never completes degrades
 *      inside its hard budget to the check-needed state;
 *  A6  fetch-lane isolation — saturating the general IO
 *      pool (parked renders/reads) cannot starve a plain
 *      source's catalogue, which rides its own lane;
 *  A3  the synopsis ladder (payload / API record / JSON-LD
 *      / meta carriers);
 *  A2  the Oldest/Newest display-order fallback chain.
 */
class V133FixEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val base = "https://novels.example"
    private val perms =
        ExtensionPermissions(domains = listOf(FixtureSite.HOST))

    private fun source(
        config: DeclarativeConfig,
        transport: FakeTransport
    ): DeclarativeSource = DeclarativeSource(
        config,
        testContext(transport, tmp.newFolder(), perms)
    )

    private fun config(
        popular: ListSpec = ListSpec(
            url = "$base/popular",
            results = "div.result",
            title = "a.t",
            link = "a.t@href",
            cover = "img.c@src"
        ),
        details: DetailsSpec = DetailsSpec(
            title = "h1",
            chapters = ChapterListSpec(
                list = "ul.chapters li a",
                name = "@text",
                link = "@href"
            )
        ),
        needsRendering: Boolean = false
    ) = DeclarativeConfig(
        id = "v133",
        name = "V133",
        baseUrl = base,
        popular = popular,
        details = details,
        chapter = ChapterSpec(content = "div.content"),
        needsRendering = needsRendering
    )

    // ── S1: the wall classifies as a check ───────────────────

    @Test
    fun `attention required wall is a challenge page`() {
        val wall = """
            <html><head><title>Attention Required! | Cloudflare</title></head>
            <body><h1>Sorry, you have been blocked</h1>
            <p>You are unable to access this site.</p></body></html>
        """.trimIndent()
        assertTrue(SiteCheck.isChallengePage(wall))
        assertTrue(SiteCheck.hasChallengeMarkersIn(wall))
    }

    @Test
    fun `empty catalogue on a gated source is a suspected wall`() {
        val gated = config(needsRendering = true)
        assertEquals(
            SessionNeed.Kind.SITE_CHECK,
            SessionNeed.forEmptyCatalogue(gated, hasSession = false)
        )
        // A session already exists: the empty shelf is honest.
        assertNull(SessionNeed.forEmptyCatalogue(gated, hasSession = true))
        // An ungated source's empty catalogue stays empty.
        assertNull(
            SessionNeed.forEmptyCatalogue(
                config(), hasSession = false
            )
        )
        val signIn = config().copy(signInNeeded = true)
        assertEquals(
            SessionNeed.Kind.SIGN_IN,
            SessionNeed.forEmptyCatalogue(signIn, hasSession = false)
        )
    }

    @Test
    fun `wall empty listing on a rendered source throws the check detail`() =
        runBlocking {
            // The static road is refused (403 wall); the
            // rendered road receives the SAME wall as a 200
            // challenge page with nothing extractable — the
            // listing must surface as Check needed, never as
            // an empty shelf.
            val wall = """
                <html><head><title>Attention Required! | Cloudflare</title></head>
                <body><div class="cf-wrapper"><h1>Sorry, you have been blocked</h1></div></body></html>
            """.trimIndent()
            val transport = FakeTransport {
                AppResult.Ok(FakeTransport.response(403, wall))
            }
            val runtime = RenderRuntime(
                MutableStateFlow(true), MutableStateFlow(PerfTier.FULL)
            )
            runtime.appInForeground = true
            val factory = HostContextFactory(
                ExtensionStore(tmp.newFolder()),
                testSessionStore(),
                transport,
                renderedFetcher = FakeRenderedFetcher(
                    fallback = { wall }
                ),
                renderRuntime = runtime
            )
            val context = factory.contextFor(
                extensionRowId = 7,
                packageId = "test.v133",
                label = "V133",
                permissions = { perms }
            )
            val gated = DeclarativeSource(
                config(needsRendering = true), context
            )
            val failure = runCatching { gated.popular(1) }
                .exceptionOrNull()
            assertNotNull("a walled catalogue must fail, not empty", failure)
            assertTrue(
                "expected the check detail, got: $failure",
                failure!!.message.orEmpty()
                    .contains(SiteCheck.CHALLENGE_MARKER)
            )
            Unit
        }

    // ── S2: lazy covers resolve ──────────────────────────────

    @Test
    fun `lazy data-src covers resolve on the listing road`() = runBlocking {
        val page = """
            <html><body><div class="results">
              <div class="result">
                <a class="t" href="/book/lazy-tale">Lazy Tale</a>
                <img class="c" data-src="/covers/lazy.jpg"/>
              </div>
              <div class="result">
                <a class="t" href="/book/srcset-tale">Srcset Tale</a>
                <img class="c" srcset="/covers/srcset.jpg 1x, /covers/srcset2.jpg 2x"/>
              </div>
            </div></body></html>
        """.trimIndent()
        val transport = FakeTransport { request ->
            if (request.url == "$base/popular") {
                FakeTransport.ok(page)
            } else {
                AppResult.Ok(FakeTransport.response(404, "no"))
            }
        }
        val books = source(config(), transport).popular(1).books
        assertEquals(2, books.size)
        assertEquals("$base/covers/lazy.jpg", books[0].coverUrl)
        assertEquals("$base/covers/srcset.jpg", books[1].coverUrl)
    }

    // ── S3: the list must prove chapter 1 ────────────────────

    private fun windowedBookPage(): String {
        val rows = (101..120).joinToString("\n") { n ->
            "<li><a href=\"/book/windowed/chapter-$n\">Chapter $n</a></li>"
        }
        return """
            <html><head><title>Windowed</title></head><body>
            <h1>Windowed</h1>
            <ul class="chapters">$rows</ul>
            <nav class="pager">
              <a rel="first" href="/book/windowed/chapters?page=1">First</a>
            </nav>
            </body></html>
        """.trimIndent()
    }

    private fun firstPageHtml(): String {
        val rows = (1..100).joinToString("\n") { n ->
            "<li><a href=\"/book/windowed/chapter-$n\">Chapter $n</a></li>"
        }
        return "<html><body><ul class=\"chapters\">$rows</ul></body></html>"
    }

    @Test
    fun `full walk proves chapter one via the toc first page`() = runBlocking {
        val transport = FakeTransport { request ->
            when (request.url) {
                "$base/book/windowed" ->
                    FakeTransport.ok(windowedBookPage())
                "$base/book/windowed/chapters?page=1" ->
                    FakeTransport.ok(firstPageHtml())
                else -> AppResult.Ok(FakeTransport.response(404, "no"))
            }
        }
        val (_, chapters) = source(config(), transport)
            .detailsAndChapters(
                SourceBook(id = "$base/book/windowed", title = "Windowed")
            )
        assertEquals(120, chapters.size)
        assertEquals(1f, chapters.first().number)
        assertTrue(
            "the TOC first page must actually be fetched",
            transport.requests.any {
                it.url == "$base/book/windowed/chapters?page=1"
            }
        )
    }

    @Test
    fun `progressive first chunk proves chapter one`() = runBlocking {
        // The JSON endpoint's page 1 is a WINDOW (101–150 of
        // 200): the progressive road must still open at
        // chapter 1, via the book page's own First link.
        fun records(range: IntRange): String = range.joinToString(",") { n ->
            "{\"name\":\"Chapter $n\",\"url\":\"/book/windowed/chapter-$n\"}"
        }
        val transport = FakeTransport { request ->
            when {
                request.url == "$base/book/windowed" -> FakeTransport.ok(
                    """
                    <html><head><title>Windowed</title></head><body>
                    <h1>Windowed</h1>
                    <script>const cfg = {"bookId": "42"};</script>
                    <ul class="chapters"></ul>
                    <a rel="first" href="/book/windowed/chapters?page=1">First</a>
                    </body></html>
                    """.trimIndent()
                )
                request.url ==
                    "$base/api/books/42/chapters?page=1" ->
                    FakeTransport.ok(
                        "{\"total\":200,\"chapters\":[${records(101..150)}]}"
                    )
                request.url ==
                    "$base/api/books/42/chapters?page=2" ->
                    FakeTransport.ok(
                        "{\"total\":200,\"chapters\":[${records(151..200)}]}"
                    )
                request.url == "$base/book/windowed/chapters?page=1" ->
                    FakeTransport.ok(firstPageHtml())
                else -> AppResult.Ok(FakeTransport.response(404, "no"))
            }
        }
        val jsonConfig = config(
            details = DetailsSpec(
                title = "h1",
                chapters = ChapterListSpec(
                    list = "ul.chapters li a",
                    name = "@text",
                    link = "@href",
                    pagination = ChapterListPagination(
                        urlTemplate = "$base/api/books/{bookId}/chapters?page={page}",
                        bookIdPattern = "\"bookId\": \"(\\d+)\"",
                        jsonArrayPath = "chapters",
                        jsonTotalPath = "total",
                        pageSize = 50
                    )
                )
            )
        )
        val chunk = source(jsonConfig, transport).chaptersFirst(
            SourceBook(id = "$base/book/windowed", title = "Windowed"),
            minCount = 10
        )
        assertTrue(chunk.chapters.isNotEmpty())
        assertEquals(1f, chunk.chapters.first().number)
        assertEquals(
            1f,
            chunk.chapters.mapNotNull { it.number }.minOrNull()
        )
    }

    // ── S5: parallel batches + honest truncation ─────────────

    @Test
    fun `json list pages fetch in parallel batches`() = runBlocking {
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        fun records(range: IntRange): String = range.joinToString(",") { n ->
            "{\"name\":\"Chapter $n\",\"url\":\"/book/batch/chapter-$n\"}"
        }
        val transport = FakeTransport { request ->
            when {
                request.url == "$base/book/batch" -> FakeTransport.ok(
                    """
                    <html><head><title>Batch</title></head><body>
                    <h1>Batch</h1>
                    <script>const cfg = {"bookId": "9"};</script>
                    <ul class="chapters"></ul>
                    </body></html>
                    """.trimIndent()
                )
                request.url.startsWith("$base/api/books/9/chapters") -> {
                    val page = request.url
                        .substringAfter("page=").toInt()
                    val now = inFlight.incrementAndGet()
                    maxInFlight.updateAndGet { maxOf(it, now) }
                    try {
                        // Longer than the rate limiter's
                        // 350 ms start spacing: if the batch
                        // were serial, no two handlers could
                        // ever be in flight together.
                        delay(600)
                    } finally {
                        inFlight.decrementAndGet()
                    }
                    val start = (page - 1) * 20 + 1
                    val end = minOf(start + 19, 90)
                    FakeTransport.ok(
                        "{\"total\":90,\"chapters\":[${records(start..end)}]}"
                    )
                }
                else -> AppResult.Ok(FakeTransport.response(404, "no"))
            }
        }
        val batchConfig = config(
            details = DetailsSpec(
                title = "h1",
                chapters = ChapterListSpec(
                    list = "ul.chapters li a",
                    name = "@text",
                    link = "@href",
                    pagination = ChapterListPagination(
                        urlTemplate = "$base/api/books/{bookId}/chapters?page={page}",
                        bookIdPattern = "\"bookId\": \"(\\d+)\"",
                        jsonArrayPath = "chapters",
                        jsonTotalPath = "total",
                        pageSize = 20
                    )
                )
            )
        )
        val src = source(batchConfig, transport)
        val book = SourceBook(id = "$base/book/batch", title = "Batch")
        val first = src.chaptersFirst(book, minCount = 10)
        assertEquals(20, first.chapters.size)
        val more = src.chaptersMore(
            book, first.chapters, minTotal = 70, walk = first.walk
        )
        assertTrue(
            "the chunk grows past the ask (got ${more.chapters.size})",
            more.chapters.size >= 70
        )
        assertEquals(
            (1..more.chapters.size).map { it.toFloat() },
            more.chapters.map { it.number }
        )
        assertFalse(more.complete)
        assertTrue(
            "pages must overlap in flight (max was ${maxInFlight.get()})",
            maxInFlight.get() >= 2
        )
    }

    @Test
    fun `walk cut by the time budget reports itself truncated`() = runBlocking {
        fun row(n: Int) = "<li><a href=\"/c/$n\">Chapter $n</a></li>"
        val bookPage = """
            <html><head><title>Mini</title></head><body>
            <h1>Mini</h1>
            <script>const BOOK_ID = "7"</script>
            <ul class="chapters">${(1..3).joinToString("") { row(it) }}</ul>
            </body></html>
        """.trimIndent()
        val transport = FakeTransport { request ->
            when {
                request.url == "$base/book" -> FakeTransport.ok(bookPage)
                request.url.startsWith("$base/list?") -> {
                    delay(60)
                    val page = request.url
                        .substringAfter("page=").toInt()
                    val start = (page - 1) * 3 + 1
                    FakeTransport.ok(
                        "<ul>${(start..start + 2).joinToString("") { row(it) }}</ul>"
                    )
                }
                else -> AppResult.Ok(FakeTransport.response(404, "no"))
            }
        }
        val walkConfig = config(
            details = DetailsSpec(
                title = "h1",
                chapters = ChapterListSpec(
                    list = "ul.chapters > li",
                    name = "a",
                    link = "a@href",
                    pagination = ChapterListPagination(
                        urlTemplate = "$base/list?book={bookId}&page={page}",
                        bookIdPattern = "BOOK_ID = \"(\\d+)\"",
                        jsonHtmlField = null
                    )
                )
            )
        )
        val src = source(walkConfig, transport)
        src.walkBudgetMs = 100
        val (_, chapters) = src.detailsAndChapters(
            SourceBook(id = "$base/book", title = "Mini")
        )
        assertTrue(
            "a truncated walk returns a partial list (got ${chapters.size})",
            chapters.size < 30
        )
        assertTrue(
            "and says so — completeness must not be claimed",
            src.lastWalkTruncated
        )
    }

    // ── A3: the synopsis ladder ──────────────────────────────

    private fun synopsisConfig() = config(
        details = DetailsSpec(
            title = "h1",
            // A selector that matches NOTHING on the fixture
            // pages: every rung below it must still be tried.
            description = "div.absent-synopsis",
            chapters = ChapterListSpec(
                list = "ul.chapters li a",
                name = "@text",
                link = "@href"
            )
        )
    )

    private fun detailsFrom(page: String): SourceBook {
        val transport = FakeTransport { request ->
            if (request.url == "$base/book/x") {
                FakeTransport.ok(page)
            } else {
                AppResult.Ok(FakeTransport.response(404, "no"))
            }
        }
        return source(synopsisConfig(), transport).details(
            SourceBook(id = "$base/book/x", title = "X")
        )
    }

    @Test
    fun `synopsis living only in the rsc payload is found`() {
        val text = "A payload-only synopsis about a cartographer " +
            "who maps drowned cities and the tides that took them."
        val page = """
            <html><head><title>X</title></head><body>
            <h1>X</h1><ul class="chapters"></ul>
            <script>self.__next_f.push([1,"{\"description\":\"$text\"}"])</script>
            </body></html>
        """.trimIndent()
        assertEquals(text, detailsFrom(page).description)
    }

    @Test
    fun `synopsis living only in an api record page is found`() {
        val text = "An api-only synopsis about a locksmith " +
            "who opens doors that were never built, for people " +
            "who were never born."
        val page = "{\"title\":\"X\",\"description\":\"$text\"}"
        assertEquals(text, detailsFrom(page).description)
    }

    @Test
    fun `synopsis living only in json-ld is found`() {
        val text = "A structured-data synopsis about twin " +
            "archivists cataloguing a library that rewrites " +
            "its own shelves every midnight."
        val page = """
            <html><head><title>X</title>
            <script type="application/ld+json">
            {"@type":"Book","name":"X","description":"$text"}
            </script></head><body>
            <h1>X</h1><ul class="chapters"></ul>
            </body></html>
        """.trimIndent()
        assertEquals(text, detailsFrom(page).description)
    }

    @Test
    fun `synopsis living only in the meta description is found`() {
        val text = "A meta-only synopsis about a ferryman " +
            "who charges memories instead of coins and keeps " +
            "meticulous, sorrowful accounts."
        val page = """
            <html><head><title>X</title>
            <meta name="description" content="$text"/>
            </head><body>
            <h1>X</h1><ul class="chapters"></ul>
            </body></html>
        """.trimIndent()
        assertEquals(text, detailsFrom(page).description)
    }

    // ── A5: the rendered road's hard budget ──────────────────

    private class HangingRenderedFetcher : RenderedPageFetcher {
        override suspend fun fetchRendered(
            url: String,
            sessionKey: String,
            permissions: ExtensionPermissions,
            waitForSelector: String?
        ): AppResult<HostResponse> {
            delay(Long.MAX_VALUE)
            error("unreachable")
        }
    }

    private class StaticFetcher(
        private val answer: (String) -> AppResult<HostResponse>
    ) : PageFetcher {
        override suspend fun get(
            url: String,
            headers: Map<String, String>
        ): AppResult<HostResponse> = answer(url)

        override suspend fun post(
            url: String,
            body: String,
            contentType: String,
            headers: Map<String, String>
        ): AppResult<HostResponse> = answer(url)
    }

    @Test
    fun `a rendered fetch that never completes degrades inside its budget`() =
        runBlocking {
            val fetcher = EscalatingPageFetcher(
                staticFetcher = StaticFetcher {
                    AppResult.Err(
                        AppError.ProviderDown(
                            "svc", httpCode = 403,
                            detail = "the source failed with HTTP 403"
                        )
                    )
                },
                renderedFetcher = HangingRenderedFetcher(),
                sessionKey = "v133-budget",
                purpose = RenderPurpose.SOURCE,
                needsRendering = { true },
                permissions = { perms },
                rateLimiter = RateLimiter(minIntervalMs = 0),
                gate = RenderGate(
                    masterEnabled = { true },
                    tier = { PerfTier.FULL },
                    userDriven = { true },
                    backgroundWork = { false }
                ),
                budgetMs = { 300L }
            )
            val started = System.nanoTime()
            val result = withTimeout(10_000) {
                fetcher.get("$base/popular")
            }
            val elapsedMs =
                (System.nanoTime() - started) / 1_000_000
            assertTrue("expected an error, got $result", result is AppResult.Err)
            val detail = (result as AppResult.Err).error.detail.orEmpty()
            assertTrue(
                "expected the in-time wording, got: $detail",
                detail.contains("couldn't load that page in time")
            )
            assertTrue(
                "the budget must bind (took ${elapsedMs}ms)",
                elapsedMs < 5_000
            )
            // And on a needsRendering source that degraded
            // state IS the check-needed hand-off.
            assertEquals(
                SessionNeed.Kind.SITE_CHECK,
                SessionNeed.classify(
                    config(needsRendering = true), result.error
                )
            )
        }

    // ── A6: fetch-lane isolation ─────────────────────────────

    /** A transport whose calls run on the static lane —
     * exactly how the production extension transport is
     * wired (OkHttpTransport over FetchLanes.static). */
    private class LaneTransport(
        private val delegate: HttpTransport
    ) : HttpTransport {
        override suspend fun execute(
            request: HttpTransport.Request
        ): AppResult<HttpTransport.Response> =
            withContext(FetchLanes.static) { delegate.execute(request) }
    }

    @Test
    fun `a plain catalogue completes while the general pool is saturated`() =
        runBlocking {
            val gate = CountDownLatch(1)
            val scope = CoroutineScope(Dispatchers.IO)
            // Park the shared IO pool solid: far more blocking
            // workers than the pool has threads — the shape of
            // stalled reads and parked renders piling up.
            val blockers = (1..160).map {
                scope.async {
                    gate.await(15, TimeUnit.SECONDS)
                    Unit
                }
            }
            delay(400) // let the pool fill
            try {
                val page = """
                    <html><body><div class="results">
                      <div class="result">
                        <a class="t" href="/book/sword-saga">Sword Saga</a>
                      </div>
                    </div></body></html>
                """.trimIndent()
                val transport = LaneTransport(
                    FakeTransport { request ->
                        if (request.url == "$base/popular") {
                            FakeTransport.ok(page)
                        } else {
                            AppResult.Ok(FakeTransport.response(404, "no"))
                        }
                    }
                )
                val factory = HostContextFactory(
                    ExtensionStore(tmp.newFolder()),
                    testSessionStore(),
                    transport
                )
                val context = factory.contextFor(
                    extensionRowId = 3,
                    packageId = "test.v133",
                    label = "V133",
                    permissions = { perms }
                )
                val plain = DeclarativeSource(config(), context)
                val books = withTimeout(8_000) { plain.popular(1).books }
                assertEquals(1, books.size)
                assertEquals("Sword Saga", books[0].title)
            } finally {
                gate.countDown()
                blockers.forEach { it.await() }
            }
            Unit
        }

    // ── A2: the display-order fallback chain ─────────────────

    private fun rowOf(name: String, url: String): Chapter =
        Chapter(
            bookId = 1,
            index = 0,
            title = name,
            remoteId = url,
            updatedAt = 0
        )

    @Test
    fun `woopread shaped rows reverse end to end`() {
        // Stored NEWEST FIRST, bare story titles, chapter-N
        // slugs, and a duplicate number in the middle — the
        // shape whose toggle was a silent no-op on device.
        val stored = listOf(
            rowOf("The Last Stand", "$base/series/x/chapter-5-the-last-stand"),
            rowOf("Turning Point", "$base/series/x/chapter-4-turning-point"),
            rowOf("Turning Point (raw)", "$base/series/x/chapter-4-turning-point-raw"),
            rowOf("The Middle", "$base/series/x/chapter-3-the-middle"),
            rowOf("First Steps", "$base/series/x/chapter-2-first-steps"),
            rowOf("The Sinuiju Operation", "$base/series/x/chapter-1-the-sinuiju-operation")
        )
        val oldest = app.xreader.domain.reader.ChapterDisplayOrder
            .ordered(stored, newestFirst = false)
        val newest = app.xreader.domain.reader.ChapterDisplayOrder
            .ordered(stored, newestFirst = true)
        assertEquals(
            listOf(1f, 2f, 3f, 4f, 4f, 5f),
            oldest.map {
                app.xreader.domain.reader.ChapterDisplayOrder
                    .numberOf(it)
            }
        )
        assertEquals(oldest.reversed(), newest)
        assertTrue("the two views must differ", oldest != newest)
    }

    @Test
    fun `unnumbered rows mirror the source sequence`() {
        // Nothing parses anywhere — the fallback is the
        // known source sequence, mirrored between the two
        // views, never a silent no-op.
        val stored = listOf(
            rowOf("Epilogue", "$base/read/zk9"),
            rowOf("Interlude", "$base/read/m41"),
            rowOf("Prologue", "$base/read/a07")
        )
        val oldest = app.xreader.domain.reader.ChapterDisplayOrder
            .ordered(stored, newestFirst = false)
        val newest = app.xreader.domain.reader.ChapterDisplayOrder
            .ordered(stored, newestFirst = true)
        // Stored lists settle into reading order at ingest,
        // so Oldest reads the sequence as stored and Newest
        // mirrors it — the two chips never show the same list.
        assertEquals(stored, oldest)
        assertEquals(stored.reversed(), newest)
        assertTrue(oldest != newest)
    }
}
