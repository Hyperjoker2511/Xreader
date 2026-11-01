package app.xreader.data.library

import app.xreader.data.db.Book
import app.xreader.data.db.Chapter
import app.xreader.data.db.ReadingProgress
import app.xreader.data.extension.WorkGrouper
import app.xreader.domain.reader.ChapterDisplayOrder

/**
 * Progress-linking (Cycle 1, F3): the same novel on different
 * sites stays as SEPARATE Library entries — never merged.
 * What links them is READING PROGRESS: open Site Two's copy
 * and it resumes where Site One left off, by TRUE chapter
 * number, with read state following.
 *
 * This file is the pure core — grouping, positions and the
 * resume plan. All of it is decided here, over plain values,
 * so the safety rules are unit-testable without a database:
 *
 *  - Matching is the app's existing same-story machinery
 *    ([WorkGrouper] over [app.xreader.data.extension.WorkMatcher]):
 *    normalised title equal AND authors compatible. Links
 *    form only between books in the user's Library.
 *  - A refused pair ("Not the same book") is cut out of the
 *    sharing BOTH ways, at use time — progress never flows
 *    between the two entries again, even through a third
 *    copy both still match.
 *  - Resume is by TRUE chapter number — the number
 *    [ChapterDisplayOrder.numberOf] reads off the chapter
 *    itself, the same number the reader labels and the
 *    Continue pill print. Unnumbered chapters ride just
 *    after the numbered chapter they follow (the house
 *    rule); a book with no numbers at all is positioned by
 *    reading-order place.
 *  - The plan only ever moves a book FORWARD. A sibling at
 *    or behind this copy's own position changes nothing —
 *    the reader is never pulled backwards.
 *  - When the sibling's chapter number doesn't exist in
 *    this copy (numbering differs — split/merged chapters),
 *    the plan lands on the NEAREST chapter and says so
 *    ([ResumePlan.exact] = false) so the UI can show the
 *    plain-words notice. It never silently lands on
 *    chapter 1 or an unrelated chapter.
 */

/** A pair of book ids the user separated with "Not the
 * same book". Stored normalised (smaller id first), so one
 * entry refuses the pair in both directions. */
data class RefusedPair(val a: Long, val b: Long) {

    fun involves(bookId: Long): Boolean = bookId == a || bookId == b

    /** The other end of the pair, or null when [bookId]
     * isn't in it. */
    fun other(bookId: Long): Long? = when (bookId) {
        a -> b
        b -> a
        else -> null
    }

    /** Stable storage key ("12:34"). */
    fun key(): String = "$a:$b"

    companion object {
        fun of(first: Long, second: Long): RefusedPair =
            if (first < second) RefusedPair(first, second)
            else RefusedPair(second, first)

        fun fromKey(key: String): RefusedPair? {
            val parts = key.split(":")
            if (parts.size != 2) return null
            val a = parts[0].toLongOrNull() ?: return null
            val b = parts[1].toLongOrNull() ?: return null
            if (a == b) return null
            return of(a, b)
        }
    }
}

/** A reading position inside one book, in true-chapter-number
 * space: the chapter's own [number] plus the [fraction] of
 * the way through that chapter (0 = its first line). A
 * fraction of 1 means the chapter is finished — produced
 * only from read flags, and normalised by the planner. */
data class LinkPosition(
    val number: Float,
    val fraction: Float,
    /** The saved scroll fraction of the anchor chapter, when
     * the position came from a progress row — the reader's
     * own restore currency. Defaults to [fraction]. */
    val scrollFraction: Float = fraction
) {
    /** One scalar for "who is furthest" comparisons. Chapter
     * numbers order the book; the fraction only breaks the
     * tie inside one chapter. */
    val scalar: Float get() = number + fraction
}

/** Where a linked copy should resume, decided by the planner. */
data class ResumePlan(
    /** The chapter in THIS book to open. */
    val targetChapter: Chapter,
    /** Within-chapter fraction to restore — carried from the
     * sibling only on an [exact] number match (another site's
     * chapter text differs, so on a nearest match the chapter
     * starts at its top). */
    val fraction: Float,
    val scrollFraction: Float,
    /** True when this book has a chapter with the sibling's
     * exact number. False = numbering differs, the target is
     * the nearest chapter, and the UI must say so. */
    val exact: Boolean,
    /** The sibling book the winning position came from. */
    val sourceBookId: Long,
    /** This book's chapters (by id) that the carry-over marks
     * read: everything before the resumed point that isn't
     * read already. */
    val markReadIds: List<Long>
)

object ProgressLinker {

    /** Float slack for "same number" — chapter numbers are
     * parsed from decimal text on both sides, so equal
     * numbers arrive as equal floats; this only absorbs
     * representation dust. */
    const val EPS = 0.001f

    // ── Grouping ──────────────────────────────────────────

    /**
     * The copies of [target]'s work that share progress with
     * it: the same-story group (transitively matched by
     * title/author over [library]) minus the target itself
     * and minus every book refused against the target. The
     * refusal filter runs per target, so a refused pair never
     * exchanges progress in either direction even when a
     * third copy still matches both.
     *
     * [library] must already be the user's Library (the
     * caller filters membership and merged-away rows).
     */
    fun siblingsOf(
        target: Book,
        library: List<Book>,
        refused: Set<RefusedPair>
    ): List<Book> {
        if (library.none { it.id == target.id }) return emptyList()
        val group = WorkGrouper.group(library, { it.title }, { it.author })
            .firstOrNull { group -> group.copies.any { it.id == target.id } }
            ?: return emptyList()
        val refusedWithTarget = refused
            .mapNotNull { it.other(target.id) }
            .toSet()
        return group.copies.filter {
            it.id != target.id && it.id !in refusedWithTarget
        }
    }

