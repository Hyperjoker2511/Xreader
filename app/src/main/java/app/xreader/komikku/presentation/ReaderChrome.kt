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
 * v1.5.7 (Komikku full): the donor's Compose chrome, adapted
 * from Komikku (eu.kanade.presentation.reader.appbars.*,
 * components/ChapterNavigator.kt, ReaderPageIndicator.kt,
 * ReaderNavigationOverlayView): top bar with the chapter
 * bookmark toggle, the configurable bottom bar, the horizontal
 * ChapterNavigator and its VERTICAL page scrubber variant, the
 * centred stroked page indicator, the ExhUtils strip and the
 * tap-zone navigation overlay. Adaptations (§4(b)):
 *  - The WebView / Browser bottom-bar buttons are NOT rendered
 *    (conscious exclusion: Xreader's invisible-engine rule —
 *    the web view is a hidden engine, never a reading surface).
 *    Their catalogue entries stay in ReaderBottomButton so a
 *    stored button set keeps parsing; they are filtered here.
 *  - Strings are literals, per the port's convention; the bars
 *    bind the app Material theme (Nocturne Violet context).
 */
package app.xreader.komikku.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.xreader.komikku.setting.ReaderBottomButton
import app.xreader.komikku.viewer.ViewerNavigation

private val BarColor = Color.Black.copy(alpha = 0.82f)

// ------------------------------------------------------------ top bar

/** Donor ReaderTopBar: back, manga + chapter titles, and the
 * current chapter's bookmark toggle (R-03). */
@Composable
fun ReaderTopBar(
    bookTitle: String,
    chapterTitle: String,
    chapterBookmarked: Boolean,
    onBack: () -> Unit,
    onToggleBookmark: () -> Unit
) {
    Surface(color = BarColor) {
        Row(
            Modifier
                .fillMaxWidth()
                // v1.5.35 (item 3): the status-bar clearance
                // is the SYSTEM inset (the donor's app bars
                // pad by window insets) — the shipped
                // hardcoded 30 dp vertical padding only
                // approximated one phone's status bar and
                // wasted the rest on every other.
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    bookTitle,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1
                )
                Text(
                    chapterTitle,
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
            }
            IconButton(onClick = onToggleBookmark) {
                Icon(
                    if (chapterBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    if (chapterBookmarked) "Remove chapter bookmark" else "Bookmark chapter",
                    tint = Color.White
                )
            }
        }
    }
}

// ------------------------------------------------------------ navigators

/** Donor ChapterNavigator (horizontal): prev-chapter button,
 * current page, draggable slider with haptics, total,
 * next-chapter button. Hidden wholesale for 1-page chapters. */
@Composable
fun ChapterNavigator(
    currentPage: Int,
    totalPages: Int,
    hasPreviousChapter: Boolean,
    hasNextChapter: Boolean,
    onSeekPage: (Int) -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (totalPages <= 1) return
    val haptics = LocalHapticFeedback.current
    var sliderValue by remember(currentPage) {
        mutableFloatStateOf((currentPage + 1).toFloat())
    }
    Surface(color = BarColor, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPreviousChapter, enabled = hasPreviousChapter) {
                Icon(Icons.Filled.SkipPrevious, "Previous chapter", tint = Color.White)
            }
            Text(
                "${sliderValue.toInt()}",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.width(32.dp)
            )
            Slider(
                value = sliderValue,
                onValueChange = { value ->
                    if (value.toInt() != sliderValue.toInt()) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    sliderValue = value
                },
                onValueChangeFinished = { onSeekPage(sliderValue.toInt() - 1) },
                valueRange = 1f..totalPages.toFloat(),
                steps = (totalPages - 2).coerceAtLeast(0),
                modifier = Modifier.weight(1f)
            )
            Text(
                "$totalPages",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.width(32.dp)
            )
            IconButton(onClick = onNextChapter, enabled = hasNextChapter) {
                Icon(Icons.Filled.SkipNext, "Next chapter", tint = Color.White)
            }
        }
    }
}

/**
 * v1.5.8 (I1): the vertical scrubber's presentation model —
 * the donor ChapterNavigatorVert shape as data: a slim rail,
 * a clean pill thumb riding it, and the current/total labels
 * OUTSIDE the track. There is deliberately NO bandBackground
 * field: the v1.5.7 dark slab down the screen edge is the
 * defect this item removes, and the model cannot re-grow it.
 */
data class ScrubberPresentation(
    val currentLabel: String,
    val totalLabel: String,
    /** 0f (first page) .. 1f (last page): the pill's ride. */
    val thumbFraction: Float
)

fun scrubberPresentation(currentPage: Int, totalPages: Int): ScrubberPresentation =
    ScrubberPresentation(
        currentLabel = "${currentPage + 1}",
        totalLabel = "$totalPages",
        thumbFraction = if (totalPages <= 1) {
            0f
        } else {
            currentPage.toFloat() / (totalPages - 1)
        }
    )

