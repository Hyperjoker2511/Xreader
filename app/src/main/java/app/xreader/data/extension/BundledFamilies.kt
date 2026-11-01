package app.xreader.data.extension

/**
 * v1.3.0: the family builders behind the bundled defaults
 * ([BundledDefaults] / [BundledWave1]) — plan E2's family-template
 * support in the config layer. Each builder is ONE verified site
 * recipe, parameterised per site: the EmpireCMS pair share every
 * selector and differ only in host; the Madara sites share the
 * WordPress shape and differ in archive prefix, chapter mode and
 * card details; and so on. Every shape here was proven live
 * against the real site at bundle time (fixtures-capture/ carries
 * the evidence; build-logs/V130-BUILD-REPORT.md the verdicts).
 *
 * Endpoints and selectors are FACTS read off the live sites (and
 * cross-checked against research into other readers' provider
 * tables — facts only, no code or data copied; the GPL source
 * consulted was never reproduced). Where a recipe derives from
 * the Apache-2.0 Mangayomi source index, the site says so in its
 * builder comment and the credit lives in the About screen's
 * open-source licences list.
 */
internal object BundledFamilies {

    // ── EmpireCMS (wuxiabox.com, fanmtl.com) ────────────────────
    //
    // Verified live 2026-11-01: catalogue cards `li.novel-item`
    // (real covers ride `data-src`; `src` is a placeholder),
    // chapters from the fy.php paging endpoint (0-based pages —
    // the book page embeds page 0, so the walk's engine page 2
    // is the endpoint's page 1 via {page0}), the id is the book
    // slug in the page's own `wjm=` links, and search is the
    // two-step EmpireCMS form: POST /e/search/index.php answers
    // 302 to a results page; the app's client keeps the method
    // on a 302, so the POST listing lands on the results page
    // itself (verified end-to-end from this machine).
    fun empireCms(id: String, name: String, base: String): DeclarativeConfig {
        val cards = ListSpec(
            url = "$base/list/all/all-onclick-0.html",
            results = "li.novel-item",
            title = "a[title]@title",
            link = "a[title]@href",
            cover = "img@data-src"
        )
        return DeclarativeConfig(
            id = id,
            name = name,
            baseUrl = base,
            search = cards.copy(
                url = "$base/e/search/index.php",
                method = "POST",
                body = "show=title&tempid=1&tbname=news&keyboard={query}"
            ),
            popular = cards,
            details = DetailsSpec(
                title = "h1.novel-title",
                author = "div.author [itemprop=author]",
                description = "div.summary",
                chapters = ChapterListSpec(
                    list = "ul.chapter-list li",
                    name = "a",
                    link = "a@href",
                    reverse = false,
                    pagination = ChapterListPagination(
                        urlTemplate = "$base/e/extend/fy.php?page={page0}&wjm={bookId}",
                        bookIdPattern = "wjm=([a-zA-Z0-9-]+)"
                    )
                )
            ),
            chapter = ChapterSpec(content = "div.chapter-content")
        )
    }

    // ── AllNovel family, option-list chapters ───────────────────
    //
    // novelfull.com and novelfull.net (independent templates of
    // the same family): the book page carries `data-novel-id`
    // and ONE option-list endpoint call returns every chapter
    // as an <option> (verified live: 4,909 rows on novelfull,
    // ascending). The two sites differ in their listing cards
    // and book-page selectors, so those ride as parameters.
    fun allNovelOptions(
        id: String,
        name: String,
        base: String,
        listingResults: String,
        listingTitle: String,
        listingLink: String,
        listingCover: String?,
        detailsTitle: String?,
        detailsAuthor: String?,
        detailsDescription: String?
    ): DeclarativeConfig {
        val cards = ListSpec(
            url = "$base/hot-novel",
            results = listingResults,
            title = listingTitle,
            link = listingLink,
            cover = listingCover
        )
        return DeclarativeConfig(
            id = id,
            name = name,
            baseUrl = base,
            search = cards.copy(url = "$base/search?keyword={query}"),
            popular = cards,
            latest = cards.copy(url = "$base/latest-release-novel"),
            details = DetailsSpec(
                title = detailsTitle,
                author = detailsAuthor,
                description = detailsDescription,
                chapters = ChapterListSpec(
                    list = "option",
                    name = "@text",
                    link = "@value",
                    reverse = false,
                    pagination = ChapterListPagination(
                        urlTemplate = "$base/ajax-chapter-option?novelId={bookId}",
                        bookIdPattern = "data-novel-id=\"(\\d+)\""
                    )
                )
            ),
            chapter = ChapterSpec(content = "#chapter-content")
        )
    }

