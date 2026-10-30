package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.0.6.2: F4 (home-page pastes walk into a book; refusals
 * name what was actually pasted), F5 (meaning checks — the
 * list-shape judge, the always-on prose gate, the one-call AI
 * verify), F2's tester page-2 proof, and RA's pure lane
 * refresh.
 */
class V1062ProbeSemanticsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun prober(transport: FakeTransport): LinkProber {
        val factory = HostContextFactory(
            ExtensionStore(tmp.newFolder()), testSessionStore(), transport
        )
        return LinkProber(factory, testSessionStore())
    }

    // ── F4: home-page pastes ───────────────────────────────────

    @Test
    fun `a pasted home page walks into its first book`() = runBlocking {
        val outcome = prober(LyingSearchSite.transport())
            .probe(LyingSearchSite.BASE)
        assertTrue(
            "expected Ready, got $outcome",
            outcome is LinkProber.Outcome.Ready
        )
        val ready = outcome as LinkProber.Outcome.Ready
        assertEquals("home page", ready.stageLines.first().proposer)
        assertEquals("lying.example", ready.draft.host)
        assertEquals(LyingSearchSite.BOOK_URL, ready.draft.bookUrl)
        assertEquals("Quiet Star", ready.draft.testDrive.bookTitle)
    }

    @Test
    fun `a home page whose books cannot be read is refused for what it is`() =
        runBlocking {
            val outcome = prober(EmptyHomeSite.transport())
                .probe(EmptyHomeSite.BASE)
            assertTrue(
                "expected Failed, got $outcome",
                outcome is LinkProber.Outcome.Failed
            )
            assertEquals(
                LinkProber.HOME_PAGE_COPY,
                (outcome as LinkProber.Outcome.Failed).message
            )
        }

    @Test
    fun `a bare site root with no book list gets the same honest refusal`() =
        runBlocking {
            val transport = FakeTransport { request ->
                if (request.url.startsWith(EmptyHomeSite.BASE)) {
                    FakeTransport.ok(EmptyHomeSite.BARE_ROOT_PAGE)
                } else {
                    AppResult.Ok(FakeTransport.response(404, "not found"))
                }
            }
            val outcome = prober(transport).probe(EmptyHomeSite.BASE)
            assertTrue(
                "expected Failed, got $outcome",
                outcome is LinkProber.Outcome.Failed
            )
            assertEquals(
                LinkProber.HOME_PAGE_COPY,
                (outcome as LinkProber.Outcome.Failed).message
            )
        }

    // ── F5: the list-shape judge, through the test-drive ───────

    @Test
    fun `a feed shaped list is refused by the test drive`() = runBlocking {
        val result = prober(FeedSite.transport()).testDrive(
            FeedSite.candidate("${FeedSite.BASE}/feed"), FeedSite.BOOK_URL
        )
        assertTrue("expected Err, got $result", result is AppResult.Err)
        val message = (result as AppResult.Err).error.toString()
        assertTrue(
            "the refusal must name the shape, was: $message",
            message.contains("look like posts, not books")
        )
    }

    @Test
    fun `a book shaped list passes with a clean shape`() = runBlocking {
        val result = prober(FeedSite.transport()).testDrive(
            FeedSite.candidate("${FeedSite.BASE}/novels"), FeedSite.BOOK_URL
        )
        assertTrue("expected Ok, got $result", result is AppResult.Ok)
        assertEquals(
            ListShapeVerdict.CLEAN,
            (result as AppResult.Ok).value.listShape
        )
    }

    @Test
    fun `one post among books is borderline and proven by its first book`() =
        runBlocking {
            val result = prober(FeedSite.transport()).testDrive(
                FeedSite.candidate("${FeedSite.BASE}/mixed"),
                FeedSite.BOOK_URL
            )
            assertTrue("expected Ok, got $result", result is AppResult.Ok)
            assertEquals(
                ListShapeVerdict.BORDERLINE,
                (result as AppResult.Ok).value.listShape
            )
        }

    @Test
    fun `a post in first place fails the borderline follow check`() =
        runBlocking {
            val result = prober(FeedSite.transport()).testDrive(
                FeedSite.candidate("${FeedSite.BASE}/post-first"),
                FeedSite.BOOK_URL
            )
            assertTrue("expected Err, got $result", result is AppResult.Err)
            val message = (result as AppResult.Err).error.toString()
            assertTrue(
                "the refusal must name the shape, was: $message",
                message.contains("look like posts, not books")
            )
        }

    @Test
    fun `boilerplate chapter text fails the prose gate even when locks read fine`() =
        runBlocking {
            val candidate = FeedSite.candidate("${FeedSite.BASE}/novels")
                .copy(chapter = ChapterSpec(content = "#boilerplate"))
            val result = prober(FeedSite.transport())
                .testDrive(candidate, FeedSite.BOOK_URL)
            assertTrue("expected Err, got $result", result is AppResult.Err)
            val message = (result as AppResult.Err).error.toString()
            assertTrue(
                "the refusal must say the text could not be read, was: $message",
                message.contains("the chapter text could not be read")
            )
        }

    // ── F5: the probe-level AI verify (borderline band only) ───

    @Test
    fun `an ai no turns a borderline candidate away`() = runBlocking {
        val outcome = prober(MixedHomeSite.transport()).probe(
            MixedHomeSite.BOOK_URL,
            semanticVerifier = { _, _ -> false }
        )
        assertTrue(
            "expected Failed, got $outcome",
            outcome is LinkProber.Outcome.Failed
        )
        val failed = outcome as LinkProber.Outcome.Failed
        assertTrue(failed.message.contains("The AI check looked at"))
        assertTrue(failed.stageLines.any { it.proposer == "AI check" })
    }

    @Test
    fun `an ai yes is recorded on the stage lines`() = runBlocking {
        val outcome = prober(MixedHomeSite.transport()).probe(
            MixedHomeSite.BOOK_URL,
            semanticVerifier = { _, _ -> true }
        )
        assertTrue(
            "expected Ready, got $outcome",
            outcome is LinkProber.Outcome.Ready
        )
        val ready = outcome as LinkProber.Outcome.Ready
        assertTrue(
            ready.stageLines.any {
                it.proposer == "AI check" && it.step == "confirmed"
            }
        )
    }

    @Test
    fun `without a verifier the borderline verdict stands on its own`() =
        runBlocking {
            val outcome = prober(MixedHomeSite.transport())
                .probe(MixedHomeSite.BOOK_URL)
            assertTrue(
                "expected Ready, got $outcome",
                outcome is LinkProber.Outcome.Ready
            )
            val ready = outcome as LinkProber.Outcome.Ready
            assertTrue(ready.stageLines.none { it.proposer == "AI check" })
        }

    @Test
    fun `a feed home fails the probe with the shape named`() = runBlocking {
        val outcome = prober(MixedHomeSite.transport(feedHome = true))
            .probe(MixedHomeSite.BOOK_URL)
        assertTrue(
            "expected Failed, got $outcome",
            outcome is LinkProber.Outcome.Failed
        )
        assertTrue(
            (outcome as LinkProber.Outcome.Failed).message
                .contains("look like posts, not books")
        )
    }

    // ── F2: the contract tester's page-2 proof ─────────────────

    private fun testerVerdict(
        config: DeclarativeConfig,
        transport: FakeTransport
    ): ContractVerdict {
        val store = ExtensionStore(tmp.newFolder())
        val sessionStore = testSessionStore()
        val factory = HostContextFactory(store, sessionStore, transport)
        val extDao = FakeInstalledExtensionDao()
        val service = ExtensionService(
            extDao, store, factory, FakeDexLoader()
        ) { FakeJsRuntime() }
        val manager = RepoManager(
            FakeExtensionRepoDao(), extDao, store, transport,
            service, sessionStore
        )
        val saved = manager.saveLinkSource(config, PagingSite.HOST)
        val row = (saved as AppResult.Ok).value
        val handle = (service.sourcesOf(row.id) as AppResult.Ok)
            .value.first()
        return ContractTester(service).test(handle)
    }

    @Test
    fun `the tester fails a catalogue whose page 2 repeats page 1`() =
        runBlocking {
            val verdict = testerVerdict(
                PagingSite.config(), PagingSite.transport(paging = false)
            )
            val list = verdict.results.first {
                it.capability == SourceCapability.LIST
            }
            assertTrue(!list.ok)
            assertTrue(list.detail.contains("page 2 shows the same books"))
        }

    @Test
    fun `the tester passes a catalogue that really pages`() = runBlocking {
        val verdict = testerVerdict(
            PagingSite.config(), PagingSite.transport(paging = true)
        )
        val list = verdict.results.first {
            it.capability == SourceCapability.LIST
        }
        assertTrue(list.ok)
        assertTrue(list.detail.contains("page 2 shows different books"))
    }

    // ── RA: the pure lane refresh ──────────────────────────────

    @Test
    fun `a stale lane config is refreshed and keeps its own choices`() {
        val entry = KnownSites.entryFor("novelight.net")!!
        val current = KnownSites.fullConfig(
            entry, "link-x", "Novelight", "https://novelight.net"
        )!!
        val stale = current.copy(
            popular = current.popular!!.copy(
                url = "https://novelight.net/catalog/"
            ),
            details = current.details.copy(
                chapters = current.details.chapters.copy(pagination = null)
            ),
            sampleBookUrl = "https://novelight.net/book/whatever",
            needsRendering = true
        )
        val refreshed = HealSweepLogic.refreshedLaneConfig(stale)
        assertTrue(refreshed != null)
        assertTrue(refreshed!!.details.chapters.pagination != null)
        assertTrue(refreshed.popular!!.url.contains("{page}"))
        assertEquals(
            "https://novelight.net/book/whatever", refreshed.sampleBookUrl
        )
        assertTrue(refreshed.needsRendering)
    }

    @Test
    fun `a current lane config and a foreign config are left alone`() {
        val entry = KnownSites.entryFor("novelight.net")!!
        val current = KnownSites.fullConfig(
            entry, "link-x", "Novelight", "https://novelight.net"
        )!!
        assertNull(HealSweepLogic.refreshedLaneConfig(current))
        assertNull(HealSweepLogic.refreshedLaneConfig(FixtureSite.config()))
    }
}

