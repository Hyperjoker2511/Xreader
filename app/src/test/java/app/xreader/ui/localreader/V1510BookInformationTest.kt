package app.xreader.ui.localreader

import app.xreader.fixtures.FakePreferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.10 (F2) gate: Book Information — the model HAS the
 * Moon fields on real inputs and says so honestly when a
 * value does not exist yet (no fake numbers). Includes the
 * Part 4 re-run defect regression: its merge credited
 * duration and end-percent but NEVER words-per-day; the
 * tree port credits words.
 */
class V1510BookInformationTest {

    private fun inputs(
        liveCounts: TextCounts? = TextCounts(chars = 500_000, words = 87_500, complete = true)
    ) = BookInfoInputs(
        title = "The Grieving Soul",
        contributors = listOf("Author A", "Translator B"),
        fileName = "grieving.epub",
        location = "/storage/emulated/0/Books",
        fileSizeBytes = 2_500_000,
        format = ReaderFormat.EPUB,
        currentChapterOneBased = 7,
        currentChapterTitle = "Chapter 7",
        liveCounts = liveCounts,
        bookProgression = 0.4f
    )

    // ── Model completeness ──────────────────────────────────

    @Test
    fun `model carries the shipped fields on fixture inputs`() {
        val model = buildBookInfoModel(
            inputs(),
            BookStats(
                totalReadingMillis = 3_600_000,
                totalWordsAdvanced = 87_500,
                textCounts = TextCounts(500_000, 87_500, true),
                starRating = 4
            )
        )
        assertEquals("The Grieving Soul", model.title)
        assertEquals(listOf("Author A", "Translator B"), model.contributors)
        assertEquals("grieving.epub", model.fileName)
        assertEquals("/storage/emulated/0/Books", model.location)
        assertEquals(ReaderFormat.EPUB, model.format)
        // EPUB pages are the estimate: 87 500 / 250 = 350.
        assertEquals(350, model.estimatedTotalPages)
        assertNull(model.totalPages)
        assertEquals(500_000L, model.charsInBook)
        assertEquals(87_500L, model.wordsInBook)
        assertEquals(7, model.currentChapterOneBased)
        assertEquals(3_600_000L, model.totalReadingMillis)
        assertEquals(4, model.starRating)
        assertTrue(model.countsComplete)
    }

    @Test
    fun `pdf total pages are real not estimated`() {
        val model = buildBookInfoModel(
            inputs().copy(
                format = ReaderFormat.PDF,
                totalPages = 212,
                currentPageOneBased = 40
            ),
            BookStats()
        )
        assertEquals(212, model.totalPages)
        assertNull(model.estimatedTotalPages)
        assertEquals(40, model.currentPageOneBased)
    }

    @Test
    fun `honest empties - no counts means no derived numbers`() {
        val model = buildBookInfoModel(inputs(liveCounts = null), BookStats())
        assertNull(model.estimatedTotalPages)
        assertNull(model.wordsInBook)
        assertFalse(model.countsComplete)
        assertNull(model.wordsRemainingInBook)
        assertNull(model.measuredWpm)
        assertNull(model.timeLeftBookMinutes)
    }

    // ── Measured WPM + time left ────────────────────────────

    @Test
    fun `measured wpm uses totals with floor and clamp`() {
        // 87 500 words in 60 minutes = 1458 wpm.
        assertEquals(1458, measuredWpm(3_600_000, 87_500))
        // Absurd speed clamps at the ceiling.
        assertEquals(1500, measuredWpm(60_000, 87_500))
        // 10 000 words in 30 minutes = 333 wpm.
        assertEquals(333, measuredWpm(1_800_000, 10_000))
        // Below the 15-second floor: not measurable.
        assertNull(measuredWpm(10_000, 5_000))
        assertNull(measuredWpm(60_000, 0))
    }

    @Test
    fun `words remaining and time left follow progression`() {
        val model = buildBookInfoModel(
            inputs(),
            BookStats(
                totalReadingMillis = 1_800_000,
                totalWordsAdvanced = 10_000
            )
        )
        // 87 500 total × (1 − 0.4) = 52 500 words left.
        assertEquals(52_500L, model.wordsRemainingInBook)
        assertEquals(333, model.measuredWpm)
        // 52 500 / 333 ≈ 158 minutes.
        assertEquals(158L, model.timeLeftBookMinutes)
    }

