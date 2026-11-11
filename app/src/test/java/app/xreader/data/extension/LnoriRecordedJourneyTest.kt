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
 * v1.5.2 (FIX-14): the shipped Lnori default, end to end on
 * the RECORDED live shapes (captured from lnori.com on
 * 2026-11-03; byte counts match the probe's evidence in
 * build-logs/V152-LNORI-PROBE.md). All five legs through the
 * production DeclarativeSource:
 *  catalogue (884 rows off one /library fetch) · book page ·
 *  volume list (10 / 28, reading order) · volume text (the
 *  whole 85k-word volume served as ONE chapter — QuickNovel's
 *  shape, owner-ruled 2026-11-03: the volume page's
 *  main > article > section HTML whole, <picture> unwrapped
 *  to <img>) · search (the localFilter mechanism — the site's
 *  own client-side semantics).
 */
class LnoriRecordedJourneyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) {
            "missing test resource $name"
        }.readBytes().toString(Charsets.UTF_8)

    private class CountingTransport(
        private val routes: Map<String, String>
    ) {
        // v1.5.2 (round-7 programme item 2): synchronised — the
        // list is appended from the transport's calling threads
        // and read on the test thread; the unsynchronised list
        // was a named suspect in the volume-text flap.
        val requests: MutableList<String> =
            java.util.Collections.synchronizedList(mutableListOf<String>())
        val transport = FakeTransport { request ->
            requests += request.url
            val body = routes.entries
                .firstOrNull { request.url.startsWith(it.key) }?.value
            if (body != null) {
                AppResult.Ok(FakeTransport.response(200, body))
            } else {
                AppResult.Ok(FakeTransport.response(404, "no recorded shape"))
            }
        }
    }

    private fun rig(): Pair<DeclarativeSource, CountingTransport> {
        val counting = CountingTransport(
            mapOf(
                "https://lnori.com/library" to resource("lnori/library.html"),
                "https://lnori.com/series/10041/ishura" to
                    resource("lnori/series-ishura.html"),
                "https://lnori.com/series/3343/" to
                    resource("lnori/series-rezero.html"),
                "https://lnori.com/book/27387/" to
                    resource("lnori/book-ishura-vol1.html"),
                "https://lnori.com/book/12040/" to
                    resource("lnori/book-rezero-vol1.html")
            )
        )
        val config = BundledDefaults.configs().first { it.id == "bundled-lnori" }
        val context = testContext(
            transport = counting.transport,
            filesDir = tmp.newFolder(),
            permissions = ExtensionPermissions(
                domains = listOf("lnori.com", "cdn.lnori.com", "img.lnori.com")
            )
        )
        return DeclarativeSource(config, context) to counting
    }

    private fun words(text: String): Int =
        text.split(Regex("\\s+")).count { it.isNotBlank() }

    @Test
    fun `catalogue - 884 rows off one library page, with authors`() {
        val (source, _) = rig()
        val page = source.popular(1)
        assertEquals(884, page.books.size)
        assertTrue(!page.hasNextPage)
        val ishura = page.books.firstOrNull { it.title == "Ishura" }
            ?: throw AssertionError("Ishura missing from the catalogue")
        assertEquals("Keiso", ishura.author)
        assertTrue(ishura.id.endsWith("/series/10041/ishura"))
        assertTrue(
            "covers ride the cdn host: ${ishura.coverUrl}",
            ishura.coverUrl?.contains("cdn.lnori.com") == true
        )
    }

    @Test
    fun `book page and volume list - ishura 10 volumes in reading order`() {
        val (source, _) = rig()
        val book = SourceBook(
            id = "https://lnori.com/series/10041/ishura", title = "Ishura"
        )
        val details = source.details(book)
        assertEquals("Ishura", details.title)
        assertEquals("Keiso", details.author)
        assertTrue(!details.description.isNullOrBlank())
        val chapters = source.chapters(book)
        assertEquals(10, chapters.size)
        assertEquals(
            (1..10).map { "Volume $it" },
            chapters.map { it.name }
        )
        assertTrue(
            "first volume id: ${chapters.first().id}",
            chapters.first().id.endsWith(
                "/book/27387/ishura-the-new-demon-king-war-vol-1"
            )
        )
        assertTrue(
            "last volume id: ${chapters.last().id}",
            chapters.last().id.endsWith(
                "/book/44839/ishura-vol-10-the-martyr-walks-alone"
            )
        )
    }

    @Test
    fun `book page and volume list - rezero 28 volumes`() {
        val (source, _) = rig()
        val book = SourceBook(
            id = "https://lnori.com/series/3343/" +
                "re-zero-starting-life-in-another-world",
            title = "Re:ZERO"
        )
        val chapters = source.chapters(book)
        assertEquals(28, chapters.size)
        assertEquals("Volume 1", chapters.first().name)
        assertEquals("Volume 28", chapters.last().name)
    }

    @Test
    fun `volume text - the whole volume served as one chapter, quicknovel shape`() {
        val (source, _) = rig()
        val book = SourceBook(
            id = "https://lnori.com/series/10041/ishura", title = "Ishura"
        )
        val chapter = source.chapters(book).first()
        val content = source.chapterContent(chapter)
        val html = content.html ?: ""
        val text = Jsoup.parseBodyFragment(html).text()
        // The probe's live count: 85,589 words for this volume.
        assertTrue(
            "volume words ${words(text)} — the whole volume must arrive",
            words(text) > 80_000
        )
        // ONE chapter body carrying the volume's own chapter
        // headings in order (no splitting — the ruled shape).
        val headings = Jsoup.parseBodyFragment(html)
            .select("h2.chapter-title").map { it.text().trim() }
        assertTrue(
            "the volume's chapter headings ride the one body: $headings",
            headings.contains("Chapter 1 Soujirou the Willow-Sword") &&
                headings.contains("Chapter 23 The Imperial Competition") &&
                headings.contains("Afterword")
        )
        assertTrue(text.contains("FIRST VERSE: OTHERWORLD OF CARNAGE"))
        // <picture> unwrapped to plain <img> (the recorded
        // volume page carries 82 picture elements).
        assertTrue(
            "no picture wrappers survive into the served body",
            !html.contains("<picture")
        )
        assertTrue(
            "the illustrations ride as plain img tags",
            html.contains("<img") && html.contains("img.lnori.com")
        )
    }

    @Test
    fun `search - local filter semantics over the catalogue, one fetch`() {
        val (source, counting) = rig()
        // Title match (the probe's replicated hits).
        val ishura = source.search("ishura", 1)
        assertEquals(listOf("Ishura"), ishura.books.map { it.title })
        // Author match.
        val keiso = source.search("keiso", 1)
        assertTrue(
            "author search: ${keiso.books.map { it.title }}",
            keiso.books.isNotEmpty() &&
                keiso.books.all { it.author == "Keiso" }
        )
        // Case-insensitive.
        assertEquals(1, source.search("ISHURA", 1).books.size)
        // The probe's multi-hit title query.
        assertEquals(3, source.search("re:zero", 1).books.size)
        // Nonsense matches nothing — never the whole catalogue.
        assertTrue(source.search("zxqwvkj", 1).books.isEmpty())
        // Empty query = the site filter with an empty string:
        // the whole catalogue.
        assertEquals(884, source.search("", 1).books.size)
        // All of it over ONE catalogue fetch (memoised).
        assertEquals(
            "library fetches: ${counting.requests}",
            1, counting.requests.count { it.contains("/library") }
        )
    }
}
