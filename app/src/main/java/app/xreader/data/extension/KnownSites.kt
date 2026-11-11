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

    enum class Family { MADARA, NOVELFULL, NOVELIGHT, CHIKARI, BOTI, XNOVEL }

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
        // before it can save.
        // v1.5.7 (Scope E): the lane's recipe is now the
        // site's own JSON API (see botiConfig) — the rendered
        // /mobile/ road never completed a catalogue on the
        // owner's phone (v1.5.5/v1.5.6 proofs: the false wall
        // fell, the catalogue stayed empty), while the API
        // answers every leg anonymously and carries the
        // search endpoint the old lane predates, so the
        // search hint is finally true.
        Entry(
            domains = listOf("botitranslation.com", "www.botitranslation.com"),
            family = Family.BOTI,
            canSearch = true,
            // v1.3.0 (E4): verified end-to-end by the
            // maintainers (catalogue + book + chapters + text
            // on the rendered /mobile/ road); the on-device
            // drive may not veto it on a slow connection.
            maintainerVerified = true
        ),
        // v1.5.35 (Item 5): xnovel.me — a Qwik City app whose
        // data channel is q-data.json on every route (see
        // xnovelConfig + build-logs/XNOVEL-SOURCE-SPEC.md).
        // Catalogue, search, details and the FULL chapter
        // list are proven live, anonymous, over plain HTTP;
        // the chapter TEXT routes are Cloudflare
        // managed-challenged from bare HTTP and ride the
        // app's standard gated-source road (one in-app check
        // solve, session cookies after) — hence verified
        // end-to-end by the maintainers on every leg this
        // lane declares, with the text leg's on-device proof
        // still the owner's.
        Entry(
            domains = listOf("xnovel.me", "www.xnovel.me"),
            family = Family.XNOVEL,
            canSearch = true,
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
            Family.XNOVEL -> {
                // /{bookId}-{slug} is a book (one segment,
                // digit-led); /{bookId}-{slug}/{chapterId}-
                // {slug} is a chapter — never a book page.
                val segment = path.trim('/')
                !segment.contains('/') && segment.contains('-') &&
                    segment.substringBefore('-')
                        .all { it.isDigit() }
            }
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
            Family.XNOVEL -> xnovelConfig(id, name, baseUrl)
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
            Family.XNOVEL -> config
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
     * BotiTranslation's shape — v1.5.7 (Scope E) REWRITE.
     *
     * History: v1.2.3 shipped this lane as a RENDERED-DOM
     * recipe (the /mobile/ SPA: catalogue cards, chapter
     * anchors, prose in <article>, needsRendering with a
     * wait selector). That road never completed on the
     * owner's phone — v1.5.5's proof met a false human-check
     * wall, v1.5.6 removed the wall and the listed catalogue
     * still came back empty (the honest signed-in-empty):
     * every leg depended on the hidden browser rendering a
     * Vite shell whose data lives behind the site's API.
     *
     * The recipe below is the site's OWN JSON API — the
     * story-wave backend the site's client (and the
     * maintained Keiyoushi novel extension for this site)
     * calls — driven through the declarative engine's
     * existing JSON machinery, the FenrirRealm/Chikari
     * pattern, with NO rendering anywhere:
     *
     *  - catalogue: GET {api}/books?pageNumber={page} —
     *    records at `data.list` ({id, title, authorPseudonym,
     *    coverImgUrl, synopsis}); a book's id IS its API
     *    detail URL (jsonLinkTemplate), so the details leg
     *    below reads the same record the catalogue row came
     *    from;
     *  - search: GET {api}/books/search?keyWord={query} —
     *    the endpoint the v1.2.3 lane predates;
     *  - book: GET {api}/books/{id} — the envelope's
     *    `data.coverImgUrl` heals the cover, the payload
     *    ladder reads `data.synopsis` as the description,
     *    title/author ride the catalogue row;
     *  - chapters: the pagination endpoint walk over
     *    {api}/chapters/page?bookId={bookSlug} — the book
     *    URL's last segment IS the book id for BOTH id
     *    shapes this source has ever issued (the API detail
     *    URL and the legacy /mobile/book/<id> page), so
     *    books stored by the old lane keep working; rows
     *    carry {id, title, chapterOrder, paywallStatus},
     *    addressed ASC, totals at `data.totalCount`;
     *  - text: the chapter-text ajax road over
     *    {api}/chapters/{id} — {id} is the chapter URL's
     *    trailing numeric segment, which the legacy
     *    /mobile/chapter/<id> ids also carry, so old
     *    chapters heal too; the prose is the HTML string
     *    at `data.content`. A charged chapter answers
     *    with no readable content and surfaces as the
     *    engine's paid/locked failure, never an empty
     *    chapter and never a fake success.
     *
     * Every leg is a plain HTTP call through the source's
     * own client, so the session contract below (the
     * userToken header binding + the site's static
     * interceptor headers) applies exactly as declared —
     * anonymously the API answers all five legs (live-
     * probed 2026-11-05 from the build sandbox: catalogue
     * totalCount 19901 under the site-domain header,
     * search/book/chapters/text all 200 with the recorded
     * envelope), and a signed-in session's token unlocks
     * whatever the site reserves for accounts. No leg
     * needs the browser view, so needsRendering and the
     * render wait selector are GONE: a bare-JSON answer
     * could never satisfy a rendered-HTML wait selector,
     * which was the old road's structural dead end.
     */
    private fun botiConfig(
        id: String,
        name: String,
        baseUrl: String
    ): DeclarativeConfig {
        val base = baseUrl.trimEnd('/')
        val api = "https://api.mystorywave.com" +
            "/story-wave-backend/api/v1/content"
        val listing = ListSpec(
            url = "$api/books?pageNumber={page}&pageSize=20",
            results = "",
            title = "title",
            link = "id",
            cover = "coverImgUrl",
            author = "authorPseudonym",
            jsonPath = "data.list",
            jsonLinkTemplate = "$api/books/{value}"
        )
        return DeclarativeConfig(
            id = id,
            name = name,
            lang = "en",
            baseUrl = base,
            search = listing.copy(
                url = "$api/books/search" +
                    "?keyWord={query}&pageNumber={page}&pageSize=20"
            ),
            popular = listing,
            latest = null,
            needsRendering = false,
            renderWaitSelector = null,
            details = DetailsSpec(
                title = null,
                author = null,
                description = null,
                cover = null,
                jsonCover = "data.coverImgUrl",
                chapters = ChapterListSpec(
                    // The DOM row spec is the fallback for
                    // the one book-page shape that still has
                    // a DOM: a legacy /mobile/book/<id> page
                    // a pre-v1.5.7 install stored, whose
                    // rendered rows the endpoint walk then
                    // dedupes against (the endpoint is the
                    // road whenever it answers).
                    list = "a[href*='/mobile/chapter/']",
                    name = "@text",
                    link = "@href",
                    reverse = false,
                    pagination = ChapterListPagination(
                        urlTemplate = "$api/chapters/page" +
                            "?bookId={bookSlug}" +
                            "&pageNumber={page}&pageSize=100" +
                            "&sortDirection=ASC",
                        bookIdPattern = "",
                        jsonArrayPath = "data.list",
                        jsonTotalPath = "data.totalCount",
                        pageSize = 100
                    ),
                    jsonNameField = "title",
                    jsonLinkField = "id",
                    jsonLinkTemplate = "$api/chapters/{value}"
                )
            ),
            chapter = ChapterSpec(
                content = "body",
                title = null,
                ajax = ChapterTextAjax(
                    urlTemplate = "$api/chapters/{id}",
                    jsonContentPath = "data.content"
                )
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
            // never to any other host. Since v1.5.7 every
            // leg is a native fetch, so the binding now
            // serves the whole source, not a side road.
            // v1.5.7 (Scope E): the site's own interceptor
            // headers ride along as static bindings — the
            // API answers a DIFFERENT catalogue without them
            // (live-probed 2026-11-05: no site-domain header
            // → totalCount 17825 across all orgs; with
            // site-domain: botitranslation.com → 19901, the
            // site's own catalogue; lang pins the locale).
            // The harvest-derived road has carried these
            // since v1.5.2 (§10 item 1); the lane now
            // declares them itself. site-domain is the
            // config's own web host, so a www paste declares
            // the www spelling the probe verified answers
            // the same org.
            extraDomains = listOf("api.mystorywave.com"),
            credentials = CredentialSpec(
                headerBinding = HeaderBinding(
                    cookieName = "userToken",
                    headerName = "Authorization",
                    prefix = "",
                    hosts = listOf("api.mystorywave.com")
                ),
                staticHeaders = listOf(
                    StaticHeaderBinding(
                        headerName = "lang",
                        value = "en_US",
                        hosts = listOf("api.mystorywave.com")
                    ),
                    StaticHeaderBinding(
                        headerName = "site-domain",
                        value = base.normaliseHost(),
                        hosts = listOf("api.mystorywave.com")
                    )
                )
            )
        )
    }

    /**
     * Xnovel's shape — v1.5.35 (Item 5, per
     * build-logs/XNOVEL-SOURCE-SPEC.md + its addendum; every
     * leg below live-probed from the build VM 2026-11-07 and
     * re-verified at build time).
     *
     * The site is a Qwik City app: every page route R serves
     * its loader data at R/q-data.json in Qwik's `_objs`
     * graph form. The engine's one native addition for this
     * lane is the fetch pre-step ([QDataResolve]): payloads
     * are resolved and re-emitted as a plain envelope —
     * `books` / `book` / `chapters` / `content` / `paging` —
     * and every mapping below reads that envelope with the
     * engine's ordinary JSON machinery, exactly as BOTI
     * reads its API.
     *
     *  - catalogue: GET /popular|latest/q-data.json?page=N —
     *    20 records a page; a book's link IS its data twin
     *    (jsonLinkTemplate appends /q-data.json), so the
     *    book id the app stores is the details URL itself,
     *    the BOTI shape;
     *  - search: GET /search/q-data.json?word={query} —
     *    page size 36, same record shape;
     *  - book: GET {book}/q-data.json — title/author ride
     *    the listing row (BOTI pattern); the details leg
     *    heals the cover from the payload's own record and
     *    the description from the envelope's book record;
     *  - chapters: page 1 is the book payload's own window
     *    (newest 45, `jsonEndpointPath` seeds it), pages
     *    2..N come from the site's own data API —
     *    POST /n2/, GraphQL get_NovelsChaptersList,
     *    PLAINTEXT body (no crypto header; replay-proven
     *    byte-identical to the encrypted form, session-free)
     *    — walked by the declared pagination until the
     *    stated total (paging.total) is gathered; rows are
     *    the endpoint's `items[].data` records, merged and
     *    de-duped by chapter URL, ordered by the numbers
     *    their titles carry;
     *  - text: GET {chapter}/q-data.json, the record's
     *    `content` field hoisted by the shim. The chapter
     *    routes are Cloudflare managed-challenged from bare
     *    HTTP (403, "Just a moment…", verified twice while
     *    sibling routes stayed 200): the leg rides the
     *    app's standard gated-source road — the fetch layer
     *    escalates a block to the browser view, the owner
     *    solves the check once in-app, and the session's
     *    cookies replay on this same fetch afterwards. No
     *    bypass, no solver, no stored password.
     *
     * Rating: the site's per-book genre slugs carry the
     * adult signal (adult/mature/ecchi/smut — the proof book
     * carries four of them), and the app's rating model is
     * per-SOURCE (the Mihon model, ContentRatings): a
     * catalogue that mixes general and adult books is MIXED,
     * which is what this source declares.
     */
    private fun xnovelConfig(
        id: String,
        name: String,
        baseUrl: String
    ): DeclarativeConfig {
        val base = baseUrl.trimEnd('/')
        val listing = ListSpec(
            url = "$base/popular/q-data.json?page={page}",
            results = "",
            title = "N_title",
            link = "N_Path",
            cover = "urlCoverOri",
            author = "info_meta_Author.0",
            jsonPath = "books",
            jsonLinkTemplate = "$base{value}/q-data.json",
            jsonCoverTemplate = "$base{value}",
            // Books live at /{id}-{slug}; anything else the
            // payload classifies as a record is not a book row.
            linkPattern = "^/\\d+-[^/]+/q-data\\.json$"
        )
        return DeclarativeConfig(
            id = id,
            name = name,
            lang = "en",
            baseUrl = base,
            search = listing.copy(
                url = "$base/search/q-data.json" +
                    "?word={query}&page={page}"
            ),
            popular = listing,
            latest = listing.copy(
                url = "$base/latest/q-data.json?page={page}"
            ),
            needsRendering = false,
            renderWaitSelector = null,
            // The catalogue mixes general and adult books
            // (genre evidence above) — the per-source model's
            // MIXED.
            contentRating = ContentRating.MIXED,
            details = DetailsSpec(
                title = null,
                author = null,
                description = null,
                cover = null,
                jsonCover = "book.urlCoverOri",
                jsonCoverTemplate = "$base{value}",
                chapters = ChapterListSpec(
                    // No DOM road exists on a q-data page (the
                    // document is the payload itself); the bare
                    // selector is deliberately UNSCOPED, so the
                    // endpoint road can never be "substituted"
                    // by a furniture sweep — it answers or the
                    // list honestly fails.
                    list = "a",
                    name = "@text",
                    link = "@href",
                    reverse = false,
                    // Page 1: the book payload's own chapter
                    // window (newest 45 + the first chapter),
                    // resolved into the envelope's `chapters`
                    // in ch_index order by the shim.
                    jsonEndpointPath = "chapters",
                    jsonNameField = "ch_title",
                    jsonLinkField = "ChPath",
                    jsonLinkTemplate = "$base{value}",
                    pagination = ChapterListPagination(
                        urlTemplate = "$base/n2/",
                        // The book id out of the envelope the
                        // shim made of the book payload.
                        bookIdPattern = "\"N_id\"\\s*:\\s*\"(\\d+)\"",
                        headers = mapOf(
                            "Content-Type" to "application/json"
                        ),
                        method = "POST",
                        body = "{\"query\":\"query " +
                            "get_NovelsChaptersList(\$select: " +
                            "NovelsChaptersList_Select) { " +
                            "get_NovelsChaptersList(select: " +
                            "\$select) { paging { total pages " +
                            "page init size skip limit prev " +
                            "next } items { id data { N_id " +
                            "N_title ch_id status dbStatus " +
                            "ch_index ch_title content ChPath " +
                            "is_new date_create date_update " +
                            "isFinal comments_topic " +
                            "comments_total views_login " +
                            "views_guest } } } }\"" +
                            ",\"variables\":{\"select\":" +
                            "{\"N_id\":\"{bookId}\",\"init\":45," +
                            "\"size\":45,\"page\":{page}," +
                            "\"sortBy\":\"desc\"}}}",
                        jsonArrayPath =
                            "data.get_NovelsChaptersList.items",
                        jsonTotalPath =
                            "data.get_NovelsChaptersList.paging.total",
                        pageSize = 45
                    )
                )
            ),
            chapter = ChapterSpec(
                content = "body",
                title = null,
                ajax = ChapterTextAjax(
                    // {seg-2}/{seg-1} are the chapter URL's own
                    // book and chapter segments — the data twin
                    // of the chapter page itself.
                    urlTemplate = "$base/{seg-2}/{seg-1}/q-data.json",
                    jsonContentPath = "content"
                )
            )
        )
    }
}
