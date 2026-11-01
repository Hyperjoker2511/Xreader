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
 *    the numbers can carry the order (near-complete; equal
 *    numbers keep stored relative order); "Oldest" is
 *    ascending identity, "Newest" its mirror, whatever the
 *    stored order is. When numbers can't carry it, the
 *    fallback chain runs the stored trend, then the source
 *    sequence itself mirrored — never a silent no-op
 *    (v1.3.3, V132-CHECK 16/20).
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
     * against ANY stored order.
     *
     * v1.3.3 (V132-CHECK 16/20): THE FALLBACK CHAIN, in order —
     * a toggle that changes nothing is a broken toggle, and
     * WoopRead's road proved the old chain could silently
     * no-op: its stored rows defeated BOTH old guards (the
     * identity sort demanded perfectly distinct numbers; the
     * trend test demanded a parse quorum), so "Oldest" and
     * "Newest" both returned the stored list untouched and a
     * newest-first list wore the "Oldest" chip. The chain
     * now is:
     *  1. the chapters' own parsed numbers (the record's
     *     number, the title's, the URL's) — a stable sort,
     *     duplicates tolerated: equal numbers keep their
     *     stored relative order instead of vetoing the sort;
     *  2. the stored trend, when any numbers parse at all;
     *  3. the source sequence itself: the stored order IS
     *     one direction (ingest settles lists into reading
     *     order), the other chip is its exact reverse.
     * Every rung visibly reorders (or honestly mirrors), so
     * the two chips can never show the same list again. */
    fun ordered(
        chapters: List<Chapter>,
        newestFirst: Boolean
    ): List<Chapter> {
        if (chapters.size < 2) return chapters
        val ascending = ascendingByIdentity(chapters)
        if (ascending != null) {
            return if (newestFirst) ascending.asReversed() else ascending
        }
        // Some numbers parse but can't carry the order: the
        // stored trend still says which way the source's
        // sequence runs.
        val descending = storedRunsDescending(chapters)
        if (descending) {
            return if (newestFirst) chapters else chapters.asReversed()
        }
        if (chapters.any { numberOf(it) != null }) {
            // Numbers exist and the trend runs ascending (or
            // flat): stored order is the reading sequence.
            return if (newestFirst) chapters.asReversed() else chapters
        }
        // No numbers anywhere: the source sequence is the
        // only truth — Oldest reads it as stored (ingest
        // settles lists into reading order), Newest mirrors
        // it. Never a silent no-op.
        return if (newestFirst) chapters.asReversed() else chapters
    }

    /** Ascending by own number when the numbers can carry
     * the order (at least 3/4 of rows numbered); unnumbered
     * rows ride just after the numbered row they followed,
     * and rows sharing a number keep their stored relative
     * order (a stable sort — v1.3.3: duplicate numbers from
     * a dual-carrier list no longer veto the whole sort). */
    private fun ascendingByIdentity(
        chapters: List<Chapter>
    ): List<Chapter>? {
        val numbers = chapters.map { numberOf(it) }
        val numbered = numbers.filterNotNull()
        if (numbered.size < (chapters.size * 3 + 3) / 4) return null
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
