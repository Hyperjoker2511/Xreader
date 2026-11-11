package app.xreader.data.extension

/**
 * v1.3.0: the single-site bundled defaults — sites whose verified
 * recipe doesn't share a family builder (mostly the JSON-API
 * sites the verification programme discovered: the pattern of
 * the programme was that "hard" sites run plain JSON APIs, and
 * these configs speak to those APIs directly). Every shape was
 * proven live at bundle time; evidence in fixtures-capture/,
 * verdicts in build-logs/V130-BUILD-REPORT.md.
 */
internal object BundledSingles {

    /** Chikari (chikari.moe) — OUR API lane (QuickNovel's is
     * broken by its own code: it fabricates contiguous rows
     * from one page; the user's carve-out stands). The site's
     * own JSON API: search doubles as the catalogue (an empty
     * query returns nothing, so the popular slot carries the
     * broad single-letter query the harness drive proved —
     * 20 real books with covers), chapters page by limit/offset
     * against the API's stated total, text from the read
     * endpoint whose `body` is plain-text prose and whose
     * `locked` flag names paid chapters. Proven end-to-end from
     * this machine: 3,231/3,231 chapters, two chapters of prose.
     *
     * v1.4.4: the chapters envelope re-verified live
     * (2026-11-02): `{"items":[…],"total":N,"limit":L,
     * "offset":O}` — the endpoint's own page size is 100 (a
     * bare request defaults to it; larger limits are clamped
     * to 500). The walk now pages at that native 100 per
     * request — the progressive-loading design: the list
     * grows a page at a time as the reader scrolls, never a
     * bulk fetch of a 3,000-chapter list up front. */
    fun chikari(id: String, name: String): DeclarativeConfig {
        val listing = ListSpec(
            url = "https://chikari.moe/api/novels/search?q=a&limit=20",
            results = "",
            title = "title",
            link = "slug",
            cover = "cover_url",
            jsonPath = "",
            jsonLinkTemplate = "https://chikari.moe/api/novels/{value}"
        )
        return DeclarativeConfig(
            id = id,
            name = name,
            baseUrl = "https://chikari.moe",
            search = listing.copy(
                url = "https://chikari.moe/api/novels/search?q={query}&limit=20"
            ),
            popular = listing,
            details = DetailsSpec(
                // The book "page" is the API's metadata document;
                // its DOM is the payload text, so details ride
                // the catalogue row (title + cover) and the
                // chapter machinery below does the real work.
                // v1.5.3 (finding #3): the payload's own cover
                // field, so a stored coverless book can heal
                // from its book document (class C).
                jsonCover = "cover_url",
                chapters = ChapterListSpec(
                    list = "a",
                    name = "@text",
                    link = "@href",
                    reverse = false,
                    pagination = ChapterListPagination(
                        urlTemplate = "https://chikari.moe/api/novels/{bookSlug}/chapters?order=asc&limit={limit}&offset={offset}",
                        bookIdPattern = "",
                        jsonArrayPath = "items",
                        jsonTotalPath = "total",
                        pageSize = 100
                    ),
                    jsonNameField = "title",
                    jsonLinkField = "number",
                    jsonDateField = "created_at",
                    jsonLinkTemplate = "https://chikari.moe/novels/{bookSlug}/{value}"
                )
            ),
            chapter = ChapterSpec(
                content = "body",
                ajax = ChapterTextAjax(
                    urlTemplate = "https://chikari.moe/api/novels/{seg-2}/chapters/{seg-1}/read",
                    jsonContentField = "body",
                    plainTextField = true,
                    jsonLockField = "locked"
                )
            )
        )
    }

