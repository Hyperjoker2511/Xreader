package app.xreader.data.library

import app.xreader.data.db.Shelf
import app.xreader.data.db.ShelfKind
import app.xreader.data.db.SmartRule

/**
 * Shelf membership logic (v1.0.2, R21) — pure, so the chip rail,
 * the counts, and the Library filter all agree by construction.
 *
 * Built-in shelves are computed, never stored: **All** is pinned
 * permanently (it cannot be hidden or deleted — it is the guaranteed
 * route to the whole library); Favourites / Downloaded / Behind on
 * can be hidden but not deleted. User shelves are Room rows; MANUAL
 * membership comes from shelf_books, SMART membership is evaluated
 * here from the same [BookFacts] for every book.
 */
object ShelfLogic {

    const val BUILTIN_FAVOURITES = "favourites"
    const val BUILTIN_DOWNLOADED = "downloaded"
    const val BUILTIN_BEHIND_ON = "behind_on"

    val BUILTIN_KEYS = listOf(BUILTIN_FAVOURITES, BUILTIN_DOWNLOADED, BUILTIN_BEHIND_ON)

    fun builtinName(key: String): String = when (key) {
        BUILTIN_FAVOURITES -> "Favourites"
        BUILTIN_DOWNLOADED -> "Downloaded"
        BUILTIN_BEHIND_ON -> "Behind on"
        else -> key
    }

    /** Everything membership is computed from — one fact row per
     * visible (non-merged) library book. */
    data class BookFacts(
        val bookId: Long,
        val favourite: Boolean,
        val downloadedChapters: Int,
        val unreadDownloaded: Int,
        val unseenNewChapters: Int,
        val lastUpdatedAt: Long,
        val status: ReadingStatus,
        val tagSlugs: Set<String>
    )

    fun builtinMembers(key: String, facts: List<BookFacts>): Set<Long> = when (key) {
        BUILTIN_FAVOURITES -> facts.filter { it.favourite }.map { it.bookId }
        BUILTIN_DOWNLOADED -> facts.filter { it.downloadedChapters > 0 }.map { it.bookId }
        BUILTIN_BEHIND_ON -> facts.filter { it.unseenNewChapters > 0 }.map { it.bookId }
        else -> emptySet()
    }.toSet()

    fun smartMembers(shelf: Shelf, facts: List<BookFacts>, nowMillis: Long): Set<Long> {
        if (shelf.kind != ShelfKind.SMART) return emptySet()
        val weekAgo = nowMillis - 7L * 24 * 60 * 60 * 1000
        return when (shelf.rule) {
            SmartRule.TAG -> facts.filter { shelf.ruleValue in it.tagSlugs }
            SmartRule.UPDATED_THIS_WEEK -> facts.filter { it.lastUpdatedAt >= weekAgo }
            SmartRule.UNREAD_DOWNLOADS -> facts.filter { it.unreadDownloaded > 0 }
            SmartRule.DOWNLOADED -> facts.filter { it.downloadedChapters > 0 }
            SmartRule.READING_STATUS -> facts.filter {
                it.status.name == shelf.ruleValue
            }
            null -> emptyList()
        }.map { it.bookId }.toSet()
    }

    /** One chip on the rail. [ref] is "all", a builtin key, or
     * "user:<id>" — stable strings the UI can persist as selection. */
    data class ShelfChip(
        val ref: String,
        val name: String,
        val count: Int,
        val builtin: Boolean
    )

    /**
     * The rail: All first (always), visible builtins in their fixed
     * order, then visible user shelves in the user's order. Counts
     * are unique book counts, computed from the same membership
     * sets the filter uses.
     */
    fun chips(
        facts: List<BookFacts>,
        userShelves: List<Shelf>,
        manualMembers: Map<Long, Set<Long>>,
        hiddenBuiltins: Set<String>,
        nowMillis: Long
    ): List<ShelfChip> {
        val out = mutableListOf(
            ShelfChip("all", "All", facts.size, builtin = true)
        )
        for (key in BUILTIN_KEYS) {
            if (key in hiddenBuiltins) continue
            out += ShelfChip(
                ref = key,
                name = builtinName(key),
                count = builtinMembers(key, facts).size,
                builtin = true
            )
        }
        for (shelf in userShelves) {
            if (shelf.hidden) continue
            val members = if (shelf.kind == ShelfKind.SMART) {
                smartMembers(shelf, facts, nowMillis)
            } else {
                manualMembers[shelf.id] ?: emptySet()
            }
            out += ShelfChip("user:${shelf.id}", shelf.name, members.size, builtin = false)
        }
        return out
    }

    /** The book ids a chip ref selects (null ref/"all" = everything). */
    fun membersFor(
        ref: String?,
        facts: List<BookFacts>,
        userShelves: List<Shelf>,
        manualMembers: Map<Long, Set<Long>>,
        nowMillis: Long
    ): Set<Long>? {
        if (ref == null || ref == "all") return null
        if (ref in BUILTIN_KEYS) return builtinMembers(ref, facts)
        val id = ref.removePrefix("user:").toLongOrNull() ?: return null
        val shelf = userShelves.firstOrNull { it.id == id } ?: return emptySet()
        return if (shelf.kind == ShelfKind.SMART) {
            smartMembers(shelf, facts, nowMillis)
        } else {
            manualMembers[id] ?: emptySet()
        }
    }
}
