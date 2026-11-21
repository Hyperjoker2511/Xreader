package app.xreader.data.prepare

import app.xreader.data.parse.PdfProse
import app.xreader.data.parse.PdfReflow
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import app.xreader.domain.reader.ChapterText

/**
 * v1.7 OE-1 (R9): per-page Reflow text preparation — the
 * pipeline extracted from the retired PdfConversionService
 * before its deletion, unchanged in behaviour:
 *
 *  - Cleanup (MR-2 amendment A5) runs on the strip's raw
 *    lines first: a line equal to a page's first or last
 *    non-empty line, repeating in that position on ≥ 60%
 *    of a run of ≥ 5 consecutive pages, is a running
 *    header/footer and drops; standalone page-number
 *    lines in first or last position drop with them.
 *    Nothing else is rewritten — the text is the book's
 *    own.
 *  - Each page's cleaned text then passes through
 *    [PdfReflow] (dehyphenation, line joins, paragraph
 *    breaks, heading detection) and becomes blocks:
 *    a heading-like paragraph is a level-2 Heading, the
 *    rest plain Paragraphs — the same block mapping the
 *    conversion used, so a page's [ChapterText] here is
 *    the string the conversion would have landed for
 *    that page.
 *
 * Moon+ prepares a page's Reflow text when the page is
 * shown (`pdfCreateReflowTextBionic(lastPosition)`);
 * preparation here is likewise per page on demand —
 * [cleanedPageTexts] is the one whole-strip pass (the
 * header/footer rule is defined over runs of pages, so
 * it cannot be per page), and it is computed once per
 * strip landing and cached by the caller.
 */
object PdfReflowText {

    /** The header/footer run rule (A5): at least this
     * many consecutive pages… */
    const val MIN_RUN_PAGES = 5

    /** The scanned test (MR-2 P1): below this many
     * printable chars in the whole strip, there is no
     * text layer worth reflowing. */
    const val MIN_TOTAL_CHARS = 200

    /** The scanned test: the per-page average floor. */
    const val MIN_AVG_CHARS_PER_PAGE = 50

    private val PAGE_NUMBER_LINE = Regex("""\D{0,3}\d{1,5}\D{0,3}""")

    /** True when the strip carries a usable text layer
     * (the conversion's scanned test, verbatim): total
     * printable prose of at least [MIN_TOTAL_CHARS] and
     * an average of at least [MIN_AVG_CHARS_PER_PAGE]
     * per page. A book failing it is Pages-only (R22). */
    fun hasUsableText(texts: List<String>): Boolean {
        if (texts.isEmpty()) return false
        val totalProse = texts.sumOf { PdfProse.printableCount(it) }
        return totalProse >= MIN_TOTAL_CHARS &&
            totalProse >= MIN_AVG_CHARS_PER_PAGE * texts.size
    }

    /**
     * Amendment A5's cleanup, over the strip's raw page
     * texts. Returns the kept text per page (lines joined
     * back with newlines, ready for [PdfReflow]).
     * Extracted verbatim from PdfConversionService.
     */
    fun cleanedPageTexts(texts: List<String>): List<String> {
        val pageLines = texts.map { text ->
            text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        }
        val firstIndex = pageLines.map { lines ->
            lines.indexOfFirst { it.isNotBlank() }
        }
        val lastIndex = pageLines.map { lines ->
            lines.indexOfLast { it.isNotBlank() }
        }
        val dropFirst = BooleanArray(texts.size)
        val dropLast = BooleanArray(texts.size)
        // Running headers/footers: a first/last line value
        // repeating in that position on ≥ 60% of a run of
        // ≥ 5 consecutive pages.
        for (position in 0..1) {
            val lineAt: (Int) -> String? = { page ->
                val lines = pageLines[page]
                val index = if (position == 0) {
                    firstIndex[page]
                } else {
                    lastIndex[page]
                }
                if (index >= 0) lines[index].trim() else null
            }
            val pagesByValue = HashMap<String, MutableList<Int>>()
            for (page in texts.indices) {
                val value = lineAt(page)?.takeIf { it.isNotEmpty() }
                    ?: continue
                pagesByValue.getOrPut(value) { mutableListOf() } += page
            }
            for ((_, pages) in pagesByValue) {
                for (i in pages.indices) {
                    var bestEnd = -1
                    for (j in i until pages.size) {
                        val span = pages[j] - pages[i] + 1
                        val count = j - i + 1
                        if (span >= MIN_RUN_PAGES &&
                            count * 5 >= span * 3
                        ) {
                            bestEnd = j
                        }
                    }
                    if (bestEnd >= 0) {
                        for (k in i..bestEnd) {
                            if (position == 0) {
                                dropFirst[pages[k]] = true
                            } else {
                                dropLast[pages[k]] = true
                            }
                        }
                    }
                }
            }
        }
        // Standalone page numbers in first/last position
        // drop with them (the importer's rule — a lone
        // number at a page's edge is furniture, never
        // prose).
        for (page in texts.indices) {
            val lines = pageLines[page]
            val first = firstIndex[page]
            val last = lastIndex[page]
            if (first >= 0 && isStandalonePageNumber(lines[first])) {
                dropFirst[page] = true
            }
            if (last >= 0 && isStandalonePageNumber(lines[last])) {
                dropLast[page] = true
            }
        }
        return pageLines.mapIndexed { page, lines ->
            lines.filterIndexed { index, _ ->
                !(index == firstIndex[page] && dropFirst[page]) &&
                    !(index == lastIndex[page] && dropLast[page])
            }.joinToString("\n")
        }
    }

    private fun isStandalonePageNumber(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.length > 8) return false
        if (!trimmed.any { it.isDigit() }) return false
        return PAGE_NUMBER_LINE.matches(trimmed)
    }

    /** One page's Reflow content: its cleaned strip text
     * through [PdfReflow], blocks mapped 1:1 from the
     * paragraphs (the conversion's mapping). */
    fun pageContent(cleanedText: String): ChapterContent {
        val paragraphs = PdfReflow.reflow(cleanedText)
        return ChapterContent(
            blocks = paragraphs.map { paragraph ->
                if (PdfReflow.looksLikeHeading(paragraph)) {
                    ChapterBlock.Heading(2, paragraph)
                } else {
                    ChapterBlock.Paragraph.plain(paragraph)
                }
            }
        )
    }

    /** One page's Reflow text as one string — the
     * [ChapterText] flattening of [pageContent], the
     * coordinate system Reflow search hits, highlights
     * and read-aloud all speak in. */
    fun pageText(cleanedText: String): String =
        ChapterText(pageContent(cleanedText)).text
}