    /** FenrirRealm (fenrirealm.com) — v1.3.2 (items 75/75b,
     * the LOGIN RULE): a sign-in-supported default. The site's
     * own JSON API reads anonymously for catalogue, search,
     * book and the FULL chapter list in one answer (verified
     * live at capture: 896/896 chapters on the drive book,
     * records {slug = the chapter number as text, title,
     * number}); chapter TEXT answers `content: ""` with
     * `content_format: "locked"` until the reader signs in
     * once — the app shows the site's own sign-in page in the
     * sanctioned screen, keeps only the session cookies, and
     * the same fetch then carries the prose. Labelled "Sign
     * in needed to read" ([DeclarativeConfig.signInNeeded]).
     * Covers are bare "storage/…" paths serving from the
     * site's root — hence the cover template. The chapter
     * row's id IS the API chapter URL, so the text ajax
     * derives the series slug as {seg-3} and the chapter
     * number as {seg-1}.
     *
     * v1.3.4 (T1, session v2 — TOKEN-SESSION-RESEARCH §1):
     * the v1.3.2 comment above was HALF the truth, and the
     * missing half was the v1.3.3 sign-in loop: the account
     * cookie alone never unlocks a chapter. The site runs
     * TWO credentials — the account session (a cookie) and a
     * short-lived READ TOKEN (~5 min) its client mints from
     * that session (`POST /api/read-token`) and replays as
     * `Authorization: Bearer` on chapter calls. The contract
     * is declared below ([CredentialSpec.operationToken]);
     * the coordinator (ReadTokenCoordinator) mints
     * single-flight on a locked answer, retries once with
     * the token, and maps a mint refusal by its own shape
     * (challenge_required → the check, run on the site's
     * READER page per [OperationTokenSpec.checkUrlTemplate];
     * auth-shaped → sign in again). The encrypted-content
     * opt-in is never sent and nothing is ever decrypted. */
    fun fenrirRealm(id: String, name: String): DeclarativeConfig {
        val listing = ListSpec(
            url = "https://fenrirealm.com/api/new/v2/series" +
                "?page={page}&per_page=24&sort=popular",
            results = "",
            title = "title",
            link = "slug",
            cover = "cover",
            author = "user.name",
            jsonPath = "data",
            jsonLinkTemplate =
                "https://fenrirealm.com/api/new/v2/series/{value}",
            jsonCoverTemplate = "https://fenrirealm.com/{value}",
            // v1.5.2 (§7): tier-1 per-item kind evidence — the
            // API's own `type` field, live-evidenced 2026-11-02.
            kindField = "type",
            kindValueMap = app.xreader.data.extension.BookKind152.FENRIR_TYPE_MAP
        )
        return DeclarativeConfig(
            id = id,
            name = name,
            baseUrl = "https://fenrirealm.com",
            signInNeeded = true,
            search = listing.copy(
                url = "https://fenrirealm.com/api/new/v2/series" +
                    "?search={query}&per_page=24"
            ),
            popular = listing,
            latest = listing.copy(
                url = "https://fenrirealm.com/api/new/v2/series" +
                    "?page={page}&per_page=24"
            ),
            details = DetailsSpec(
                // v1.5.3 (finding #3, fix A4.2): the series
                // endpoint's payload IS the book document, and
                // its `cover` field (a bare storage path — the
                // template expands it exactly as the listing
                // spec's does) is the only cover that document
                // can ever declare; without it a stored
                // coverless Fenrir book could never heal.
                jsonCover = "cover",
                jsonCoverTemplate = "https://fenrirealm.com/{value}",
                // v1.5.8 (B1 / F3 Fix 1): the series payload's
                // own `type` — the SAME tier-1 evidence the
                // listing declares, read from the book's own
                // document at every refresh so a kindless row
                // heals without a re-add (and a novel payload
                // files TEXT, the value it already carries).
                kindField = "type",
                kindValueMap = app.xreader.data.extension.BookKind152.FENRIR_TYPE_MAP,
                chapters = ChapterListSpec(
                    list = "a",
                    name = "@text",
                    link = "@href",
                    reverse = false,
                    pagination = ChapterListPagination(
                        urlTemplate = "https://fenrirealm.com/api/new/v2" +
                            "/series/{bookSlug}/chapters",
                        bookIdPattern = "",
                        // The endpoint answers the whole list
                        // as a bare JSON array (the root).
                        jsonArrayPath = ""
                    ),
                    jsonNameField = "title",
                    jsonLinkField = "slug",
                    jsonLinkTemplate = "https://fenrirealm.com/api/new/v2" +
                        "/series/{bookSlug}/chapters/{value}",
                    jsonDateField = "created_at"
                )
            ),
            chapter = ChapterSpec(
                content = "body",
                // v1.5.37 (combined build): the site plants
                // anti-scrape DECOY elements inside the html
                // content payload (aria-hidden scramble divs,
                // attribution marks) — rendered raw, they were
                // the gibberish blocks between the real
                // paragraphs in the owner's v1.5.35 proof
                // (FENRIR-TEXT-GIBBERISH-FORENSICS §5). Both
                // working readers strip exactly these markers
                // (LNReader's Fenrir cleaner; QuickNovel's
                // FenrirRealProvider); the selectors key on
                // site-applied non-content markers only, never
                // on prose, so the story text cannot match.
                remove = listOf(
                    "[aria-hidden=\"true\"]",
                    ".reader-attribution",
                    "[data-fr-attr]",
                    "style",
                    "script"
                ),
                ajax = ChapterTextAjax(
                    urlTemplate = "https://fenrirealm.com/api/new/v2" +
                        "/series/{seg-3}/chapters/{seg-1}",
                    jsonContentField = "content"
                )
            ),
            // v1.3.4 (T1): the read-token contract — mint on
            // the site origin with the account session, token
            // rides Bearer to the same host only, and the
            // human check (when the mint demands one) runs on
            // the site's own READER page ({seg-3} = the series
            // slug and {seg-1} = the chapter number of the API
            // chapter URL — the web reader's own route shape,
            // live-verified: /series/<slug>/<n>).
            credentials = CredentialSpec(
                operationToken = OperationTokenSpec(
                    mintUrl = "https://fenrirealm.com/api/read-token",
                    mintBodyTemplate = "{\"chapter_id\":\"{chapter_id}\"}",
                    checkUrlTemplate =
                        "https://fenrirealm.com/series/{seg-3}/{seg-1}",
                    // TOKEN-SESSION research: the token lives
                    // ~300 s; renew ~120 s early so a chapter
                    // fetch never rides a dying token.
                    renewalMarginSeconds = 120
                )
            )
        )
    }

    /** NovelBuddy (novelbuddy.me) — API default, verified live:
     * catalogue/search from api.novelbuddy.me (search's text
     * parameter is `q`; the catalogue's sort call takes an empty
     * `query=`), the FULL chapter list from
     * /titles/{id}/chapters with the id read out of the book
     * page's embedded payload (3,229/3,229 on the drive book),
     * details and chapter text from the book/chapter pages. */
    fun novelBuddy(id: String, name: String): DeclarativeConfig {
        val listing = ListSpec(
            url = "https://api.novelbuddy.me/titles/search?query=&status=all&sort_by=popular&page={page}&limit=24",
            results = "",
            title = "name",
            link = "url",
            cover = "cover",
            jsonPath = "data.items",
            jsonLinkTemplate = "https://novelbuddy.me{value}"
        )
        return DeclarativeConfig(
            id = id,
            name = name,
            baseUrl = "https://novelbuddy.me",
            extraDomains = listOf("api.novelbuddy.me"),
            search = listing.copy(
                url = "https://api.novelbuddy.me/titles/search?q={query}&page=1&limit=24"
            ),
            popular = listing,
            details = DetailsSpec(
                title = "h1",
                author = "a[href*='/authors/']",
                description = "div.mb-5 p",
                chapters = ChapterListSpec(
                    list = "a",
                    name = "@text",
                    link = "@href",
                    reverse = true,
                    pagination = ChapterListPagination(
                        urlTemplate = "https://api.novelbuddy.me/titles/{bookId}/chapters",
                        bookIdPattern = "initialManga\":\\{\"id\":\"([^\"]+)\"",
                        jsonArrayPath = "data.chapters"
                    ),
                    jsonNameField = "name",
                    jsonLinkField = "url",
                    jsonDateField = "updated_at"
                )
            ),
            chapter = ChapterSpec(content = "div.novel-tts-content")
        )
    }

    /** NovelBin — the site now lives at novelping.com
     * (novelbin.com serves the same catalogue under a different
     * path shape; bundling both would duplicate every book).
     * DOM catalogue, JSON search (records key books by slug),
     * and the whole chapter list in ONE JSON answer
     * (4,612/4,612 cross-checked; the book page's stated count
     * is stale, the API total is the truth). */
    fun novelPing(id: String, name: String): DeclarativeConfig {
        val cards = ListSpec(
            url = "https://novelping.com/sort/hot?page={page}",
            results = "div.list-novel div.row",
            title = "h3.novel-title a",
            link = "h3.novel-title a@href",
            cover = "img@src"
        )
        return DeclarativeConfig(
            id = id,
            name = name,
            baseUrl = "https://novelping.com",
            search = ListSpec(
                url = "https://novelping.com/api-web/novels?limit=20&page=1&status=all&sort=SEARCH_KEYWORD&genre=ALL&keyword={query}",
                results = "",
                title = "novel_name",
                link = "novel_id",
                author = "novel_author",
                jsonPath = "items",
                jsonLinkTemplate = "https://novelping.com/book/{value}"
            ),
            popular = cards,
            latest = cards.copy(
                url = "https://novelping.com/sort/updates?page={page}"
            ),
            details = DetailsSpec(
                title = "h1",
                author = ".info a",
                description = "div.desc-text",
                chapters = ChapterListSpec(
                    list = "a",
                    name = "@text",
                    link = "@href",
                    reverse = false,
                    pagination = ChapterListPagination(
                        urlTemplate = "https://novelping.com/api-web/novels/{bookSlug}/chapters?sort=asc",
                        bookIdPattern = "",
                        jsonArrayPath = "items",
                        jsonTotalPath = "pagination.total"
                    ),
                    jsonNameField = "chapter_name",
                    jsonLinkField = "chapter_id",
                    jsonLinkTemplate = "https://novelping.com/book/{bookSlug}/{value}"
                )
            ),
            chapter = ChapterSpec(content = "#chr-content")
        )
    }

