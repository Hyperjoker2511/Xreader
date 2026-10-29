package app.xreader.data.extension

import app.xreader.data.db.Chapter
import app.xreader.data.db.DownloadState
import app.xreader.extensionapi.SourceChapter

/**
 * Chapter identity reconciliation (v1.0.2, R03 — item 15's root fix).
 *
 * v1.0.1 matched a refreshed chapter list to stored rows by remoteId
 * ONLY: when a source changed its chapter URLs (site migration,
 * slug change, mirror switch), every chapter looked brand-new, the
 * insert collided with the unique (bookId, index) index or produced
 * a second list, and the reader — opening the row it was given —
 * reported "This chapter isn't in the book anymore".
 *
 * This pure planner is the single matching brain. It pairs each
 * fetched chapter with at most one stored row, strongest evidence
 * first:
 *  1. exact remoteId
 *  2. canonicalised remoteId (whitespace / trailing slash / case)
 *  3. same position + same normalised title
 *  4. same chapter number (source-supplied or parsed from titles)
 *  5. same normalised title anywhere
 *
 * A matched row KEEPS its row id — downloads, read state, bookmarks
 * and progress anchors all hang off the row, so a remoteId change is
 * an alias update, never a delete+insert. Unmatched fetched chapters
 * insert at their fetched position. Unmatched stored rows are stale:
 * dropped only when they carry nothing of the user's (no downloaded
 * text, not queued/downloading, never read); otherwise they are KEPT
 * and re-indexed after the fetched list so nothing the user has is
 * ever silently deleted by a refresh.
 *
 * The plan is collision-free by construction: every update/insert
 * target index is unique across the plan. Pure and JVM-tested.
 */
object ChapterReconciler {

    data class Update(
        val row: Chapter,
        val newIndex: Int,
        val newTitle: String,
        val newRemoteId: String
    )

    data class Insert(val index: Int, val fetched: SourceChapter)

    data class Plan(
        val updates: List<Update>,
        val inserts: List<Insert>,
        /** Stale rows safe to delete (nothing of the user's in them). */
        val dropped: List<Chapter>,
        /** Stale rows kept and moved after the fetched list. */
        val keptStale: List<Update>
    )

    fun reconcile(existing: List<Chapter>, fetched: List<SourceChapter>): Plan {
        val used = mutableSetOf<Long>()
        val updates = mutableListOf<Update>()
        val inserts = mutableListOf<Insert>()

        fun free(row: Chapter) = row.id !in used

        fetched.forEachIndexed { index, chapter ->
            val match = findMatch(existing, chapter, index, ::free)
            if (match == null) {
                inserts += Insert(index, chapter)
            } else {
                used += match.id
                updates += Update(match, index, chapter.name, chapter.id)
            }
        }

        val stale = existing.filter { it.id !in used }.sortedBy { it.index }
        val dropped = mutableListOf<Chapter>()
        val keptStale = mutableListOf<Update>()
        stale.forEach { row ->
            if (isDisposable(row)) {
                dropped += row
            } else {
                keptStale += Update(
                    row = row,
                    newIndex = fetched.size + keptStale.size,
                    newTitle = row.title,
                    newRemoteId = row.remoteId ?: ""
                )
            }
        }
        return Plan(updates, inserts, dropped, keptStale)
    }

    /** A stored row is disposable only when the user has nothing in
     * it: no downloaded text on disk, no download in flight, unread. */
    fun isDisposable(row: Chapter): Boolean =
        row.contentPath == null &&
            row.downloadState == DownloadState.NONE &&
            !row.read

    private fun findMatch(
        existing: List<Chapter>,
        fetched: SourceChapter,
        fetchedIndex: Int,
        free: (Chapter) -> Boolean
    ): Chapter? {
        // 1. Exact remoteId.
        existing.firstOrNull { free(it) && it.remoteId == fetched.id }
            ?.let { return it }
        // 2. Canonicalised remoteId (URL-ish ids: case/slash drift).
        val canonical = canonicalId(fetched.id)
        existing.firstOrNull { free(it) && canonicalId(it.remoteId) == canonical }
            ?.let { return it }
        // 3. Same position, same normalised title.
        val fetchedTitle = ChapterMatcher.normaliseTitle(fetched.name)
        if (fetchedTitle.isNotEmpty()) {
            existing.firstOrNull {
                free(it) && it.index == fetchedIndex &&
                    ChapterMatcher.normaliseTitle(it.title) == fetchedTitle
            }?.let { return it }
        }
        // 4. Same chapter number.
        val fetchedNumber = fetched.number ?: ChapterNumbers.parse(fetched.name)
        if (fetchedNumber != null) {
            existing.firstOrNull {
                free(it) &&
                    (ChapterNumbers.parse(it.title)?.let { n ->
                        kotlin.math.abs(n - fetchedNumber) < 0.001f
                    } == true)
            }?.let { return it }
        }
        // 5. Same normalised title anywhere.
        if (fetchedTitle.isNotEmpty()) {
            existing.firstOrNull {
                free(it) && ChapterMatcher.normaliseTitle(it.title) == fetchedTitle
            }?.let { return it }
        }
        return null
    }

    private fun canonicalId(id: String?): String =
        (id ?: "").trim().trimEnd('/').lowercase()
}
