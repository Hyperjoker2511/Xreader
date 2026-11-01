package app.xreader.domain.reader

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Chapter release dates (v1.0.6.3, P5): sources print dates as
 * text (novelight's DD.MM.YYYY row spans); the app stores the raw
 * text and renders it relatively at display time. Pure and
 * JVM-tested: an unparseable date renders as NOTHING (never a
 * guess, never a raw string dumped on the row), and a future date
 * renders as an absolute date, never negative nonsense.
 *
 * v1.3.0 delta (V120-CHECK 53): API lanes carry full timestamps
 * (chikari's `created_at`, NovelBuddy's `updated_at`) — those now
 * parse too, and relative wording follows the owner's examples:
 * "just now", "5 min ago", "1 hour ago", "3 days ago",
 * "2 weeks ago", "a month ago", "3 months ago"; beyond about a
 * year, a short date. Date-only values keep day granularity
 * ("Today" / "Yesterday" / …) — sub-day wording is never
 * fabricated from a date that carries no time.
 */
object ChapterDateText {

    private val DOTTED: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val ABSOLUTE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM yyyy")

    /** True when [raw] carries a time-of-day (an ISO timestamp
     * or an epoch number), so sub-day wording is honest. */
    private fun hasTimeComponent(raw: String): Boolean =
        raw.contains('T') || raw.all { it.isDigit() }

    /** Parse the shapes sources actually print; null otherwise. */
    fun parse(raw: String?): LocalDate? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { LocalDate.parse(text, DOTTED) }.getOrNull()
            ?: runCatching { LocalDate.parse(text, ISO) }.getOrNull()
            ?: parseInstant(text)?.atZone(ZoneId.systemDefault())
                ?.toLocalDate()
    }

    /** The instant a timestamp-shaped value names: ISO-8601
     * instants and offset date-times (chikari), local date-times
     * (treated as UTC — the APIs that print them are UTC), and
     * epoch seconds / milliseconds. Null for date-only text. */
    fun parseInstant(raw: String?): Instant? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text.all { it.isDigit() }) {
            val value = text.toLongOrNull() ?: return null
            return runCatching {
                // 10 digits ≈ seconds, 13 ≈ milliseconds.
                if (text.length >= 12) Instant.ofEpochMilli(value)
                else Instant.ofEpochSecond(value)
            }.getOrNull()
        }
        return runCatching { Instant.parse(text) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant() }
                .getOrNull()
            ?: runCatching {
                LocalDateTime.parse(text).toInstant(java.time.ZoneOffset.UTC)
            }.getOrNull()
    }

    /** Relative wording against an explicit clock (the
     * instant-precise entry the tests drive). Named apart
     * from [relative] so single-argument call sites stay
     * unambiguous. */
    fun relativeAtMillis(raw: String?, nowMs: Long): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val today = Instant.ofEpochMilli(nowMs)
            .atZone(ZoneId.systemDefault()).toLocalDate()
        return relativeAt(text, today, nowMs)
    }

    /** "just now" / "5 min ago" / "1 hour ago" / "Today" /
     * "Yesterday" / "3 days ago" / "2 weeks ago" /
     * "a month ago" / "3 months ago" / a short absolute date
     * for older (or future) ones; null when the raw text
     * carries no readable date. */
    fun relative(raw: String?, today: LocalDate = LocalDate.now()): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val nowMs = if (today == LocalDate.now()) {
            System.currentTimeMillis()
        } else {
            today.atTime(12, 0)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        return relativeAt(text, today, nowMs)
    }

    private fun relativeAt(
        text: String,
        today: LocalDate,
        nowMs: Long
    ): String? {
        if (hasTimeComponent(text)) {
            val instant = parseInstant(text) ?: return null
            val ageMs = nowMs - instant.toEpochMilli()
            if (ageMs < -60_000L) {
                // Future beyond a small clock-skew allowance.
                return instant.atZone(ZoneId.systemDefault())
                    .toLocalDate().format(ABSOLUTE)
            }
            val age = maxOf(0L, ageMs)
            val minutes = age / 60_000L
            val hours = age / 3_600_000L
            return when {
                age < 60_000L -> "just now"
                minutes < 60L ->
                    if (minutes == 1L) "1 min ago" else "$minutes min ago"
                hours < 24L ->
                    if (hours == 1L) "1 hour ago" else "$hours hours ago"
                else -> dayBands(
                    ChronoUnit.DAYS.between(
                        instant.atZone(ZoneId.systemDefault()).toLocalDate(),
                        today
                    ),
                    dateOnly = false,
                    date = instant.atZone(ZoneId.systemDefault()).toLocalDate()
                )
            }
        }
        val date = parse(text) ?: return null
        val days = ChronoUnit.DAYS.between(date, today)
        return when {
            days < 0L -> date.format(ABSOLUTE)
            days == 0L -> "Today"
            days == 1L -> "Yesterday"
            else -> dayBands(days, dateOnly = true, date = date)
        }
    }

    /** The shared day-and-older bands (item 53's wording):
     * days → weeks → months ("a month ago" for the first) →
     * a short absolute date beyond about a year. */
    private fun dayBands(days: Long, dateOnly: Boolean, date: LocalDate): String {
        return when {
            days < 0L -> date.format(ABSOLUTE)
            days == 0L -> if (dateOnly) "Today" else "1 day ago"
            days == 1L -> if (dateOnly) "Yesterday" else "1 day ago"
            days < 7L -> "$days days ago"
            days < 30L -> {
                val weeks = days / 7
                if (weeks == 1L) "1 week ago" else "$weeks weeks ago"
            }
            days < 365L -> {
                val months = days / 30
                if (months <= 1L) "a month ago" else "$months months ago"
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

    // ── v1.3.2 (items 71/78): de-gluing ──────────────────────
    // Some roads read a chapter row's WHOLE text as its name —
    // number, title and release date in one string ("Chapter
    // 156: Totally Awful 3 days ago", "101Chapter 101 –
    // Unexpected Clue5 years ago"). The name and the date are
    // separate fields everywhere in the app; when a name
    // ENDS in a date phrase, the phrase is split off here and
    // returned as a storable date text (relative phrases are
    // converted against [nowMs] into ISO instants, which the
    // renderer reads back exactly; absolute dates ride as
    // written). A name that is ONLY a date, or carries no
    // trailing date phrase, comes back untouched with a null
    // date — nothing is ever invented.

    private val RELATIVE_TAIL = Regex(
        "(?i)^(.*?)[\\s·•|–—-]*(about\\s+|around\\s+|~\\s*)?" +
            "(\\d+|a|an)\\s+" +
            "(second|minute|min|hour|day|week|month|year)s?" +
            "\\s+ago\\s*$"
    )
    private val JUST_NOW_TAIL = Regex("(?i)^(.*?)[\\s·•|–—-]*just\\s+now\\s*$")
    private val DAY_WORD_TAIL = Regex(
        "(?i)^(.*?)[\\s·•|–—-]*(today|yesterday)\\s*$"
    )
    private val ABSOLUTE_TAIL = Regex(
        "^(.*?)[\\s·•|–—-]*" +
            "(\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}|\\d{4}-\\d{2}-\\d{2})\\s*$"
    )

    /** (clean name, split-off date text) — the date is null
     * when the name carries no trailing date phrase. */
    fun splitGluedDate(
        name: String,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<String, String?> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return name to null
        JUST_NOW_TAIL.matchEntire(trimmed)?.let { match ->
            val head = match.groupValues[1].trim()
            if (head.isNotEmpty()) {
                return head to Instant.ofEpochMilli(nowMs).toString()
            }
        }
        RELATIVE_TAIL.matchEntire(trimmed)?.let { match ->
            val head = match.groupValues[1].trim()
            if (head.isNotEmpty()) {
                val amountText = match.groupValues[3]
                val amount = amountText.toLongOrNull() ?: 1L
                val unitMs = when (match.groupValues[4].lowercase()) {
                    "second" -> 1_000L
                    "minute", "min" -> 60_000L
                    "hour" -> 3_600_000L
                    "day" -> 86_400_000L
                    "week" -> 7 * 86_400_000L
                    "month" -> 30 * 86_400_000L
                    "year" -> 365 * 86_400_000L
                    else -> 0L
                }
                if (unitMs > 0L) {
                    return head to Instant
                        .ofEpochMilli(nowMs - amount * unitMs)
                        .toString()
                }
            }
        }
        DAY_WORD_TAIL.matchEntire(trimmed)?.let { match ->
            val head = match.groupValues[1].trim()
            if (head.isNotEmpty()) {
                val days = if (
                    match.groupValues[2].equals("yesterday", true)
                ) {
                    1L
                } else {
                    0L
                }
                return head to Instant
                    .ofEpochMilli(nowMs - days * 86_400_000L)
                    .toString()
            }
        }
        ABSOLUTE_TAIL.matchEntire(trimmed)?.let { match ->
            val head = match.groupValues[1].trim()
            val dateText = match.groupValues[2].trim()
            if (head.isNotEmpty() && parse(dateText) != null) {
                return head to dateText
            }
        }
        return name to null
    }
}
