package app.xreader.ui.localreader

import app.xreader.data.engine.PdfStripState

/**
 * v1.5.8 (H5) → v1.7 OE-1 (R30): the PDF Text view's
 * whole-book overlay surface is retired — Reflow in the
 * one reader (one page's text at a time) supersedes it.
 * What survives here is the view's pure state model and
 * its span renderer, kept because the gate tests pin
 * them and because [highlightedPdfText] is the span
 * shape the Reflow surface's own renderer follows:
 * page-anchored pages from the strip's state, and a
 * page's text with its stored highlights (and the live
 * selection) as background spans.
 */

data class PdfTextPage(
    val page: Int,
    val text: String,
    /** v1.5.11 (step 7, D7): false when this page's text has
     * not been extracted yet (a partial, per-page Text view
     * still filling in) — the row then shows its "reading"
     * note instead of claiming the page has no text. Pages
     * from a landed whole strip are always loaded. */
    val loaded: Boolean = true
)

sealed interface PdfTextViewState {
    data object Preparing : PdfTextViewState
    data object Unavailable : PdfTextViewState
    data class Ready(val pages: List<PdfTextPage>) : PdfTextViewState
}

/** The view's state from the strip's: pending → Preparing
 * (never an empty view); landed → the page-anchored pages
 * (blank pages keep their anchor with an honest marker so
 * page numbers never drift). */
fun pdfTextViewState(stripState: PdfStripState): PdfTextViewState =
    when (stripState) {
        is PdfStripState.Pending -> PdfTextViewState.Preparing
        is PdfStripState.Unavailable -> PdfTextViewState.Unavailable
        is PdfStripState.Ready -> PdfTextViewState.Ready(
            stripState.result.texts.mapIndexed { index, text ->
                PdfTextPage(page = index, text = text)
            }
        )
    }

/**
 * v1.5.11 (step 7, research D7): the PER-PAGE Text-view state.
 * While the whole-book strip is still pending, pages already
 * extracted on their own ([pageTexts], keyed by page index)
 * are shown immediately as a partial Ready: extracted pages
 * carry their real text, the rest are present as unloaded
 * anchors (page numbers never drift) that fill in as their
 * extractions land. With nothing extracted yet the view is
 * still Preparing — never an empty view. A landed strip
 * supersedes the partial state wholesale; an unavailable
 * strip stays Unavailable.
 */
fun pdfTextViewState(
    stripState: PdfStripState,
    pageTexts: Map<Int, String>,
    pageCount: Int
): PdfTextViewState = when (stripState) {
    is PdfStripState.Ready -> pdfTextViewState(stripState)
    is PdfStripState.Unavailable -> PdfTextViewState.Unavailable
    is PdfStripState.Pending -> {
        if (pageTexts.isEmpty() || pageCount <= 0) {
            PdfTextViewState.Preparing
        } else {
            PdfTextViewState.Ready(
                pages = (0 until pageCount).map { page ->
                    val text = pageTexts[page]
                    PdfTextPage(
                        page = page,
                        text = text.orEmpty(),
                        loaded = text != null
                    )
                }
            )
        }
    }
}

/** The one-time honest note the view carries (H5). */
const val PDF_TEXT_VIEW_NOTE =
    "This is the document's text layer, flowing as text. " +
        "Printed page numbers, headers and footers don't survive " +
        "into this view — the words are the document's own."

/** One live Text-view selection: the long-pressed word on a
 * page (F3's PDF half — the selection unit is a word). */
data class PdfTextSelection(
    val page: Int,
    val range: IntRange,
    val word: String
)

/** A page's text with its stored highlights (and the live
 * selection) as background spans — the Text view's rendering
 * of the shared highlight store. */
fun highlightedPdfText(
    pageText: String,
    page: Int,
    highlights: List<StoredSelectionHighlight>,
    selection: PdfTextSelection?
): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        append(pageText)
        for (range in highlightSpansFor(pageText, page, highlights)) {
            addStyle(
                androidx.compose.ui.text.SpanStyle(
                    background = androidx.compose.ui.graphics.Color(HIGHLIGHT_TINT_ARGB)
                ),
                range.first, range.last + 1
            )
        }
        if (selection != null && selection.page == page) {
            val start = selection.range.first.coerceIn(0, pageText.length)
            val end = (selection.range.last + 1).coerceIn(start, pageText.length)
            if (end > start) {
                addStyle(
                    androidx.compose.ui.text.SpanStyle(
                        background = androidx.compose.ui.graphics.Color(0x8B5CF6)
                            .copy(alpha = 0.55f)
                    ),
                    start, end
                )
            }
        }
    }

