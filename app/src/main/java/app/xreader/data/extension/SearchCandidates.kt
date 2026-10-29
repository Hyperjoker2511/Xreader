package app.xreader.data.extension

import java.net.URI
import java.net.URLEncoder

/**
 * Search-URL candidates for a site that has no known search
 * template (v1.0.1 hotfix): when [LinkInference.findSearchTemplate]
 * finds no search form on a book/listing page, the caller can still
 * try the conventions most novel sites follow, in order, and use
 * the first one that answers with a plausible results page.
 *
 * The conventions tried by [conventionUrls], IN ORDER, are:
 *
 * 1. `{base}/search?q={q}` — the most common novel-site shape.
 * 2. `{base}/search?keyword={q}` — WordPress-ish / aggregator shape.
 * 3. `{base}/search/{q}` — path form; `{q}` is percent-encoded
 *    WITHOUT `+` for spaces (spaces are `%20`, see below).
 * 4. `{base}/?s={q}` — stock WordPress search.
 * 5. `{base}/search.html?wd={q}` — static-page / Chinese-site shape.
 * 6. `{base}/search.html?keyword={q}` — static-page variant.
 *
 * Everything here is pure string work — fetching, and judging
 * whether a candidate actually returned results, lives in the
 * caller — so fixtures pin the behaviour exactly.
 */
object SearchCandidates {

    /** One concrete way to ask a site for [query] results: fetch
     * [url] with [method] ("GET" or "POST"); [body] is the
     * form-encoded request body for POST and null for GET. */
    data class SearchAttempt(
        val url: String,
        val method: String,
        val body: String?
    )

    // ── query encoding ────────────────────────────────────────────

    /**
     * Form-style (`application/x-www-form-urlencoded`) encoding of
     * a raw search query: spaces become `+`, and everything outside
     * the unreserved set is UTF-8 percent-encoded — CJK characters
     * therefore become their `%XX` byte sequences, never raw text.
     *
     * This deliberately uses [URLEncoder] semantics, quirks and
     * all, because that is what the declarative engine already puts
     * into `{query}` placeholders (see [DeclarativeSource]) and what
     * HTML forms themselves submit:
     *
     * - `A–Z a–z 0–9` and `. - * _` are left as-is (`*` staying raw
     *   is a URLEncoder quirk, not RFC 3986 unreserved behaviour).
     * - `~` is percent-encoded (`%7E`) even though RFC 3986 treats
     *   it as unreserved.
     * - A space is `+`, NOT `%20` — correct for query strings and
     *   form bodies, wrong for path segments (see [encodePathSegment]).
     *
     * Never throws: a blank query encodes to "".
     */
    fun encodeQuery(raw: String): String =
        if (raw.isEmpty()) "" else runCatching {
            URLEncoder.encode(raw, "UTF-8")
        }.getOrDefault("")

    /**
     * Percent-encoding for a single URL PATH segment: UTF-8 bytes,
     * RFC 3986 unreserved characters (`A–Z a–z 0–9 - . _ ~`) left
     * as-is, everything else `%XX` — so a space is `%20` (never the
     * form-style `+`, which a path segment would read literally)
     * and a `/` inside the query cannot split the path (`%2F`).
     */
    private fun encodePathSegment(raw: String): String {
        if (raw.isEmpty()) return ""
        val bytes = raw.toByteArray(Charsets.UTF_8)
        return buildString(bytes.size) {
            for (byte in bytes) {
                val value = byte.toInt() and 0xFF
                val ch = value.toChar()
                if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                    ch == '-' || ch == '.' || ch == '_' || ch == '~'
                ) {
                    append(ch)
                } else {
                    append('%')
                    append(HEX[value ushr 4])
                    append(HEX[value and 0x0F])
                }
            }
        }
    }

    // ── convention candidates ─────────────────────────────────────

    /**
     * The documented convention list (see the class KDoc for the
     * full list, in order) for [query] against [baseUrl]. The base
     * is normalised first: surrounding whitespace is trimmed and
     * any trailing `/`s are dropped, so `https://site.example/` and
     * `https://site.example` give the same candidates.
     *
     * Never throws: a blank base yields an empty list.
     */
    fun conventionUrls(baseUrl: String, query: String): List<String> {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return emptyList()
        val q = encodeQuery(query)
        val pathQ = encodePathSegment(query)
        return listOf(
            "$base/search?q=$q",
            "$base/search?keyword=$q",
            "$base/search/$pathQ",
            "$base/?s=$q",
            "$base/search.html?wd=$q",
            "$base/search.html?keyword=$q"
        )
    }

    // ── form attempts ─────────────────────────────────────────────

    /**
     * Build the attempt for a search FORM the caller did find (the
     * [LinkInference.findSearchTemplate] shape, taken apart): the
     * form's [formAction], its query input's [inputName], and its
     * [method].
     *
     * - [formAction] is resolved against [baseUrl] first: absolute
     *   `http(s)` actions are used as-is, path-only (`/search`) and
     *   relative (`search`) actions resolve against the base the
     *   same way [LinkInference] resolves hrefs, and a blank action
     *   means the base itself (an HTML form with no action submits
     *   to its own page).
     * - GET (the HTML default — any method that isn't POST is
     *   treated as GET): the encoded `inputName=query` pair is
     *   appended to the resolved action, with `&` when the action
     *   already carries a query string and `?` otherwise; [SearchAttempt.body]
     *   is null.
     * - POST: the resolved action is used unchanged and the pair
     *   becomes the form-encoded [SearchAttempt.body].
     *
     * A blank [inputName] falls back to "q", the commonest name.
     * Never throws on bad input.
     */
    fun formAttempt(
        formAction: String,
        inputName: String,
        method: String,
        query: String,
        baseUrl: String
    ): SearchAttempt {
        val action = resolveAction(formAction, baseUrl)
        val name = inputName.trim().ifBlank { "q" }
        val pair = "$name=${encodeQuery(query)}"
        return if (method.trim().equals("POST", ignoreCase = true)) {
            SearchAttempt(url = action, method = "POST", body = pair)
        } else {
            val separator = if (action.contains('?')) "&" else "?"
            SearchAttempt(url = "$action$separator$pair", method = "GET", body = null)
        }
    }

    private fun resolveAction(formAction: String, baseUrl: String): String {
        val action = formAction.trim()
        val trimmedBase = baseUrl.trim()
        if (action.isEmpty()) return trimmedBase.trimEnd('/')
        if (action.startsWith("http://") || action.startsWith("https://")) {
            return action
        }
        runCatching { URI(trimmedBase).resolve(action).toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        // Fallback for an unparseable base: a plain join.
        val base = trimmedBase.trimEnd('/')
        return if (action.startsWith("/")) base + action else "$base/$action"
    }

    private const val HEX = "0123456789ABCDEF"
}