    // ── Positions ─────────────────────────────────────────

    /**
     * Every chapter's effective number in [chapters],
     * keyed by chapter id: the chapter's own parsed number
     * where it has one; unnumbered chapters ride just after
     * the numbered chapter they precede/follow in reading
     * order (the [ChapterDisplayOrder] house rule). A list
     * with NO numbers at all is placed by reading-order
     * position (1, 2, 3…) — the only honest scale left, and
     * the one two unnumbered copies of the same book share.
     */
    fun effectiveNumbers(chapters: List<Chapter>): Map<Long, Float> {
        if (chapters.isEmpty()) return emptyMap()
        val ordered = ChapterDisplayOrder.ordered(chapters, newestFirst = false)
        val own = ordered.map { ChapterDisplayOrder.numberOf(it) }
        if (own.all { it == null }) {
            return ordered.mapIndexed { index, chapter ->
                chapter.id to (index + 1).toFloat()
            }.toMap()
        }
        val result = LinkedHashMap<Long, Float>(ordered.size)
        var last = 0f
        var run = 0
        ordered.forEachIndexed { index, chapter ->
            val number = own[index]
            if (number != null) {
                last = number
                run = 0
                result[chapter.id] = number
            } else {
                run++
                result[chapter.id] = last + 0.001f * run
            }
        }
        return result
    }

    /**
     * Where reading stands in one book: the saved progress
     * row when its chapter still resolves (a dangling pointer
     * is no position at all — the book page's own rule), else
     * the read flags: the last read chapter in reading order
     * counts as finished (fraction 1). Null when the book has
     * never been read.
     */
    fun positionOf(
        chapters: List<Chapter>,
        progress: ReadingProgress?
    ): LinkPosition? {
        if (chapters.isEmpty()) return null
        val numbers = effectiveNumbers(chapters)
        if (progress != null) {
            val anchor = chapters.firstOrNull { it.id == progress.chapterId }
            if (anchor != null) {
                val number = numbers[anchor.id] ?: return null
                return LinkPosition(
                    number,
                    progress.fraction.coerceIn(0f, 1f),
                    progress.scrollFraction.coerceIn(0f, 1f)
                )
            }
            // Dangling pointer: fall through to the read flags.
        }
        val ordered = ChapterDisplayOrder.ordered(chapters, newestFirst = false)
        val lastRead = ordered.lastOrNull { it.read } ?: return null
        val number = numbers[lastRead.id] ?: return null
        return LinkPosition(number, 1f)
    }

    // ── The plan ──────────────────────────────────────────

    /**
     * Decide where [targetChapters]' book should resume,
     * given its own [ownPosition] and every sibling's
     * position (book id → position). Null = change nothing:
     * no sibling positions, or the furthest sibling is not
     * ahead of this book's own position (never pull the
     * reader backwards, never rewrite an equal position).
     */
    fun plan(
        targetChapters: List<Chapter>,
        ownPosition: LinkPosition?,
        siblingPositions: Map<Long, LinkPosition>
    ): ResumePlan? {
        if (targetChapters.isEmpty() || siblingPositions.isEmpty()) return null
        val winner = siblingPositions.maxWithOrNull(
            compareBy<Map.Entry<Long, LinkPosition>> { it.value.scalar }
                .thenBy { it.key }
        ) ?: return null
        val source = winner.value
        if (ownPosition != null && source.scalar <= ownPosition.scalar + EPS) {
            return null
        }
        val numbers = effectiveNumbers(targetChapters)
        val ordered = ChapterDisplayOrder.ordered(
            targetChapters, newestFirst = false
        )

        // A finished chapter (fraction 1, from read flags)
        // resumes at the NEXT chapter's start; only when
        // there is no next chapter does it stay on the
        // finished one, at its end.
        var wanted = source.number
        var fraction = source.fraction
        var scrollFraction = source.scrollFraction
        if (fraction >= 1f) {
            val finishedAt = ordered.indexOfFirst {
                kotlin.math.abs((numbers[it.id] ?: Float.NaN) - wanted) <= EPS
            }
            val next = ordered.getOrNull(finishedAt + 1)
            if (finishedAt >= 0 && next != null) {
                wanted = numbers[next.id] ?: wanted
                fraction = 0f
                scrollFraction = 0f
            } else {
                fraction = 0.999f
                scrollFraction = 0.999f
            }
        }

        val exactChapter = ordered.firstOrNull {
            kotlin.math.abs((numbers[it.id] ?: Float.NaN) - wanted) <= EPS
        }
        val target: Chapter
        val exact: Boolean
        if (exactChapter != null) {
            target = exactChapter
            exact = true
        } else {
            // Numbering differs: the nearest chapter by
            // number; a tie lands on the LOWER chapter (the
            // reader must never be pushed past content they
            // haven't read). The caller surfaces the notice.
            target = ordered.minWithOrNull(
                compareBy<Chapter> {
                    kotlin.math.abs((numbers[it.id] ?: 0f) - wanted)
                }.thenBy { numbers[it.id] ?: 0f }
            ) ?: return null
            exact = false
            fraction = 0f
            scrollFraction = 0f
        }

        val targetNumber = numbers[target.id] ?: wanted
        val markRead = ordered.filter { chapter ->
            !chapter.read &&
                (numbers[chapter.id] ?: Float.MAX_VALUE) < targetNumber - EPS
        }.map { it.id }
        return ResumePlan(
            targetChapter = target,
            fraction = if (exact) fraction else 0f,
            scrollFraction = if (exact) scrollFraction else 0f,
            exact = exact,
            sourceBookId = winner.key,
            markReadIds = markRead
        )
    }
}
