package app.xreader.domain.reader

import app.xreader.data.db.Chapter
import app.xreader.data.db.DownloadState

/**
 * v1.0.7 (draw-check ask): search + filters for the book
 * page's chapter list. A 1,692-chapter list is unusable by
 * scrolling alone — worse when a long novel is split into
 * parts and only the later parts carry the marker in their
 * titles ("TCF Part 2 – …"), which made Part 1 a
 * scroll-and-hunt chore.
 *
 * Pure and unit-tested; the screen feeds it the full list
 * and renders what comes back.
 */
object ChapterListFilter {

    enum class State { ALL, UNREAD, DOWNLOADED }

    /** "Part 2", "Volume 3", "Book 2", "Season 2" as printed
     * inside a chapter title. Requires the number right after
     * the word, so "The Parting" never matches. */
    private val PART_MARKER =
        Regex("""(?i)\b(part|volume|vol|book|season)\s*\.?\s*(\d+)""")

    /** The part label a title declares, or null. */
    fun partLabelOf(title: String): String? =
        PART_MARKER.find(title)?.let { match ->
            val kind = when (match.groupValues[1].lowercase()) {
                "vol", "volume" -> "Volume"
                "part" -> "Part"
                "book" -> "Book"
                else -> "Season"
            }
            "$kind ${match.groupValues[2]}"
        }

    /**
     * Every chapter's part label, in list order. A chapter
     * with no marker inherits the part in force where it
     * sits; chapters before the first marker belong to the
     * part just before it ("Part 1" when the first marker is
     * "Part 2"). Null when the book shows no part structure
     * at all (no markers, or only one part).
     */
    fun partAssignments(chapters: List<Chapter>): List<String>? {
        val marked = chapters.mapNotNull { chapter ->
            partLabelOf(chapter.title)?.let { chapter to it }
        }
        if (marked.isEmpty()) return null
        val firstLabel = marked.first().second
        val kind = firstLabel.substringBeforeLast(' ')
        val firstNumber = firstLabel.substringAfterLast(' ')
            .toIntOrNull() ?: 1
        val beforeLabel = "$kind ${(firstNumber - 1).coerceAtLeast(1)}"
        val labels = ArrayList<String>(chapters.size)
        var current = beforeLabel
        for (chapter in chapters) {
            val own = partLabelOf(chapter.title)
            if (own != null) {
                current = own
            }
            labels += current
        }
        // Only a real split counts: two or more distinct parts.
        return labels.takeIf { it.toSet().size >= 2 }
    }

    /** The filter chips to offer, in first-appearance order. */
    fun availableParts(chapters: List<Chapter>): List<String> =
        partAssignments(chapters)?.distinct() ?: emptyList()

    fun apply(
        chapters: List<Chapter>,
        query: String,
        state: State,
        part: String?
    ): List<Chapter> {
        val assignments = if (part != null) {
            partAssignments(chapters)
        } else {
            null
        }
        val needle = query.trim().lowercase()
        return chapters.filterIndexed { i, chapter ->
            if (part != null &&
                (assignments == null || assignments[i] != part)
            ) {
                return@filterIndexed false
            }
            when (state) {
                State.ALL -> Unit
                State.UNREAD -> if (chapter.read) return@filterIndexed false
                State.DOWNLOADED -> if (
                    chapter.downloadState != DownloadState.READY &&
                    chapter.contentPath == null
                ) {
                    return@filterIndexed false
                }
            }
            if (needle.isNotEmpty()) {
                val inTitle = chapter.title.lowercase().contains(needle)
                val inNumber = needle.all { it.isDigit() } &&
                    (chapter.index + 1).toString() == needle
                if (!inTitle && !inNumber) return@filterIndexed false
            }
            true
        }
    }
}
