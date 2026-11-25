package app.xreader.data.parse

import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent

/**
 * MR-6 Item 9 (SPEC-MR6-TXT-2026-11-25): Moon+'s TXT trim options
 * in kind — "Trim blank lines and spaces" (`trimBlankSpace`) and
 * "Trim blank lines at the top of the page when turning pages"
 * (`trimTopSpace`) — applied at READ/RENDER time, never at import
 * (Moon+ applies them when chapter text is prepared for display,
 * `cleanHtml` / `getTxtDisplayText`; a change re-applies to
 * already-open books). The stored chapter text is written by
 * `TxtParser.textToContentPreserving` — blank paragraphs and
 * paragraph edge spacing survive import precisely so these
 * switches have the as-printed structure to act on.
 *
 * Deliberate narrowing, labelled per the spec: Moon+'s trim applies
 * to the whole text world; Xreader's two switches are TXT-only —
 * the caller gates them on the book being a TXT.
 *
 * Both default ON (see TxtPrefs): Xreader's shipped shape is the
 * trimmed one, and the spec's rule is "defaulting to current
 * behaviour so existing books do not change shape on update" —
 * ON is the value that honours it here. OFF shows the book
 * as printed.
 */
object TxtDisplayTransform {

    /**
     * Applies the two trims to a chapter's display copy. Returns
     * the SAME instance when neither trim changes anything.
     *
     * - [trimBlankLines]: blank paragraphs go, and each paragraph's
     *   leading/trailing spaces are trimmed (Moon+'s blank-space
     *   trim over non-HTML text).
     * - [trimPageTopBlanks]: blank paragraphs at the very top of
     *   the chapter go (the render-level equivalent of Moon+'s
     *   page-turn top trim — a chapter never opens on blank air).
     */
    fun applyTrims(
        content: ChapterContent,
        trimBlankLines: Boolean,
        trimPageTopBlanks: Boolean
    ): ChapterContent {
        if (!trimBlankLines && !trimPageTopBlanks) return content
        var blocks = content.blocks
        var changed = false
        if (trimPageTopBlanks) {
            val trimmed = blocks.dropWhile { it.isBlankParagraph() }
            if (trimmed.size != blocks.size) {
                blocks = trimmed
                changed = true
            }
        }
        if (trimBlankLines) {
            val out = ArrayList<ChapterBlock>(blocks.size)
            for (block in blocks) {
                when {
                    block.isBlankParagraph() -> changed = true
                    block is ChapterBlock.Paragraph -> {
                        val text = block.text
                        val trimmedText = text.trim()
                        when {
                            trimmedText == text -> out += block
                            block.spans.size == 1 && !block.spans[0].bold &&
                                !block.spans[0].italic ->
                                out += ChapterBlock.Paragraph.plain(trimmedText, block.id)
                                    .also { changed = true }
                            else -> out += block
                        }
                    }
                    else -> out += block
                }
            }
            blocks = out
        }
        return if (changed) content.copy(blocks = blocks) else content
    }

    private fun ChapterBlock.isBlankParagraph(): Boolean =
        this is ChapterBlock.Paragraph && text.isBlank()
}
