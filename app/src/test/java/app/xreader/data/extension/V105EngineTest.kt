package app.xreader.data.extension

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.5 (E4) pure-logic pins: title hygiene (A7), the search
 * filtering proof (A5), locked-chapter detection (A3), repeating
 * list selectors (A2), the known-sites index (A11), JS-shell
 * detection (A9), field sanity (A8) and the cover chain (A6) —
 * all over the trimmed live-capture fixtures in
 * [FastLaneFixtures] plus the house MoonSite fixture.
 */
class V105EngineTest {

    // ── A7: TitleCleaner ────────────────────────────────────────

    @Test
    fun `trailing date stamps are stripped from titles`() {
        assertEquals("Under Heaven", TitleCleaner.clean("Under Heaven 29.10.2026"))
        assertEquals("Under Heaven", TitleCleaner.clean("Under Heaven [29.10.2026]"))
        assertEquals("Under Heaven", TitleCleaner.clean("Under Heaven 2026-10-29"))
    }

    @Test
    fun `site suffix cutting still works`() {
        assertEquals("Star Fall", TitleCleaner.clean("Star Fall - MoonRead"))
        assertEquals("Star Fall", TitleCleaner.clean("Star Fall | MoonRead"))
    }

    @Test
    fun `a date-only title survives cleaning so callers can reject it`() {
        assertEquals("29.10.2026", TitleCleaner.clean("29.10.2026"))
        assertTrue(TitleCleaner.isDateOnly("29.10.2026"))
        assertTrue(TitleCleaner.isDateOnly("(29.10.2026)"))
        assertFalse(TitleCleaner.isDateOnly("Chapter 29.10.2026"))
        assertFalse(TitleCleaner.isDateOnly("Under Heaven"))
    }

    @Test
    fun `slug titles come from the url and reject pure numbers and dates`() {
        assertEquals(
            "Cultivating Immortality In Cave Dwellings",
            TitleCleaner.slugTitle(
                "https://x.example/book/cultivating-immortality-in-cave-dwellings"
            )
        )
        assertNull(TitleCleaner.slugTitle("https://x.example/book/12345"))
        assertNull(TitleCleaner.slugTitle("https://x.example/book/29-10-2026"))
    }

    // ── A5: SearchFilterProof ───────────────────────────────────

    @Test
    fun `search proof requires the real term to find books`() {
        val verdict = SearchFilterProof.judge(emptySet(), emptySet(), null)
        assertFalse(verdict.verified)
        assertTrue(verdict.reason!!.contains("no books"))
    }

    @Test
    fun `search proof fails when a nonsense word finds as much`() {
        val verdict = SearchFilterProof.judge(
            setOf("a", "b"), setOf("a", "b"), null
        )
        assertFalse(verdict.verified)
        assertTrue(verdict.reason!!.contains("doesn't filter"))
    }

    @Test
    fun `search proof fails when results equal the whole catalogue`() {
        val verdict = SearchFilterProof.judge(
            setOf("a", "b"), setOf("c"), setOf("a", "b")
        )
        assertFalse(verdict.verified)
        assertTrue(verdict.reason!!.contains("catalogue"))
    }

    @Test
    fun `search proof passes a genuinely filtering search`() {
        assertTrue(
            SearchFilterProof.judge(setOf("a", "b"), setOf("c"), setOf("a", "b", "c"))
                .verified
        )
        assertTrue(
            SearchFilterProof.judge(setOf("a"), emptySet(), null).verified
        )
    }

    // ── A3: ChapterLocks ────────────────────────────────────────

    @Test
    fun `lock-marked rows are detected and free rows are not`() {
        val doc = Jsoup.parse(NovelightSite.BOOK_PAGE, NovelightSite.BOOK_URL)
        val rows = doc.select(".chapters a.chapter")
        assertEquals(8, rows.size)
        val locked = rows.filter { ChapterLocks.rowIsLocked(it) }
        assertEquals(4, locked.size)
        assertTrue(locked.all { it.attr("href").endsWith("/105") ||
            it.attr("href").endsWith("/104") })
    }

