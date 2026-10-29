package app.xreader.domain.reader

/**
 * Bionic reading (PLAN §2.3): the first part of each word is bolded so
 * the eye fixates faster. Pure transform, unit-tested.
 *
 * Rule: bold the first ceil(len/2) letters of each word's letter run
 * (a 1-letter word bolds fully, a 4-letter word bolds 2, a 7-letter
 * word bolds 4). Punctuation and digits stay unbolded, attached to
 * whichever side they fall on.
 */
object BionicReading {

    /** (bold, rest) for one whitespace-free token. */
    fun splitToken(token: String): Pair<String, String> {
        val letterCount = token.count { it.isLetter() }
        if (letterCount == 0) return "" to token
        val boldLetters = (letterCount + 1) / 2
        val builder = StringBuilder()
        var seen = 0
        var cut = token.length
        for ((index, c) in token.withIndex()) {
            if (c.isLetter()) {
                seen++
                if (seen == boldLetters) {
                    cut = index + 1
                    break
                }
            }
        }
        builder.append(token.substring(0, cut))
        return builder.toString() to token.substring(cut)
    }

    /**
     * Transforms a whole string into render segments:
     * (text, isBold) runs that concatenate back to the input exactly.
     */
    fun segments(text: String): List<Pair<String, Boolean>> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Pair<String, Boolean>>()
        var i = 0
        fun push(segment: String, bold: Boolean) {
            if (segment.isEmpty()) return
            val last = out.lastOrNull()
            if (last != null && last.second == bold) {
                out[out.lastIndex] = (last.first + segment) to bold
            } else {
                out += segment to bold
            }
        }
        while (i < text.length) {
            if (text[i].isWhitespace()) {
                var j = i
                while (j < text.length && text[j].isWhitespace()) j++
                push(text.substring(i, j), false)
                i = j
            } else {
                var j = i
                while (j < text.length && !text[j].isWhitespace()) j++
                val (bold, rest) = splitToken(text.substring(i, j))
                push(bold, true)
                push(rest, false)
                i = j
            }
        }
        return out
    }
}