    /** Web Novel Translations (wntl.net) — a React SPA over a
     * small JSON API (recipe cross-checked against the
     * Apache-2.0 Mangayomi index entry, then proven live):
     * the catalogue endpoint IS the search surface (the site's
     * own search is client-side over its 18-novel catalogue),
     * chapters come from /api/chapters/<slug>, and chapter URLs
     * point straight at the plain-text content endpoint.
     * Details stay empty on purpose: the SPA shell's meta tags
     * describe the SITE, not the book — the catalogue row's
     * title/author/cover are the honest metadata. */
    fun wntl(id: String, name: String): DeclarativeConfig {
        val listing = ListSpec(
            url = "https://wntl.net/api/novels",
            results = "",
            title = "title",
            link = "id",
            cover = "cover",
            author = "author",
            jsonPath = "novels",
            jsonLinkTemplate = "https://wntl.net/series/{value}"
        )
        return DeclarativeConfig(
            id = id,
            name = name,
            baseUrl = "https://wntl.net",
            search = listing,
            popular = listing,
            details = DetailsSpec(
                // v1.5.3 (finding #3): the payload's own cover
                // field heals stored coverless books (class C).
                jsonCover = "cover",
                chapters = ChapterListSpec(
                    list = "a",
                    name = "@text",
                    link = "@href",
                    reverse = false,
                    pagination = ChapterListPagination(
                        urlTemplate = "https://wntl.net/api/chapters/{bookSlug}",
                        bookIdPattern = "",
                        jsonArrayPath = "chapters"
                    ),
                    jsonNameField = "title",
                    jsonLinkField = "file",
                    jsonLinkTemplate = "https://wntl.net/api/chapter-content/{bookSlug}/{value}"
                )
            ),
            chapter = ChapterSpec(content = "body")
        )
    }

    /** Royal Road (royalroad.com) — HTML catalogue/search/book/
     * chapters (all proven live) with chapter TEXT from the
     * official API's content endpoint: the chapter pages
     * themselves refuse this machine's egress (403) while the
     * API serves the same prose (43,000 chars on the drive
     * chapter). {seg-2} of a chapter URL is its numeric id. */
    fun royalRoad(id: String, name: String): DeclarativeConfig {
        val cards = ListSpec(
            url = "https://www.royalroad.com/fictions/best-rated?page={page}",
            results = "div.fiction-list-item",
            title = "h2.fiction-title a",
            link = "h2.fiction-title a@href",
            cover = "img@src"
        )
        return DeclarativeConfig(
            id = id,
            name = name,
            baseUrl = "https://www.royalroad.com",
            // v1.5.3 (finding #3 sweep, item 14): covers serve
            // from www.royalroadcdn.com (covers-large/covers-
            // full on the recorded listing/book captures in
            // fixtures-capture/royalroad.com/) — a different
            // host from the site and its API, so cover FILES
            // need it permitted explicitly.
            extraDomains = listOf(
                "api.royalroad.com", "www.royalroadcdn.com"
            ),
            search = cards.copy(
                url = "https://www.royalroad.com/fictions/search?title={query}"
            ),
            popular = cards,
            latest = cards.copy(
                url = "https://www.royalroad.com/fictions/active-popular?page={page}"
            ),
            details = DetailsSpec(
                title = "h1",
                author = "a[href*='/profile/']",
                description = "div.description",
                chapters = ChapterListSpec(
                    list = "table#chapters tbody tr",
                    name = "a",
                    link = "a@href",
                    reverse = false
                )
            ),
            chapter = ChapterSpec(
                content = "div.chapter-inner || div.chapter-content || article",
                ajax = ChapterTextAjax(
                    urlTemplate = "https://api.royalroad.com/v1/fiction/chapters/{seg-2}",
                    jsonContentField = "content"
                )
            )
        )
    }

    /** ScribbleHub (scribblehub.com) — the table of contents is
     * newest-first: 15 rows inline, the rest from the site's
     * admin-ajax pagination POST (starting at pagenum=2; the
     * pagenum=-1 "all" form times out and is never used).
     * Search answers through the series finder. */
    fun scribbleHub(id: String, name: String): DeclarativeConfig {
        val cards = ListSpec(
            url = "https://www.scribblehub.com/latest-series/",
            results = "div.search_main_box",
            title = "div.search_title a",
            link = "div.search_title a@href",
            cover = "img@src"
        )
        return DeclarativeConfig(
            id = id,
            name = name,
            baseUrl = "https://www.scribblehub.com",
            search = cards.copy(
                url = "https://www.scribblehub.com/series-finder/?sf=1&sh={query}"
            ),
            popular = cards,
            details = DetailsSpec(
                title = ".fic_title",
                author = ".auth_name_fic",
                description = ".wi_fic_desc",
                chapters = ChapterListSpec(
                    list = "li.toc_w",
                    name = "a.toc_a",
                    link = "a.toc_a@href",
                    reverse = true,
                    pagination = ChapterListPagination(
                        urlTemplate = "https://www.scribblehub.com/wp-admin/admin-ajax.php",
                        bookIdPattern = "id=\"mypostid\" value=\"(\\d+)\"",
                        method = "POST",
                        body = "action=wi_getreleases_pagination&pagenum={page}&mypostid={bookId}"
                    )
                )
            ),
            chapter = ChapterSpec(content = "div#chp_raw")
        )
    }

    /** MtlNovel (mtlnovel.me) — the whole chapter list arrives
     * as ONE HTML fragment from /ajax/chapters/?slug= (493/493
     * on the drive book, ascending); the pagination template
     * carries no {page}, so the walk's second fetch repeats the
     * fragment and the dedupe stop ends it. */
    fun mtlNovel(id: String, name: String): DeclarativeConfig {
        val cards = ListSpec(
            url = "https://mtlnovel.me/list/?page={page}",
            results = "div.novel-box",
            title = "h3.title a",
            link = "h3.title a@href",
            cover = "img@src"
        )
        return DeclarativeConfig(
            id = id,
            name = name,
            baseUrl = "https://mtlnovel.me",
            search = cards.copy(
                url = "https://mtlnovel.me/search/?keyword={query}"
            ),
            popular = cards,
            details = DetailsSpec(
                title = "h5.m-card-header",
                description = "div.m-card.text-break",
                chapters = ChapterListSpec(
                    list = "p.update-box-chapter a",
                    name = "@text",
                    link = "@href",
                    reverse = false,
                    pagination = ChapterListPagination(
                        urlTemplate = "https://mtlnovel.me/ajax/chapters/?slug={bookSlug}",
                        bookIdPattern = ""
                    )
                )
            ),
            chapter = ChapterSpec(content = "div.content.text-break")
        )
    }

