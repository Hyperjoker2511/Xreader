package app.xreader.data.library

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reading-history grouping (v1.0.1 hotfix), as pure functions over
 * progress-like records.
 *
 * The input deliberately does NOT depend on Room: [HistorySource]
 * is a small copy of the fields history needs from a
 * `ReadingProgress` row joined with its book/chapter (the Room
 * entity itself carries no title — see LibraryEntities), so the
 * caller maps rows to [HistorySource] and this grouping — day
 * boundaries, clearing, hiding — is pinned by plain JVM tests.
 *
 * Day boundaries are the supplied [ZoneId]'s by construction:
 * [HistorySource.updatedAtMillis] is an instant, and the day an
 * entry belongs to is that instant's local date in the zone.
 * [today] is injected (like `Streaks` in the gamification code) so
 * the "Today"/"Yesterday" labels are testable at the midnight edge
 * instead of hoped about.
 */
object HistoryBuilder {

    /**
     * One book's last-known reading position, in the shape history
     * displays. [bookId] is the string form of the book's id (the
     * same form [resumeRoute] interpolates and [hiddenBookIds] in
     * [build] are keyed by).
     */
    data class HistorySource(
        val bookId: String,
        val title: String,
        val coverPath: String? = null,
        val chapterTitle: String? = null,
        val chapterIndex: Int,
        val fraction: Float,
        val updatedAtMillis: Long
    )

    /** History entries under one plain heading, newest first.
     * [label] is "Today", "Yesterday", or "Earlier" — v1.0.6.2
     * (H1): everything older than yesterday gathers under the
     * one plain word instead of a list of ISO dates. */
    data class DayGroup(
        val label: String,
        val entries: List<HistorySource>
    )

    /**
     * Group [sources] into [DayGroup]s, newest first both across
     * groups (today's group first) and within each group.
     *
     * Exclusions, applied before grouping:
     * - entries whose book is in [hiddenBookIds];
     * - entries at or before [clearedBeforeMillis] — the
     *   "clear history" watermark: an entry timestamped exactly AT
     *   the watermark was already on screen when the user cleared,
     *   so it goes too. The default 0 excludes nothing real (no
     *   genuine reading instant is at the epoch).
     */
    fun build(
        sources: List<HistorySource>,
        hiddenBookIds: Set<String> = emptySet(),
        clearedBeforeMillis: Long = 0L,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone)
    ): List<DayGroup> {
        val visible = sources.filter { source ->
            source.bookId !in hiddenBookIds &&
                source.updatedAtMillis > clearedBeforeMillis
        }
        // Group by the plain label: Today, then Yesterday, then
        // one merged Earlier bucket (newest first throughout).
        val byLabel = visible.groupBy { source ->
            labelFor(localDate(source.updatedAtMillis, zone), today)
        }
        return listOf("Today", "Yesterday", "Earlier").mapNotNull { label ->
            val entries = byLabel[label]
                ?.sortedByDescending { it.updatedAtMillis }
                ?: return@mapNotNull null
            DayGroup(label = label, entries = entries)
        }
    }

    /**
     * The reader route for resuming a book — exactly the route the
     * shell registers and navigates to (`reader/{bookId}/{chapterIndex}`
     * in MainScaffold). A negative [chapterIndex] is clamped to 0.
     */
    fun resumeRoute(bookId: String, chapterIndex: Int): String =
        "reader/$bookId/${chapterIndex.coerceAtLeast(0)}"

    private fun localDate(epochMillis: Long, zone: ZoneId): LocalDate =
        LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), zone)

    private fun labelFor(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> "Earlier"
    }
}
