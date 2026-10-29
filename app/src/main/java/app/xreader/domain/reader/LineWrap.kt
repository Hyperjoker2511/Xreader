package app.xreader.domain.reader

/**
 * A pure, font-free line breaker: the measuring model the pagination
 * tests run against (production paged mode measures real glyphs with
 * Compose's TextMeasurer and feeds the same [Paginator]).
 *
 * Widths are in abstract units: most characters cost 1, wide scripts
 * (CJK ideographs, Hangul, fullwidth forms) cost 2. Break rules follow
 * the scripts: Latin text breaks at spaces/hyphens and never splits a
 * word unless the word alone exceeds the line; CJK text may break
 * between any two characters (no word spaces exist to honour).
 */
object LineWrap {

    /** Display width of one character in abstract units. */
    fun charWidth(c: Char): Int = if (isWide(c)) 2 else 1

    /** CJK-wide ranges (BMP): ideographs, kana, Hangul, fullwidth. */
    fun isWide(c: Char): Boolean {
        val v = c.code
        return v in 0x1100..0x11FF || // Hangul Jamo
            v in 0x2E80..0x9FFF || // CJK radicals, kana, ideographs
            v in 0xA000..0xA4CF || // Yi
            v in 0xAC00..0xD7A3 || // Hangul syllables
            v in 0xF900..0xFAFF || // CJK compatibility ideographs
            v in 0xFE30..0xFE4F || // CJK compatibility forms
            v in 0xFF00..0xFF60 // fullwidth forms
    }

    /** True when a line may break immediately AFTER [c]. */
    private fun breakAfter(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '-' || isWide(c)

    /**
     * Breaks [text] into lines of at most [maxWidth] units. Returns
     * line ranges as (start, endExclusive) pairs into [text]; line
     * ends exclude trailing spaces, and the following line starts after
     * them — concatenating the ranges in order, skipping only spaces,
     * reproduces the input (the paginator's no-lost-text invariant is
     * tested through this).
     */
    fun breakLines(text: String, maxWidth: Int): List<Pair<Int, Int>> {
        require(maxWidth >= 1) { "maxWidth must be >= 1" }
        if (text.isEmpty()) return listOf(0 to 0)
        val lines = ArrayList<Pair<Int, Int>>()
        var lineStart = 0
        var i = 0
        var width = 0
        var lastBreak = -1 // index AFTER which a break is allowed
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') {
                lines += lineStart to i
                i++
                lineStart = i
                width = 0
                lastBreak = -1
                continue
            }
            val w = charWidth(c)
            if (width + w > maxWidth) {
                when {
                    // Break at the last opportunity inside this line.
                    lastBreak > lineStart -> {
                        var end = lastBreak
                        while (end > lineStart && text[end - 1] == ' ') end--
                        lines += lineStart to end
                        var next = lastBreak
                        while (next < text.length && text[next] == ' ') next++
                        lineStart = next
                        i = next
                    }
                    // No opportunity: the current char alone overflows
                    // (a wide char on a 1-unit line, or a hard split) —
                    // emit what we have; if the line is empty, take the
                    // single char so progress is guaranteed.
                    i > lineStart -> {
                        lines += lineStart to i
                        lineStart = i
                    }
                    else -> {
                        lines += lineStart to (i + 1)
                        i++
                        lineStart = i
                    }
                }
                width = 0
                lastBreak = -1
                continue
            }
            width += w
            i++
            if (breakAfter(c)) lastBreak = i
        }
        if (lineStart < text.length || lines.isEmpty()) {
            lines += lineStart to text.length
        }
        return lines
    }
}
