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
    /** v1.3.0: this config SHIPS WITH THE APP as a built-in
     * default, proven against the live site at build time by
     * the maintainer (the defaults programme). Two effects,
     * both honest bookkeeping rather than trust shortcuts: the
     * startup sync installs/refreshes it under the "Built-in
     * sources" repo, and the probe's lane-proof policy (E4)
     * may install it from a LIGHT proof when a full device
     * test-drive cannot run — verification then happens on
     * first real read, exactly as the policy states in Health.
     * Defaulted false so every older config decodes unchanged. */
    val maintainerVerified: Boolean = false,
    /** v1.3.0: extra hosts this source may open beyond its base
     * host — an API host (novelbuddy's api. subdomain, royal
     * road's api.), or a host chapter pages redirect to
     * (libread's chapter text lands on freewebnovel.com;
     * syosetu's books live on ncode. while its catalogue sits
     * on yomou.). Install paths add these to the extension's
     * domain whitelist. Defaulted empty so every older config
     * decodes unchanged. */
    val extraDomains: List<String> = emptyList(),
    /** v1.3.2 (items 75/75b, the LOGIN RULE): this site's
     * chapter TEXT sits behind an account — catalogue, search,
     * book pages and chapter lists read anonymously, but the
     * prose unlocks only with a signed-in session. The source
     * is labelled "Sign in needed to read", a locked text
     * fetch offers the one-time sign-in screen (the app keeps
     * the session's cookies, never the password), and after
     * signing in the same fetch rides the session natively.
     * A login gate is a solvable state, never a dead end.
     * Defaulted false so every older config decodes
     * unchanged. */
    val signInNeeded: Boolean = false,
    /** Source-shipped cleaner rules (origin "source", not editable). */
    val cleaners: List<CleanerRuleData> = emptyList(),
    /** v1.3.4 (session v2, T1): this source's CREDENTIAL
     * CONTRACT — the explicitly declared session artefacts
     * beyond plain cookies that its fetches may carry (a
     * cookie replayed as a header to one exact API host; a
     * short-lived operation token minted from the account
     * session). Null (every config written before v1.3.4) =
     * cookies only, exactly as before. Declarations are
     * EXACT: a credential is only ever sent to the hosts its
     * declaration names — never harvested generically, never
     * replayed anywhere else. See [CredentialSpec]. */
    val credentials: CredentialSpec? = null,
    /** v1.3.4 (T6, mirror failover): BACKUP HOSTS for this
     * source, in order — the same site answering under other
     * domains (a mirror the maintainer verified serves the
     * same shapes). On TRANSPORT failure only (DNS, connect,
     * timeout, host-level 5xx with no usable body) the fetch
     * road retries the same path on the next host; a host
     * that ANSWERED (wall, lock, auth state, 404) is never
     * failed over — its answer stands. Book/chapter identity
     * stays canonical to the primary host (see MirrorHosts).
     * Seeded ONLY from sweep-verified mirrors. Defaulted
     * empty so every older config decodes unchanged. */
    val backupHosts: List<String> = emptyList()
)

/**
 * v1.3.4 (session v2, T1): a source's declared credential
 * contract. Two shapes exist because two real sites proved
 * them (TOKEN-SESSION-RESEARCH): a login token a site's own
 * client replays as a header to a DIFFERENT host (BOTI), and
 * a short-lived operation token minted per reading session
 * from the account cookie (FenrirRealm). Nothing else is
 * declared, and nothing undeclared is ever captured or sent.
 */
@Serializable
data class CredentialSpec(
    /** Replay a named session cookie as a header to exact hosts. */
    val headerBinding: HeaderBinding? = null,
    /** Mint + carry a short-lived operation (read) token. */
    val operationToken: OperationTokenSpec? = null
)

/**
 * Replay the session cookie named [cookieName] as the header
 * [headerName] (with [prefix] — empty = the cookie value
 * verbatim) on requests to [hosts] ONLY (exact host match,
 * normalised). The cookie is looked up across the source's
 * own session jars — the site may set it on its web host
 * while the API host is the one that must receive it (BOTI:
 * `userToken` set on .botitranslation.com, replayed to
 * api.mystorywave.com). It is never sent to any other host,
 * and a caller's own header of the same name is dropped on
 * hosts this binding doesn't declare.
 */
