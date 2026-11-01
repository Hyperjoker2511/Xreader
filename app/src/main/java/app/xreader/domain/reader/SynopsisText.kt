package app.xreader.domain.reader

/**
 * Synopsis hygiene (v1.3.2, V120-CHECK item 76).
 *
 * Two device-proven defects meet here:
 *
 *  1. A source's META description is an SEO snippet, not the
 *     synopsis: it opens with boilerplate ("Read online
 *     "The Complainant Murder Club" - …") and is cut short,
 *     while the page's own synopsis body carries the full
 *     text. Extraction now prefers the page body when the
 *     configured selector reads a meta tag, and the
 *     boilerplate prefix is stripped wherever it appears.
 *  2. The book page showed its "Read more" expander only
 *     when the synopsis exceeded 200 CHARACTERS — a shorter
 *     text can still overflow the collapsed 3 lines on a
 *     narrow screen, leaving a visibly truncated synopsis
 *     with no way to read the rest. Overflow is a layout
 *     fact, not a length: the card now asks the text layout
 *     itself ([hasVisualOverflow] drives it), with the
 *     length rule kept only as the pre-layout candidate.
 *
 * Pure and JVM-tested.
 */
object SynopsisText {

    /** The SEO boilerplate shapes novel sites prepend to
     * their meta descriptions: `Read online "Title" - text`,
     * `Read "Title" online - text`, `Title - Read online …`
     * style leads. Only a LEADING boilerplate is stripped,
     * and only when real text follows it. */
    private val BOILERPLATE_PREFIXES = listOf(
        Regex(
            "(?i)^read\\s+(?:online\\s+)?[\"“”'‘’][^\"“”'‘’]{1,120}" +
                "[\"“”'‘’]\\s*(?:online\\s*)?[-–—:|·]\\s*"
        ),
        Regex("(?i)^read\\s+online\\s*[-–—:|·]\\s*"),
        Regex("(?i)^read\\s+[\"“”'‘’][^\"“”'‘’]{1,120}[\"“”'‘’]\\s+online\\s*[-–—:|·]\\s*")
    )

    /** True when [text] opens with a site's SEO boilerplate —
     * the mark of a meta snippet rather than a synopsis body. */
    fun hasBoilerplatePrefix(text: String): Boolean {
        val trimmed = text.trim()
        return BOILERPLATE_PREFIXES.any { it.containsMatchIn(trimmed) }
    }

    /** [text] with a leading boilerplate prefix removed;
     * unchanged when there is none (or when stripping would
     * leave nothing). */
    fun stripBoilerplate(text: String): String {
        val trimmed = text.trim()
        for (pattern in BOILERPLATE_PREFIXES) {
            val match = pattern.find(trimmed) ?: continue
            val rest = trimmed.substring(match.range.last + 1).trim()
            if (rest.isNotEmpty()) return rest
        }
        return text
    }

    /** True when a configured description selector reads a
     * META tag's content attribute — the shape whose text is
     * a snippet by construction, so a longer on-page synopsis
     * body wins over it. */
    fun isMetaSpec(spec: String?): Boolean {
        val value = spec?.trim()?.lowercase() ?: return false
        return value.contains("meta[") || value.contains("og:description") ||
            value.contains("name=description") || value.contains("name=\"description\"")
    }

    /** The pre-layout expander candidate: a synopsis this
     * long MIGHT overflow the collapsed lines, so the card
     * measures it. The final word belongs to the layout
     * (hasVisualOverflow) — a truncated synopsis with no
     * expander is a bug state at ANY length. */
    fun mayOverflow(length: Int): Boolean = length > COLLAPSED_CANDIDATE_CHARS

    /** Chars beyond which a 3-line clamp is plausible on the
     * narrowest supported screen. Deliberately conservative:
     * measuring a text that fits costs nothing. */
    const val COLLAPSED_CANDIDATE_CHARS = 110
}
