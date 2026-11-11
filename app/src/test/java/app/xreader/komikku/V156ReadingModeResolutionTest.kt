package app.xreader.komikku

import app.xreader.komikku.setting.ChapterShape
import app.xreader.komikku.setting.InMemoryKomikkuStore
import app.xreader.komikku.setting.PageDimension
import app.xreader.komikku.setting.ReaderPreferences
import app.xreader.komikku.setting.ReadingMode
import app.xreader.komikku.setting.ReadingModeResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.6 (§5.6 + Gemini amendment): explicit-vs-default
 * reading-mode resolution. The verdict is computed over the
 * WHOLE page set (order ignored, tall = height >= 2x width,
 * WEBTOON at >= half of dimension-known pages, no verdict
 * under 3 known pages); an explicit series choice always
 * wins, an explicit global choice wins over shape, and only
 * with NOTHING explicitly chosen may the shape decide.
 */
class V156ReadingModeResolutionTest {

    private val rtl = ReadingMode.RIGHT_TO_LEFT.flagValue
    private val ltr = ReadingMode.LEFT_TO_RIGHT.flagValue

    private fun ordinary(count: Int): List<PageDimension?> =
        (1..count).map { PageDimension(800, 1200) }

    private fun tall(count: Int): List<PageDimension?> =
        (1..count).map { PageDimension(800, 2400) }

    /** The ViewModel's wiring, composed from the pure parts
     * exactly as KomikkuReaderViewModel.resolveReadingMode +
     * probeChapterShape compose them. */
    private fun resolve(
        seriesFlag: Int = 0,
        globalFlag: Int = rtl,
        globalExplicit: Boolean = false,
        dimensions: List<PageDimension?> = emptyList()
    ): ReadingMode = ReadingModeResolution.resolve(
        seriesFlag = seriesFlag,
        globalFlag = globalFlag,
        globalExplicit = globalExplicit,
        shapeWebtoon = ChapterShape.isWebtoonShaped(dimensions)
    )

    @Test
    fun freshDefaultsPlusTallChapterResolvesWebtoon() {
        assertEquals(
            ReadingMode.WEBTOON,
            resolve(dimensions = tall(6))
        )
    }

    @Test
    fun explicitSeriesRtlAlwaysWins() {
        assertEquals(
            ReadingMode.RIGHT_TO_LEFT,
            resolve(seriesFlag = rtl, dimensions = tall(6))
        )
    }

    @Test
    fun explicitGlobalLtrWithUnsetSeriesResolvesLtr() {
        assertEquals(
            ReadingMode.LEFT_TO_RIGHT,
            resolve(
                globalFlag = ltr,
                globalExplicit = true,
                dimensions = tall(6)
            )
        )
    }

    @Test
    fun ordinaryChapterPlusFreshDefaultsStaysPager() {
        val mode = resolve(dimensions = ordinary(6))
        assertEquals(ReadingMode.RIGHT_TO_LEFT, mode)
        assertEquals(ReadingMode.ViewerType.Pager, mode.type)
    }

    @Test
    fun misfireFixtureOneTallCreditsPageKeepsPager() {
        // An ordinary chapter + ONE tall credits page (1 of
        // 10) can never flip the chapter to webtoon.
        val dims = ordinary(9) + PageDimension(800, 3200)
        assertEquals(false, ChapterShape.isWebtoonShaped(dims))
        assertEquals(
            ReadingMode.RIGHT_TO_LEFT,
            resolve(dimensions = dims)
        )
    }

    @Test
    fun misfireFixtureShortOpenersPlusDominantTallRemainderIsWebtoon() {
        // Page ORDER is ignored: short opening pages do not
        // hide a dominant tall remainder.
        val dims = ordinary(2) + tall(8)
        assertEquals(true, ChapterShape.isWebtoonShaped(dims))
        assertEquals(
            ReadingMode.WEBTOON,
            resolve(dimensions = dims)
        )
    }

    @Test
    fun fewerThanThreeKnownPagesTakesNoVerdict() {
        assertNull(ChapterShape.isWebtoonShaped(tall(2)))
        assertNull(
            ChapterShape.isWebtoonShaped(
                listOf(PageDimension(800, 2400), null, null, null)
            )
        )
        // No verdict -> the default chain applies (shipped
        // RTL pager), NOT a guessed webtoon.
        assertEquals(
            ReadingMode.RIGHT_TO_LEFT,
            resolve(dimensions = tall(2))
        )
    }

    @Test
    fun exactlyHalfTallIsWebtoonAndTallMeansTwiceAsTall() {
        // height == 2x width counts as tall; 2 of 4 is half.
        val dims = listOf(
            PageDimension(800, 1600),
            PageDimension(800, 1600),
            PageDimension(800, 1200),
            PageDimension(800, 1200)
        )
        assertEquals(true, ChapterShape.isWebtoonShaped(dims))
        // Just under twice as tall is not tall.
        val under = listOf(
            PageDimension(800, 1599),
            PageDimension(800, 1599),
            PageDimension(800, 1599),
            PageDimension(800, 1200)
        )
        assertEquals(false, ChapterShape.isWebtoonShaped(under))
    }

    @Test
    fun explicitGlobalWebtoonChoiceIsHonouredForOrdinaryChapter() {
        assertEquals(
            ReadingMode.WEBTOON,
            resolve(
                globalFlag = ReadingMode.WEBTOON.flagValue,
                globalExplicit = true,
                dimensions = ordinary(6)
            )
        )
    }

    // ── the explicit-set marker itself ────────────────────

    @Test
    fun globalModeMarkerStartsUnsetAndStampsOnWrite() {
        val store = InMemoryKomikkuStore()
        val prefs = ReaderPreferences(store)
        // Never touched: shipped default value, marker unset
        // — the two must be distinguishable (§5.6).
        assertEquals(rtl, prefs.defaultReadingMode().get())
        assertFalse(prefs.defaultReadingModeExplicit().get())
        prefs.defaultReadingMode().set(ltr)
        assertEquals(ltr, prefs.defaultReadingMode().get())
        assertTrue(prefs.defaultReadingModeExplicit().get())
    }

    @Test
    fun storedGlobalMeaningIsNotChangedRetroactively() {
        // A store that already carries a mode value from an
        // older build (written before the marker existed)
        // reads back the same value, with the marker unset —
        // pre-existing installs are treated as "never
        // explicitly chosen" rather than having a meaning
        // reassigned to their stored value.
        val store = InMemoryKomikkuStore(
            mapOf("pref_default_reading_mode_key" to ltr)
        )
        val prefs = ReaderPreferences(store)
        assertEquals(ltr, prefs.defaultReadingMode().get())
        assertFalse(prefs.defaultReadingModeExplicit().get())
    }
}
