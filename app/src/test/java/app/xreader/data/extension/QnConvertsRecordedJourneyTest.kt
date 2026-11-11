package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.extensionapi.SourceBook
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.2 (FIX-16): the QuickNovel-set converts shipped in
 * this build — ScrollersPub, ReadHive, WuxiaClick and the
 * host-corrected FaqWiki — each driven through the
 * production DeclarativeSource on RECORDED live shapes
 * (captured 2026-11-03; recipes and live evidence in
 * build-logs/V152-QN-CONVERSION-PROBE.md). All five legs
 * per provider: catalogue · search · book · chapters ·
 * text. The live twins live in QnConvertsLiveSmokeTest.
 */
class QnConvertsRecordedJourneyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) {
            "missing test resource $name"
        }.readBytes().toString(Charsets.UTF_8)

    private fun sourceFor(
        configId: String,
        transport: FakeTransport,
        domains: List<String>
    ): DeclarativeSource {
        val config = BundledDefaults.configs().first { it.id == configId }
        val context = testContext(
            transport = transport,
            filesDir = tmp.newFolder(),
            permissions = ExtensionPermissions(domains = domains)
        )
        return DeclarativeSource(config, context)
    }

    private fun wordsOf(html: String): Int =
        Jsoup.parseBodyFragment(html).text()
            .split(Regex("\\s+")).count { it.isNotBlank() }

    // ── ScrollersPub ──────────────────────────────────────

    @Test
    fun `scrollerspub - five legs over the JSON API`() = runBlocking {
        val transport = FakeTransport { request ->
            val url = request.url
            val body = when {
                url.contains("/chapters?") -> {
                    val off = Regex("offset=(\\d+)").find(url)
                        ?.groupValues?.get(1) ?: "0"
                    resource("scrollerspub/slices/chapters-$off.json")
                }
                url.contains("/api/chapter/") ->
                    resource("scrollerspub/text.json")
                url.contains("search=lord") ->
                    resource("scrollerspub/search.json")
                url.contains("/api/novels?") ->
                    resource("scrollerspub/catalogue.json")
                url.contains("/api/novel/") ->
                    resource("scrollerspub/book.json")
                url.contains("/read/") ->
                    "<html><head></head><body></body></html>"
                else -> null
            }
            if (body != null) {
                AppResult.Ok(FakeTransport.response(200, body))
            } else {
                AppResult.Ok(FakeTransport.response(404, "no recorded shape"))
            }
        }
        val source = sourceFor(
            "bundled-scrollerspub", transport,
            listOf("www.scrollerspub.com")
        )
        // Catalogue.
        val page = source.popular(1)
        assertEquals(24, page.books.size)
        val book = page.books.first()
        assertEquals(
            "Qi Cultivation Starting From The Repair Panel", book.title
        )
        assertTrue(!book.author.isNullOrBlank())
        // Search. (Identity asserted by the API id: the
        // app's TitleCleaner cuts every listing title at
        // " - " — the long-standing site-suffix rule — so
        // the recorded first hit "Dr Blessed - In Mha With
        // Lord Of Mysteries System" displays as "Dr Blessed"
        // here as it would for any source.)
        val hits = source.search("lord", 1)
        assertEquals(24, hits.books.size)
        assertTrue(
            "first hit: ${hits.books.first().id}",
            hits.books.first().id.endsWith(
                "9b49d83b-9c7b-4710-ae8f-5073e1f3b222"
            )
        )
        assertTrue(hits.books.first().title.startsWith("Dr Blessed"))
        // Book + chapters (offset walk over the recorded set).
        val details = source.details(book)
        assertEquals(book.title, details.title)
        val chapters = source.chapters(book)
        assertEquals(1026, chapters.size)
        assertEquals("Chapter 1 - 1: Servant", chapters.first().name)
        // Text (the ajax read road).
        val text = source.chapterContent(chapters.first())
        assertTrue(
            "served text opens with the recorded first paragraph",
            text.html.contains(
                "A chill swept over him, and Chu Zheng slowly opened his eyes"
            )
        )
        Unit
    }

    // ── ReadHive ──────────────────────────────────────────

    @Test
    fun `readhive - five legs`() = runBlocking {
        val routes = linkedMapOf(
            "https://readhive.org/ajax" to "readhive/search.json",
            "https://readhive.org/series/110/1" to "readhive/text.html",
            "https://readhive.org/series/110/" to "readhive/book.html",
            "https://readhive.org/" to "readhive/home.html"
        )
        val transport = FakeTransport { request ->
            val hit = routes.entries
                .firstOrNull { request.url.startsWith(it.key) }
            if (hit != null) {
                AppResult.Ok(FakeTransport.response(200, resource(hit.value)))
            } else {
                AppResult.Ok(FakeTransport.response(404, "no recorded shape"))
            }
        }
        val source = sourceFor(
            "bundled-readhive", transport, listOf("readhive.org")
        )
        // Catalogue.
        val page = source.popular(1)
        assertEquals(33, page.books.size)
        assertEquals(
            "I’m Debuting With The Bias I Killed",
            page.books.first().title
        )
        // Search (the POST /ajax contract).
        val hits = source.search("reincarnation", 1)
        assertEquals(8, hits.books.size)
        assertEquals(
            "Waiting For Your Reincarnation", hits.books.first().title
        )
        // Book + chapters.
        val book = SourceBook(
            id = "https://readhive.org/series/110/",
            title = "I need sponsorship"
        )
        val details = source.details(book)
        assertEquals("I need sponsorship", details.title)
        val chapters = source.chapters(book)
        assertEquals(25, chapters.size)
        assertTrue(
            "oldest chapter first after the reverse: ${chapters.first().name}",
            chapters.first().name.contains("Chapter 1")
        )
        // Text.
        val text = source.chapterContent(chapters.first())
        assertTrue(
            "prose words: ${wordsOf(text.html)}",
            wordsOf(text.html) > 400
        )
        Unit
    }

    // ── WuxiaClick ────────────────────────────────────────

    @Test
    fun `wuxiaclick - five legs with the ranged toc walk`() = runBlocking {
        val transport = FakeTransport { request ->
            val url = request.url
            val body = when {
                url.contains("/search/") ->
                    resource("wuxiaclick/search.html")
                url.contains("/chapter/") ->
                    resource("wuxiaclick/text.html")
                url.contains("?page=") -> {
                    val pg = Regex("page=(\\d+)").find(url)
                        ?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    when {
                        pg <= 1 -> resource("wuxiaclick/book.html")
                        pg == 2 -> resource("wuxiaclick/book-p2.html")
                        // Recorded-shape mapping: any later
                        // range serves the recorded SHORT
                        // final page (95 rows, chapters
                        // 2001-2095), which is the walk's
                        // natural stop. The full 2,095-row
                        // enumeration is the live smoke's leg.
                        else -> resource("wuxiaclick/book-p21.html")
                    }
                }
                url.contains("/novel/renegade-immortal") ->
                    resource("wuxiaclick/book.html")
                url == "https://wuxia.click/" ->
                    resource("wuxiaclick/home.html")
                else -> null
            }
            if (body != null) {
                AppResult.Ok(FakeTransport.response(200, body))
            } else {
                AppResult.Ok(FakeTransport.response(404, "no recorded shape"))
            }
        }
        val source = sourceFor(
            "bundled-wuxiaclick", transport, listOf("wuxia.click")
        )
        // Catalogue.
        val page = source.popular(1)
        assertEquals(12, page.books.size)
        // Search.
        val hits = source.search("renegade", 1)
        assertEquals(1, hits.books.size)
        assertEquals("Renegade Immortal", hits.books.first().title)
        // Book + chapters: the book page's own range carries
        // clean embedded-JSON names; later ranges ride the
        // ranged walk.
        val book = hits.books.first()
        val chapters = source.chapters(book)
        // 100 (book page) + 100 (range 2) + 95 (the recorded
        // short final range, served for any later range) —
        // the walk dedupes by URL and stops on the short page.
        assertEquals(295, chapters.size)
        assertTrue(
            "first chapter id: ${chapters.first().id}",
            chapters.first().id.endsWith("/chapter/renegade-immortal-1")
        )
        assertTrue(
            "first chapter name (the site's glued form): " +
                chapters.first().name,
            chapters.first().name.startsWith("Chapter 1, Leaving Home")
        )
        assertTrue(
            "range 2 reached: ${chapters[100].id}",
            chapters[100].id.endsWith("-101")
        )
        assertTrue(
            "short final range reached: ${chapters.last().id}",
            chapters.last().id.endsWith("-2095")
        )
        // Text.
        val text = source.chapterContent(chapters.first())
        assertTrue(
            text.html.contains(
                "Tie Zhu was sitting on the side of a little road in the village"
            )
        )
        Unit
    }

    // ── FaqWiki (corrected host) ──────────────────────────

    @Test
    fun `faqwiki - five legs on the corrected host`() = runBlocking {
        val routes = linkedMapOf(
            "https://faqwiki.xyz/?s=" to "faqwiki/search.html",
            "https://faqwiki.xyz/reincarnation-of-the-fist-king-chapter-1/" to
                "faqwiki/text.html",
            "https://faqwiki.xyz/reincarnation-of-the-fist-king/" to
                "faqwiki/book.html",
            "https://faqwiki.xyz/" to "faqwiki/home.html"
        )
        val transport = FakeTransport { request ->
            val hit = routes.entries
                .firstOrNull { request.url.startsWith(it.key) }
            if (hit != null) {
                AppResult.Ok(FakeTransport.response(200, resource(hit.value)))
            } else {
                AppResult.Ok(FakeTransport.response(404, "no recorded shape"))
            }
        }
        val source = sourceFor(
            "bundled-faqwiki", transport, listOf("faqwiki.xyz")
        )
        // Catalogue.
        val page = source.popular(1)
        assertEquals(42, page.books.size)
        // Search.
        val hits = source.search("fist", 1)
        assertTrue(
            "search finds the drive book: ${hits.books.map { it.title }}",
            hits.books.any {
                it.title.startsWith("Reincarnation of the Fist King")
            }
        )
        // Book + chapters (exactly the stated 200).
        val book = SourceBook(
            id = "https://faqwiki.xyz/reincarnation-of-the-fist-king/",
            title = "Reincarnation of the Fist King"
        )
        val details = source.details(book)
        assertTrue(
            "book h1 (site-suffixed form): ${details.title}",
            details.title.startsWith("Reincarnation of the Fist King")
        )
        val chapters = source.chapters(book)
        assertEquals(200, chapters.size)
        assertTrue(chapters.first().name.contains("Chapter 1"))
        // Text.
        val text = source.chapterContent(chapters.first())
        assertTrue(
            "prose words: ${wordsOf(text.html)}",
            wordsOf(text.html) > 400
        )
        Unit
    }
}
