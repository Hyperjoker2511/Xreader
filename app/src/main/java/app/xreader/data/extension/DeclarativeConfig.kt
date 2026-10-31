package app.xreader.data.extension

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A declarative source: pure data, no code (PLAN §5 — the default and
 * most-trusted kind; the Phase 5 AI selector generates exactly this
 * shape, and "Add source from link" infers it — Amendment 7).
 *
 * Selector strings are CSS selectors, optionally suffixed with
 * `@attr` to read an attribute instead of text: `a.chap@href`,
 * `img.cover@src`. A spec of `@text` (or empty) means "this element's
 * own text"; `@href` means "this element's own href" — used by
 * inferred sources where the list item IS the link.
 */
@Serializable
data class DeclarativeConfig(
    val id: String,
    val name: String,
    val lang: String = "en",
    val baseUrl: String,
    val search: ListSpec? = null,
    /** v1.0.1: true when the test-drive could NOT verify search —
     * the source page must say so plainly and offer "Set up search". */
    val searchNeedsSetup: Boolean = false,
    /**
     * v1.0.6 (W1.3): the source's pages are JavaScript shells — every
     * fetch for this source goes through the browser view
     * ([RenderedPageFetcher]) instead of the static client. Written
     * ONLY by a drive that proved the source rendered (plan triggers
     * TR1 / TR4-flip, Q5); defaulted so every pre-v1.0.6 config file
     * decodes unchanged and unmarked.
     */
    val needsRendering: Boolean = false,
    /** v1.0.8.1: a CSS selector (groups allowed) the browser
     * view must see before a render of this source's content
     * pages may count as settled — the wait-for-content hint
     * for app-shell lanes whose loading spinner can look
     * stable (chikari.moe: its chapter container / article).
     * Read by the probe's lane drive; null (every config
     * written before it existed) = no hint, exactly as before. */
    val renderWaitSelector: String? = null,
    /** v1.0.1: a book page on this site that probing proved readable
     * — the "Set up search" retry re-detects from this page. */
    val sampleBookUrl: String? = null,
    val popular: ListSpec? = null,
    val latest: ListSpec? = null,
    val details: DetailsSpec,
    val chapter: ChapterSpec,
    /** v1.0.1 enrichment: reader reviews on the book page. */
    val reviews: ReviewsSpec? = null,
    /** v1.0.1 enrichment: book-level comments on the book page. */
    val comments: CommentsSpec? = null,
    /** v1.0.1 enrichment: "More like this" — a listing on the book page. */
    val related: ListSpec? = null,
    /** v1.0.2 (R24): the signed-in account's bookshelf/library page,
     * listing-shaped. Filled by the source-builder when the site
     * exposes one; read through the user's assisted-login session.
     * Null = this source doesn't know its bookshelf (the import UI
     * says so plainly instead of pretending). */
    val bookshelf: ListSpec? = null,
    /** Source-shipped cleaner rules (origin "source", not editable). */
    val cleaners: List<CleanerRuleData> = emptyList()
)

/** A listing page (search results / popular / latest). [url] may use
 * {query} (URL-encoded), {page} and {page0} placeholders. */
@Serializable
data class ListSpec(
    val url: String,
    /** Selector for one result element. */
    val results: String,
    val title: String,
    val link: String,
    val cover: String? = null,
    val author: String? = null,
    /** Selector whose presence means another page exists. */
    val nextPage: String? = null,
    /** v1.0.1: "GET" (default) or "POST" — POST search forms exist. */
    val method: String = "GET",
    /** For POST listings: the form body template, may use {query}. */
    val body: String? = null,
    /** v1.1.0 (design §6.1, Shape B): the Book-Shape Rule for
     * this listing — a regex over the item link's normalised
     * path (query/fragment stripped, one trailing slash
     * removed); an item whose link doesn't match is dropped,
     * however book-like its card looks. Modelled on
     * [ChapterListSpec.urlPattern]. Null (every spec written
     * before v1.1.0) = no shape filter, exactly as before. */
    val linkPattern: String? = null
)

/** The book page: fills details and carries the chapter list. When the
 * book's id is itself a URL (the common case), it's fetched directly. */
