package app.xreader.data.extension

import app.xreader.extensionapi.SourceChapter
import java.net.URLDecoder

/**
 * Canonical chapter identity + list merge (v1.3.2, V120-CHECK
 * items 64/78/79/80/82 — scope B, generalised per R3).
 *
 * A book's chapter list reaches the engine as FRAGMENTS: the
 * book page's DOM slice, embedded-JSON rows, pagination pages,
 * an API answer. v1.3.1 concatenated the fragments and deduped
 * by the RAW url string, so the same chapter in two url forms
 * (www vs bare host, trailing slash, an encoded slug) survived
 * twice — NovelBuddy's stored list ran +24 over its API's own
 * count — and a newest-first fragment stitched onto an
 * ascending one (WoopRead, LibRead) stayed stitched, with the
 * display and the reader keying on list position instead of
 * identity: tapping "Chapter 1" opened chapter 158.
 *
 * The rule now, applied at the one choke point every
 * declarative list passes ([DeclarativeSource]'s finishing):
 *
 *  1. MERGE by canonical identity (see [canonicalKey]):
 *     duplicates collapse, keeping the RICHEST record — a
 *     dated row beats a dateless one, a titled row beats a
 *     bare "Chapter n", a numbered row beats an unnumbered.
 *  2. ORDER by parsed chapter number when the list's numbers
 *     can carry the order (unique across the list, and nearly
 *     every row numbered): fragments can arrive in any order
 *     and still settle into reading order. Lists whose
 *     numbers repeat (per-volume restarts) or are mostly
 *     absent keep their site order untouched.
 *
 * Pure and JVM-tested.
 */
object ChapterIdentity {

    /**
     * The canonical identity of a chapter URL: scheme and host
     * case folded, a leading "www." dropped, default ports
     * dropped, percent-escapes decoded, duplicate slashes and
     * a trailing slash folded away. Two URL forms of the same
     * chapter share a key; genuinely different chapters (a
     * different slug, a different number) never do.
     */
    fun canonicalKey(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ""
        val noFragment = trimmed.substringBefore('#')
        val uri = runCatching { java.net.URI(noFragment) }.getOrNull()
            ?: return noFragment.lowercase()
        val scheme = (uri.scheme ?: "https").lowercase()
        var host = (uri.host ?: "").lowercase()
        if (host.startsWith("www.")) host = host.drop(4)
        // v1.3.4 (T6): a registered MIRROR host folds to its
        // primary — the same chapter served from a backup
        // address is the same chapter, for the Library,
        // progress, downloads and repairs alike.
        host = MirrorHosts.canonicalHost(host)
        val port = uri.port
        val portPart = when {
            port <= 0 -> ""
            scheme == "http" && port == 80 -> ""
            scheme == "https" && port == 443 -> ""
            else -> ":$port"
        }
        val rawPath = uri.rawPath ?: ""
        val decoded = runCatching {
            URLDecoder.decode(rawPath, "UTF-8")
        }.getOrDefault(rawPath)
        // Case folds over the WHOLE key, path included: the
        // reconciler's long-standing canonicalisation matched
        // "Site/Ch/1/" to "site/ch/1" (pinned by its test),
        // and chapter slugs on these sites are case-insensitive
        // in practice — two chapters differing only by path
        // case would be the same chapter served twice.
        val path = decoded.replace(Regex("/+"), "/")
            .trimEnd('/').ifEmpty { "/" }
            .lowercase()
        val query = uri.rawQuery
            ?.split('&')
            ?.filter { it.isNotBlank() }
            ?.sorted()
            ?.joinToString("&")
            ?.takeIf { it.isNotEmpty() }
            ?.let { "?$it" } ?: ""
        return "$scheme://$host$portPart$path$query"
    }

    /** How much a row carries, for the richest-wins collapse:
     * a date is worth most (only one fragment usually has it),
     * then a real title over a bare number label, then a
     * number itself, then a longer name. */
    private fun richness(row: SourceChapter): Int {
        var score = 0
        if (!row.dateText.isNullOrBlank()) score += 8
        val name = row.name.trim()
        if (name.isNotEmpty() &&
            !name.matches(Regex("(?i)chapter\\s+\\d+(?:\\.\\d+)?"))
        ) {
            score += 4
        }
        if (row.number != null) score += 2
        score += minOf(name.length, 20) / 10
        return score
    }

    /**
     * Merge fragments into one list: the first occurrence of
     * each canonical identity fixes its position; a later,
     * RICHER record for the same chapter replaces the stored
     * one (carrying the first record's id form — the URL the
     * list already knows — with the richer record's name,
     * number and date). Exact-string duplicates behave
     * exactly as the old distinctBy did.
     */
    fun merge(rows: List<SourceChapter>): List<SourceChapter> {
        if (rows.size < 2) return rows
        val byKey = LinkedHashMap<String, SourceChapter>()
        for (row in rows) {
            val key = canonicalKey(row.id)
            val existing = byKey[key]
            if (existing == null) {
                byKey[key] = row
            } else if (richness(row) > richness(existing)) {
                byKey[key] = SourceChapter(
                    id = existing.id,
                    name = row.name,
                    number = row.number ?: existing.number,
                    dateText = row.dateText ?: existing.dateText
                )
            } else if (existing.dateText.isNullOrBlank() &&
                !row.dateText.isNullOrBlank()
            ) {
                byKey[key] = existing.copy(dateText = row.dateText)
            }
        }
        return byKey.values.toList()
    }