/** Donor ChapterNavigatorVert (R-09), restyled v1.5.8 (I1) to
 * the donor's rail shape: NO full-height band background — a
 * slim rail with a clean pill thumb, the current-page label
 * above the track and the total below it, both OUTSIDE the
 * rail. Geometry, drag behaviour and haptics are the shipped
 * ones: drag moves the value with a haptic tick per page, and
 * releasing seeks. */
@Composable
fun ChapterNavigatorVert(
    currentPage: Int,
    totalPages: Int,
    hasPreviousChapter: Boolean,
    hasNextChapter: Boolean,
    onSeekPage: (Int) -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (totalPages <= 1) return
    val haptics = LocalHapticFeedback.current
    var sliderValue by remember(currentPage) {
        mutableFloatStateOf((currentPage + 1).toFloat())
    }
    val presentation = scrubberPresentation(
        (sliderValue.toInt() - 1).coerceIn(0, totalPages - 1), totalPages
    )
    Column(
        modifier
            .fillMaxHeight()
            .width(56.dp)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(onClick = onPreviousChapter, enabled = hasPreviousChapter) {
            Icon(
                Icons.Filled.SkipPrevious, "Previous chapter",
                tint = Color.White
            )
        }
        // Current-page label: above the track, outside it.
        Text(
            presentation.currentLabel,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium
        )
        // The slim rail + pill thumb (no band behind either).
        Box(
            Modifier
                .weight(1f)
                .width(48.dp)
                .pointerInput(totalPages) {
                    detectVerticalDragGestures(
                        onDragEnd = { onSeekPage(sliderValue.toInt() - 1) },
                        onVerticalDrag = { change, _ ->
                            val fraction = (change.position.y / size.height)
                                .coerceIn(0f, 1f)
                            val value = 1f + fraction * (totalPages - 1)
                            if (value.toInt() != sliderValue.toInt()) {
                                haptics.performHapticFeedback(
                                    HapticFeedbackType.TextHandleMove
                                )
                            }
                            sliderValue = value
                        }
                    )
                },
            contentAlignment = Alignment.TopCenter
        ) {
            // Rail: a slim 4dp track, vertically centred.
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(4.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.28f))
            )
            // Pill thumb riding the rail at the page fraction.
            ScrubberPillThumb(fraction = presentation.thumbFraction)
        }
        // Total label: below the track, outside it.
        Text(
            presentation.totalLabel,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium
        )
        IconButton(onClick = onNextChapter, enabled = hasNextChapter) {
            Icon(
                Icons.Filled.SkipNext, "Next chapter",
                tint = Color.White
            )
        }
    }
}

/** The pill thumb: a clean violet pill centred on the rail at
 * [fraction] of the track height (measured, so the ride is
 * exact at any height). */
@Composable
private fun ScrubberPillThumb(fraction: Float) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier
            .fillMaxHeight()
            .width(48.dp)
    ) {
        val thumbHeight = 28.dp
        val travel = (maxHeight - thumbHeight).coerceAtLeast(0.dp)
        Box(
            Modifier
                .offset(y = travel * fraction.coerceIn(0f, 1f))
                .align(Alignment.TopCenter)
                .width(16.dp)
                .height(thumbHeight)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

// ------------------------------------------------------------ bottom bar

/** Donor ReaderBottomBar over the user's configured button set
 * (R-05). WebView/Browser entries are filtered out (invisible-
 * engine rule). The settings gear is fixed, as in the donor. */
@Composable
fun ReaderBottomBar(
    buttons: Set<String>,
    cropBordersActive: Boolean,
    isPager: Boolean,
    showShiftDoublePages: Boolean,
    onViewChapters: () -> Unit,
    onShare: () -> Unit,
    onReadingMode: () -> Unit,
    onRotation: () -> Unit,
    onToggleCropBorders: () -> Unit,
    onPageLayout: () -> Unit,
    onShiftDoublePages: () -> Unit,
    onToggleExhUtils: () -> Unit,
    onSettings: () -> Unit
) {
    Surface(color = BarColor) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            if (ReaderBottomButton.ViewChapters.isIn(buttons)) {
                IconButton(onClick = onViewChapters) {
                    Icon(Icons.Filled.Bookmarks, "Chapters", tint = Color.White)
                }
            }
            if (ReaderBottomButton.Share.isIn(buttons)) {
                IconButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, "Share chapter", tint = Color.White)
                }
            }
            if (ReaderBottomButton.ReadingMode.isIn(buttons)) {
                IconButton(onClick = onReadingMode) {
                    Icon(Icons.Filled.MenuBook, "Reading mode", tint = Color.White)
                }
            }
            if (ReaderBottomButton.Rotation.isIn(buttons)) {
                IconButton(onClick = onRotation) {
                    Icon(Icons.Filled.ScreenRotation, "Rotation", tint = Color.White)
                }
            }
            val cropInSet = if (isPager) {
                ReaderBottomButton.CropBordersPager.isIn(buttons)
            } else {
                ReaderBottomButton.CropBordersWebtoon.isIn(buttons) ||
                    ReaderBottomButton.CropBordersContinuesVertical.isIn(buttons)
            }
            if (cropInSet) {
                IconButton(onClick = onToggleCropBorders) {
                    Icon(
                        if (cropBordersActive) Icons.Filled.CropFree else Icons.Filled.Crop,
                        "Crop borders",
                        tint = Color.White
                    )
                }
            }
            if (showShiftDoublePages) {
                IconButton(onClick = onShiftDoublePages) {
                    Icon(Icons.Filled.SwapHoriz, "Shift double pages", tint = Color.White)
                }
            } else if (ReaderBottomButton.PageLayout.isIn(buttons) && isPager) {
                IconButton(onClick = onPageLayout) {
                    Icon(Icons.Filled.MenuBook, "Page layout", tint = Color.White)
                }
            }
            IconButton(onClick = onToggleExhUtils) {
                Icon(Icons.Filled.Build, "Reader tools", tint = Color.White)
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, "Reader settings", tint = Color.White)
            }
        }
    }
}