@Serializable
data class HeaderBinding(
    val cookieName: String,
    val headerName: String = "Authorization",
    val prefix: String = "",
    val hosts: List<String> = emptyList()
) {
    fun appliesTo(host: String): Boolean {
        val h = host.normaliseHost()
        return hosts.any { it.normaliseHost() == h }
    }

    /** The header value for [cookieValue], or null when the
     * value is unusable (blank values are never sent). */
    fun headerValue(cookieValue: String?): String? {
        val value = cookieValue?.takeIf { it.isNotBlank() } ?: return null
        return prefix + value
    }
}

/**
 * A short-lived OPERATION TOKEN (FenrirRealm's read token):
 * minted by POSTing [mintUrl] with the account session's
 * cookies (the fetch road attaches them), answered by
 * `{active, token, expires_at}` or by a refusal that names
 * its own kind (403 + `challenge_required` = the site wants
 * its human check, run on [checkUrlTemplate]'s page; an
 * auth-shaped refusal = the account session itself is dead).
 * The token rides as [headerName]: [prefix]<token> on the
 * source's API fetches to [tokenHosts] (default: the mint
 * URL's own host — origin-exact, never a mirror unless the
 * mirror is named here). [defaultLifetimeSeconds] applies
 * when a mint answer omits the expiry (the site's own
 * client assumes 300 s); renewal happens
 * [renewalMarginSeconds] before expiry. A mint response may
 * also re-issue the token through the [issuedHeader] /
 * [issuedExpiresHeader] response headers on ANY answer —
 * the engine harvests those opportunistically, like the
 * site's own client. The encrypted-content opt-in header
 * is never sent and no decryption is ever attempted.
 */
