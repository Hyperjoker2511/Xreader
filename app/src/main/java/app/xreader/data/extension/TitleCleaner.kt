package app.xreader.data.extension

/**
 * Title hygiene (v1.0.5, A7 — D24/D25): ONE cleaning rule for book
 * titles wherever a source produces them — link inference, the
 * heuristic proposer, and the declarative engine's listing/details
 * reads. Two defects it exists to kill:
 *
 * - Sites stamp a trailing date onto listing titles
 *   ("…Under Heaven 29.10.2026"); the stamp is not part of the
 *   book's name and is stripped.
 * - A title that is ONLY a date ("29.10.2026" — the extractor
 *   picked the date element) must never become a book's name:
 *   callers reject it as a candidate and fall back (next
 *   candidate, the URL slug, or the title the book already had).
 *
 * Pure string work; fixtures pin the behaviour exactly.
 */
object TitleCleaner {

    /** A full-string date stamp: dd.mm.yyyy / dd/mm/yyyy /
     * dd-mm-yyyy / yyyy-mm-dd, optionally wrapped in brackets. */
    private val DATE_ONLY = Regex(
        "^[\\[(]?\\s*(?:\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}|" +
            "\\d{4}[./-]\\d{1,2}[./-]\\d{1,2})\\s*[\\])]?$"
    )

    /** A date stamp at the very END of a longer string, with an
     * optional separator or opening bracket before it. */
    private val TRAILING_DATE = Regex(
        "[\\s\\u00b7|\\-\\u2013\\u2014,]*[\\[(]?\\s*" +
            "(?:\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}|" +
            "\\d{4}[./-]\\d{1,2}[./-]\\d{1,2})" +
            "\\s*[\\])]?\\s*$"
    )

    /** True when [text] is nothing but a date stamp. */
    fun isDateOnly(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return DATE_ONLY.matches(trimmed)
    }

    /**
     * Clean a raw site title: trim, cut a site suffix after
     * " | " / " - " (the long-standing rule), then strip a
     * trailing date stamp if one remains. Returns the cleaned
     * string — which may still be date-only (check [isDateOnly])
     * or blank; deciding what to do then is the caller's job.
     */
    fun clean(raw: String): String {
        var title = raw.trim()
            .substringBefore(" | ")
            .substringBefore(" - ")
            .trim()
        if (title.isEmpty()) title = raw.trim()
        // Strip a trailing date stamp (possibly after a separator
        // the substringBefore cuts didn't remove, e.g. a bare
        // space: "Some Book 29.10.2026").
        val stripped = title.replace(TRAILING_DATE, "").trim()
        if (stripped.isNotEmpty() && stripped != title) {
            title = stripped
        }
        return title
    }

    /**
     * A human title derived from a book URL's last path segment:
     * "childhood-friend-of-the-zenith" → "Childhood Friend Of The
     * Zenith". Null when the segment carries nothing usable
     * (blank, purely numeric, or itself a date).
     */
    fun slugTitle(url: String): String? {
        val segment = url.substringBefore('?').substringBefore('#')
            .trimEnd('/').substringAfterLast('/').trim()
        if (segment.isEmpty()) return null
        val decoded = runCatching {
            java.net.URLDecoder.decode(segment, "UTF-8")
        }.getOrDefault(segment)
        val words = decoded.split(Regex("[^A-Za-z0-9\\u4e00-\\u9fff\\uac00-\\ud7a3]+"))
            .filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        val candidate = words.joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        }
        if (isDateOnly(candidate)) return null
        if (candidate.all { it.isDigit() || it == ' ' }) return null
        return candidate
    }
}