    /** NoBadNovel (nobadnovel.com) — plain server-rendered site:
     * inline chapter list (737/737), text in `div.text-base`
     * (the selectors in the research notes don't exist on the
     * live site; the capture corrected them).
     *
     * v1.5.3 (finding #3, Part B): the cards spec now roots at
     * the CARD (the results grid's direct <div> children — the
     * grid's Tailwind classes, never the site's astro-… build
     * hashes) so the cover <img> in the card's sibling cover
     * block is reachable: the v1.5.2 spec rooted at the title
     * anchor itself and declared no cover at all, so every
     * card was born coverless. Covers serve from
     * cdn.nobadnovel.com — a sibling host, not a subdomain of
     * www.nobadnovel.com — hence extraDomains (and the
     * install-path permissions refresh that lets existing
     * installs receive it). */
    fun noBadNovel(id: String, name: String): DeclarativeConfig {
        val cards = ListSpec(
            url = "https://www.nobadnovel.com/series?sort=popularity",
            results = "div.grid > div",
            title = "h4 a@text",
            link = "h4 a@href",
            cover = "img@src"
        )
        return DeclarativeConfig(
            id = id,
            name = name,
            baseUrl = "https://www.nobadnovel.com",
            extraDomains = listOf("cdn.nobadnovel.com"),
            search = cards.copy(
                url = "https://www.nobadnovel.com/series?keyword={query}"
            ),
            popular = cards,
            latest = cards.copy(url = "https://www.nobadnovel.com/series"),
            details = DetailsSpec(
                title = "h1",
                description = "meta[name=description]@content",
                chapters = ChapterListSpec(
                    list = ".chapter-list a",
                    name = "@text",
                    link = "@href",
                    reverse = false
                )
            ),
            chapter = ChapterSpec(
                content = "div.text-base || div.card-body"
            )
        )
    }

    /** FaqWiki — CORRECTED in v1.5.2 (FIX-16): faqwiki.us is
     * dead; faqwiki.xyz is the same site, all five legs proven
     * live in the conversion probe
     * (build-logs/V152-QN-CONVERSION-PROBE.md §2). The
     * correction is a host change PLUS dropping the old
     * /novel prefix (books at /{slug}/, chapters at
     * /{slug}-chapter-{n}/); every selector from the shipped
     * recipe works unchanged. The chapter list on the drive
     * book is exactly the stated 200 RAW chapters. */
    fun faqWiki(id: String, name: String): DeclarativeConfig = DeclarativeConfig(
        id = id,
        name = name,
        baseUrl = "https://faqwiki.xyz",
        search = ListSpec(
            url = "https://faqwiki.xyz/?s={query}",
            results = "article.hitmag-post",
            title = "h3.entry-title a",
            link = "h3.entry-title a@href",
            cover = "img@src"
        ),
        popular = ListSpec(
            url = "https://faqwiki.xyz/",
            results = "div.plt-page-item",
            title = "h3.plt-title a",
            link = "h3.plt-title a@href",
            cover = "img@src"
        ),
        details = DetailsSpec(
            title = "h1",
            description = "div.entry-content > p",
            chapters = ChapterListSpec(
                list = "ul.lcp_catlist li a",
                name = "@text",
                link = "@href",
                reverse = false
            )
        ),
        chapter = ChapterSpec(content = "main > article > div")
    )

    /** ScrollersPub (www.scrollerspub.com) — v1.5.2 (FIX-16),
     * converted from the QuickNovel set; full JSON-API chain
     * proven live in the conversion probe (§1): catalogue and
     * search over /api/novels, the book via the SINGULAR
     * /api/novel/{UUID} (the plural is not an API route),
     * chapters via /api/novel/{id}/chapters with offset
     * paging (the UUID is the only key — slug addressing
     * returns total 0), text via /api/chapter/{id}/read.
     * Title/author/cover ride the listing row — declarative
     * details are DOM-only (Chikari/Fenrir precedent). */
    fun scrollersPub(id: String, name: String): DeclarativeConfig {
        val listing = ListSpec(
            url = "https://www.scrollerspub.com/api/novels?search=&offset=0&limit=24",
            results = "", title = "title", link = "id",
            cover = "cover_url", author = "authors",
            jsonPath = "items",
            jsonLinkTemplate = "https://www.scrollerspub.com/api/novel/{value}"
        )
        return DeclarativeConfig(
            id = id, name = name,
            baseUrl = "https://www.scrollerspub.com",
            search = listing.copy(
                url = "https://www.scrollerspub.com/api/novels?search={query}&offset=0&limit=24"
            ),
            popular = listing,
            details = DetailsSpec(
                // v1.5.3 (finding #3): the payload's own cover
                // field heals stored coverless books (class C).
                jsonCover = "cover_url",
                chapters = ChapterListSpec(
                    list = "a", name = "@text", link = "@href",
                    reverse = false,
                    pagination = ChapterListPagination(
                        urlTemplate = "https://www.scrollerspub.com/api/novel/{bookSlug}/chapters?limit={limit}&offset={offset}",
                        bookIdPattern = "",
                        jsonArrayPath = "items",
                        jsonTotalPath = "total",
                        pageSize = 50
                    ),
                    jsonNameField = "title", jsonLinkField = "id",
                    jsonDateField = "created_at",
                    jsonLinkTemplate = "https://www.scrollerspub.com/read/{value}"
                )
            ),
            chapter = ChapterSpec(
                content = "body",
                ajax = ChapterTextAjax(
                    urlTemplate = "https://www.scrollerspub.com/api/chapter/{seg-1}/read",
                    jsonContentField = "content",
                    plainTextField = false
                )
            )
        )
    }

    /** ReadHive (readhive.org) — v1.5.2 (FIX-16), converted
     * from the QuickNovel set; all five legs proven live in
     * the conversion probe (§4). Search is the site's own
     * contract, read off its JS: POST /ajax with
     * action=search&query=… (the endpoint accepts the
     * urlencoded body the declarative POST spec sends);
     * catalogue cards are the home page's swiper slides;
     * chapter rows ride the book page (newest first, hence
     * reverse = true); text in div.prose. Chapter names
     * arrive doubled ("Chapter 25 - Chapter 25") — the
     * site's own data. */
    fun readHive(id: String, name: String): DeclarativeConfig =
        DeclarativeConfig(
            id = id, name = name, baseUrl = "https://readhive.org",
            search = ListSpec(
                url = "https://readhive.org/ajax",
                method = "POST", body = "action=search&query={query}",
                results = "", title = "title", link = "url",
                cover = "thumb",
                jsonPath = "data",
                jsonCoverTemplate = "https://readhive.org{value}"
            ),
            popular = ListSpec(
                url = "https://readhive.org/",
                results = "div.swiper-slide.w-32",
                title = "h6", link = "a@href", cover = "img@src"
            ),
            details = DetailsSpec(
                title = "h1",
                description = "h2 + div",
                chapters = ChapterListSpec(
                    list = "a.bg-accent", name = "span.ml-1",
                    link = "@href", date = "span.text-xs",
                    reverse = true
                )
            ),
            chapter = ChapterSpec(content = "div.prose")
        )

