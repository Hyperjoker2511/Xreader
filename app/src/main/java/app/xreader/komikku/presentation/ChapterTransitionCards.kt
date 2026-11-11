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
 * v1.5.7 (Komikku full): adapted from Komikku
 * (eu.kanade.presentation.reader.ChapterTransition +
 * ui/reader/viewer/ReaderTransitionView) — the transition
 * cards that sit in the page flow: Finished/Next (or
 * Previous/Current) with chapter names, the missing-chapters
 * gap warning, and the end-of-series "No next chapter" card.
 * v1.5.8 (I2/E1): ReaderChapter now carries the scanlator the
 * extension supplied (mapped by the ViewModel from the Room
 * column), so the cards render it exactly as the donor's
 * ChapterTransition does; the downloaded check reads the
 * chapter's loader (a local loader means the chapter is on
 * disk). Strings are literals, per the port's adaptation
 * convention.
 */
package app.xreader.komikku.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.xreader.komikku.loader.DownloadPageLoader
import app.xreader.komikku.model.ChapterTransition
import app.xreader.komikku.model.ReaderChapter
import app.xreader.komikku.model.calculateChapterGap

@Composable
fun ChapterTransitionCard(
    transition: ChapterTransition,
    onCross: () -> Unit
) {
    val isNext = transition is ChapterTransition.Next
    val from = transition.from
    val to = transition.to
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isNext) {
            Text(
                "Finished",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            ChapterLine(from)
            val gap = calculateChapterGap(higher = to, lower = from)
            if (to != null && gap > 0) {
                Spacer(Modifier.height(16.dp))
                Text(
                    if (gap == 1) {
                        "1 chapter is missing between this and the next chapter."
                    } else {
                        "$gap chapters are missing between this and the next chapter."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(32.dp))
            if (to != null) {
                Text(
                    "Next",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                ChapterLine(to)
                Spacer(Modifier.height(24.dp))
                TextButton(onClick = onCross) { Text("Read next chapter") }
            } else {
                Text(
                    "No next chapter",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        } else {
            if (to != null) {
                Text(
                    "Previous",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                ChapterLine(to)
                Spacer(Modifier.height(24.dp))
                TextButton(onClick = onCross) { Text("Read previous chapter") }
            } else {
                Text(
                    "No previous chapter",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(32.dp))
            Text(
                "Current",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            ChapterLine(from)
        }
    }
}

@Composable
private fun ChapterLine(chapter: ReaderChapter) {
    val downloaded = chapter.pageLoader is DownloadPageLoader
    Text(
        chapter.title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center
    )
    // v1.5.8 (I2): the donor's scanlator line — present only
    // when the chapter carries one (no empty segment).
    app.xreader.komikku.ScanlatorLine.scanlatorSegment(chapter.scanlator)
        ?.let { scanlator ->
            Text(
                scanlator,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    if (downloaded) {
        Text(
            "Downloaded",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
