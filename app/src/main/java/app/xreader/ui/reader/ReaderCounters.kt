package app.xreader.ui.reader

import app.xreader.data.extension.ChapterNumbers
import app.xreader.domain.content.ChapterBlock
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

    /**
     * v1.3.2 (item 79): the IDENTITY footer line — the
     * chapter's OWN number against the book's count. This is
     * the line the reader shows whenever the open chapter
     * carries a number of its own: position is a storage
     * fact that a mis-ordered stored list can make a lie
     * (the device check opened chapter 158 under a "Chapter
     * 1 of 170" label computed from position), while the
     * chapter's own number is the chapter's identity and
     * agrees with its heading by construction. [ownNumber]
     * null (a chapter with no number anywhere) falls back to
     * the position line — the only honest label left.
     */
    fun footerLine(
        chapterIndex: Int,
        chapterCount: Int,
        ownNumber: Float?
    ): String {
        if (ownNumber == null) return footerLine(chapterIndex, chapterCount)
        if (chapterCount <= 0) return ""
        val asInt = ownNumber.toInt()
        val label = if (ownNumber == asInt.toFloat()) {
            asInt.toString()
        } else {
            ownNumber.toString()
        }
        return "Chapter $label of $chapterCount"
    }

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

    /**
     * v1.3.2 (item 79): the header line when the chapter's
     * identity is known. The title still shows when it
     * AGREES with the chapter's own number; when the title
     * and the identity disagree (a mislabelled row, a
     * foreign heading), the identity line wins — the header
     * must agree with the chapter the reader actually
     * opened, and the regression test pins exactly that.
     */
    fun headerLine(
        chapterTitle: String,
        chapterIndex: Int,
        chapterCount: Int,
        ownNumber: Float?
    ): String {
        if (ownNumber == null) {
            return headerLine(chapterTitle, chapterIndex, chapterCount)
        }
        val named = ChapterNumbers.parse(chapterTitle)
        val agrees = named != null && abs(named - ownNumber) < 1f
        return when {
            chapterTitle.isNotBlank() && (named == null || agrees) ->
                chapterTitle
            else -> footerLine(chapterIndex, chapterCount, ownNumber)
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

    /**
     * Whether this content is an image chapter for PROGRESS
     * purposes: it carries image blocks and no more timed
     * words than a credit line. Char offsets cannot measure
     * progress through such a chapter — image blocks occupy
     * zero-width ranges in the chapter text (ChapterText), so
     * an offset-derived fraction sits at ~0 no matter how much
     * of the strip has been read. For these chapters the
     * renderer's own progress (scroll fraction, page fraction)
     * is the only honest in-chapter fraction, and position
     * code must prefer it.
     */
    fun isImageChapter(content: ChapterContent?): Boolean =
        content != null &&
            content.wordCount <= MIN_TIMED_WORDS &&
            content.blocks.any { it is ChapterBlock.Image }
}
