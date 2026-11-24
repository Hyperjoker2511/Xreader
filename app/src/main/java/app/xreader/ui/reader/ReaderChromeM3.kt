package app.xreader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import app.xreader.data.prefs.M3ReaderChrome
import app.xreader.domain.reader.WpmEstimator

/**
 * Code 120 (design §2.6 R28 + addendum A2): the reader chrome in
 * the Material 3 design. The same doors, counters and gating as
 * the Nocturne chrome (ReaderChrome in ReaderScreen.kt) — this
 * composable is called with the identical arguments and honours
 * the identical conditions (CBZ page world hides the text doors,
 * a PDF's mode switch rides the bar, time estimates only when
 * the chapter has timed words) — but the form is the spec's:
 *
 *  - Default: the FLOATING TOOLBAR (HorizontalFloatingToolbar,
 *    standard colours = surfaceContainerHigh) carrying the
 *    action doors as icon buttons, with the seek slider and the
 *    chapter/page counter in a tonal chip (full shape,
 *    surfaceContainerHigh) directly above it. The PDF page pill
 *    is part of that chip's counter line.
 *  - Alternative (the reader-chrome knob): TOP-AND-BOTTOM BARS —
 *    a small top app bar and a docked bottom bar. On material3
 *    1.5.0 there is no DockedToolbar composable (verified in the
 *    1.5.0 sources: only its tokens exist, used internally by
 *    BottomAppBar), so the docked form is BottomAppBar — the
 *    library's own docked-toolbar realization.
 *  - The top bar in both forms is the small top app bar:
 *    back, the book/chapter context, bookmark, Listen, Settings.
 *  - Chrome motion is effect-spec only (§1.4's discipline):
 *    fade + a short slide on the theme's effects specs, never
 *    the spatial specs.
 *  - Colours are the scheme's (§1.5's reader row). The page
 *    itself keeps the reader's own palette — only the chrome
 *    wears the scheme.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun M3ReaderChromeBar(
    visible: Boolean,
    chromeStyle: M3ReaderChrome,
    bookTitle: String,
    chapterTitle: String,
    chapterIndex: Int,
    chapterCount: Int,
    chapterOwnNumber: Float? = null,
    bookFraction: Float,
    minutesLeftInChapter: Int,
    minutesLeftInBook: Int,
    showTimeEstimates: Boolean = true,
    bookmarked: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onBack: () -> Unit,
    onToggleBookmark: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeekFraction: (Float) -> Unit,
    onOpenToc: () -> Unit,
    onOpenListen: () -> Unit,
    onOpenFocus: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAi: () -> Unit,
    onOpenSearch: () -> Unit = {},
    pdfPageLabel: String? = null,
    pdfModeLabel: String? = null,
    onTogglePdfMode: () -> Unit = {},
    cbzPageLabel: String? = null,
    onTopBarHeight: (Int) -> Unit = {},
    onBottomBarHeight: (Int) -> Unit = {}
) {
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val effectsOffset = MaterialTheme.motionScheme.defaultEffectsSpec<androidx.compose.ui.unit.IntOffset>()
    val counterLine = when {
        pdfPageLabel != null ->
            "$pdfPageLabel · ${(bookFraction * 100).toInt()}%"
        cbzPageLabel != null ->
            "$cbzPageLabel · ${(bookFraction * 100).toInt()}%"
        else -> ReaderCounters.footerLine(
            chapterIndex, chapterCount, chapterOwnNumber
        ) + " · ${(bookFraction * 100).toInt()}%"
    }
    Column(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(effects) + slideInVertically(effectsOffset) { -it / 2 },
            exit = fadeOut(effects) + slideOutVertically(effectsOffset) { -it / 2 }
        ) {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            bookTitle,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                        if (chapterTitle.isNotBlank()) {
                            Text(
                                chapterTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (cbzPageLabel == null) {
                        IconButton(onClick = onToggleBookmark) {
                            Icon(
                                if (bookmarked) Icons.Filled.Bookmark
                                else Icons.Filled.BookmarkBorder,
                                if (bookmarked) "Remove bookmark" else "Bookmark here",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = onOpenListen) {
                            Icon(Icons.Filled.Headphones, "Listen")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, "Reading settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                modifier = Modifier.onSizeChanged { onTopBarHeight(it.height) }
            )
        }
        Spacer(Modifier.weight(1f))
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(effects) + slideInVertically(effectsOffset) { it / 2 },
            exit = fadeOut(effects) + slideOutVertically(effectsOffset) { it / 2 }
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .onSizeChanged { onBottomBarHeight(it.height) }
            ) {
                if (chromeStyle == M3ReaderChrome.FLOATING_TOOLBAR) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        M3SeekChip(
                            counterLine = counterLine,
                            minutesLeftInChapter = minutesLeftInChapter,
                            minutesLeftInBook = minutesLeftInBook,
                            showTimeEstimates = showTimeEstimates,
                            bookFraction = bookFraction,
                            hasPrevious = hasPrevious,
                            hasNext = hasNext,
                            onPreviousChapter = onPreviousChapter,
                            onNextChapter = onNextChapter,
                            onSeekFraction = onSeekFraction
                        )
                        HorizontalFloatingToolbar(
                            expanded = true,
                            content = {
                                M3ChromeActions(
                                    cbzPageLabel = cbzPageLabel,
                                    pdfModeLabel = pdfModeLabel,
                                    onOpenToc = onOpenToc,
                                    onOpenSearch = onOpenSearch,
                                    onOpenListen = onOpenListen,
                                    onOpenAi = onOpenAi,
                                    onTogglePdfMode = onTogglePdfMode,
                                    onOpenFocus = onOpenFocus,
                                    onOpenSettings = onOpenSettings
                                )
                            }
                        )
                    }
                } else {
                    // The docked form (A2): the seek row docks
                    // directly on the BottomAppBar, both in the
                    // bar's own container colour.
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        M3SeekRow(
                            counterLine = counterLine,
                            minutesLeftInChapter = minutesLeftInChapter,
                            minutesLeftInBook = minutesLeftInBook,
                            showTimeEstimates = showTimeEstimates,
                            bookFraction = bookFraction,
                            hasPrevious = hasPrevious,
                            hasNext = hasNext,
                            onPreviousChapter = onPreviousChapter,
                            onNextChapter = onNextChapter,
                            onSeekFraction = onSeekFraction,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                    BottomAppBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        content = {
                            M3ChromeActions(
                                cbzPageLabel = cbzPageLabel,
                                pdfModeLabel = pdfModeLabel,
                                onOpenToc = onOpenToc,
                                onOpenSearch = onOpenSearch,
                                onOpenListen = onOpenListen,
                                onOpenAi = onOpenAi,
                                onTogglePdfMode = onTogglePdfMode,
                                onOpenFocus = onOpenFocus,
                                onOpenSettings = onOpenSettings
                            )
                        }
                    )
                }
            }
        }
    }
}

/** The seek chip of the floating form: full shape,
 * surfaceContainerHigh (§1.5), carrying the counter line, the
 * stock slider and the chapter-turn buttons. */