    /** WuxiaClick (wuxia.click) — v1.5.2 (FIX-16), converted
     * from the QuickNovel set; all five legs proven live in
     * the conversion probe (§5c). Next.js site: the chapter
     * list is the book page's DOM rows plus the ranged walk
     * (?page={n} on the book URL, 100 rows per range, the
     * short final range is the stop). The embedded payload's
     * ToC is deliberately NOT declared: its rows duplicate
     * the DOM range (same URLs — the merge keeps one entry
     * per URL, DOM first), so the block would add nothing
     * here; DOM + pagination enumerate the complete list
     * (the probe's pre-decided fallback shape). Chapter
     * names are the site's own glued form (number + title
     * spans; the date rides its own field via
     * time@datetime). Description is omitted: it exists
     * only inside the JSON payload with no stable DOM
     * selector (Fenrir precedent). */
    fun wuxiaClick(id: String, name: String): DeclarativeConfig {
        val cards = ListSpec(
            url = "https://wuxia.click/",
            results = "a.group",
            title = "h3", link = "@href", cover = "img@src",
            author = "p.truncate",
            linkPattern = "/novel/[^/]+$"
        )
        return DeclarativeConfig(
            id = id, name = name, baseUrl = "https://wuxia.click",
            search = cards.copy(
                url = "https://wuxia.click/search/{query}"
            ),
            popular = cards,
            details = DetailsSpec(
                title = "h1",
                chapters = ChapterListSpec(
                    list = "li.h-11 a", name = "@text",
                    link = "@href", date = "time@datetime",
                    reverse = false,
                    pagination = ChapterListPagination(
                        urlTemplate = "{bookUrl}?page={page}",
                        bookIdPattern = ""
                    )
                )
            ),
            chapter = ChapterSpec(content = "div.reader-text")
        )
    }

    /** AsianNovel (asianovel.net — single-s; the double-s domain
     * is dead) — a Fictioneer WordPress site: story cards,
     * inline chapter-group list, text in section#chapter-content. */
    fun asiaNovel(id: String, name: String): DeclarativeConfig {
        val cards = ListSpec(
            url = "https://www.asianovel.net/stories/",
            results = "li.card._story",
            title = ".card__title a",
            link = ".card__title a@href",
            cover = "img@src"
        )
        return DeclarativeConfig(
            id = id,
            name = name,
            baseUrl = "https://www.asianovel.net",
            search = cards.copy(
                url = "https://www.asianovel.net/?s={query}&post_type=fcn_story"
            ),
            popular = cards,
            details = DetailsSpec(
                title = "h1",
                author = ".author",
                description = ".story__summary",
                chapters = ChapterListSpec(
                    list = "a.chapter-group__list-item-link",
                    name = "@text",
                    link = "@href",
                    reverse = false
                )
            ),
            chapter = ChapterSpec(content = "section#chapter-content")
        )
    }

    /** WoopRead (woopread.com) — E6's answer: the WTR-LAB recipe
     * does NOT transfer (no __NEXT_DATA__, no raw_id); the site
     * is its own recipe. Catalogue anchors on the home page
     * (shape-filtered to book URLs), the FULL chapter list in
     * the book page's RSC stream (a `chapters` array inside the
     * element-tuple payload — dot path `3.chapters`; slugs are
     * used verbatim, never synthesised), search from its own
     * /api/search. Chapter text is static HTML under `main`. */
    fun woopRead(id: String, name: String): DeclarativeConfig = DeclarativeConfig(
        id = id,
        name = name,
        baseUrl = "https://woopread.com",
        search = ListSpec(
            url = "https://woopread.com/api/search?q={query}",
            results = "",
            title = "title",
            link = "slug",
            jsonPath = "novels",
            jsonLinkTemplate = "https://woopread.com/series/{value}"
        ),
        popular = ListSpec(
            url = "https://woopread.com/",
            results = "a[href^='/series/']",
            title = "img@alt",
            link = "@href",
            cover = "img@src",
            linkPattern = "/series/[^/]+$"
        ),
        details = DetailsSpec(
            title = "h1",
            author = "div.mb-4:nth-of-type(4) a",
            description = "#novel-description-content",
            chapters = ChapterListSpec(
                list = "a[href*='/chapter-']",
                name = "@text",
                link = "@href",
                reverse = false,
                jsonPath = "3.chapters",
                jsonNameField = "title",
                jsonLinkField = "slug",
                jsonLinkTemplate = "https://woopread.com/series/{bookSlug}/{value}"
            )
        ),
        chapter = ChapterSpec(content = "main")
    )

