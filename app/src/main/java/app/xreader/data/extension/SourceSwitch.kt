package app.xreader.data.extension

import app.xreader.extensionapi.SourceChapter

/**
 * Deliberate source switching / "Move this book to another source"
 * (v1.0.2, R11 + sweep migration add). The preview is computed
 * BEFORE anything changes: how many chapters match on the new
 * source, which ones don't, and exactly where reading progress
 * would land. Progress maps only to the same or an earlier chapter
 * and never moves backwards (G04). Downloaded files stay attached
 * to the old source entry — the preview says so; nothing pretends
 * they transferred.
 *
 * Pure: inputs are the two canonical chapter lists (as source
 * chapters) and the current progress position.
 */
data class SwitchPreview(
    val matchedChapters: Int,
    val unmatchedOldChapters: List<Int>,
    val newSourceChapters: Int,
    /** Where progress lands on the new list; -1 = start. */
    val progressChapterIndex: Int,
    /** True when progress landed on an earlier chapter than the
     * exact one (the match was approximate). */
    val progressApproximate: Boolean,
    val canSwitch: Boolean
) {
    fun summaryLine(): String {
        val base = "$matchedChapters of ${matchedChapters + unmatchedOldChapters.size} " +
            "chapters found on the new source"
        return when {
            !canSwitch -> "This book could not be matched on the new source."
            progressApproximate ->
                "$base. Your place moves to the nearest earlier chapter."
            else -> "$base. Your place is kept."
        }
    }
}

object SourceSwitchPlanner {

    fun preview(
        oldChapters: List<SourceChapter>,
        newChapters: List<SourceChapter>,
        progressChapterIndex: Int,
        progressCharOffset: Int
    ): SwitchPreview {
        if (oldChapters.isEmpty() || newChapters.isEmpty()) {
            return SwitchPreview(
                matchedChapters = 0,
                unmatchedOldChapters = oldChapters.indices.toList(),
                newSourceChapters = newChapters.size,
                progressChapterIndex = -1,
                progressApproximate = false,
                canSwitch = false
            )
        }
        val matches = ChapterMatcher.match(oldChapters, newChapters)
            .filter { it.confidence >= ChapterMatcher.AUTO_THRESHOLD }
        val matchedOld = matches.map { it.primaryIndex }.toSet()
        val unmatched = oldChapters.indices.filter { it !in matchedOld }

        // Progress: exact match wins; otherwise the nearest EARLIER
        // matched chapter; otherwise the start. Never later than
        // the user's true position, never backwards in the old
        // book's terms (an earlier chapter on the new list is the
        // conservative landing).
        var progressIndex = -1
        var approximate = false
        if (progressChapterIndex >= 0) {
            val byOld = matches.associate { it.primaryIndex to it.altIndex }
            val exact = byOld[progressChapterIndex]
            if (exact != null) {
                progressIndex = exact
            } else {
                val earlier = matches
                    .filter { it.primaryIndex < progressChapterIndex }
                    .maxByOrNull { it.primaryIndex }
                if (earlier != null) {
                    progressIndex = earlier.altIndex
                    approximate = true
                }
            }
        }
        return SwitchPreview(
            matchedChapters = matches.size,
            unmatchedOldChapters = unmatched,
            newSourceChapters = newChapters.size,
            progressChapterIndex = progressIndex,
            progressApproximate = approximate,
            canSwitch = matches.isNotEmpty()
        )
    }
}
