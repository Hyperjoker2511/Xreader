package app.xreader.data.parse

import android.content.Context
import app.xreader.data.prefs.ScriptMode
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import app.xreader.domain.content.TextSpan

/**
 * MR-6 Item 8 (SPEC-MR6-TXT-2026-11-25): Simplified↔Traditional
 * display-time conversion — Moon+'s `textJian2Fan` / `textFan2Jian`
 * in kind, applied to the display copy only; the text on disk
 * never changes.
 *
 * Table source (the spec's named source): **OpenCC
 * (BYVoid/OpenCC), Apache License 2.0** — its `STCharacters`
 * dictionary converted to the bundled asset
 * `assets/opencc/st_characters.txt`; the Traditional→Simplified
 * table is derived from it by first-value inversion
 * (`assets/opencc/ts_characters.txt`). Moon+'s proprietary
 * `simp.txt` / `trad.txt` were NOT lifted.
 *
 * Two deliberate, labelled narrowings, both forced by Xreader's
 * char-offset place/highlight model:
 * - Character tables only — OpenCC's phrase tables are excluded
 *   because phrase conversion changes string lengths, which would
 *   shift every stored char offset in the book.
 * - Only single-BMP-char pairs are kept, so conversion is strictly
 *   length-preserving: a converted display copy has exactly the
 *   same length as the original, and offsets never move.
 */
object SimplifiedTraditional {

    class Tables(
        val toTraditional: Map<Char, Char>,
        val toSimplified: Map<Char, Char>
    ) {
        fun isEmpty(): Boolean = toTraditional.isEmpty() && toSimplified.isEmpty()

        companion object {
            val EMPTY = Tables(emptyMap(), emptyMap())
        }
    }

    /** Parses the asset format: one `simplified<TAB>traditional`
     * pair per line, `#` lines are comments. Only pairs whose two
     * sides are each exactly one UTF-16 char are kept (the
     * length-preserving rule above). */
    fun parseTables(stText: String, tsText: String): Tables {
        fun parse(text: String): Map<Char, Char> {
            val map = LinkedHashMap<Char, Char>()
            for (line in text.lineSequence()) {
                if (line.isEmpty() || line.startsWith("#")) continue
                val tab = line.indexOf('\t')
                if (tab < 0) continue
                val left = line.substring(0, tab)
                val right = line.substring(tab + 1).trim()
                if (left.length != 1 || right.length != 1) continue
                if (left[0] != right[0]) map.putIfAbsent(left[0], right[0])
            }
            return map
        }
        // st_characters.txt lines are simplified<TAB>traditional;
        // ts_characters.txt lines are traditional<TAB>simplified.
        return Tables(
            toTraditional = parse(stText),
            toSimplified = parse(tsText)
        )
    }

    fun loadFromAssets(context: Context): Tables = try {
        val st = context.assets.open("opencc/st_characters.txt")
            .bufferedReader().use { it.readText() }
        val ts = context.assets.open("opencc/ts_characters.txt")
            .bufferedReader().use { it.readText() }
        parseTables(st, ts)
    } catch (e: Exception) {
        Tables.EMPTY
    }

    /** Converts [text] char by char. Length-preserving by
     * construction; returns the original string when nothing maps. */
    fun convert(text: String, mode: ScriptMode, tables: Tables): String {
        val map = when (mode) {
            ScriptMode.OFF -> return text
            ScriptMode.TO_TRADITIONAL -> tables.toTraditional
            ScriptMode.TO_SIMPLIFIED -> tables.toSimplified
        }
        if (map.isEmpty()) return text
        var changed = false
        val out = CharArray(text.length)
        for (i in text.indices) {
            val mapped = map[text[i]]
            if (mapped != null) {
                out[i] = mapped
                if (mapped != text[i]) changed = true
            } else {
                out[i] = text[i]
            }
        }
        return if (changed) String(out) else text
    }

    /**
     * Applies the conversion at the block-text level (the spec's
     * Item 8 seam: the chapter-load/display path, so EPUBs convert
     * too). Returns the SAME instance when nothing changed, so
     * callers can detect the no-op.
     */
    fun applyToContent(
        content: ChapterContent,
        mode: ScriptMode,
        tables: Tables
    ): ChapterContent {
        if (mode == ScriptMode.OFF || tables.isEmpty()) return content
        var changed = false
        fun mapText(text: String): String {
            val converted = convert(text, mode, tables)
            if (converted !== text) changed = true
            return converted
        }
        fun mapBlock(block: ChapterBlock): ChapterBlock = when (block) {
            is ChapterBlock.Heading -> block.copy(text = mapText(block.text))
            is ChapterBlock.Paragraph -> block.copy(
                spans = block.spans.map { span -> span.copy(text = mapText(span.text)) }
            )
            is ChapterBlock.FootnoteBox -> block.copy(
                entries = block.entries.map { entry ->
                    entry.copy(
                        linkName = mapText(entry.linkName),
                        blocks = entry.blocks.map { mapBlock(it) }
                    )
                }
            )
            else -> block
        }
        val blocks = content.blocks.map { mapBlock(it) }
        return if (changed) content.copy(blocks = blocks) else content
    }
}
