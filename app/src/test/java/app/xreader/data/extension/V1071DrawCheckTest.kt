package app.xreader.data.extension

import app.xreader.extensionapi.SourceBook
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.0.7.1 draw-check pins: the cut-off synopsis. Sites slice
 * their meta description mid-word ("…was a n..."); Xreader
 * used to store that snippet as the book's synopsis. Now:
 * inference prefers the page's own synopsis block when the
 * meta reads truncated and the block is longer, and the
 * declarative reader heals an already-saved meta spec the
 * same way at read time. An untruncated meta still wins.
 */
class V1071DrawCheckTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val truncatedMeta =
        "When I opened my eyes, I was inside a novel. " +
            "[The Birth of a Hero] was a n..."

    private val fullSynopsis =
        "When I opened my eyes, I was inside a novel. " +
            "[The Birth of a Hero] was a novel about a world " +
            "of heroes and monsters, and I woke in the body " +
            "of a minor count's son with a talent for survival."

    private fun bookPage(meta: String, withBlock: Boolean): String = """
        <html><head>
          <title>Some Book - Example</title>
          <meta name="description" content="$meta"/>
        </head><body>
        <h1 class="book-title">Some Book</h1>
        ${if (withBlock) "<div class=\"synopsis\">$fullSynopsis</div>" else ""}
        <ul class="chapters">
          <li><a href="/book/some/chapter-3">Chapter 3</a></li>
          <li><a href="/book/some/chapter-2">Chapter 2</a></li>
          <li><a href="/book/some/chapter-1">Chapter 1</a></li>
        </ul>
        </body></html>
    """.trimIndent()

    @Test
    fun `inference prefers the synopsis block over a truncated meta`() {
        val doc = Jsoup.parse(
            bookPage(truncatedMeta, withBlock = true),
            "https://novels.example/book/some"
        )
        val analysis = LinkInference.analyseBookPage(
            doc, "https://novels.example/book/some"
        )
        assertNotNull(analysis)
        assertFalse(
            "a sliced meta must not become the description spec",
            analysis!!.descriptionSpec ==
                "meta[name=description]@content"
        )
        assertNotNull(analysis.descriptionSpec)
    }

    @Test
    fun `inference keeps an untruncated meta as the description`() {
        val doc = Jsoup.parse(
            bookPage("A complete, finished summary.", withBlock = true),
            "https://novels.example/book/some"
        )
        val analysis = LinkInference.analyseBookPage(
            doc, "https://novels.example/book/some"
        )
        assertNotNull(analysis)
        assertEquals(
            "meta[name=description]@content",
            analysis!!.descriptionSpec
        )
    }

    @Test
    fun `details heal a truncated meta from the page synopsis block`() =
        runBlocking {
            val transport = FixtureSite.transport(
                mapOf(
                    FixtureSite.BOOK_URL to bookPage(
                        truncatedMeta, withBlock = true
                    )
                )
            )
            val context = testContext(transport, tmp.newFolder())
            val source = DeclarativeSource(FixtureSite.config(), context)
            val details = source.details(
                SourceBook(id = FixtureSite.BOOK_URL, title = "?")
            )
            assertEquals(fullSynopsis, details.description)
        }

    @Test
    fun `details keep the meta when no fuller block exists`() =
        runBlocking {
            val transport = FixtureSite.transport(
                mapOf(
                    FixtureSite.BOOK_URL to bookPage(
                        truncatedMeta, withBlock = false
                    )
                )
            )
            val context = testContext(transport, tmp.newFolder())
            val source = DeclarativeSource(FixtureSite.config(), context)
            val details = source.details(
                SourceBook(id = FixtureSite.BOOK_URL, title = "?")
            )
            assertEquals(truncatedMeta, details.description)
        }
}
