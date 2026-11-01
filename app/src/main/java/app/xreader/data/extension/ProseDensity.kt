package app.xreader.data.extension

/**
 * v1.3.0 (E5): the prose-density floor for test-drives.
 *
 * Before this, a chapter-text step passed on character COUNT
 * alone — and a site whose real prose is protected served 321
 * characters of navigation chrome that the drive once accepted
 * as "text" (noveldex, Site Batch B: the book was nearly
 * bundled on the strength of its own page furniture). Count
 * cannot tell prose from furniture; density can. Real chapter
 * prose is mostly letters and spaces in word-shaped runs, ends
 * sentences, and is almost never link text. Chrome is the
 * opposite: short labels, dense links, few sentences.
 *
 * Pure and deterministic — the same text always judges the
 * same — and deliberately conservative: the floor sits far
 * below any real chapter (the contract tester samples chapters
 * of 1,500+ characters) so a genuine page never trips it.
 */
object ProseDensity {

    data class Verdict(
        val isProse: Boolean,
        val chars: Int,
        val words: Int,
        /** Plain words for the drive's detail line when the
         * text is NOT prose ("321 characters, but they read as
         * page furniture, not story text"). */
        val reason: String
    )

    /** Judge one sampled chapter's PLAIN text (markup already
     * stripped). [linkText] is the visible text inside the
     * sample's links, when the caller can measure it — the
     * link-share signal; empty = unmeasured.
     *
     * Script-aware (v1.3.0, the Syosetu lesson): CJK prose has
     * no spaces between words and ends sentences with 。 —
     * a Latin-only word/sentence count reads a genuine
     * Japanese chapter as furniture (the verification drive's
     * ch.1: 4,237 chars, 85 。-sentences, "1 word"). When the
     * text is CJK-dominant, each CJK character counts as a
     * word unit and the word-SHAPE check is waived (there are
     * no spaces to shape); every other signal still applies. */
    fun judge(plainText: String, linkText: String = ""): Verdict {
        val text = plainText.replace(Regex("\\s+"), " ").trim()
        val chars = text.length
        val words = if (text.isBlank()) {
            emptyList()
        } else {
            text.split(' ').filter { token ->
                token.any { it.isLetterOrDigit() }
            }
        }
        val letters = text.count { it.isLetter() }
        val cjkChars = text.count { isCjk(it) }
        val cjkDominant = letters > 0 && cjkChars * 2 >= letters
        val wordCount = if (cjkDominant) {
            // Whitespace tokens undercount spaceless scripts to
            // near zero; the CJK characters themselves are the
            // word units.
            words.size + cjkChars
        } else {
            words.size
        }
        val letterShare = if (chars == 0) 0.0 else letters.toDouble() / chars
        val avgWordLength = when {
            cjkDominant -> AVG_WORD_NEUTRAL
            wordCount == 0 -> 0.0
            else -> words.sumOf { w -> w.count { it.isLetterOrDigit() } }
                .toDouble() / words.size.coerceAtLeast(1)
        }
        val sentences = text.count {
            it == '.' || it == '!' || it == '?' || it == '…' ||
                it == '。' || it == '！' || it == '？'
        }
        val linkShare = if (linkText.isBlank() || chars == 0) {
            0.0
        } else {
            linkText.length.toDouble() / chars
        }
        val reason = when {
            chars < MIN_CHARS ->
                "only $chars characters — too little to be a chapter"
            wordCount < MIN_WORDS ->
                "$chars characters but only $wordCount words — " +
                    "that reads as page furniture, not story text"
            letterShare < MIN_LETTER_SHARE ->
                "most of the text isn't letters — it reads as " +
                    "page furniture, not story text"
            avgWordLength < MIN_AVG_WORD || avgWordLength > MAX_AVG_WORD ->
                "the words aren't word-shaped (average length " +
                    "${"%.1f".format(avgWordLength)}) — not story prose"
            sentences < MIN_SENTENCES ->
                "no sentence endings in $chars characters — " +
                    "story prose ends sentences; page furniture doesn't"
            linkShare > MAX_LINK_SHARE ->
                "most of the text is links — a navigation block, " +
                    "not a chapter"
            else -> "reads as story prose"
        }
        return Verdict(
            isProse = reason == "reads as story prose",
            chars = chars,
            words = wordCount,
            reason = reason
        )
    }

    /** Han, Hiragana, Katakana and Hangul characters — the
     * spaceless scripts the word count treats per-character. */
    private fun isCjk(c: Char): Boolean =
        (c in '\u4e00'..'\u9fff') || (c in '\u3040'..'\u30ff') ||
            (c in '\uac00'..'\ud7a3') || (c in '\u3400'..'\u4dbf')

    // The floor: an order of magnitude below a real sampled
    // chapter on every axis, so only furniture can trip it.
    private const val MIN_CHARS = 400
    private const val MIN_WORDS = 60
    private const val MIN_LETTER_SHARE = 0.55
    private const val MIN_AVG_WORD = 3.0
    private const val MAX_AVG_WORD = 9.5
    private const val MIN_SENTENCES = 3
    private const val MAX_LINK_SHARE = 0.30
    /** The word-shape value a CJK-dominant text is judged
     * with: inside the accepted band by construction (the
     * check is waived, not passed on evidence). */
    private const val AVG_WORD_NEUTRAL = 4.5
}
