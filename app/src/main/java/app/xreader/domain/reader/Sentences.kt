package app.xreader.domain.reader

/** One speakable/highlightable sentence: a char range of the chapter text. */
data class Sentence(val start: Int, val end: Int) {
    fun textIn(chapterText: String): String = chapterText.substring(start, end)
}

/**
 * Sentence segmentation for TTS + karaoke highlighting (PLAN §6).
 * Handles Latin terminators (. ! ? …), CJK terminators (。 ！ ？),
 * closing quotes/brackets after a terminator, decimals (3.14),
 * ellipses, and a small abbreviation set (Mr., Dr., St., vs., e.g.,
 * i.e.). Pure and unit-tested, including CJK prose.
 */
object SentenceSplitter {

    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "st", "jr", "sr", "prof", "hon",
        "vs", "etc", "e.g", "i.e", "a.m", "p.m", "u.s", "u.k"
    )

    private fun isTerminator(c: Char): Boolean =
        c == '.' || c == '!' || c == '?' || c == '…' ||
            c == '。' || c == '！' || c == '？'

    private fun isCloser(c: Char): Boolean =
        c == '"' || c == '\'' || c == '”' || c == '’' || c == ')' ||
            c == ']' || c == '}' || c == '」' || c == '』'

    fun split(text: String): List<Sentence> {
        if (text.isBlank()) return emptyList()
        val sentences = ArrayList<Sentence>()
        var start = 0
        // Skip leading whitespace of the first sentence.
        while (start < text.length && text[start].isWhitespace()) start++
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') {
                // A blank line (paragraph break) ends a sentence even
                // without terminal punctuation; a single newline does not.
                if (i + 1 < text.length && text[i + 1] == '\n') {
                    emit(sentences, text, start, i)
                    start = skipWhitespace(text, i + 2)
                    i = start
                    continue
                }
                i++
                continue
            }
            if (!isTerminator(c)) {
                i++
                continue
            }
            // Decimal point: digit '.' digit is not a boundary.
            if (c == '.' && i > 0 && i + 1 < text.length &&
                text[i - 1].isDigit() && text[i + 1].isDigit()
            ) {
                i++
                continue
            }
            // Ellipsis: "..." or "…" runs count as one terminator.
            var end = i + 1
            if (c == '.') {
                while (end < text.length && text[end] == '.') end++
            }
            // Abbreviation check for '.'/'…'-free single dots: the word
            // before the dot.
            if (c == '.' && end == i + 1) {
                val wordStart = wordStartBefore(text, i)
                val word = text.substring(wordStart, i).lowercase()
                if (word in ABBREVIATIONS) {
                    i = end
                    continue
                }
            }
            // Absorb closing quotes/brackets.
            var after = end
            while (after < text.length && isCloser(text[after])) after++
            // Boundary only if followed by whitespace or end of text
            // (so "word.Next" inside a token stays together), except
            // CJK terminators which need no following space.
            val cjk = c == '。' || c == '！' || c == '？'
            if (after >= text.length || text[after].isWhitespace() || cjk) {
                emit(sentences, text, start, after)
                start = skipWhitespace(text, after)
                i = start
                continue
            }
            i = after
        }
        emit(sentences, text, start, text.length)
        return sentences
    }

    private fun emit(out: ArrayList<Sentence>, text: String, from: Int, to: Int) {
        var s = from
        var e = to
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        if (s < e) out += Sentence(s, e)
    }

    private fun skipWhitespace(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun wordStartBefore(text: String, dotIndex: Int): Int {
        var i = dotIndex - 1
        while (i >= 0 && (text[i].isLetter() || text[i] == '.')) i--
        return i + 1
    }
}