/** A home page whose book links lead nowhere readable — the
 * walk-in tries and honestly gives up (F4). */
private object EmptyHomeSite {
    const val HOST = "empty.example"
    const val BASE = "https://empty.example"

    val HOME_PAGE = """
        <html><head><title>EmptyBooks</title></head><body>
        <div class="grid">
          <a href="/book/one"><img src="/covers/one.jpg"/>Book One</a>
          <a href="/book/two"><img src="/covers/two.jpg"/>Book Two</a>
        </div>
        </body></html>
    """.trimIndent()

    val BOOK_PAGE = """
        <html><head><title>Book One</title></head><body>
        <h1>Book One</h1>
        <p>Short.</p>
        </body></html>
    """.trimIndent()

    val BARE_ROOT_PAGE = """
        <html><head><title>EmptyBooks</title></head><body>
        <form action="/search" method="get"><input type="text" name="q"/></form>
        <p>Welcome to EmptyBooks.</p>
        </body></html>
    """.trimIndent()

    fun transport(): FakeTransport = FakeTransport { request ->
        val url = request.url
        val body = when {
            url == BASE || url == "$BASE/" -> HOME_PAGE
            url.startsWith("$BASE/book/") -> BOOK_PAGE
            else -> null
        }
        if (body == null) {
            AppResult.Ok(FakeTransport.response(404, "not found"))
        } else {
            FakeTransport.ok(body)
        }
    }
}

