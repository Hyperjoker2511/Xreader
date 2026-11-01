package app.xreader.data.extension

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.data.prefs.PerfTier
import app.xreader.domain.reader.ChapterDateText
import app.xreader.extensionapi.HostResponse
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceChapter
import app.xreader.extensionapi.SourceException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v1.3.2 hotfix — engine-level pins:
 *  - slug chapter numbers (item 79's root: "chapter-156-totally-
 *    awful" parsed to NO number, so order/identity broke);
 *  - canonical chapter identity + fragment merge (items 64,
 *    80/80b, 82: WoopRead, LibRead, NovelBuddy);
 *  - the chapter-shape gate against nav pollution (item 85);
 *  - date de-gluing on every road (items 71/78);
 *  - synopsis extraction (item 76);
 *  - FenrirRealm's bundled config against its captures (A7);
 *  - the session-need classifier + state words (A2/R1);
 *  - static-block escalation + challenge surfacing (A1);
 *  - paragraph structure through conversion (item 87).
 */
class V132HotfixEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun captureDir(name: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val base = dir ?: return null
            val candidate = File(base, "fixtures-capture/$name")
            if (candidate.isDirectory) return candidate
            dir = base.parentFile
        }
        return null
    }

    // ── Chapter numbers from slugs (item 79 root) ─────────────

    @Test
    fun `slug tails yield chapter numbers`() {
        assertEquals(
            156f,
            ChapterNumbers.parseAny(
                "Totally Awful",
                "https://x.example/book/chapter-156-totally-awful"
            )
        )
        assertEquals(
            101f,
            ChapterNumbers.parseAny(
                "Unexpected Clue",
                "https://x.example/book/chapter-101"
            )
        )
        assertEquals(
            42f,
            ChapterNumbers.parseFromUrl(
                "https://x.example/novel/abc/chapter/42"
            )
        )
        assertEquals(
            7f,
            ChapterNumbers.parseSlug("ch-7-the-beginning")
        )
        // A volume repeat is NOT a chapter number source.
        assertNull(
            ChapterNumbers.parseAny(
                "Volume 2 extras",
                "https://x.example/book/extras"
            )
        )
    }

    // ── Fragment merge (items 80/80b, 82) ─────────────────────

    private fun row(
        url: String,
        name: String,
        number: Float?,
        date: String? = null
    ) = SourceChapter(id = url, name = name, number = number, dateText = date)

    @Test
    fun `woopread two fragments merge into one ascending list`() {
        // The book page's newest block (34..28, www + trailing
        // slash forms, no dates) …
        val domBlock = (34 downTo 28).map { n ->
            row(
                "https://www.woopread.com/series/x/chapter-$n/",
                "Chapter $n", n.toFloat()
            )
        }
        // … then the payload's ascending run 1..34 (dated).
        val payload = (1..34).map { n ->
            row(
                "https://woopread.com/series/x/chapter-$n",
                "Chapter $n", n.toFloat(), date = "2026-10-0${(n % 9) + 1}"
            )
        }
        val settled = ChapterIdentity.settle(domBlock + payload) { it }
        assertEquals(34, settled.size)
        assertEquals(
            (1..34).map { it.toFloat() },
            settled.map { it.number }
        )
        // The dated (richest) record won the duplicates.
        assertTrue(settled.all { it.dateText != null })
        // Chapter 1 is chapter 1.
        assertEquals(
            "https://woopread.com/series/x/chapter-1",
            settled.first().id
        )
    }

    @Test
    fun `libread two fragments merge by identity`() {
        val domBlock = (297 downTo 292).map { n ->
            row(
                "https://libread.com/book/x/chapter-$n",
                "Chapter $n", n.toFloat()
            )
        }
        val ajax = (1..297).map { n ->
            row(
                "https://libread.com/book/x/chapter-$n",
                "Chapter $n", n.toFloat(), date = "2026-09-15"
            )
        }
        val settled = ChapterIdentity.settle(domBlock + ajax) { it }
        assertEquals(297, settled.size)
        assertEquals(1f, settled.first().number)
        assertEquals(297f, settled.last().number)
        assertEquals(
            settled.size,
            settled.map { ChapterIdentity.canonicalKey(it.id) }.toSet().size
        )
    }

    @Test
    fun `repeated numbers never trigger a sort`() {
        // Volume-numbered books legitimately repeat numbers
        // (Vol 1 ch 1..10, Vol 2 ch 1..10) — identity order
        // must leave the site order alone there.
        val rows = (1..10).map { n ->
            row("https://x.example/b/v1-$n", "Chapter $n", n.toFloat())
        } + (1..10).map { n ->
            row("https://x.example/b/v2-$n", "Chapter $n", n.toFloat())
        }
        assertNull(ChapterIdentity.orderByNumber(rows))
    }

    // ── NovelBuddy: the captured payload recounts (item 64) ───

    @Test
    fun `novelbuddy captured payload recounts to the api total`() {
        val dir = captureDir("novelbuddy.me")
        assumeTrue("captures present", dir != null)
        val payload = File(dir!!, "api/chapters-shadow-slave.json")
            .readText(Charsets.UTF_8)
        val root = kotlinx.serialization.json.Json
            .parseToJsonElement(payload).let {
                it as kotlinx.serialization.json.JsonObject
            }
        val chapters = (root["data"] as kotlinx.serialization.json.JsonObject)[
            "chapters"
        ] as kotlinx.serialization.json.JsonArray
        assertEquals(3229, chapters.size)
        val apiRows = chapters.map { element ->
            val record = element as kotlinx.serialization.json.JsonObject
            fun field(key: String) =
                (record[key] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content
            SourceChapter(
                id = "https://novelbuddy.me" + field("url"),
                name = field("name") ?: "",
                number = field("number")?.toFloatOrNull(),
                dateText = field("updated_at")
            )
        }
        // The book page's DOM slice (all inside the API set,
        // older + dateless forms of the same chapters).
        val bookHtml = File(dir, "book.html").readText(Charsets.UTF_8)
        val domRows = org.jsoup.Jsoup.parse(bookHtml, "https://novelbuddy.me")
            .select("a[href*=chapter-]")
            .map { anchor ->
                SourceChapter(
                    id = anchor.absUrl("href"),
                    name = anchor.text().trim()
                )
            }
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
        assertTrue("DOM slice present", domRows.isNotEmpty())
        val settled = ChapterIdentity.settle(domRows + apiRows) { it }
        assertEquals(
            "stored rows must equal the API's own count",
            3229, settled.size
        )
        assertEquals(1f, settled.first().number)
        assertEquals(3229f, settled.last().number)
        // Item 78b: dates ride the rows — chapter 1 carries its
        // 2023 date, the newest its 2026 one.
        assertEquals("2023-02-22T05:05:14.000Z", settled.first().dateText)
        assertEquals("2026-10-29T02:00:51.000Z", settled.last().dateText)
    }

    // ── The shape gate (item 85) ──────────────────────────────

    @Test
    fun `nav and genre anchors never survive the shape gate`() {
        val chapters = (1..30).map { n ->
            row(
                "https://novelping.com/book/x/$n",
                "Chapter $n", n.toFloat()
            )
        }
        val menu = listOf(
            "/novel-list", "/latest-release", "/hot-novel",
            "/genre/action", "/genre/adventure", "/genre/drama",
            "/genre/eastern", "/completed", "/most-popular"
        ).map { path ->
            row("https://novelping.com$path", path.trim('/'), null)
        }
        val kept = ChapterShape.filterConsistent(
            menu + chapters, "https://novelping.com/book/x"
        )
        assertEquals(30, kept.size)
        assertTrue(kept.all { it.number != null })
    }

    @Test
    fun `novelbin endpoint is the road and the polluted page adds nothing`() =
        runBlocking {
            val items = (1..30).joinToString(",") { n ->
                """{"chapter_id":$n,"chapter_name":"Chapter $n"}"""
            }
            val pollutedPage = buildString {
                append("<html><body><h1>Some Book</h1>")
                append("""<a href="/book/x/1">Chapter 1</a>""")
                listOf(
                    "Novel List" to "/novel-list",
                    "Latest Release" to "/latest-release",
                    "Hot Novel" to "/hot-novel",
                    "Action" to "/genre/action",
                    "Adventure" to "/genre/adventure",
                    "Drama" to "/genre/drama",
                    "Eastern" to "/genre/eastern"
                ).forEach { (label, href) ->
                    append("""<a href="$href">$label</a>""")
                }
                append("</body></html>")
            }
            val transport = FakeTransport { request ->
                when {
                    request.url.contains("/api-web/novels/") &&
                        request.url.contains("/chapters") ->
                        FakeTransport.ok(
                            """{"items":[$items],""" +
                                """"pagination":{"total":30}}"""
                        )
                    request.url.contains("novelping.com/book/") ->
                        FakeTransport.ok(pollutedPage)
                    else -> FakeTransport.ok("<html><body></body></html>")
                }
            }
            val context = testContext(
                transport, tmp.newFolder(),
                ExtensionPermissions(domains = listOf("novelping.com"))
            )
            val source = DeclarativeSource(
                BundledSingles.novelPing("novelbin", "NovelBin"), context
            )
            val chapters = source.chapters(
                SourceBook(
                    id = "https://novelping.com/book/x", title = "Some Book"
                )
            )
            assertEquals(30, chapters.size)
            assertEquals(
                (1..30).map { it.toFloat() },
                chapters.map { it.number }
            )
            assertTrue(
                chapters.none {
                    it.name.contains("Genre") ||
                        it.name.contains("Novel List") ||
                        it.name.contains("Action")
                }
            )
        }

    @Test
    fun `novelbin polluted page alone still yields zero menu rows`() =
        runBlocking {
            // The endpoint is DOWN: the DOM fallback runs over a
            // page whose anchors are 1 chapter + the whole menu —
            // the shape gate must still keep menus out.
            val pollutedPage = buildString {
                append("<html><body><h1>Some Book</h1>")
                (1..12).forEach { n ->
                    append(
                        """<a href="/book/x/$n">Chapter $n</a>"""
                    )
                }
                listOf(
                    "Novel List" to "/novel-list",
                    "Latest Release" to "/latest-release",
                    "Hot Novel" to "/hot-novel",
                    "Action" to "/genre/action",
                    "Adventure" to "/genre/adventure",
                    "Anime & comics" to "/genre/anime-comics",
                    "Drama" to "/genre/drama",
                    "Eastern" to "/genre/eastern",
                    "Fantasy" to "/genre/fantasy",
                    "History" to "/genre/history"
                ).forEach { (label, href) ->
                    append("""<a href="$href">$label</a>""")
                }
                append("</body></html>")
            }
            val transport = FakeTransport { request ->
                when {
                    request.url.contains("/chapters") ->
                        AppResult.Ok(FakeTransport.response(404, "no"))
                    else -> FakeTransport.ok(pollutedPage)
                }
            }
            val context = testContext(
                transport, tmp.newFolder(),
                ExtensionPermissions(domains = listOf("novelping.com"))
            )
            val source = DeclarativeSource(
                BundledSingles.novelPing("novelbin", "NovelBin"), context
            )
            val chapters = try {
                source.chapters(
                    SourceBook(
                        id = "https://novelping.com/book/x",
                        title = "Some Book"
                    )
                )
            } catch (expected: SourceException) {
                // A clean failure is also honest — but the shape
                // gate should let the 12 real chapters through.
                throw AssertionError(
                    "expected the real chapters, got: ${expected.message}"
                )
            }
            assertEquals(12, chapters.size)
            assertTrue(chapters.all { it.number != null })
        }

    @Test
    fun `number-first names order by their number, never by url id`() {
        // Novelight's house style: names are "760 chapter" and
        // the URL tail is the chapter's DATABASE id (…/chapter/
        // 346374 is chapter 760). Ordering must key on the
        // name's number — the first gate run caught the list
        // "sorted" by id, its fragments interleaved.
        assertEquals(760f, ChapterNumbers.parse("760 chapter"))
        // (A rating-led book title still parses to its rating
        // via the long-standing leading-number pattern — that
        // is baseline behaviour, and exactly why the shape
        // gate additionally requires the numbered rows to be
        // chapters OF the book before it judges anything.)
        val rows = listOf(
            row("https://n.example/book/chapter/900003", "3 chapter", null),
            row("https://n.example/book/chapter/900001", "1 chapter", null),
            row("https://n.example/book/chapter/900002", "2 chapter", null)
        )
        val settled = ChapterIdentity.settle(rows) { it }
        assertEquals(
            listOf("1 chapter", "2 chapter", "3 chapter"),
            settled.map { it.name }
        )
        // A bare URL tail under a "chapter" segment IS a
        // number by construction (…/chapter/214) and orders.
        val tailNumbered = listOf(
            row("https://n.example/book/chapter/3", "Third", null),
            row("https://n.example/book/chapter/1", "First", null),
            row("https://n.example/book/chapter/2", "Second", null)
        )
        assertEquals(
            listOf("First", "Second", "Third"),
            ChapterIdentity.orderByNumber(tailNumbered)?.map { it.name }
        )
        // A bare tail anywhere else is an id, not a number —
        // a list carrying only those does not order by them.
        val idOnly = listOf(
            row("https://n.example/read/900003", "Third", null),
            row("https://n.example/read/900001", "First", null),
            row("https://n.example/read/900002", "Second", null)
        )
        assertEquals(null, ChapterIdentity.orderByNumber(idOnly))
    }

    // ── Date de-gluing (items 71/78a) ─────────────────────────

    @Test
    fun `glued relative dates split into their own field`() {
        val now = 1_800_000_000_000L // a fixed "now" in 2027-01
        val (name1, date1) = ChapterDateText.splitGluedDate(
            "Chapter 156: Totally Awful 3 days ago", now
        )
        assertEquals("Chapter 156: Totally Awful", name1)
        assertNotNull(date1)
        val (name2, date2) = ChapterDateText.splitGluedDate(
            "Chapter 158: A Bad Art Dealer? about 24 hours ago", now
        )
        assertEquals("Chapter 158: A Bad Art Dealer?", name2)
        assertNotNull(date2)
        // A name that is ONLY a date is not a chapter name at all
        // and stays untouched (other gates judge it).
        val (name3, date3) = ChapterDateText.splitGluedDate(
            "3 days ago", now
        )
        assertEquals("3 days ago", name3)
        assertNull(date3)
        // No date glued on: unchanged, no date invented.
        val (name4, date4) = ChapterDateText.splitGluedDate(
            "Chapter 12: The Long Road", now
        )
        assertEquals("Chapter 12: The Long Road", name4)
        assertNull(date4)
    }

    @Test
    fun `dom rows de-glue names dates and duplicate prefixes`() = runBlocking {
        val bookHtml = """
            <html><body><h1>Some Book</h1>
            <div class="toc">
              <a href="/book/x/chapter-101">101Chapter 101 – Unexpected Clue5 years ago</a>
              <a href="/book/x/chapter-102">Chapter 102 – The Next Step 2 days ago</a>
              <a href="/book/x/chapter-103">Chapter 103 – Plain</a>
            </div></body></html>
        """.trimIndent()
        val transport = FakeTransport { request ->
            if (request.url.endsWith("/book/x")) {
                FakeTransport.ok(bookHtml)
            } else {
                FakeTransport.ok("<html><body></body></html>")
            }
        }
        val config = DeclarativeConfig(
            id = "glue", name = "Glue", baseUrl = FixtureSite.BASE,
            details = DetailsSpec(
                title = "h1",
                chapters = ChapterListSpec(
                    list = "div.toc a", name = "@text", link = "@href"
                )
            ),
            chapter = ChapterSpec(content = "article")
        )
        val source = DeclarativeSource(
            config, testContext(transport, tmp.newFolder())
        )
        val chapters = source.chapters(
            SourceBook(id = "${FixtureSite.BASE}/book/x", title = "Some Book")
        )
        assertEquals(3, chapters.size)
        val byNumber = chapters.associateBy { it.number }
        assertEquals(
            "Chapter 101 – Unexpected Clue", byNumber[101f]?.name
        )
        assertNotNull(byNumber[101f]?.dateText)
        assertEquals("Chapter 102 – The Next Step", byNumber[102f]?.name)
        assertNotNull(byNumber[102f]?.dateText)
        assertEquals("Chapter 103 – Plain", byNumber[103f]?.name)
        assertNull(byNumber[103f]?.dateText)
    }

    // ── Synopsis (item 76) ────────────────────────────────────

    @Test
    fun `synopsis prefers the full body and strips boilerplate`() = runBlocking {
        val full = "A disgraced inspector returns to the city that " +
            "ruined him, and the first case on his desk is his own " +
            "brother's. Every witness lies, every record has been " +
            "doctored, and the only honest person in the building " +
            "is the one who wants him gone."
        val bookHtml = """
            <html><head>
            <meta name="description" content="Read online &quot;The Complainant Murder Club&quot; - A disgraced inspector returns to the city that…"/>
            </head><body>
            <h1>The Complainant Murder Club</h1>
            <div class="summary">$full</div>
            </body></html>
        """.trimIndent()
        val transport = FakeTransport { FakeTransport.ok(bookHtml) }
        val config = DeclarativeConfig(
            id = "syn", name = "Syn", baseUrl = FixtureSite.BASE,
            details = DetailsSpec(
                title = "h1",
                description = "meta[name=description]@content",
                chapters = ChapterListSpec(
                    list = "a", name = "@text", link = "@href"
                )
            ),
            chapter = ChapterSpec(content = "article")
        )
        val source = DeclarativeSource(
            config, testContext(transport, tmp.newFolder())
        )
        val details = source.details(
            SourceBook(id = "${FixtureSite.BASE}/book/x", title = "?")
        )
        assertEquals(full, details.description)
        assertFalse(
            details.description.orEmpty().startsWith("Read online")
        )
    }

    @Test
    fun `synopsis helpers pin the boilerplate and overflow rules`() {
        assertEquals(
            "A real synopsis.",
            app.xreader.domain.reader.SynopsisText.stripBoilerplate(
                "Read online \"Some Book\" - A real synopsis."
            )
        )
        assertTrue(
            app.xreader.domain.reader.SynopsisText.isMetaSpec(
                "meta[name=description]@content"
            )
        )
        assertFalse(
            app.xreader.domain.reader.SynopsisText.isMetaSpec(
                "div.summary"
            )
        )
        // ~110 chars is where three collapsed lines overflow —
        // the expander candidate threshold.
        assertTrue(app.xreader.domain.reader.SynopsisText.mayOverflow(180))
        assertFalse(app.xreader.domain.reader.SynopsisText.mayOverflow(40))
    }

    // ── FenrirRealm against its captures (A7) ─────────────────

    @Test
    fun `fenrirrealm catalogue chapters and locked text`() = runBlocking {
        val dir = captureDir("fenrirealm.com")
        assumeTrue("captures present", dir != null)
        val seriesPage = File(dir!!, "api-series-p1.json")
            .readText(Charsets.UTF_8)
        val chaptersJson = File(dir, "api-chapters-small.json")
            .readText(Charsets.UTF_8)
        val lockedJson = File(dir, "api-chapter-1-locked.json")
            .readText(Charsets.UTF_8)
        val transport = FakeTransport { request ->
            when {
                request.url.contains("/api/new/v2/series?") ->
                    FakeTransport.ok(seriesPage)
                request.url.endsWith("/chapters") ->
                    FakeTransport.ok(chaptersJson)
                request.url.contains("/chapters/") ->
                    FakeTransport.ok(lockedJson)
                else -> FakeTransport.ok("{}")
            }
        }
        val context = testContext(
            transport, tmp.newFolder(),
            ExtensionPermissions(domains = listOf("fenrirealm.com"))
        )
        val config = BundledSingles.fenrirRealm("fenrirealm", "FenrirRealm")
        assertTrue("FenrirRealm is a sign-in-supported default", config.signInNeeded)
        val source = DeclarativeSource(config, context)
        val page = source.popular(1)
        assertTrue(page.books.isNotEmpty())
        val first = page.books.first()
        assertEquals("akazatl", first.author)
        assertTrue(
            "cover resolves onto the site's storage",
            first.coverUrl.orEmpty()
                .startsWith("https://fenrirealm.com/storage/")
        )
        val book = SourceBook(
            id = "https://fenrirealm.com/api/new/v2/series/some-slug",
            title = "Some Slug"
        )
        val chapters = source.chapters(book)
        assertEquals(10, chapters.size)
        assertEquals(
            (1..10).map { it.toFloat() },
            chapters.map { it.number }
        )
        // Anonymous text for a locked chapter is an honest
        // failure (the sign-in hand-off's cue), never prose.
        try {
            source.chapterContent(chapters.first())
            fail("locked chapter text must not come back as prose")
        } catch (expected: SourceException) {
            // expected — the detail drives SessionNeed's SIGN_IN.
        }
    }

    // ── Session need (A2/R1) ──────────────────────────────────

    @Test
    fun `session need classification and state words`() {
        val renderConfig = DeclarativeConfig(
            id = "r", name = "R", baseUrl = "https://r.example",
            needsRendering = true,
            details = DetailsSpec(
                chapters = ChapterListSpec(
                    list = "a", name = "@text", link = "@href"
                )
            ),
            chapter = ChapterSpec(content = "article")
        )
        val signInConfig = DeclarativeConfig(
            id = "s", name = "S", baseUrl = "https://s.example",
            signInNeeded = true,
            details = DetailsSpec(
                chapters = ChapterListSpec(
                    list = "a", name = "@text", link = "@href"
                )
            ),
            chapter = ChapterSpec(content = "article")
        )
        val challenge = AppError.ProviderDown(
            "svc",
            detail = SiteCheck.challengeDetail()
        )
        assertEquals(
            SessionNeed.Kind.SITE_CHECK,
            SessionNeed.classify(renderConfig, challenge)
        )
        assertEquals(
            SessionNeed.Kind.SIGN_IN,
            SessionNeed.classify(
                signInConfig,
                AppError.ExtensionFailed(
                    "S", "this chapter is paid or locked on the site"
                )
            )
        )
        // BOTI's shape (item 77): a user-added render lane whose
        // read died in the browser view — the hand-off applies
        // exactly as for a bundled source.
        assertEquals(
            SessionNeed.Kind.SITE_CHECK,
            SessionNeed.classify(
                renderConfig,
                AppError.ExtensionFailed(
                    "R", "the page never finished building in the browser view"
                )
            )
        )
        assertNull(SessionNeed.classify(renderConfig, AppError.NoNetwork))
        assertEquals(
            "Sign in needed",
            SessionNeed.stateWord(signInConfig, hasSession = false, null)
        )
        assertEquals(
            "Signed in",
            SessionNeed.stateWord(signInConfig, hasSession = true, null)
        )
        assertEquals(
            "Working",
            SessionNeed.stateWord(renderConfig, hasSession = false, null)
        )
        assertEquals(
            "Check needed",
            SessionNeed.stateWord(
                renderConfig, hasSession = false,
                lastErrorDetail = SiteCheck.challengeDetail()
            )
        )
    }

    // ── Escalation (A1) ───────────────────────────────────────

    private class StaticFetcher(
        private val answer: (String) -> AppResult<HostResponse>
    ) : PageFetcher {
        val calls = mutableListOf<String>()
        override suspend fun get(
            url: String,
            headers: Map<String, String>
        ): AppResult<HostResponse> {
            calls += url
            return answer(url)
        }

        override suspend fun post(
            url: String,
            body: String,
            contentType: String,
            headers: Map<String, String>
        ): AppResult<HostResponse> = answer(url)
    }

    private fun gate() = RenderGate(
        masterEnabled = { true },
        tier = { PerfTier.FULL },
        userDriven = { true },
        backgroundWork = { false }
    )

    private fun escalating(
        static: StaticFetcher,
        rendered: FakeRenderedFetcher
    ) = EscalatingPageFetcher(
        staticFetcher = static,
        renderedFetcher = rendered,
        sessionKey = "v132",
        purpose = RenderPurpose.SOURCE,
        needsRendering = { false },
        permissions = {
            ExtensionPermissions(domains = listOf("x.example"))
        },
        rateLimiter = RateLimiter(minIntervalMs = 0),
        gate = gate(),
        shellHosts = ShellHostMemory()
    )

    @Test
    fun `a static 403 escalates to the rendered road`() = runBlocking {
        val static = StaticFetcher {
            AppResult.Err(
                AppError.ProviderDown(
                    "svc", httpCode = 403,
                    detail = "the source failed with HTTP 403"
                )
            )
        }
        val rendered = FakeRenderedFetcher(
            fallback = { "<html><body><p>real catalogue</p></body></html>" }
        )
        val result = escalating(static, rendered)
            .get("https://x.example/popular")
        assertTrue(result is AppResult.Ok)
        assertEquals(1, rendered.calls.size)
    }

    @Test
    fun `a rendered challenge surfaces as a check-needed error`() =
        runBlocking {
            val challengeHtml = """
                <html><head><title>Just a moment...</title></head>
                <body><p>Checking your browser before accessing.</p></body></html>
            """.trimIndent()
            val static = StaticFetcher {
                AppResult.Ok(
                    HostResponse(403, challengeHtml, finalUrl = it)
                )
            }
            val rendered = FakeRenderedFetcher(fallback = { challengeHtml })
            val result = escalating(static, rendered)
                .get("https://x.example/popular")
            assertTrue(result is AppResult.Err)
            val detail = (result as AppResult.Err).error.detail ?: ""
            assertTrue(
                "the detail must carry the challenge marker",
                SiteCheck.isChallengeDetail(detail)
            )
        }

    @Test
    fun `prose mentioning a moment is not a challenge`() = runBlocking {
        val prose = buildString {
            append("<html><head><title>Chapter 9</title></head><body>")
            repeat(30) {
                append(
                    "<p>“Wait — just a moment,” she said, and the " +
                        "room went quiet while the rain kept up its " +
                        "soft drumming on the courthouse steps.</p>"
                )
            }
            append("</body></html>")
        }
        assertFalse(SiteCheck.isChallengePage(prose))
        val static = StaticFetcher {
            AppResult.Ok(HostResponse(200, prose, finalUrl = it))
        }
        val rendered = FakeRenderedFetcher(fallback = { null })
        val result = escalating(static, rendered)
            .get("https://x.example/chapter-9")
        assertTrue(result is AppResult.Ok)
        assertEquals(0, rendered.calls.size)
    }

    @Test
    fun `rendered json unwraps out of its pre shell`() = runBlocking {
        val json = """{"items":[{"number":1}],"total":1}"""
        val static = StaticFetcher {
            AppResult.Err(
                AppError.ProviderDown(
                    "svc", httpCode = 403,
                    detail = "the source failed with HTTP 403"
                )
            )
        }
        val rendered = FakeRenderedFetcher(
            fallback = {
                "<html><head></head><body><pre>$json</pre></body></html>"
            }
        )
        val result = escalating(static, rendered)
            .get("https://x.example/api/list")
        assertTrue(result is AppResult.Ok)
        assertEquals(
            json,
            (result as AppResult.Ok).value.body.trim()
        )
    }

    // ── Paragraph structure (item 87) ─────────────────────────

    @Test
    fun `br and div prose converts to separate paragraphs`() {
        val fragment = """
            <div class="text">
            <p>First paragraph, standing on the platform.</p>
            <p>Second paragraph, watching the train come in.</p>
            A loose line of prose outside any tag.<br><br>
            After a double break, a new paragraph begins here.
            </div>
        """.trimIndent()
        val content = HtmlToContent.fromFragment(
            fragment, "https://x.example/"
        )
        val paragraphs = content.blocks.filter {
            it is app.xreader.domain.content.ChapterBlock.Paragraph
        }
        assertTrue(
            "expected at least 3 paragraphs, got ${paragraphs.size}",
            paragraphs.size >= 3
        )
    }

    @Test
    fun `collapsed text fallback keeps paragraph structure`() {
        // Markup the structured converter walks past entirely:
        // the plain-text reading must still be paragraphs, not
        // one wall (the reader's spacing applies between blocks).
        val fragment = buildString {
            append("<div>")
            repeat(6) { i ->
                append(
                    "<span>Paragraph number $i holds a full " +
                        "sentence of prose that runs long enough " +
                        "to matter for the guard.</span><br><br>"
                )
            }
            append("</div>")
        }
        val content = HtmlToContent.fromFragment(
            fragment, "https://x.example/"
        )
        assertTrue(
            "expected several paragraph blocks, got ${content.blocks.size}",
            content.blocks.size >= 4
        )
    }

    @Test
    fun `line-per-paragraph plain text splits, wrapped text stays whole`() {
        val linePerParagraph = (1..6).joinToString("\n") { i ->
            "Paragraph $i of the chapter, a full sentence that " +
                "ends properly like this one does."
        }
        val split = HtmlToContent.plainTextFallback(linePerParagraph)
        assertEquals(6, split.blocks.size)
        val wrapped = (1..6).joinToString("\n") {
            "this line is a fragment of one long paragraph that"
        }
        val whole = HtmlToContent.plainTextFallback(wrapped)
        assertEquals(1, whole.blocks.size)
    }
}
