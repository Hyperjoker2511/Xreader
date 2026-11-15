package app.xreader.ui.shell

/**
 * Where the floating dock belongs (next-update Patch 6, the
 * owner's full-screen source rule, 2026-11-13: "In novel source
 * one why is there nav bar inside it — it should fully novel
 * source").
 *
 * The dock belongs to the top-level tabs. Inside a source — its
 * catalogue, and a book's page reached from it — the screen
 * belongs fully to the source and the dock is never composed.
 * Settings destinations keep the dock ONLY when they were
 * reached from the Me tab: the user is there to judge how the
 * app looks (glass, dock style), so they must never judge it
 * blind — but a settings page pushed from anywhere else owns
 * its screen like any other pushed destination.
 *
 * Routes here are destination PATTERNS, exactly as NavController
 * reports them (`entry.destination.route`): the catalogue is
 * "source/{extId}/{sourceId}", never a concrete "source/3/12".
 * The immersive routes (reader, local, ai, login) are NOT this
 * policy's business: [ShellChrome] owns them, and at the shell's
 * call site immersion wins over this policy, exactly as before.
 *
 * Pure and unit-tested (DockVisibilityPolicyTest) so the route
 * sets can't silently drift.
 */
enum class DockOrigin {
    /** The current destination is itself a tab root. */
    TAB_ROOT,

    /** A pushed destination whose back stack roots at the Me
     * tab (me → more → customise still counts: the scan walks
     * the whole stack, not just the previous entry). */
    FROM_ME_TAB,

    /** Anything else: pushed from another tab, or no tab
     * underneath at all. */
    FROM_ELSEWHERE
}

object DockVisibilityPolicy {

    /** The source catalogue's destination pattern, as declared
     * in the NavHost. */
    const val SOURCE_CATALOGUE_PATTERN = "source/{extId}/{sourceId}"

    /** The book page's destination pattern, as declared in the
     * NavHost. */
    const val BOOK_PAGE_PATTERN = "book/{bookId}"

    /** The five top-level tab routes (the [Tab] enum's routes —
     * ShellNavTest pins those; this set is pinned against them
     * by DockVisibilityPolicyTest). */
    private val TAB_ROUTES = setOf(
        "library", "browse", "history", "downloads", "me"
    )

    /** Settings destinations that keep the dock when — and only
     * when — they were reached from the Me tab. Patch 11 took
     * "terms" out of this set: the Me door is gone and the
     * terms pages (with "bookTerms/{bookId}") are reader-flow
     * destinations now, so they hide the dock from every
     * origin like the reader and its AI page do. The
     * visible-layer fix round took "updates" out the same way:
     * it is a Library CHILD page now (chapter news, wired from
     * the Library filter sheet's "New chapters" row), so it
     * hides the dock from every origin like the book page —
     * its selectedTab already maps to Library. */
    private val ME_ONLY_ROUTES = setOf(
        "customise", "appearance", "profile", "providers", "sync",
        "migration", "more", "labs", "about",
        "vocab", "appUpdates", "whatsNew", "generatedArt"
    )

    /**
     * Should the dock be composed on [route] (a destination
     * pattern, or null while the graph settles)?
     *
     * Tab routes always show it; the catalogue and the book page
     * never do, from any origin; the settings routes show it
     * only from the Me tab; everything else — and null — hides
     * it. A query string never changes the answer: one real
     * pattern carries one ("local/{bookId}?target={target}"),
     * so it is stripped before matching.
     */
    fun dockVisible(route: String?, origin: DockOrigin): Boolean {
        val pattern = route?.substringBefore("?") ?: return false
        return when {
            pattern in TAB_ROUTES -> true
            pattern == SOURCE_CATALOGUE_PATTERN ||
                pattern == BOOK_PAGE_PATTERN -> false
            pattern in ME_ONLY_ROUTES ->
                origin == DockOrigin.FROM_ME_TAB
            else -> false
        }
    }

    /**
     * Where the current destination was reached from, proven by
     * its back stack: [backStackRoutes] are the destination
     * patterns in stack order (bottom first, current last) and
     * [currentRoute] is the current destination's pattern.
     *
     * A tab destination is its own origin. Otherwise the scan
     * walks DOWN from just below the current entry to the
     * nearest tab root: the Me tab means FROM_ME_TAB (so
     * me → more → customise resolves correctly through the
     * intermediate page); any other tab — or no tab at all —
     * means FROM_ELSEWHERE.
     */
    fun computeDockOrigin(
        backStackRoutes: List<String?>,
        currentRoute: String?
    ): DockOrigin {
        val current = currentRoute?.substringBefore("?")
        if (current != null && current in TAB_ROUTES) {
            return DockOrigin.TAB_ROOT
        }
        val stack = backStackRoutes.map { it?.substringBefore("?") }
        val belowCurrent =
            if (stack.lastOrNull() == current) stack.dropLast(1) else stack
        val nearestTab = belowCurrent.lastOrNull { route ->
            route != null && route in TAB_ROUTES
        }
        return if (nearestTab == "me") {
            DockOrigin.FROM_ME_TAB
        } else {
            DockOrigin.FROM_ELSEWHERE
        }
    }
}
