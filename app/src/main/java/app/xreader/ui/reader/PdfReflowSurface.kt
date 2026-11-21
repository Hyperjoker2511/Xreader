package app.xreader.ui.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import app.xreader.domain.reader.ChapterText
import app.xreader.ui.localreader.HIGHLIGHT_TINT_ARGB
import app.xreader.ui.localreader.PdfTextSelection
import app.xreader.ui.localreader.SelectionActionRow
import app.xreader.ui.localreader.SelectionNoteEditor
import app.xreader.ui.localreader.StoredSelectionHighlight
import app.xreader.ui.localreader.expandWordRange
import app.xreader.ui.localreader.highlightSpansFor
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * v1.7 OE-1 (R8–R12, OQ3): Reflow in the one reader — ONE
 * page's text at a time, rendered by the core reader's own
 * text styling, deliberately NOT inside DocumentReader:
 * the chapter machinery (wall, prefetch, chapter state)
 * stays inert for a PDF, and a page boundary is crossed by
 * replacing this page's text with the adjacent page's —
 * Moon+'s shape, never a whole-book flow, never a chapter.
 *
 * Scrolling inside the page is ordinary text scrolling. A
 * continued pull (or a strong fling) past the top or
 * bottom edge crosses to the adjacent page and the new
 * page starts at its top — the page-granular place rule
 * (R14): nothing finer is carried.
 *
 * Selection is the local reader's own: long-press a word,
 * then Copy / Highlight / Note / Dictionary from the
 * shared action row. Highlights made here are the Reflow
 * form (R20): page + offset within THIS page's Reflow
 * text — the same string Listen speaks in this mode, so
 * the spoken range and the search mark land on the very
 * offsets the reader sees.
 */