    @Test
    fun `estimated pages - 250 words per page`() {
        assertEquals(350, estimatedTotalPages(87_500L))
        assertNull(estimatedTotalPages(0L))
        assertNull(estimatedTotalPages(-5L))
        assertNull(estimatedTotalPages(null))
    }

    // ── Daily history merge (addendum defect regression) ────

    @Test
    fun `merge credits words per day - the re-run defect`() {
        val merged = mergeDailyHistory(
            existing = emptyList(),
            dateIso = "2026-11-06",
            deltaMillis = 600_000,
            wordsAdvanced = 2_000,
            endBookPercent = 12.5f
        )
        assertEquals(1, merged.size)
        assertEquals(2_000L, merged.single().wordsRead)
        val again = mergeDailyHistory(
            existing = merged,
            dateIso = "2026-11-06",
            deltaMillis = 300_000,
            wordsAdvanced = 1_500,
            endBookPercent = 15f
        )
        assertEquals(1, again.size)
        assertEquals(900_000L, again.single().durationMillis)
        assertEquals(3_500L, again.single().wordsRead)
        assertEquals(15f, again.single().endBookPercent)
    }

    @Test
    fun `merge keeps days newest first and caps at ninety`() {
        var history = emptyList<DailyReadingEntry>()
        for (day in 1..95) {
            history = mergeDailyHistory(
                history, "2026-03-%02d-x%02d".format(day % 28 + 1, day),
                60_000, 100, 1f
            )
        }
        assertEquals(90, history.size)
        assertEquals(
            history.map { it.dateIso },
            history.map { it.dateIso }.sortedDescending()
        )
    }

    @Test
    fun `daily codec round trips`() {
        val entries = listOf(
            DailyReadingEntry("2026-11-06", 600_000, 2_000, 12.5f),
            DailyReadingEntry("2026-11-05", 300_000, 900, 9.0f)
        )
        assertEquals(entries, decodeDailyHistory(encodeDailyHistory(entries)))
        assertEquals(emptyList<DailyReadingEntry>(), decodeDailyHistory("junk"))
        assertEquals(emptyList<DailyReadingEntry>(), decodeDailyHistory(null))
    }

    // ── Session accumulator ─────────────────────────────────

    @Test
    fun `accumulator credits forward deltas only`() {
        val acc = ReadingSessionAccumulator()
        acc.begin(0.10f)
        // +5% of 100 000 words.
        assertEquals(5_000L, acc.sample(0.15f, 100_000L))
        // Backwards: nothing credited; the marker follows.
        assertEquals(0L, acc.sample(0.12f, 100_000L))
        // Forward from the new marker: +8%.
        assertEquals(8_000L, acc.sample(0.20f, 100_000L))
        // Unknown totals credit nothing.
        assertEquals(0L, acc.sample(0.25f, null))
    }

    // ── Store round trip ────────────────────────────────────

    @Test
    fun `store accumulates sessions and persists rating and counts`() = runTest {
        val store = BookStatsStore(FakePreferencesDataStore())
        store.recordSession(1L, "2026-11-06", 1_800_000, 10_000, 40f)
        store.recordSession(1L, "2026-11-06", 1_800_000, 10_000, 45f)
        val stats = store.stats(1L).first()
        assertEquals(3_600_000L, stats.totalReadingMillis)
        assertEquals(20_000L, stats.totalWordsAdvanced)
        assertEquals(1, stats.dailyHistory.size)
        assertEquals(20_000L, stats.dailyHistory.single().wordsRead)
        assertEquals(333, stats.measuredWpm)

        store.saveStarRating(1L, 5)
        store.saveTextCounts(1L, TextCounts(500_000, 87_500, true))
        val reloaded = store.stats(1L).first()
        assertEquals(5, reloaded.starRating)
        assertEquals(TextCounts(500_000, 87_500, true), reloaded.textCounts)

        // A zero slice records nothing.
        store.recordSession(1L, "2026-11-07", 0, 0, 46f)
        assertEquals(1, store.stats(1L).first().dailyHistory.size)
        // Other books untouched.
        assertEquals(BookStats(), store.stats(2L).first())
    }

    @Test
    fun `count words splits on whitespace`() {
        assertEquals(0L, countWords(""))
        assertEquals(1L, countWords("word"))
        assertEquals(4L, countWords("a  b\nc\td"))
    }
}