/**
 * The F5 fixture: one site carrying a social FEED and a real
 * book list in the same card markup, one real book with prose
 * chapters, and post pages with no chapters and no synopsis.
 * The candidate config is hand-built — the judge is exercised
 * through the public test-drive, exactly as Repair drives it.
 */
private object FeedSite {
    const val HOST = "feed.example"
    const val BASE = "https://feed.example"
    const val BOOK_URL = "$BASE/book/quiet-star"

    private fun card(href: String, title: String): String = """
        <div class="card">
          <a class="t" href="$href">$title</a>
          <img class="c" src="/img/x.jpg"/>
        </div>
    """.trimIndent()

    private fun listingPage(cards: String): String = """
        <html><head><title>Listing</title></head><body>
        <div class="grid">$cards</div>
        </body></html>
    """.trimIndent()

    val FEED_PAGE = listingPage(
        card("/post/1", "Monarch just now Ruined the mood") +
            card("/post/2", "Theonebeggar21 just now Neck could be better") +
            card("/post/3", "Junsakazuki just now hmm I have a bad feelings") +
            card("/post/4", "Magician just now")
    )

    val NOVELS_PAGE = listingPage(
        card("/book/quiet-star", "Quiet Star") +
            card("/book/loud-moon", "Loud Moon") +
            card("/book/dim-sun", "Dim Sun")
    )

