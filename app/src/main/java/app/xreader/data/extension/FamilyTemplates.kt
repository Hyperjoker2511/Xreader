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

    // ── LightNovelWP (WordPress) ────────────────────────────
    //
    // Port of LightNovelWPPlugin: listings live under the
    // series archive slug (m_orderby pages), details from the
    // summary block / info table, chapters from the book
    // page's rendered list-chapter rows, reading text from
    // .chapter-content .content-inner with the theme fallbacks.

    private fun lightNovelWp(
        base: String,
        pluginId: String,
        name: String,
        lang: String,
        options: Map<String, String>
    ): DeclarativeConfig {
        val slug = options["novelSlug"] ?: run {
            val path = base.substringAfter("://", "").substringAfter("/", "")
                .trim('/')
            if (path.isNotEmpty()) path.substringAfterLast("/")
            else base.substringAfter("://", "").substringBefore(".")
        }
        val archive = if (slug.isBlank()) base else "$base/$slug"
        val listing = ListSpec(
            url = "$archive/page/{page}/?m_orderby=views",
            results = ".page-item-detail, .manga-card, article, .post",
            title = ".post-title a@text",
            link = "a@href",
            cover = "img@data-src || img@src"
        )
        return DeclarativeConfig(
            id = "lnreader.$pluginId",
            name = name,
            lang = lang.ifBlank { "en" },
            baseUrl = base,
            // The generator's search URL wasn't visible in its
            // source; this is the Madara-family WordPress form
            // these themes ship with.
            search = ListSpec(
                url = "$base/?s={query}&post_type=wp-manga",
                results = listing.results,
                title = listing.title,
                link = listing.link,
                cover = listing.cover
            ),
            popular = listing,
            latest = ListSpec(
                url = "$archive/page/{page}/?m_orderby=latest",
                results = listing.results,
                title = listing.title,
                link = listing.link,
                cover = listing.cover
            ),
            details = DetailsSpec(
                title = "h1",
                author = ".author-content a@text",
                description = ".summary__content@text || .summary-text@text",
                cover = ".summary_image img@data-src || .summary_image img@src",
                chapters = ChapterListSpec(
                    list = "ul.list-chapter li, .chapter-list li, " +
                        ".listing-chapters_wrap li",
                    name = "a@text",
                    link = "a@href",
                    reverse = true
                )
            ),
            chapter = ChapterSpec(
                content = ".chapter-content .content-inner || " +
                    ".reading-content-inner || .entry-content || " +
                    ".reading-content .text-left",
                remove = listOf(
                    ".adsbygoogle", ".sharedaddy", ".jp-relatedposts",
                    ".post-navigation", ".prev-next", ".comments-area",
                    ".chapter-nav", ".breadcrumbs", ".alert", ".notice",
                    ".donate-box"
                )
            )
        )
    }

    // ── ReadNovelFull (NovelFull family) ────────────────────
    //
    // Port of ReadNovelFullPlugin: the site root is the
    // latest-release listing (?page= paging), search is
    // {searchPage}?{searchKey}=, details from the book info
    // block (.desc-text summary, /author/ links), chapters
    // from the book page's ul.list-chapter rows, reading text
    // from #chapter-content with the family's ad stripping.

    private fun readNovelFull(
        base: String,
        pluginId: String,
        name: String,
        lang: String,
        options: Map<String, String>
    ): DeclarativeConfig {
        val latestPage = options["latestPage"]?.trim('/') ?: ""
        val pageParam = options["pageParam"] ?: "page"
        val pageAsPath = options["pageAsPath"] == "true"
        fun listingUrl(root: String): String = when {
            pageAsPath -> "$root/{page}"
            root == base -> "$base/?$pageParam={page}"
            else -> "$root?$pageParam={page}"
        }
        val latestRoot = if (latestPage.isBlank()) base else "$base/$latestPage"
        val listing = ListSpec(
            url = listingUrl(latestRoot),
            results = ".list-novel .row, .archive .row, .col-content .row",
            title = "h3.title a@text || a@text",
            link = "a@href",
            cover = ".cover img@src || img@src"
        )
        val searchPage = options["searchPage"]?.trim('/') ?: "search"
        val searchKey = options["searchKey"] ?: "keyword"
        return DeclarativeConfig(
            id = "lnreader.$pluginId",
            name = name,
            lang = lang.ifBlank { "en" },
            baseUrl = base,
            search = ListSpec(
                url = "$base/$searchPage?$searchKey={query}",
                results = listing.results,
                title = listing.title,
                link = listing.link,
                cover = listing.cover
            ),
            popular = listing,
            latest = listing,
            details = DetailsSpec(
                title = "h1 || .title",
                author = "a[href*='/author/']@text",
                description = ".desc-text@text",
                cover = ".book img@src || .info img@src",
                chapters = ChapterListSpec(
                    list = "ul.list-chapter li",
                    name = "a@text",
                    link = "a@href",
                    date = ".chapter-time@text",
                    reverse = true
                )
            ),
            chapter = ChapterSpec(
                content = "#chapter-content || .chapter-content || .content",
                remove = listOf(
                    "script", "style", ".ads", ".ad",
                    "[class*='advert']", "[class*='chapter-nav']",
                    "[class*='topnav']", "[class*='botnav']"
                )
            )
        )
    }
}
