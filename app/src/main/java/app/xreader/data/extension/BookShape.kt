package app.xreader.data.extension

import app.xreader.data.db.InstalledExtension
import java.net.URI

/**
 * v1.1.0 (Nocturne reskin design §6.0): ONE shared Book-Shape
 * Rule for every catalogue path — the lane spec, the declarative
 * engine and Live derivation all discriminate books the same
 * way, because both catalogue bugs (the chikari lane's and the
 * Live install's "empty or junk" catalogue) were the same bug:
 * comment/chapter-shaped links accepted as books.
 *
 * > A catalogue item is a book only if its link's path ends at
 * > the book slug — `/novels/<slug>` with NO further path
 * > segment — and its title comes from the cover image inside
 * > the link (`img@alt`). Links to `/novels/<slug>/<anything>`
 * > are chapters or comment targets, never books. The href
 * > shape is the discriminator: comment cards DO have images
 * > (their avatars), so "has a cover" alone cannot separate
 * > them.
 */
object BookShape {

    /** The path a listing href points at, normalised the way
     * the rule reads it: query and `#fragment` stripped, ONE
     * trailing slash removed, lower-cased host noise removed by
     * reading only the URI path. A relative href is resolved
     * against [pageUrl] by the caller before this is read; a
     * raw path also works. */
    fun normalisedPath(url: String): String {
        val noFragment = url.substringBefore('#').substringBefore('?')
        val path = runCatching { URI(noFragment).path }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: noFragment.substringAfter("://")
                .substringAfter('/', "")
                .let { if (it.isBlank()) noFragment else "/$it" }
        return path.trimEnd('/').ifBlank { "/" }
    }

    /** Whether [linkUrl]'s normalised path matches the
     * book-shape [pattern] (a regex over the path, e.g.
     * `/novels/[^/]+$`). A blank or broken pattern never
     * matches — a misdeclared rule drops the item rather than
     * admitting junk, and the listing's own test-drive surfaces
     * the empty result loudly. */
    fun matchesPattern(linkUrl: String, pattern: String): Boolean {
        if (pattern.isBlank()) return false
        val regex = runCatching { Regex(pattern) }.getOrNull() ?: return false
        return regex.containsMatchIn(normalisedPath(linkUrl))
    }

    /** The generic, site-agnostic half of the rule, for Live
     * derivation where no site pattern is known: a listing
     * anchor is chapter/comment-shaped when its path's LAST
     * segment is a bare number (`/novels/<slug>/12`), when it
     * names a chapter in the path (`…/chapter-12`), or when it
     * points below a book-shaped path with a numeric tail.
     * Book hrefs end at the slug — never at a number. */
    fun isChapterShapedHref(href: String): Boolean {
        val path = normalisedPath(href)
        if (path == "/") return false
        val segments = path.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return false
        val last = segments.last().lowercase()
        if (last.all { it.isDigit() }) {
            // v1.2.1 (Defects 1+2): a bare number under a
            // BOOK-named collection is a book ID, not a chapter
            // number — app-shell and id-catalogue sites address
            // books as /book/<id>, /novel/<id>, /series/<id>,
            // with chapters under a DIFFERENT collection
            // (/chapter/<id>). The v1.1.1 reading ("a book href
            // never ends at a number") misread every such book
            // link as a chapter tail: the site's books became
            // invisible to booksOnly derivation while its
            // recommendation cards passed the chapter tests.
            // The collection segment carries the meaning, so
            // the tail is only chapter-shaped when its parent
            // segment does not name a book collection. A real
            // chapter tail keeps a SLUG parent (/novels/<slug>/12)
            // or a chapter-named one, so it is unaffected.
            val parent = segments.getOrNull(segments.size - 2)
                ?.lowercase()
            return parent !in BOOK_COLLECTION_SEGMENTS
        }
        if (CHAPTER_SEGMENT.containsMatchIn(last)) return true
        return false
    }

    /** Path segments that name a BOOK collection — a numeric
     * tail beneath one is the book's id (v1.2.1). */
    private val BOOK_COLLECTION_SEGMENTS = setOf(
        "book", "books", "novel", "novels", "series", "title",
        "titles", "story", "stories", "work", "works", "comic",
        "comics", "manga", "manhwa", "fiction", "fictions"
    )

    private val CHAPTER_SEGMENT =
        Regex("(?i)^(chapter|chap|ch)[-_]?\\d+(\\.\\d+)?(\\.html?)?$")

