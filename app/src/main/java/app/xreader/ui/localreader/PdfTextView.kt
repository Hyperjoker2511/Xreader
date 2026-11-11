package app.xreader.ui.localreader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.xreader.data.engine.PdfStripState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * v1.5.8 (H5): the PDF read as TEXT — Moon+'s "A"-badge view,
 * and the honest substitute for on-page text selection (X3,
 * impossible below API 35 on the bitmap render path): here the
 * words are real text that can be selected and copied.
 *
 * It renders F4's PERSISTED strip as flowing text in the app's
 * text-reader styling, page-anchored both directions: it opens
 * at the reader's current page, and closing it returns to the
 * page the text view had reached. It states ONCE, in plain
 * words, what does not survive: printed page numbers, headers
 * and footers. Until the strip lands it shows its "preparing"
 * state — never an empty view.
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

@Composable
internal fun PdfTextView(
    state: PdfTextViewState,
    initialPage: Int,
    onClose: (page: Int) -> Unit,
    // v1.5.10 (F3, Q3 → B): the selection actions. All
    // defaulted so pre-F3 call sites render the shipped
    // read-only view; when [onAddHighlight] is wired, the view
    // gains long-press word selection with Copy · Highlight ·
    // Note · Dictionary and renders the stored highlights as
    // spans. Highlights persist per (book, page, char-range)
    // in the shared selection store (EpubSelection.kt).
    highlights: List<StoredSelectionHighlight> = emptyList(),
    onAddHighlight: ((StoredSelectionHighlight) -> Unit)? = null,
    onRemoveHighlight: ((StoredSelectionHighlight) -> Unit)? = null,
    dictionaryLookup: (suspend (String) -> String?)? = null
) {
    // Page-anchored both directions: the view opens at the
    // reader's page and closing returns to the page it reached.
    var reachedPage by remember { mutableIntStateOf(initialPage) }
    var selection by remember { mutableStateOf<PdfTextSelection?>(null) }
    var noteEditing by remember { mutableStateOf(false) }
    var dictionaryResult by remember { mutableStateOf<String?>(null) }
    var dictionaryTried by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onClose(reachedPage) }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to pages"
                )
            }
            Text(
                "Text view",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        Text(
            PDF_TEXT_VIEW_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (onAddHighlight != null) {
            Text(
                "Press and hold a word to select it — then copy " +
                    "it, highlight it, add a note, or look it up.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(12.dp))
        // F3: the shared selection action bar (the EPUB popup's
        // own row) over the current word selection.
        val currentSelection = selection
        if (onAddHighlight != null && currentSelection != null) {
            SelectionActionRow(
                selectedText = currentSelection.word,
                onCopy = {
                    val clipboard = context.getSystemService(
                        android.content.Context.CLIPBOARD_SERVICE
                    ) as? android.content.ClipboardManager
                    clipboard?.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            "Text view selection", currentSelection.word
                        )
                    )
                    selection = null
                },
                onHighlight = {
                    onAddHighlight(
                        StoredSelectionHighlight(
                            id = java.util.UUID.randomUUID().toString(),
                            page = currentSelection.page,
                            charStart = currentSelection.range.first,
                            charEnd = currentSelection.range.last + 1,
                            selectedText = currentSelection.word,
                            createdAtEpochMillis = System.currentTimeMillis()
                        )
                    )
                    selection = null
                },
                onNote = { noteEditing = true },
                onDictionary = {
                    val lookup = dictionaryLookup
                    if (lookup == null) {
                        dictionaryResult = null
                        dictionaryTried = true
                    } else {
                        scope.launch {
                            dictionaryResult = lookup(
                                app.xreader.ui.library.DictionaryLookup
                                    .firstWord(currentSelection.word)
                            )
                            dictionaryTried = true
                        }
                    }
                },
                onDismiss = { selection = null }
            )
            if (dictionaryTried) {
                Text(
                    dictionaryResult
                        ?: if (dictionaryLookup == null) {
                            "No dictionary added yet — add one in " +
                                "Library, under Words I'm learning."
                        } else {
                            "That word isn't in this dictionary."
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            // An exactly-matching stored highlight can be
            // removed from here (the Text-view twin of the
            // EPUB decoration tap → Remove).
            val existingHighlight = highlights.firstOrNull { stored ->
                stored.page == currentSelection.page &&
                    stored.charStart == currentSelection.range.first &&
                    stored.charEnd == currentSelection.range.last + 1
            }
            if (existingHighlight != null && onRemoveHighlight != null) {
                androidx.compose.material3.TextButton(
                    onClick = {
                        onRemoveHighlight(existingHighlight)
                        selection = null
                    }
                ) { Text("Remove highlight") }
                if (existingHighlight.hasNote) {
                    Text(
                        existingHighlight.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (noteEditing && currentSelection != null && onAddHighlight != null) {
            SelectionNoteEditor(
                selectedText = currentSelection.word,
                initialNote = "",
                onSave = { note ->
                    onAddHighlight(
                        StoredSelectionHighlight(
                            id = java.util.UUID.randomUUID().toString(),
                            page = currentSelection.page,
                            charStart = currentSelection.range.first,
                            charEnd = currentSelection.range.last + 1,
                            selectedText = currentSelection.word,
                            note = note,
                            createdAtEpochMillis = System.currentTimeMillis()
                        )
                    )
                    noteEditing = false
                    selection = null
                },
                onCancel = { noteEditing = false }
            )
        }
        when (state) {
            is PdfTextViewState.Preparing -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Preparing the text…")
                Text(
                    "The document's text layer is still being read.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is PdfTextViewState.Unavailable -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("This document's text couldn't be read.")
                Text(
                    "Scanned pages without a text layer have no text view.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is PdfTextViewState.Ready -> PdfTextPages(
                state = state,
                initialPage = initialPage,
                onPageReached = { reachedPage = it },
                interactive = onAddHighlight != null,
                highlights = highlights,
                selection = selection,
                onWordSelected = { page, range, word ->
                    selection = PdfTextSelection(page, range, word)
                    dictionaryTried = false
                    dictionaryResult = null
                }
            )
        }
    }
}

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

@Composable
private fun PdfTextPages(
    state: PdfTextViewState.Ready,
    initialPage: Int,
    onPageReached: (page: Int) -> Unit,
    interactive: Boolean = false,
    highlights: List<StoredSelectionHighlight> = emptyList(),
    selection: PdfTextSelection? = null,
    onWordSelected: (page: Int, range: IntRange, word: String) -> Unit =
        { _, _, _ -> }
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialPage.coerceIn(
            0, (state.pages.size - 1).coerceAtLeast(0)
        )
    )
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collectLatest { index ->
            state.pages.getOrNull(index)?.let { onPageReached(it.page) }
        }
    }
    if (!interactive) {
        SelectionContainer {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(state.pages, key = { it.page }) { page ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(
                            "Page ${page.page + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            // v1.5.11 (step 7): an unloaded
                            // page is still being read — it
                            // must not claim to have no text.
                            if (!page.loaded) {
                                "Reading this page's text…"
                            } else {
                                page.text.ifBlank { "(no text on this page)" }
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
        return
    }
    // F3 interactive mode: long-press selects its word; stored
    // highlights render as spans. (System SelectionContainer
    // is off in this mode — our action bar IS the selection
    // menu, the Q3 → B shared shape.)
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(state.pages, key = { it.page }) { page ->
            var layout by remember(page.page) {
                mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null)
            }
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    "Page ${page.page + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                if (!page.loaded) {
                    // v1.5.11 (step 7): still extracting —
                    // honest pending note, never "(no text…)".
                    Text(
                        "Reading this page's text…",
                        style = MaterialTheme.typography.bodyLarge
                    )
                } else if (page.text.isBlank()) {
                    Text(
                        "(no text on this page)",
                        style = MaterialTheme.typography.bodyLarge
                    )
                } else {
                    Text(
                        highlightedPdfText(page.text, page.page, highlights, selection),
                        style = MaterialTheme.typography.bodyLarge,
                        onTextLayout = { layout = it },
                        modifier = Modifier.pointerInput(page.page, page.text) {
                            detectTapGestures(
                                onLongPress = { offset ->
                                    val result = layout ?: return@detectTapGestures
                                    val textOffset = result.getOffsetForPosition(offset)
                                    val range = expandWordRange(page.text, textOffset)
                                    if (!range.isEmpty()) {
                                        onWordSelected(
                                            page.page,
                                            range,
                                            page.text.substring(
                                                range.first, range.last + 1
                                            )
                                        )
                                    }
                                }
                            )
                        }
                    )
                }
            }
        }
    }
}
