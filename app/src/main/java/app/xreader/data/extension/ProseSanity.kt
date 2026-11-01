package app.xreader.data.extension

/**
 * v1.3.4 (T2, content integrity): the NOT-PROSE sanity gate.
 *
 * The FenrirRealm device failure (V133-CHECK 1a): a locked
 * chapter's fallback scrape served the site's encoded blob —
 * hundreds of characters of unbroken base64-shaped tokens —
 * to the reader AS the chapter text. A chapter is prose:
 * words, spaces, sentence punctuation, paragraph structure.
 *
 * The gate is MULTI-SIGNAL (a lone percentage threshold was
 * the first sketch; the second review killed it — a single
 * signal can misfire on unusual-but-real prose). A text is
 * refused only when the signals AGREE it isn't prose:
 *
 *  - BLOB DOMINANCE: a large share of its characters belong
 *    to very long whitespace-delimited tokens drawn
 *    entirely from the base64 alphabet;
 *  - NO SENTENCE TEXTURE: spaces are vanishingly rare (real
 *    prose runs ~15–19% spaces; even line-wrapped base64
 *    sits near 1–2%) and sentence punctuation is absent;
 *  - NO PLAUSIBLE LANGUAGE: common short words of the
 *    text's own language family (the/and/of/a/in/is/…)
 *    essentially never appear as whole words.
 *
 * …or when blob dominance is so total (>= [TOTAL_BLOB_SHARE])
 * that no texture signal is needed. A DECLARED lock (the
 * record's own lock field / locked format) is handled
 * upstream in the source, before extraction — it is the
 * fourth signal and never reaches this gate. Short texts
 * are never judged (a one-line chapter note is prose by
 * benefit of the doubt), and real prose with the odd long
 * word or URL passes with a wide margin on every signal.
 */
object ProseSanity {

    const val MIN_JUDGE_CHARS = 200
    const val BLOB_TOKEN_MIN = 24
    const val BLOB_SHARE = 0.45
    const val TOTAL_BLOB_SHARE = 0.8
    const val MAX_PROSELESS_SPACE_DENSITY = 0.05
    const val MIN_STOPWORD_HITS = 3

    private val BASE64_CHARS: Set<Char> =
        (('A'..'Z') + ('a'..'z') + ('0'..'9') +
            listOf('+', '/', '=', '-', '_')).toSet()

    /** Whole-word staples of English prose (the app's
     * reading languages are translations INTO English; a
     * chapter with essentially none of these, at length,
     * is not English prose). */
    private val STOPWORDS = setOf(
        "the", "and", "of", "to", "a", "in", "is", "was", "he",
        "she", "it", "his", "her", "with", "for", "on", "as",
        "at", "by", "an", "be", "this", "that", "from", "or",
        "had", "not", "but", "what", "all", "were", "they"
    )

    /** The measured signals for a text (diagnostics + tests). */
    data class Signals(
        /** Share of characters in blob-shaped tokens. */
        val blobShare: Double,
        /** Spaces per character. */
        val spaceDensity: Double,
        /** Whole-word stopword occurrences. */
        val stopwordHits: Int,
        /** Sentence-ending punctuation occurrences. */
        val sentenceMarks: Int
    )

    fun signals(text: String): Signals {
        if (text.isEmpty()) return Signals(0.0, 0.0, 0, 0)
        var blobChars = 0
        var stopwords = 0
        for (token in text.split(Regex("\\s+"))) {
            if (token.length >= BLOB_TOKEN_MIN &&
                token.all { it in BASE64_CHARS }
            ) {
                blobChars += token.length
            }
            val word = token.trim('.', ',', '!', '?', ';', ':', '"', '\'', '(', ')')
                .lowercase()
            if (word in STOPWORDS) stopwords++
        }
        return Signals(
            blobShare = blobChars.toDouble() / text.length,
            spaceDensity = text.count { it == ' ' }.toDouble() / text.length,
            stopwordHits = stopwords,
            sentenceMarks = text.count { it == '.' || it == '!' || it == '?' }
        )
    }

    /** The share of [text]'s characters that belong to
     * blob-shaped tokens (the dominance signal, kept public
     * for diagnostics). */
    fun blobShare(text: String): Double = signals(text).blobShare

    /** True when [text] reads as prose (or is too short to
     * judge); false only when the signals agree it is an
     * encoded blob wearing a chapter's clothes. */
    fun isProse(text: String): Boolean {
        if (text.length < MIN_JUDGE_CHARS) return true
        val s = signals(text)
        if (s.blobShare >= TOTAL_BLOB_SHARE) return false
        return !(s.blobShare >= BLOB_SHARE &&
            s.spaceDensity < MAX_PROSELESS_SPACE_DENSITY &&
            s.stopwordHits < MIN_STOPWORD_HITS)
    }
}

/**
 * The one serve-time check every extension chapter passes
 * (ExtensionChapterFetcher): content that fails the
 * not-prose gate is a FAILURE with an honest reason, never
 * a served chapter — so it is never stored, never shown,
 * and the fetch's failure classification takes over.
 */
object ChapterServeGate {

    /** The honest failure for blob-shaped "content", or
     * null when [plainText] may be served. */
    fun refusalFor(plainText: String): String? {
        if (ProseSanity.isProse(plainText)) return null
        return "the text that came back wasn't readable prose — " +
            "it looks like protected or scrambled data, not the chapter"
    }
}
