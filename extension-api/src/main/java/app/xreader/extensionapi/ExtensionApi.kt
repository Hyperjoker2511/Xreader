package app.xreader.extensionapi

/**
 * The Xreader extension API (PLAN §1.2 / §5): the ONLY surface
 * third-party source code is ever allowed to see. It lives in its own
 * tiny module so Dex-loaded packages can link against it, and it is
 * versioned — the host refuses packages built for a newer [API_LEVEL]
 * than it provides.
 *
 * The contract is deliberately blocking and string-shaped (Mihon-style):
 * extension authors write ordinary synchronous code; the host calls it
 * on a background dispatcher. Every failure an extension can cause is
 * returned to the host as an exception, which the host maps to its own
 * typed error — extension exception types never cross the bridge.
 */

/** The API level this artifact provides. Bumped only on breaking changes. */
object ExtensionApi {
    const val API_LEVEL = 1
}

/** A book as sources know it: an opaque [id] (usually a URL or site id)
 * plus display fields. The host persists its own rows from these. */
data class SourceBook(
    val id: String,
    val title: String,
    val author: String? = null,
    val coverUrl: String? = null,
    val description: String? = null
)

/** One page of catalogue results. */
data class SourceBookPage(
    val books: List<SourceBook>,
    val hasNextPage: Boolean = false
)

/** A chapter as sources know it: opaque [id] (usually a URL) + name. */
data class SourceChapter(
    val id: String,
    val name: String,
    /** Chapter number when the source knows it (1, 2, 10.5…). */
    val number: Float? = null
)

/** One chapter's readable content: an HTML fragment (the host converts
 * it to its normalised model and strips scripts/styles) or plain text. */
data class SourceChapterContent(
    val html: String,
    val title: String? = null
)

/** A source: one site (or one mirror of a site) the user can browse. */
interface Source {
    /** Stable id, unique inside its package (declared in the repo index). */
    val id: String
    val name: String
    /** ISO language code the source serves ("en", "ko", "multi"…). */
    val lang: String
    /** The site's base URL. The host also whitelists this host. */
    val baseUrl: String

    fun search(query: String, page: Int): SourceBookPage
    fun popular(page: Int): SourceBookPage
    fun latest(page: Int): SourceBookPage

    /** Fills in author/description/cover for a catalogue [book]. */
    fun details(book: SourceBook): SourceBook

    /** The book's chapters, in reading order (first chapter first). */
    fun chapters(book: SourceBook): List<SourceChapter>

    /** One chapter's content. */
    fun chapterContent(chapter: SourceChapter): SourceChapterContent

    // ── Optional enrichment (v1.0.1) ──────────────────────────────
    // Default "not supported" (null): old extensions are unaffected.
    // An empty list means "this source offers it, there's just
    // nothing there yet" — the UI words those two states differently.

    /** Reader reviews for a book, when the source shares them. */
    fun reviews(book: SourceBook): List<SourceReview>? = null

    /** Book-level comments, when the source shares them. */
    fun comments(book: SourceBook): List<SourceComment>? = null

    /** "More like this" books, when the source offers them. */
    fun related(book: SourceBook): List<SourceBook>? = null
}

/** One reader review from a source (v1.0.1 enrichment). */
data class SourceReview(
    val author: String,
    val rating: Float? = null,
    val text: String,
    val dateText: String? = null
)

/** One comment from a source (v1.0.1 enrichment). */
data class SourceComment(
    val author: String,
    val text: String,
    val dateText: String? = null
)

/** What a package hands back when the host loads it. */
interface ExtensionFactory {
    fun create(context: SourceContext): List<Source>
}

/** The host's gifts to an extension — and the whole of its world. */
interface SourceContext {
    val http: HostHttp
    val html: HostHtml
    val store: HostStore
    val log: HostLog
}

/**
 * HTTP through the host's own client: the domain whitelist is enforced
 * inside it (a source physically cannot reach an undeclared host), the
 * user's saved login session is attached transparently, and requests
 * are rate-limited per extension. Cookie VALUES are never exposed —
 * sessions are used, never shown.
 */
interface HostHttp {
    fun get(url: String, headers: Map<String, String> = emptyMap()): HostResponse
    fun post(
        url: String,
        body: String,
        contentType: String = "application/x-www-form-urlencoded",
        headers: Map<String, String> = emptyMap()
    ): HostResponse
}

data class HostResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
    /** After redirects — always a whitelisted host, like every hop. */
    val finalUrl: String = ""
)

/** Host-side HTML parsing (jsoup-class). Extensions query a parsed
 * document; they never touch the parser itself. */
interface HostHtml {
    fun parse(html: String, baseUrl: String): HostDocument
}

interface HostDocument {
    fun select(css: String): List<HostElement>
    fun selectFirst(css: String): HostElement?
    /** The document's full text (scripts/styles excluded). */
    fun text(): String
}

interface HostElement {
    fun text(): String
    fun html(): String
    /** Attribute value (href/src resolved against the base URL), or null. */
    fun attr(name: String): String?
    fun select(css: String): List<HostElement>
    fun selectFirst(css: String): HostElement?
}

/** Key-value storage scoped to THIS extension's private directory.
 * There is no way to name another extension's store. */
interface HostStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** Extension logging — goes to the host's diagnostic log only. */
interface HostLog {
    fun log(message: String)
}

/** The one exception type the API defines: sources throw this (or any
 * exception — the host catches all) and the host turns it into a
 * plain-words failure naming the source. */
class SourceException(message: String) : Exception(message)
