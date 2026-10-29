package app.xreader.data.extension

import app.xreader.extensionapi.SourceChapter

/**
 * Cross-source chapter matching (PLAN §9.1): when the same book lives
 * on two sources, match its chapters so a failed chapter can be healed
 * from the other source. Pure and conservative — a wrong match is
 * worse than no match, so auto-mapping needs real confidence:
 *
 * - same chapter number → 0.95 ("Chapter 12" is "Chapter 12")
 * - same normalised title → 0.80
 * - same position in both lists (only when counts are close) → 0.50
 *   (below the auto threshold; used only as a tie-break hint)
 *
 * Auto-add threshold: 0.6.
 */
object ChapterMatcher {

    const val AUTO_THRESHOLD = 0.6f

    data class Match(
        val primaryIndex: Int,
        val altIndex: Int,
        val confidence: Float
    )

    /** Greedy best-first matching: strongest pairs lock first. */
    fun match(
        primary: List<SourceChapter>,
        alternates: List<SourceChapter>
    ): List<Match> {
        val scored = mutableListOf<Match>()
        for (p in primary.indices) {
            for (a in alternates.indices) {
                val confidence = confidence(primary[p], alternates[a], primary, alternates, p, a)
                if (confidence > 0f) scored += Match(p, a, confidence)
            }
        }
        val usedPrimary = mutableSetOf<Int>()
        val usedAlt = mutableSetOf<Int>()
        val result = mutableListOf<Match>()
        for (match in scored.sortedByDescending { it.confidence }) {
            if (match.primaryIndex in usedPrimary || match.altIndex in usedAlt) continue
            usedPrimary += match.primaryIndex
            usedAlt += match.altIndex
            result += match
        }
        return result.sortedBy { it.primaryIndex }
    }

    fun confidence(
        primary: SourceChapter,
        alternate: SourceChapter,
        primaryList: List<SourceChapter>,
        altList: List<SourceChapter>,
        primaryIndex: Int,
        altIndex: Int
    ): Float {
        val primaryNumber = primary.number ?: ChapterNumbers.parse(primary.name)
        val altNumber = alternate.number ?: ChapterNumbers.parse(alternate.name)
        if (primaryNumber != null && altNumber != null) {
            return if (kotlin.math.abs(primaryNumber - altNumber) < 0.001f) 0.95f else 0f
        }
        if (normaliseTitle(primary.name) == normaliseTitle(alternate.name) &&
            normaliseTitle(primary.name).isNotEmpty()
        ) {
            return 0.80f
        }
        // Positional fallback — only meaningful when both lists are
        // the same book at similar completeness.
        val sizeRatio = if (maxOf(primaryList.size, altList.size) == 0) 0f
        else minOf(primaryList.size, altList.size).toFloat() /
            maxOf(primaryList.size, altList.size)
        if (primaryIndex == altIndex && sizeRatio >= 0.8f) return 0.50f
        return 0f
    }

    /** Chapter titles compare without their numbers/punctuation:
     * "Chapter 12: The Fall!" ≡ "the fall". */
    fun normaliseTitle(name: String): String =
        name.lowercase()
            .replace(Regex("(?i)chapter\\s*\\d+(\\.\\d+)?"), " ")
            .replace(Regex("(?i)\\bch\\.?\\s*\\d+(\\.\\d+)?"), " ")
            .replace(Regex("第\\s*\\d+\\s*[章话話节節回]"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
}
