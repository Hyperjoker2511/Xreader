package app.xreader.data.prepare

import app.xreader.data.db.ReadingProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.7 Patch 3 (Item 6): the place migration's ladders, rung
 * by rung (Items 3a/3b/3d), plus the quote search marks
 * re-anchor through (Item 3e). Pure — the inputs are plain
 * values, exactly as EpubPlaceMigration takes them.
 */
class Patch3MigrationTest {

    private val parserHrefs = listOf(
        "OEBPS/Text/first.xhtml",
        "OEBPS/Text/second.xhtml",
        "OEBPS/Text/appendix.xhtml"
    )
    private val sessionHrefs = listOf(
        "OEBPS/Text/first.xhtml",
        "OEBPS/Text/blank.xhtml",
        "OEBPS/Text/second.xhtml"
    )
    private val parserTitles = listOf("First", "Second", "Appendix")
    private val parserLengths = listOf(100, 200, 50)

    private fun inputs(
        saved: ReadingProgress?,
        oldSectionMap: Map<Int, Long> = emptyMap(),
        oldChapterIdsInOrder: List<Long> = emptyList(),
        oldChapterLengths: Map<Long, Int> = emptyMap()
    ) = EpubPlaceMigration.Inputs(
        saved = saved,
        oldSectionMap = oldSectionMap,
        sessionHrefs = sessionHrefs,
        parserHrefs = parserHrefs,
        parserTitles = parserTitles,
        parserLengths = parserLengths,
        oldChapterIdsInOrder = oldChapterIdsInOrder,
        oldChapterLengths = oldChapterLengths
    )

    private fun locatorRow(
        chapterIndex: Int = 0,
        locatorJson: String? = null,
        scrollFraction: Float = 0f,
        anchorText: String? = null
    ) = ReadingProgress(
        bookId = 1,
        chapterId = 0,
        chapterIndex = chapterIndex,
        charOffset = 0,
        scrollFraction = scrollFraction,
        updatedAt = 0,
        locatorJson = locatorJson,
        anchorText = anchorText
    )

    // ── Item 3a/3b: locator rows ────────────────────────────

    @Test
    fun locatorHrefRungWins() {
        val saved = locatorRow(
            chapterIndex = 0,
            locatorJson = Patch3EpubFixtures.locatorJson(
                "OEBPS/Text/second.xhtml", 0.25f
            )
        )
        val place = EpubPlaceMigration.resolvePlace(inputs(saved))
        assertEquals(1, place.chapterIndex)
        assertEquals(50, place.charOffset) // 0.25 × 200
        assertTrue(place.resolved)
    }

    @Test
    fun sessionBridgeUsesTheIndexAsASessionKey() {
        // No locator at all: chapterIndex 2 is SESSION space
        // ("second"), which is parser index 1 — never 2.
        val saved = locatorRow(chapterIndex = 2, scrollFraction = 0.5f)
        val place = EpubPlaceMigration.resolvePlace(inputs(saved))
        assertEquals(1, place.chapterIndex)
        assertEquals(100, place.charOffset) // 0.5 × 200
        assertTrue(place.resolved)
    }

    @Test
    fun anchorTitleRungAppliesTheScrollFraction() {
        val saved = locatorRow(
            chapterIndex = 99, // no session entry either
            scrollFraction = 0.1f,
            anchorText = "Appendix"
        )
        val place = EpubPlaceMigration.resolvePlace(inputs(saved))
        assertEquals(2, place.chapterIndex)
        assertEquals(5, place.charOffset) // 0.1 × 50
        assertTrue(place.resolved)
    }

    @Test
    fun nothingMatchingIsAnHonestBookStart() {
        val saved = locatorRow(
            chapterIndex = 99,
            anchorText = "No Such Chapter"
        )
        val place = EpubPlaceMigration.resolvePlace(inputs(saved))
        assertEquals(0, place.chapterIndex)
        assertEquals(0, place.charOffset)
        assertFalse(place.resolved)
    }

    @Test
    fun noSavedRowIsBookStart() {
        val place = EpubPlaceMigration.resolvePlace(inputs(null))
        assertEquals(0, place.chapterIndex)
        assertFalse(place.resolved)
    }

    @Test
    fun progressionBeatsScrollFraction() {
        val saved = locatorRow(
            locatorJson = Patch3EpubFixtures.locatorJson(
                "OEBPS/Text/first.xhtml", 0.9f
            ),
            scrollFraction = 0.1f
        )
        val place = EpubPlaceMigration.resolvePlace(inputs(saved))
        assertEquals(90, place.charOffset) // 0.9 × 100, not 0.1
    }

    // ── Item 3d: core rows across a re-prepare ──────────────

