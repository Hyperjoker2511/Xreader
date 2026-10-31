package app.xreader.ui.reader

import app.xreader.data.db.Chapter
import app.xreader.domain.reader.WpmEstimator
import kotlin.math.ceil

/**
 * Pure reader policies for v1.2.0 (Wave 3, reader core).
 *
 * Everything in this file is free of Android/Compose/Room types so
 * the decisions can be pinned on the JVM: the ViewModel and the
 * composables keep the side effects, these objects keep the rules.
 */

/**
 * The comic near-end prefetch decision (USER-SPECIFIED policy,
 * 2026-10-31): while reading an IMAGE chapter, once the reader is
 * ~70% through it, warm the NEXT chapter through the direct read
 * path so the turn doesn't wait on the network.
 *
 * The policy is deliberately narrow — image chapters only, next
 * chapter only, one warm at a time, never a chapter that is
 * already stored — and it applies on EVERY performance tier: the
 * user stated the policy unconditionally, and because it is
 * next-only the cost is bounded to a single chapter however long
 * the book is. (The older queue-based neighbour prefetch keeps
 * its own tier gate; this direct-path warm does not inherit it.)
 */
object PrefetchPolicy {

    /** In-chapter fraction at which the next chapter warms. */
    const val NEAR_END_FRACTION = 0.7f

    fun shouldWarmNext(
        isImageChapter: Boolean,
        fraction: Float,
        hasNext: Boolean,
        nextStored: Boolean,
        inFlight: Boolean
    ): Boolean =
        isImageChapter &&
            fraction >= NEAR_END_FRACTION &&
            hasNext &&
            !nextStored &&
            !inFlight
}

/**
 * Progress-save coalescing (OPTIMIZATION-AUDIT finding #2): a
 * settled position used to schedule a Room write ~1.5 s later,
 * every time, and each write invalidates the whole-library Flows
 * behind the Library screen. This coalescer decides WHEN the next
 * write may land:
 *
 *  - a write is due on a SETTLE (no new position for
 *    [settleMs]) — but never sooner than [coarseMs] after the
 *    previous write, so a long scroll can't produce a write per
 *    screenful pause;
 *  - a write is due at most [coarseMs] after the position first
 *    became dirty, even if scrolling never settles, so continuous
 *    reading still persists periodically;
 *  - forced flushes (chapter change, reader pause/leave) bypass
 *    this entirely and are recorded via [markPersisted] — the
 *    FINAL stored position is always exact; coalescing only ever
 *    delays INTERMEDIATE values.
 *
 * Pure and clock-free: callers pass `now` in, tests drive it.
 */
class ProgressSaveCoalescer(
    private val settleMs: Long = SETTLE_MS,
    private val coarseMs: Long = COARSE_MS
) {

    /** When the last DB write landed; 0 = never. */
    var lastPersistMs: Long = 0L
        private set

    /** When the current unpersisted position first appeared. */
    private var dirtySinceMs: Long = -1L

    /** When the most recent position arrived. */
    private var lastPositionMs: Long = -1L

    val isDirty: Boolean get() = dirtySinceMs >= 0L

    /** A new position arrived at [nowMs]; returns the absolute
     * time at which the next write should fire. */
    fun onPosition(nowMs: Long): Long {
        if (dirtySinceMs < 0L) dirtySinceMs = nowMs
        lastPositionMs = nowMs
        // dirtySinceMs >= 0 at this point, so dueMs() cannot be
        // null; the fallback is the plain settle time, unreachable
        // in practice.
        return dueMs() ?: (nowMs + settleMs)
    }

    /** The absolute time the pending write should fire, or null
     * when nothing is dirty. */
    fun dueMs(): Long? {
        if (dirtySinceMs < 0L) return null
        val settleAt = lastPositionMs + settleMs
        val notBefore = if (lastPersistMs > 0L) {
            lastPersistMs + coarseMs
        } else {
            Long.MIN_VALUE
        }
        val hardCap = dirtySinceMs + coarseMs
        return minOf(maxOf(settleAt, notBefore), hardCap)
    }

    /** A write (scheduled or forced) landed at [nowMs]. */
    fun markPersisted(nowMs: Long) {
        lastPersistMs = nowMs
        dirtySinceMs = -1L
        lastPositionMs = -1L
    }

    companion object {
        /** Settle window: a position is "settled" once no newer
         * one has arrived for this long (the historical debounce). */
        const val SETTLE_MS = 1_500L

        /** Coarse cap: DB writes are at most this frequent while
         * reading (forced flushes excepted). */
        const val COARSE_MS = 20_000L
    }
}