    val MIXED_PAGE = listingPage(
        card("/book/quiet-star", "Quiet Star") +
            card("/book/loud-moon", "Loud Moon") +
            card("/book/dim-sun", "Dim Sun") +
            card("/post/1", "Monarch just now Ruined the mood")
    )

    val POST_FIRST_PAGE = listingPage(
        card("/post/1", "Monarch just now Ruined the mood") +
            card("/book/quiet-star", "Quiet Star") +
            card("/book/loud-moon", "Loud Moon") +
            card("/book/dim-sun", "Dim Sun")
    )

    val chapterText = (1..5).joinToString("\n") {
        "The quiet star watched over the harbour in part $it, " +
            "and nobody below noticed at all."
    }

    /** Long, comma-joined boilerplate: plenty of characters,
     * (almost) no sentence punctuation — the prose gate's
     * target shape. */
    val boilerplateText = (1..12).joinToString(", ") {
        "alpha bravo charlie delta echo foxtrot golf hotel india " +
            "juliet kilo lima mike november oscar papa quebec romeo"
    }

    private fun chapterPage(n: Int): String = """
        <html><head><title>Chapter $n</title></head><body>
        <h1>Chapter $n</h1>
        <div id="chapter-content">
          ${chapterText.split("\n").joinToString("\n") { "<p>$it</p>" }}
        </div>
        <div id="boilerplate">$boilerplateText</div>
        </body></html>
    """.trimIndent()

    val BOOK_PAGE = """
        <html><head>
          <title>Quiet Star - FeedBooks</title>
          <meta name="description" content="A quiet star saga."/>
        </head><body>
        <h1>Quiet Star</h1>
        <ul id="chapter-list">
          <li><a href="/book/quiet-star/chapter-3">Chapter 3</a></li>
          <li><a href="/book/quiet-star/chapter-2">Chapter 2</a></li>
          <li><a href="/book/quiet-star/chapter-1">Chapter 1</a></li>
        </ul>
        </body></html>
    """.trimIndent()

    val POST_PAGE = """
        <html><head><title>Monarch's post</title></head><body>
        <h1>Monarch</h1>
        <p>Ruined the mood.</p>
        </body></html>
    """.trimIndent()

    fun candidate(popularUrl: String): DeclarativeConfig = DeclarativeConfig(
        id = "feed-test",
        name = "Feed Test",
        baseUrl = BASE,
        popular = ListSpec(
            url = popularUrl,
            results = "div.card",
            title = "a.t",
            link = "a.t@href",
            cover = "img.c@src"
        ),
        details = DetailsSpec(
            title = "h1",
            description = "meta[name=description]@content",
            chapters = ChapterListSpec(
                list = "ul#chapter-list > li",
                name = "a",
                link = "a@href",
                reverse = true
            )
        ),
        chapter = ChapterSpec(content = "#chapter-content")
    )

