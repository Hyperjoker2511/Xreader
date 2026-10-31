package app.xreader.data.extension

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/**
 * The heuristic proposer (v1.0.4, D15): a second, fully
 * deterministic reading of a book page for "Add source from link" —
 * no AI, no key, no extra fetching. It exists for pages
 * [LinkInference] can't read: where chapter links carry no
 * chapter-shaped text or URL for its cluster rules, and synopses
 * live in unnamed plain blocks its class-name search walks past.
 *
 * The scoring is the user's pasted design, ported to this app's
 * contract shape:
 * - Title: first non-empty <h1> → og:title → an element classed
 *   title / book-name / novel-title.
 * - Synopsis: div/p/span elements scored by OWN-text length
 *   (≥ [MIN_SYNOPSIS_CHARS]) with a +[NAME_BONUS] bonus when the
 *   class or id mentions synopsis / summary / desc.
 * - Chapter list: links whose text mentions a chapter or carries
 *   any digit, same-host only, credit their ancestors up to
 *   [MAX_ANCESTOR_DEPTH] levels; the most-credited container holds
 *   the list, and its children on the links' paths are the rows.
 * - Safe selector (single-target specs): #id → first stable class
 *   → jsoup's structural cssSelector.
 *
 * Two adaptations to this app's engine, both deliberate: the
 * chapter LIST selector must match EVERY row, so it comes from
 * [SelectorSynthesizer] (or the row's class form) rather than the
 * single-target safe selector; and every candidate this proposer
 * produces still faces the same mandatory test-drive in
 * [LinkProber] before anything is saved — the digit heuristic is
 * noisy by design and the test-drive is its judge.
 *
 * Pure document analysis, like [LinkInference]: fetching and the
 * test-drive live in [LinkProber], so fixtures pin the behaviour.
 */
object HeuristicProposer {

    /**
     * A full book-page analysis in the same shape [LinkProber]
     * consumes from [LinkInference.analyseBookPage] — the probe's
     * downstream (chapter fetch, catalogue pass, config build,
     * test-drive) is shared unchanged.
     */
    fun analyseBookPage(doc: Document, pageUrl: String): LinkInference.BookPageAnalysis? {
        val foundTitle = findTitle(doc)
        // v1.0.5 (A7): mirror the primary inference — date-only
        // candidates are rejected; the URL slug is the fallback.
        val title: Pair<String, String>
        val fromSlug: Boolean
        if (foundTitle != null) {
            title = foundTitle
            fromSlug = false
        } else {
            val slug = TitleCleaner.slugTitle(pageUrl) ?: return null
            title = slug to ""
            fromSlug = true
        }
        val cluster = findChapterCluster(doc, pageUrl) ?: return null
        val numbers = cluster.rows.mapNotNull { ChapterNumbers.parse(it.text()) }
        val newestFirst = numbers.size >= 2 && numbers.first() > numbers.last()
        val firstRow = if (newestFirst) cluster.rows.last() else cluster.rows.first()
        val firstAnchor = anchorOf(firstRow) ?: return null
        val firstUrl = firstAnchor.attr("abs:href").ifBlank { null }
            ?: absolute(firstAnchor.attr("href"), pageUrl).ifBlank { null }
            ?: return null
        val coverUrl = findCover(doc, pageUrl)
        return LinkInference.BookPageAnalysis(
            title = title.first,
            titleSpec = title.second,
            authorSpec = findAuthorSpec(doc),
            descriptionSpec = synopsisElement(doc)?.let { specFor(doc, it) },
            coverUrl = coverUrl,
            coverSpec = if (coverUrl != null) {
                "meta[property=og:image]@content"
            } else {
                null
            },
            titleFromSlug = fromSlug,
            chapterListSelector = listSelectorFor(cluster.rows.first()),
            chapterNameSpec = if (cluster.itemsAreAnchors) "@text" else "a@text",
            chapterLinkSpec = if (cluster.itemsAreAnchors) "@href" else "a@href",
            chapterCount = cluster.rows.size,
            firstChapterUrl = firstUrl,
            newestFirst = newestFirst
        )
    }

    /**
     * The density-scored synopsis as a selector spec, or null when
     * no block qualifies. Also used by [LinkProber] to fill the
     * description when the primary inference found none.
     */
    fun synopsisSpec(doc: Document): String? =
        synopsisElement(doc)?.let { specFor(doc, it) }

