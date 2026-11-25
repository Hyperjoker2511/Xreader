package app.xreader.data.parse

import app.xreader.data.prefs.TxtLanguage
import app.xreader.data.prefs.TxtPrefs

/** One detected chapter boundary: the chapter's [title], the char
 * offset where its heading line starts ([lineStart]) and the char
 * offset where its body begins ([bodyStart], just past the heading
 * line's newline). Moon+'s `TxTChapter{chapter, position}` in kind. */
data class DetectedChapter(
    val title: String,
    val lineStart: Int,
    val bodyStart: Int
)

/**
 * MR-6 Item 4 (SPEC-MR6-TXT-2026-11-25 §1.3): the orchestration of
 * Moon+'s `getTxtChapters()` (A.java:4745-4795) — a MODE SWITCH,
 * not a vote:
 *
 * - `txt_toc_reg` ON → the regex detector only (Item 1).
 * - OFF → the heuristics only: detector 1 is the default winner;
 *   for Chinese books detector 2 replaces it only under Moon+'s
 *   exact rules (>110% for most winning lists; for
 *   `txtChapterType` 3 or 5, quoted titles are first subtracted
 *   from detector 2's count and the discounted count must beat
 *   2× detector 1 under 10 detector-1 chapters, 4× at 10 or more);
 *   for non-Chinese books the >10-gated pruning runs (threshold
 *   `max(10, average title length)`, titles over 2× removed).
 * - Both modes de-duplicate against the previous title.
 *
 * There is no run in which the regex and the heuristics co-run.
 */
object TxtChapterDetection {

    fun detect(text: String, settings: TxtPrefs.State): List<DetectedChapter> {
        if (text.isBlank()) return emptyList()
        val lines = splitTxtLines(text)
        val language = TxtChapterRegex.languageOf(text)
        if (settings.tocRegMode) {
            return TxtChapterRegex.detect(text, lines, language, settings)
        }
        val detector1 = TxtHeuristics.detector1(text, lines, language)
        var winner = detector1
        if (language == TxtLanguage.CN) {
            val detector2 = TxtHeuristics.detector2(text, lines)
            val size = detector1.size
            val wins = if (detector2.type == 3 || detector2.type == 5) {
                val quoted = detector2.chapters.count {
                    TxtHeuristics.isChapterTitleBothQuotes(it.title)
                }
                val discounted = detector2.chapters.size - quoted
                if (size < 10) discounted > 2 * size else discounted > 4 * size
            } else {
                // > 110% of detector 1's count, in integers.
                detector2.chapters.size * 10 > size * 11
            }
            if (wins) winner = detector2.chapters
        } else {
            winner = pruneNonChinese(detector1)
        }
        return dedupAgainstPrevious(winner)
    }

    /** Moon+'s non-Chinese pruning (A.java:4776-4790): only when
     * detector 1's count is over 10; the threshold is
     * `max(10, average title length)` in Moon+'s integer math,
     * and titles longer than twice it are removed. */
    private fun pruneNonChinese(list: List<DetectedChapter>): List<DetectedChapter> {
        if (list.size <= 10) return list
        val average = list.sumOf { it.title.length } / list.size
        val threshold = maxOf(10, average)
        return list.filter { it.title.length <= 2 * threshold }
    }

    private fun dedupAgainstPrevious(list: List<DetectedChapter>): List<DetectedChapter> {
        if (list.size < 2) return list
        val out = ArrayList<DetectedChapter>(list.size)
        for (chapter in list) {
            if (out.lastOrNull()?.title == chapter.title) continue
            out += chapter
        }
        return out
    }
}