    /** Noveli (noveli.to) — a same-origin JSON API behind a
     * shell site, verified live TWICE (the first drive book's
     * merged-sequel tail chapters are empty ON THE SITE; the
     * re-drive on a clean book passed every leg: 1,194/1,194
     * chapters, first chapter 8,827 chars, last 1,799). Chapter
     * prose rides `chapter.paragraphs` — paragraph objects whose
     * text the engine joins — with the site's anti-scraping
     * watermark items skipped by id and its reader-settings
     * chrome removed by the source cleaners below. The chapter
     * endpoint refuses requests without a Referer header. */
    fun noveli(id: String, name: String): DeclarativeConfig {
        val listing = ListSpec(
            url = "https://noveli.to/api/novels?page={page}",
            results = "",
            title = "title",
            link = "slug",
            cover = "cover_image",
            author = "author",
            jsonPath = "data",
            jsonLinkTemplate = "https://noveli.to/novels/{value}"
        )
        return DeclarativeConfig(
            id = id,
            name = name,
            baseUrl = "https://noveli.to",
            search = listing.copy(
                url = "https://noveli.to/api/novels/search?q={query}&limit=20"
            ),
            popular = listing,
            details = DetailsSpec(
                // The web book page is an app shell; the honest
                // metadata rides the catalogue row, and the
                // chapter machinery below speaks to the API.
                // v1.5.3 (finding #3): the API book payload's
                // own cover field heals stored coverless books
                // (class C).
                jsonCover = "cover_image",
                chapters = ChapterListSpec(
                    list = "a",
                    name = "@text",
                    link = "@href",
                    reverse = false,
                    pagination = ChapterListPagination(
                        urlTemplate = "https://noveli.to/api/novels/{bookSlug}/chapters",
                        bookIdPattern = "",
                        jsonArrayPath = "chapters"
                    ),
                    jsonNameField = "title",
                    jsonLinkField = "id",
                    jsonLinkTemplate = "https://noveli.to/novels/{bookSlug}/chapters/{value}"
                )
            ),
            chapter = ChapterSpec(
                content = "body",
                ajax = ChapterTextAjax(
                    urlTemplate = "https://noveli.to/api/novels/{seg-3}/chapters/{seg-1}",
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to "https://noveli.to/"
                    ),
                    jsonContentPath = "chapter.paragraphs",
                    jsonItemTextField = "text",
                    jsonSkipIdPattern = "^p-(trap|wm)-",
                    plainTextField = true
                )
            ),
            cleaners = listOf(
                // The reader-settings chrome the API interleaves
                // as the first paragraphs of every chapter.
                CleanerRuleData(
                    pattern = "^(Provided by Noveli\\.to|Settings|Font Size|Font Family|Background Color|16px|A)$",
                    replacement = ""
                ),
                // The site's support/watermark paragraphs.
                CleanerRuleData(
                    pattern = ".*noveli\\.to.*",
                    replacement = "",
                    ignoreCase = true
                )
            )
        )
    }

    /** Syosetu (syosetu.com) — Japan's biggest web-novel site
     * (scope amendment: Japanese is IN). The catalogue ranks
     * live on yomou. while the books themselves live on ncode.
     * (extraDomains bridges the two); the table of contents
     * pages at 100 chapters a page through ?p=; prose sits in
     * .p-novel__body. One-shots carry their text on the book
     * page with no chapter list — serialised works are the
     * proven shape. lang "ja". */
    fun syosetu(id: String, name: String): DeclarativeConfig = DeclarativeConfig(
        id = id,
        name = name,
        lang = "ja",
        baseUrl = "https://yomou.syosetu.com",
        extraDomains = listOf("ncode.syosetu.com"),
        search = ListSpec(
            url = "https://yomou.syosetu.com/search.php?search_type=novel&word={query}&order=hyoka",
            results = "div.smpnovel_list",
            title = ".novel_h",
            link = "a.read_button@href"
        ),
        popular = ListSpec(
            url = "https://yomou.syosetu.com/rank/list/type/daily_total/",
            results = ".p-ranklist-item",
            title = ".p-ranklist-item__title a",
            link = ".p-ranklist-item__title a@href"
        ),
        details = DetailsSpec(
            title = ".p-novel__title",
            author = ".p-novel__author",
            description = "#novel_ex",
            chapters = ChapterListSpec(
                list = "a.p-eplist__subtitle",
                name = "@text",
                link = "@href",
                reverse = false,
                pagination = ChapterListPagination(
                    urlTemplate = "{bookUrl}?p={page}",
                    bookIdPattern = ""
                )
            )
        ),
        chapter = ChapterSpec(
            content = ".p-novel__body || .p-novel__text"
        )
    )

    /** WeTriedTLS (wetriedtls.com) — user-added candidate,
     * verified live to the full bar (drive in
     * fixtures-capture/wetriedtls.com/: 140-book catalogue,
     * 138/138 public chapters on the drive book, prose at both
     * ends, search). Its own JSON API on api.wetriedtls.com:
     * one 140-series boutique list (no sort params — popular
     * and latest are the same list), search via ?q=, chapter
     * pages of 100 against meta.total, text as HTML at
     * chapter.chapter_content. The series record claims ~20
     * more chapters than the public list carries — those are
     * subscription early-access that free on schedule; the
     * list's own total is the honest one. Book links point at
     * the API record (chikari pattern); covers live on
     * media.reaperscans.net, hence the extra domain. */
    fun weTriedTls(id: String, name: String): DeclarativeConfig {
        val listing = ListSpec(
            url = "https://api.wetriedtls.com/series/all",
            results = "",
            title = "title",
            link = "series_slug",
            cover = "thumbnail",
            jsonPath = "",
            jsonLinkTemplate = "https://api.wetriedtls.com/series/{value}"
        )
        return DeclarativeConfig(
            id = id,
            name = name,
            baseUrl = "https://wetriedtls.com",
            extraDomains = listOf(
                "api.wetriedtls.com", "media.reaperscans.net"
            ),
            search = listing.copy(
                url = "https://api.wetriedtls.com/series/search?q={query}&limit=20",
                jsonPath = "data"
            ),
            popular = listing,
            latest = listing,
            details = DetailsSpec(
                // v1.5.3 (finding #3): the payload's own cover
                // field heals stored coverless books (class C).
                jsonCover = "thumbnail",
                chapters = ChapterListSpec(
                    list = "a",
                    name = "@text",
                    link = "@href",
                    reverse = false,
                    pagination = ChapterListPagination(
                        urlTemplate = "https://api.wetriedtls.com/chapters/{bookSlug}?page={page}&perPage=100&order=asc",
                        bookIdPattern = "",
                        jsonArrayPath = "data",
                        jsonTotalPath = "meta.total",
                        pageSize = 100
                    ),
                    jsonNameField = "chapter_name",
                    jsonLinkField = "chapter_slug",
                    jsonDateField = "created_at",
                    jsonLinkTemplate =
                        "https://wetriedtls.com/series/{bookSlug}/{value}"
                )
            ),
            chapter = ChapterSpec(
                content = "body",
                ajax = ChapterTextAjax(
                    urlTemplate =
                        "https://api.wetriedtls.com/chapter/{seg-2}/{seg-1}",
                    jsonContentPath = "chapter.chapter_content"
                )
            )
        )
    }

    /** The NovelFire family (NovelFire novelfire.net + Novel
     * Phoenix novelphoenix.com) — RENDER-BACKED bundled
     * defaults (user decision 2026-11-01 01:17 IST: "If it is
     * Cloudflare we have web view, I can solve it."). Every
     * route answers this build machine's egress with a
     * Cloudflare 403, so NO leg could be exercised live from
     * here; the config is built from the working Mangayomi
     * extension's evidenced shapes (catalogue/search paths
     * cross-proven by the site's own robots.txt) and ships
     * needsRendering + maintainerVerified under the E4
     * proof-pending policy: it installs, Health reads
     * Working-with-limits, and the user's first open (solving
     * any one-time check) is the proof. DEVICE-PROOF-PENDING —
     * never counted among the live-verified passes. Chapters
     * use the extension's evidenced GET {book}/chapters road
     * (the QuickNovel POST ajax claim is unproven — its form
     * fields were never captured). Phoenix differs only by
     * host + its /novel/ book section, which rides in the
     * cards' own hrefs. */
    fun novelFireFamily(
        id: String,
        name: String,
        base: String
    ): DeclarativeConfig {
        val cards = ListSpec(
            url = "$base/genre-all/sort-popular/status-all/all-novel?page={page}",
            results = "ul.novel-list li.novel-item",
            title = "a@title",
            link = "a@href",
            // v1.5.3 (finding #3, addendum D1): the real cover
            // rides the LAZY attribute — every working reader
            // of this site reads data-src first, src second;
            // the shipped src-only bind read a non-blank
            // placeholder and never fell through.
            cover = "img@data-src || img@src"
        )
        return DeclarativeConfig(
            id = id,
            name = name,
            baseUrl = base,
            needsRendering = true,
            search = ListSpec(
                url = "$base/search?keyword={query}&type=both&page={page}",
                results = "ul.novel-list li.novel-item",
                title = "a@title",
                link = "a@href",
                cover = "img@data-src || img@src"
            ),
            popular = cards,
            latest = cards.copy(
                url = "$base/latest-release-novels?page={page}"
            ),
            details = DetailsSpec(
                title = "div.main-head h1.novel-title",
                author = "span[itemprop='author']",
                description = "meta[itemprop='description']@content",
                // v1.5.3 (finding #3, addendum D1): the book
                // page's own cover, donor-evidenced
                // (QuickNovel/Mangayomi/lncrawl agree), so a
                // stored book can heal from its own page.
                cover = "figure.cover img@src",
                chapters = ChapterListSpec(
                    list = "ul.chapter-list li a",
                    name = "@text",
                    link = "@href",
                    reverse = false,
                    pagination = ChapterListPagination(
                        urlTemplate = "{bookUrl}/chapters?page={page}",
                        bookIdPattern = ""
                    )
                )
            ),
            chapter = ChapterSpec(
                content = "div#content || div.chapter-content"
            )
        )
    }

    /** BrightNovel (brightnovels.com — the plural host; the
     * singular is a parked domain) — v1.5.3 (finding #2): the
     * config REWRITTEN to the site's real wire, read off
     * QuickNovel's working provider for this site (facts
     * only). The site is an Inertia.js app: every payload
     * rides the page's `div#app[data-page]` JSON, there are
     * no /series anchors and no `url` fields — the v1.5.2
     * transcription named shapes the site never had, so the
     * catalogue read nothing. The shapes below are the
     * provider's own: catalogue records at
     * props.seriesList.data of the /series page, search
     * records at data.series of /api/search, links built
     * from `slug`, covers at cover.url; the chapter list is
     * the chapters/free endpoint's top-level `chapters`
     * array ({name, slug, updated_at, is_premium} — the
     * provider reads it newest-first and reverses, hence
     * reverse = true), chapter links
     * /series/{bookSlug}/{chapterSlug}; chapter text is the
     * chapter page payload's props.chapter.content, named
     * here as the content spec's embedded-JSON path (engine
     * item 5b). Details metadata rides the catalogue/search
     * rows (Noveli precedent for app-shell sites). The
     * render wait names the payload root itself, so a
     * render that lands before the payload exists is a
     * failed render, not an empty catalogue (§4 step 1).
     * These shapes are fixture-proven at mechanism level
     * (WS-2's item 7 suite); the on-device leg is still
     * owed and is NOT claimed here. */
    fun brightNovel(id: String, name: String): DeclarativeConfig =
        DeclarativeConfig(
            id = id,
            name = name,
            baseUrl = "https://brightnovels.com",
            needsRendering = true,
            renderWaitSelector = "div#app[data-page]",
            renderBudgetMs = 60_000,
            search = ListSpec(
                url = "https://brightnovels.com/api/search?query={query}",
                results = "",
                title = "title",
                link = "slug",
                cover = "cover.url",
                jsonPath = "data.series",
                jsonLinkTemplate = "/series/{value}"
            ),
            popular = ListSpec(
                url = "https://brightnovels.com/series",
                results = "",
                title = "title",
                link = "slug",
                cover = "cover.url",
                jsonPath = "props.seriesList.data",
                jsonLinkTemplate = "/series/{value}"
            ),
            details = DetailsSpec(
                chapters = ChapterListSpec(
                    list = "a",
                    name = "@text",
                    link = "@href",
                    reverse = true,
                    // v1.5.12 (Front 1, S3): the suspicious-empty
                    // FLOOR. This config previously declared NO
                    // chapter-count hint of any kind (no
                    // jsonTotalPath, no totalCount, no
                    // totalCountPattern), so the strict road's
                    // suspicious-empty tier could NEVER fire and
                    // a literal {"chapters":[]} from the endpoint
                    // was stamped proven-complete with 0 chapters
                    // (the v1.5.11 proof conviction). Neither
                    // wire hint is real for this site: the working
                    // provider's endpoint model carries ONLY the
                    // chapters array (no total field for
                    // jsonTotalPath to name), and the book page
                    // states no total in its visible text (its
                    // payload rides a data attribute, not body
                    // text, so totalCountPattern has nothing true
                    // to match). What IS true of this endpoint's
                    // shape: it is the chapter list of a published
                    // series that the book page itself resolved —
                    // a real book here always has at least one
                    // chapter (the endpoint's own records carry
                    // premium flags, so even a fully-premium book
                    // lists rows). totalCount = 1 is therefore
                    // declared as the floor: its ONLY live effect
                    // for this config (no urlPattern is declared,
                    // so pattern completion never reads it) is
                    // the chapterCountHint it feeds — an endpoint
                    // [] now fires suspicious-empty and the refresh
                    // surfaces the honest unproven state instead
                    // of stamping 0/complete, while any non-empty
                    // answer parses exactly as before.
                    totalCount = 1,
                    pagination = ChapterListPagination(
                        urlTemplate = "https://brightnovels.com/series/{bookSlug}/chapters/free?loaded=0&sort_order=desc",
                        bookIdPattern = "",
                        jsonArrayPath = "chapters",
                        pageSize = 1000
                    ),
                    jsonNameField = "name",
                    jsonLinkField = "slug",
                    jsonLinkTemplate =
                        "https://brightnovels.com/series/{bookSlug}/{value}",
                    jsonDateField = "updated_at"
                )
            ),
            chapter = ChapterSpec(
                content = "props.chapter.content"
            )
        )

    /** NovelArchive (novelarchive.cc) — user-added candidate,
     * verified live to the full bar (drive book Lord of the
     * Mysteries: 1,438/1,438 chapters, prose at both ends).
     * Everything real is under /api/: the catalogue pages a
     * flat `novels` array keyed by 24-hex ids; the book record
     * carries total_chapters AND the full chapter_names list
     * (bare strings — the engine's name-list branch builds
     * each chapter's URL from its 1-based position, which is
     * the site's own addressing: /chapters/<n>); chapter text
     * is plain prose at chapter.content. Details ride the
     * catalogue row (the book "page" is the API record). */
    fun novelArchive(id: String, name: String): DeclarativeConfig {
        val listing = ListSpec(
            url = "https://novelarchive.cc/api/novels?page={page}",
            results = "",
            title = "title",
            link = "id",
            cover = "cover_url",
            author = "author",
            jsonPath = "novels",
            jsonLinkTemplate = "https://novelarchive.cc/api/novels/{value}"
        )
        return DeclarativeConfig(
            id = id,
            name = name,
            baseUrl = "https://novelarchive.cc",
            search = listing.copy(
                url = "https://novelarchive.cc/api/novels?search={query}&page={page}"
            ),
            popular = listing,
            latest = listing,
            details = DetailsSpec(
                // v1.5.3 (finding #3): the payload's own cover
                // field heals stored coverless books (class C).
                // The catalogue/chapter recipe is unchanged.
                jsonCover = "cover_url",
                chapters = ChapterListSpec(
                    list = "a",
                    name = "@text",
                    link = "@href",
                    reverse = false,
                    jsonEndpointPath = "novel.chapter_names",
                    jsonLinkTemplate =
                        "https://novelarchive.cc/api/novels/{bookSlug}/chapters/{index}"
                )
            ),
            chapter = ChapterSpec(
                content = "body",
                ajax = ChapterTextAjax(
                    urlTemplate =
                        "https://novelarchive.cc/api/novels/{seg-3}/chapters/{seg-1}",
                    jsonContentPath = "chapter.content",
                    plainTextField = true
                )
            )
        )
    }

    /** Lnori (lnori.com) — v1.5.2 (FIX-14): English editions
     * of Japanese light novels, modelled as the site actually
     * is — SERIES → VOLUMES (the learner's chapter-link model
     * never fit it; see build-logs/V152-LNORI-PROBE.md).
     * Catalogue = the single /library page (884 article.card
     * rows carrying data-id/data-t/data-a/data-v), book page =
     * /series/{id}/{slug}, the chapter list = the series
     * page's volume cards in reading order.
     *
     * SHAPE — QUICKNOVEL'S, BY THE OWNER'S CALL (2026-11-03,
     * "Let's just use quicknovel"; QUICKNOVEL-RESEARCH.md §5
     * is the facts record): the volume page's main article
     * HTML is served WHOLE as one chapter, with <picture>
     * elements unwrapped to plain <img> by the engine.
     * QuickNovel's own selector names the article's sections
     * (main > article > section, concatenated by its
     * loader); this engine serves the content selector's
     * FIRST match, so the same served body is declared one
     * level up — main > article, the single element whose
     * content is exactly those sections (fixture-verified:
     * the article's children are the volume's sections and
     * nothing else). An earlier design split volumes at
     * their EPUB heading wrappers into reader chapters; the
     * owner cut it — do not revive it without his word.
     * Search has no
     * server side: the site's own search is a client-side
     * filter over the catalogue, declared here as
     * [ListSpec.localFilter] (title OR author substring —
     * the probe's replicated semantics, a superset of
     * QuickNovel's title-only filter). Covers ride
     * cdn.lnori.com; volume illustrations ride img.lnori.com
     * (observed 530/1016 at probe time — prose unaffected).
     * Rating MIXED: a general licensed-LN list with a small
     * mature tail (probe §4). */
    /** Light Novel Translations (lightnovelstranslations.com) —
     * v1.5.2 (FIX-17), owner-ruled MUST-WORK. Five legs proven in
     * the conversion probe (§5a) on unlocked/completed books:
     * catalogue/search on /browse/ (the site's real search field
     * is `field-search`; the generic /?s= shape returns zero),
     * book content proven (the page h1 is site chrome, so the
     * details title stays null and the listing title stands —
     * Fenrir precedent), text in div.text_story. Chapters ride
     * the FIX-17 PANELLED mode: the book page links each volume
     * panel (?tab=table_contents&lnt_volume=<id>) and the list
     * is every panel's rows concatenated in panel order.
     * Unlock-economy books mark their rows with the `lock`
     * class; those chapters surface through the engine's locked
     * detection (ChapterLocks / lockedChapterIds) and the
     * reader's honest LOCKED state — never as preview text
     * served as a chapter (the §14.5 stub refusal). */
    fun lightNovelTranslations(id: String, name: String): DeclarativeConfig {
        val cards = ListSpec(
            url = "https://lightnovelstranslations.com/browse/?paged={page}",
            results = "article.read_list-story-item",
            title = "h3 a",
            link = "h3 a@href",
            cover = "div.item_thumb img@src"
        )
        return DeclarativeConfig(
            id = id, name = name,
            baseUrl = "https://lightnovelstranslations.com",
            // v1.5.3 (finding #4, item 17): series with no
            // cover art get the site's membership-plugin
            // banner in the cover slot — a resolved cover URL
            // carrying this path is site chrome, not a cover,
            // and is treated as no cover at ingestion.
            coverDenyPatterns = listOf("/plugins/lnt-membership/"),
            search = cards.copy(
                url = "https://lightnovelstranslations.com/browse/?field-search={query}"
            ),
            popular = cards,
            details = DetailsSpec(
                description = "meta[name=description]@content",
                // v1.5.3 (finding #4): the book page's own
                // cover slot (finding #3's sweep candidate),
                // so stored books heal from their own page.
                cover = "div.novel-image img@src",
                chapters = ChapterListSpec(
                    // The ROW is the <li> (it carries the lock
                    // class the engine's lock detection reads);
                    // name/link resolve on its anchor.
                    list = "ul.lnt-chapter-rows li.chapter-item",
                    name = "a@text",
                    link = "a@href",
                    reverse = false,
                    panelSelector =
                        "a[href*='tab=table_contents'][href*='lnt_volume=']"
                )
            ),
            chapter = ChapterSpec(content = "div.text_story")
        )
    }

    // NovLove (novelnice.com) config REMOVED from v1.5.2 by
    // owner ruling 2026-11-03 (~17:58) — banked for the next
    // update; the exact recipe is saved in
    // build-logs/V152-NOVLOVE-BANKED.md §3.

    fun lnori(id: String, name: String): DeclarativeConfig {
        val listing = ListSpec(
            url = "https://lnori.com/library",
            results = "article.card",
            title = "h2.card-title span",
            link = "figure.card-cover a@href",
            cover = "figure.card-cover img@src",
            author = "@data-a",
            linkPattern = "^/series/\\d+/[^/]+$"
        )
        return DeclarativeConfig(
            id = id,
            name = name,
            baseUrl = "https://lnori.com",
            extraDomains = listOf("cdn.lnori.com", "img.lnori.com"),
            contentRating = ContentRating.MIXED,
            search = listing.copy(localFilter = true),
            popular = listing,
            latest = listing,
            details = DetailsSpec(
                title = "h1.s-title",
                author = ".hero-section p.author",
                description = "section.desc-box p.description",
                cover = ".cover-wrap img@src",
                chapters = ChapterListSpec(
                    list = "section.vol-grid > article.card",
                    name = "h3.card-title",
                    link = "a.stretched-link@href",
                    reverse = false
                )
            ),
            chapter = ChapterSpec(
                content = "main > article",
                title = "h1#book-title"
            )
        )
    }
}
