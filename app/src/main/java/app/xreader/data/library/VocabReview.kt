package app.xreader.data.library

import app.xreader.data.db.VocabState
import app.xreader.data.db.VocabWord

/**
 * Spaced review for "Words I'm learning" (v1.0.2, sweep addendum —
 * KOReader Vocabulary Builder's shape): a saved word comes back
 * after a growing interval when you remember it, and soon when you
 * don't. Pure: given a word row and the user's answer, produce the
 * next row. The ladder (days): 0 → 1 → 2 → 4 → 7 → 14 → 30; a word
 * whose interval reaches 14 days graduates to KNOWN and leaves the
 * review queue (it stays in the word list, removable as always).
 */
object VocabReview {

    private val LADDER_DAYS = listOf(0.0, 1.0, 2.0, 4.0, 7.0, 14.0, 30.0)
    private const val DAY_MS = 24.0 * 60 * 60 * 1000

    /** A fresh save: due immediately (it appears in today's review). */
    fun initialDue(nowMillis: Long): Long = nowMillis

    fun review(word: VocabWord, remembered: Boolean, nowMillis: Long): VocabWord {
        return if (remembered) {
            val nextInterval = nextInterval(word.intervalDays)
            val graduated = nextInterval >= 14.0 && word.state != VocabState.NEW
            word.copy(
                state = if (graduated) VocabState.KNOWN else VocabState.LEARNING,
                intervalDays = nextInterval,
                dueAt = nowMillis + (nextInterval * DAY_MS).toLong(),
                updatedAt = nowMillis
            )
        } else {
            word.copy(
                state = if (word.state == VocabState.KNOWN) VocabState.LEARNING
                else word.state,
                intervalDays = 1.0,
                // Forgotten words come back tomorrow, not never —
                // and not in the same session's face either.
                dueAt = nowMillis + DAY_MS.toLong(),
                updatedAt = nowMillis
            )
        }
    }

    private fun nextInterval(current: Double): Double {
        val idx = LADDER_DAYS.indexOfFirst { it > current }
        return if (idx == -1) LADDER_DAYS.last() else LADDER_DAYS[idx]
    }

    /** Words due for review now, oldest-due first. */
    fun dueWords(words: List<VocabWord>, nowMillis: Long): List<VocabWord> =
        words.filter { it.state != VocabState.KNOWN && it.dueAt <= nowMillis }
            .sortedBy { it.dueAt }
}
