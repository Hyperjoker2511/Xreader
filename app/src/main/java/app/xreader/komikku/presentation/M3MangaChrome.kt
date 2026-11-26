/*
 * Copyright 2024 The Komikku Authors
 * Copyright 2015 Javier Tomás
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Patch B / B3 (2026-11-26, owner: "Manga ui full material 3"):
 * the manga reader's chrome in the Material 3 face is a COMPLETE
 * Material 3 design, rebuilt from the donor chrome's shape —
 * the plain-black top strip, the heavy floating capsule dock,
 * the mixed plain icons and the orphaned floating reading-mode
 * label are all gone in this face. What stands instead, built
 * only from material3 1.5.0 components and the active M3 colour
 * scheme's tokens:
 *
 *  - Top: the stock TopAppBar on `surface` (scrolled:
 *    `surfaceContainer`) — the same bar the core reader's M3
 *    chrome (ReaderChromeM3) uses. Back, the manga title
 *    (titleMedium), the chapter line beneath it (bodySmall,
 *    onSurfaceVariant), the reading mode as a proper chip
 *    (secondaryContainer / onSecondaryContainer, labelSmall —
 *    the mode's own name from ReadingMode.label, never a bare
 *    floating word over the page), and the chapter bookmark
 *    toggle (primary when set, onSurfaceVariant when not).
 *  - Bottom: ONE docked M3 surface — a Surface in
 *    `surfaceContainer` with the extraLarge top corners (28 dp,
 *    the M3 sheet/docked-bar shape), never a floating capsule.
 *    Inside it, in order: the seek row (previous chapter ·
 *    the page slider in the scheme's slider colours with the
 *    page chip · next chapter), a HorizontalDivider
 *    (outlineVariant), and the action row of LABELLED doors in
 *    the navigation-bar-item form (icon over a labelSmall
 *    label, the active door carrying the secondaryContainer
 *    indicator pill): Chapters · Reading mode · Share chapter
 *    (Patch B gap 25, only when the user enabled the Share
 *    bottom button) · Shift double pages (Patch B gap 24, only
 *    when a double-page pager is active) · Reader tools ·
 *    Reader settings. Every door the previous M3 toolbar
 *    carried survives, re-expressed in this form; Crop borders
 *    and Page layout stay in the settings sheet (gap 26,
 *    deliberate) and seeking stays on this horizontal slider
 *    (gap 27, deliberate).
 *  - The centred page indicator is an M3 chip (a
 *    surfaceContainerHigh Surface in the extraLarge shape,
 *    labelLarge tabular figures), not bare stroked text.
 *  - The reader-tools strip is a surfaceContainerHigh Surface
 *    of stock TextButtons, docked directly above the bottom
 *    surface so the two read as one chrome.
 *
 * PRESENTATION ONLY. Every callback lands on the exact handler
 * the shipped (Nocturne) chrome uses — seek on slider release
 * with a haptic tick per page (the donor ChapterNavigator's
 * contract), chapter doors on the ViewModel, doors on the
 * screen's sheet state, Share on shareChapterLink, Shift on
 * PagerConfig.shiftDoublePage. The Komikku engine — page
 * loading, caching, the pager/webtoon rendering — is not
 * touched, and when the Nocturne design is active none of this
 * composes: ReaderChrome.kt's shipped bars render instead, in
 * Nocturne's own glass/violet language.
 */
package app.xreader.komikku.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.xreader.ui.theme.LocalDesignState

// ------------------------------------------------------------ top bar

