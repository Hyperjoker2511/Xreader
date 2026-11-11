/*
 * Copyright 2024 The Komikku Authors
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
 * FIX-18 (v1.5.2): THE SEAM between Xreader's pipeline and the
 * transplanted Komikku reader. Xreader side: a chapter's content is
 * a ChapterContent whose `pages` is a ChapterPages — the ordered
 * ChapterPage list (remote url + optional downloaded localFile)
 * every manga road already produces: the declarative engine, the
 * link learner, local CBZ imports, and the Keiyoushi player stack
 * (whose adapter stamps the source's Referer into
 * ChapterPages.referer). Donor side: the reader consumes
 * ReaderChapter, whose pages arrive through the PageLoader
 * contract (ChapterLoader orchestrates; PipelinePageLoader /
 * DownloadPageLoader bind the pipeline). This adapter builds the
 * chapters and the prev/current/next window; neither side's data
 * layer changes.
 */
package app.xreader.komikku.loader

import app.xreader.komikku.model.ReaderChapter
import app.xreader.komikku.model.ViewerChapters

object KomikkuPageAdapter {

    /** One chapter row → the donor's ReaderChapter (pages unloaded;
     * ChapterLoader fills them through the PageLoader contract). */
    fun toReaderChapter(
        chapterId: Long,
        title: String,
        lastPageRead: Int = 0,
        read: Boolean = false
    ): ReaderChapter = ReaderChapter(
        chapterId = chapterId,
        title = title,
        lastPageRead = lastPageRead,
        read = read
    )

    /** The donor's prev/current/next window over an ordered chapter
     * list; [chapters] are (id, title) in reading order. */
    fun viewerChapters(
        chapters: List<Pair<Long, String>>,
        currentChapterId: Long,
        lastPageRead: Int = 0,
        read: Boolean = false
    ): ViewerChapters? {
        val index = chapters.indexOfFirst { it.first == currentChapterId }
        if (index < 0) return null
        fun build(i: Int): ReaderChapter? {
            val (id, title) = chapters.getOrNull(i) ?: return null
            return toReaderChapter(id, title, lastPageRead, read)
        }
        return ViewerChapters(
            prevChapter = build(index - 1),
            currChapter = build(index) ?: return null,
            nextChapter = build(index + 1)
        )
    }
}