    /**
     * v1.1.1: the FULL Book-Shape Rule for one href — the
     * generic half [isChapterShapedHref] was only half of it.
     * A book href's path ENDS AT A SLUG UNDER A COLLECTION
     * PREFIX: at least two segments (`/novels/<slug>`,
     * `/series/<slug>`, `/en/novels/<slug>`), the last of
     * which is the book's own slug (never chapter-shaped).
     *
     * What this excludes by construction — the shapes that
     * reached the catalogue on device in v1.1.0:
     * - chat/comment endpoints whose identity lives in the
     *   QUERY (`/chat?comment=4346840` → path `/chat`, ONE
     *   segment). v1.1.0's filter only asked "is the tail
     *   chapter-shaped?", and "chat" is not — so the whole
     *   General Chat rail passed as books.
     * - bare section paths (`/novels`, `/`) and chapter
     *   tails (`/novels/<slug>/214`).
     */
    fun isBookShapedHref(href: String): Boolean {
        val segments = normalisedPath(href)
            .split('/').filter { it.isNotBlank() }
        if (segments.size < 2) return false
        return !isChapterShapedHref(href)
    }

    /** The collection prefix of a book href: its normalised
     * path minus the slug (`/novels/<slug>` → `/novels`,
     * `/en/novels/<slug>` → `/en/novels`). Anchors that share
     * a prefix belong to one collection; a listing cluster
     * is only ever drawn from ONE prefix group, so sections
     * can never blend into a catalogue. Blank when the path
     * has no prefix (not book-shaped). */
    fun collectionPrefixOf(href: String): String {
        val path = normalisedPath(href)
        val cut = path.lastIndexOf('/')
        return if (cut > 0) path.substring(0, cut) else ""
    }

    /** The [ListSpec.linkPattern] that enforces one
     * collection's Book-Shape Rule at fetch time:
     * `/novels` → `/novels/[^/]+$` (the lane's own spelling).
     * Regex metacharacters in the prefix are escaped, but
     * `/` is left plain so stored specs stay readable. */
    fun linkPatternForPrefix(prefix: String): String {
        val escaped = StringBuilder()
        for (ch in prefix) {
            if (ch in "\\.^$|?*+()[]{}") escaped.append('\\')
            escaped.append(ch)
        }
        return "$escaped/[^/]+$"
    }
}

/**
 * v1.1.0 (design §6.4, Fix 5): the ONE rebuildability helper.
 * Every gate that used to test `packageId.startsWith("link.")`
 * — config loading, Repair, the parser-rules save, the screen
 * copy, the book page's Repair door — asks this instead, so a
 * healed row can never be rebuildable at one gate and refused
 * at another.
 *
 * Rebuildable = a link source, OR a Live Source (repaired by
 * re-derivation/conversion, §6.3), OR a DECLARATIVE row whose
 * stored definition decodes to exactly ONE config (a repo
 * package holding one source's rules — re-probing it heals in
 * place and deliberately leaves the repo's update channel).
 * Foreign-code rows (JS/DEX) and multi-config packages have no
 * declarative rules to rebuild: Repair test-drives them only.
 *
 * HealSweep eligibility deliberately does NOT use this helper
 * (design §6.4 sweep note): the sweep stays link-only.
 */
enum class RebuildKind {
    /** `link.*` — probe-saved, incl. probe-saved lanes. */
    LINK,

    /** `live.*` — repaired via §6.3 re-derive → convert. */
    LIVE,

    /** Repo packageId, DECLARATIVE, exactly one stored config. */
    REPO_DECLARATIVE_SINGLE,

    /** Repo packageId, DECLARATIVE, zero or several configs. */
    REPO_DECLARATIVE_MULTI,

    /** Foreign code (JS/DEX) — no declarative rules exist. */
    REPO_CODE,

    /** Anything else (unknown shape). */
    OTHER
}

object SourceRebuildability {

    /** Classify from the row plus its raw stored definition
     * (the definition file's text; null when unreadable). */
    fun classify(
        extension: InstalledExtension,
        definitionRaw: String?
    ): RebuildKind {
        val configCount = definitionRaw?.let {
            DeclarativeFingerprints.decode(it).size
        } ?: -1
        return classify(
            packageId = extension.packageId,
            kind = extension.kind,
            configCount = configCount
        )
    }

