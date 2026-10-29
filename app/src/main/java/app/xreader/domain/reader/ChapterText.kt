package app.xreader.domain.reader

import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent

/**
 * A chapter flattened to one string with stable char offsets — the
 * reader's universal coordinate system (PLAN §6: the locator is a char
 * offset, so changing font size or switching paged/scroll never loses
 * the reader's place).
 *
 * Prose blocks (headings, paragraphs) occupy their text's range; blocks
 * are joined by a blank line (`\n\n`, 2 chars that belong to no block).
 * Images and rules occupy a zero-width range at their position, so the
 * paginator can place them without disturbing prose offsets.
 */
class ChapterText(val content: ChapterContent) {

    /** [start, end) char range of one block inside [text]. */
    data class BlockRange(
        val blockIndex: Int,
        val start: Int,
        val end: Int,
        /** XHTML element id (Media Overlay addressing), if the block has one. */
        val blockId: String?
    )

    val text: String
    val ranges: List<BlockRange>

    init {
        val builder = StringBuilder()
        val built = ArrayList<BlockRange>(content.blocks.size)
        content.blocks.forEachIndexed { index, block ->
            if (index > 0) builder.append("\n\n")
            val start = builder.length
            when (block) {
                is ChapterBlock.Heading -> {
                    builder.append(block.text)
                    built += BlockRange(index, start, builder.length, block.id)
                }
                is ChapterBlock.Paragraph -> {
                    builder.append(block.text)
                    built += BlockRange(index, start, builder.length, block.id)
                }
                is ChapterBlock.Image -> built += BlockRange(index, start, start, null)
                is ChapterBlock.Rule -> built += BlockRange(index, start, start, null)
            }
        }
        text = builder.toString()
        ranges = built
    }

    val length: Int get() = text.length

    fun rangeOf(blockIndex: Int): BlockRange? = ranges.getOrNull(blockIndex)

    /** The block whose range contains [charOffset] (separators and
     * zero-width blocks resolve to the following prose block, then the
     * previous one). */
    fun blockIndexAt(charOffset: Int): Int {
        if (ranges.isEmpty()) return -1
        val clamped = charOffset.coerceIn(0, length)
        for (range in ranges) {
            if (clamped < range.end) return range.blockIndex
            if (clamped == range.end && range.end > range.start) return range.blockIndex
        }
        return ranges.last().blockIndex
    }

    /** Char offset where [blockIndex]'s text begins. */
    fun startOf(blockIndex: Int): Int = ranges.getOrNull(blockIndex)?.start ?: 0
}