    /**
     * Reading order by chapter number, or null when the
     * numbers cannot carry this list's order: fewer than 3
     * rows, under 3/4 of rows numbered, or any number repeated
     * (per-volume numbering restarts — sorting those by number
     * would scramble the volumes). Unnumbered rows in an
     * otherwise numbered list ride just after the numbered
     * row they followed (a prologue before chapter 1, an
     * epilogue after the last), keeping their relative order.
     */
    fun orderByNumber(rows: List<SourceChapter>): List<SourceChapter>? {
        if (rows.size < 3) return null
        val numbers = rows.map { row ->
            row.number ?: ChapterNumbers.parseForOrder(row.name, row.id)
        }
        val numbered = numbers.filterNotNull()
        if (numbered.size < (rows.size * 3 + 3) / 4) return null
        if (numbered.distinct().size != numbered.size) return null
        val keyed = ArrayList<Pair<Float, SourceChapter>>(rows.size)
        var last = 0f
        var unnumberedRun = 0
        rows.forEachIndexed { index, row ->
            val number = numbers[index]
            if (number != null) {
                last = number
                unnumberedRun = 0
                keyed += number to row
            } else {
                unnumberedRun++
                keyed += (last + 0.001f * unnumberedRun) to row
            }
        }
        return keyed.sortedBy { it.first }.map { it.second }
    }

    /** The fully settled list: merge, then order by number
     * when the numbers can carry it. [fallback] runs only
     * when they cannot (the caller's site-order finishing —
     * reverse flags and the descending flip). */
    fun settle(
        rows: List<SourceChapter>,
        fallback: (List<SourceChapter>) -> List<SourceChapter>
    ): List<SourceChapter> {
        val merged = merge(rows)
        return orderByNumber(merged) ?: fallback(merged)
    }
}

/**
 * The chapter-shape consistency gate (v1.3.2, V120-CHECK
 * item 85 — generalising item 50's gate from CTA labels and
 * tag paths to whole menus).
 *
 * A row qualifies as a chapter only if its URL or name yields
 * a chapter identity consistent with the book's own
 * chapter-URL pattern. The pattern is learned from the list
 * itself: the rows whose name or URL carries a chapter
 * number define the dominant URL shape (path segments, with
 * digit-bearing or slug-like segments wildcarded); a row
 * with NO number anywhere qualifies only when its URL wears
 * that same shape. Site nav and genre menus — "Novel List",
 * "Latest Release", "Action" — carry no chapter number and
 * live at listing-shaped URLs, so they never qualify on a
 * book whose real chapters are numbered. When a book's
 * chapters carry no numbers at all (title-only lists), there
 * is no pattern to judge against and the gate stays out of
 * the way.
 */
object ChapterShape {

    fun filterConsistent(
        rows: List<SourceChapter>,
        bookUrl: String
    ): List<SourceChapter> {
        if (rows.size < 3) return rows
        val numbered = rows.filter {
            ChapterNumbers.parseAny(it.name, it.id) != null
        }
        if (numbered.isEmpty()) return rows
        // The gate judges only when numbered rows are at
        // least half the list — below that, the "pattern" is
        // a guess and real title-only chapters would die.
        if (numbered.size * 2 < rows.size) return rows
        // And only when the numbered rows are chapters OF
        // THIS BOOK — under its path, or wearing a chapter
        // tail. A listing misread as a book (a home page's
        // book grid, whose cards carry rating numbers) has
        // numbered rows that are other BOOKS: there is no
        // chapter pattern to judge against, the gate stays
        // out of the way, and the caller's own book/listing
        // judgement decides the page's fate — the walk-in
        // probe's self-heal depends on reading those rows
        // exactly as they are (v1.3.2 regression, caught by
        // the V124 walk-in suite).
        val ownChapters = numbered.count {
            BookShape.isChapterRowOf(bookUrl, it.id)
        }
        if (ownChapters * 2 < numbered.size) return rows
        val dominant = numbered
            .groupingBy { shapeOf(it.id) }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key ?: return rows
        return rows.filter { row ->
            ChapterNumbers.parseAny(row.name, row.id) != null ||
                shapeOf(row.id) == dominant
        }
    }

    /** A URL's path shape: each segment literal, except
     * digit-bearing or long slug-like segments, which are
     * wildcards — the parts that vary per chapter. */
    private fun shapeOf(url: String): String {
        val path = runCatching { java.net.URI(url).path }
            .getOrNull() ?: url.substringBefore('?')
        return path.split('/')
            .filter { it.isNotBlank() }
            .joinToString("/") { segment ->
                if (segment.any { it.isDigit() } || segment.length > 16) {
                    "*"
                } else {
                    segment.lowercase()
                }
            }
    }
}
