package app.xreader.data.extension

/**
 * Best source, dedupe, and safe switching (v1.0.2, R11 + the sweep's
 * source-migration add): the same work found on several sources is
 * ONE book card with a source-count chip; ranking is one preference
 * the user sets once; switching an existing library book is always
 * deliberate, previewed, and never moves progress backwards.
 *
 * All pure — the UI and the sync layer consume these results.
 */

/** One source's copy of a work, as ranking sees it. */
data class SourceCandidate(
    val extensionId: Long,
    val sourceName: String,
    val remoteId: String,
    val health: SourceHealthState = SourceHealthState.UNKNOWN,
    val downloadedChapters: Int = 0,
    val totalChapters: Int = 0,
    val latestChapterAtMillis: Long = 0,
    /** Last measured load time; null = unmeasured (ranks neutrally). */
    val loadMillis: Long? = null
)

/** The single ranking preference (Customise → Sources & repairs). */
enum class BestSourcePriority { DOWNLOADED_FIRST, MOST_CHAPTERS, NEWEST_CHAPTER, FASTEST }

object BestSourceRanker {

    private fun healthScore(health: SourceHealthState): Int = when (health) {
        SourceHealthState.WORKING -> 0
        SourceHealthState.WORKING_WITH_LIMITS -> 1
        SourceHealthState.UNKNOWN -> 2
        SourceHealthState.NEEDS_SETUP -> 3
        SourceHealthState.BROKEN -> 4
    }

    /**
     * Rank best-first. A BROKEN source always sinks below usable
     * ones (its downloads still read offline, but it cannot be the
     * "best" source for anything new). Among usable sources the
     * chosen priority leads; the plan's default tie-break chain
     * (already downloaded → source health → newest chapter) orders
     * the rest, so the result is stable and explainable.
     */
    fun rank(
        candidates: List<SourceCandidate>,
        priority: BestSourcePriority = BestSourcePriority.DOWNLOADED_FIRST
    ): List<SourceCandidate> {
        val usable = compareBy<SourceCandidate> {
            if (it.health == SourceHealthState.BROKEN) 1 else 0
        }
        val comparator = usable.then(when (priority) {
            BestSourcePriority.DOWNLOADED_FIRST -> compareByDescending<SourceCandidate> {
                it.downloadedChapters
            }.thenBy { healthScore(it.health) }
                .thenByDescending { it.latestChapterAtMillis }

            BestSourcePriority.MOST_CHAPTERS -> compareByDescending<SourceCandidate> {
                it.totalChapters
            }.thenBy { healthScore(it.health) }
                .thenByDescending { it.downloadedChapters }

            BestSourcePriority.NEWEST_CHAPTER -> compareByDescending<SourceCandidate> {
                it.latestChapterAtMillis
            }.thenBy { healthScore(it.health) }
                .thenByDescending { it.downloadedChapters }

            BestSourcePriority.FASTEST -> compareBy<SourceCandidate> {
                it.loadMillis ?: Long.MAX_VALUE
            }.thenBy { healthScore(it.health) }
                .thenByDescending { it.downloadedChapters }
        })
        return candidates.sortedWith(
            comparator.thenBy { healthScore(it.health) }.thenBy { it.sourceName.lowercase() }
        )
    }

    fun best(
        candidates: List<SourceCandidate>,
        priority: BestSourcePriority = BestSourcePriority.DOWNLOADED_FIRST
    ): SourceCandidate? = rank(candidates, priority).firstOrNull()
}

/** Work identity for dedupe (R03/R11): normalised title + author.
 * Two listings are the same work when their normalised titles are
 * equal AND their authors are compatible (equal, or one blank —
 * a blank author is missing evidence, not contrary evidence). */
object WorkMatcher {

    fun normaliseTitle(title: String): String =
        title.lowercase()
            .replace(Regex("\\[[^\\]]*]"), " ")       // [bracketed tags]
            .replace(Regex("\\([^)]*\\)"), " ")        // (parentheticals)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .removePrefix("the ")

    fun normaliseAuthor(author: String): String =
        author.lowercase().replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ").trim()

    fun sameWork(titleA: String, authorA: String, titleB: String, authorB: String): Boolean {
        val ta = normaliseTitle(titleA)
        val tb = normaliseTitle(titleB)
        if (ta.isEmpty() || ta != tb) return false
        val aa = normaliseAuthor(authorA)
        val ab = normaliseAuthor(authorB)
        return aa.isEmpty() || ab.isEmpty() || aa == ab
    }
}

/** A deduped search/catalogue group: one card, N sources (R11/R15). */
data class WorkGroup<T>(
    val title: String,
    val author: String,
    val copies: List<T>
)

object WorkGrouper {
    fun <T> group(
        items: List<T>,
        title: (T) -> String,
        author: (T) -> String
    ): List<WorkGroup<T>> {
        val groups = mutableListOf<WorkGroup<T>>()
        for (item in items) {
            val t = title(item)
            val a = author(item)
            val existing = groups.firstOrNull {
                WorkMatcher.sameWork(it.title, it.author, t, a)
            }
            if (existing == null) {
                groups += WorkGroup(t, a, listOf(item))
            } else {
                val idx = groups.indexOf(existing)
                groups[idx] = existing.copy(copies = existing.copies + item)
            }
        }
        return groups
    }
}