@Serializable
data class DetailsSpec(
    val title: String? = null,
    val author: String? = null,
    val description: String? = null,
    val cover: String? = null,
    val chapters: ChapterListSpec
)

/** [list] selects one chapter row; [name]/[link] resolve within it
 * (or on it — `@text`/`@href`). [reverse] is for sites that list the
 * newest chapter first: the engine always returns reading order.
 * [pagination] is the optional server-side paging of the list
 * itself (v1.0.6.2, F1) for sites whose book page renders only the
 * newest slice; null (every config written before it existed) =
 * the book page's own rows are the whole list, exactly as before. */
@Serializable
data class ChapterListSpec(
    val list: String,
    val name: String,
    val link: String,
    /** v1.0.6.3: per-row release-date selector (same selector
     * grammar as [name]/[link] — e.g. ".date@text" for novelight's
     * DD.MM.YYYY spans). Null = the site prints no dates; stored
     * specs without the field keep working unchanged. */
    val date: String? = null,
    val reverse: Boolean = false,
    val pagination: ChapterListPagination? = null,
    /** v1.0.8.1: pattern completion for sites whose book page
     * renders only a slice of the list but STATES its total and
     * numbers its chapter URLs (chikari.moe: the page says
     * "3229 chapters" and chapters live at /novels/<slug>/<n>).
     * [urlPattern] builds a chapter URL from the book page's
     * URL — `{bookUrl}` and `{n}` (1-based) are replaced.
     * [totalCountPattern] is a regex over the book page's
     * visible text whose group 1 is the stated total (commas
     * allowed). Both null (every spec written before) = the
     * rendered rows are the whole list, exactly as before. */
    val urlPattern: String? = null,
    val totalCountPattern: String? = null
) {

    /** The chapter total the book page's visible text states,
     * per [totalCountPattern]; null when undeclared, unmatched
     * or unparseable (a broken pattern must never take the
     * chapter list down with it). */
    fun statedTotalIn(pageText: String): Int? {
        val pattern = totalCountPattern?.takeIf { it.isNotBlank() }
            ?: return null
        val digits = runCatching { Regex(pattern).find(pageText) }
            .getOrNull()?.groupValues?.getOrNull(1) ?: return null
        return digits.replace(",", "").trim().toIntOrNull()
            ?.takeIf { it > 0 }
    }

    /** The chapter URL for [n] under [urlPattern], or null
     * when no pattern is declared. */
    fun patternUrl(bookPageUrl: String, n: Int): String? {
        val pattern = urlPattern?.takeIf { it.isNotBlank() }
            ?: return null
        return pattern
            .replace("{bookUrl}", bookPageUrl.trimEnd('/'))
            .replace("{n}", n.toString())
    }
}

/**
 * Chapter-list pagination (v1.0.6.2, F1 — the QuickNovel contract:
 * a book yields its COMPLETE chapter list). Some sites render only
 * the newest rows on the book page and serve the rest from a
 * paging endpoint the page's own scripts call (novelight's
 * `/book/ajax/chapter-pagination?book_id=…&page=…`, verified live
 * 2026-10-30: 50 rows per page, newest first, the last page short,
 * the walk ending at chapter 1). This block declares that endpoint
 * declaratively, like everything else in the config:
 *
 * - [urlTemplate] builds each page's URL; `{bookId}` and `{page}`
 *   (1-based; the walk starts at page 2 — the book page itself is
 *   page 1) are replaced before the URL is resolved against the
 *   source's base URL, so the template may be absolute or
 *   base-relative, and the placeholders may sit in the path or
 *   the query string.
 * - [bookIdPattern] is a regex applied to the book page's HTML
 *   (script bodies included); group 1 is the id the endpoint
 *   needs (novelight: `BOOK_ID = "402"`). No match = the walk
 *   simply doesn't run and the rendered rows are the list.
 * - [jsonHtmlField]: when set, each response is a JSON object and
 *   the named field's string value is the rows' HTML fragment
 *   (novelight: `"html"`). Null = the response body IS the
 *   fragment.
 * - [headers] ride along on every page request through the
 *   source's normal session/whitelist HTTP. The default carries
 *   the XMLHttpRequest header these endpoints demand (the same
 *   convention as [ChapterTextAjax]).
 *
 * The engine parses each fragment with the chapter list's own
 * selectors, dedupes by chapter URL across the whole walk, and
 * stops on an empty page, an all-already-seen page, a page
 * shorter than the first paginated page (a short page is the
 * last), or a hard page cap — a failed page mid-walk keeps the
 * rows gathered so far instead of losing the list.
 */
