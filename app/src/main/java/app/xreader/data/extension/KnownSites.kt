package app.xreader.data.extension

import java.net.URI

/**
 * The known-sites fast lane (v1.0.5, A11 — after the lncrawl
 * lesson): a small, curated index of domains whose site FAMILY
 * Xreader already knows. Inference is good, but a decade of
 * curated per-site knowledge beats guessing — so for a known
 * domain the probe tries the family's known-good shape FIRST,
 * then the ordinary pipeline (templates → inference →
 * heuristics → AI). Every fast-lane candidate still faces the
 * SAME mandatory test-drive as any other proposer: the index is
 * a head start, never a pass.
 *
 * The index is our own declarative data — domains, a family, and
 * capability HINTS only (a hint never proves anything; the drive
 * does). Adding a site is a one-line entry once its family is
 * known; adding a family is one builder below. Shapes marked
 * VERIFIED were read off the live sites on 2026-10-29.
 */
object KnownSites {

    enum class Family { MADARA, NOVELFULL, NOVELIGHT, CHIKARI, BOTI }

    data class Entry(
        val domains: List<String>,
        val family: Family,
        /** Hint only: the family usually supports in-source
         * search. The test-drive still has to prove it. */
        val canSearch: Boolean = true,
        /** Hint only: the family usually offers account login. */
        val canLogin: Boolean = false,
        /**
         * v1.3.0 (E4): the lane's maintainer verification. When
         * true, the lane was verified END-TO-END by the app
         * maintainers at bundle time, so the probe's on-device
         * test-drive may not VETO it: a slow connection turning
         * the drive's rendered book page into a timeout was
         * rejecting the repair of a lane we know works (boti,
         * V120-CHECK item 19 — the phone's Repair failed while
         * its connection crawled at 7 kB/s, on a lane that is
         * sound). The drive still runs; if it cannot complete,
         * the lane installs on the light proof (its catalogue
         * answered) with a proof-pending drive result, and the
         * first real read re-confirms. False (the default)
         * keeps the v1.0.8.1 bar for every other lane: the
         * drive decides.
         */
        val maintainerVerified: Boolean = false
    )

    private val ENTRIES: List<Entry> = listOf(
        // VERIFIED 2026-10-29 against the live site (D18/D21):
        // catalogue + search share the /catalog/ grid, chapters
        // are .chapters rows, covers live on the listing cards.
        Entry(
            domains = listOf("novelight.net", "www.novelight.net"),
            family = Family.NOVELIGHT,
            canSearch = true
        ),
        // VERIFIED 2026-10-29 against the live site: chapter
        // list under #list-chapter, chapter text in
        // #chapter-content, search is the site's own form.
        Entry(
            domains = listOf("novelfull.com", "www.novelfull.com"),
            family = Family.NOVELFULL,
            canSearch = true
        ),
        // VERIFIED 2026-10-30 against the live site (rendered
        // DOM inspection): a SvelteKit app — novels at
        // /novels/<slug>, chapters at /novels/<slug>/<n>, prose
        // in <article>, the book page states its chapter total
        // as plain text. Search exists in the site's UI but its
        // shape is unverified, so the hint stays off until a
        // drive proves it.
        Entry(
            domains = listOf("chikari.moe", "www.chikari.moe"),
            family = Family.CHIKARI,
            canSearch = false
        ),
        // v1.2.3: botitranslation.com — a Vite SPA whose static
        // HTML is a 3 KB shell; everything readable lives under
        // /mobile/ in the RENDERED document (books at
        // /mobile/book/<id>, chapters at /mobile/chapter/<id>,
        // prose in <article>). Shape modelled from the site's
        // own bundle (route table + card templates) and the
        // user's on-device screenshots (v1.2.1 round); the
        // lane's config is driven like any other candidate
        // before it can save. No search shape is derivable from
        // the bundle, so the hint stays off and Health says so
        // honestly (the chikari precedent).
        Entry(
            domains = listOf("botitranslation.com", "www.botitranslation.com"),
            family = Family.BOTI,
            canSearch = false,
            // v1.3.0 (E4): verified end-to-end by the
            // maintainers (catalogue + book + chapters + text
            // on the rendered /mobile/ road); the on-device
            // drive may not veto it on a slow connection.
            maintainerVerified = true
        )
        // MADARA is claimed by page markers, not by domain —
        // see LinkInference.isMadaraPage; it needs no entries.
    )