    // ── FreeWebNovel family, ajax chapter pages ─────────────────
    //
    // freewebnovel.com and libread.com run the same software:
    // the chapter list is a JSON-wrapped HTML fragment behind
    // `{book}?ajax=chapters&page=N&pageSize=40` (the old
    // chapterlist.php POST from the research is dead — verified
    // live), chapter text sits in `div.txt`, listing cards are
    // `div.li-row`. The sites differ in their search (GET
    // keyword vs POST searchkey) and catalogue paths.
    fun freeWebNovelFamily(
        id: String,
        name: String,
        base: String,
        popular: ListSpec,
        search: ListSpec,
        extraDomains: List<String> = emptyList()
    ): DeclarativeConfig = DeclarativeConfig(
        id = id,
        name = name,
        baseUrl = base,
        search = search,
        popular = popular,
        extraDomains = extraDomains,
        details = DetailsSpec(
            title = "h1.tit",
            author = "a[href^='/author/']",
            description = "div.inner",
            chapters = ChapterListSpec(
                list = "li:has(> span.glyphicon-book) > a.con",
                name = "@text",
                link = "@href",
                reverse = false,
                pagination = ChapterListPagination(
                    urlTemplate = "{bookUrl}?ajax=chapters&page={page}&pageSize=40",
                    bookIdPattern = "",
                    jsonHtmlField = "html"
                )
            )
        ),
        chapter = ChapterSpec(content = "div.txt")
    )

    /** The family's shared listing cards. */
    fun freeWebCards(
        url: String,
        method: String = "GET",
        body: String? = null
    ): ListSpec = ListSpec(
        url = url,
        results = "div.li-row",
        title = "h3.tit > a@title",
        link = "h3.tit > a@href",
        cover = "img@src",
        method = method,
        body = body
    )

    // ── Madara (WordPress wp-manga) ─────────────────────────────
    //
    // The family's shape, verified live per site 2026-11-01:
    // catalogue cards `div.page-item-detail`, search cards live
    // under the c-tabs containers (a DIFFERENT card shape from
    // the catalogue on every Madara site), chapter text in
    // `div.text-left`. Chapters are never in the static book
    // page: most themes serve them from a POST to
    // `{book}ajax/chapters/` (which needs a Referer header —
    // without one the firewall stalls ~45 s; with one it
    // answers in seconds); hiraethtranslation renders them
    // inline instead. Lists come newest-first (reverse = true).
    fun madara(
        id: String,
        name: String,
        base: String,
        popularUrl: String,
        coverSelector: String,
        searchResults: String,
        searchTitle: String,
        detailsTitle: String?,
        detailsDescription: String?,
        chaptersViaAjaxPost: Boolean
    ): DeclarativeConfig {
        val catalogue = ListSpec(
            url = popularUrl,
            results = "div.page-item-detail",
            title = "div.item-thumb a@title",
            link = "div.item-thumb a@href",
            cover = coverSelector
        )
        val chapters = ChapterListSpec(
            list = "li.wp-manga-chapter a",
            name = "@text",
            link = "@href",
            reverse = true,
            pagination = if (chaptersViaAjaxPost) {
                ChapterListPagination(
                    urlTemplate = "{bookUrl}ajax/chapters/",
                    bookIdPattern = "",
                    method = "POST",
                    body = "",
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to "$base/"
                    )
                )
            } else {
                null
            }
        )
        return DeclarativeConfig(
            id = id,
            name = name,
            baseUrl = base,
            search = ListSpec(
                url = "$base/?s={query}&post_type=wp-manga",
                results = searchResults,
                title = searchTitle,
                link = "$searchTitle@href",
                cover = coverSelector
            ),
            popular = catalogue,
            details = DetailsSpec(
                title = detailsTitle,
                author = ".author-content a",
                description = detailsDescription,
                chapters = chapters
            ),
            chapter = ChapterSpec(content = "div.text-left")
        )
    }
}