    fun transport(): FakeTransport = FakeTransport { request ->
        val url = request.url
        val body = when {
            url == BOOK_URL -> BOOK_PAGE
            url.startsWith("$BASE/book/quiet-star/chapter-") ->
                chapterPage(url.substringAfterLast("chapter-").toInt())
            url == "$BASE/feed" -> FEED_PAGE
            url == "$BASE/novels" -> NOVELS_PAGE
            url == "$BASE/mixed" -> MIXED_PAGE
            url == "$BASE/post-first" -> POST_FIRST_PAGE
            url.startsWith("$BASE/post/") -> POST_PAGE
            else -> null
        }
        if (body == null) {
            AppResult.Ok(FakeTransport.response(404, "not found"))
        } else {
            FakeTransport.ok(body)
        }
    }
}

/**
 * The probe-level F5 staging: a real book (the LyingSearchSite
 * skeleton) whose site HOME — which becomes the inference
 * candidate's popular list — mixes book cards with post cards.
 * First card is a real book, so the shape judges BORDERLINE;
 * with [feedHome] every card is a post and the shape is FEED.
 */
private object MixedHomeSite {
    const val HOST = "mixed.example"
    const val BASE = "https://mixed.example"
    const val BOOK_URL = "$BASE/book/quiet-star"

    val chapterText = (1..5).joinToString("\n") {
        "The quiet star watched over the harbour in part $it, " +
            "and nobody below noticed at all."
    }

    private fun chapterPage(n: Int): String = """
        <html><head><title>Chapter $n</title></head><body>
        <h1>Chapter $n</h1>
        <div id="chapter-content">
          ${chapterText.split("\n").joinToString("\n") { "<p>$it</p>" }}
        </div>
        </body></html>
    """.trimIndent()

    val BOOK_PAGE = """
        <html><head>
          <title>Quiet Star - MixedBooks</title>
          <meta property="og:title" content="Quiet Star"/>
          <meta name="description" content="A quiet star saga."/>
        </head><body>
        <form action="/catalogue" method="get"><input type="text" name="q"/></form>
        <h1>Quiet Star</h1>
        <ul id="chapter-list">
          <li><a href="/book/quiet-star/chapter-3">Chapter 3</a></li>
          <li><a href="/book/quiet-star/chapter-2">Chapter 2</a></li>
          <li><a href="/book/quiet-star/chapter-1">Chapter 1</a></li>
        </ul>
        </body></html>
    """.trimIndent()

    private fun anchor(href: String, img: String, text: String): String =
        "<a href=\"$href\"><img src=\"$img\"/>$text</a>"

    val MIXED_HOME = """
        <html><body><div class="grid">
          ${anchor("/book/quiet-star", "/covers/quiet.jpg", "Quiet Star")}
          ${anchor("/book/loud-moon", "/covers/moon.jpg", "Loud Moon")}
          ${anchor("/book/dim-sun", "/covers/sun.jpg", "Dim Sun")}
          ${anchor("/post/1", "/avatars/m.jpg", "Monarch just now Ruined the mood")}
        </div></body></html>
    """.trimIndent()

    val FEED_HOME = """
        <html><body><div class="grid">
          ${anchor("/post/1", "/avatars/m.jpg", "Monarch just now Ruined the mood")}
          ${anchor("/post/2", "/avatars/t.jpg", "Theonebeggar21 just now Neck could be better")}
          ${anchor("/post/3", "/avatars/j.jpg", "Junsakazuki just now hmm I have a bad feelings")}
          ${anchor("/post/4", "/avatars/g.jpg", "Magician just now")}
        </div></body></html>
    """.trimIndent()

    val CATALOGUE_PAGE = """
        <html><body><div class="grid">
          ${anchor("/book/quiet-star", "/covers/quiet.jpg", "Quiet Star")}
          ${anchor("/book/loud-moon", "/covers/moon.jpg", "Loud Moon")}
          ${anchor("/book/dim-sun", "/covers/sun.jpg", "Dim Sun")}
        </div></body></html>
    """.trimIndent()

