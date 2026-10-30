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

    enum class Family { MADARA, NOVELFULL, NOVELIGHT }

    data class Entry(
        val domains: List<String>,
        val family: Family,
        /** Hint only: the family usually supports in-source
         * search. The test-drive still has to prove it. */
        val canSearch: Boolean = true,
        /** Hint only: the family usually offers account login. */
        val canLogin: Boolean = false
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
    ): DeclarativeConfig? = when (entry.family) {
        Family.NOVELIGHT -> novelightConfig(id, name, baseUrl)
        Family.NOVELFULL, Family.MADARA -> null
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
}