    /** The entry for [host] (exact domain or a subdomain of a
     * listed domain), or null when the site is unknown. */
    fun entryFor(host: String): Entry? {
        val normalised = host.trim().lowercase()
        if (normalised.isEmpty()) return null
        return ENTRIES.firstOrNull { entry ->
            entry.domains.any { domain ->
                normalised == domain || normalised.endsWith(".$domain")
            }
        }
    }

    /** Whether [url] looks like a BOOK page for this family —
     * the fast lane drives its candidate against a book page, so
     * it must know one when it sees one. */
    fun looksLikeBookPage(entry: Entry, url: String): Boolean {
        val path = runCatching { URI(url).path }.getOrNull()?.lowercase()
            ?: return false
        return when (entry.family) {
            Family.NOVELIGHT ->
                path.startsWith("/book/") && !path.startsWith("/book/chapter/")
            Family.NOVELFULL ->
                path.endsWith(".html") && !path.contains("/chapter-")
            Family.MADARA ->
                path.contains("/manga/") || path.contains("/novel/")
            Family.CHIKARI ->
                // /novels/<slug> is a book; /novels/<slug>/<n>
                // is a chapter of it — exactly one segment
                // under /novels/ means the book's own page.
                path.startsWith("/novels/") &&
                    path.removePrefix("/novels/").trim('/')
                        .isNotEmpty() &&
                    !path.removePrefix("/novels/").trim('/')
                        .contains('/')
            Family.BOTI ->
                // /mobile/book/<id> is a book; /mobile/chapter/
                // <id> is a chapter — never a book page.
                path.startsWith("/mobile/book/")
        }
    }

    /**
     * A FULL standalone candidate for families whose whole shape
     * is known — or null for override-only families (their
     * candidate is the inference config with [applyOverrides]
     * merged in). The caller test-drives the result before
     * anything is saved; a failed drive falls through to the
     * ordinary pipeline.
     */
    fun fullConfig(
        entry: Entry,
        id: String,
        name: String,
        baseUrl: String
    ): DeclarativeConfig? {
        val built = when (entry.family) {
            Family.NOVELIGHT -> novelightConfig(id, name, baseUrl)
            Family.CHIKARI -> chikariConfig(id, name, baseUrl)
            Family.BOTI -> botiConfig(id, name, baseUrl)
            Family.NOVELFULL, Family.MADARA -> null
        } ?: return null
        // v1.3.0 (E4): the lane's verification travels with the
        // config it produced, so the installed source and the
        // bundled-sync both know it is maintainer-verified.
        return if (entry.maintainerVerified && !built.maintainerVerified) {
            built.copy(maintainerVerified = true)
        } else {
            built
        }
    }

    /**
     * The family's verified overrides merged into an
     * inference-built config: the parts the family knows better
     * than page guessing (chapter list shape, chapter text
     * container, the scoped Madara search) replace the inferred
     * ones; everything else the inference found stays.
     */
    fun applyOverrides(entry: Entry, config: DeclarativeConfig): DeclarativeConfig =
        when (entry.family) {
            Family.NOVELIGHT -> config
            Family.CHIKARI -> config
            Family.BOTI -> config
            Family.NOVELFULL -> config.copy(
                details = config.details.copy(
                    chapters = ChapterListSpec(
                        list = "#list-chapter ul.list-chapter li",
                        name = "a@text",
                        link = "a@href",
                        reverse = false
                    )
                ),
                chapter = ChapterSpec(
                    content = "#chapter-content",
                    title = config.chapter.title,
                    nextPage = config.chapter.nextPage,
                    remove = config.chapter.remove
                )
            )
            Family.MADARA -> if (config.search == null) {
                config.copy(
                    search = LinkInference.madaraSearchSpec(config.baseUrl)
                )
            } else {
                config
            }
        }