    /** Classify from parts, for callers that hold a row's
     * identity but not its definition ([configCount] < 0 =
     * unknown; a DECLARATIVE row of unknown count is treated
     * as not-yet-proven rebuildable). */
    fun classify(
        packageId: String,
        kind: app.xreader.data.db.ExtensionKind,
        configCount: Int
    ): RebuildKind = when {
        // The row's KIND decides first: a converted Live row
        // keeps its live.* packageId (§6.3) but is DECLARATIVE
        // now, with editable rules like any single-config row.
        kind == app.xreader.data.db.ExtensionKind.LIVE ->
            RebuildKind.LIVE
        packageId.startsWith("link.") -> RebuildKind.LINK
        kind == app.xreader.data.db.ExtensionKind.JS ||
            kind == app.xreader.data.db.ExtensionKind.DEX ->
            RebuildKind.REPO_CODE
        kind == app.xreader.data.db.ExtensionKind.DECLARATIVE ->
            if (configCount == 1) {
                RebuildKind.REPO_DECLARATIVE_SINGLE
            } else {
                RebuildKind.REPO_DECLARATIVE_MULTI
            }
        else -> RebuildKind.OTHER
    }

    /** True when Repair may rebuild this row's rules (the
     * §6.3 Live flow counts: it rebuilds how the site is read). */
    fun isRebuildable(kind: RebuildKind): Boolean = when (kind) {
        RebuildKind.LINK,
        RebuildKind.LIVE,
        RebuildKind.REPO_DECLARATIVE_SINGLE -> true
        RebuildKind.REPO_DECLARATIVE_MULTI,
        RebuildKind.REPO_CODE,
        RebuildKind.OTHER -> false
    }

    /** True when the row carries editable declarative rules
     * TODAY (a Live row's rules become editable only after a
     * Repair converts it — §6.3). */
    fun hasEditableRules(kind: RebuildKind): Boolean = when (kind) {
        RebuildKind.LINK,
        RebuildKind.REPO_DECLARATIVE_SINGLE -> true
        RebuildKind.LIVE,
        RebuildKind.REPO_DECLARATIVE_MULTI,
        RebuildKind.REPO_CODE,
        RebuildKind.OTHER -> false
    }

    /** The honest parser-rules line for a row kind (§6.4). */
    fun parserRulesLine(kind: RebuildKind): String = when (kind) {
        RebuildKind.LINK ->
            "These rules are how Xreader reads this site's pages. " +
                "If the site changes its layout and reading breaks, " +
                "you can fix them here — a test read must pass " +
                "before new rules are saved."
        RebuildKind.LIVE ->
            "This is a Live Source: it keeps no saved rules — " +
                "Xreader works out how to read the site each time. " +
                "Run Repair and, if the site can be read with fixed " +
                "rules, it becomes a regular source whose rules " +
                "you can edit here."
        RebuildKind.REPO_DECLARATIVE_SINGLE ->
            "These rules came from its repository. Repair can " +
                "rebuild them in place — doing so leaves the " +
                "repository's update channel for this source — " +
                "and you can edit them here; a test read must " +
                "pass before new rules are saved."
        RebuildKind.REPO_DECLARATIVE_MULTI ->
            "This package holds several sources' rules, so they " +
                "can't be edited here — if they break, the " +
                "repository needs to update them. Repair can run " +
                "the test-drive and show exactly which step fails."
        RebuildKind.REPO_CODE ->
            "This source's rules live inside its package — " +
                "Repair can check it, but only its repository " +
                "can update it."
        RebuildKind.OTHER ->
            "This source's rules can't be edited here. Repair " +
                "can run the test-drive and show exactly which " +
                "step fails."
    }

    /** The honest Repair line for a row kind (§6.4) — what the
     * confirm dialog and the Health card say BEFORE Repair runs,
     * replacing the old dialog's false promise. */
    fun repairLine(kind: RebuildKind): String = when (kind) {
        RebuildKind.LINK ->
            "Xreader will re-read the site's pages and rebuild " +
                "this source's rules, then run the test-drive " +
                "again. Your current rules are backed up first " +
                "and restored if the rebuild can't be saved."
        RebuildKind.LIVE ->
            "Xreader will work out how to read this site again " +
                "and test it. If that still fails, it will try to " +
                "rebuild this as a regular source with fixed " +
                "rules — same source, same books."
        RebuildKind.REPO_DECLARATIVE_SINGLE ->
            "Xreader will re-read the site and rebuild this " +
                "source's rules in place, then test again. This " +
                "leaves the repository's update channel for this " +
                "source — its repository will no longer update it."
        RebuildKind.REPO_DECLARATIVE_MULTI,
        RebuildKind.REPO_CODE ->
            "This source's rules live inside its package — " +
                "Repair can check it, but only its repository " +
                "can update it. Repair will run the test-drive " +
                "and show exactly which step fails."
        RebuildKind.OTHER ->
            "Repair will run the test-drive and show exactly " +
                "which step fails."
    }
}