// ------------------------------------------------------------ ExhUtils

/** Donor ExhUtils strip (R-10): auto-scroll toggle + interval,
 * Retry all, Boost page. */
@Composable
fun ExhUtilsStrip(
    autoScrollOn: Boolean,
    intervalSeconds: Float,
    onToggleAutoScroll: () -> Unit,
    onRetryAll: () -> Unit,
    onBoostPage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(color = BarColor, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            androidx.compose.material3.TextButton(onClick = onToggleAutoScroll) {
                Text(
                    if (autoScrollOn) {
                        "Auto-scroll on (${intervalSeconds.toInt()}s)"
                    } else {
                        "Auto-scroll"
                    },
                    color = Color.White
                )
            }
            androidx.compose.material3.TextButton(onClick = onRetryAll) {
                Text("Retry all", color = Color.White)
            }
            androidx.compose.material3.TextButton(onClick = onBoostPage) {
                Text("Boost page", color = Color.White)
            }
        }
    }
}

// ------------------------------------------------------------ indicator

/** Donor ReaderPageIndicator (R-14): the current page centred
 * at the bottom, stroked, in the theme's primary colour. */
@Composable
fun ReaderPageIndicator(
    currentPage: Int,
    totalPages: Int,
    modifier: Modifier = Modifier
) {
    if (totalPages <= 0) return
    val primary = MaterialTheme.colorScheme.primary
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            "${currentPage + 1} / $totalPages",
            color = primary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge.copy(
                shadow = Shadow(
                    color = Color.Black,
                    offset = Offset(1.5f, 1.5f),
                    blurRadius = 3f
                )
            )
        )
    }
}

// ------------------------------------------------------------ nav overlay

/** Donor ReaderNavigationOverlayView (R-15): the tap-zone
 * regions, coloured and labelled, drawn over the page. */
@Composable
fun NavigationOverlay(
    navigator: ViewerNavigation,
    modifier: Modifier = Modifier
) {
    val colors = mapOf(
        ViewerNavigation.NavigationRegion.MENU to Color(0xFF9E9E9E),
        ViewerNavigation.NavigationRegion.PREV to Color(0xFFE53935),
        ViewerNavigation.NavigationRegion.NEXT to Color(0xFF43A047),
        ViewerNavigation.NavigationRegion.LEFT to Color(0xFF1E88E5),
        ViewerNavigation.NavigationRegion.RIGHT to Color(0xFF8E24AA)
    )
    Box(modifier) {
        Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
            for (region in navigator.getRegions()) {
                val color = colors[region.type] ?: Color.Gray
                drawRect(
                    color = color.copy(alpha = 0.35f),
                    topLeft = Offset(
                        region.rectF.left * size.width,
                        region.rectF.top * size.height
                    ),
                    size = Size(
                        (region.rectF.right - region.rectF.left) * size.width,
                        (region.rectF.bottom - region.rectF.top) * size.height
                    )
                )
            }
        }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight()) {
            for (region in navigator.getRegions()) {
                val color = colors[region.type] ?: Color.Gray
                val centerX = (region.rectF.left + region.rectF.right) / 2f
                val centerY = (region.rectF.top + region.rectF.bottom) / 2f
                Text(
                    region.type.label,
                    color = color,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.offset(
                        x = maxWidth * centerX - 40.dp,
                        y = maxHeight * centerY - 12.dp
                    )
                )
            }
        }
    }
}