    @Test
    fun `lock detection is token based not substring based`() {
        val doc = Jsoup.parse(
            """<a class="chapter-block" href="/c/1">Chapter 1: The Lock Keeper</a>""",
            "https://x.example/"
        )
        assertFalse(ChapterLocks.rowIsLocked(doc.selectFirst("a")!!))
        assertFalse(ChapterLocks.nameSuggestsLocked("Chapter 1: The Lock Keeper"))
        assertTrue(ChapterLocks.nameSuggestsLocked("Chapter 12 (locked)"))
        assertTrue(ChapterLocks.nameSuggestsLocked("Chapter 9 🔒"))
    }

    @Test
    fun `paywall wording only counts on short pages`() {
        assertTrue(
            ChapterLocks.pageSuggestsLocked(
                "This chapter is locked. Unlock this chapter with coins."
            )
        )
        val longText = "The locked chapter door creaked open. ".repeat(30)
        assertTrue(longText.length > 600)
        assertFalse(ChapterLocks.pageSuggestsLocked(longText))
    }

    // ── A2: list selectors ──────────────────────────────────────

    @Test
    fun `list selectors repeat over every row and carry no positional steps`() {
        val doc = Jsoup.parse(NovelightSite.BOOK_PAGE, NovelightSite.BOOK_URL)
        val firstRow = doc.selectFirst(".chapters a.chapter")!!
        val selector = SelectorSynthesizer.forList(firstRow)
        assertFalse(selector.contains(":nth-of-type"))
        assertEquals(8, doc.select(selector).size)
    }

    @Test
    fun `single element selectors keep their positional behaviour`() {
        val doc = Jsoup.parse(NovelightSite.BOOK_PAGE, NovelightSite.BOOK_URL)
        val firstRow = doc.selectFirst(".chapters a.chapter")!!
        val selector = SelectorSynthesizer.forElement(firstRow)
        assertTrue(selector.contains(":nth-of-type"))
        assertEquals(1, doc.select(selector).size)
    }

    // ── A11: KnownSites ─────────────────────────────────────────

    @Test
    fun `known sites resolve by domain and subdomain only`() {
        assertNotNull(KnownSites.entryFor("novelight.net"))
        assertNotNull(KnownSites.entryFor("www.novelight.net"))
        assertNotNull(KnownSites.entryFor("sub.novelight.net"))
        assertNotNull(KnownSites.entryFor("novelfull.com"))
        assertNull(KnownSites.entryFor("fakenovelight.net"))
        assertNull(KnownSites.entryFor("novelight.net.evil.example"))
        assertNull(KnownSites.entryFor("unknown.example"))
    }

    @Test
    fun `novelight fast lane carries the verified shape`() {
        val entry = KnownSites.entryFor("novelight.net")!!
        val config = KnownSites.fullConfig(
            entry, "link-test", "Novelight", NovelightSite.BASE
        )!!
        assertEquals(
            // v1.0.6.2 (F2): the lane search URL pages, like the
            // catalogue — the sanctioned pin update.
            "https://novelight.net/catalog/?search={query}&page={page}",
            config.search!!.url
        )
        assertEquals(".manga-grid-list a.item", config.popular!!.results)
        assertEquals(".poster img@src", config.popular!!.cover)
        assertEquals(".chapters a.chapter", config.details.chapters.list)
        assertEquals(".title@text", config.details.chapters.name)
        assertTrue(config.details.chapters.reverse)
    }

    @Test
    fun `book page shapes are recognised per family`() {
        val novelight = KnownSites.entryFor("novelight.net")!!
        assertTrue(
            KnownSites.looksLikeBookPage(novelight, NovelightSite.BOOK_URL)
        )
        assertFalse(
            KnownSites.looksLikeBookPage(
                novelight, "https://novelight.net/book/chapter/101"
            )
        )
        val novelfull = KnownSites.entryFor("novelfull.com")!!
        assertTrue(
            KnownSites.looksLikeBookPage(
                novelfull, "https://novelfull.com/martial-peak.html"
            )
        )
        assertFalse(
            KnownSites.looksLikeBookPage(
                novelfull, "https://novelfull.com/martial-peak/chapter-1.html"
            )
        )
    }

    @Test
    fun `novelfull overrides replace the chapter shape and keep the rest`() {
        val entry = KnownSites.entryFor("novelfull.com")!!
        val base = DeclarativeConfig(
            id = "link-test",
            name = "NovelFull",
            baseUrl = NovelFullSite.BASE,
            details = DetailsSpec(
                title = "h3.title",
                chapters = ChapterListSpec(list = "ul li", name = "a@text", link = "a@href")
            ),
            chapter = ChapterSpec(content = "div.text", title = "h1")
        )
        val merged = KnownSites.applyOverrides(entry, base)
        assertEquals(
            "#list-chapter ul.list-chapter li", merged.details.chapters.list
        )
        assertFalse(merged.details.chapters.reverse)
        assertEquals("#chapter-content", merged.chapter.content)
        assertEquals("h1", merged.chapter.title)
        assertEquals("h3.title", merged.details.title)
    }