/**
 * The chapter list, indexed ONCE per list change (audit #2's
 * O(rest of book) per-settle cost): an id→index map for O(1)
 * chapter lookups, plus suffix aggregates over word counts so the
 * "minutes left in book" estimate for ANY chapter position is
 * O(1) instead of a drop+map+scan of every later chapter on every
 * settled position.
 *
 * The remaining-minutes maths replicates
 * [WpmEstimator.estimateRemainingMinutes] over the chapters after
 * a position exactly: measured chapters (wordCount > 0, read or
 * not) set the average; unread unmeasured chapters count at that
 * average (or [WpmEstimator.FALLBACK_CHAPTER_WORDS] when the
 * suffix has no measured chapter); read chapters contribute
 * nothing. The equivalence is pinned by tests against the
 * estimator itself.
 */
class ChapterPositionIndex private constructor(
    private val indexById: Map<Long, Int>,
    private val suffixMeasuredCount: IntArray,
    private val suffixMeasuredWords: LongArray,
    private val suffixUnreadMeasuredWords: LongArray,
    private val suffixUnreadUnmeasuredCount: IntArray
) {

    val size: Int get() = indexById.size

    /** O(1) position of the chapter with [chapterId], or null. */
    fun indexOfChapter(chapterId: Long?): Int? =
        chapterId?.let { indexById[it] }

    /**
     * Minutes left in the chapters AFTER [chapterIndex], at
     * [wpm] — the same value
     * `WpmEstimator.estimateRemainingMinutes(chapters.drop(
     * chapterIndex + 1), wpm).first` produced, in O(1).
     */
    fun remainingMinutesAfter(chapterIndex: Int, wpm: Double): Int {
        if (wpm <= 0.0) return 0
        val from = (chapterIndex + 1).coerceIn(0, size)
        val measuredCount = suffixMeasuredCount[from]
        val average = if (measuredCount > 0) {
            (suffixMeasuredWords[from] / measuredCount).toInt()
        } else {
            WpmEstimator.FALLBACK_CHAPTER_WORDS
        }
        val unreadWords = suffixUnreadMeasuredWords[from] +
            suffixUnreadUnmeasuredCount[from].toLong() * average
        if (unreadWords <= 0L) return 0
        return ceil(unreadWords / wpm).toInt().coerceAtLeast(1)
    }

    companion object {
        val EMPTY = build(emptyList())

        fun build(chapters: List<Chapter>): ChapterPositionIndex {
            val n = chapters.size
            val indexById = HashMap<Long, Int>(n)
            val measuredCount = IntArray(n + 1)
            val measuredWords = LongArray(n + 1)
            val unreadMeasured = LongArray(n + 1)
            val unreadUnmeasured = IntArray(n + 1)
            for (i in n - 1 downTo 0) {
                val chapter = chapters[i]
                indexById[chapter.id] = i
                measuredCount[i] = measuredCount[i + 1]
                measuredWords[i] = measuredWords[i + 1]
                unreadMeasured[i] = unreadMeasured[i + 1]
                unreadUnmeasured[i] = unreadUnmeasured[i + 1]
                if (chapter.wordCount > 0) {
                    measuredCount[i] += 1
                    measuredWords[i] += chapter.wordCount
                }
                if (!chapter.read) {
                    if (chapter.wordCount > 0) {
                        unreadMeasured[i] += chapter.wordCount
                    } else {
                        unreadUnmeasured[i] += 1
                    }
                }
            }
            return ChapterPositionIndex(
                indexById, measuredCount, measuredWords,
                unreadMeasured, unreadUnmeasured
            )
        }
    }
}
