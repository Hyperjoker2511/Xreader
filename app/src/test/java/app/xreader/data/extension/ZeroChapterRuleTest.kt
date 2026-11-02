package app.xreader.data.extension

import app.xreader.data.FakeTransport
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceException
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.4.3 — the ZERO-CHAPTER RULE (the field diagnosis's
 * Asura case): a book page that visibly links to chapters
 * but whose learned rule matches none of them is a FAILED
 * read, never an empty list stored as complete. The living
 * example was a site moving its series URLs (/series/ →
 * /comics/): the old page kept chapter-shaped links the rule
 * couldn't match, and the book silently showed 0 chapters.
 * A page with NO chapter-shaped links keeps the honest
 * empty list — a book with no chapters yet is not an error.
 * All fixtures are synthetic.
 */
class ZeroChapterRuleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val bookUrl = "https://novels.example/comics/blade"

    private fun sourceServing(page: String): DeclarativeSource {
        val transport = FakeTransport { FakeTransport.ok(page) }
        return DeclarativeSource(
            FixtureSite.config(),
            testContext(transport, tmp.newFolder())
        )
    }

    // The moved-series page: chapter anchors under the book's
    // own URL, in a shape the config's `ul.chapters > li`
    // rule does not match (no list markup at all).
    private val movedPage = """
        <html><head><title>Blade</title></head><body>
        <h1 class="book-title">Blade</h1>
        <div class="chapters">
          <a href="/comics/blade/chapter-1">Chapter 1</a>
          <a href="/comics/blade/chapter-2">Chapter 2</a>
        </div>
        </body></html>
    """.trimIndent()

    // A genuinely chapterless page: navigation only, nothing
    // chapter-shaped below the book's URL.
    private val emptyPage = """
        <html><head><title>Blade</title></head><body>
        <h1 class="book-title">Blade</h1>
        <nav>
          <a href="/latest-chapters">Latest chapters</a>
          <a href="/comics/other-book">Another book</a>
        </nav>
        <p>No chapters have been posted yet.</p>
        </body></html>
    """.trimIndent()

    @Test
    fun `chapter links below the book url are chapter shaped`() {
        val source = sourceServing(movedPage)
        val doc = Jsoup.parse(movedPage, bookUrl)
        assertTrue(source.pageHasChapterShapedLinks(doc, bookUrl))
    }

    @Test
    fun `nav widgets and other books links are not chapter shaped`() {
        val source = sourceServing(emptyPage)
        val doc = Jsoup.parse(emptyPage, bookUrl)
        // "/latest-chapters" mentions chapters but does not
        // live below THIS book's URL; the other book's link
        // doesn't either.
        assertFalse(source.pageHasChapterShapedLinks(doc, bookUrl))
    }

    @Test
    fun `zero parsed chapters on a chapter linking page is a failure`() {
        val source = sourceServing(movedPage)
        try {
            source.chapters(SourceBook(id = bookUrl, title = "Blade"))
            fail("an empty parse of a chapter-linking page must throw")
        } catch (expected: SourceException) {
            assertTrue(
                expected.message!!.contains("chapter list couldn't be read")
            )
        }
    }

    @Test
    fun `zero chapters on a page without chapter links stays an honest empty list`() {
        val source = sourceServing(emptyPage)
        val chapters = source.chapters(
            SourceBook(id = bookUrl, title = "Blade")
        )
        assertEquals(emptyList<Any>(), chapters)
    }

    @Test
    fun `a matching chapter list still parses normally`() {
        // The fixture site's own book page: the rule matches,
        // the guard stays out of the way.
        val source = DeclarativeSource(
            FixtureSite.config(),
            testContext(FixtureSite.transport(), tmp.newFolder())
        )
        val chapters = source.chapters(
            SourceBook(id = FixtureSite.BOOK_URL, title = "Sword Saga")
        )
        assertEquals(3, chapters.size)
    }
}
