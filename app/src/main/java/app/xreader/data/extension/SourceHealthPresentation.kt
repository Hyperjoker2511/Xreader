package app.xreader.data.extension

import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * v1.0.5 (E2): health at a glance — how a source's last honest
 * verdict is PRESENTED: the dot on a Browse row and the "Last
 * checked" line on its Details page. Pure functions over the
 * persisted [SourceHealthRecord], so the mapping is pinned by
 * unit tests and can never drift between surfaces.
 *
 * The dot vocabulary is deliberately tiny: green = reading works,
 * amber = needs setup, red = broken, grey = never checked. A
 * source that works with limits still READS, so it is green —
 * amber is reserved for sources a reader would trip over.
 */
enum class HealthDot { GREEN, AMBER, RED, GREY }

fun SourceHealthState.healthDot(): HealthDot = when (this) {
    SourceHealthState.WORKING,
    SourceHealthState.WORKING_WITH_LIMITS -> HealthDot.GREEN
    SourceHealthState.NEEDS_SETUP -> HealthDot.AMBER
    SourceHealthState.BROKEN -> HealthDot.RED
    SourceHealthState.UNKNOWN -> HealthDot.GREY
}

/** A source with no record at all was never checked: grey. */
fun SourceHealthRecord?.healthDot(): HealthDot =
    this?.state?.healthDot() ?: HealthDot.GREY

/**
 * "Last checked …" in the reader's terms: relative while the
 * memory is fresh, an absolute date once it is history. A
 * non-positive timestamp means the source was never checked.
 * Day boundaries are calendar days in the device's timezone, not
 * 24-hour spans, so "yesterday evening" reads as yesterday the
 * next morning.
 */
fun lastCheckedLine(checkedAtMillis: Long, nowMillis: Long): String {
    if (checkedAtMillis <= 0L) return "Not checked yet"
    val elapsed = nowMillis - checkedAtMillis
    if (elapsed < 60_000L) return "Last checked just now"
    val days = calendarDaysBetween(checkedAtMillis, nowMillis)
    return when {
        days <= 0 -> {
            val time = DateFormat.getTimeInstance(DateFormat.SHORT)
                .format(Date(checkedAtMillis))
            "Last checked today at $time"
        }
        days == 1 -> "Last checked yesterday"
        days < 7 -> "Last checked $days days ago"
        else -> "Last checked on " + DateFormat.getDateInstance(
            DateFormat.MEDIUM
        ).format(Date(checkedAtMillis))
    }
}

/** Whole calendar days from [fromMillis]'s day to [toMillis]'s
 * day (never negative). Noon-anchored so a DST shift of an hour
 * can never round a day away. */
internal fun calendarDaysBetween(fromMillis: Long, toMillis: Long): Int {
    fun dayStart(millis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
    val diff = dayStart(toMillis) - dayStart(fromMillis)
    if (diff <= 0L) return 0
    return ((diff + 43_200_000L) / 86_400_000L).toInt()
}