@Composable
private fun M3SeekChip(
    counterLine: String,
    minutesLeftInChapter: Int,
    minutesLeftInBook: Int,
    showTimeEstimates: Boolean,
    bookFraction: Float,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeekFraction: (Float) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        M3SeekRow(
            counterLine = counterLine,
            minutesLeftInChapter = minutesLeftInChapter,
            minutesLeftInBook = minutesLeftInBook,
            showTimeEstimates = showTimeEstimates,
            bookFraction = bookFraction,
            hasPrevious = hasPrevious,
            hasNext = hasNext,
            onPreviousChapter = onPreviousChapter,
            onNextChapter = onNextChapter,
            onSeekFraction = onSeekFraction,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/** The seek row shared by both chrome forms: previous, the
 * counter over the stock slider (the updated slider component —
 * drag previews, the jump lands on release, the Nocturne
 * contract), next. */
@Composable
private fun M3SeekRow(
    counterLine: String,
    minutesLeftInChapter: Int,
    minutesLeftInBook: Int,
    showTimeEstimates: Boolean,
    bookFraction: Float,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeekFraction: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var sliderFraction by remember { mutableStateOf(bookFraction) }
    var sliderDragging by remember { mutableStateOf(false) }
    LaunchedEffect(bookFraction) {
        if (!sliderDragging) sliderFraction = bookFraction
    }
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconButton(onClick = onPreviousChapter, enabled = hasPrevious) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous chapter")
        }
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    counterLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                if (showTimeEstimates) {
                    Text(
                        "${WpmEstimator.formatMinutes(minutesLeftInChapter)} in chapter · " +
                            "${WpmEstimator.formatMinutes(minutesLeftInBook)} in book",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            Slider(
                value = sliderFraction,
                onValueChange = {
                    sliderDragging = true
                    sliderFraction = it
                },
                onValueChangeFinished = {
                    sliderDragging = false
                    onSeekFraction(sliderFraction)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        IconButton(onClick = onNextChapter, enabled = hasNext) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next chapter")
        }
    }
}

/** The action doors, one set for both chrome forms — the same
 * doors and gating as the Nocturne capsule: Contents · Search ·
 * Listen in the text world, the PDF mode switch in a PDF's Ask
 * AI seat, then Focus · Settings. */
@Composable
private fun M3ChromeActions(
    cbzPageLabel: String?,
    pdfModeLabel: String?,
    onOpenToc: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenListen: () -> Unit,
    onOpenAi: () -> Unit,
    onTogglePdfMode: () -> Unit,
    onOpenFocus: () -> Unit,
    onOpenSettings: () -> Unit
) {
    if (cbzPageLabel == null) {
        IconButton(onClick = onOpenToc) {
            Icon(Icons.Filled.List, "Contents")
        }
        IconButton(onClick = onOpenSearch) {
            Icon(Icons.Filled.Search, "Search")
        }
        IconButton(onClick = onOpenListen) {
            Icon(Icons.Filled.Headphones, "Listen")
        }
    }
    if (pdfModeLabel != null) {
        IconButton(onClick = onTogglePdfMode) {
            Icon(Icons.Filled.Description, pdfModeLabel)
        }
    } else if (cbzPageLabel == null) {
        IconButton(onClick = onOpenAi) {
            Icon(
                Icons.Filled.AutoAwesome, "Ask AI",
                tint = MaterialTheme.colorScheme.tertiary
            )
        }
    }
    IconButton(onClick = onOpenFocus) {
        Icon(Icons.Filled.Timer, "Focus")
    }
    Spacer(Modifier.width(2.dp))
    IconButton(onClick = onOpenSettings) {
        Icon(Icons.Filled.Settings, "Settings")
    }
}
