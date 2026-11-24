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
 * Code 121 (W6, design §3.8 S13/S35): the manga reader's chrome
 * in the Material 3 design — the app's ONE reader-chrome family
 * (the same family as R28's M3ReaderChromeBar): a small top app
 * bar (back · manga title · "Chapter N · title" beneath ·
 * bookmark toggle) and a floating toolbar carrying the chapter
 * list door, the page slider with its page chip, the
 * reading-mode door and the settings door. Scheme-toned
 * throughout; pager and webtoon wear it identically.
 *
 * PRESENTATION ONLY. Every callback lands on the exact handler
 * the shipped (Nocturne) chrome uses — seek on slider release
 * with a haptic tick per page (the donor ChapterNavigator's
 * contract), chapter doors on the ViewModel, doors on the
 * screen's sheet state. The Komikku engine — page loading,
 * caching, the pager/webtoon rendering — is not touched, and
 * when the Nocturne design is active none of this composes:
 * ReaderChrome.kt's shipped bars render instead.
 */
package app.xreader.komikku.presentation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.xreader.ui.theme.LocalDesignState

// ------------------------------------------------------------ top bar

/** S13's small top app bar: back, the manga title with the
 * chapter identity beneath it, and the current chapter's
 * bookmark toggle — the same data and handlers as the shipped
 * [ReaderTopBar], in the family form (R28's M3 top bar). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun M3MangaTopBar(
    bookTitle: String,
    chapterTitle: String,
    chapterBookmarked: Boolean,
    onBack: () -> Unit,
    onToggleBookmark: () -> Unit
) {
    TopAppBar(
        title = {
            androidx.compose.foundation.layout.Column {
                Text(
                    bookTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (chapterTitle.isNotBlank()) {
                    Text(
                        chapterTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
            IconButton(onClick = onToggleBookmark) {
                Icon(
                    if (chapterBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    if (chapterBookmarked) "Remove chapter bookmark" else "Bookmark chapter",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    )
}

// ------------------------------------------------------------ floating toolbar

/**
 * S13's floating toolbar: chapter-list door · previous chapter ·
 * the page slider with the page chip ("1 / 24") · next chapter ·
 * reading-mode door · reader-tools door · settings door.
 *
 * The chapter doors ride the slider's ends (the R28 seek-row
 * pattern) so S13's must-carry chapter previous/next survives in
 * the one toolbar. The reader-tools door opens the same
 * ExhUtils strip the shipped bottom bar carries (auto-scroll,
 * retry all, boost page) — no shipped door is lost in this
 * design. The page chip obeys the S13 Show/Hide knob
 * ([app.xreader.data.prefs.DesignState.mangaPageChip]).
 *
 * Seek contract is the donor navigator's: the slider previews
 * with a haptic tick per page and the seek lands on release.
 * For a 1-page chapter the slider segment hides (the donor
 * navigator's rule); the doors stay.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
    modifier: Modifier = Modifier
) {
    val showPageChip = LocalDesignState.current.mangaPageChip
    val haptics = LocalHapticFeedback.current
    var sliderValue by remember(currentPage) {
        mutableFloatStateOf((currentPage + 1).toFloat())
    }
    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        content = {
            IconButton(onClick = onViewChapters) {
                Icon(Icons.AutoMirrored.Filled.List, "Chapters")
            }
            if (totalPages > 1) {
                IconButton(
                    onClick = onPreviousChapter,
                    enabled = hasPreviousChapter,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Filled.SkipPrevious, "Previous chapter")
                }
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
                    onValueChangeFinished = { onSeekPage(sliderValue.toInt() - 1) },
                    valueRange = 1f..totalPages.toFloat(),
                    steps = (totalPages - 2).coerceAtLeast(0),
                    modifier = Modifier.weight(1f)
                )
                if (showPageChip) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Text(
                            "${sliderValue.toInt()} / $totalPages",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
                IconButton(
                    onClick = onNextChapter,
                    enabled = hasNextChapter,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Filled.SkipNext, "Next chapter")
                }
            }
            IconButton(onClick = onReadingMode) {
                Icon(Icons.Filled.MenuBook, "Reading mode")
            }
            // Code 122 (audit #26) — DEVIATION KEPT, by the
            // no-silent-loss rule: this door is NOT removed.
            // The strip it opens is the ONLY door to Retry
            // all and Boost page (KomikkuReaderViewModel
            // .retryAllErrored / .boostCurrentPage are called
            // from the strips alone) and to the auto-scroll
            // toggle in this design; removing the door would
            // silently lose all three destinations.
            IconButton(onClick = onToggleExhUtils) {
                Icon(Icons.Filled.Build, "Reader tools")
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, "Reader settings")
            }
        }
    )
}

/**
 * Code 122 (S13, audit #26): the centred page indicator for
 * the M3 chrome — the donor ReaderPageIndicator's seat
 * (centred, over the page) in scheme tones. It honours the
 * "Show page number" preference: the host passes the pref's
 * live value as [showPageNumber] and the indicator draws
 * only when it is on (and the chapter has pages).
 *
 * HOST WIRING STILL NEEDED (KomikkuReaderScreen, outside
 * this file): the screen's indicator site is currently
 * `if (!m3 && showPageNumber && state.pageCount > 0)` — the
 * M3 branch must compose this indicator at the same
 * BottomCenter seat when showPageNumber is on.
 */
@Composable
fun M3CentredPageIndicator(
    currentPage: Int,
    totalPages: Int,
    showPageNumber: Boolean,
    modifier: Modifier = Modifier
) {
    if (!showPageNumber || totalPages <= 0) return
    androidx.compose.foundation.layout.Box(
        modifier,
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Text(
            "${currentPage + 1} / $totalPages",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
    }
}

/** The ExhUtils strip in scheme tones (S13's reader-tools door):
 * the same three actions the shipped strip carries —
 * auto-scroll toggle + interval, Retry all, Boost page — over a
 * scheme surface instead of the donor's black slab. */
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
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly
        ) {
            androidx.compose.material3.TextButton(onClick = onToggleAutoScroll) {
                Text(
                    if (autoScrollOn) {
                        "Auto-scroll on (${intervalSeconds.toInt()}s)"
                    } else {
                        "Auto-scroll"
                    }
                )
            }
            androidx.compose.material3.TextButton(onClick = onRetryAll) {
                Text("Retry all")
            }
            androidx.compose.material3.TextButton(onClick = onBoostPage) {
                Text("Boost page")
            }
        }
    }
}