    /**
     * Novelight's verified shape (live-checked 2026-10-29,
     * D18/D21 + the Stage 1.5 ajax verification). Known limit,
     * stated plainly: the book page carries no cover <img> of
     * its own (covers bind from the catalogue cards, whose
     * poster the engine keeps). Chapter TEXT never appears in
     * the chapter page's static HTML — the page is a shell and
     * the text comes from the JSON endpoint declared in the
     * chapter spec's ajax mode below (verified live: an old
     * unlocked chapter returns full prose; locked/newest
     * chapters return an empty content div, which the engine
     * reports as paid/locked).
     */
    private fun novelightConfig(
        id: String,
        name: String,
        baseUrl: String
    ): DeclarativeConfig {
        val base = baseUrl.trimEnd('/')
        // v1.0.6.2 (F2): the catalogue pages with ?page=N (links
        // verified in the live catalogue HTML 2026-10-30) — the
        // {page} placeholder is what lets Browse walk past the
        // first screen of books at all.
        val grid = ListSpec(
            url = "$base/catalog/?page={page}",
            results = ".manga-grid-list a.item",
            title = ".title@text",
            link = "@href",
            cover = ".poster img@src"
        )
        return DeclarativeConfig(
            id = id,
            name = name,
            lang = "en",
            baseUrl = base,
            search = grid.copy(url = "$base/catalog/?search={query}&page={page}"),
            popular = grid,
            latest = null,
            details = DetailsSpec(
                title = "h1",
                author = null,
                description = "meta[name=description]@content",
                // v1.0.6.3 (P4): the book's OWN poster is the img
                // inside the page's .poster container (verified
                // against the live book page 2026-10-30 — every
                // .image-cover img on the page is another book or
                // the translator team's avatar). This was null, so
                // book covers never resolved at all.
                cover = ".poster img@src",
                chapters = ChapterListSpec(
                    list = ".chapters a.chapter",
                    name = ".title@text",
                    link = "@href",
                    // v1.0.6.3 (P5): every chapter row carries its
                    // release date in a .date span (DD.MM.YYYY,
                    // verified in the pagination capture).
                    date = ".date@text",
                    reverse = true,
                    // v1.0.6.2 (F1): the book page renders only
                    // the newest 50 rows; the rest come from the
                    // paging endpoint the site's own scripts call
                    // (verified live 2026-10-30: {"html": …}, 50
                    // rows a page, the last page short, ending
                    // at chapter 1; BOOK_ID sits in the book
                    // page's scripts).
                    pagination = ChapterListPagination(
                        urlTemplate = "$base/book/ajax/chapter-pagination?book_id={bookId}&page={page}",
                        bookIdPattern = "BOOK_ID\\s*=\\s*\"(\\d+)\"",
                        jsonHtmlField = "html",
                        // v1.0.8: the book page's own pagination
                        // select lists every page (verified live
                        // 2026-10-30: TCF carries 34 options) —
                        // the total the progressive walk needs
                        // to start from the oldest chapters.
                        totalPagesSelector = "#select-pagination-chapter"
                    )
                )
            ),
            chapter = ChapterSpec(
                // Inside the ajax payload the prose lives in a
                // div.chapter-text (plus a random second class the
                // site rotates — the single stable class is the
                // selector). The static page's .chapter-text__place
                // holds only "Loading…" and is never read now.
                content = ".chapter-text",
                title = null,
                nextPage = null,
                ajax = ChapterTextAjax(
                    urlTemplate = "$base/book/ajax/read-chapter/{id}"
                )
            )
        )
    }

    /**
     * Chikari's verified shape (live rendered-DOM inspection
     * 2026-10-30). The site is a SvelteKit app: every page is a
     * shell until its data arrives (~8 s on a fast connection —
     * the loading state shows ONLY a logo and spinner, no text
     * or links at all), so the lane is marked needsRendering
     * and declares the wait selector its content pages carry.
     * Books live at /novels/<slug>; chapters at
     * /novels/<slug>/<n>; prose sits in an <article>; the book
     * page states its total as plain text ("3229 chapters")
     * while initially rendering only 20 chapter rows, so the
     * chapter spec completes the list by pattern.
     *
     * Known limits, stated plainly: selectors are class-free
     * (the inspection could verify tags/hrefs/text, not class
     * names) — href shapes carry the precision instead. The
     * book page's synopsis has no verified container, so
     * description stays null rather than guess; covers bind
     * from the catalogue cards, whose <img alt> is the title.
     * Chapter rows' own text carries list chrome (relative
     * dates, a "Mark read" button), so pattern-completed rows
     * keep generated names — see DeclarativeSource.
     */
    private fun chikariConfig(
        id: String,
        name: String,
        baseUrl: String
    ): DeclarativeConfig {
        val base = baseUrl.trimEnd('/')
        // The home page's book cards ARE anchors: the cover's
        // alt is the book's title, the href its page. Comics
        // live under /series/ and are not this app's business;
        // the /novels/ href shape keeps the lane novels-only.
        val grid = ListSpec(
            url = base,
            results = "a[href*='/novels/']",
            title = "img@alt",
            link = "@href",
            cover = "img@src",
            // v1.1.0 (design §6.1, Shape B): the Book-Shape
            // Rule as data. The live rendered href shapes
            // could not be re-verified clean in-build (the
            // site's fetch timed out and its search index
            // exposes no hrefs), so the `$`-anchored selector
            // (Shape A) was NOT trusted: this pattern is
            // enforced by the engine over the NORMALISED path
            // (query/fragment stripped, trailing slash
            // removed), which admits clean AND slash/query
            // hrefs while dropping chapter-shaped comment
            // anchors (/novels/<slug>/<n>) either way.
            linkPattern = "/novels/[^/]+$"
        )
        return DeclarativeConfig(
            id = id,
            name = name,
            lang = "en",
            baseUrl = base,
            search = null,
            popular = grid,
            latest = null,
            needsRendering = true,
            // The content the lane reads: the chapter article,
            // or a novels anchor (chapter rows / book cards) —
            // neither exists in the loading state, so a render
            // cannot settle on the spinner.
            renderWaitSelector = "article, a[href*='/novels/']",
            details = DetailsSpec(
                title = "h1",
                author = "a[href*='/authors/']@text",
                description = null,
                cover = null,
                chapters = ChapterListSpec(
                    // Chapter rows are bare anchors whose href
                    // ends in the chapter number (the regex is
                    // jsoup's ~= — the engine's selectors are
                    // jsoup, not browser CSS). Book links (one
                    // segment) and author links never match.
                    // The pattern must stay UNQUOTED: jsoup
                    // treats quotes as part of the pattern, so a
                    // quoted regex silently matches nothing
                    // (v1.0.8.1 gate run 1: 834/835, proven
                    // against the real jsoup jar locally).
                    list = "a[href~=/novels/[^/]+/[0-9]+$]",
                    name = "@text",
                    link = "@href",
                    reverse = true,
                    urlPattern = "{bookUrl}/{n}",
                    totalCountPattern = "([\\d,]+)\\s+chapters"
                )
            ),
            chapter = ChapterSpec(
                content = "article",
                title = null
            )
        )
    }

