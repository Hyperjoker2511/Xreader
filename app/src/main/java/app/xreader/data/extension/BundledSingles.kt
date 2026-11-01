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
     * this machine: 3,231/3,231 chapters, two chapters of prose. */
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
                        pageSize = 500
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
            jsonCoverTemplate = "https://fenrirealm.com/{value}"
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
                        "https://fenrirealm.com/series/{seg-3}/{seg-1}"
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
            extraDomains = listOf("api.royalroad.com"),
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
     * live site; the capture corrected them). */
    fun noBadNovel(id: String, name: String): DeclarativeConfig {
        val cards = ListSpec(
            url = "https://www.nobadnovel.com/series?sort=popularity",
            results = "h4 a[href^='https://www.nobadnovel.com/series/']",
            title = "@text",
            link = "@href"
        )
        return DeclarativeConfig(
            id = id,
            name = name,
            baseUrl = "https://www.nobadnovel.com",
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

    /** FaqWiki (faqwiki.us) — the novels live under /novel/ (the
     * bare domain is a games portal now, so the base carries the
     * path). The chapter list is a category listing whose rows
     * are "<Book> Chapter N" (635/635 = the stated RAW total). */
    fun faqWiki(id: String, name: String): DeclarativeConfig = DeclarativeConfig(
        id = id,
        name = name,
        baseUrl = "https://faqwiki.us/novel",
        search = ListSpec(
            url = "https://faqwiki.us/novel/?s={query}",
            results = "article.hitmag-post",
            title = "h3.entry-title a",
            link = "h3.entry-title a@href",
            cover = "img@src"
        ),
        popular = ListSpec(
            url = "https://faqwiki.us/novel/",
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
            cover = "img@src"
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
                cover = "img@src"
            ),
            popular = cards,
            latest = cards.copy(
                url = "$base/latest-release-novels?page={page}"
            ),
            details = DetailsSpec(
                title = "div.main-head h1.novel-title",
                author = "span[itemprop='author']",
                description = "meta[itemprop='description']@content",
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
     * singular is a parked domain) — RENDER-BACKED bundled
     * default under the same Cloudflare decision as the
     * NovelFire family: every route answers this egress with
     * Cloudflare's "Attention Required!" wall, so the config
     * carries QuickNovel's provider claims (catalogue /series
     * anchors, JSON search at /api/search?query=, JSON chapter
     * list at /series/{slug}/chapters/free, text in article /
     * div.chapter-content) implemented natively and flagged
     * needsRendering + maintainerVerified. DEVICE-PROOF-
     * PENDING — not a live-verified pass. */
    fun brightNovel(id: String, name: String): DeclarativeConfig =
        DeclarativeConfig(
            id = id,
            name = name,
            baseUrl = "https://brightnovels.com",
            needsRendering = true,
            search = ListSpec(
                url = "https://brightnovels.com/api/search?query={query}",
                results = "",
                title = "title",
                link = "url",
                jsonPath = "data"
            ),
            popular = ListSpec(
                url = "https://brightnovels.com/series",
                results = "a[href^='/series/']",
                title = "@text",
                link = "@href"
            ),
            details = DetailsSpec(
                title = "h1",
                description = "meta[name=description]@content",
                chapters = ChapterListSpec(
                    list = "a",
                    name = "@text",
                    link = "@href",
                    reverse = true,
                    pagination = ChapterListPagination(
                        urlTemplate = "https://brightnovels.com/series/{bookSlug}/chapters/free?loaded=0&sort_order=desc",
                        bookIdPattern = "",
                        jsonArrayPath = "data",
                        pageSize = 1000
                    ),
                    jsonNameField = "title",
                    jsonLinkField = "url"
                )
            ),
            chapter = ChapterSpec(
                content = "article || div.chapter-content || div.prose || main"
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
}
