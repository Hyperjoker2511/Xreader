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
    val body: String? = null
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
 * newest chapter first: the engine always returns reading order. */
@Serializable
data class ChapterListSpec(
    val list: String,
    val name: String,
    val link: String,
    val reverse: Boolean = false
)

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
