package app.xreader.data.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * v1.0.5 (E2): the health dot mapping and the "Last checked"
 * line are pure presentation decisions over the persisted
 * verdict — pinned so the Browse dot and the Details line can
 * never disagree about what a verdict means.
 */
class SourceHealthPresentationTest {

    @Test
    fun workingStates_areGreen() {
        assertEquals(HealthDot.GREEN, SourceHealthState.WORKING.healthDot())
        assertEquals(
            HealthDot.GREEN,
            SourceHealthState.WORKING_WITH_LIMITS.healthDot()
        )
    }

    @Test
    fun needsSetup_isAmber_broken_isRed_unknown_isGrey() {
        assertEquals(HealthDot.AMBER, SourceHealthState.NEEDS_SETUP.healthDot())
        assertEquals(HealthDot.RED, SourceHealthState.BROKEN.healthDot())
        assertEquals(HealthDot.GREY, SourceHealthState.UNKNOWN.healthDot())
    }

    @Test
    fun missingRecord_isGrey() {
        val none: SourceHealthRecord? = null
        assertEquals(HealthDot.GREY, none.healthDot())
        assertEquals(
            HealthDot.GREEN,
            SourceHealthRecord(state = SourceHealthState.WORKING).healthDot()
        )
    }

    private fun at(
        dayOffset: Int,
        hour: Int,
        minute: Int = 0
    ): Long {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.OCTOBER, 29, hour, minute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, dayOffset)
        return cal.timeInMillis
    }

    @Test
    fun neverChecked_saysSo() {
        assertEquals("Not checked yet", lastCheckedLine(0, at(0, 12)))
        assertEquals("Not checked yet", lastCheckedLine(-5, at(0, 12)))
    }

    @Test
    fun justNow_withinAMinute() {
        val now = at(0, 12)
        assertEquals("Last checked just now", lastCheckedLine(now - 30_000, now))
    }

    @Test
    fun sameDay_showsTodayWithTime() {
        val line = lastCheckedLine(at(0, 9), at(0, 12))
        assertTrue(line, line.startsWith("Last checked today at "))
    }

    @Test
    fun yesterday_evenWhenLessThan24Hours() {
        // 23:00 yesterday vs 01:00 today: two hours apart, but a
        // different calendar day — it must read "yesterday".
        val line = lastCheckedLine(at(-1, 23), at(0, 1))
        assertEquals("Last checked yesterday", line)
    }

    @Test
    fun aFewDaysAgo_countsCalendarDays() {
        assertEquals(
            "Last checked 3 days ago",
            lastCheckedLine(at(-3, 10), at(0, 10))
        )
        assertEquals(
            "Last checked 6 days ago",
            lastCheckedLine(at(-6, 10), at(0, 10))
        )
    }

    @Test
    fun aWeekOrMore_showsAnAbsoluteDate() {
        val line = lastCheckedLine(at(-30, 10), at(0, 10))
        assertTrue(line, line.startsWith("Last checked on "))
        assertTrue(line, line.contains("2026"))
    }
}
