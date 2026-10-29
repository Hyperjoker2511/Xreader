package app.xreader.domain.export

/**
 * The quote card's content layout (PLAN §8): a highlight becomes a
 * shareable card — quote lines, the book's title, chapter, author.
 * This is the pure half: text shaping and wrapping against a
 * caller-supplied width measurer (the Android renderer measures
 * with the real typeface; tests measure in characters). The
 * renderer only draws what this model decides.
 */
object QuoteCardLayout {

    const val CARD_WIDTH = 1080
    const val CARD_HEIGHT = 1350

    data class Model(
        val quoteLines: List<String>,
        val bookTitle: String,
        val chapterLabel: String,
        val author: String
    )

    /**
     * Greedy word wrap. [measure] returns a line's width in the
     * same units as [maxWidth]. Lines never split a word unless the
     * word alone exceeds the width (then it is hard-split).
     */
    fun wrap(text: String, maxWidth: Float, measure: (String) -> Float): List<String> {
        val words = text.replace(Regex("\\s+"), " ").trim().split(" ")
            .filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var current = ""
        fun fits(candidate: String): Boolean = measure(candidate) <= maxWidth
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (fits(candidate)) {
                current = candidate
                continue
            }
            if (current.isNotEmpty()) {
                lines += current
                current = ""
            }
            if (fits(word)) {
                current = word
            } else {
                // One word wider than the card: hard-split it.
                var rest = word
                while (!fits(rest) && rest.length > 1) {
                    var cut = rest.length - 1
                    while (cut > 1 && !fits(rest.substring(0, cut))) cut--
                    lines += rest.substring(0, cut)
                    rest = rest.substring(cut)
                }
                current = rest
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    /** Builds the card model, capping the quote at [maxLines] with
     * an ellipsis on the last kept line. */
    fun build(
        quote: String,
        bookTitle: String,
        chapterLabel: String,
        author: String,
        maxWidth: Float,
        measure: (String) -> Float,
        maxLines: Int = 12
    ): Model {
        var lines = wrap(quote, maxWidth, measure)
        if (lines.size > maxLines) {
            lines = lines.take(maxLines).toMutableList()
            val last = lines.last()
            lines[lines.lastIndex] = if (last.length > 2) {
                last.dropLast(2).trimEnd() + "…"
            } else {
                "$last…"
            }
        }
        return Model(
            quoteLines = lines,
            bookTitle = bookTitle,
            chapterLabel = chapterLabel,
            author = author
        )
    }
}
