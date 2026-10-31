package app.xreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.xreader.data.db.Highlight
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.reader.ChapterText
import app.xreader.domain.reader.ReaderConfig
import app.xreader.ui.theme.ReaderPalette
import java.io.File
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The endless stream (v1.0.2, R17): ONE LazyColumn over the
 * window of chapters the ViewModel's StreamWindowPlanner keeps
 * resident. Chapters flow into each other with a quiet header —
 * no chapter-end wall, no swap, no position reset. Position is
 * reported as (chapterIndex, charOffsetInChapter, chapterFraction)
 * — the exact locator the rest of the reader already speaks.
 *
 * Window slides are keyed by chapter+block, so appending forward
 * (the common case) never disturbs what's on screen.
 */
@OptIn(FlowPreview::class)
@Composable
fun ScrollStreamReader(
    entries: List<StreamEntry>,
    totalChapters: Int,
    config: ReaderConfig,
    palette: ReaderPalette,
    fontFamily: FontFamily,
    highlights: List<Highlight>,
    restoreChapterIndex: Int,
    restoreOffset: Int,
    restoreGeneration: Int,
    externalTurns: SharedFlow<Int>,
    assetFile: (String) -> File,
    onStreamPosition: (chapterIndex: Int, charOffset: Int, chapterFraction: Float) -> Unit,
    onCenterTap: () -> Unit,
    onChromeWanted: (Boolean) -> Unit,
    onDownloadChapter: (chapterIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    /** v1.0.6.2 (F7): measured chrome heights (see ScrollReader). */
    topInset: androidx.compose.ui.unit.Dp = 0.dp,
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp
) {
    val listState = rememberLazyListState()
    var restoredGeneration by remember { mutableStateOf(-1) }
    // v1.2.0 (WS2): scroll lock for image zoom — the flat-item
    // keys whose ZoomableImage reports a pinch/pan in progress or
    // a zoomed-in state. While any is active the column must not
    // scroll: drags belong to the image. Zoom state itself stays
    // per-item inside ZoomableImage; this set is only the lock.
    var zoomLockedItems by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Flatten the window into stable item keys once per window.
    val flat = remember(entries, totalChapters) {
        flattenEntries(entries, totalChapters)
    }

    // Restore: land on the anchor chapter's saved block. Runs once
    // per restore generation, after the window exists.
    LaunchedEffect(restoreGeneration, flat.size) {
        if (flat.isEmpty() || restoredGeneration == restoreGeneration) {
            return@LaunchedEffect
        }
        val anchor = entries.firstOrNull { it.chapterIndex == restoreChapterIndex }
            ?: entries.firstOrNull() ?: return@LaunchedEffect
        val blockIndex = anchor.text?.blockIndexAt(restoreOffset) ?: 0
        val flatIndex = flat.indexOfFirst {
            it.chapterIndex == anchor.chapterIndex && it.blockIndex == blockIndex
        }
        if (flatIndex >= 0) {
            listState.scrollToItem(flatIndex)
            restoredGeneration = restoreGeneration
        }
    }

    // Position reporting from the first visible item.
    LaunchedEffect(entries) {
        snapshotFlow {
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull()
                ?: return@snapshotFlow null
            val flatItem = flat.getOrNull(item.index) ?: return@snapshotFlow null
            // v1.0.6.2 (F7): the named chapter is the one whose
            // TEXT is on screen — a known-empty entry (imported
            // front matter) never names the position by itself.
            val blockCounts = entries.associate {
                it.chapterIndex to (it.text?.content?.blocks?.size ?: -1)
            }
            val resolvedIndex =
                app.xreader.domain.reader.StreamReadingPosition.resolve(
                    flatItem.chapterIndex, blockCounts
                )
            val entry = entries.firstOrNull { it.chapterIndex == resolvedIndex }
                ?: return@snapshotFlow null
            val text = entry.text ?: return@snapshotFlow null
            if (flatItem.blockIndex < 0 || resolvedIndex != flatItem.chapterIndex) {
                // On a chapter header: position = chapter start.
                return@snapshotFlow Triple(entry.chapterIndex, 0, 0f)
            }
            val range = text.rangeOf(flatItem.blockIndex) ?: return@snapshotFlow null
            val blockLength = maxOf(1, range.end - range.start)
            val within = if (item.size > 0) {
                (listState.firstVisibleItemScrollOffset.toFloat() / item.size)
                    .coerceIn(0f, 1f)
            } else {
                0f
            }
            val offset = range.start + (blockLength * within).toInt()
            Triple(
                entry.chapterIndex,
                offset,
                offset.toFloat() / maxOf(1, text.length)
            )
        }
            .distinctUntilChanged()
            .debounce(180L)
            .collectLatest { triple ->
                if (triple != null) {
                    onStreamPosition(triple.first, triple.second, triple.third)
                }
            }
    }

    // Chrome follows scroll direction, like the single-chapter
    // reader: up = show, down a while = hide.
    LaunchedEffect(entries) {
        var lastIndex = -1
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collectLatest { index ->
                if (lastIndex >= 0) {
                    if (index < lastIndex) onChromeWanted(true)
                    else if (index > lastIndex + 6) onChromeWanted(false)
                }
                lastIndex = index
            }
    }

    // Volume-key turns scroll a viewport, as in scroll mode.
    LaunchedEffect(Unit) {
        externalTurns.collectLatest { direction ->
            val viewport = listState.layoutInfo.viewportSize.height
            if (viewport > 0) {
                listState.animateScrollBy(direction * viewport.toFloat() * 0.92f)
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            // v1.0.6.2 (F7): Initial-pass tap observation — the
            // centre tap toggles the chrome even when it lands on
            // text (the old Main-pass detector was starved by the
            // blocks' click handlers and the selection container).
            .readerTapToggle(entries) { xFraction, _ ->
                if (app.xreader.domain.reader.CentreTap.isCentre(xFraction)) {
                    onCenterTap()
                }
            }
    ) {
        SelectionContainer {
            LazyColumn(
                state = listState,
                userScrollEnabled = zoomLockedItems.isEmpty(),
                contentPadding = PaddingValues(
                    start = config.marginHorizontalDp.dp,
                    end = config.marginHorizontalDp.dp,
                    top = config.marginVerticalDp.dp + topInset,
                    bottom = config.marginVerticalDp.dp + bottomInset
                ),
                modifier = Modifier.fillMaxSize()
            ) {
                flat.forEach { flatItem ->
                    when (flatItem.kind) {
                        FlatKind.HEADER -> item(key = flatItem.key) {
                            StreamChapterHeader(
                                // v1.1.2: the same reconciliation
                                // as the chrome header — a chapter
                                // name whose own number contradicts
                                // its position yields to the
                                // position line, so the eyebrow
                                // ("Chapter N") and the title under
                                // it can never disagree.
                                title = ReaderCounters.headerLine(
                                    chapterTitle = flatItem.title,
                                    chapterIndex = flatItem.chapterIndex,
                                    chapterCount = totalChapters
                                ),
                                chapterNumber = flatItem.chapterIndex + 1,
                                palette = palette
                            )
                        }
                        FlatKind.MISSING -> item(key = flatItem.key) {
                            // v1.0.6.2 (F3-stream): a chapter the
                            // stream is already pulling live says
                            // so — no button for a download that's
                            // already running.
                            val entry = entries.firstOrNull {
                                it.chapterIndex == flatItem.chapterIndex
                            }
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (entry?.fetching == true) {
                                    Text(
                                        "Fetching this chapter…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = palette.muted,
                                        textAlign = TextAlign.Center
                                    )
                                } else {
                                    Text(
                                        "This chapter hasn't been downloaded yet.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = palette.muted,
                                        textAlign = TextAlign.Center
                                    )
                                    TextButton(
                                        onClick = { onDownloadChapter(flatItem.chapterIndex) }
                                    ) { Text("Download this chapter") }
                                }
                            }
                        }
                        FlatKind.BLOCK -> item(key = flatItem.key) {
                            val entry = entries.firstOrNull {
                                it.chapterIndex == flatItem.chapterIndex
                            }
                            val text = entry?.text
                            val block = text?.content?.blocks
                                ?.getOrNull(flatItem.blockIndex)
                            // v1.2.0 (Wave 3): the per-chapter
                            // highlight filter is remembered — a
                            // fresh list identity per recomposition
                            // would defeat StreamBlock's own
                            // per-block memoisation below.
                            val chapterHighlights = remember(
                                highlights, entry?.chapterId
                            ) {
                                highlights.filter {
                                    it.chapterId == entry?.chapterId
                                }
                            }
                            if (text != null && block != null) {
                                StreamBlock(
                                    block = block,
                                    blockIndex = flatItem.blockIndex,
                                    chapterText = text,
                                    config = config,
                                    palette = palette,
                                    fontFamily = fontFamily,
                                    highlights = chapterHighlights,
                                    assetFile = assetFile,
                                    onZoomActiveChanged = { active ->
                                        zoomLockedItems = if (active) {
                                            zoomLockedItems + flatItem.key
                                        } else {
                                            zoomLockedItems - flatItem.key
                                        }
                                    }
                                )
                            }
                        }
                        FlatKind.END_OF_BOOK -> item(key = flatItem.key) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    "You've reached the end of the book.",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = palette.text
                                )
                                Text(
                                    "New chapters will appear here when " +
                                        "the source publishes them.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.muted,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
        if (config.readingLine) {
            ReadingLineOverlay(palette = palette)
        }
    }
}

private enum class FlatKind { HEADER, BLOCK, MISSING, END_OF_BOOK }

private data class FlatItem(
    val key: String,
    val kind: FlatKind,
    val chapterIndex: Int,
    val blockIndex: Int,
    val title: String = ""
)

private fun flattenEntries(
    entries: List<StreamEntry>,
    totalChapters: Int
): List<FlatItem> {
    val out = mutableListOf<FlatItem>()
    for (entry in entries) {
        out += FlatItem(
            key = "h-${entry.chapterIndex}",
            kind = FlatKind.HEADER,
            chapterIndex = entry.chapterIndex,
            blockIndex = -1,
            title = entry.title
        )
        when {
            entry.missing || entry.text == null -> out += FlatItem(
                key = "m-${entry.chapterIndex}",
                kind = FlatKind.MISSING,
                chapterIndex = entry.chapterIndex,
                blockIndex = -1
            )
            else -> {
                val blocks = entry.text.content.blocks
                for (index in blocks.indices) {
                    out += FlatItem(
                        key = "b-${entry.chapterIndex}-$index",
                        kind = FlatKind.BLOCK,
                        chapterIndex = entry.chapterIndex,
                        blockIndex = index
                    )
                }
            }
        }
    }
    val lastEntry = entries.lastOrNull()
    if (lastEntry != null && lastEntry.chapterIndex >= totalChapters - 1) {
        out += FlatItem(
            key = "end-of-book",
            kind = FlatKind.END_OF_BOOK,
            chapterIndex = lastEntry.chapterIndex,
            blockIndex = -1
        )
    }
    return out
}

@Composable
private fun StreamChapterHeader(
    title: String,
    chapterNumber: Int,
    palette: ReaderPalette
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 28.dp, bottom = 10.dp)
    ) {
        Text(
            "Chapter $chapterNumber",
            style = MaterialTheme.typography.labelLarge,
            color = palette.accent
        )
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = palette.text
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.muted.copy(alpha = 0.35f))
        )
    }
}

@Composable
private fun StreamBlock(
    block: ChapterBlock,
    blockIndex: Int,
    chapterText: ChapterText,
    config: ReaderConfig,
    palette: ReaderPalette,
    fontFamily: FontFamily,
    highlights: List<Highlight>,
    assetFile: (String) -> File,
    onZoomActiveChanged: (Boolean) -> Unit = {}
) {
    val range = chapterText.rangeOf(blockIndex) ?: return
    when (block) {
        // v1.2.0 (WS2): image blocks zoom via the shared
        // ZoomableImage (see ScrollReader) — graphicsLayer-only,
        // so stream position reporting is unaffected.
        is ChapterBlock.Image -> ZoomableImage(
            model = assetFile(block.fileName),
            contentDescription = block.alt,
            contentScale = ContentScale.FillWidth,
            doubleTapToZoom = config.doubleTapToZoom,
            disableZoomOut = config.disableZoomOut,
            onZoomActiveChanged = onZoomActiveChanged,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )
        is ChapterBlock.Rule -> Text(
            "• • •",
            color = palette.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        )
        else -> {
            // v1.2.0 (Wave 3, audit #2): remembered per block, as
            // in ScrollReader — keyed by the block, its range, the
            // highlights and the rendering inputs (bionic, palette).
            val highlightColor = palette.accent.copy(alpha = 0.38f)
            val spokenColor = palette.accent.copy(alpha = 0.30f)
            val overlayColor = palette.accent.copy(alpha = 0.20f)
            val backgrounds = remember(
                block, range.start, range.end, highlights,
                highlightColor, spokenColor, overlayColor
            ) {
                blockBackgrounds(
                    blockStart = range.start,
                    blockEnd = range.end,
                    highlightRanges = highlights.map { it.startOffset to it.endOffset },
                    spokenRange = null,
                    overlayActive = false,
                    highlightColor = highlightColor,
                    spokenColor = spokenColor,
                    overlayColor = overlayColor
                )
            }
            val annotated = remember(block, config.bionicReading, backgrounds) {
                annotatedForBlock(
                    block = block,
                    bionic = config.bionicReading,
                    backgrounds = backgrounds
                )
            }
            Text(
                annotated,
                style = blockTextStyle(block, config, fontFamily),
                color = palette.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = if (block is ChapterBlock.Heading) 16.dp else 0.dp,
                        bottom = config.paragraphSpacingDp.dp
                    )
            )
        }
    }
}

/**
 * The reading line (sweep addendum): a soft guide at reading
 * height. Drawn as an overlay — it never touches layout, text
 * selection, or position reporting.
 */
@Composable
fun ReadingLineOverlay(palette: ReaderPalette, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .align(Alignment.Center)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(
                            androidx.compose.ui.graphics.Color.Transparent,
                            palette.accent.copy(alpha = 0.07f),
                            androidx.compose.ui.graphics.Color.Transparent
                        )
                    )
                )
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .align(Alignment.Center)
                .background(palette.accent.copy(alpha = 0.35f))
        )
    }
}
