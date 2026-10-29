package app.xreader.data.extension

/**
 * "Bring my library from a site" reconciliation (v1.0.2, R24 + the
 * Q1 answer: the library lives on SEVERAL sites — Webnovel,
 * Wuxiaworld, Novelpia, etc. — so this engine is per-site and
 * generic; the site specifics live in the R10 contract's account
 * capabilities, not here).
 *
 * The flow is scan → preview → import, never a silent write:
 * - duplicates match by source identity, then confirmed work match;
 * - progress is furthest-position-wins and never moves backwards;
 * - an exact chapter match is used where possible, else the
 *   nearest earlier chapter — and the result calls it approximate;
 * - a site "favourite" sets Xreader Favourite ONLY when the site
 *   category is explicitly favourites; a general bookshelf doesn't;
 * - import never downloads chapter bodies.
 *
 * Pure: the planner consumes plain snapshots and produces a preview
 * plus a per-book decision list the executor applies idempotently.
 */
enum class AccountCategory { BOOKSHELF, PROGRESS, FAVOURITES, BOOKMARKS, HISTORY, FOLLOWS }

data class AccountBook(
    /** The source's own key for this book (canonical locator part). */
    val remoteKey: String,
    val title: String,
    val author: String = "",
    /** Chapter key the site says the user reached, if shown. */
    val progressChapterKey: String? = null,
    val progressFraction: Float = 0f,
    /** Site categories this entry came from. */
    val categories: Set<AccountCategory> = setOf(AccountCategory.BOOKSHELF)
)

data class ExistingBookState(
    val bookId: Long,
    val remoteKey: String?,
    val title: String,
    val author: String = "",
    /** Current local position for furthest-wins comparison. */
    val progressChapterIndex: Int = -1,
    val progressFraction: Float = 0f
)

enum class ImportAction { CREATE, UPDATE_PROGRESS, ALREADY_PRESENT, SKIP }

data class ImportDecision(
    val account: AccountBook,
    val action: ImportAction,
    val existingBookId: Long? = null,
    /** Chapter index the imported progress maps to (executor fills
     * the chapter-key mapping; -1 = unmappable). */
    val mappedChapterIndex: Int = -1,
    val approximate: Boolean = false,
    val setFavourite: Boolean = false
)

data class ImportPreview(
    val found: Int,
    val alreadyPresent: Int,
    val newBooks: Int,
    val mappablePositions: Int,
    val approximatePositions: Int,
    val decisions: List<ImportDecision>
) {
    fun summaryLine(): String =
        "$found found · $alreadyPresent already in your Library · " +
            "$newBooks new · $mappablePositions reading places can be brought over" +
            if (approximatePositions > 0) " ($approximatePositions approximate)" else ""
}

object AccountImportPlanner {

    /**
     * [chapterIndexForKey] maps a site chapter key to the book's
     * canonical chapter index (exact), or null; [nearestEarlier]
     * gives the nearest earlier index when only an approximate
     * landing exists. Both are per-book lookups the executor builds
     * from the canonical chapter list (R03).
     */
    fun plan(
        accountBooks: List<AccountBook>,
        existing: List<ExistingBookState>,
        selectedCategories: Set<AccountCategory>,
        chapterIndexForKey: (bookKey: String, chapterKey: String) -> Int?,
        nearestEarlier: (bookKey: String, chapterKey: String) -> Int?
    ): ImportPreview {
        val byRemote = existing.mapNotNull { state ->
            state.remoteKey?.let { it to state }
        }.toMap()
        val decisions = mutableListOf<ImportDecision>()
        for (account in accountBooks) {
            // Only entries from categories the user left selected
            // participate at all (preview honesty, R24).
            if (account.categories.none { it in selectedCategories }) {
                decisions += ImportDecision(account, ImportAction.SKIP)
                continue
            }
            val match = byRemote[account.remoteKey] ?: existing.firstOrNull {
                WorkMatcher.sameWork(it.title, it.author, account.title, account.author)
            }
            val setFavourite = AccountCategory.FAVOURITES in account.categories &&
                AccountCategory.FAVOURITES in selectedCategories
            if (match == null) {
                decisions += ImportDecision(
                    account = account,
                    action = ImportAction.CREATE,
                    setFavourite = setFavourite
                )
                continue
            }
            // Furthest-wins: mapped import position vs local.
            var mapped = -1
            var approximate = false
            if (AccountCategory.PROGRESS in selectedCategories &&
                account.progressChapterKey != null
            ) {
                val exact = chapterIndexForKey(account.remoteKey, account.progressChapterKey)
                if (exact != null) {
                    mapped = exact
                } else {
                    val near = nearestEarlier(account.remoteKey, account.progressChapterKey)
                    if (near != null) {
                        mapped = near
                        approximate = true
                    }
                }
            }
            val importIsFurther = mapped > match.progressChapterIndex ||
                (mapped == match.progressChapterIndex && mapped >= 0 &&
                    account.progressFraction > match.progressFraction)
            decisions += ImportDecision(
                account = account,
                action = if (importIsFurther) ImportAction.UPDATE_PROGRESS
                else ImportAction.ALREADY_PRESENT,
                existingBookId = match.bookId,
                mappedChapterIndex = mapped,
                approximate = approximate && importIsFurther,
                setFavourite = setFavourite
            )
        }
        val active = decisions.filter { it.action != ImportAction.SKIP }
        return ImportPreview(
            found = active.size,
            alreadyPresent = active.count {
                it.action == ImportAction.ALREADY_PRESENT ||
                    it.action == ImportAction.UPDATE_PROGRESS
            },
            newBooks = active.count { it.action == ImportAction.CREATE },
            mappablePositions = active.count { it.mappedChapterIndex >= 0 },
            approximatePositions = active.count { it.approximate },
            decisions = decisions
        )
    }
}