    private fun coreRow(
        chapterId: Long,
        chapterIndex: Int,
        charOffset: Int,
        locatorJson: String? = null
    ) = ReadingProgress(
        bookId = 1,
        chapterId = chapterId,
        chapterIndex = chapterIndex,
        charOffset = charOffset,
        updatedAt = 0,
        locatorJson = locatorJson
    )

    @Test
    fun coreRowFollowsTheInvertedSectionMap() {
        // Old landing: session 2 → chapter id 77 ("second" —
        // session space counts the blank section the parser
        // space skips). The row sits at half of the old
        // chapter (old length 400); the new parse's chapter
        // is 200 long.
        val saved = coreRow(chapterId = 77, chapterIndex = 1, charOffset = 200)
        val place = EpubPlaceMigration.resolvePlace(
            inputs(
                saved,
                oldSectionMap = mapOf(0 to 70L, 2 to 77L),
                oldChapterLengths = mapOf(77L to 400)
            )
        )
        assertEquals(1, place.chapterIndex)
        assertEquals(100, place.charOffset) // half of 200
        assertTrue(place.resolved)
    }

    @Test
    fun coreRowFallsToTheDormantLocator() {
        val saved = coreRow(
            chapterId = 55, chapterIndex = 0, charOffset = 10,
            locatorJson = Patch3EpubFixtures.locatorJson(
                "OEBPS/Text/appendix.xhtml", 0.5f
            )
        )
        val place = EpubPlaceMigration.resolvePlace(
            inputs(saved, oldSectionMap = mapOf(0 to 70L))
        )
        assertEquals(2, place.chapterIndex)
        assertEquals(25, place.charOffset) // 0.5 × 50
    }

    @Test
    fun coreRowFallsToTheSameIndexScaled() {
        val saved = coreRow(chapterId = 88, chapterIndex = 1, charOffset = 300)
        val place = EpubPlaceMigration.resolvePlace(
            inputs(
                saved,
                oldChapterIdsInOrder = listOf(80L, 88L, 90L),
                oldChapterLengths = mapOf(88L to 600)
            )
        )
        assertEquals(1, place.chapterIndex)
        assertEquals(100, place.charOffset) // 300/600 × 200
    }

    @Test
    fun coreRowOutOfRangeIsBookStart() {
        val saved = coreRow(chapterId = 88, chapterIndex = 9, charOffset = 10)
        val place = EpubPlaceMigration.resolvePlace(inputs(saved))
        assertEquals(0, place.chapterIndex)
        assertFalse(place.resolved)
    }

    @Test
    fun migrationIsIdempotentAcrossASecondPass() {
        // First pass: the locator row lands at parser 1,
        // offset 100 (0.5 × 200).
        val first = EpubPlaceMigration.resolvePlace(
            inputs(locatorRow(chapterIndex = 2, scrollFraction = 0.5f))
        )
        assertEquals(1, first.chapterIndex)
        assertEquals(100, first.charOffset)
        // Second pass over the MIGRATED row (now core-shaped,
        // chapter id 502 from the first landing, the first
        // record's map + old lengths supplied): the same
        // place comes back — a re-prepare never walks the
        // place backwards.
        val migrated = coreRow(
            chapterId = 502, chapterIndex = 1, charOffset = first.charOffset
        )
        val second = EpubPlaceMigration.resolvePlace(
            inputs(
                migrated,
                oldSectionMap = mapOf(2 to 502L),
                oldChapterLengths = mapOf(502L to 200)
            )
        )
        assertEquals(first.chapterIndex, second.chapterIndex)
        assertEquals(first.charOffset, second.charOffset)
        assertTrue(second.resolved)
    }

    @Test
    fun coreShapeIsTheChapterId() {
        assertFalse(EpubPlaceMigration.isCoreShaped(null))
        assertFalse(EpubPlaceMigration.isCoreShaped(locatorRow()))
        assertTrue(
            EpubPlaceMigration.isCoreShaped(
                coreRow(chapterId = 5, chapterIndex = 0, charOffset = 0)
            )
        )
    }

    // ── Item 3e: quote search ───────────────────────────────

    @Test
    fun findQuoteExact() {
        assertEquals(
            6,
            EpubPlaceMigration.findQuote("hello brave world", "brave")
        )
    }

    @Test
    fun findQuoteAcrossWhitespaceDifferences() {
        // The old road's quote carries single spaces; the
        // converted text has a line break + double space. The
        // returned offset is in the ORIGINAL text.
        val text = "the ferry\n  lantern sways"
        assertEquals(
            4,
            EpubPlaceMigration.findQuote(text, "ferry lantern")
        )
    }

    @Test
    fun findQuoteMissesAreNull() {
        assertNull(EpubPlaceMigration.findQuote("some text", "absent"))
        assertNull(EpubPlaceMigration.findQuote("some text", "  "))
        assertNull(EpubPlaceMigration.findQuote("", "anything"))
    }
}
