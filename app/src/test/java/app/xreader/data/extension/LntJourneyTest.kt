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
 * v1.5.2 (FIX-17): Light Novel Translations on the PANELLED
 * chapter-list mode — driven through the production
 * DeclarativeSource on recorded live SHAPES (recipe and live
 * evidence: build-logs/V152-QN-CONVERSION-PROBE.md §5a). LNT
 * shape: 3 panels, summed counts, first/last panel
 * provenance, locked rows → the locked detection. (NovLove was
 * removed from v1.5.2 by owner ruling — see
 * build-logs/V152-NOVLOVE-BANKED.md.)
 */
class LntJourneyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) {
            "missing test resource $name"
        }.readBytes().toString(Charsets.UTF_8)

    private fun wordsOf(html: String): Int =
        Jsoup.parseBodyFragment(html).text()
            .split(Regex("\\s+")).count { it.isNotBlank() }

    // ── Light Novel Translations ──────────────────────────

    private fun lntRig(requests: MutableList<String>): DeclarativeSource {
        val routes = linkedMapOf(
            "https://lightnovelstranslations.com/read/re-master-magic/?tab=table_contents&lnt_volume=11" to "lnt/panel-11.html",
            "https://lightnovelstranslations.com/read/re-master-magic/?tab=table_contents&lnt_volume=12" to "lnt/panel-12.html",
            "https://lightnovelstranslations.com/read/re-master-magic/?tab=table_contents&lnt_volume=13" to "lnt/panel-13.html",
            "https://lightnovelstranslations.com/read/re-master-magic/" to "lnt/book-multi.html",
            "https://lightnovelstranslations.com/read/the-green-witch/chapter-0/" to "lnt/text.html",
            "https://lightnovelstranslations.com/read/the-green-witch/" to "lnt/book-single.html",
            "https://lightnovelstranslations.com/browse/?field-search=" to "lnt/search.html",
            "https://lightnovelstranslations.com/browse/" to "lnt/browse.html"
        )
        val transport = FakeTransport { request ->
            requests += request.url
            val hit = routes.entries.firstOrNull { request.url.startsWith(it.key) }
            if (hit != null) {
                AppResult.Ok(FakeTransport.response(200, resource(hit.value)))
            } else {
                AppResult.Ok(FakeTransport.response(404, "no recorded shape"))
            }
        }
        val config = BundledDefaults.configs().first { it.id == "bundled-lnt" }
        val context = testContext(
            transport = transport,
            filesDir = tmp.newFolder(),
            permissions = ExtensionPermissions(
                domains = listOf("lightnovelstranslations.com")
            )
        )
        return DeclarativeSource(config, context)
    }

    @Test
    fun `lnt - five legs, panelled chapters summed across three panels`() = runBlocking {
        val requests = mutableListOf<String>()
        val source = lntRig(requests)
        // Catalogue + search.
        val page = source.popular(1)
        assertEquals(2, page.books.size)
        assertEquals("Re: Master Magic", page.books.first().title)
        val hits = source.search("green witch", 1)
        assertEquals(1, hits.books.size)
        assertEquals("The Green Witch", hits.books.first().title)
        // Book (title rides the listing row; description from meta).
        val book = page.books.first()
        val details = source.details(book)
        assertEquals("Re: Master Magic", details.title)
        assertTrue(
            "description: ${details.description}",
            details.description?.contains("mage returns") == true
        )
        // Chapters: 2 + 3 + 1 across the three panels, in panel order.
        val chapters = source.chapters(book)
        assertEquals(6, chapters.size)
        assertTrue(
            "first chapter is panel 1's first row: ${chapters.first().id}",
            chapters.first().id.endsWith("/intro-to-magic/")
        )
        assertTrue(
            "last chapter is panel 3's row: ${chapters.last().id}",
            chapters.last().id.endsWith("/chapter-5/")
        )
        assertTrue(
            "every panel was fetched: $requests",
            listOf("lnt_volume=11", "lnt_volume=12", "lnt_volume=13")
                .all { marker -> requests.any { it.contains(marker) } }
        )
        // Text (unlocked/completed book's chapter serves whole).
        val text = source.chapterContent(
            chapters.first().copy(id = "https://lightnovelstranslations.com/read/the-green-witch/chapter-0/")
        )
        assertTrue("prose words: ${wordsOf(text.html)}", wordsOf(text.html) > 100)
        Unit
    }

    @Test
    fun `lnt - locked rows surface through the locked detection`() = runBlocking {
        val source = lntRig(mutableListOf())
        val book = SourceBook(
            id = "https://lightnovelstranslations.com/read/the-green-witch/",
            title = "The Green Witch"
        )
        val chapters = source.chapters(book)
        assertEquals(2, chapters.size)
        val locked = source.lockedChapterIds(book)
        assertEquals(
            setOf("https://lightnovelstranslations.com/read/the-green-witch/chapter-1/"),
            locked
        )
        Unit
    }



}
