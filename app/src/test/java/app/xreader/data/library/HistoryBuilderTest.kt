package app.xreader.data.library

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.1 hotfix: history day grouping. Asia/Kolkata is UTC+5:30, so
 * an entry just after local midnight belongs to the NEW local day
 * even though it is still the previous day in UTC — the boundary
 * case this builder exists to get right.
 */
class HistoryBuilderTest {

    private val ist: ZoneId = ZoneId.of("Asia/Kolkata")
    private val today: LocalDate = LocalDate.of(2026, 10, 28)

    private fun millis(
        day: Int,
        hour: Int,
        minute: Int = 0,
        month: Int = 10,
        year: Int = 2026
    ): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ist)
            .toInstant().toEpochMilli()

    private fun source(
        bookId: String,
        updatedAtMillis: Long,
        title: String = "Book $bookId",
        chapterIndex: Int = 0
    ) = HistoryBuilder.HistorySource(
        bookId = bookId,
        title = title,
        coverPath = null,
        chapterTitle = "Chapter ${chapterIndex + 1}",
        chapterIndex = chapterIndex,
        fraction = 0.5f,
        updatedAtMillis = updatedAtMillis
    )

    private fun build(
        sources: List<HistoryBuilder.HistorySource>,
        hiddenBookIds: Set<String> = emptySet(),
        clearedBeforeMillis: Long = 0L
    ) = HistoryBuilder.build(
        sources = sources,
        hiddenBookIds = hiddenBookIds,
        clearedBeforeMillis = clearedBeforeMillis,
        zone = ist,
        today = today
    )

    @Test
    fun `entries either side of local midnight land in different day groups`() {
        val groups = build(
            listOf(
                source("before", millis(day = 27, hour = 23, minute = 30)),
                source("after", millis(day = 28, hour = 0, minute = 30))
            )
        )
        assertEquals(2, groups.size)
        assertEquals("Today", groups[0].label)
        assertEquals(listOf("after"), groups[0].entries.map { it.bookId })
        assertEquals("Yesterday", groups[1].label)
        assertEquals(listOf("before"), groups[1].entries.map { it.bookId })
    }

    @Test
    fun `older days are labelled with their ISO date`() {
        val groups = build(
            listOf(source("old", millis(day = 25, hour = 12)))
        )
        assertEquals(1, groups.size)
        assertEquals("2026-10-25", groups[0].label)
    }

    @Test
    fun `groups and entries are newest first`() {
        val groups = build(
            listOf(
                source("old-day", millis(day = 25, hour = 23)),
                source("today-early", millis(day = 28, hour = 9)),
                source("yesterday", millis(day = 27, hour = 12)),
                source("today-late", millis(day = 28, hour = 21))
            )
        )
        assertEquals(listOf("Today", "Yesterday", "2026-10-25"), groups.map { it.label })
        assertEquals(
            listOf("today-late", "today-early"),
            groups[0].entries.map { it.bookId }
        )
    }

    @Test
    fun `cleared watermark excludes entries at or before it`() {
        val watermark = millis(day = 28, hour = 10)
        val groups = build(
            sources = listOf(
                source("before", millis(day = 28, hour = 9)),
                source("at", watermark),
                source("after", millis(day = 28, hour = 11))
            ),
            clearedBeforeMillis = watermark
        )
        assertEquals(1, groups.size)
        assertEquals(listOf("after"), groups[0].entries.map { it.bookId })
    }

    @Test
    fun `hidden books are excluded`() {
        val groups = build(
            sources = listOf(
                source("kept", millis(day = 28, hour = 9)),
                source("hidden", millis(day = 28, hour = 10))
            ),
            hiddenBookIds = setOf("hidden")
        )
        assertEquals(1, groups.size)
        assertEquals(listOf("kept"), groups[0].entries.map { it.bookId })
    }

    @Test
    fun `empty and fully excluded inputs give no groups`() {
        assertTrue(build(emptyList()).isEmpty())
        assertTrue(
            build(
                sources = listOf(source("hidden", millis(day = 28, hour = 9))),
                hiddenBookIds = setOf("hidden")
            ).isEmpty()
        )
    }

    @Test
    fun `resume route matches the shell reader route`() {
        assertEquals("reader/42/3", HistoryBuilder.resumeRoute("42", 3))
        assertEquals("reader/7/0", HistoryBuilder.resumeRoute("7", 0))
        assertEquals("reader/7/0", HistoryBuilder.resumeRoute("7", -2))
    }
}
