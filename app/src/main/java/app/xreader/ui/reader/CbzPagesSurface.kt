package app.xreader.ui.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import java.io.File
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * v1.7 OE-4: the core book reader's own page world for a local
 * CBZ/CBR — the shape the owner's CBZ ruling (2026-11-21) and
 * Moon+ agree on: the comic's stored pages, one per screen, in
 * order, under the standard reader chrome. It serves both
 * doors into this reader — the "CBZ opens in: Book reader"
 * option and the automatic fallback when the manga reader
 * (Komikku) can't open the comic.
 *
 * The place is a page number: the settled page is reported
 * through [onPage] (the ViewModel keeps it in the progress
 * row in the manga reader's own shape, so the two readers
 * hand the place to each other exactly), and explicit jumps
 * (chrome turns, the seek slider, volume keys) arrive on
 * [jumps]. Pages turn by swipe or by edge taps (outer
 * thirds); the centre tap toggles the chrome, the host's
 * MOON_DEFAULT tap family. Pinch zoom rides the shared
 * [ZoomableImage]; its double-tap zoom is off here so a tap
 * is never both a turn and a zoom — the one gesture the page
 * world deliberately does not carry.
 *
 * There is no Contents/Listen/search/highlight machinery on
 * image pages: the host's chrome hides those doors while this
 * surface is up (ReaderChrome's cbzPageLabel seat).
 */
@Composable
fun CbzPagesSurface(
    pages: List<String>,
    initialPage: Int,
    jumps: SharedFlow<Int>,
    assetFile: (String) -> File,
    onPage: (Int) -> Unit,
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pageCount = pages.size
    if (pageCount == 0) return
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, pageCount - 1)
    ) { pageCount }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            onPage(page)
        }
    }
    LaunchedEffect(jumps) {
        jumps.collect { jump ->
            pagerState.scrollToPage(
                jump.coerceIn(0, pageCount - 1)
            )
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(pageCount) {
                detectTapGestures { offset ->
                    val third = size.width / 3f
                    when {
                        offset.x < third -> scope.launch {
                            pagerState.animateScrollToPage(
                                (pagerState.currentPage - 1)
                                    .coerceAtLeast(0)
                            )
                        }
                        offset.x > size.width - third ->
                            scope.launch {
                                pagerState.animateScrollToPage(
                                    (pagerState.currentPage + 1)
                                        .coerceAtMost(pageCount - 1)
                                )
                            }
                        else -> onToggleChrome()
                    }
                }
            }
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            ZoomableImage(
                model = assetFile(pages[page]),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                imageModifier = Modifier.fillMaxSize(),
                doubleTapToZoom = false,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