@Serializable
data class ChapterListPagination(
    val urlTemplate: String,
    val bookIdPattern: String,
    val jsonHtmlField: String? = null,
    val headers: Map<String, String> = mapOf(
        "X-Requested-With" to "XMLHttpRequest"
    ),
    /** v1.0.8: a CSS selector for a <select> on the book page
     * whose option values run 1..N — the site's own page
     * count for this book's chapter list (novelight renders
     * one). Knowing the last page up front is what lets a
     * REVERSED list load progressively: the walk starts at
     * the last page (the oldest chapters = reading chapter
     * 1) and descends. Null (every config written before
     * v1.0.8) = the total is unknown; reversed lists then
     * keep the complete-on-open walk. */
    val totalPagesSelector: String? = null
) {

    /** The page count a book page's pagination select
     * declares: the largest numeric option value, or the
     * option count when values aren't numeric. Null when
     * no selector is configured or the select is absent. */
    fun totalPagesIn(doc: org.jsoup.nodes.Document): Int? {
        val selector = totalPagesSelector?.takeIf { it.isNotBlank() }
            ?: return null
        val select = runCatching { doc.select(selector).firstOrNull() }
            .getOrNull() ?: return null
        val values = select.select("option")
            .mapNotNull { it.attr("value").trim().toIntOrNull() }
        if (values.isNotEmpty()) return values.max()
        val count = select.select("option").size
        return count.takeIf { it > 0 }
    }
    /** The endpoint URL for one page of the walk. */
    fun endpointFor(bookId: String, page: Int): String =
        urlTemplate
            .replace("{bookId}", bookId)
            .replace("{page}", page.toString())

    /** The book id [pageHtml] carries, or null when the pattern
     * doesn't match (or doesn't compile — a broken pattern must
     * never take the whole chapter list down with it). */
    fun bookIdIn(pageHtml: String): String? =
        runCatching { Regex(bookIdPattern).find(pageHtml) }
            .getOrNull()?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
}

/** One chapter page. [content] selects the readable element; [nextPage]
 * chains multi-page chapters (selector for the next-page link),
 * capped by the engine. [remove] selectors are stripped first
 * (site ads, "previous/next" bars inside the text…). [ajax] is the
 * optional background-text mode (v1.0.5 Stage 1.5) for sites whose
 * chapter pages are shells that load their text from a JSON
 * endpoint; null (every config written before it existed) = the
 * static page is read exactly as before. */
@Serializable
data class ChapterSpec(
    val content: String,
    val title: String? = null,
    val nextPage: String? = null,
    val remove: List<String> = emptyList(),
    val ajax: ChapterTextAjax? = null
)

/**
 * AJAX chapter-text mode (v1.0.5 Stage 1.5): some sites serve a
 * chapter page with no prose in it — the page's JavaScript fetches
 * the text from a JSON endpoint instead (novelight's
 * `/book/ajax/read-chapter/<id>` is the verified shape, and it
 * refuses with HTTP 403 unless the request carries an
 * XMLHttpRequest header). This block declares that endpoint
 * declaratively, like everything else in the config:
 *
 * - [urlTemplate] builds the endpoint from the chapter page's URL;
 *   `{id}` is replaced by the chapter URL's trailing numeric
 *   segment (`/book/chapter/346043` → `346043`). A chapter URL
 *   with no numeric tail makes the mode simply not apply — the
 *   engine falls back to the static page for that chapter.
 * - [headers] are sent with the request (through the source's
 *   normal session/whitelist HTTP, so cookies and rate limits
 *   still apply). The default carries the header the verified
 *   shape demands.
 * - [jsonContentField] names the JSON field whose value is the
 *   chapter's HTML; the engine then runs the chapter spec's
 *   ordinary content extraction and cleanup over it. An empty or
 *   missing field — or HTML with no readable text in it — is how
 *   the site says the chapter is paid/locked, and the engine
 *   reports it that way instead of serving an empty chapter.
 */
