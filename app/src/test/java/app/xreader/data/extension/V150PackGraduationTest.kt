package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.data.net.HttpTransport
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.0 (Part A bench + the user's shipped-set directive,
 * 2026-11-02 14:15 IST): the Kaiw-set bench sites measured
 * live from this machine. The ones that pass ALL FIVE legs
 * GRADUATE into the provider-pack go-live set
 * (packs/novels.json) so every user gets them ready-made;
 * this test is their recipe record, their generator
 * (XR_WRITE_PACK=1 rewrites the shipped file from these
 * builders) and their standing proof (the shipped file is
 * parsed by the app's own codec and every source in it is
 * driven over the captured live pages, five legs each).
 *
 * Live bench outcomes 2026-11-02 (evidence in
 * build-logs/V150-BUILD-REPORT.md):
 * - LightNovel.to — 5/5, static (pattern-completed chapter
 *   list from the book page's stated total). GRADUATED.
 * - Lunox Scans — 5/5, Madara family shape with customised
 *   catalogue cards + chapter anchors. GRADUATED.
 * - MeioNovel (meionovels.com) — 5/5, stock Madara.
 *   GRADUATED.
 * - atsu.moe — catalogue/search/book proven, chapter list
 *   only exists as a runtime XHR (opaque chapter ids);
 *   read endpoint + payload shape known. NOT graduated
 *   (chapters leg).
 * - KuuPress — book/chapters/text proven from the SSR
 *   payload; search is a client-side API call, not
 *   statically provable. NOT graduated (search leg).
 * - LnMTL — catalogue/book/chapters/text proven (embedded
 *   paginated chapter JSON); search exists only as a
 *   client-side index file our listing model cannot
 *   filter. NOT graduated (search leg).
 * - LightNovelTranslations — catalogue/search/book/text
 *   proven; the full chapter list sits behind a JS
 *   progressive TOC + volume tabs (static windows stop at
 *   the first 50). NOT graduated (chapters leg).
 */
class V150PackGraduationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── The graduated recipes ───────────────────────────────

    private fun lightNovelTo(): DeclarativeConfig =
        DeclarativeConfig(
            id = "pack-lightnovelto",
            name = "LightNovel.to",
            baseUrl = "https://lightnovel.to",
            popular = ListSpec(
                url = "https://lightnovel.to/",
                results = "a.ln-ncard",
                title = "img@alt",
                link = "@href",
                cover = "img@src"
            ),
            search = ListSpec(
                url = "https://lightnovel.to/search?q={query}",
                results = "a.ln-ncard",
                title = "img@alt",
                link = "@href",
                cover = "img@src"
            ),
            details = DetailsSpec(
                title = "h1.ln-dtitle",
                description = "p.ln-lead",
                cover = "div.ln-dcv img@src",
                chapters = ChapterListSpec(
                    list = "div.ln-crow",
                    name = "span.ln-ct",
                    link = "a@href",
                    reverse = true,
                    urlPattern = "{bookUrl}/chapter-{n}",
                    totalCountPattern = "with ([\\d,]+) chapters"
                )
            ),
            chapter = ChapterSpec(content = "div.ln-prose"),
            sampleBookUrl = "https://lightnovel.to/novel/tycoon-war-god"
        )

    private fun lunoxScans(): DeclarativeConfig {
        val family = BundledFamilies.madara(
            id = "pack-lunoxscans",
            name = "Lunox Scans",
            base = "https://lunoxscans.com",
            popularUrl = "https://lunoxscans.com/",
            coverSelector = "img@src",
            searchResults = "div.page-item-detail",
            searchTitle = "div.post-title a",
            detailsTitle = "h1",
            detailsDescription =
                "div.description-summary div.summary__content",
            chaptersViaAjaxPost = true
        )
        // The Lunox theme customises two shapes the stock
        // family gets wrong: homepage cards are horizontal
        // cards (thumb anchor carries title + link), and the
        // ajax chapter list is anchors, not wp-manga <li>s.
        return family.copy(
            popular = ListSpec(
                url = "https://lunoxscans.com/",
                results = "div.page-item-detail",
                title = "div.novel-card-thumb a@title",
                link = "div.novel-card-thumb a@href",
                cover = "div.novel-card-thumb img@src"
            ),
            details = family.details.copy(
                chapters = family.details.chapters.copy(
                    list = "a.lunox-chapter-item",
                    name = "span.lunox-chapter-name",
                    link = "@href"
                )
            ),
            sampleBookUrl =
                "https://lunoxscans.com/series/" +
                    "thats-how-i-became-a-villainess/"
        )
    }

    private fun meioNovel(): DeclarativeConfig =
        BundledFamilies.madara(
            id = "pack-meionovel",
            name = "MeioNovel",
            base = "https://meionovels.com",
            popularUrl = "https://meionovels.com/",
            coverSelector = "img@src",
            // Meio's search panel wraps every result in one
            // c-tabs-item; the per-book row is the
            // c-tabs-item__content inside it.
            searchResults = "div.c-tabs-item__content",
            searchTitle = "div.post-title a",
            detailsTitle = "h1",
            detailsDescription =
                "div.description-summary div.summary__content",
            chaptersViaAjaxPost = true
        ).copy(
            lang = "id",
            sampleBookUrl =
                "https://meionovels.com/novel/" +
                    "aku-menjadi-immortal-di-alam-fana/"
        )

    private fun builtPack(): ProviderPack = ProviderPack(
        id = "novels",
        version = 2,
        date = "2026-11-02",
        sources = listOf(
            PackSource(
                id = "pack-lightnovelto",
                name = "LightNovel.to",
                lang = "en",
                baseUrl = "https://lightnovel.to",
                config = lightNovelTo()
            ),
            PackSource(
                id = "pack-lunoxscans",
                name = "Lunox Scans",
                lang = "en",
                baseUrl = "https://lunoxscans.com",
                config = lunoxScans()
            ),
            PackSource(
                id = "pack-meionovel",
                name = "MeioNovel",
                lang = "id",
                baseUrl = "https://meionovels.com",
                config = meioNovel()
            )
        )
    )

    private fun packFile(): File {
        // Unit tests run with the app module as the working
        // directory; the pack file lives at the project root.
        val fromModule = File("../packs/novels.json")
        if (fromModule.exists()) return fromModule
        return File("packs/novels.json")
    }

    private fun fixture(name: String): String {
        val stream = javaClass.getResourceAsStream("/bench/$name")
        assertNotNull("fixture /bench/$name missing", stream)
        return stream!!.bufferedReader().readText()
    }

    // ── Generator + shipped-file integrity ─────────────────

    @Test
    fun `the shipped pack parses and matches the graduated recipes`() {
        if (System.getenv("XR_WRITE_PACK") == "1") {
            val file = packFile()
            file.parentFile?.mkdirs()
            // encodeDefaults: the codec's own parse gate
            // requires the format/formatVersion fields the
            // default-skipping encoder would omit (the
            // published file spells every field out).
            val packJson = kotlinx.serialization.json.Json {
                encodeDefaults = true
                prettyPrint = true
            }
            file.writeText(
                packJson.encodeToString(
                    ProviderPack.serializer(), builtPack()
                )
            )
        }
        val raw = packFile().readText()
        val parsed = ProviderPackCodec.parse(raw)
        if (parsed !is AppResult.Ok) {
            fail("shipped pack rejected: $parsed")
        }
        val pack = (parsed as AppResult.Ok).value
        assertEquals(builtPack(), pack)
        assertEquals(
            listOf(
                "pack-lightnovelto",
                "pack-lunoxscans",
                "pack-meionovel"
            ),
            pack.sources.map { it.id }
        )
    }

    // ── Five-leg drives over the captured live pages ───────

    private fun sourceFor(
        config: DeclarativeConfig,
        transport: FakeTransport,
        host: String
    ): DeclarativeSource = DeclarativeSource(
        config,
        testContext(
            transport, tmp.newFolder(),
            ExtensionPermissions(domains = listOf(host))
        )
    )

    @Test
    fun `lightnovel to walks all five legs`() = runTest {
        val transport = FakeTransport { request ->
            val url = request.url
            when {
                url.contains("/chapter-") ->
                    FakeTransport.ok(fixture("lnto-chapter.html"))
                url.contains("/search") ->
                    FakeTransport.ok(fixture("lnto-search.html"))
                url == "https://lightnovel.to/novel/tycoon-war-god" ->
                    FakeTransport.ok(fixture("lnto-book.html"))
                else ->
                    FakeTransport.ok(fixture("lnto-home.html"))
            }
        }
        val source = sourceFor(
            lightNovelTo(), transport, "lightnovel.to"
        )
        val popular = source.popular(1)
        assertTrue(popular.books.size >= 3)
        assertTrue(popular.books.first().title.isNotBlank())

        val found = source.search("sword", 1)
        assertTrue(found.books.size >= 3)

        val book = popular.books.first()
        val detailed = source.details(book)
        assertTrue(detailed.title.contains("Tycoon"))

        val chapters = source.chapters(book)
        // Pattern completion from the stated total (1,375).
        assertTrue(
            "expected the completed list, got ${chapters.size}",
            chapters.size >= 1000
        )
        val content = source.chapterContent(chapters.first())
        val plain = org.jsoup.Jsoup.parseBodyFragment(content.html)
            .text()
        assertTrue(plain.length > 400)
    }

    @Test
    fun `lunox scans walks all five legs`() = runTest {
        val bookUrl = "https://lunoxscans.com/series/" +
            "thats-how-i-became-a-villainess/"
        val transport = FakeTransport { request ->
            val url = request.url
            when {
                url.contains("ajax/chapters") ->
                    FakeTransport.ok(fixture("lunox-ajax.html"))
                url.contains("/chapter-") ->
                    FakeTransport.ok(fixture("lunox-chapter.html"))
                url.contains("?s=") ->
                    FakeTransport.ok(fixture("lunox-search.html"))
                url == bookUrl ->
                    FakeTransport.ok(fixture("lunox-book.html"))
                else ->
                    FakeTransport.ok(fixture("lunox-home.html"))
            }
        }
        val config = lunoxScans()
        val source = sourceFor(config, transport, "lunoxscans.com")
        val popular = source.popular(1)
        assertTrue(popular.books.size >= 3)
        assertTrue(popular.books.first().title.isNotBlank())

        val found = source.search("villainess", 1)
        assertTrue(found.books.size >= 3)

        val book = app.xreader.extensionapi.SourceBook(
            id = bookUrl, title = "placeholder"
        )
        val detailed = source.details(book)
        assertTrue(detailed.title.contains("Villainess"))

        val chapters = source.chapters(book)
        assertTrue(
            "expected the ajax chapter list, got ${chapters.size}",
            chapters.size >= 10
        )
        val content = source.chapterContent(chapters.first())
        val plain = org.jsoup.Jsoup.parseBodyFragment(content.html)
            .text()
        assertTrue(plain.length > 400)
    }

    @Test
    fun `meionovel walks all five legs`() = runTest {
        val bookUrl = "https://meionovels.com/novel/" +
            "aku-menjadi-immortal-di-alam-fana/"
        val transport = FakeTransport { request ->
            val url = request.url
            when {
                url.contains("ajax/chapters") ->
                    FakeTransport.ok(fixture("meio-ajax.html"))
                url.contains("/chapter-") ->
                    FakeTransport.ok(fixture("meio-chapter.html"))
                url.contains("?s=") ->
                    FakeTransport.ok(fixture("meio-search.html"))
                url == bookUrl ->
                    FakeTransport.ok(fixture("meio-book.html"))
                else ->
                    FakeTransport.ok(fixture("meio-home.html"))
            }
        }
        val source = sourceFor(meioNovel(), transport, "meionovels.com")
        val popular = source.popular(1)
        assertTrue(popular.books.size >= 3)
        assertTrue(popular.books.first().title.isNotBlank())

        val found = source.search("immortal", 1)
        assertTrue(found.books.size >= 3)

        val book = app.xreader.extensionapi.SourceBook(
            id = bookUrl, title = "placeholder"
        )
        val detailed = source.details(book)
        assertTrue(detailed.title.contains("Immortal"))

        val chapters = source.chapters(book)
        assertTrue(
            "expected the ajax chapter list, got ${chapters.size}",
            chapters.size >= 10
        )
        val content = source.chapterContent(chapters.first())
        val plain = org.jsoup.Jsoup.parseBodyFragment(content.html)
            .text()
        assertTrue(plain.length > 400)
    }
}
