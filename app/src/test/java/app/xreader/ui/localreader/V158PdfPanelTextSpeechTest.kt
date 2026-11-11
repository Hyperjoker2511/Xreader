package app.xreader.ui.localreader

import app.xreader.data.engine.OutlineEntry
import app.xreader.data.engine.PdfStripState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.8 (H2 + H5 + H6) — the panel, the text view and the
 * read-aloud feeder over F4's persisted strip.
 */
class V158PdfPanelTextSpeechTest {

    private fun outlineOf(vararg targets: Int): List<OutlineEntry> =
        targets.mapIndexed { i, target ->
            OutlineEntry(
                title = "Chapter ${i + 1}",
                targetJson = """{"page":$target}"""
            )
        }

    // ── H2 ────────────────────────────────────────────────────

    @Test
    fun `outline rows carry the target page number at the row end`() {
        val rows = pdfChapterRows(outlineOf(0, 9, 41, 77), currentPage = 45)
        assertEquals(4, rows.size)
        assertEquals("1", rows[0].trailingLabel)
        assertEquals("10", rows[1].trailingLabel)
        assertEquals("42", rows[2].trailingLabel)
        assertEquals("78", rows[3].trailingLabel)
        // The row containing the current page is marked.
        assertTrue(rows[2].isCurrent)
    }

    @Test
    fun `empty outline yields the honest empty state and footer position zero`() {
        assertTrue(pdfChapterRows(emptyList(), currentPage = 3).isEmpty())
        assertEquals(0, pdfFooterPosition(emptyList(), currentPage = 3))
        assertEquals(
            "Chapter 3 (4/10)",
            contentsFooterText("Chapter 3", position = 4, total = 10)
        )
        assertEquals("", contentsFooterText(null, position = 0, total = 0))
    }

    // ── H5 ────────────────────────────────────────────────────

    @Test
    fun `text view shows anchored pages once the strip is ready`() {
        val state = pdfTextViewState(
            PdfStripState.Ready(
                app.xreader.data.parse.PdfStripResult(
                    texts = listOf("First page text.", "", "Third page text."),
                    bounds = listOf(null, null, null)
                )
            )
        )
        val ready = state as PdfTextViewState.Ready
        assertEquals(3, ready.pages.size)
        assertEquals(listOf(0, 1, 2), ready.pages.map { it.page })
        assertEquals("First page text.", ready.pages[0].text)
        assertEquals("", ready.pages[1].text) // anchor kept for blank pages
        assertEquals("Third page text.", ready.pages[2].text)
    }

    @Test
    fun `no strip yet is the preparing state, never an empty view`() {
        assertTrue(pdfTextViewState(PdfStripState.Pending) is PdfTextViewState.Preparing)
        assertTrue(
            pdfTextViewState(PdfStripState.Unavailable) is PdfTextViewState.Unavailable
        )
        assertTrue(PDF_TEXT_VIEW_NOTE.contains("page numbers"))
    }

    // ── H6 ────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `read aloud speaks page N onward through the existing seam`() = runTest {
        val texts = listOf("Page zero.", "Page one.", "", "Page three.", "Page four.")
        val spoken = mutableListOf<String>()
        lateinit var feeder: PdfStripSpeech
        // The production sink IS the ReadAloudController
        // (loadChapter + play); the fake records each chapter's
        // plain text and reports it finished, as the
        // controller's onChapterFinished callback does.
        val sink = object : SpeechSink {
            override fun loadChapter(text: app.xreader.domain.reader.ChapterText) {
                spoken += PdfStripSpeech.plainTextOf(text)
            }
            override fun play() {
                feeder.onChapterFinished()
            }
        }
        feeder = PdfStripSpeech(
            textAt = { page -> texts.getOrElse(page) { "" } },
            pageCount = texts.size,
            sink = sink,
            scope = this
        )
        feeder.startAt(1)
        runCurrent()
        // Page 1, blank page 2 skipped, pages 3 and 4 — then the
        // end of the book settles the feeder.
        assertEquals(listOf("Page one.", "Page three.", "Page four."), spoken)
        assertEquals(-1, feeder.currentPage)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `read aloud over a textless stretch loads nothing and settles`() = runTest {
        val loaded = mutableListOf<String>()
        val feeder = PdfStripSpeech(
            textAt = { "" },
            pageCount = 3,
            sink = object : SpeechSink {
                override fun loadChapter(text: app.xreader.domain.reader.ChapterText) {
                    loaded += PdfStripSpeech.plainTextOf(text).joinToString()
                }
                override fun play() {}
            },
            scope = this
        )
        feeder.startAt(0)
        runCurrent()
        assertTrue(loaded.isEmpty())
        assertEquals(-1, feeder.currentPage)
    }
}
