package app.xreader.domain.reader

import app.xreader.data.db.Chapter

/**
 * v1.7 Patch 2 (item 1a): the book-wide char map — the novel
 * engine's whole-book coordinate system.
 *
 * Holds the ordered chapter descriptors (id + word count) and,
 * per chapter, a char length and a base offset (the cumulative
 * sum of the lengths before it), so a chapter-local char offset
 * becomes a book offset and back:
 *
 *     bookOffset(index, local) = baseOf(index) + local
 *     locate(bookOffset)       = (index, local)
 *
 * COORDINATE SYSTEM: the map measures the DISPLAY text — the
 * [ChapterText] the renderers consume (the terms-applied copy
 * when reader terms are in force) — because every offset it
 * serves is reported against display text (`onPosition`
 * receives display offsets). Feeding sites pass the same
 * display lengths the renderers get; cache originals feed it
 * only when no terms copy exists (original == display then).
 *
 * EXACT vs ESTIMATED: a chapter's length is EXACT once its
 * display [ChapterText] has been built and fed in
 * ([setExactLength]); before that it is ESTIMATED, and the
 * estimate is replaced by the exact length when the chapter is
 * parsed. The estimator mirrors the tree's own doctrine
 * ([ChapterPositionIndex] in the UI layer): `wordCount`
 * defaults to 0 and is populated only when a body is stored,
 * so pricing unparsed chapters at `wordCount * 6` would price
 * most of them at 1 char and collapse `totalChars` toward the
 * parsed prefix. An unmeasured chapter is therefore priced at
 * the book's MEASURED AVERAGE chars per chapter (over the
 * chapters already exact); only when NO chapter is measured
 * yet does it fall back to `wordCount * 6` (min 1) per chapter.
 *
 * TIES: an offset exactly on a chapter boundary belongs to the
 * LATER chapter; `offset == totalChars` maps to the last
 * chapter's end; zero-length chapters own no offsets (skipped
 * by [locate], still addressable by index).
 *
 * REBUILDS: when the chapter list changes (a refresh adds or
 * removes chapters), [rebuild] produces the map for the new
 * descriptors with the exact lengths of still-present chapter
 * ids carried over. The model is in-memory only — book offsets
 * are derived, never persisted (the stored contract,
 * [app.xreader.data.db.ReadingProgress], is unchanged).
 *
 * Pure, no Android, no I/O. Fed by ReaderViewModel wherever a
 * display [ChapterText] is built (load, prefetch, stream).
 */
class BookDocument(chapters: List<Chapter>) {

    private var chapterIds: List<Long> = chapters.map { it.id }
    private var wordCounts: List<Int> = chapters.map { it.wordCount }

    /** Exact display lengths by chapter id — the rebuild carrier. */
    private val exactLengths = mutableMapOf<Long, Int>()

    // The laid-out prefix sums, recomputed lazily after any
    // exact-length change (an estimate moves when the measured
    // average moves, so bases are never cached across a feed).
    private var basesCache: IntArray? = null
    private var lengthsCache: IntArray? = null
    private var totalCache: Int = 0

    val chapterCount: Int get() = chapterIds.size

    /** Record [chapterId]'s exact DISPLAY length (chars).
     * Unknown ids are ignored — a feed can race a list change. */
    @Synchronized
    fun setExactLength(chapterId: Long, chars: Int) {
        if (chapterId !in chapterIds) return
        val length = chars.coerceAtLeast(0)
        if (exactLengths[chapterId] == length) return
        exactLengths[chapterId] = length
        basesCache = null
        lengthsCache = null
    }

    /** The map for a changed chapter list: descriptors from
     * [chapters], exact lengths carried over for the chapter
     * ids that are still present. */
    fun rebuild(chapters: List<Chapter>): BookDocument {
        val next = BookDocument(chapters)
        val surviving = synchronized(this) { exactLengths.toMap() }
        val ids = next.chapterIds.toSet()
        next.exactLengths.putAll(surviving.filterKeys { it in ids })
        return next
    }

    /** The length chapter [index] is currently priced at —
     * exact when measured, the estimate otherwise. 0 for an
     * out-of-range index. */
    fun lengthOf(index: Int): Int {
        if (index !in chapterIds.indices) return 0
        layout()
        return lengthsCache!![index]
    }

    /** True when chapter [index]'s length is measured, not
     * estimated. */
    fun isExact(index: Int): Boolean =
        chapterIds.getOrNull(index)?.let { it in exactLengths } ?: false

    /** The char offset at which chapter [index] starts. */
    fun baseOf(index: Int): Int {
        if (index !in chapterIds.indices) return 0
        layout()
        return basesCache!![index]
    }

    /** Total chars in the book at current prices. */
    val totalChars: Int
        get() {
            layout()
            return totalCache
        }

    /** The book offset of a chapter-local offset (the local
     * offset is clamped into the chapter's priced length). */
    fun bookOffset(index: Int, localOffset: Int): Int {
        if (index !in chapterIds.indices) return 0
        return baseOf(index) + localOffset.coerceIn(0, lengthOf(index))
    }

    /** The (chapter index, local offset) a book offset names.
     * Boundary offsets belong to the LATER chapter; an offset
     * at/past the end maps to the last chapter's end. */
    fun locate(bookOffset: Int): Pair<Int, Int> {
        if (chapterIds.isEmpty()) return 0 to 0
        layout()
        val bases = basesCache!!
        val lengths = lengthsCache!!
        val offset = bookOffset.coerceIn(0, totalCache)
        if (offset == totalCache) {
            val last = chapterIds.lastIndex
            return last to lengths[last]
        }
        for (i in chapterIds.indices) {
            if (lengths[i] <= 0) continue
            if (offset < bases[i] + lengths[i]) {
                return i to (offset - bases[i])
            }
        }
        val last = chapterIds.lastIndex
        return last to lengths[last]
    }

    /** (Re)compute bases + lengths when a feed invalidated them. */
    @Synchronized
    private fun layout() {
        if (basesCache != null) return
        val lengths = IntArray(chapterIds.size) { i ->
            exactLengths[chapterIds[i]] ?: estimateFor(i)
        }
        val bases = IntArray(chapterIds.size)
        var acc = 0
        for (i in lengths.indices) {
            bases[i] = acc
            acc += lengths[i]
        }
        lengthsCache = lengths
        basesCache = bases
        totalCache = acc
    }

    /** The price of an unmeasured chapter: the book's measured
     * average chars per chapter when anything is measured,
     * else the word-count fallback (min 1 so an unmeasured book
     * never collapses to zero). Only called for chapters with
     * no exact length, from [layout]. */
    private fun estimateFor(index: Int): Int {
        if (exactLengths.isNotEmpty()) {
            val sum = exactLengths.values.sumOf { it.toLong() }
            val count = exactLengths.size
            // Rounded average — the same "this book's own
            // average" the minutes-left index prices with.
            return ((sum + count / 2) / count).toInt()
        }
        return (wordCounts[index] * CHARS_PER_WORD).coerceAtLeast(1)
    }

    companion object {
        /** The stream planner's long-standing chars-per-word
         * estimate, used only when nothing is measured yet. */
        const val CHARS_PER_WORD = 6

        val EMPTY: BookDocument = BookDocument(emptyList())
    }
}
