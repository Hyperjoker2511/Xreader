package app.xreader.data.library

/**
 * Search inside my downloaded books (v1.0.2, sweep addendum —
 * Librera's multi-document word search): full-text search over
 * DOWNLOADED / local chapter text only, so it is honest about its
 * scope and safe on the 2 GB tablet (no network, no whole-library
 * scan of undownloaded chapters). Results carry the chapter and a
 * sentence-sized snippet around the first match.
 *
 * Pure over supplied chapter texts; the caller loads content files
 * for downloaded chapters only, in bounded batches.
 */
data class ChapterTextForSearch(
    val bookId: Long,
    val bookTitle: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val text: String
)

data class ContentHit(
    val bookId: Long,
    val bookTitle: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val snippet: String,
    val matchCount: Int
)

object ContentSearcher {

    private const val SNIPPET_RADIUS = 60

    fun search(query: String, chapters: List<ChapterTextForSearch>): List<ContentHit> {
        val needle = query.trim().lowercase()
        if (needle.length < 2) return emptyList()
        val hits = mutableListOf<ContentHit>()
        for (chapter in chapters) {
            val haystack = chapter.text.lowercase()
            var count = 0
            var firstAt = -1
            var from = 0
            while (true) {
                val at = haystack.indexOf(needle, from)
                if (at < 0) break
                if (firstAt < 0) firstAt = at
                count++
                from = at + needle.length
                if (count > 999) break // count cap: some is enough
            }
            if (count == 0 || firstAt < 0) continue
            val start = (firstAt - SNIPPET_RADIUS).coerceAtLeast(0)
            val end = (firstAt + needle.length + SNIPPET_RADIUS)
                .coerceAtMost(chapter.text.length)
            val snippet = buildString {
                if (start > 0) append("…")
                append(chapter.text.substring(start, end).replace('\n', ' ').trim())
                if (end < chapter.text.length) append("…")
            }
            hits += ContentHit(
                bookId = chapter.bookId,
                bookTitle = chapter.bookTitle,
                chapterIndex = chapter.chapterIndex,
                chapterTitle = chapter.chapterTitle,
                snippet = snippet,
                matchCount = count
            )
        }
        // Most matches first; ties keep reading order.
        return hits.sortedWith(
            compareByDescending<ContentHit> { it.matchCount }
                .thenBy { it.bookTitle.lowercase() }
                .thenBy { it.chapterIndex }
        )
    }
}
