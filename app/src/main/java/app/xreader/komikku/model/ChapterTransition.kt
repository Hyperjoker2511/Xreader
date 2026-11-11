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
 * v1.5.7 (Komikku full): transplanted from Komikku
 * (eu.kanade.tachiyomi.ui.reader.model.ChapterTransition +
 * viewer/MissingChapters.calculateChapterGap). Adaptations: the
 * port's ReaderChapter wraps (id, title) instead of the donor's
 * SQLDelight Chapter, so toString and the gap calculation read
 * the title; the gap itself is the donor domain service's rule
 * (whole chapters strictly between two consecutive chapter
 * numbers), with the number parsed from the title — the only
 * number Xreader's chapter row carries.
 */
package app.xreader.komikku.model

/** A transition item in the page flow (donor ChapterTransition):
 * rendered as the Finished/Next (or Previous/Current) card. */
sealed class ChapterTransition {

    abstract val from: ReaderChapter
    abstract val to: ReaderChapter?

    class Prev(
        override val from: ReaderChapter,
        override val to: ReaderChapter?
    ) : ChapterTransition()

    class Next(
        override val from: ReaderChapter,
        override val to: ReaderChapter?
    ) : ChapterTransition()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChapterTransition) return false
        if (from == other.from && to == other.to) return true
        if (from == other.to && to == other.from) return true
        return false
    }

    override fun hashCode(): Int {
        var result = from.hashCode()
        result = 31 * result + (to?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "${javaClass.simpleName}(from=${from.title}, to=${to?.title})"
    }
}

/** The chapter number a title carries ("Chapter 12.5", "Ch. 12",
 * a bare leading number), or null when it carries none. */
fun chapterNumberFromTitle(title: String?): Float? {
    if (title == null) return null
    val match = TITLE_NUMBER_REGEX.find(title) ?: return null
    val number = match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }
        ?: match.groupValues.getOrNull(2)
    return number?.toFloatOrNull()
}

private val TITLE_NUMBER_REGEX =
    Regex("""(?:chapter|ch\.?|episode|ep\.?)\s*(\d+(?:\.\d+)?)|^\s*(\d+(?:\.\d+)?)\b""", RegexOption.IGNORE_CASE)

/**
 * Donor calculateChapterGap: how many whole chapters are
 * missing between [higher] (the later chapter) and [lower]
 * (the earlier one). 0 when either number is unknown or the
 * chapters are consecutive.
 */
fun calculateChapterGap(higher: ReaderChapter?, lower: ReaderChapter?): Int {
    val higherNumber = chapterNumberFromTitle(higher?.title) ?: return 0
    val lowerNumber = chapterNumberFromTitle(lower?.title) ?: return 0
    val gap = (higherNumber - lowerNumber - 1).toInt()
    return if (gap > 0) gap else 0
}
