package app.xreader.data.extension

/**
 * LNReader multisrc family templates (v1.0.9) — the three big
 * generator families of the LNReader plugin catalogue
 * (madara, lightnovelwp, readnovelfull), re-implemented ONCE
 * each as native Xreader declarative configurations.
 *
 * LNReader and its plugins are MIT-licensed (Copyright (c)
 * 2021 Rajarshee Chatterjee); these templates are a port of
 * the generators' site-reading logic (URLs, selectors,
 * endpoints) into Xreader's own config language, credited in
 * the app's licences screen. A family site then installs as an
 * ordinary declarative source — no per-site learning, no
 * probe: the per-site part is the small options bag the
 * plugin's constructor carries ([JsConstructorOptions]).
 *
 * Known v1 limits (honest): chapter lists read the book
 * page's own rendered rows — the families' POST chapter
 * endpoints (Madara's ajax/chapters, LNWP's chapter-archive,
 * RNF's chapter-option) don't fit the declarative pagination
 * machinery yet, so a book whose site renders NO chapter rows
 * on its page reads as an empty list here (the probe / Live
 * Source paths remain the fallback for such a book). Search
 * filters (the plugins' filter tables) are not surfaced yet;
 * plain search works.
 */
object FamilyTemplates {

    /** Families with a native template in this build. */
    val FAMILIES = setOf("madara", "lightnovelwp", "readnovelfull")

    /** The generator family a compiled plugin URL belongs to,
     * from its `Name[family].js` filename (any family, templated
     * or not — [hasTemplate] says which); null for hand-written
     * plugins, which carry no bracket tag. */
    fun familyOf(pluginUrl: String): String? {
        val fileName = pluginUrl.substringAfterLast("/")
            .substringBefore("?")
        val match = Regex("""\[([a-z0-9_-]+)\]\.js$""", RegexOption.IGNORE_CASE)
            .find(fileName) ?: return null
        val family = match.groupValues[1].lowercase()
        return if (family in FAMILIES) family else family
    }

    /** Whether [family] has a native template here. */
    fun hasTemplate(family: String?): Boolean = family in FAMILIES

    /**
     * The declarative config for one family site, or null when
     * the family has no template. [options] is the plugin
     * constructor's options bag ([JsConstructorOptions] output;
     * empty when the plugin file couldn't be read — the family
     * defaults then apply, which is what most sites use).
     */
    fun configFor(
        family: String,
        pluginId: String,
        site: String,
        name: String,
        lang: String,
        options: Map<String, String>
    ): DeclarativeConfig? {
        val base = site.trim().trimEnd('/')
        if (base.isEmpty()) return null
        return when (family) {
            "madara" -> madara(base, pluginId, name, lang, options)
            "lightnovelwp" -> lightNovelWp(base, pluginId, name, lang, options)
            "readnovelfull" -> readNovelFull(base, pluginId, name, lang, options)
            else -> null
        }
    }

    // ── Madara (WordPress) ──────────────────────────────────
    //
    // Port of MadaraPlugin, corrected against the generator's
    // compiled form (six sampled plugins, identical class):
    // listings are the WordPress search endpoint walked by
    // /page/{n}/ (an empty query lists the whole wp-manga
    // archive; latest appends m_orderby=latest), search is the
    // stock ?s= form Xreader's own Madara spec also uses,
    // items are .page-item-detail / .c-tabs-item__content with
    // the title link inside .post-title, covers prefer the
    // lazy-load attributes (data-lazy-src, then data-src),
    // details come from the summary block, chapters from the
    // book page's .wp-manga-chapter rows (newest first — the
    // generator reverses them, and numbers them by position),
    // and the reading text chain is .text-left → .text-right →
    // .entry-content. The generator's chapter list itself is a
    // POST endpoint (see the file header's known limit); the
    // options it reads (useNewChapterEndpoint, hasLocked,
    // browserHeaders, customJs, resolveCoverDataUri) have no
    // declarative expression and are ignored here.

