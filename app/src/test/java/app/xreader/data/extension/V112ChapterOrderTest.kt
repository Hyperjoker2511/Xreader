package app.xreader.data.extension

import app.xreader.data.FakeTransport
import app.xreader.domain.reader.PageLocator
import app.xreader.extensionapi.SourceBook
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.1.2 (expanded): the stored chapter ORDER is the reader's
 * only truth — the reconciler stores the fetched list's
 * positions verbatim as Chapter.index, and saved progress,
 * the Library hero and the Continue pill all read that
 * position back.
 *
 * The device failure (2026-10-31, a Live-source manhwa): the
 * fetched list arrived newest-first while the derived config
 * said reverse=false (the analysis-time newestFirst heuristic
 * — first vs last cluster item — missed), so chapter 1 was
 * stored at index 90 of 92. The user read half of chapter 1
 * and the app saved progress "Chapter 91 of 92, 97%".
 *
 * These tests pin the invariant at the finishing choke point
 * every declarative list passes through: the finished list
 * must never be strongly descending in its chapter numbers,
 * whichever way the config's reverse flag was set — and a
 * list that is not strongly descending must pass through
 * untouched.
 */
class V112ChapterOrderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val host = "shelf.example"
    private val base = "https://$host"
    private val bookUrl = "$base/series/talisman"

    private fun page(rowsHtml: String): String = """
        <html><head><title>The Tale</title></head><body>
          <h1>The Tale</h1>
          <div class="chapters">$rowsHtml</div>
        </body></html>
    """.trimIndent()

    private fun namedRows(numbers: IntProgression): String =
        numbers.joinToString("\n") { n ->
            """<a href="/series/talisman/$n">Ch. $n Vortex Scans · 1 month</a>"""
        }

    private fun source(bookPage: String, reverse: Boolean): DeclarativeSource {
        val transport = FakeTransport { request ->
            if (request.url == bookUrl) {
                FakeTransport.ok(bookPage)
            } else {
                FakeTransport.response(404, "")
                    .let { app.xreader.core.error.AppResult.Ok(it) }
            }
        }
        val context = testContext(
            transport, tmp.newFolder(),
            ExtensionPermissions(domains = listOf(host))
        )
        val config = DeclarativeConfig(
            id = "shelf-example",
            name = "Shelf Example",
            lang = "en",
            baseUrl = base,
            details = DetailsSpec(
                title = "h1",
                chapters = ChapterListSpec(
                    list = ".chapters a",
                    name = "@text",
                    link = "@href",
                    reverse = reverse
                )
            ),
            chapter = ChapterSpec(content = "article")
        )
        return DeclarativeSource(config, context)
    }

    private fun chapters(bookPage: String, reverse: Boolean) = runBlocking {
        source(bookPage, reverse).chapters(SourceBook(id = bookUrl, title = "The Tale"))
    }

    // ── the device case ────────────────────────────────────

    @Test
    fun `descending list with reverse false comes back in reading order`() {
        val chapters = chapters(page(namedRows(92 downTo 1)), reverse = false)
        assertEquals(92, chapters.size)
        assertEquals(1f, chapters.first().number)
        assertEquals(92f, chapters.last().number)
        assertTrue(chapters.first().id.endsWith("/1"))
        assertTrue(chapters.last().id.endsWith("/92"))
    }

    @Test
    fun `stored progress for a half-read first chapter is near zero not 97 percent`() {
        val fetched = chapters(page(namedRows(92 downTo 1)), reverse = false)
        // What SourceBookSync stores: the reconciler assigns
        // each fetched chapter its list position as its index.
        val plan = ChapterReconciler.reconcile(emptyList(), fetched)
        val firstChapter = plan.inserts.single { it.fetched.number == 1f }
        assertEquals(0, firstChapter.index)
        // What the reader then saves after half the chapter:
        // position + in-chapter fraction over the whole book.
        val fraction = PageLocator.bookFraction(firstChapter.index, 0.5f, 92)
        assertTrue(
            "half of chapter 1 must save as ~0.5% of the book, was $fraction",
            fraction < 0.01f
        )
    }

    // ── the other quadrants ────────────────────────────────

    @Test
    fun `ascending list with reverse false is untouched`() {
        val chapters = chapters(page(namedRows(1..12)), reverse = false)
        assertEquals((1..12).map { it.toFloat() }, chapters.map { it.number })
    }

    @Test
    fun `descending list with reverse true still ends in reading order`() {
        val chapters = chapters(page(namedRows(12 downTo 1)), reverse = true)
        assertEquals((1..12).map { it.toFloat() }, chapters.map { it.number })
    }

    @Test
    fun `ascending list with reverse true is corrected back to reading order`() {
        val chapters = chapters(page(namedRows(1..12)), reverse = true)
        assertEquals((1..12).map { it.toFloat() }, chapters.map { it.number })
    }

    // ── restraint: no strong trend, no touch ───────────────

    @Test
    fun `numbering that restarts per volume is untouched`() {
        // Reading order: 1..10 of volume one, then 1..5 of
        // volume two — one descending step in fourteen is a
        // volume break, not a reversed list.
        val names = (1..10) + (1..5)
        val rows = names.mapIndexed { i, n ->
            """<a href="/series/talisman/c${i + 1}">Ch. $n</a>"""
        }.joinToString("\n")
        val chapters = chapters(page(rows), reverse = false)
        assertEquals(names.map { it.toFloat() }, chapters.map { it.number })
    }

    @Test
    fun `href numbers alone reveal a descending list`() {
        // Rows whose visible text carries no chapter number at
        // all (a date only): the trend must still be readable
        // from the chapter URLs' tails.
        val rows = (12 downTo 1).joinToString("\n") { n ->
            """<a href="/series/talisman/$n">2 days ago</a>"""
        }
        val chapters = chapters(page(rows), reverse = false)
        assertTrue(chapters.first().id.endsWith("/1"))
        assertTrue(chapters.last().id.endsWith("/12"))
    }
}
