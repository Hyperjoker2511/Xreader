package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeExtensionRepoDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import java.io.File
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.2.3 — the catalogue defect, fail-before / pass-after.
 *
 * The v1.2.2 investigation (V122SavedSourceSurveyTest) proved
 * the shared root end-to-end: the probe's pass gate was the
 * BOOK test-drive only, so a source could be SAVED WITH NO
 * CATALOGUE (popular/latest/search all null); the contract
 * tester's LIST step then threw "has no catalogue page"
 * before any fetch, and Health reported "Needs setup" for a
 * source Repair had just "passed". On the pre-fix tree every
 * test in this file failed with those exact signatures:
 *
 *  1. novelbuddy probe → Ready with popular=null latest=null;
 *     contract LIST "… has no catalogue page".
 *  2. a book-only site probed Ready (saved-but-broken) instead
 *     of being refused at add time.
 *  3. saveLinkSource accepted a catalogue-less config (Ok).
 *  4. installLiveSource named novelbuddy
 *     "NovelBuddy— Read Web Novels & Light Novels Onlin"
 *     (the raw <title>, cut mid-word at 48 chars — the exact
 *     string the user's phone showed).
 *  5. botitranslation had no known-sites lane at all.
 *  6. the embedded-JSON discovery found nothing (the class
 *     did not exist).
 *
 * Fixtures: the REAL novelbuddy captures under
 * fixtures-capture/novelbuddy.com (see the survey test for
 * provenance), boti pages modelled on the site's own bundle
 * (as in V121BotiFixTest), and one synthetic book-only site.
 */
class V123CatalogueGateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── shared stack (mirrors V122SavedSourceSurveyTest) ────

    private class Stack(
        val manager: RepoManager,
        val service: ExtensionService,
        val prober: LinkProber
    )

    private fun stack(transport: FakeTransport): Stack {
        val repoDao = FakeExtensionRepoDao()
        val extDao = FakeInstalledExtensionDao()
        val store = ExtensionStore(tmp.newFolder())
        val sessionStore = SessionStore(testVault())
        val factory = HostContextFactory(store, sessionStore, transport)
        val service = ExtensionService(
            extDao, store, factory, FakeDexLoader()
        ) { FakeJsRuntime() }
        val manager = RepoManager(
            repoDao, extDao, store, transport, service, sessionStore
        )
        return Stack(manager, service, LinkProber(factory, sessionStore))
    }

    private fun captureDir(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val base = dir ?: return null
            val candidate = File(base, "fixtures-capture/novelbuddy.com")
            if (candidate.isDirectory) return candidate
            dir = base.parentFile
        }
        return null
    }

    /** The captured search page with its ssrItems array
     * emptied — what the site serves for a nonsense query, so
     * the search filter proof can pass honestly. */
    private fun emptiedSearchPage(searchHtml: String): String {
        val marker = "\"ssrItems\":"
        val at = searchHtml.indexOf(marker)
        if (at < 0) return searchHtml
        val open = searchHtml.indexOf('[', at)
        if (open < 0) return searchHtml
        var depth = 0
        var inString = false
        var escaped = false
        var i = open
        while (i < searchHtml.length) {
            val c = searchHtml[i]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) {
                            return searchHtml.substring(0, open + 1) +
                                searchHtml.substring(i)
                        }
                    }
                }
            }
            i++
        }
        return searchHtml
    }

    private fun novelbuddyTransport(dir: File): FakeTransport {
        fun read(name: String) = File(dir, name).readText(Charsets.UTF_8)
        val bodies = mapOf(
            "home" to read("home.html"),
            "latest" to read("latest.html"),
            "book" to read("book.html"),
            "chapter" to read("chapter.html"),
            "search" to read("search.html"),
            "notfound" to read("browse.html")
        )
        val emptySearch = emptiedSearchPage(bodies.getValue("search"))
        val bookShape = Regex("https://novelbuddy\\.me/[a-z0-9][a-z0-9-]*")
        val chapterShape =
            Regex("https://novelbuddy\\.me/[a-z0-9-]+/chapter-.*")
        val chrome = setOf(
            "https://novelbuddy.me/latest", "https://novelbuddy.me/ranking",
            "https://novelbuddy.me/search", "https://novelbuddy.me/browse"
        )
        return FakeTransport { request ->
            val url = request.url
            if (request.method != "GET") {
                return@FakeTransport AppResult.Ok(
                    FakeTransport.response(404, bodies.getValue("notfound"))
                )
            }
            val body = when {
                url == "https://novelbuddy.me" ||
                    url == "https://novelbuddy.me/" -> bodies.getValue("home")
                url.substringBefore('?') == "https://novelbuddy.me/latest" ->
                    bodies.getValue("latest")
                url.substringBefore('?') == "https://novelbuddy.me/search" ->
                    if (url.contains(SearchFilterProof.NONSENSE_TERM)) {
                        emptySearch
                    } else {
                        bodies.getValue("search")
                    }
                chapterShape.matches(url.substringBefore('?')) ->
                    bodies.getValue("chapter")
                bookShape.matches(url.substringBefore('?')) &&
                    url.substringBefore('?') !in chrome ->
                    bodies.getValue("book")
                else -> null
            }
            if (body == null) {
                AppResult.Ok(
                    FakeTransport.response(404, bodies.getValue("notfound"))
                )
            } else {
                FakeTransport.ok(body)
            }
        }
    }

    // ── 1. novelbuddy: catalogue + full contract ────────────

    @Test(timeout = 900_000)
    fun `novelbuddy probe carries a catalogue and the saved source passes the full contract`() =
        runBlocking {
            val dir = captureDir()
            assumeTrue("novelbuddy capture not on disk", dir != null)
            val stack = stack(novelbuddyTransport(dir!!))
            val outcome = stack.prober.probe(
                "https://novelbuddy.me/eternal-holy-emperor"
            )
            val ready = outcome as? LinkProber.Outcome.Ready
            assertNotNull(
                "probe should draft a source, got: $outcome", ready
            )
            val config = ready!!.draft.config
            println(
                "[v123] novelbuddy draft popular=${config.popular} " +
                    "latest=${config.latest} search=${config.search}"
            )
            assertTrue(
                "the draft must carry a verified catalogue",
                config.popular != null || config.latest != null
            )
            val saved = stack.manager.saveLinkSource(
                config, ready.draft.host
            )
            assertTrue("the draft saves: $saved", saved is AppResult.Ok)
            val handle = stack.service
                .sourcesOf((saved as AppResult.Ok).value.id)
                .getOrNull()?.firstOrNull()
            assertNotNull(handle)
            val verdict = ContractTester(stack.service).test(handle!!)
            verdict.results.forEach { r ->
                println("[v123] contract ${r.capability}: ok=${r.ok} — ${r.detail}")
            }
            fun step(capability: SourceCapability) =
                verdict.results.first { it.capability == capability }
            assertTrue(
                "LIST: ${step(SourceCapability.LIST).detail}",
                step(SourceCapability.LIST).ok
            )
            assertTrue(
                "DETAILS: ${step(SourceCapability.DETAILS).detail}",
                step(SourceCapability.DETAILS).ok
            )
            assertTrue(
                "CHAPTERS: ${step(SourceCapability.CHAPTERS).detail}",
                step(SourceCapability.CHAPTERS).ok
            )
            assertTrue(
                "TEXT: ${step(SourceCapability.TEXT).detail}",
                step(SourceCapability.TEXT).ok
            )
            assertTrue(
                "SEARCH: ${step(SourceCapability.SEARCH).detail}",
                step(SourceCapability.SEARCH).ok
            )
            Unit
        }

    // ── 2. the gate: a book-only site is refused at add time ─

    private val STORY_BASE = "https://storyonly.example"
    private val STORY_BOOK = "$STORY_BASE/book/1"
    private val STORY_PROSE =
        "Maren kept the lighthouse log in a hand so steady that " +
            "visitors mistook it for print, though the wind shook " +
            "the tower every night of the winter she turned " +
            "nineteen. She wrote the weather, the ships, and once, " +
            "in the margin beside a gale entry, the single line she " +
            "never explained to anyone: the light is not for the " +
            "ships at all. Years later her grandson found the log " +
            "in a salt-stained trunk and read that line by the " +
            "light of a torch, standing in the same round room, " +
            "while below him the tide worried the rocks exactly " +
            "as it had on the night she wrote it."

    private fun storyTransport(): FakeTransport {
        val bookPage = """
            <html><head><title>The Lighthouse Log</title></head><body>
              <main>
                <h1>The Lighthouse Log</h1>
                <img class="cover" src="/covers/log.jpg"/>
                <ul class="chapters">
                  <li><a href="/book/1/chapter-1">Chapter 1</a></li>
                  <li><a href="/book/1/chapter-2">Chapter 2</a></li>
                  <li><a href="/book/1/chapter-3">Chapter 3</a></li>
                </ul>
              </main>
            </body></html>
        """.trimIndent()
        val paragraphs = STORY_PROSE.split(". ")
            .map { "$it." }.joinToString("") { "<p>$it</p>" }
        val chapterPage = """
            <html><head><title>Chapter 1</title></head><body>
              <h1>Chapter 1</h1>
              <div class="content">$paragraphs</div>
            </body></html>
        """.trimIndent()
        return FakeTransport { request ->
            val url = request.url
            when {
                url == STORY_BOOK -> FakeTransport.ok(bookPage)
                url.startsWith("$STORY_BASE/book/1/chapter-") ->
                    FakeTransport.ok(chapterPage)
                else -> AppResult.Ok(FakeTransport.response(404, ""))
            }
        }
    }

    @Test(timeout = 900_000)
    fun `a book-only site is refused at add time with the catalogue message`() =
        runBlocking {
            val stack = stack(storyTransport())
            val outcome = stack.prober.probe(STORY_BOOK)
            val failed = outcome as? LinkProber.Outcome.Failed
            assertNotNull(
                "a site with no book list must NOT draft a source, " +
                    "got: $outcome",
                failed
            )
            assertEquals(
                LinkProber.CATALOGUE_MISSING_MESSAGE,
                failed!!.message
            )
            assertTrue(
                "the stage lines must name the missing book list: " +
                    failed.stageLines.joinToString { it.render() },
                failed.stageLines.any { it.step == "book list" }
            )
            Unit
        }

    // ── 3. the save path refuses catalogue-less configs ─────

    @Test
    fun `saveLinkSource refuses a catalogue-less config`() = runBlocking {
        val stack = stack(FakeTransport {
            AppResult.Ok(FakeTransport.response(404, ""))
        })
        val config = DeclarativeConfig(
            id = "no-catalogue",
            name = "NoCatalogue",
            baseUrl = "https://nocatalogue.example",
            details = DetailsSpec(
                title = "h1",
                chapters = ChapterListSpec(
                    list = "ul li", name = "a@text", link = "a@href"
                )
            ),
            chapter = ChapterSpec(content = "article")
        )
        val result = stack.manager.saveLinkSource(config, "nocatalogue.example")
        assertTrue(
            "a catalogue-less config must be refused, got: $result",
            result is AppResult.Err
        )
        val error = (result as AppResult.Err).error
        val reason = (error as? app.xreader.core.error.AppError.BadFile)
            ?.reason ?: error.detail ?: ""
        assertTrue(
            "the refusal names the missing book list: $reason",
            reason.contains("book list")
        )
        Unit
    }

    // ── 4. the Live-install name defect ─────────────────────

    @Test(timeout = 300_000)
    fun `live install names novelbuddy cleanly`() = runBlocking {
        val dir = captureDir()
        assumeTrue("novelbuddy capture not on disk", dir != null)
        val stack = stack(novelbuddyTransport(dir!!))
        val installed = stack.manager.installLiveSource("https://novelbuddy.me/")
        assertTrue("install: $installed", installed is AppResult.Ok)
        assertEquals(
            "NovelBuddy",
            (installed as AppResult.Ok).value.displayName
        )
        Unit
    }

    @Test
    fun `cleanSiteTitle keeps the head segment only`() {
        assertEquals(
            "NovelBuddy",
            RepoManager.cleanSiteTitle(
                "NovelBuddy— Read Web Novels & Light Novels Online Free"
            )
        )
        assertEquals(
            "Some Site",
            RepoManager.cleanSiteTitle("Some Site | Read Novels Online")
        )
        assertEquals(
            "Plain Title",
            RepoManager.cleanSiteTitle("Plain Title")
        )
    }

    // ── 5. botitranslation: lane + probe ────────────────────

    private val BOTI_BASE = "https://botitranslation.com"
    private val BOTI_BOOK = "$BOTI_BASE/mobile/book/101"

    private fun botiCard(id: Int, title: String): String = """
        <div class="swiper-slide">
          <a href="/mobile/book/$id"><img src="/covers/$id.jpg" alt="$title"/></a>
        </div>
    """.trimIndent()

    private val BOTI_HOME = """
        <html><head><title>Home</title></head><body class="skin-default home">
          <div class="main-container">
            <section class="rail"><div class="swiper"><div class="swiper-wrapper">
              ${botiCard(101, "Sword Emperor of the Ninth Heaven")}
              ${botiCard(102, "The Genius Midfielder")}
              ${botiCard(103, "Last on Earth")}
              ${botiCard(104, "Reloaded into the Other World")}
            </div></div></section>
            <section class="rail"><div class="swiper"><div class="swiper-wrapper">
              ${botiCard(105, "The Earth's Chosen Savior")}
              ${botiCard(106, "The Roguish Guard in a Medieval World")}
              ${botiCard(107, "Copy Skills With Affinity!")}
              ${botiCard(108, "A Quiet Village Life")}
            </div></div></section>
          </div>
        </body></html>
    """.trimIndent()

    private val BOTI_PROSE =
        "The rain had been falling on the harbour town for three " +
            "days when the letter arrived, sealed with black wax " +
            "and carrying no name but his own. Tomas read it twice " +
            "by the lamp, then fed it to the fire and watched the " +
            "words curl away, because some messages are safer " +
            "forgotten and some debts are only collected in the " +
            "dark, and he had been expecting this one for years."

    private val BOTI_BOOK_PAGE = """
        <html><head><title>Sword Emperor of the Ninth Heaven</title></head>
        <body class="skin-default">
          <div class="main-container">
            <h1>Sword Emperor of the Ninth Heaven</h1>
            <img class="cover" src="/covers/101.jpg" alt="Sword Emperor of the Ninth Heaven"/>
            <ul class="chapter-list">
              <li><a href="/mobile/chapter/3001">Chapter 1</a></li>
              <li><a href="/mobile/chapter/3002">Chapter 2</a></li>
              <li><a href="/mobile/chapter/3003">Chapter 3</a></li>
              <li><a href="/mobile/chapter/3004">Chapter 4</a></li>
            </ul>
          </div>
        </body></html>
    """.trimIndent()

    private val BOTI_CHAPTER_PAGE = """
        <html><body><main><article>
          <h1>Chapter 1</h1>
          <p>$BOTI_PROSE</p><p>$BOTI_PROSE</p>
        </article></main></body></html>
    """.trimIndent()

    private fun botiTransport(): FakeTransport = FakeTransport { request ->
        val url = request.url
        when {
            url == BOTI_BOOK -> FakeTransport.ok(BOTI_BOOK_PAGE)
            url.startsWith("$BOTI_BASE/mobile/chapter/") ->
                FakeTransport.ok(BOTI_CHAPTER_PAGE)
            url == "$BOTI_BASE/mobile/" || url == "$BOTI_BASE/" ||
                url == BOTI_BASE -> FakeTransport.ok(BOTI_HOME)
            else -> AppResult.Ok(FakeTransport.response(404, ""))
        }
    }

    @Test
    fun `boti is a known site whose lane config carries a catalogue`() {
        val entry = KnownSites.entryFor("botitranslation.com")
        assertNotNull("botitranslation must be a known site", entry)
        val config = KnownSites.fullConfig(
            entry!!, "link-boti", "BotiTranslation", BOTI_BASE
        )
        assertNotNull("the BOTI family builds a full config", config)
        assertNotNull("the lane config carries a catalogue", config!!.popular)
        assertTrue(
            "the catalogue is the /mobile/ home",
            config.popular!!.url.endsWith("/mobile/")
        )
        assertTrue(
            "a shell site's config must read rendered pages",
            config.needsRendering
        )
        assertNull(
            "no search shape is derivable — honestly absent",
            config.search
        )
    }

    @Test(timeout = 900_000)
    fun `boti probe drafts a source with a working catalogue`() = runBlocking {
        val transport = botiTransport()
        val stack = stack(transport)
        val outcome = stack.prober.probe(BOTI_BOOK)
        val ready = outcome as? LinkProber.Outcome.Ready
        assertNotNull("boti probe should draft, got: $outcome", ready)
        val config = ready!!.draft.config
        println(
            "[v123] boti draft popular=${config.popular} " +
                "needsRendering=${config.needsRendering}"
        )
        assertTrue(
            "the boti draft carries a catalogue",
            config.popular != null || config.latest != null
        )
        // The catalogue actually reads books through the engine.
        val source = DeclarativeSource(
            config,
            testContext(
                transport, tmp.newFolder(),
                ExtensionPermissions(domains = listOf("botitranslation.com"))
            )
        )
        val page = source.popular(1)
        assertTrue(
            "the catalogue reads books: ${page.books.map { it.title }}",
            page.books.size >= 4
        )
        Unit
    }

    // ── 6. embedded-JSON discovery on the real captures ─────

    @Test
    fun `embedded JSON discovery reads novelbuddy search and chapters`() {
        val dir = captureDir()
        assumeTrue("novelbuddy capture not on disk", dir != null)
        fun read(name: String) = File(dir!!, name).readText(Charsets.UTF_8)

        val searchDoc = Jsoup.parse(
            read("search.html"), "https://novelbuddy.me/search?q=eternal"
        )
        val books = EmbeddedJson.discoverBookArray(searchDoc, minItems = 1)
        assertNotNull("search results live in ssrItems", books)
        assertEquals(24, books!!.count)
        assertEquals("name", books.titleField)
        assertEquals("url", books.linkField)

        val bookDoc = Jsoup.parse(
            read("book.html"), "https://novelbuddy.me/eternal-holy-emperor"
        )
        val chapters = EmbeddedJson.discoverChapterArray(bookDoc)
        assertNotNull("the book page payload carries chapters", chapters)
        assertEquals(50, chapters!!.count)
        assertEquals(2217, chapters.statedTotal)

        val latestDoc = Jsoup.parse(
            read("latest.html"), "https://novelbuddy.me/latest"
        )
        val latest = EmbeddedJson.discoverBookArray(latestDoc, minItems = 3)
        assertNotNull("the latest payload carries the catalogue", latest)
        assertEquals(24, latest!!.count)
    }
}
