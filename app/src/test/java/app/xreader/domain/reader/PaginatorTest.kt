package app.xreader.domain.reader

import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaginatorTest {

    /**
     * The test measuring model: [LineWrap] at [widthUnits] per line —
     * a stand-in for Compose's real glyph measurement, exercising the
     * exact packing code production uses.
     */
    private fun measure(
        chapterText: ChapterText,
        widthUnits: Int,
        lineHeightPx: Float = 10f,
        imageHeightPx: Float = 50f
    ): List<MeasuredBlock> {
        return chapterText.content.blocks.mapIndexed { index, block ->
            val range = chapterText.rangeOf(index)!!
            when (block) {
                is ChapterBlock.Image -> MeasuredBlock(
                    blockIndex = index, blockStart = range.start, blockEnd = range.end,
                    fixedHeightPx = imageHeightPx
                )
                is ChapterBlock.Rule -> MeasuredBlock(
                    blockIndex = index, blockStart = range.start, blockEnd = range.end,
                    fixedHeightPx = 12f
                )
                else -> {
                    val blockText = chapterText.text.substring(range.start, range.end)
                    val lines = LineWrap.breakLines(blockText, widthUnits)
                    MeasuredBlock(
                        blockIndex = index, blockStart = range.start, blockEnd = range.end,
                        lineStarts = lines.map { (s, _) -> range.start + s }
                            .ifEmpty { listOf(range.start) },
                        lineHeightPx = lineHeightPx,
                        spaceAfterPx = 6f
                    )
                }
            }
        }
    }

    private fun proseChapter(paragraphs: List<String>): ChapterText =
        ChapterText(
            ChapterContent(
                paragraphs.map { ChapterBlock.Paragraph.plain(it) }
            )
        )

    private fun assertNoLostOrDuplicatedText(text: ChapterText, pages: List<Page>) {
        val covered = BooleanArray(text.length)
        var previousEnd = -1
        for (page in pages) {
            for (slice in page.slices) {
                if (slice.fixed) continue
                assertTrue("slices out of order", slice.startChar >= previousEnd - 0)
                previousEnd = maxOf(previousEnd, slice.endChar)
                for (i in slice.startChar until slice.endChar) {
                    assertTrue("char $i covered twice", !covered[i])
                    covered[i] = true
                }
            }
        }
        for ((i, c) in text.text.withIndex()) {
            if (c != ' ' && c != '\n') {
                assertTrue("char $i ('$c') lost in pagination", covered[i])
            }
        }
        // Page starts strictly increase (monotonic progress).
        for (i in 1 until pages.size) {
            assertTrue(
                "page starts must increase",
                pages[i].startChar > pages[i - 1].startChar
            )
        }
    }

    @Test
    fun paginationCoversEveryCharacter() {
        val chapter = proseChapter(
            listOf(
                "The ancient mist clung to the jagged peaks like a shroud of damp silk.",
                "Within the abyssal ravine, ten thousand rusted blades hummed in unison.",
                "Su Yi stood at the precipice, his black robes fluttering in the gale."
            )
        )
        val pages = Paginator(64f).paginate(measure(chapter, 18))
        assertTrue("expected multiple pages", pages.size >= 3)
        assertNoLostOrDuplicatedText(chapter, pages)
        assertEquals(0, pages.first().startChar)
    }

    @Test
    fun cjkPaginationIsExact() {
        val cjk = "天地玄黄宇宙洪荒日月盈昃辰宿列张律吕调阳云腾致雨露结为霜金生丽水玉出昆冈"
        val chapter = proseChapter(listOf(cjk, "剑号巨阙珠称夜光果珍李柰菜重芥姜海咸河淡鳞潜羽翔"))
        val pages = Paginator(40f).paginate(measure(chapter, 8))
        assertTrue(pages.size >= 4)
        assertNoLostOrDuplicatedText(chapter, pages)
        // CJK has no skippable spaces: prose slices concatenate exactly,
        // modulo the block separator that belongs to no block.
        val joined = pages.flatMap { it.slices }.filter { !it.fixed }
            .joinToString("") { chapter.text.substring(it.startChar, it.endChar) }
        assertEquals(chapter.text.replace("\n\n", ""), joined)
    }

    @Test
    fun locatorRoundTripAcrossTypographyChange() {
        val chapter = proseChapter(
            (1..6).map { "Paragraph $it holds a handful of plain words to fill the page nicely." }
        )
        val pagesA = Paginator(64f).paginate(measure(chapter, 18))
        val pagesB = Paginator(64f).paginate(measure(chapter, 11)) // "bigger font"
        assertTrue(pagesB.size > pagesA.size)
        assertNoLostOrDuplicatedText(chapter, pagesA)
        assertNoLostOrDuplicatedText(chapter, pagesB)
        // Every page start in A restores into the page of B that shows it.
        for (page in pagesA) {
            val restored = PageLocator.pageIndexForOffset(pagesB, page.startChar)
            assertTrue(
                "offset ${page.startChar} restored outside its page",
                pagesB[restored].startChar <= page.startChar &&
                    (restored == pagesB.lastIndex ||
                        pagesB[restored + 1].startChar > page.startChar)
            )
        }
        // offsetForPage → pageIndexForOffset is the identity.
        for ((index, page) in pagesB.withIndex()) {
            assertEquals(
                index,
                PageLocator.pageIndexForOffset(
                    pagesB, PageLocator.offsetForPage(pagesB, index)
                )
            )
            assertEquals(page.startChar, PageLocator.offsetForPage(pagesB, index))
        }
    }

    @Test
    fun oversizeLineAndImageGetTheirOwnPages() {
        val chapter = ChapterText(
            ChapterContent(
                listOf(
                    ChapterBlock.Paragraph.plain("short"),
                    ChapterBlock.Image("p1.png"),
                    ChapterBlock.Paragraph.plain("after")
                )
            )
        )
        // Page height 30 < image height 50: the image still lands.
        val pages = Paginator(30f).paginate(measure(chapter, 18, imageHeightPx = 50f))
        val imagePage = pages.first { page -> page.slices.any { it.fixed } }
        assertEquals(1, imagePage.slices.size)
        assertNoLostOrDuplicatedText(chapter, pages)
    }

    @Test
    fun emptyChapterIsOneEmptyPage() {
        val chapter = ChapterText(ChapterContent(emptyList()))
        val pages = Paginator(100f).paginate(measure(chapter, 18))
        assertEquals(1, pages.size)
        assertEquals(0, pages[0].startChar)
    }

    @Test
    fun chapterAndBookFractionsAreMonotonic() {
        var last = -1f
        for (offset in 0..100 step 7) {
            val f = PageLocator.chapterFraction(offset, 100)
            assertTrue(f >= last)
            last = f
        }
        assertEquals(1f, PageLocator.chapterFraction(100, 100))
        assertEquals(0f, PageLocator.chapterFraction(5, 0))
        // Book fraction: chapter 2 of 4, halfway through = 0.625.
        assertEquals(0.625f, PageLocator.bookFraction(2, 0.5f, 4), 0.0001f)
        assertEquals(0f, PageLocator.bookFraction(0, 0f, 0))
    }
}