    /**
     * v1.2.3: BotiTranslation's shape. The site is a Vite SPA —
     * the static HTML is an empty shell, so the config reads
     * the RENDERED document (needsRendering) and its catalogue
     * is the /mobile/ home's book rails: cards that are anchors
     * to /mobile/book/<id> with the cover's alt as the title.
     * Chapters are /mobile/chapter/<id> rows on the book page;
     * prose sits in <article>. Search: none derivable — the
     * lane ships search-less and Health labels it honestly.
     *
     * Verification status, stated plainly: the rendered DOM
     * cannot be captured from the build environment (the
     * site's app only renders in a real browser), so this
     * shape is verified against fixtures modelled from the
     * site's own JS bundle (its route table and card
     * templates) and the user's on-device screenshots — the
     * same evidence base as the v1.2.1 Live-catalogue fix,
     * which the user confirmed working on-device.
     */
    private fun botiConfig(
        id: String,
        name: String,
        baseUrl: String
    ): DeclarativeConfig {
        val base = baseUrl.trimEnd('/')
        val grid = ListSpec(
            url = "$base/mobile/",
            results = "a[href*='/mobile/book/']",
            title = "img@alt",
            link = "@href",
            cover = "img@src",
            linkPattern = "/mobile/book/[^/]+$"
        )
        return DeclarativeConfig(
            id = id,
            name = name,
            lang = "en",
            baseUrl = base,
            search = null,
            popular = grid,
            latest = null,
            needsRendering = true,
            renderWaitSelector = "a[href*='/mobile/book/'], article",
            details = DetailsSpec(
                title = "h1",
                author = null,
                description = null,
                cover = "img.cover@src",
                chapters = ChapterListSpec(
                    list = "a[href*='/mobile/chapter/']",
                    name = "@text",
                    link = "@href",
                    reverse = false
                )
            ),
            chapter = ChapterSpec(
                content = "article",
                title = null
            ),
            // v1.3.4 (T1, session v2 — TOKEN-SESSION-RESEARCH
            // §2): BOTI's own client keeps its login token in
            // a JS-set cookie (userToken, on the parent
            // domain) and replays it VERBATIM as the
            // Authorization header to its API host,
            // api.mystorywave.com. Cookie capture alone could
            // never reach that host; the declared binding is
            // the missing half. Origin-exact: the token goes
            // to api.mystorywave.com ONLY (declared in
            // extraDomains so the whitelist can reach it),
            // never to any other host. The lane's rendered
            // legs are unaffected — the browser view runs the
            // site's own client, which authenticates itself;
            // the binding serves the native fetches.
            extraDomains = listOf("api.mystorywave.com"),
            credentials = CredentialSpec(
                headerBinding = HeaderBinding(
                    cookieName = "userToken",
                    headerName = "Authorization",
                    prefix = "",
                    hosts = listOf("api.mystorywave.com")
                )
            )
        )
    }
}