    // ── title ───────────────────────────────────────────────────────

    private fun findTitle(doc: Document): Pair<String, String>? {
        // v1.0.5 (A7): shared title hygiene — a candidate that is
        // only a date stamp is rejected, exactly as in the primary
        // inference.
        fun usable(raw: String): String? {
            val cleaned = TitleCleaner.clean(raw)
            return cleaned.takeIf {
                it.isNotBlank() && !TitleCleaner.isDateOnly(it)
            }
        }
        val h1 = doc.select("h1").firstOrNull { it.text().isNotBlank() }
        if (h1 != null) usable(h1.text())?.let { return it to specFor(doc, h1) }
        doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.trim()?.ifBlank { null }
            ?.let { raw ->
                usable(raw)?.let {
                    return it to "meta[property=og:title]@content"
                }
            }
        val named = doc.select(".title, .book-name, .novel-title")
            .firstOrNull { it.text().isNotBlank() }
        if (named != null) {
            usable(named.text())?.let { return it to specFor(doc, named) }
        }
        return null
    }

    // ── synopsis ────────────────────────────────────────────────────

    private fun synopsisElement(doc: Document): Element? {
        var best: Element? = null
        var bestScore = 0
        for (element in doc.select("div, p, span")) {
            // v1.0.5 (A8): a blurb inside carousel chrome is
            // another book's synopsis, never this one's.
            if (isInChromeContainer(element)) continue
            val text = element.ownText()
            if (text.length < MIN_SYNOPSIS_CHARS) continue
            var score = text.length
            val className = element.className().lowercase()
            val id = element.id().lowercase()
            if (SYNOPSIS_HINTS.any { className.contains(it) }) score += NAME_BONUS
            if (SYNOPSIS_HINTS.any { id.contains(it) }) score += NAME_BONUS
            if (score > bestScore) {
                bestScore = score
                best = element
            }
        }
        return best
    }

    // ── chapter list ────────────────────────────────────────────────

    private data class ChapterCluster(
        val rows: List<Element>,
        val itemsAreAnchors: Boolean
    )

    private fun findChapterCluster(doc: Document, pageUrl: String): ChapterCluster? {
        val pageHost = runCatching { URI(pageUrl).host }.getOrNull()
            ?: return null
        val links = doc.select("a[href]").filter { anchor ->
            val href = anchor.attr("abs:href")
                .ifBlank { absolute(anchor.attr("href"), pageUrl) }
            if (href.isBlank()) return@filter false
            val linkHost = runCatching { URI(href).host }.getOrNull()
                ?: return@filter false
            // v1.2.1 (Defect 2): the shared sibling-book rule —
            // a link to another book under the same collection
            // is never this book's chapter, on this path either.
            if (LinkInference.isSiblingBookLink(anchor, pageUrl)) {
                return@filter false
            }
            linkHost == pageHost && isChapterLike(anchor.text())
        }
        if (links.size < MIN_CLUSTER) return null

        // Credit the ancestors: the container shared by the most
        // chapter-like links is the chapter list. Closer ancestors
        // are credited first, so a tie resolves toward the most
        // specific container.
        val scores = LinkedHashMap<Element, Int>()
        for (link in links) {
            var parent = link.parent()
            var depth = 0
            while (parent != null && depth < MAX_ANCESTOR_DEPTH) {
                scores[parent] = (scores[parent] ?: 0) + 1
                parent = parent.parent()
                depth++
            }
        }
        var container = scores.maxByOrNull { it.value }?.key ?: return null
        if ((scores[container] ?: 0) < MIN_CLUSTER) return null

        // Rows are the container's children on each link's path. If
        // that leaves ONE wrapper row holding the whole list (the
        // credit landed a level too high), step into it.
        var rows = rowsUnder(container, links)
        var guard = 0
        while (rows.size == 1 && guard < MAX_ANCESTOR_DEPTH) {
            val only = rows.single()
            if (links.count { isAncestor(only, it) } < MIN_CLUSTER) break
            container = only
            rows = rowsUnder(container, links)
            guard++
        }
        val distinct = rows.distinct()
        if (distinct.size < MIN_CLUSTER) return null
        return ChapterCluster(
            rows = distinct,
            itemsAreAnchors = distinct.all { it.tagName() == "a" }
        )
    }

