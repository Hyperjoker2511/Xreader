package app.xreader.ui.reader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.xreader.data.db.Highlight
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.reader.AutoScrollController
import app.xreader.domain.reader.ChapterText
import app.xreader.domain.reader.ReaderConfig
import app.xreader.ui.theme.EmberAmber
import app.xreader.ui.theme.ReaderPalette
import coil.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/** Actions offered on a long-pressed paragraph (passage tools, §2.4 #7). */
enum class ParagraphAction { HIGHLIGHT, NOTE, BOOKMARK }

/**
 * The continuous-scroll reader (PLAN §6): one LazyColumn over the
 * chapter's blocks — only visible blocks compose, so chapter size
 * doesn't matter. Position is reported as (char offset, fraction);
 * the fraction restores scroll, the offset feeds paged mode and the
 * saved locator. Long-pressing a paragraph offers Highlight / Note /
 * Bookmark for that passage; text selection (copy) works via
 * [SelectionContainer].
 */
@OptIn(ExperimentalFoundationApi::class, FlowPreview::class)
@Composable
fun ScrollReader(
    chapterText: ChapterText,
    config: ReaderConfig,
    palette: ReaderPalette,
    fontFamily: FontFamily,
    highlights: List<Highlight>,
    spokenRange: IntRange?,
    overlayBlockId: String?,
    restoreOffset: Int,
    restoreScrollFraction: Float,
    restoreGeneration: Int,
    externalTurns: SharedFlow<Int>,
    assetFile: (String) -> File,
    onPosition: (charOffset: Int, fraction: Float) -> Unit,
    onReachedEnd: () -> Unit,
    /** v1.0.1: false in "Chapter by chapter" transition mode — the
     * river pauses at chapter ends instead of flowing on. */
    autoContinue: Boolean = true,
    onParagraphAction: (blockIndex: Int, action: ParagraphAction) -> Unit,
    onCenterTap: () -> Unit,
    /** Scroll direction drives chrome auto-show/hide in scroll mode
     * (taps land on text and are consumed by selection/long-press). */
    onChromeWanted: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val blocks = chapterText.content.blocks
    var lastReportedOffset by remember(chapterText) { mutableStateOf(-1) }

    // Restore: land on the saved block, nudged by the saved fraction.
    LaunchedEffect(restoreGeneration) {
        if (blocks.isEmpty()) return@LaunchedEffect
        val blockIndex = chapterText.blockIndexAt(restoreOffset)
            .coerceIn(0, blocks.lastIndex)
        listState.scrollToItem(blockIndex, 0)
        onPosition(restoreOffset, restoreScrollFraction)
    }

    // Position reporting: first visible block + offset inside it.
    LaunchedEffect(chapterText) {
        snapshotFlow {
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull()
                ?: return@snapshotFlow null
            val range = chapterText.rangeOf(item.index) ?: return@snapshotFlow null
            val blockLength = maxOf(1, range.end - range.start)
            val within = if (item.size > 0) {
                (listState.firstVisibleItemScrollOffset.toFloat() / item.size)
                    .coerceIn(0f, 1f)
            } else {
                0f
            }
            val offset = range.start + (blockLength * within).toInt()
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            Triple(offset, offset.toFloat() / maxOf(1, chapterText.length), lastVisible)
        }
            .distinctUntilChanged()
            .debounce(180L)
            .collectLatest { triple ->
                if (triple == null) return@collectLatest
                val previous = lastReportedOffset
                if (previous >= 0) {
                    if (triple.first < previous - 40) onChromeWanted(true)
                    else if (triple.first > previous + 400) onChromeWanted(false)
                }
                lastReportedOffset = triple.first
                onPosition(triple.first, triple.second)
                if (triple.third >= blocks.lastIndex && triple.second >= 0.97f) {
                    if (autoContinue) onReachedEnd()
                }
            }
    }

    // Auto-scroll (v1.0.1): steady drift at the configured speed.
    // Any tap pauses (the tap handler below sets autoPaused); a
    // centre tap resumes. Chapter change / leaving the reader
    // cancels this effect — it can never run in the background.
    var autoPaused by remember(chapterText) { mutableStateOf(false) }
    LaunchedEffect(config.autoScrollEnabled, config.autoScrollSpeed, chapterText, autoPaused) {
        if (!config.autoScrollEnabled || autoPaused) return@LaunchedEffect
        val velocity = AutoScrollController.velocityPxPerSecond(config.autoScrollSpeed)
        while (true) {
            delay(100L)
            listState.animateScrollBy(velocity * 0.1f)
        }
    }

    // External turns (volume keys): one viewport per press.
    LaunchedEffect(Unit) {
        externalTurns.collectLatest { direction ->
            val viewport = listState.layoutInfo.viewportSize.height
            if (viewport > 0) {
                listState.animateScrollBy(direction * viewport.toFloat() * 0.92f)
            }
        }
    }

    // Read-aloud follow: keep the spoken sentence / overlay block visible.
    LaunchedEffect(spokenRange) {
        if (spokenRange == null) return@LaunchedEffect
        val blockIndex = chapterText.blockIndexAt(spokenRange.first)
        if (blockIndex >= 0) listState.animateScrollToItem(blockIndex)
    }
    LaunchedEffect(overlayBlockId) {
        if (overlayBlockId == null) return@LaunchedEffect
        val blockIndex = chapterText.ranges
            .firstOrNull { it.blockId == overlayBlockId }?.blockIndex ?: return@LaunchedEffect
        listState.animateScrollToItem(blockIndex)
    }

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(chapterText) {
                detectTapGestures { offset ->
                    // Taps toggle the chrome from anywhere (scroll and
                    // selection gestures are unaffected — they cancel
                    // or coexist with tap detection). A tap also
                    // pauses auto-scroll; a CENTRE tap resumes it
                    // (the same control family that shows the menu).
                    if (offset.x in (size.width * 0.30f)..(size.width * 0.70f)) {
                        autoPaused = false
                        onCenterTap()
                    } else {
                        autoPaused = true
                    }
                }
            }
    ) {
        SelectionContainer {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    horizontal = config.marginHorizontalDp.dp,
                    vertical = config.marginVerticalDp.dp
                ),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(
                    blocks,
                    key = { index, _ -> index }
                ) { index, block ->
                    ScrollBlock(
                        block = block,
                        blockIndex = index,
                        chapterText = chapterText,
                        config = config,
                        palette = palette,
                        fontFamily = fontFamily,
                        highlights = highlights,
                        spokenRange = spokenRange,
                        overlayActive = overlayBlockId != null &&
                            chapterText.rangeOf(index)?.blockId == overlayBlockId,
                        assetFile = assetFile,
                        onParagraphAction = onParagraphAction
                    )
                }
                item(key = "end-pad") {
                    Box(Modifier.height(48.dp))
                }
            }
        }
        if (config.readingLine) {
            ReadingLineOverlay(palette = palette)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScrollBlock(
    block: ChapterBlock,
    blockIndex: Int,
    chapterText: ChapterText,
    config: ReaderConfig,
    palette: ReaderPalette,
    fontFamily: FontFamily,
    highlights: List<Highlight>,
    spokenRange: IntRange?,
    overlayActive: Boolean,
    assetFile: (String) -> File,
    onParagraphAction: (blockIndex: Int, action: ParagraphAction) -> Unit
) {
    val range = chapterText.rangeOf(blockIndex) ?: return
    when (block) {
        is ChapterBlock.Image -> AsyncImage(
            model = assetFile(block.fileName),
            contentDescription = block.alt,
            contentScale = ContentScale.FillWidth,
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
            var menuOpen by remember(blockIndex) { mutableStateOf(false) }
            val backgrounds = blockBackgrounds(
                blockStart = range.start,
                blockEnd = range.end,
                highlightRanges = highlights.map { it.startOffset to it.endOffset },
                spokenRange = spokenRange,
                overlayActive = overlayActive,
                highlightColor = palette.accent.copy(alpha = 0.38f),
                spokenColor = palette.accent.copy(alpha = 0.30f),
                overlayColor = palette.accent.copy(alpha = 0.20f)
            )
            Box {
                Text(
                    annotatedForBlock(
                        block = block,
                        bionic = config.bionicReading,
                        backgrounds = backgrounds
                    ),
                    style = blockTextStyle(block, config, fontFamily),
                    color = palette.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { menuOpen = true }
                        )
                        .padding(
                            top = if (block is ChapterBlock.Heading) 16.dp else 0.dp,
                            bottom = config.paragraphSpacingDp.dp
                        )
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Highlight passage") },
                        onClick = {
                            menuOpen = false
                            onParagraphAction(blockIndex, ParagraphAction.HIGHLIGHT)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add note to passage") },
                        onClick = {
                            menuOpen = false
                            onParagraphAction(blockIndex, ParagraphAction.NOTE)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Bookmark here") },
                        onClick = {
                            menuOpen = false
                            onParagraphAction(blockIndex, ParagraphAction.BOOKMARK)
                        }
                    )
                }
            }
        }
    }
}
