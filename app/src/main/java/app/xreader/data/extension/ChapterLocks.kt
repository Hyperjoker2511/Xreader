package app.xreader.data.extension

import org.jsoup.nodes.Element

/**
 * Paid/locked chapter detection (v1.0.5, A3 — D18): many sites
 * list every chapter but lock the newest behind payment, marked
 * in the list row itself (an `fa-lock` icon, a "locked" class, a
 * 🔒 in the name). A test-drive that samples a locked chapter
 * reads an empty or paywalled page and wrongly declares the whole
 * source unreadable — so sampling must skip lock-marked rows, and
 * when EVERY chapter is locked the verdict must say exactly that
 * ("paid/locked" is a truthful state, not a broken source).
 *
 * Detection is deliberately token-based, not substring-based: a
 * chapter NAMED "The Lock Keeper" is not locked, and a class like
 * "chapter-block" is not a lock marker. Pure DOM/text checks;
 * fixtures pin the behaviour.
 */
object ChapterLocks {

    /** Class tokens that mark a lock when they appear on the row
     * or any element inside it (icon fonts and site conventions). */
    private val LOCK_TOKENS = setOf(
        "fa-lock", "lock", "locked", "is-locked",
        "icon-lock", "lock-icon", "chapter-locked", "locked-chapter"
    )

    /** True when a chapter-list ROW carries a lock marker: a
     * lock class token on the row or any descendant, or a 🔒 in
     * the row's text. */
    fun rowIsLocked(row: Element): Boolean {
        if (row.text().contains('🔒')) return true
        val elements = mutableListOf(row)
        elements += row.getAllElements()
        for (element in elements) {
            for (token in element.classNames()) {
                if (token.lowercase() in LOCK_TOKENS) return true
            }
            val id = element.id().lowercase()
            if (id == "lock" || id == "locked") return true
        }
        return false
    }

    /** True when a chapter NAME itself advertises a lock (🔒 or a
     * trailing "(locked)" / "[locked]" tag). A story title that
     * merely contains the word "lock" does not count. */
    fun nameSuggestsLocked(name: String): Boolean {
        if (name.contains('🔒')) return true
        val trimmed = name.trim().lowercase()
        return trimmed.endsWith("(locked)") || trimmed.endsWith("[locked]")
    }

    /** Phrases a paywall/lock notice uses on the chapter page
     * itself — evidence when the sampled page's text is short. */
    private val PAYWALL_PHRASES = listOf(
        "this chapter is locked", "chapter is locked",
        "unlock this chapter", "unlock chapter",
        "purchase this chapter", "buy this chapter",
        "please purchase", "please unlock",
        "you need to purchase", "you must purchase",
        "locked chapter"
    )

    /** True when a chapter page's plain text reads as a lock /
     * purchase notice rather than story text. Only meaningful
     * for SHORT texts (a real chapter mentioning a purchase in
     * passing is long) — callers combine it with a length check. */
    fun pageSuggestsLocked(plainText: String): Boolean {
        if (plainText.length > 600) return false
        val lower = plainText.lowercase()
        return PAYWALL_PHRASES.any { lower.contains(it) }
    }
}