/**
 * The M3 top bar: back, the manga title (titleMedium) with the
 * chapter line beneath it (bodySmall, onSurfaceVariant) and —
 * when the "show reading mode" preference is on — the reading
 * mode named in a secondaryContainer chip (labelSmall) beside
 * the chapter line: ReadingMode.label verbatim ("Left to
 * right", "Webtoon", "Continuous vertical", …), the same name
 * the Nocturne chrome announces. The chapter bookmark toggle
 * closes the bar (primary when set).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun M3MangaTopBar(
    bookTitle: String,
    chapterTitle: String,
    chapterBookmarked: Boolean,
    onBack: () -> Unit,
    onToggleBookmark: () -> Unit,
    readingModeLabel: String = "",
    showReadingMode: Boolean = true
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    bookTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (chapterTitle.isNotBlank() || (showReadingMode && readingModeLabel.isNotBlank())) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        if (chapterTitle.isNotBlank()) {
                            Text(
                                chapterTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                        if (showReadingMode && readingModeLabel.isNotBlank()) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ) {
                                Text(
                                    readingModeLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    modifier = Modifier.padding(
                                        horizontal = 6.dp, vertical = 2.dp
                                    )
                                )
                            }
                        }
                    }
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        },
        actions = {
            IconButton(onClick = onToggleBookmark) {
                Icon(
                    if (chapterBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    if (chapterBookmarked) "Remove chapter bookmark" else "Bookmark chapter",
                    tint = if (chapterBookmarked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    )
}

// ------------------------------------------------------------ bottom dock

/**
 * The M3 bottom chrome: one docked surfaceContainer Surface
 * (extraLarge top corners) carrying the seek row and the
 * labelled action row — see the file header for the full
 * inventory. Seek contract is the donor navigator's: the
 * slider previews with a haptic tick per page and the seek
 * lands on release. For a 1-page chapter the slider segment
 * hides (the donor navigator's rule) and the seek row shows
 * the page position as text; the doors stay.
 *
 * [showShare] / [onShare] are Patch B gap 25: the Share
 * chapter door, rendered only when the user enabled the Share
 * bottom button (ReaderBottomButton.Share), wired to the same
 * shareChapterLink the Nocturne bar calls.
 * [showShiftDoublePages] / [onShiftDoublePages] are Patch B
 * gap 24: the Shift double pages door, rendered only when a
 * double-page pager is active, wired to the same
 * PagerConfig.shiftDoublePage toggle; [shiftDoublePagesActive]
 * carries its live state into the door's M3 active indicator.
 * The page chip obeys the Show/Hide knob
 * ([app.xreader.data.prefs.DesignState.mangaPageChip]); with
 * the chip off the position still reads as plain text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun M3MangaToolbar(
    currentPage: Int,
    totalPages: Int,
    hasPreviousChapter: Boolean,
    hasNextChapter: Boolean,
    onSeekPage: (Int) -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onViewChapters: () -> Unit,
    onReadingMode: () -> Unit,
    onToggleExhUtils: () -> Unit,
    onSettings: () -> Unit,
    showShare: Boolean = false,
    onShare: () -> Unit = {},
    showShiftDoublePages: Boolean = false,
    shiftDoublePagesActive: Boolean = false,
    onShiftDoublePages: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val showPageChip = LocalDesignState.current.mangaPageChip
    val haptics = LocalHapticFeedback.current
    var sliderValue by remember(currentPage) {
        mutableFloatStateOf((currentPage + 1).toFloat())
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        shadowElevation = 3.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // -- Seek row: previous chapter · slider + page
            // chip · next chapter.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(
                    onClick = onPreviousChapter,
                    enabled = hasPreviousChapter
                ) {
                    Icon(Icons.Filled.SkipPrevious, "Previous chapter")
                }
                if (totalPages > 1) {
                    Slider(
                        value = sliderValue,
                        onValueChange = { value ->
                            if (value.toInt() != sliderValue.toInt()) {
                                haptics.performHapticFeedback(
                                    HapticFeedbackType.TextHandleMove
                                )
                            }
                            sliderValue = value
                        },
                        onValueChangeFinished = {
                            onSeekPage(sliderValue.toInt() - 1)
                        },
                        valueRange = 1f..totalPages.toFloat(),
                        steps = (totalPages - 2).coerceAtLeast(0),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor =
                                MaterialTheme.colorScheme.surfaceContainerHighest
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    M3PageChip(
                        text = "${sliderValue.toInt()} / $totalPages",
                        showChip = showPageChip
                    )
                } else if (totalPages == 1) {
                    Text(
                        "Page 1 of 1",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    )
                } else {
                    androidx.compose.foundation.layout.Spacer(
                        Modifier.weight(1f)
                    )
                }
                IconButton(
                    onClick = onNextChapter,
                    enabled = hasNextChapter
                ) {
                    Icon(Icons.Filled.SkipNext, "Next chapter")
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            // -- Action row: every door labelled, in the
            // navigation-bar-item form. The reader-tools door
            // is NOT removable: the strip it opens is the only
            // door to Retry all, Boost page and the auto-scroll
            // toggle in this design (code 122, audit #26).
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                M3ChromeAction(
                    icon = Icons.AutoMirrored.Filled.List,
                    label = "Chapters",
                    onClick = onViewChapters,
                    modifier = Modifier.weight(1f)
                )
                M3ChromeAction(
                    icon = Icons.Filled.MenuBook,
                    label = "Mode",
                    onClick = onReadingMode,
                    modifier = Modifier.weight(1f)
                )
                if (showShare) {
                    M3ChromeAction(
                        icon = Icons.Filled.Share,
                        label = "Share",
                        onClick = onShare,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (showShiftDoublePages) {
                    M3ChromeAction(
                        icon = Icons.Filled.SwapHoriz,
                        label = "Shift",
                        active = shiftDoublePagesActive,
                        onClick = onShiftDoublePages,
                        modifier = Modifier.weight(1f)
                    )
                }
                M3ChromeAction(
                    icon = Icons.Filled.Build,
                    label = "Tools",
                    onClick = onToggleExhUtils,
                    modifier = Modifier.weight(1f)
                )
                M3ChromeAction(
                    icon = Icons.Filled.Settings,
                    label = "Settings",
                    onClick = onSettings,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** The page position in the seek row: the chip form
 * (secondaryContainer, labelLarge tabular figures) while the
 * Show page chip knob is on, plain labelLarge text while it
 * is off — the position is never lost with the chip. */
