package app.xreader.ui.shell

/**
 * Which routes own the whole screen (v1.0.1 immersive reader).
 *
 * The v1.0.0 shell composed its bottom bar / rail unconditionally
 * around the NavHost, so inside the reader the shell bar sat over
 * the book and the reader was laid out in the space the bar left
 * behind. The contract now: immersive routes get NO shell chrome
 * and the full screen height; the reader shows only its own chrome.
 * Pure and unit-tested so the route list can't silently drift.
 */
object ShellChrome {
    /** Destination route patterns (as declared in the NavHost). */
    private val IMMERSIVE_PATTERNS = listOf(
        "reader/{bookId}/{chapterIndex}",
        "login"
    )

    fun isImmersiveRoutePattern(route: String?): Boolean =
        route != null && route in IMMERSIVE_PATTERNS

    /** Live route check for concrete routes ("reader/42/3"). */
    fun isImmersive(route: String?): Boolean {
        if (route == null) return false
        if (route == "login") return true
        return route.startsWith("reader/")
    }
}