    @Test
    fun `madara overrides only fill a missing search`() {
        val entry = KnownSites.Entry(
            domains = listOf("madara.example"), family = KnownSites.Family.MADARA
        )
        val base = DeclarativeConfig(
            id = "link-test",
            name = "Madara",
            baseUrl = "https://madara.example",
            details = DetailsSpec(
                chapters = ChapterListSpec(list = "li", name = "a@text", link = "a@href")
            ),
            chapter = ChapterSpec(content = "div")
        )
        val filled = KnownSites.applyOverrides(entry, base)
        assertNotNull(filled.search)
        assertTrue(filled.search!!.url.contains("post_type=wp-manga"))
        val own = base.copy(
            search = ListSpec(
                url = "https://madara.example/find?q={query}",
                results = "a", title = "@text", link = "@href"
            )
        )
        assertEquals(
            "https://madara.example/find?q={query}",
            KnownSites.applyOverrides(entry, own).search!!.url
        )
    }

    // ── A9: JS shells ───────────────────────────────────────────

    @Test
    fun `an empty javascript shell is detected`() {
        val doc = Jsoup.parse(JsShellSite.SHELL_PAGE, JsShellSite.BASE)
        assertTrue(LinkInference.isJsShell(doc))
    }

    @Test
    fun `real and sparse pages are not shells`() {
        assertFalse(
            LinkInference.isJsShell(
                Jsoup.parse(MoonSite.BOOK_PAGE, MoonSite.BOOK_URL)
            )
        )
        assertFalse(
            LinkInference.isJsShell(
                Jsoup.parse(MoonSite.SPARSE_PAGE, "https://blog.example/post")
            )
        )
        assertFalse(
            LinkInference.isJsShell(
                Jsoup.parse(NovelightSite.BOOK_PAGE, NovelightSite.BOOK_URL)
            )
        )
    }

    // ── A6 + A8: cover chain and field sanity ───────────────────

    @Test
    fun `the cover chain binds the alt-matched poster with a working spec`() {
        val doc = Jsoup.parse(NovelFullSite.BOOK_PAGE, NovelFullSite.BOOK_URL)
        val analysis = LinkInference.analyseBookPage(doc, NovelFullSite.BOOK_URL)
        assertNotNull(analysis)
        analysis!!
        assertEquals("Martial Peak", analysis.title)
        assertNotNull(analysis.coverUrl)
        assertTrue(analysis.coverUrl!!.endsWith("martial-peak.webp"))
        assertNotNull(analysis.coverSpec)
        val img = doc.selectFirst(analysis.coverSpec!!.substringBeforeLast("@"))
        assertNotNull(img)
        assertTrue(img!!.attr("src").endsWith("martial-peak.webp"))
    }

    @Test
    fun `a page with no cover yields no cover and no blank cover rule`() {
        val doc = Jsoup.parse(NovelightSite.BOOK_PAGE, NovelightSite.BOOK_URL)
        val analysis = LinkInference.analyseBookPage(doc, NovelightSite.BOOK_URL)
        assertNotNull(analysis)
        analysis!!
        assertNull(analysis.coverUrl)
        assertNull(analysis.coverSpec)
    }

    @Test
    fun `author synthesis never reads inside a carousel`() {
        val html = """
            <html><head><title>Swiper Book</title></head><body>
            <h1>Swiper Book</h1>
            <div class="swiper-container">
              <div class="swiper-slide"><span class="author">Carousel Writer</span></div>
            </div>
            <ul>
              <li><a href="/b/chapter-3">Chapter 3</a></li>
              <li><a href="/b/chapter-2">Chapter 2</a></li>
              <li><a href="/b/chapter-1">Chapter 1</a></li>
            </ul>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://swiper.example/b")
        val analysis = LinkInference.analyseBookPage(doc, "https://swiper.example/b")
        assertNotNull(analysis)
        assertNull(analysis!!.authorSpec)
    }
}
