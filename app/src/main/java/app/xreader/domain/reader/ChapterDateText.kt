package app.xreader.domain.reader

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Chapter release dates (v1.0.6.3, P5): sources print dates as
 * text (novelight's DD.MM.YYYY row spans); the app stores the raw
 * text and renders it relatively at display time — "Today",
 * "Yesterday", "3 days ago" — the way the owner asked to see
 * when the latest chapter landed. Pure and JVM-tested: an
 * unparseable date renders as NOTHING (never a guess, never a
 * raw string dumped on the row), and a future date renders as
 * an absolute date, never negative nonsense.
 */
object ChapterDateText {

    private val DOTTED: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val ABSOLUTE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM yyyy")

    /** Parse the shapes sources actually print; null otherwise. */
    fun parse(raw: String?): LocalDate? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { LocalDate.parse(text, DOTTED) }.getOrNull()
            ?: runCatching { LocalDate.parse(text, ISO) }.getOrNull()
    }

    /** "Today" / "Yesterday" / "N days ago" / "N weeks ago" /
     * an absolute date for older (or future) ones; null when the
     * raw text carries no readable date. */
    fun relative(raw: String?, today: LocalDate = LocalDate.now()): String? {
        val date = parse(raw) ?: return null
        val days = ChronoUnit.DAYS.between(date, today)
        return when {
            days < 0L -> date.format(ABSOLUTE)
            days == 0L -> "Today"
            days == 1L -> "Yesterday"
            days < 7L -> "$days days ago"
            days < 30L -> {
                val weeks = days / 7
                if (weeks == 1L) "1 week ago" else "$weeks weeks ago"
            }
            else -> date.format(ABSOLUTE)
        }
    }

    /** The relative label of the NEWEST parseable date in the
     * list — the book page's "Latest chapter · …" freshness line.
     * Null when no chapter carries a readable date. */
    fun latestRelative(
        rawDates: List<String?>,
        today: LocalDate = LocalDate.now()
    ): String? {
        val newest = rawDates.mapNotNull { parse(it) }.maxOrNull()
            ?: return null
        return relative(newest.format(ISO), today)
    }
}
