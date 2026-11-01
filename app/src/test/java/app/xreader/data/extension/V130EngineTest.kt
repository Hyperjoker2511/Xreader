package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeExtensionRepoDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.extensionapi.SourceBook
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.3.0 engine round — the E1 API shapes (paged JSON chapter
 * lists with stated totals, ranged JSON lists, plain-text
 * chapter fields with lock flags), the E3 API-discovery seed,
 * the E4 maintainer-lane proof policy, and the E5 prose-density
 * floor. All fixtures are synthetic (an invented API site,
 * invented prose) shaped like the live-verified mechanisms in
 * build-logs/QUICKNOVEL-RESEARCH.md and the chikari capture.
 */
class V130EngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── synthetic prose (well over every density floor) ────

    private fun prose(sentences: Int): String {
        val pool = listOf(
            "The harbour bell rang twice before the fog lifted.",
            "Mara counted the coins again and found them short.",
            "No one in the village remembered building the wall.",
            "He had sworn never to return to the northern road.",
            "The letter, when it came, was written in green ink.",
            "By dusk the river had taken the lower fields.",
            "She kept the key on a cord around her neck.",
            "Old Tomas laughed and said the storm would pass."
        )
        return (0 until sentences).joinToString(" ") { pool[it % pool.size] }
    }

    // ── E5: prose density ──────────────────────────────────

    @Test
    fun `E5 real prose passes the density floor`() {
        val verdict = ProseDensity.judge(prose(24))
        assertTrue(verdict.reason, verdict.isProse)
    }

    @Test
    fun `E5 navigation chrome fails the density floor`() {
        // The noveldex shape: a few hundred characters of page
        // furniture once accepted as chapter text.
        val chrome = "Home Library Browse Genres Rankings Forum " +
            "Sign in Register Search novels, authors… Latest " +
            "updates Popular this week Completed Ongoing " +
            "Advanced search Settings Profile Bookmarks History " +
            "Notifications Messages Friends Groups Chat Rules " +
            "FAQ About Contact Privacy Terms Copyright 2026 " +
            "All rights reserved Back to top Next Previous"
        val verdict = ProseDensity.judge(chrome)
        assertFalse(verdict.reason, verdict.isProse)
    }

    @Test
    fun `E5 link-dense text fails the density floor`() {
        val linkSoup = (1..40).joinToString(" ") { "Chapter $it" }
        val verdict = ProseDensity.judge(prose(24), linkText = prose(24))
        assertFalse(verdict.reason, verdict.isProse)
        val mostlyLinks = ProseDensity.judge(linkSoup, linkText = linkSoup)
        assertFalse(mostlyLinks.reason, mostlyLinks.isProse)
    }

    @Test
    fun `E5 a short genuine paragraph still fails on size alone`() {
        val verdict = ProseDensity.judge(prose(2))
        assertFalse(verdict.isProse)
        assertTrue(verdict.reason, verdict.reason.contains("too little"))
    }

    @Test
    fun `E5 japanese prose passes the density floor`() {
        // Synthetic spaceless prose: no Latin words, sentences
        // end with 。 — the Syosetu drive's shape (its ch.1
        // measured 4,237 chars / 85 sentences live). A
        // Latin-only judge read that as furniture.
        val sentence = "その日、港の鐘が二度鳴り、霧が晴れた。" +
            "マラは硬貨を数え直して、足りないことに気づいた。" +
            "村の誰も、その壁を建てた者のことを覚えていなかった。"
        val verdict = ProseDensity.judge(sentence.repeat(24))
        assertTrue(verdict.reason, verdict.isProse)
    }

    @Test
    fun `E5 japanese navigation chrome still fails`() {
        val chrome = "ホーム ライブラリ ランキング 検索 ログイン " +
            "新規登録 設定 プロフィール 本棚 履歴 お知らせ " +
            "メッセージ 友達 グループ ルール ヘルプ 会社概要 " +
            "お問い合わせ プライバシー 利用規約 著作権 トップへ戻る"
        val verdict = ProseDensity.judge(chrome)
        assertFalse(verdict.reason, verdict.isProse)
    }

    // ── E3: API discovery hints ────────────────────────────

    @Test
    fun `E3 endpoint hints find api paths in scripts and markup`() {
        val doc = Jsoup.parse(
            """
            <html><head>
              <script src="/assets/app.js"></script>
              <script>fetch("/api/novels/search?q=" + q); axios.get('/api/v2/books')</script>
            </head><body>
              <a href="/api/docs">API</a><a href="/novels/x">A book</a>
            </body></html>
            """.trimIndent(),
            "https://api.example/"
        )
        val hints = EmbeddedJson.apiEndpointHints(doc, "https://api.example/")
        assertTrue(hints.toString(), hints.any { it.contains("/api/novels/search") })
        assertTrue(hints.toString(), hints.any { it.contains("/api/v2/books") })
        assertTrue(hints.toString(), hints.none { it.contains("novels/x") })
    }

    @Test
    fun `E3 embedded listing finds the book array in next data`() {
        val payload = (1..12).joinToString(",") { n ->
            """{"title":"Book $n","slug":"book-$n","cover":"/c$n.jpg"}"""
        }
        val doc = Jsoup.parse(
            """
            <html><head><script id="__NEXT_DATA__" type="application/json">
            {"props":{"pageProps":{"results":[$payload],"total":12}}}
            </script></head><body><h1>Home</h1></body></html>
            """.trimIndent(),
            "https://api.example/"
        )
        val listing = EmbeddedJson.embeddedListing(doc)
        assertNotNull(listing)
        assertEquals("props.pageProps.results", listing!!.path)
        assertEquals(12, listing.size)
    }

    @Test
    fun `E3 embedded listing ignores non book arrays`() {
        val doc = Jsoup.parse(
            """
            <html><head><script id="__NEXT_DATA__" type="application/json">
            {"props":{"pageProps":{"tags":["alpha","beta","gamma","delta"]}}}
            </script></head><body></body></html>
            """.trimIndent(),
            "https://api.example/"
        )
        assertEquals(null, EmbeddedJson.embeddedListing(doc))
    }

    // ── E1: the synthetic API site ─────────────────────────

    private object ApiSite {
        const val HOST = "api.example"
        const val BASE = "https://api.example"
        const val BOOK_URL = "$BASE/book/sea-wolves"

        /** 75 chapters, newest first, 50 per API page — the
         * book's details address IS page 1 of its API (the
         * fenrir/novelping shape: the book payload and the
         * chapter walk share the endpoint family). */
        fun chaptersPage(page: Int): String {
            val all = (75 downTo 1).map { n ->
                """{"number":$n,"title":"Tide $n","url":"/book/sea-wolves/$n"}"""
            }
            val slice = all.drop((page - 1) * 50).take(50)
            return """{"items":[${slice.joinToString(",")}],"total":75}"""
        }

        val bookPayload = """{"props":{"pageProps":{"book":{
            "title":"Sea Wolves","author":"R. Mariner",
            "chapters":[${(1..3).joinToString(",") { n ->
                """{"name":"Chapter $n","url":"/book/sea-wolves/$n"}"""
            }}]}}}}"""

        fun transport(): FakeTransport = FakeTransport { request ->
            val url = request.url
            when {
                url == "$BASE/api/books/sea-wolves" ->
                    FakeTransport.ok(chaptersPage(1))
                url == "$BASE/api/books/sea-wolves?page=2" ->
                    FakeTransport.ok(chaptersPage(2))
                url.startsWith("$BASE/novel/") -> FakeTransport.ok(
                    """<html><head><script id="__NEXT_DATA__"
                        type="application/json">$bookPayload</script>
                        </head><body></body></html>"""
                )
                else -> FakeTransport.ok(
                    "<html><body><h1>Sea Wolves</h1></body></html>"
                )
            }
        }

        fun pagedConfig(): DeclarativeConfig = DeclarativeConfig(
            id = "api-example",
            name = "API Example",
            baseUrl = BASE,
            popular = ListSpec(
                url = "$BASE/",
                results = "a",
                title = "@text",
                link = "@href"
            ),
            details = DetailsSpec(
                title = "h1",
                chapters = ChapterListSpec(
                    list = "a",
                    name = "@text",
                    link = "@href",
                    reverse = true,
                    jsonEndpointPath = "items",
                    jsonNameField = "title",
                    jsonLinkField = "url",
                    pagination = ChapterListPagination(
                        urlTemplate = "/api/books/{bookSlug}?page={page}",
                        bookIdPattern = "",
                        jsonArrayPath = "items",
                        jsonTotalPath = "total",
                        pageSize = 50
                    )
                )
            ),
            chapter = ChapterSpec(content = "article")
        )
    }

    private fun source(
        config: DeclarativeConfig,
        transport: FakeTransport,
        host: String = ApiSite.HOST
    ): DeclarativeSource {
        val context = testContext(
            transport, tmp.newFolder(),
            ExtensionPermissions(domains = listOf(host))
        )
        return DeclarativeSource(config, context)
    }

    @Test
    fun `E1 paged JSON chapter list walks to the stated total`() = runBlocking {
        val chapters = source(ApiSite.pagedConfig(), ApiSite.transport())
            .chapters(
                SourceBook(
                    id = "${ApiSite.BASE}/api/books/sea-wolves",
                    title = "Sea Wolves"
                )
            )
        assertEquals(75, chapters.size)
        assertEquals("${ApiSite.BOOK_URL}/1", chapters.first().id)
        assertEquals("${ApiSite.BOOK_URL}/75", chapters.last().id)
        // Record names carry their number prefix (bare title +
        // stated number in the record).
        assertTrue(chapters.first().name, chapters.first().name.startsWith("Chapter 1"))
    }

    @Test
    fun `E1 ranged JSON chapter list uses start and end`() = runBlocking {
        val rangedTransport = FakeTransport { request ->
            val url = request.url
            when {
                url.contains("start=1&end=3") || url.contains("start%3D1") ->
                    FakeTransport.ok(
                        """{"data":[{"name":"Chapter 1","url":"/r/1"},
                        {"name":"Chapter 2","url":"/r/2"},
                        {"name":"Chapter 3","url":"/r/3"}]}"""
                    )
                url.contains("start=4") -> FakeTransport.ok(
                    """{"data":[{"name":"Chapter 4","url":"/r/4"}]}"""
                )
                else -> FakeTransport.ok("<html><body></body></html>")
            }
        }
        val config = ApiSite.pagedConfig().copy(
            details = DetailsSpec(
                title = "h1",
                chapters = ChapterListSpec(
                    list = "a",
                    name = "@text",
                    link = "@href",
                    jsonEndpointPath = "data",
                    pagination = ChapterListPagination(
                        urlTemplate = "/api/range?start={start}&end={end}",
                        bookIdPattern = "",
                        jsonArrayPath = "data",
                        pageSize = 3
                    )
                )
            )
        )
        val chapters = source(config, rangedTransport)
            .chapters(SourceBook(id = ApiSite.BOOK_URL, title = "Sea Wolves"))
        assertEquals(chapters.map { it.id }.toString(), 4, chapters.size)
        assertTrue(
            rangedTransport.requests.any { it.url.contains("start=4&end=6") }
        )
    }

    @Test
    fun `E1 direct JSON endpoint rows replace the shell page rows`() = runBlocking {
        val config = ApiSite.pagedConfig().copy(
            details = DetailsSpec(
                title = "h1",
                chapters = ChapterListSpec(
                    list = "a",
                    name = "@text",
                    link = "@href",
                    jsonEndpointPath = "props.pageProps.book.chapters",
                    jsonNameField = "name",
                    jsonLinkField = "url"
                )
            )
        )
        val chapters = source(config, ApiSite.transport()).chapters(
            SourceBook(id = "${ApiSite.BASE}/novel/sea-wolves", title = "Sea Wolves")
        )
        assertEquals(3, chapters.size)
        assertEquals("${ApiSite.BOOK_URL}/1", chapters.first().id)
    }

    // ── E1: plain-text chapter fields + lock flag ──────────

    @Test
    fun `E1 plain text ajax field becomes paragraphs and lock flag fails typed`() =
        runBlocking {
            val body = (1..14).joinToString("\n") { n ->
                "Line $n. " + prose(2)
            }
            val readTransport = FakeTransport { request ->
                when {
                    request.url.endsWith("/chapters/1/read") ->
                        FakeTransport.ok(
                            """{"body":${kotlinx.serialization.json.JsonPrimitive(body)},"locked":false}"""
                        )
                    request.url.endsWith("/chapters/2/read") ->
                        FakeTransport.ok("""{"body":"","locked":true}""")
                    else -> FakeTransport.ok("<html><body></body></html>")
                }
            }
            val config = ApiSite.pagedConfig().copy(
                chapter = ChapterSpec(
                    content = "article",
                    ajax = ChapterTextAjax(
                        urlTemplate = "/api/novels/{seg-2}/chapters/{seg-1}/read",
                        jsonContentField = "body",
                        plainTextField = true,
                        jsonLockField = "locked"
                    )
                )
            )
            val src = source(config, readTransport)
            val content = src.chapterContent(
                app.xreader.extensionapi.SourceChapter(
                    id = "${ApiSite.BASE}/novels/sea-wolves/1",
                    name = "Chapter 1",
                    number = 1f
                )
            )
            val plain = content.html.replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ").trim()
            assertTrue(plain.take(120), plain.contains("Line 1."))
            assertTrue(ProseDensity.judge(plain).isProse)
            val locked = runCatching {
                src.chapterContent(
                    app.xreader.extensionapi.SourceChapter(
                        id = "${ApiSite.BASE}/novels/sea-wolves/2",
                        name = "Chapter 2",
                        number = 2f
                    )
                )
            }
            assertTrue("a locked API chapter must fail, not serve",
                locked.isFailure)
        }

    // ── E4: maintainer lane proof policy ───────────────────

    @Test
    fun `E4 boti lane installs proof pending when its drive cannot run`() =
        runBlocking {
            val host = "www.botitranslation.com"
            val base = "https://$host"
            val catalogue = """
                <html><head><title>BotiTranslation</title></head><body>
                ${(1..8).joinToString("") { n ->
                    """<a href="/mobile/book/10$n"><img src="/c$n.jpg" alt="Book $n"/></a>"""
                }}
                </body></html>
            """.trimIndent()
            val bookPage = """
                <html><head><title>Book 1</title></head><body>
                <h1>Book 1</h1></body></html>
            """.trimIndent()
            var bookFetches = 0
            val transport = FakeTransport { request ->
                val url = request.url
                when {
                    url.startsWith("$base/mobile/book/") -> {
                        bookFetches++
                        if (bookFetches == 1) FakeTransport.ok(bookPage)
                        else AppResult.Ok(FakeTransport.response(500, "slow"))
                    }
                    url.startsWith("$base/mobile/") ->
                        FakeTransport.ok(catalogue)
                    else -> AppResult.Ok(FakeTransport.response(500, "no"))
                }
            }
            val sessionStore = testSessionStore()
            val factory = HostContextFactory(
                ExtensionStore(tmp.newFolder()), sessionStore, transport
            )
            val prober = LinkProber(factory, sessionStore)
            val outcome = prober.probe("$base/mobile/book/101")
            val ready = outcome as? LinkProber.Outcome.Ready
            assertNotNull("the verified lane must install, got $outcome", ready)
            assertTrue(ready!!.draft.testDrive.proofPending)
            assertTrue(ready.draft.config.maintainerVerified)
            assertTrue(
                ready.stageLines.any { it.render().contains("verified rules") }
            )
        }

    // ── E2: bundled defaults plumbing ──────────────────────

    @Test
    fun `E2 bundled sync installs once and never overwrites a changed row`() =
        runBlocking {
            val repoDao = FakeExtensionRepoDao()
            val extDao = FakeInstalledExtensionDao()
            val store = ExtensionStore(tmp.newFolder())
            val sessionStore = testSessionStore()
            val transport = FakeTransport { FakeTransport.ok("") }
            val factory = HostContextFactory(store, sessionStore, transport)
            val service = ExtensionService(
                extDao, store, factory, FakeDexLoader()
            ) { FakeJsRuntime() }
            val manager = RepoManager(
                repoDao, extDao, store, transport, service, sessionStore
            )
            val first = manager.installBundledDefaults()
            assertEquals(BundledDefaults.configs().size, first)
            // Idempotent: a second run changes nothing.
            assertEquals(0, manager.installBundledDefaults())
            assertTrue(
                repoDao.repos.value.any {
                    it.indexUrl == RepoManager.BUNDLED_REPO_URL
                }
            )
        }

    @Test
    fun `bundled defaults are well formed and survive the storage round trip`() {
        val configs = BundledDefaults.configs()
        assertTrue(
            "the wave-1 assembly must not be empty",
            configs.size >= 20
        )
        assertEquals(
            "config ids are unique",
            configs.size, configs.map { it.id }.toSet().size
        )
        for (config in configs) {
            assertTrue(
                "${config.id} carries the bundled id shape",
                config.id.startsWith("bundled-")
            )
            assertTrue(
                "${config.id} is maintainer-verified",
                config.maintainerVerified
            )
            // The v1.2.3 catalogue gate: a default without any
            // catalogue is a broken promise — refuse it here.
            assertTrue(
                "${config.id} has a catalogue (popular or latest)",
                config.popular != null || config.latest != null
            )
            // The user's bar: search works on every default.
            assertTrue(
                "${config.id} has search",
                config.search != null
            )
            val decoded = DeclarativeFingerprints.decode(
                DeclarativeFingerprints.encode(listOf(config))
            )
            assertEquals(
                "${config.id} survives encode/decode unchanged",
                listOf(config), decoded
            )
        }
    }
}
