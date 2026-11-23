package app.xreader.data.parse

/**
 * v1.7 MR-2b: per-character page geometry for on-page PDF
 * selection — the sibling of [PdfPageBounds] (the one union
 * rect per page the F4 strip records). Where the bounds say
 * "the page's text lives somewhere in here", this says where
 * EVERY character of the page's strip text sits, so a stored
 * (page, char range) anchor can be drawn back onto the
 * printed page and a press on the page can be turned into a
 * char offset. Moon+'s model, copied: selection anchors are
 * char ranges, never word rects (REA-MOON / RESEARCH-MR2B).
 *
 * Everything in this file is pure (no Android, no Compose):
 * the external MR-2b harness drives it beside the collecting
 * stripper in [PdfStripperLoop].
 */

/** One character's rect in page points (display space:
 * origin top-left — the same space [PdfPageBounds] and
 * TextPosition's direction-adjusted coordinates live in). */
data class PdfCharRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun union(other: PdfCharRect): PdfCharRect = PdfCharRect(
        left = minOf(left, other.left),
        top = minOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom)
    )

    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom
}

/**
 * One page's strip text with a rect for EVERY character of
 * it, in output order: `rects.size == pageText.length` is
 * the load-bearing invariant (index integrity is absolute —
 * a stored char anchor must land on the same char it was
 * taken from; rect PRECISION may degrade on ligature/bidi
 * words, offsets never drift). Separator characters (word
 * spaces, line breaks, the page-end newline) carry the
 * nearest glyph's rect — they are drawn by nobody, but they
 * must occupy their slot or every later index shifts.
 * Produced by [PdfStripperLoop.stripPageGeometry]; a page
 * whose text is blank has NO geometry (the strip refuses
 * it, and the session door refuses it again) — a scanned
 * page is honestly unselectable, never approximately
 * selectable.
 */
data class PageCharGeometry(
    val pageText: String,
    val rects: List<PdfCharRect>
)

/** The selection kit's word characters (EpubSelection's
 * expandWordRange rule, restated for the page world):
 * letters, digits, apostrophes and hyphens. */
fun pdfIsWordChar(ch: Char): Boolean =
    ch.isLetterOrDigit() || ch == '\'' || ch == '-'

/** The word-char spans of each line of [text] as char-index
 * ranges, newline chars excluded: line `k`'s span covers the
 * chars between the (k−1)th and kth '\n'. Empty lines yield
 * no span. */
private fun pdfLineSpans(text: String): List<IntRange> {
    val spans = ArrayList<IntRange>()
    var start = 0
    for (i in text.indices) {
        if (text[i] == '\n') {
            if (i > start) spans += IntRange(start, i - 1)
            start = i + 1
        }
    }
    if (text.length > start) spans += IntRange(start, text.length - 1)
    return spans
}

/** The full word around word char [at] (inclusive range) —
 * expandWordRange's walk, on an index already known to be a
 * word char. */
private fun pdfExpandWord(text: String, at: Int): IntRange {
    var start = at
    while (start > 0 && pdfIsWordChar(text[start - 1])) start--
    var end = at
    while (end < text.length - 1 && pdfIsWordChar(text[end + 1])) end++
    return IntRange(start, end)
}

/**
 * MR-2b (Revision 1, A4 restated): the word a press at char
 * [index] selects. A word char expands to its whole word
 * (Moon+'s outward snap, ObjsAlignWord). A press on
 * whitespace first scans OUTWARD within the same line to the
 * nearest word char (either direction; the nearer wins, a
 * tie goes to the preceding word) and expands from there —
 * expandWordRange alone returns EMPTY on whitespace, which
 * would make presses between words dead. Null when the line
 * carries no word char at all (a blank or separator-only
 * line selects nothing).
 */
fun resolvePdfWordRange(text: String, index: Int): IntRange? {
    if (text.isEmpty()) return null
    val at = index.coerceIn(0, text.length - 1)
    if (pdfIsWordChar(text[at])) return pdfExpandWord(text, at)
    val lineStart = if (at == 0) {
        0
    } else {
        text.lastIndexOf('\n', at - 1) + 1
    }
    val newline = text.indexOf('\n', at)
    val lineEnd = if (newline < 0) text.length else newline
    var left = -1
    var i = at - 1
    while (i >= lineStart) {
        if (pdfIsWordChar(text[i])) {
            left = i
            break
        }
        i--
    }
    var right = -1
    i = at + 1
    while (i < lineEnd) {
        if (pdfIsWordChar(text[i])) {
            right = i
            break
        }
        i++
    }
    val chosen = when {
        left < 0 && right < 0 -> return null
        left < 0 -> right
        right < 0 -> left
        at - left <= right - at -> left
        else -> right
    }
    return pdfExpandWord(text, chosen)
}

/**
 * MR-2b: the char index a page point ([xPt], [yPt], page
 * points) presses — hit-testing in page coordinates, so it
 * is zoom-independent (Moon+'s rule). Exact containment
 * first (separator chars are never a press target — their
 * rects duplicate a glyph's); failing that, the vertically
 * nearest line's horizontally nearest char, so a press in a
 * line's leading or in the gap between two words still lands
 * on that line (A4's scan then finds the word). Null only
 * when the page has no non-separator char at all.
 */
fun pdfCharIndexAt(geometry: PageCharGeometry, xPt: Float, yPt: Float): Int? {
    val text = geometry.pageText
    val rects = geometry.rects
    if (text.isEmpty() || rects.size != text.length) return null
    for (i in text.indices) {
        if (text[i] == '\n') continue
        if (rects[i].contains(xPt, yPt)) return i
    }
    val spans = pdfLineSpans(text)
    if (spans.isEmpty()) return null
    fun bandDistance(span: IntRange): Float {
        var top = Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (i in span) {
            val r = rects[i]
            if (r.top < top) top = r.top
            if (r.bottom > bottom) bottom = r.bottom
        }
        return when {
            yPt < top -> top - yPt
            yPt > bottom -> yPt - bottom
            else -> 0f
        }
    }
    val line = spans.minByOrNull { bandDistance(it) } ?: return null
    fun xDistance(i: Int): Float {
        val r = rects[i]
        return when {
            xPt < r.left -> r.left - xPt
            xPt > r.right -> xPt - r.right
            else -> 0f
        }
    }
    return line.minByOrNull { xDistance(it) }
}

/**
 * MR-2b: [range]'s draw rects — one union rect per LINE the
 * range touches (Moon+ draws a selection as per-line rects,
 * never one big box across a line break). Newline chars
 * inside the range draw nothing and break the line; chars
 * are taken in range order, which is line order in strip
 * text. Out-of-range indexes are skipped defensively (a
 * stored range is clamped by its caller before this runs in
 * production; the harness passes exact ranges).
 */
fun pdfMergedLineRects(
    geometry: PageCharGeometry,
    range: IntRange
): List<PdfCharRect> {
    if (range.isEmpty()) return emptyList()
    val out = ArrayList<PdfCharRect>()
    var acc: PdfCharRect? = null
    for (i in range) {
        if (i !in geometry.rects.indices) continue
        if (geometry.pageText[i] == '\n') {
            acc?.let { out += it }
            acc = null
            continue
        }
        val r = geometry.rects[i]
        acc = acc?.union(r) ?: r
    }
    acc?.let { out += it }
    return out
}
