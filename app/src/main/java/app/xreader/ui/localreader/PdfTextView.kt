package app.xreader.ui.localreader

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
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

// ── Code 122 (S15, audit #15): the local PDF text view's own
// M3 wiring site ─────────────────────────────────────────────
//
// S15 is S11's composition inside R29: the same top-bar
// subtitle and the same return toggle the main Reflow surface
// wires in ReaderScreen (subtitle "Page N · Reflow" at its M3
// top bar; the toggle named "View as printed"). Those strings
// live HERE as pure values so the local view's wiring cannot
// drift from S11's, and [LocalPdfTextViewM3TopBar] is the
// bar that carries them in the R29 family form (the same
// small TopAppBar + subtitle shape as LocalReaderChromeM3).
//
// HOST WIRING STILL NEEDED (not done — the host is outside
// this file): no live surface composes a local PDF text view
// today — LocalDocReaderScreen renders no PDF (OE-1/OE-2 send
// local PDFs to the core reader, whose Reflow surface already
// carries S11), and LocalReaderChromeM3 has no text-view
// state. When a local text view is composed under
// LocalReaderChromeM3, its chrome must be this bar with
// subtitle = pdfTextViewSubtitle(page) and the toggle calling
// the host's return-to-pages door.

/** S11's subtitle for the local text view's top bar. */
fun pdfTextViewSubtitle(page: Int): String = "Page ${page + 1} · Reflow"

/** S11's return-toggle label for the local text view. */
const val PDF_TEXT_VIEW_TOGGLE_LABEL = "View as printed"

/** The local PDF text view's M3 top bar (S15's wiring site):
 * book title, the S11 subtitle beneath it, and the
 * "View as printed" toggle as its action — the exact
 * composition ReaderScreen wires for the main Reflow surface,
 * in the R29 chrome family. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
fun LocalPdfTextViewM3TopBar(
    bookTitle: String,
    page: Int,
    onBack: () -> Unit,
    onTogglePdfMode: () -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier
) {
    androidx.compose.material3.TopAppBar(
        title = {
            androidx.compose.foundation.layout.Column {
                androidx.compose.material3.Text(
                    bookTitle,
                    style = androidx.compose.material3.MaterialTheme
                        .typography.titleMedium,
                    maxLines = 1
                )
                androidx.compose.material3.Text(
                    pdfTextViewSubtitle(page),
                    style = androidx.compose.material3.MaterialTheme
                        .typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme
                        .colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        },
        navigationIcon = {
            androidx.compose.material3.IconButton(onClick = onBack) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.AutoMirrored
                        .Filled.ArrowBack,
                    "Back"
                )
            }
        },
        actions = {
            androidx.compose.material3.IconButton(onClick = onTogglePdfMode) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Filled.Description,
                    PDF_TEXT_VIEW_TOGGLE_LABEL
                )
            }
        },
        colors = androidx.compose.material3.TopAppBarDefaults
            .topAppBarColors(
                containerColor = androidx.compose.material3.MaterialTheme
                    .colorScheme.surface,
                scrolledContainerColor = androidx.compose.material3
                    .MaterialTheme.colorScheme.surfaceContainer
            ),
        modifier = modifier
    )
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
 * of the shared highlight store.
 *
 * Code 120 fix pass (recheck L3): both backgrounds derive
 * from [selectionTint] — the active design's selection tint,
 * threaded exactly the way Reflow's renderer (this function's
 * own span shape) takes it. The parameter defaults to the
 * shipped ReaderViolet value, so every existing caller —
 * including the gate tests that pin this renderer — renders
 * byte-identically under Nocturne: the stored-highlight span
 * is the tint at 0.4 alpha (the [HIGHLIGHT_TINT_ARGB] value)
 * and the live-selection span the tint at 0.55. */
fun highlightedPdfText(
    pageText: String,
    page: Int,
    highlights: List<StoredSelectionHighlight>,
    selection: PdfTextSelection?,
    selectionTint: androidx.compose.ui.graphics.Color =
        androidx.compose.ui.graphics.Color(0xFF8B5CF6)
): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        append(pageText)
        for (range in highlightSpansFor(pageText, page, highlights)) {
            addStyle(
                androidx.compose.ui.text.SpanStyle(
                    background = selectionTint.copy(alpha = 0.4f)
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
                        background = selectionTint.copy(alpha = 0.55f)
                    ),
                    start, end
                )
            }
        }
    }

