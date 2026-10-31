package app.xreader.ui.reader

import app.xreader.data.extension.ChapterNumbers
import app.xreader.domain.content.ChapterContent
import kotlin.math.abs

/**
 * v1.1.2: the reader's counters, in one pure place.
 *
 * The reader historically kept TWO chapter numbering systems
 * and presented both as "the chapter number": the header pill
 * printed the chapter's source-given NAME (which embeds the
 * source's own numbering — a Live-derived manhwa chapter named
 * "Ch. 1 Vortex Scans · 1 month"), while the footer printed
 * the chapter's POSITION in the locally stored chapter list
 * ("Chapter 91 of 92"), and the percent/minutes followed the
 * position. Whenever a stored list's order diverges from the
 * source's numbering, the two flatly contradict each other on
 * the same screen.
 *
 * The position is the reader's native truth — saved progress,
 * the TOC, next/previous and the seek slider all speak it —
 * so both counters now derive from it through here. The
 * header keeps the chapter's name exactly as before whenever
 * the name does not contradict the position (the well-ordered
 * text-book case is byte-identical); a name whose own chapter
 * number disagrees with the position yields to the position
 * line, so header and footer can never tell two stories.
 */
object ReaderCounters {

    /** The footer's chapter line: pure position, as it has
     * always been computed — now shared so the header's
     * fallback text is the SAME string, not a lookalike. */
    fun footerLine(chapterIndex: Int, chapterCount: Int): String =
        if (chapterCount > 0) "Chapter ${chapterIndex + 1} of $chapterCount" else ""

    /** The header pill's chapter line. */
    fun headerLine(
        chapterTitle: String,
        chapterIndex: Int,
        chapterCount: Int
    ): String {
        val position = chapterIndex + 1
        val named = ChapterNumbers.parse(chapterTitle)
        // A fractional special (Ch. 10.5 at position 11) is the
        // same chapter the position means — only a full-step
        // disagreement is a contradiction.
        val conflicts = named != null && chapterCount > 0 &&
            abs(named - position) >= 1f
        return when {
            conflicts -> footerLine(chapterIndex, chapterCount)
            chapterTitle.isNotBlank() -> chapterTitle
            chapterCount > 0 -> "Chapter $position"
            else -> chapterTitle
        }
    }

    /** Chapters at or below this many words carry nothing a
     * words-per-minute estimate can honestly time — a comic
     * chapter's credit line, an author's note page. */
    const val MIN_TIMED_WORDS = 50

    /**
     * Whether the footer's time-left estimates may show for a
     * chapter with this content. Image chapters count ZERO
     * words by construction (ChapterContent.wordCount reads
     * only heading/paragraph text), so their "minutes left"
     * were pure invention — the estimator's per-chapter
     * fallback word count priced at the default speed. A
     * chapter with no timed words gets no time estimates;
     * its progress still shows as position + percent.
     */
    fun showTimeEstimates(content: ChapterContent?): Boolean =
        content != null && content.wordCount > MIN_TIMED_WORDS
}