@Serializable
data class ChapterTextAjax(
    val urlTemplate: String,
    val headers: Map<String, String> = mapOf(
        "X-Requested-With" to "XMLHttpRequest"
    ),
    val jsonContentField: String = "content"
) {
    /** The chapter id this mode would use for [chapterUrl]: the
     * URL's trailing path segment when it is entirely numeric,
     * else null (the mode doesn't apply to that chapter). */
    fun chapterId(chapterUrl: String): String? {
        val tail = chapterUrl.trimEnd('/').substringAfterLast('/')
        return tail.takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } }
    }

    /** The endpoint URL for [chapterUrl], or null when the mode
     * doesn't apply (no numeric chapter id). */
    fun endpointFor(chapterUrl: String): String? {
        val id = chapterId(chapterUrl) ?: return null
        return urlTemplate.replace("{id}", id)
    }
}

/** Reviews on a book page (v1.0.1): [list] selects one review
 * element; the rest resolve within it. [aggregate] reads the
 * summary rating text (e.g. "4.6") from the whole page. */
@Serializable
data class ReviewsSpec(
    val list: String,
    val author: String,
    val text: String,
    val rating: String? = null,
    val date: String? = null,
    val aggregate: String? = null
)

/** Book-level comments on a book page (v1.0.1). */
@Serializable
data class CommentsSpec(
    val list: String,
    val author: String,
    val text: String,
    val date: String? = null
)

/** A cleaner rule as data (also the shape of stored user rules). */
@Serializable
data class CleanerRuleData(
    val pattern: String,
    val replacement: String = "",
    val ignoreCase: Boolean = false,
    val enabled: Boolean = true
)

/** A selector string split into its CSS part and optional attribute. */
data class SelectorSpec(val css: String, val attr: String?) {
    companion object {
        fun parse(raw: String): SelectorSpec {
            val trimmed = raw.trim()
            val at = trimmed.lastIndexOf('@')
            // "@text"/"@href" alone: the element itself.
            if (at == 0) return SelectorSpec("", trimmed.substring(1).ifBlank { null })
            if (at > 0) {
                val attr = trimmed.substring(at + 1)
                if (attr.isNotBlank() && !attr.contains(' ') && !attr.contains('.')) {
                    return SelectorSpec(trimmed.substring(0, at).trim(), attr)
                }
            }
            return SelectorSpec(trimmed, null)
        }
    }
}

/**
 * Selector fallback chains (v1.0.4, D14): one stored selector field
 * may carry several candidates separated by [SEPARATOR] — the engine
 * tries them in order and the first that finds something wins. A
 * field without the separator is a one-candidate chain, so every
 * config written before chains existed behaves exactly as before
 * (no storage change: the separator lives inside the existing
 * string fields, and `||` cannot appear in a valid single jsoup
 * selector — `|=` attribute matching uses a single pipe).
 */
object SelectorChains {
    const val SEPARATOR = "||"

    /** Candidates in order. A field with no separator is returned
     * as-is — a one-element chain, even when blank. */
    fun split(raw: String): List<String> {
        if (!raw.contains(SEPARATOR)) return listOf(raw)
        return raw.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun join(candidates: List<String>): String =
        candidates.joinToString(" $SEPARATOR ")
}

object DeclarativeFingerprints {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** The canonical fingerprint of a declarative package: SHA-256 of
     * the canonical re-serialisation of its rules. Any rule change —
     * even one character — changes the fingerprint (§9.1). */
    fun of(sources: List<DeclarativeConfig>): String {
        val canonical = json.encodeToString(
            ListSerializer(DeclarativeConfig.serializer()), sources
        )
        return Hashes.sha256Hex(canonical)
    }

    fun encode(sources: List<DeclarativeConfig>): String =
        json.encodeToString(ListSerializer(DeclarativeConfig.serializer()), sources)

    fun decode(raw: String): List<DeclarativeConfig> =
        runCatching {
            json.decodeFromString(ListSerializer(DeclarativeConfig.serializer()), raw)
        }.getOrDefault(emptyList())
}