    private fun madara(
        base: String,
        pluginId: String,
        name: String,
        lang: String,
        options: Map<String, String>
    ): DeclarativeConfig {
        val listing = ListSpec(
            url = "$base/page/{page}/?s=&post_type=wp-manga",
            results = ".page-item-detail, .c-tabs-item__content, " +
                ".manga-card, article, .post, li[data-post-id]",
            title = ".post-title a@text",
            link = ".post-title a@href || a@href",
            cover = "img@data-lazy-src || img@data-src || img@src",
            nextPage = ".wp-pagenavi a, .pagination a, .c-pagination a"
        )
        return DeclarativeConfig(
            id = "lnreader.$pluginId",
            name = name,
            lang = lang.ifBlank { "en" },
            baseUrl = base,
            search = ListSpec(
                url = "$base/page/{page}/?s={query}&post_type=wp-manga",
                results = listing.results,
                title = listing.title,
                link = listing.link,
                cover = listing.cover,
                nextPage = listing.nextPage
            ),
            popular = listing,
            latest = ListSpec(
                url = "$base/page/{page}/?s=&post_type=wp-manga&m_orderby=latest",
                results = listing.results,
                title = listing.title,
                link = listing.link,
                cover = listing.cover,
                nextPage = listing.nextPage
            ),
            details = DetailsSpec(
                title = ".post-title h1 || #manga-title h1 || h1",
                author = ".author-content a@text || " +
                    ".manga-authors a@text || .manga-author a@text",
                description = ".summary__content@text",
                cover = ".summary_image img@data-lazy-src || " +
                    ".summary_image img@data-src || .summary_image img@src",
                chapters = ChapterListSpec(
                    list = ".wp-manga-chapter",
                    name = "a@text",
                    link = "a@href",
                    date = "span.chapter-release-date@text",
                    reverse = true
                )
            ),
            chapter = ChapterSpec(
                content = ".text-left || .text-right || .entry-content || " +
                    ".reading-content",
                remove = listOf(
                    ".ad", ".ads", "div.code-block", ".sharedaddy",
                    ".jp-relatedposts", ".wp-manga-reader-pagination",
                    ".nav-links", ".breadcrumb"
                )
            )
        )
    }

    // ── LightNovelWP ────────────────────────────────────────
    //
    // Port of LightNovelWPPlugin (FULL generator extraction,
    // 2026-10-30 — this family is NOT a Madara theme; the
    // earlier Madara-shaped template was wrong): catalogue =
    // {site}{seriesPath || "/series/"}?page={n} (latest adds
    // &order=latest); items are <article> blocks whose main
    // anchor carries href + title ATTRIBUTE (the name is the
    // attribute, cover = img data-src || src); search =
    // {site}page/{n}/?s={term}; the book page carries the
    // title/cover on the .ts-post-image element (title attr),
    // the summary in div.entry-content / [itemprop=description]
    // and the chapters INSIDE the page's .eplister element
    // (li rows, .epl-title/.epl-date fields) — no endpoint,
    // document order unless the plugin sets reverseChapters;
    // chapter text = the first .epcontent element, p/img only.

    private fun lightNovelWp(
        base: String,
        pluginId: String,
        name: String,
        lang: String,
        options: Map<String, String>
    ): DeclarativeConfig {
        val seriesPath = options["seriesPath"]?.takeIf { it.isNotBlank() }
            ?: "/series/"
        val archive = base + "/" + seriesPath.trim('/')
        val listing = ListSpec(
            url = "$archive/?page={page}",
            results = "article",
            title = "a@title || a@text",
            link = "a@href",
            cover = "img@data-src || img@src"
        )
        return DeclarativeConfig(
            id = "lnreader.$pluginId",
            name = name,
            lang = lang.ifBlank { "en" },
            baseUrl = base,
            search = ListSpec(
                url = "$base/page/{page}/?s={query}",
                results = listing.results,
                title = listing.title,
                link = listing.link,
                cover = listing.cover
            ),
            popular = listing,
            latest = ListSpec(
                url = "$archive/?page={page}&order=latest",
                results = listing.results,
                title = listing.title,
                link = listing.link,
                cover = listing.cover
            ),
            details = DetailsSpec(
                // The label-matched info spans (.spe/.serl) the
                // generator reads can't be expressed in specs;
                // author stays to the listing/fallback chain.
                title = ".ts-post-image@title || h1",
                description = "div.entry-content@text || " +
                    "[itemprop=description]@text",
                cover = ".ts-post-image img@data-src || " +
                    ".ts-post-image img@src || " +
                    ".ts-post-image@data-src || .ts-post-image@src",
                chapters = ChapterListSpec(
                    list = ".eplister li",
                    name = ".epl-title@text || a@text",
                    link = "a@href",
                    date = ".epl-date@text",
                    reverse = options["reverseChapters"] == "true"
                )
            ),
            chapter = ChapterSpec(
                content = ".epcontent",
                remove = listOf(".code-block")
            )
        )
    }