    /** The pasted heuristic, kept deliberately loose: chapter-ness
     * is judged by the clustering and the test-drive, not here. */
    private fun isChapterLike(rawText: String): Boolean {
        val text = rawText.trim().lowercase()
        if (text.isEmpty()) return false
        return text.contains("chapter") || text.contains("ch.") ||
            DIGIT.containsMatchIn(text)
    }

    /** For each link, the child of [container] on the link's own
     * ancestor path (the link itself when it is a direct child). */
    private fun rowsUnder(container: Element, links: List<Element>): List<Element> {
        val rows = mutableListOf<Element>()
        for (link in links) {
            var el: Element = link
            while (true) {
                val parent = el.parent() ?: break
                if (parent == container) {
                    rows += el
                    break
                }
                el = parent
                if (el == container) break
            }
        }
        return rows
    }

    private fun isAncestor(ancestor: Element, element: Element): Boolean {
        var current = element.parent()
        while (current != null) {
            if (current == ancestor) return true
            current = current.parent()
        }
        return false
    }

    // ── selectors ───────────────────────────────────────────────────

    /**
     * The pasted safe selector for a single-target element: a
     * stable #id wins, then the first usable class, then jsoup's
     * structural path.
     */
    fun safeSelector(element: Element): String {
        if (element.id().isNotEmpty()) return "#${element.id()}"
        val primaryClass = element.classNames()
            .firstOrNull { it.length > 2 && !it.contains("active") }
        if (primaryClass != null) return ".$primaryClass"
        return element.cssSelector()
    }

    /**
     * A single-target spec for [element]: the safe selector, unless
     * it matches several elements and the scored one isn't first
     * (a class shared with page chrome) — then the app's stable
     * path selector, which names this element exactly.
     */
    private fun specFor(doc: Document, element: Element): String {
        val safe = safeSelector(element)
        val css = SelectorSpec.parse(safe).css
        if (css.isBlank()) return safe
        val matches = runCatching { doc.select(css) }.getOrDefault(emptyList())
        return if (matches.size <= 1 || matches.first() == element) {
            safe
        } else {
            SelectorSynthesizer.forElement(element)
        }
    }

    /**
     * The chapter LIST selector must match every row: the row's
     * class form when [safeSelector] offers one, otherwise the
     * synthesiser's list mode (v1.0.5, A2 — no per-row position
     * anywhere in the selector).
     */
    private fun listSelectorFor(row: Element): String {
        val safe = safeSelector(row)
        if (safe.startsWith(".")) return safe
        return SelectorSynthesizer.forList(row)
    }

    /** Carousel/slider chrome check (A8), mirroring
     * [LinkInference]'s field sanity. */
    private fun isInChromeContainer(element: Element): Boolean {
        var current: Element? = element
        while (current != null) {
            val marker = (current.className() + " " + current.id()).lowercase()
            if (CHROME_CONTAINERS.any { marker.contains(it) }) return true
            current = current.parent()
        }
        return false
    }

    private val CHROME_CONTAINERS = listOf(
        "swiper", "slider", "carousel", "owl-carousel", "slick-"
    )

    // ── small pieces ────────────────────────────────────────────────

    private fun findAuthorSpec(doc: Document): String? =
        if (doc.selectFirst("meta[name=author]")?.attr("content")
                ?.isNotBlank() == true
        ) {
            "meta[name=author]@content"
        } else {
            null
        }

    private fun findCover(doc: Document, pageUrl: String): String? =
        doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.ifBlank { null }?.let { absolute(it, pageUrl) }

    private fun anchorOf(row: Element): Element? =
        if (row.tagName() == "a") row else row.selectFirst("a[href]")

    private fun absolute(raw: String, base: String): String =
        runCatching { URI(base).resolve(raw).toString() }.getOrDefault(raw)

    const val MIN_CLUSTER = 3
    const val MAX_ANCESTOR_DEPTH = 3
    const val MIN_SYNOPSIS_CHARS = 100
    const val NAME_BONUS = 500

    private val DIGIT = Regex("\\d")
    private val SYNOPSIS_HINTS = listOf("synopsis", "summary", "desc")
}