@Composable
private fun M3PageChip(text: String, showChip: Boolean) {
    val style = TextStyle(
        fontSize = MaterialTheme.typography.labelLarge.fontSize,
        lineHeight = MaterialTheme.typography.labelLarge.lineHeight,
        letterSpacing = MaterialTheme.typography.labelLarge.letterSpacing,
        fontFeatureSettings = "tnum"
    )
    if (showChip) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Text(
                text,
                style = style,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    } else {
        Text(
            text,
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

/** One labelled door of the bottom dock, in the M3
 * navigation-bar-item form: the icon rides an indicator pill
 * (secondaryContainer when the door is active — the Shift
 * door's on state — clear otherwise) over a labelSmall label.
 * The whole column is the tap target. */
@Composable
private fun M3ChromeAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false
) {
    val contentColor = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(MaterialTheme.shapes.extraLarge)
                .background(
                    if (active) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        androidx.compose.ui.graphics.Color.Transparent
                    }
                )
                .padding(horizontal = 16.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = contentColor)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The centred page indicator for the M3 chrome — the donor
 * ReaderPageIndicator's seat (centred, over the page) as an
 * M3 chip: a surfaceContainerHigh Surface in the extraLarge
 * shape carrying labelLarge tabular figures in onSurface.
 * It honours the "Show page number" preference: the host
 * passes the pref's live value as [showPageNumber] and the
 * indicator draws only when it is on (and the chapter has
 * pages).
 */
@Composable
fun M3CentredPageIndicator(
    currentPage: Int,
    totalPages: Int,
    showPageNumber: Boolean,
    modifier: Modifier = Modifier
) {
    if (!showPageNumber || totalPages <= 0) return
    Box(modifier, contentAlignment = Alignment.Center) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 2.dp
        ) {
            Text(
                "${currentPage + 1} / $totalPages",
                style = TextStyle(
                    fontSize = MaterialTheme.typography.labelLarge.fontSize,
                    lineHeight = MaterialTheme.typography.labelLarge.lineHeight,
                    letterSpacing =
                        MaterialTheme.typography.labelLarge.letterSpacing,
                    fontFeatureSettings = "tnum"
                ),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

/** The reader-tools strip in the M3 design (the bottom dock's
 * Tools door): the same three actions the shipped strip
 * carries — auto-scroll toggle + interval, Retry all, Boost
 * page — as stock TextButtons on a surfaceContainerHigh
 * Surface docked directly above the bottom dock, the
 * auto-scroll on state in primary. */
@Composable
fun M3ExhUtilsStrip(
    autoScrollOn: Boolean,
    intervalSeconds: Float,
    onToggleAutoScroll: () -> Unit,
    onRetryAll: () -> Unit,
    onBoostPage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TextButton(onClick = onToggleAutoScroll) {
                Text(
                    if (autoScrollOn) {
                        "Auto-scroll on (${intervalSeconds.toInt()}s)"
                    } else {
                        "Auto-scroll"
                    },
                    color = if (autoScrollOn) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }
            TextButton(onClick = onRetryAll) {
                Text("Retry all")
            }
            TextButton(onClick = onBoostPage) {
                Text("Boost page")
            }
        }
    }
}
