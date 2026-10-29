package app.xreader.data.library

/** Where a book sits in the user's own tracking, beyond what the
 * library row itself says. [NONE] is the untouched default. */
enum class ReadingStatus {
    NONE,
    READING,
    COMPLETED,
    ON_HOLD,
    DROPPED
}

/**
 * Per-book user metadata held OUTSIDE Room (v1.0.1 hotfix — no
 * schema change): a user's reading status for a book, whether the
 * book is tracked for new chapters, and the chapter counts that
 * tracking compares against. The owner persists one [encode]d
 * string per book (a preference/file entry keyed by book); this
 * type is only the value and its pure helpers.
 *
 * The persisted format is one compact line:
 *
 *     status|tracked|lastSeen|new
 *
 * e.g. `READING|1|120|3` — status is a [ReadingStatus] name,
 * tracked is `1`/`0`, and the two counts are plain integers:
 * [lastSeenChapterCount] is the chapter total when the user last
 * looked, [newChapterCount] is the stored new-chapter count from
 * the last check (the live count is [newChapters]).
 */
data class BookMeta(
    val status: ReadingStatus = ReadingStatus.NONE,
    val tracked: Boolean = false,
    val lastSeenChapterCount: Int = 0,
    val newChapterCount: Int = 0
) {
    companion object {

        /** [meta] as its compact persisted line (see class KDoc).
         * Counts are clamped at 0 — a negative count is never
         * written. */
        fun encode(meta: BookMeta): String =
            "${meta.status.name}|${if (meta.tracked) "1" else "0"}|" +
                "${meta.lastSeenChapterCount.coerceAtLeast(0)}|" +
                "${meta.newChapterCount.coerceAtLeast(0)}"

        /**
         * The inverse of [encode], tolerant of garbage by design —
         * this string can be hand-edited, truncated, or written by
         * an older build, and a bad value must cost the metadata,
         * never crash the library: null/blank/garbage input yields
         * the default [BookMeta], an unknown status yields
         * [ReadingStatus.NONE], missing fields fall back to their
         * defaults, extra fields are ignored, and negative or
         * non-numeric counts become 0. The tracked flag accepts
         * `1`/`true` (any case) as true.
         */
        fun decode(raw: String?): BookMeta {
            if (raw.isNullOrBlank()) return BookMeta()
            val parts = raw.trim().split('|')
            val status = parts.getOrNull(0)?.trim()?.let { name ->
                ReadingStatus.entries.firstOrNull {
                    it.name.equals(name, ignoreCase = true)
                }
            } ?: ReadingStatus.NONE
            val tracked = parts.getOrNull(1)?.trim()?.let {
                it == "1" || it.equals("true", ignoreCase = true)
            } ?: false
            val lastSeen = parts.getOrNull(2)?.trim()?.toIntOrNull()
                ?.coerceAtLeast(0) ?: 0
            val newCount = parts.getOrNull(3)?.trim()?.toIntOrNull()
                ?.coerceAtLeast(0) ?: 0
            return BookMeta(
                status = status,
                tracked = tracked,
                lastSeenChapterCount = lastSeen,
                newChapterCount = newCount
            )
        }

        /**
         * How many chapters are new since the user last looked:
         * `max(0, currentTotal - lastSeen)`. When the book was
         * never seen with a chapter count ([BookMeta.lastSeenChapterCount]
         * is 0) the answer is 0 — a first sighting establishes the
         * baseline via [markSeen], it doesn't report the whole book
         * as new.
         */
        fun newChapters(meta: BookMeta, currentTotal: Int): Int {
            if (meta.lastSeenChapterCount <= 0) return 0
            return maxOf(0, currentTotal - meta.lastSeenChapterCount)
        }

        /** [meta] caught up to [currentTotal]: that total becomes
         * the new baseline and the stored new count clears. */
        fun markSeen(meta: BookMeta, currentTotal: Int): BookMeta =
            meta.copy(
                lastSeenChapterCount = currentTotal.coerceAtLeast(0),
                newChapterCount = 0
            )
    }
}