@Serializable
data class OperationTokenSpec(
    val mintUrl: String,
    /** JSON body template for the mint POST; `{chapter_id}`
     * expands to the locked record's remint hint (or the
     * chapter's own number). Null = an empty JSON object. */
    val mintBodyTemplate: String? = null,
    val headerName: String = "Authorization",
    val prefix: String = "Bearer ",
    val defaultLifetimeSeconds: Long = 300,
    val renewalMarginSeconds: Long = 60,
    val issuedHeader: String = "x-read-token-issued",
    val issuedExpiresHeader: String = "x-read-token-expires-at",
    /** Hosts the token may be sent to; empty = the mint
     * URL's host only. */
    val tokenHosts: List<String> = emptyList(),
    /** The site's own page where its human check runs for
     * this credential (FenrirRealm: the reader page — its
     * widget lives there). `{seg-N}` placeholders expand
     * against the chapter URL exactly as in
     * [ChapterTextAjax]. Null = the site root is the check
     * page. */
    val checkUrlTemplate: String? = null
) {
    /** The hosts the minted token may ride to. */
    fun hosts(): List<String> {
        if (tokenHosts.isNotEmpty()) return tokenHosts
        val host = CookieParsing.hostOf(mintUrl) ?: return emptyList()
        return listOf(host)
    }

    fun appliesTo(host: String): Boolean {
        val h = host.normaliseHost()
        return hosts().any { it.normaliseHost() == h }
    }

    /** The mint request body for [chapterId] (JSON). */
    fun mintBody(chapterId: String?): String {
        val template = mintBodyTemplate ?: return "{}"
        return template.replace(
            "{chapter_id}", chapterId?.let { jsonEscape(it) } ?: ""
        )
    }

    /** The sanctioned check page for a chapter URL, or null
     * when no template is declared. */
    fun checkUrlFor(chapterUrl: String): String? {
        val template = checkUrlTemplate ?: return null
        return ChapterTextAjax(urlTemplate = template).endpointFor(chapterUrl)
    }

    private fun jsonEscape(raw: String): String =
        raw.replace("\\", "\\\\").replace("\"", "\\\"")
}

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
    val linkPattern: String? = null,
    /** v1.2.3: embedded-JSON mode. Some sites (Next.js apps)
     * carry their catalogue / search results ONLY as JSON
     * inside the page — the `__NEXT_DATA__` block or the RSC
     * stream — while the DOM holds a landing page or a
     * skeleton. When [jsonPath] is set, the listing's rows are
     * the records of the JSON array at that dot path in the
     * page's embedded payload, and [title] / [link] / [cover] /
     * [author] name FIELDS inside each record instead of CSS
     * selectors (see [EmbeddedJson]). Null (every spec written
     * before v1.2.3) = DOM selectors, exactly as before. A JSON
     * spec whose payload is missing or misshapen fails the
     * read honestly — it never falls back to scraping a DOM
     * that was never the data. */
    val jsonPath: String? = null,
    /** v1.3.0: JSON mode — builds a row's link from a record
     * field when the field value is not itself a usable URL: a
     * slug ("https://host/book/{value}") or a path on another
     * host of the same site ("https://novelbuddy.me{value}" —
     * the records ride the api. host but the books live on the
     * apex). Null = the field value IS the link, resolved
     * against the listing URL like any href. */
    val jsonLinkTemplate: String? = null,
    /** v1.3.2: JSON mode — builds a row's COVER from a record
     * field whose value is a bare storage path rather than a
     * resolvable URL (fenrirealm's "storage/…" covers, which
     * serve from the site's root, not from beside the API
     * path a relative resolve would produce). "{value}" is
     * the field's value. Null = the field value resolves
     * against the listing URL like any cover. */
    val jsonCoverTemplate: String? = null
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
    val totalCountPattern: String? = null,
    /** WS3 (class D): the chapter total as a NUMBER, for lists
     * the learner completed from the page's own evidence (the
     * strict-pattern window whose newest-chapter links imply
     * the total) rather than from a stated-total text pattern.
     * The engine's pattern completion accepts either source;
     * null (every spec written before) = no numeric total. */
    val totalCount: Int? = null,
    /** v1.2.3: embedded-JSON chapter rows. When [jsonPath] is
     * set, the book page's embedded payload carries a chapter
     * array at that dot path whose records name their chapter
     * in [jsonNameField] and link it in [jsonLinkField]; the
     * engine MERGES those rows with the DOM rows (one entry
     * per chapter URL) — Next.js book pages render only a
     * window of anchors statically while the payload carries
     * a longer list (novelbuddy: 21 anchors, latest 50 in
     * JSON). Null (every spec written before) = DOM rows only,
     * exactly as before. */
    val jsonPath: String? = null,
    /** v1.3.0 delta (V120-CHECK 53): the chapter record's
     * release-timestamp field for JSON chapter endpoints
     * (chikari's `created_at`, NovelBuddy's `updated_at`).
     * Stored raw on the row's dateText; the display layer
     * renders it relatively. Null = the endpoint states no
     * dates, and rows show none — never a guessed date. */
    val jsonDateField: String? = null,
    val jsonNameField: String = "name",
    val jsonLinkField: String = "url",
    /** v1.3.0 (E1): a DIRECT JSON chapter endpoint on the book
     * page itself — the page's whole payload is the response
     * of an API whose chapter array sits at this dot path
     * (fenrirrealm-style book payloads carry the array beside
     * the metadata). Same record fields as [jsonPath]; unlike
     * [jsonPath] this source REPLACES the DOM rows when it
     * yields any (the page is an API answer, its DOM is a
     * shell of the payload). Null = no direct endpoint. */
    val jsonEndpointPath: String? = null,
    /** v1.3.0 (E1): how a DIRECT JSON chapter endpoint builds
     * each row's chapter URL when the record's link field is
     * a bare id or slug rather than a URL: `{value}` is the
     * record's link-field value, `{bookSlug}` the book page
     * URL's last segment, `{bookId}` the pagination book id.
     * Null = the field value IS the link (resolved against
     * the page like any href). */
    val jsonLinkTemplate: String? = null
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
 *
 * v1.3.0 (E1) generalisation — the same block now declares the
 * API chapter endpoints the defaults programme uses:
 *
 * - The URL template (and the POST body) may use `{bookId}`
 *   (from [bookIdPattern] over the book page), `{bookSlug}` /
 *   `{bookTail}` (the book page URL's last path segment) and
 *   `{page}`. A spec whose endpoint needs NO id from the page
 *   (slug-addressed APIs) leaves [bookIdPattern] empty and
 *   uses the slug placeholders instead.
 * - [method] "POST" sends [body] as a form body with the same
 *   placeholder expansion (FreeWebNovel's chapterlist.php,
 *   NovelFire's listChapterDataAjax, ScribbleHub's admin-ajax).
 * - [jsonArrayPath]: when set, each response is a JSON
 *   document and the chapter array sits at this dot path
 *   inside it (records read with the chapter spec's
 *   jsonNameField / jsonLinkField / jsonLinkTemplate). The
 *   walk then runs over API pages: [pageSize] declares the
 *   page size for the ranged placeholders `{start}` / `{end}`
 *   (WTR-LAB: 1-based inclusive chapter ranges) and offset
 *   maths, and [jsonTotalPath] — a dot path to the response's
 *   stated chapter total — ends the walk the moment the
 *   gathered rows reach it, exactly (chikari's `total`).
 */
@Serializable
data class ChapterListPagination(
    val urlTemplate: String,
    val bookIdPattern: String,
    val jsonHtmlField: String? = null,
    val headers: Map<String, String> = mapOf(
        "X-Requested-With" to "XMLHttpRequest"
    ),
    /** v1.3.0 (E1): "GET" (default) or "POST" — POST-form
     * chapter endpoints exist (form body in [body]). */
    val method: String = "GET",
    /** v1.3.0 (E1): for POST endpoints, the form body template
     * (same placeholders as the URL template). */
    val body: String? = null,
    /** v1.3.0 (E1): when set, responses are JSON documents and
     * the chapter array lives at this dot path inside them. */
    val jsonArrayPath: String? = null,
    /** v1.3.0 (E1): a dot path inside a JSON response to the
     * endpoint's stated chapter total; the walk stops as soon
     * as the gathered rows reach it. */
    val jsonTotalPath: String? = null,
    /** v1.3.0 (E1): rows per endpoint page for the ranged
     * placeholders (`{start}`/`{end}`) and offset maths. */
    val pageSize: Int = 50,
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
    /** The endpoint URL for one page of the walk. [bookSlug]
     * is the book page URL's last path segment (v1.3.0: slug-
     * addressed APIs need no id from the page); `{start}` /
     * `{end}` expand to the page's 1-based inclusive row range
     * at [pageSize] rows a page. */
    fun endpointFor(
        bookId: String,
        page: Int,
        bookSlug: String = "",
        bookUrl: String = ""
    ): String = expand(urlTemplate, bookId, page, bookSlug, bookUrl)

    /** v1.3.0 delta: true when the endpoint's URL cannot
     * vary by page (no page-varying placeholder) — one
     * request always returns the endpoint's whole answer
     * (NovelBuddy's chapter list, NovelFull's option list,
     * MtlNovel's fragment). {limit} is page-size, not a
     * page-varying placeholder: it expands identically on
     * every page. */
    fun isSingleShot(): Boolean {
        val t = urlTemplate
        return !t.contains("{page}") && !t.contains("{page0}") &&
            !t.contains("{offset}") && !t.contains("{start}") &&
            !t.contains("{end}")
    }

    /** The POST form body for one page of the walk, or null
     * for GET endpoints (v1.3.0). */
    fun bodyFor(
        bookId: String,
        page: Int,
        bookSlug: String = "",
        bookUrl: String = ""
    ): String? = body?.let { expand(it, bookId, page, bookSlug, bookUrl) }

    private fun expand(
        template: String,
        bookId: String,
        page: Int,
        bookSlug: String,
        bookUrl: String = ""
    ): String {
        val start = (page - 1) * pageSize + 1
        return template
            .replace("{bookUrl}", bookUrl.trimEnd('/'))
            .replace("{bookId}", bookId)
            .replace("{bookSlug}", bookSlug)
            .replace("{bookTail}", bookSlug)
            .replace("{start}", start.toString())
            .replace("{end}", (start + pageSize - 1).toString())
            .replace("{page}", page.toString())
            .replace("{page0}", (page - 1).coerceAtLeast(0).toString())
            .replace("{offset}", ((page - 1) * pageSize).toString())
            .replace("{limit}", pageSize.toString())
    }

    /** The book id [pageHtml] carries, or null when the pattern
     * doesn't match (or doesn't compile — a broken pattern must
     * never take the whole chapter list down with it). A BLANK
     * pattern declares a slug-addressed endpoint: the walk
     * needs no id and this returns "" as its go-ahead (v1.3.0). */
    fun bookIdIn(pageHtml: String): String? {
        if (bookIdPattern.isBlank()) return ""
        return runCatching { Regex(bookIdPattern).find(pageHtml) }
            .getOrNull()?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
    }

    companion object {
        /** A book page URL's last path segment (query/fragment
         * stripped, one trailing slash removed) — the slug the
         * slug-addressed API shapes address books by. */
        fun bookSlugOf(bookPageUrl: String): String =
            bookPageUrl.substringBefore('?').substringBefore('#')
                .trimEnd('/').substringAfterLast('/')
    }
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
    val jsonContentField: String = "content",
    /** v1.3.0 (E1): "GET" (default) or "POST" — reader APIs that
     * take the chapter address in a request body exist
     * (WTR-LAB's reader endpoint). [body] is the body template,
     * expanded with the same placeholders as the URL. */
    val method: String = "GET",
    val body: String? = null,
    /** v1.3.0 (E1): the JSON field's value is PLAIN TEXT, not
     * HTML (chikari's read endpoint: `body` is newline-
     * separated prose). The engine wraps each paragraph in
     * <p>…</p> instead of running the content selector over
     * markup that isn't there. */
    val plainTextField: Boolean = false,
    /** v1.3.0 (E1): a boolean JSON field that marks the
     * chapter locked when true (chikari: `locked`). A locked
     * answer is the typed locked failure, never an empty
     * chapter. Null = no lock field. */
    val jsonLockField: String? = null,
    /** v1.3.0: a dot path to the content value when it is
     * nested inside the answer (noveli: `chapter.paragraphs`);
     * supersedes the flat [jsonContentField] lookup when set. */
    val jsonContentPath: String? = null,
    /** v1.3.0: for ARRAY content whose items are objects, the
     * per-item text field (noveli's paragraphs carry `text`).
     * String items contribute themselves. Ignored for scalar
     * content. */
    val jsonItemTextField: String = "text",
    /** v1.3.0: in array content, items whose `id` value matches
     * this regex are skipped — noveli interleaves anti-scraping
     * watermark entries (`p-trap-…`, `p-wm-…`) among its prose
     * paragraphs (`p-<n>`), and the reader's settings chrome is
     * removed by the config's source cleaners instead. Null =
     * keep every item. */
    val jsonSkipIdPattern: String? = null
) {
    /** The chapter id this mode would use for [chapterUrl]: the
     * URL's trailing path segment when it is entirely numeric,
     * else null (the mode doesn't apply to that chapter). */
    fun chapterId(chapterUrl: String): String? {
        val tail = chapterUrl.trimEnd('/').substringAfterLast('/')
        return tail.takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } }
    }

    /**
     * The endpoint URL for [chapterUrl], built from the URL's
     * own path segments: `{id}` is the trailing numeric
     * segment (the original mode — null when the tail isn't
     * numeric, the mode then doesn't apply); `{seg-1}`,
     * `{seg-2}`, … are the last path segments counting back
     * from the end, whatever they contain (v1.3.0: slug-shaped
     * chapter addresses — a book slug two segments up, a
     * chapter number at the tail). A template that uses only
     * `{seg-N}` placeholders applies to any chapter URL with
     * at least that many segments.
     */
    fun endpointFor(chapterUrl: String): String? =
        expand(urlTemplate, chapterUrl)

    /** The request body for [chapterUrl] under POST, else null. */
    fun bodyFor(chapterUrl: String): String? =
        body?.let { expand(it, chapterUrl) }

    private fun expand(template: String, chapterUrl: String): String? {
        var out = template
        if (out.contains("{id}")) {
            val id = chapterId(chapterUrl) ?: return null
            out = out.replace("{id}", id)
        }
        if (out.contains("{seg-")) {
            val segments = chapterUrl.substringBefore('?')
                .substringBefore('#').trimEnd('/')
                .split('/').filter { it.isNotEmpty() }
            val wanted = Regex("\\{seg-(\\d+)\\}").findAll(out)
                .map { it.groupValues[1].toInt() }.toList()
            for (n in wanted) {
                val segment = segments.getOrNull(segments.size - n)
                    ?: return null
                out = out.replace("{seg-$n}", segment)
            }
        }
        return out
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
