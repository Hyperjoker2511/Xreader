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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.xreader.data.prefs.M3ReaderChrome
import app.xreader.domain.reader.WpmEstimator

/**
 * Code 121 (design §3.6 R28 / S14, §8 Q3): the ONE reader
 * chrome family in the Material 3 design, for every format
 * (text S9, PDF Pages S10, PDF Reflow S11, CBZ S12).
 *
 *  - A small top app bar on the scheme's `surface` ABOVE the
 *    page (never translucent over it): back, the book title
 *    (titleMedium), the chapter line beneath it (bodySmall),
 *    a bookmark toggle, and an overflow menu (Go to chapter ·
 *    Book page · Share — the last two render only when the
 *    host supplies the door; ReaderScreen supplies neither
 *    today, see the build log deviation D-121-W5-3).
 *  - At the bottom, the HorizontalFloatingToolbar
 *    (standard colours = surfaceContainerHigh, the component's
 *    own extraLarge container shape): Contents · Search ·
 *    Listen · Settings, the PDF page chip (S10) and the Reflow
 *    toggle (S10/S11) where a PDF is open. The seek slider
 *    rides as the chrome's second row when the toolbar is
 *    expanded, with the position line above it in labelMedium
 *    tabular figures ("Chapter 1 of 13 · 18% · ≈52 min in
 *    chapter · ≈6 h in book"); previous/next chapter are the
 *    toolbar's leading/trailing end buttons in that same
 *    expanded state. Tapping the expand toggle collapses the
 *    toolbar to its action row and hides the seek row.
 *  - Q3: there is NO docked-form alternative. The
 *    [chromeStyle] argument is retained only so existing call
 *    sites compile; the M3 path IGNORES it — the floating
 *    toolbar is the one chrome form in every format.
 *  - Chrome appears/vanishes with the page tap (the host owns
 *    [visible]) and moves on effect specs only — chrome never
 *    bounces over a page (§2.4). Colours are the scheme's;
 *    the page keeps the reader-local palette (§2.1.3).
 *  - Behaviour is unchanged from the shipped chrome: the same
 *    doors, counters and gating (CBZ page world hides the text
 *    doors, time estimates only when the chapter has timed
 *    words, the slider previews on drag and jumps on release).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun M3ReaderChromeBar(
    visible: Boolean,
    @Suppress("UNUSED_PARAMETER") chromeStyle: M3ReaderChrome,
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
    /** S10: tapping the PDF page chip opens the page grid
     * sheet. Null hides the chip's action (the chip still
     * shows the position). */
    onOpenPageGrid: (() -> Unit)? = null,
    /** Overflow doors the host may supply (S14). Null = the
     * door does not exist for this host and is not rendered —
     * no dead menu items. */
    onOpenBookPage: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    /** S22's door, kept on the toolbar beside Settings: the
     * focus timer is reader chrome, not a settings row. */
    showFocusDoor: Boolean = true,
    onTopBarHeight: (Int) -> Unit = {},
    onBottomBarHeight: (Int) -> Unit = {}
) {
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val effectsOffset =
        MaterialTheme.motionScheme.defaultEffectsSpec<androidx.compose.ui.unit.IntOffset>()
    var overflowOpen by remember { mutableStateOf(false) }
    // The toolbar's expanded state: expanded shows the
    // prev/next end buttons and the seek second row.
    var seekExpanded by remember { mutableStateOf(true) }
    val positionLine = buildString {
        when {
            pdfPageLabel != null -> append(pdfPageLabel)
            cbzPageLabel != null -> append(cbzPageLabel)
            else -> append(
                ReaderCounters.footerLine(
                    chapterIndex, chapterCount, chapterOwnNumber
                )
            )
        }
        append(" · ${(bookFraction * 100).toInt()}%")
        if (showTimeEstimates) {
            append(
                " · ≈${WpmEstimator.formatMinutes(minutesLeftInChapter)} in chapter" +
                    " · ≈${WpmEstimator.formatMinutes(minutesLeftInBook)} in book"
            )
        }
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
                    }
                    IconButton(onClick = { overflowOpen = true }) {
                        Icon(Icons.Filled.MoreVert, "More")
                    }
                    DropdownMenu(
                        expanded = overflowOpen,
                        onDismissRequest = { overflowOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Go to chapter") },
                            onClick = {
                                overflowOpen = false
                                onOpenToc()
                            }
                        )
                        if (onOpenBookPage != null) {
                            DropdownMenuItem(
                                text = { Text("Book page") },
                                onClick = {
                                    overflowOpen = false
                                    onOpenBookPage()
                                }
                            )
                        }
                        if (onShare != null) {
                            DropdownMenuItem(
                                text = { Text("Share") },
                                onClick = {
                                    overflowOpen = false
                                    onShare()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor =
                        MaterialTheme.colorScheme.surfaceContainer
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
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .onSizeChanged { onBottomBarHeight(it.height) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (seekExpanded) {
                    // The toolbar's second row: the position
                    // line over the seek slider, in the same
                    // tonal family as the toolbar itself.
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        M3SeekRow(
                            positionLine = positionLine,
                            bookFraction = bookFraction,
                            onSeekFraction = onSeekFraction,
                            modifier = Modifier.padding(
                                horizontal = 16.dp, vertical = 8.dp
                            )
                        )
                    }
                }
                HorizontalFloatingToolbar(
                    expanded = seekExpanded,
                    colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
                        toolbarContainerColor =
                            MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    leadingContent = {
                        IconButton(
                            onClick = onPreviousChapter,
                            enabled = hasPrevious
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                "Previous chapter"
                            )
                        }
                    },
                    trailingContent = {
                        IconButton(onClick = onNextChapter, enabled = hasNext) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                "Next chapter"
                            )
                        }
                    },
                    content = {
                        M3ChromeActions(
                            cbzPageLabel = cbzPageLabel,
                            pdfPageLabel = pdfPageLabel,
                            pdfModeLabel = pdfModeLabel,
                            seekExpanded = seekExpanded,
                            showFocusDoor = showFocusDoor,
                            onOpenToc = onOpenToc,
                            onOpenSearch = onOpenSearch,
                            onOpenListen = onOpenListen,
                            onOpenAi = onOpenAi,
                            onOpenFocus = onOpenFocus,
                            onTogglePdfMode = onTogglePdfMode,
                            onOpenPageGrid = onOpenPageGrid,
                            onToggleSeekExpanded = { seekExpanded = !seekExpanded },
                            onOpenSettings = onOpenSettings
                        )
                    }
                )
            }
        }
    }
}

