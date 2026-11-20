package app.xreader.ui.reader

import app.xreader.data.extension.ChapterNumbers
import app.xreader.domain.content.ChapterBlock

/**
 * v1.7 Moon+ standard fix (Part 1): does a segment's own text
 * open with its own title? ONE kind-agnostic detector for
 * every book kind (A1: source HTML runs through the same
 * XhtmlConverter as EPUB XHTML, so novels can carry Heading
 * blocks too — there are no per-kind lanes).
 *
 * The scan's edges are pinned (A3):
 * - walk the blocks from the start; [ChapterBlock.Image] and
 *   [ChapterBlock.Rule] do NOT terminate the scan (the
 *   'Chapter 9' plate section opens with an image);
 * - the scan ends at the first text-bearing block that is
 *   not a Heading; a [ChapterBlock.Heading] found during
 *   the scan means the text opens with its own title;
 * - independently, among the first THREE text-bearing
 *   blocks, one whose trimmed text EQUALS the chapter
 *   title, EQUALS the reconciled header line, or is a bare
 *   "Chapter N" label whose N equals the segment's own
 *   number also means the text opens with its own title.
 *
 * Anything fuzzier is NOT a match — when in doubt the one
 * clean app header line shows (a single clean line is never
 * the doubled stack the owner rejected). Pure: the
 * ViewModel computes this at fill time from the segment's
 * ORIGINAL text (A2 — on display text a translated title
 * line never equals the row title, and the stack would
 * return in translated reading).
 */
object ChapterTitleDetection {

    /** The bare-label shape, strict: after normalisation
     * (trim, lowercase, one trailing '.' stripped) the whole
     * line must be "chapter"/"ch"/"ch." + a number. */
    private val BARE_LABEL =
        Regex("""^(chapter|ch\.?)\s*\d+(\.\d+)?$""")

    fun opensWithOwnTitle(
        blocks: List<ChapterBlock>,
        chapterTitle: String,
        headerLine: String,
        ownNumber: Int?
    ): Boolean {
        val title = chapterTitle.trim()
        val line = headerLine.trim()
        var textBearing = 0
        // The Heading scan runs until the first text-bearing
        // block that is not a Heading (the first Paragraph).
        var scanOpen = true
        for (block in blocks) {
            val text = when (block) {
                is ChapterBlock.Heading -> {
                    if (scanOpen) return true
                    block.text
                }
                is ChapterBlock.Paragraph -> {
                    scanOpen = false
                    block.text
                }
                // Image / Rule: not text-bearing, and they do
                // not terminate the scan.
                else -> continue
            }
            textBearing++
            if (textBearing <= 3) {
                val candidate = text.trim()
                if (candidate.isNotEmpty() &&
                    (candidate == title || candidate == line)
                ) {
                    return true
                }
                if (isBareChapterLabel(candidate, ownNumber)) {
                    return true
                }
            }
            // Past the third text-bearing block both windows
            // are closed: a Heading from here on was never
            // "during the scan" (a Paragraph already ended
            // it — a scan that met only Headings returned at
            // the first one).
            if (textBearing >= 3) break
        }
        return false
    }

    /** A bare "Chapter N" whose N is the segment's own
     * number. Nothing fuzzier matches: the normalised line
     * must fully match [BARE_LABEL] and its parsed number
     * must equal [ownNumber] exactly (10.5 is not 10). */
    private fun isBareChapterLabel(line: String, ownNumber: Int?): Boolean {
        if (ownNumber == null) return false
        val normalised = line.trim().lowercase().removeSuffix(".")
        if (!BARE_LABEL.matches(normalised)) return false
        val parsed = ChapterNumbers.parseAny(normalised, "")
            ?: return false
        return parsed == ownNumber.toFloat()
    }
}
