package app.xreader.domain.reader

import app.xreader.data.db.Chapter
import app.xreader.data.extension.ChapterNumbers

/**
 * Identity-keyed chapter display (v1.3.2, V120-CHECK items
 * 78c/79 — the check's most serious find).
 *
 * The device failure: a book whose STORED list runs
 * newest-first was displayed as stored under the "Oldest"
 * sort chip (the display never reordered against the stored
 * order), and the reader labelled by LIST POSITION — tapping
 * the row the user read as chapter 1 opened chapter 158
 * while the header and progress bar both said "Chapter 1 of
 * 170".
 *
 * The rule now: display order and labels derive from each
 * chapter's OWN parsed number/identity, never from list
 * position alone.
 *
 *  - [ordered] sorts by the chapters' own numbers whenever
 *    the numbers can carry the order (unique, near-complete
 *    — the same bar as the engine's merge); "Oldest" is
 *    ascending identity, "Newest" its mirror, whatever the
 *    stored order is. When numbers can't carry it, the
 *    display falls back to stored order, flipped when the
 *    stored trend itself runs descending under "Oldest".
 *  - [firstChapter] is the identity-earliest chapter — the
 *    Start Reading target — regardless of stored order.
 *  - [displayNumber] is the number a label may print for a
 *    chapter: its own, or null (the caller then falls back
 *    to position, the only honest label left).
 *
 * Pure and JVM-tested.
 */
object ChapterDisplayOrder {

    /** A chapter's own number: parsed from its title, then
     * its slug-shaped title, then its URL. */
    fun numberOf(chapter: Chapter): Float? =
        ChapterNumbers.parseAny(chapter.title, chapter.remoteId ?: "")

    /** The number a label prints for [chapter] — whole
     * numbers as integers ("158"), specials as written
     * ("10.5"); null when the chapter carries no number. */
    fun displayNumber(chapter: Chapter): String? {
        val number = numberOf(chapter) ?: return null
        val asInt = number.toInt()
        return if (number == asInt.toFloat()) asInt.toString() else number.toString()
    }

    /** The identity-earliest chapter (the Start target), or
     * the first stored row when no chapter carries a number. */
    fun firstChapter(chapters: List<Chapter>): Chapter? {
        if (chapters.isEmpty()) return null
        val numbered = chapters.mapNotNull { chapter ->
            numberOf(chapter)?.let { chapter to it }
        }
        if (numbered.isEmpty()) return chapters.first()
        return numbered.minByOrNull { it.second }?.first ?: chapters.first()
    }

    /** [chapters] in display order for the sort chip:
     * ascending identity for Oldest, descending for Newest,
     * against ANY stored order. */
    fun ordered(
        chapters: List<Chapter>,
        newestFirst: Boolean
    ): List<Chapter> {
        if (chapters.size < 2) return chapters
        val ascending = ascendingByIdentity(chapters)
        if (ascending != null) {
            return if (newestFirst) ascending.asReversed() else ascending
        }
        // Numbers can't carry the order: stored order is the
        // truth, flipped only when the stored trend itself
        // runs against the chip.
        val descending = storedRunsDescending(chapters)
        return when {
            newestFirst && !descending -> chapters.asReversed()
            !newestFirst && descending -> chapters.asReversed()
            else -> chapters
        }
    }

    /** Ascending by own number when the numbers can carry
     * the order (at least 3/4 of rows numbered, no repeats);
     * unnumbered rows ride just after the numbered row they
     * followed. Null when they can't. */
    private fun ascendingByIdentity(
        chapters: List<Chapter>
    ): List<Chapter>? {
        val numbers = chapters.map { numberOf(it) }
        val numbered = numbers.filterNotNull()
        if (numbered.size < (chapters.size * 3 + 3) / 4) return null
        if (numbered.distinct().size != numbered.size) return null
        val keyed = ArrayList<Pair<Float, Chapter>>(chapters.size)
        var last = 0f
        var run = 0
        chapters.forEachIndexed { index, chapter ->
            val number = numbers[index]
            if (number != null) {
                last = number
                run = 0
                keyed += number to chapter
            } else {
                run++
                keyed += (last + 0.001f * run) to chapter
            }
        }
        return keyed.sortedBy { it.first }.map { it.second }
    }

    /** The stored trend test (ChapterOrder's bar, on stored
     * rows): first number above the last, with at least
     * four fifths of the adjacent steps going down. */
    private fun storedRunsDescending(chapters: List<Chapter>): Boolean {
        val numbers = chapters.mapNotNull { numberOf(it) }
        if (numbers.size < 3) return false
        if (numbers.size < (chapters.size + 3) / 4) return false
        if (numbers.first() <= numbers.last()) return false
        var descending = 0
        var steps = 0
        for (i in 0 until numbers.lastIndex) {
            steps++
            if (numbers[i] > numbers[i + 1]) descending++
        }
        return steps > 0 && descending * 5 >= steps * 4
    }
}