/** The seek second row: the position line (labelMedium,
 * tabular figures) over the stock slider — drag previews,
 * the jump lands on release (the shipped contract). */
@Composable
private fun M3SeekRow(
    positionLine: String,
    bookFraction: Float,
    onSeekFraction: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var sliderFraction by remember { mutableStateOf(bookFraction) }
    var sliderDragging by remember { mutableStateOf(false) }
    LaunchedEffect(bookFraction) {
        if (!sliderDragging) sliderFraction = bookFraction
    }
    Column(modifier.fillMaxWidth()) {
        Text(
            positionLine,
            style = TextStyle(
                fontSize = MaterialTheme.typography.labelMedium.fontSize,
                lineHeight = MaterialTheme.typography.labelMedium.lineHeight,
                letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing,
                fontFeatureSettings = "tnum"
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
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
}

/** The toolbar's action row — the same doors and gating as
 * the shipped chrome: Contents · Search · Listen · Ask AI
 * in the text world, the PDF page chip + Reflow toggle in
 * a PDF's world, then Focus · seek expand · Settings. Focus
 * rides in every world (Patch B gap 23), CBZ included.
 * Code 124 (A7 + the owner's feature-parity rule): Ask AI
 * is a visible chrome door here, as it is in Nocturne's
 * chrome — never in the overflow menu. (The 121 note that
 * this design carries no AI door is superseded.) */
@Composable
private fun M3ChromeActions(
    cbzPageLabel: String?,
    pdfPageLabel: String?,
    pdfModeLabel: String?,
    seekExpanded: Boolean,
    showFocusDoor: Boolean,
    onOpenToc: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenListen: () -> Unit,
    onOpenAi: () -> Unit,
    onOpenFocus: () -> Unit,
    onTogglePdfMode: () -> Unit,
    onOpenPageGrid: (() -> Unit)?,
    onToggleSeekExpanded: () -> Unit,
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
        IconButton(onClick = onOpenAi) {
            Icon(Icons.Filled.AutoAwesome, "Ask AI")
        }
    }
    if (pdfPageLabel != null) {
        AssistChip(
            onClick = { onOpenPageGrid?.invoke() },
            label = { Text(pdfPageLabel) }
        )
    }
    if (pdfModeLabel != null) {
        IconButton(onClick = onTogglePdfMode) {
            Icon(Icons.Filled.Description, pdfModeLabel)
        }
    }
    // Patch B (gap 23): the Focus door shows in EVERY world,
    // including the CBZ/CBR page world — Nocturne renders its
    // Focus tile unconditionally, and the host's focus sheet
    // (ReaderScreen's ActiveSheet.FOCUS) is format-agnostic.
    // The text-only doors above (Contents · Search · Listen ·
    // Ask AI) stay CBZ-gated; Focus is not one of them.
    if (showFocusDoor) {
        IconButton(onClick = onOpenFocus) {
            Icon(Icons.Filled.Timer, "Focus timer")
        }
    }
    IconButton(onClick = onToggleSeekExpanded) {
        Icon(
            if (seekExpanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
            if (seekExpanded) "Hide position" else "Show position"
        )
    }
    IconButton(onClick = onOpenSettings) {
        Icon(Icons.Filled.Settings, "Settings")
    }
}