    // ── ReadNovelFull (NovelFull family) ────────────────────
    //
    // Port of ReadNovelFullPlugin (FULL generator extraction,
    // 2026-10-30). Catalogue has two modes: when the plugin
    // sets options.novelListing, listings are that path with
    // ?{typeParam}={latest|type}&{pageParam}={n}; otherwise
    // the listing TYPE is a PATH segment — popular = the
    // plugin's filters.type.value (extracted as "filterType"),
    // latest = options.latestPage — paged /{n} when
    // pageAsPath (the default; page 1 then asks for /1, which
    // this family serves/redirects) else ?{pageParam}={n}.
    // Item names are the anchors' TITLE ATTRIBUTES; covers
    // prefer data-src, then data-cfsrc, data-original, src.
    // Search = {searchPage}?{searchKey}={q} (POST body when
    // postSearch). The generator's chapter lists come from
    // AJAX endpoints the declarative engine can't call (POST
    // chapter-archive, ?ajax=chapters paging) — the book
    // page's own rows are the v1 road: ul.list-chapter, plus
    // ul#idData for noAjax sites; document order is kept (the
    // generator never reverses). Chapter text starts at the
    // element whose class is exactly "txt", or #chr-content /
    // #chapter-content.

    private fun readNovelFull(
        base: String,
        pluginId: String,
        name: String,
        lang: String,
        options: Map<String, String>
    ): DeclarativeConfig {
        val latestPage = options["latestPage"]?.trim('/') ?: ""
        val pageParam = options["pageParam"] ?: "page"
        val pageAsPath = options["pageAsPath"] != "false"
        val typeParam = options["typeParam"] ?: "type"
        val listingPath = options["novelListing"]?.trim('/') ?: ""
        val typeValue = options["filterType"]?.trim('/') ?: ""
        fun pathListing(segment: String): String {
            val root = if (segment.isBlank()) base else "$base/$segment"
            return if (pageAsPath) "$root/{page}" else "$root?$pageParam={page}"
        }
        fun listingUrl(popular: Boolean): String = if (listingPath.isNotBlank()) {
            val type = if (popular) {
                typeValue.ifBlank { latestPage }
            } else {
                latestPage.ifBlank { typeValue }
            }
            "$base/$listingPath?$typeParam=$type&$pageParam={page}"
        } else {
            pathListing(if (popular) typeValue else latestPage.ifBlank { typeValue })
        }
        val resultsSpec = ".list-novel .row, .archive .row, .col-content .row"
        val titleSpec = "a@title || h3.title a@text || a@text"
        val coverSpec = "img@data-src || img@data-cfsrc || " +
            "img@data-original || img@src"
        val popularListing = ListSpec(
            url = listingUrl(popular = true),
            results = resultsSpec,
            title = titleSpec,
            link = "a@href",
            cover = coverSpec
        )
        val latestListing = ListSpec(
            url = listingUrl(popular = false),
            results = resultsSpec,
            title = titleSpec,
            link = "a@href",
            cover = coverSpec
        )
        val searchPage = options["searchPage"]?.trim('/') ?: "search"
        val searchKey = options["searchKey"] ?: "keyword"
        val postSearch = options["postSearch"] == "true"
        return DeclarativeConfig(
            id = "lnreader.$pluginId",
            name = name,
            lang = lang.ifBlank { "en" },
            baseUrl = base,
            search = ListSpec(
                url = "$base/$searchPage" +
                    if (postSearch) "" else "?$searchKey={query}",
                results = resultsSpec,
                title = titleSpec,
                link = "a@href",
                cover = coverSpec,
                method = if (postSearch) "POST" else "GET",
                body = if (postSearch) "$searchKey={query}" else null
            ),
            popular = popularListing,
            latest = latestListing,
            details = DetailsSpec(
                title = ".books h3@text || .m-imgtxt h3@text || " +
                    "h1 || .title",
                author = "a[href*='/author/']@text",
                description = ".desc-text@text || .inner@text",
                cover = ".books img@data-src || .books img@src || " +
                    ".book img@src || .info img@src",
                chapters = ChapterListSpec(
                    list = "ul.list-chapter li, ul#idData li",
                    name = "a@title || a@text",
                    link = "a@href",
                    date = ".chapter-time@text",
                    reverse = false
                )
            ),
            chapter = ChapterSpec(
                content = "#chapter-content || #chr-content || " +
                    ".txt || .chapter-content",
                remove = listOf(
                    "script", "style", ".ads", ".ad",
                    "[class*='advert']", "[class*='chapter-nav']",
                    "[class*='topnav']", "[class*='botnav']",
                    "[class*='unlock-buttons']"
                )
            )
        )
    }
}