@Composable
fun PdfReflowSurface(
    content: ChapterContent,
    config: ReaderConfig,
    fontFamily: FontFamily,
    textColor: Color,
    page: Int,
    highlights: List<StoredSelectionHighlight>,
    searchMark: PdfSearchMark?,
    spokenRange: IntRange?,
    onBoundaryCross: (direction: Int) -> Unit,
    onToggleControls: () -> Unit,
    onAddHighlight: (StoredSelectionHighlight) -> Unit,
    modifier: Modifier = Modifier
) {
    val chapterText = remember(content) { ChapterText(content) }
    val fullText = chapterText.text
    val scrollState = rememberScrollState()
    // R14: a page change lands at the new page's top —
    // nothing finer than the page is ever carried.
    LaunchedEffect(page) { scrollState.scrollTo(0) }
    val density = LocalDensity.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var selection by remember(page, content) {
        mutableStateOf<PdfTextSelection?>(null)
    }
    var noteDraft by remember(page, content) {
        mutableStateOf<PdfTextSelection?>(null)
    }
    var dictionary by remember(page, content) {
        mutableStateOf<Pair<String, String?>?>(null)
    }
    val layouts = remember(page, content) {
        mutableMapOf<Int, TextLayoutResult>()
    }

    // The boundary crossing: overscroll past an edge
    // accumulates; a deliberate pull (or a strong fling
    // at the edge) crosses. Any real scroll consumption
    // resets the pull — mid-page reading never crosses.
    val edgeThresholdPx = with(density) { 72.dp.toPx() }
    val boundary = remember(page) {
        object : NestedScrollConnection {
            var pull = 0f
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (available.y == 0f) {
                    pull = 0f
                } else {
                    pull += available.y
                    if (pull <= -edgeThresholdPx) {
                        pull = 0f
                        onBoundaryCross(1)
                    } else if (pull >= edgeThresholdPx) {
                        pull = 0f
                        onBoundaryCross(-1)
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity
            ): Velocity {
                if (available.y < -1600f) {
                    onBoundaryCross(1)
                } else if (available.y > 1600f) {
                    onBoundaryCross(-1)
                }
                return Velocity.Zero
            }
        }
    }

    val pageHighlights = remember(fullText, page, highlights) {
        highlightSpansFor(fullText, page, highlights)
    }
    val mark = searchMark?.takeIf { it.page == page }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .nestedScroll(boundary)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            content.blocks.forEachIndexed { index, block ->
                val range = chapterText.rangeOf(index)
                val blockText = when (block) {
                    is ChapterBlock.Heading -> block.text
                    is ChapterBlock.Paragraph -> block.text
                    else -> null
                }
                if (blockText != null && range != null) {
                    Text(
                        text = pdfReflowAnnotated(
                            blockText = blockText,
                            blockStart = range.start,
                            highlights = pageHighlights,
                            selection = selection
                                ?.takeIf { it.page == page },
                            mark = mark,
                            spokenRange = spokenRange
                        ),
                        style = blockTextStyle(block, config, fontFamily),
                        color = textColor,
                        onTextLayout = { layouts[index] = it },
                        modifier = Modifier.pointerInput(page, content, index) {
                            detectTapGestures(
                                onTap = { onToggleControls() },
                                onLongPress = { offset ->
                                    val layout = layouts[index]
                                        ?: return@detectTapGestures
                                    val local = layout
                                        .getOffsetForPosition(offset)
                                    val global = range.start + local
                                    val word = expandWordRange(
                                        fullText, global
                                    )
                                    selection = if (word.isEmpty()) {
                                        null
                                    } else {
                                        PdfTextSelection(
                                            page = page,
                                            range = word,
                                            word = fullText.substring(
                                                word.first, word.last + 1
                                            )
                                        )
                                    }
                                }
                            )
                        }
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        val active = selection
        if (active != null) {
            val activeStart = active.range.first
                .coerceIn(0, fullText.length)
            val activeEnd = (active.range.last + 1)
                .coerceIn(activeStart, fullText.length)
            val selectedText = fullText.substring(
                activeStart, activeEnd
            )
            SelectionActionRow(
                selectedText = selectedText,
                onCopy = {
                    clipboard.setText(AnnotatedString(selectedText))
                    selection = null
                },
                onHighlight = {
                    onAddHighlight(
                        StoredSelectionHighlight(
                            id = UUID.randomUUID().toString(),
                            page = page,
                            charStart = activeStart,
                            charEnd = activeEnd,
                            selectedText = selectedText,
                            createdAtEpochMillis =
                                System.currentTimeMillis()
                        )
                    )
                    selection = null
                },
                onNote = { noteDraft = active },
                onDictionary = {
                    val word = selectedText.trim()
                        .split(Regex("\\s+")).firstOrNull().orEmpty()
                    if (word.isNotBlank()) {
                        scope.launch {
                            val definition = runCatching {
                                app.xreader.ui.library.DictionaryLookup
                                    .lookup(context.filesDir, word)
                            }.getOrNull()
                            dictionary = word to definition
                        }
                    }
                    selection = null
                },
                onDismiss = { selection = null }
            )
        }
    }

    val draft = noteDraft
    if (draft != null) {
        val draftStart = draft.range.first
            .coerceIn(0, fullText.length)
        val draftEnd = (draft.range.last + 1)
            .coerceIn(draftStart, fullText.length)
        val selectedText = fullText.substring(draftStart, draftEnd)
        SelectionNoteEditor(
            selectedText = selectedText,
            initialNote = "",
            onSave = { note ->
                onAddHighlight(
                    StoredSelectionHighlight(
                        id = UUID.randomUUID().toString(),
                        page = page,
                        charStart = draftStart,
                        charEnd = draftEnd,
                        selectedText = selectedText,
                        note = note,
                        createdAtEpochMillis =
                            System.currentTimeMillis()
                    )
                )
                noteDraft = null
                selection = null
            },
            onCancel = { noteDraft = null }
        )
    }

    val lookup = dictionary
    if (lookup != null) {
        AlertDialog(
            onDismissRequest = { dictionary = null },
            title = { Text(lookup.first) },
            text = {
                Text(
                    lookup.second
                        ?: "No definition found on this device."
                )
            },
            confirmButton = {
                TextButton(onClick = { dictionary = null }) {
                    Text("Close")
                }
            }
        )
    }
}

/** One block's annotated text: this page's highlight spans,
 * the live selection, the search mark and the spoken range,
 * each clipped to the block's own slice of the page text. */
private fun pdfReflowAnnotated(
    blockText: String,
    blockStart: Int,
    highlights: List<IntRange>,
    selection: PdfTextSelection?,
    mark: PdfSearchMark?,
    spokenRange: IntRange?
): AnnotatedString {
    val blockEnd = blockStart + blockText.length
    fun local(range: IntRange): IntRange? {
        val start = maxOf(range.first, blockStart)
        val end = minOf(range.last + 1, blockEnd)
        return if (end > start) {
            IntRange(start - blockStart, end - 1 - blockStart)
        } else {
            null
        }
    }
    return buildAnnotatedString {
        append(blockText)
        highlights.forEach { range ->
            local(range)?.let { span ->
                addStyle(
                    SpanStyle(background = Color(HIGHLIGHT_TINT_ARGB)),
                    span.first, span.last + 1
                )
            }
        }
        spokenRange?.let { range ->
            local(range)?.let { span ->
                addStyle(
                    SpanStyle(background = Color(0x338B5CF6)),
                    span.first, span.last + 1
                )
            }
        }
        if (mark != null && mark.end > mark.start) {
            local(IntRange(mark.start, mark.end - 1))?.let { span ->
                addStyle(
                    SpanStyle(background = Color(0x99FFB020)),
                    span.first, span.last + 1
                )
            }
        }
        if (selection != null && !selection.range.isEmpty()) {
            local(selection.range)
                ?.let { span ->
                    addStyle(
                        SpanStyle(background = Color(0x558B5CF6)),
                        span.first, span.last + 1
                    )
                }
        }
    }
}