    val POST_PAGE = """
        <html><head><title>Monarch's post</title></head><body>
        <h1>Monarch</h1>
        <p>Ruined the mood.</p>
        </body></html>
    """.trimIndent()

    fun transport(feedHome: Boolean = false): FakeTransport =
        FakeTransport { request ->
            val url = request.url
            val body = when {
                url == BOOK_URL -> BOOK_PAGE
                url.startsWith("$BASE/book/quiet-star/chapter-") ->
                    chapterPage(url.substringAfterLast("chapter-").toInt())
                url.startsWith("$BASE/catalogue") -> CATALOGUE_PAGE
                url.startsWith("$BASE/post/") -> POST_PAGE
                url == BASE || url == "$BASE/" ->
                    if (feedHome) FEED_HOME else MIXED_HOME
                else -> null
            }
            if (body == null) {
                AppResult.Ok(FakeTransport.response(404, "not found"))
            } else {
                FakeTransport.ok(body)
            }
        }
}

/** F2's tester staging: a {page} catalogue in two flavours —
 * one whose page 2 repeats page 1, one that really pages. */
private object PagingSite {
    const val HOST = "pages.example"
    const val BASE = "https://pages.example"
    const val BOOK_URL = "$BASE/book/alpha"

    val chapterText = (1..5).joinToString("\n") {
        "Alpha team crossed the river in part $it, and the town slept on."
    }

    private fun resultsPage(books: List<Pair<String, String>>): String {
        val cards = books.joinToString("\n") { (href, title) ->
            "<div class=\"result\">" +
                "<a class=\"t\" href=\"$href\">$title</a></div>"
        }
        return """
            <html><head><title>Popular</title></head><body>
            <div class="results">$cards</div>
            </body></html>
        """.trimIndent()
    }

    val PAGE_1 = resultsPage(
        listOf("/book/alpha" to "Alpha", "/book/beta" to "Beta")
    )
    val PAGE_2_SAME = PAGE_1
    val PAGE_2_NEW = resultsPage(listOf("/book/gamma" to "Gamma"))

    val BOOK_PAGE = """
        <html><head>
          <title>Alpha - Pages</title>
          <meta name="description" content="The alpha book."/>
        </head><body>
        <h1>Alpha</h1>
        <ul class="chapters">
          <li><a href="/book/alpha/chapter-2">Chapter 2</a></li>
          <li><a href="/book/alpha/chapter-1">Chapter 1</a></li>
        </ul>
        </body></html>
    """.trimIndent()

    private fun chapterPage(n: Int): String = """
        <html><head><title>Chapter $n</title></head><body>
        <div class="content">
          ${chapterText.split("\n").joinToString("\n") { "<p>$it</p>" }}
        </div>
        </body></html>
    """.trimIndent()

    fun config(): DeclarativeConfig = DeclarativeConfig(
        id = "paging-test",
        name = "Paging Test",
        baseUrl = BASE,
        popular = ListSpec(
            url = "$BASE/popular?page={page}",
            results = "div.result",
            title = "a.t",
            link = "a.t@href"
        ),
        details = DetailsSpec(
            title = "h1",
            description = "meta[name=description]@content",
            chapters = ChapterListSpec(
                list = "ul.chapters > li",
                name = "a",
                link = "a@href",
                reverse = true
            )
        ),
        chapter = ChapterSpec(content = "div.content"),
        sampleBookUrl = BOOK_URL
    )

    fun transport(paging: Boolean): FakeTransport = FakeTransport { request ->
        val url = request.url
        val body = when {
            url == "$BASE/popular?page=1" -> PAGE_1
            url == "$BASE/popular?page=2" ->
                if (paging) PAGE_2_NEW else PAGE_2_SAME
            url == BOOK_URL -> BOOK_PAGE
            url.startsWith("$BASE/book/alpha/chapter-") ->
                chapterPage(url.substringAfterLast("chapter-").toInt())
            else -> null
        }
        if (body == null) {
            AppResult.Ok(FakeTransport.response(404, "not found"))
        } else {
            FakeTransport.ok(body)
        }
    }
}
