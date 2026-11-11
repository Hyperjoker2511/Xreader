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
 * v1.5.7 (Komikku full): the donor's in-reader dialogs, adapted
 * from Komikku (eu.kanade.presentation.reader.ChapterListDialog,
 * ReaderPageActionsDialog, ReadingModeSelectDialog,
 * OrientationSelectDialog, components/ModeSelectionDialog):
 * the full chapter list (read state, date, live download state,
 * chapter-bookmark swipe/button, current-chapter scroll), the
 * long-press page actions, and the two radio select dialogs.
 * v1.5.8 (E2/E1): the Room chapter row now carries its
 * scanlator, so the chapter list renders it dot-separated with
 * the date exactly as the donor does; per-chapter download
 * ACTIONS in the list are the book page's job in Xreader, the
 * reader list shows state only.
 */
package app.xreader.komikku.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.xreader.data.db.Chapter
import app.xreader.data.db.DownloadState

// ------------------------------------------------------------ chapter list

/** Donor ChapterListDialog (R-11): the series' full chapter
 * list in a bottom sheet, auto-scrolled to the current chapter;
 * tap jumps (one load), swipe or the bookmark button toggles
 * the chapter bookmark. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderChapterListDialog(
    chapters: List<Chapter>,
    currentIndex: Int,
    bookmarkedChapterIds: Set<Long>,
    onSelect: (Int) -> Unit,
    onToggleBookmark: (Chapter) -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(chapters.size) {
        if (currentIndex in chapters.indices) {
            listState.scrollToItem(currentIndex)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "${chapters.size} chapters",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            LazyColumn(state = listState) {
                itemsIndexed(chapters, key = { _, chapter -> chapter.id }) { index, chapter ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value != SwipeToDismissBoxValue.Settled) {
                                onToggleBookmark(chapter)
                            }
                            false
                        }
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {},
                        content = {
                            ChapterRow(
                                chapter = chapter,
                                isCurrent = index == currentIndex,
                                bookmarked = chapter.id in bookmarkedChapterIds,
                                onClick = { onSelect(index) },
                                onToggleBookmark = { onToggleBookmark(chapter) }
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: Chapter,
    isCurrent: Boolean,
    bookmarked: Boolean,
    onClick: () -> Unit,
    onToggleBookmark: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                chapter.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (chapter.read) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1
            )
            // v1.5.8 (E2): date · scanlator, donor shape —
            // a segment renders only when its value exists.
            val meta = app.xreader.komikku.ScanlatorLine.format(
                chapter.dateText, chapter.scanlator
            )
            if (meta != null) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        when (chapter.downloadState) {
            DownloadState.READY -> Icon(
                Icons.Filled.CheckCircle, "Downloaded",
                tint = MaterialTheme.colorScheme.primary
            )
            DownloadState.DOWNLOADING, DownloadState.QUEUED -> Icon(
                Icons.Filled.Download, "Downloading",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            DownloadState.FAILED -> Icon(
                Icons.Filled.Error, "Download failed",
                tint = MaterialTheme.colorScheme.error
            )
            else -> {}
        }
        IconButton(onClick = onToggleBookmark) {
            Icon(
                if (bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                if (bookmarked) "Remove bookmark" else "Bookmark chapter"
            )
        }
    }
}

// ------------------------------------------------------------ page actions

/** Donor ReaderPageActionsDialog (R-12): long-press on a page.
 * With a spread, the same actions exist for the second page. */
@Composable
fun ReaderPageActionsDialog(
    pageLabel: String,
    secondPageLabel: String?,
    onSetAsCover: (second: Boolean) -> Unit,
    onCopy: (second: Boolean) -> Unit,
    onShare: (second: Boolean) -> Unit,
    onSave: (second: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pageLabel) },
        text = {
            Column {
                PageActionItems(second = false, onSetAsCover, onCopy, onShare, onSave)
                if (secondPageLabel != null) {
                    Text(
                        secondPageLabel,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                    )
                    PageActionItems(second = true, onSetAsCover, onCopy, onShare, onSave)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun PageActionItems(
    second: Boolean,
    onSetAsCover: (Boolean) -> Unit,
    onCopy: (Boolean) -> Unit,
    onShare: (Boolean) -> Unit,
    onSave: (Boolean) -> Unit
) {
    // v1.5.8 (I3): the rows ARE the PageActions model — exactly
    // the four donor actions, each invoking its live handler.
    val items = pageActionItems(
        onSetAsCover = { onSetAsCover(second) },
        onCopy = { onCopy(second) },
        onShare = { onShare(second) },
        onSave = { onSave(second) }
    )
    Column {
        items.forEach { item ->
            TextButton(
                onClick = item.onInvoke,
                modifier = Modifier.fillMaxWidth()
            ) { Text(item.label) }
        }
    }
}

// ------------------------------------------------------------ mode selection

/** Donor ModeSelectionDialog shell (R-06/R-07): a radio list. */
@Composable
fun ModeSelectionDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                itemsIndexed(options) { index, label ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        RadioButton(
                            selected = index == selectedIndex,
                            onClick = { onSelect(index) }
                        )
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
