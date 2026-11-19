package app.xreader.domain.reader

import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.7 Patch 2 (item 2d): the ChapterLayoutService + its
 * bounded cache, exercised through a `LineWrap`-backed
 * [ChapterMeasurer] — the same measuring model PaginatorTest
 * uses, so the golden equivalence pins the service + measurer
 * path (block order, fixed heights, packing), not merely the
 * delegation to Paginator.
 */
class ChapterLayoutServiceTest {

    /** The LineWrap measuring model as a ChapterMeasurer
     * (mirrors PaginatorTest's measure(): prose wrapped at
     * [widthUnits] per line, images fixed 50 px, rules 12 px,
     * line height 10 px, paragraph space-after 6 px). */
    private class LineWrapMeasurer(
        private val widthUnits: Int
    ) : ChapterMeasurer {
        override fun measure(
            chapterText: ChapterText,
            blockIndex: Int,
            config: ReaderConfig
        ): MeasuredBlock {
            val block = chapterText.content.blocks[blockIndex]
            val range = chapterText.rangeOf(blockIndex)!!
            return when (block) {
                is ChapterBlock.Image -> MeasuredBlock(
                    blockIndex = blockIndex, blockStart = range.start,
                    blockEnd = range.end, fixedHeightPx = 50f
                )
                is ChapterBlock.Rule -> MeasuredBlock(
                    blockIndex = blockIndex, blockStart = range.start,
                    blockEnd = range.end, fixedHeightPx = 12f
                )
                else -> {
                    val blockText = chapterText.text.substring(range.start, range.end)
                    val lines = LineWrap.breakLines(blockText, widthUnits)
                    MeasuredBlock(
                        blockIndex = blockIndex, blockStart = range.start,
                        blockEnd = range.end,
                        lineStarts = lines.map { (s, _) -> range.start + s }
                            .ifEmpty { listOf(range.start) },
                        lineHeightPx = 10f,
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

    private val fixtureParagraphs = listOf(
        "The ancient mist clung to the jagged peaks like a shroud of damp silk.",
        "Within the abyssal ravine, ten thousand rusted blades hummed in unison.",
        "Su Yi stood at the precipice, his black robes fluttering in the gale."
    )

    @Test
    fun servicePagesMatchPaginatorGolden() {
        val chapter = proseChapter(fixtureParagraphs)
        val measurer = LineWrapMeasurer(18)
        val service = ChapterLayoutService()
        val layout = service.layout(
            chapterText = chapter,
            config = ReaderConfig(),
            pageHeightPx = 64f,
            fingerprint = listOf("fp"),
            measurer = measurer
        )
        // The direct road PagedReader used to walk: measure the
        // same blocks through the same measurer, pack with
        // Paginator. The service's orchestration must land on
        // exactly these pages.
        val blocks = chapter.content.blocks.indices.map {
            measurer.measure(chapter, it, ReaderConfig())
        }
        val expected = Paginator(64f).paginate(blocks)
        assertEquals(expected, layout.pages)
        assertEquals(blocks, layout.measuredBlocks)
        assertEquals(listOf("fp"), layout.fingerprint)
        assertTrue("expected multiple pages", layout.pages.size >= 3)
        assertEquals(0, layout.pages.first().startChar)
    }

    @Test
    fun fingerprintChangeInvalidates() {
        val cache = ChapterLayoutCache()
        val chapter = proseChapter(fixtureParagraphs)
        val layout = ChapterLayoutService().layout(
            chapter, ReaderConfig(), 64f, listOf("fontA"), LineWrapMeasurer(18)
        )
        val keyA = ChapterLayoutCache.Key(
            chapterId = 7L,
            contentHash = ChapterLayoutCache.contentHash(chapter),
            fingerprint = listOf("fontA")
        )
        cache.put(keyA, layout)
        assertNotNull(cache.get(keyA))
        // Same chapter, same text, different fingerprint: a
        // different key — the stale-fingerprint layout is never
        // served.
        val keyB = keyA.copy(fingerprint = listOf("fontB"))
        assertNull(cache.get(keyB))
    }

    @Test
    fun changedTextUnderSameChapterIdIsNeverServedStale() {
        // The terms-edit case (spec 2b): the chapterId and the
        // config fingerprint are stable while the TEXT changes.
        // The content hash in the key is what stands between the
        // reader and pages in the wrong char coordinate system.
        val cache = ChapterLayoutCache()
        val service = ChapterLayoutService()
        val measurer = LineWrapMeasurer(18)
        val fingerprint = listOf("fp")
        val before = proseChapter(fixtureParagraphs)
        val beforeKey = ChapterLayoutCache.Key(
            7L, ChapterLayoutCache.contentHash(before), fingerprint
        )
        cache.put(
            beforeKey,
            service.layout(before, ReaderConfig(), 64f, fingerprint, measurer)
        )
        val edited = proseChapter(
            listOf(
                "The ancient mist clung to the jagged peaks like a shroud of damp silk, and the harbour bells were silent.",
                "Within the abyssal ravine, ten thousand rusted blades hummed in unison.",
                "Su Yi stood at the precipice, his black robes fluttering in the gale."
            )
        )
        val editedKey = ChapterLayoutCache.Key(
            7L, ChapterLayoutCache.contentHash(edited), fingerprint
        )
        assertTrue(beforeKey != editedKey)
        assertNull("the stale layout must be unreachable", cache.get(editedKey))
        val fresh = service.layout(
            edited, ReaderConfig(), 64f, fingerprint, measurer
        )
        cache.put(editedKey, fresh)
        assertEquals(fresh, cache.get(editedKey))
    }

    @Test
    fun cacheIsBoundedToCurrentPlusNext() {
        val cache = ChapterLayoutCache()
        val chapter = proseChapter(fixtureParagraphs)
        val service = ChapterLayoutService()
        val measurer = LineWrapMeasurer(18)
        fun key(id: Long) = ChapterLayoutCache.Key(
            id, ChapterLayoutCache.contentHash(chapter), listOf("fp")
        )
        fun layout() = service.layout(
            chapter, ReaderConfig(), 64f, listOf("fp"), measurer
        )
        cache.put(key(1L), layout())
        cache.put(key(2L), layout())
        assertEquals(2, cache.size)
        // A third chapter evicts the eldest.
        cache.put(key(3L), layout())
        assertEquals(2, cache.size)
        assertNull(cache.get(key(1L)))
        assertNotNull(cache.get(key(2L)))
        assertNotNull(cache.get(key(3L)))
        // Refreshing an entry makes it the newest: the OTHER
        // entry is the one the next insert evicts.
        cache.put(key(2L), layout())
        cache.put(key(4L), layout())
        assertNull(cache.get(key(3L)))
        assertNotNull(cache.get(key(2L)))
        assertNotNull(cache.get(key(4L)))
    }

    @Test
    fun layoutAheadPopulatesTheNextChapterEntry() {
        // The service-level shape of PagedReader's flow: the
        // current chapter's layout lands in the cache, then the
        // ahead pass lays out the next chapter into the same
        // cache — a later lookup for the next chapter hits.
        val service = ChapterLayoutService()
        val measurer = LineWrapMeasurer(18)
        val config = ReaderConfig()
        val fingerprint = listOf("fp")
        val current = proseChapter(fixtureParagraphs)
        val next = proseChapter(
            listOf("A new chapter opens on a quiet road east of the river.")
        )
        val currentKey = ChapterLayoutCache.Key(
            1L, ChapterLayoutCache.contentHash(current), fingerprint
        )
        val nextKey = ChapterLayoutCache.Key(
            2L, ChapterLayoutCache.contentHash(next), fingerprint
        )
        assertNull(service.cache.get(nextKey))
        service.cache.put(
            currentKey,
            service.layout(current, config, 64f, fingerprint, measurer)
        )
        // The ahead pass (what the paged reader runs on
        // Dispatchers.Default once the current layout completes).
        service.cache.put(
            nextKey,
            service.layout(next, config, 64f, fingerprint, measurer)
        )
        assertNotNull(service.cache.get(currentKey))
        assertNotNull(service.cache.get(nextKey))
        assertEquals(2, service.cache.size)
    }

    @Test
    fun aheadCapAndTierGate() {
        // M9: the cap is per ahead chapter, at the stream
        // planner's FULL/STANDARD budget.
        assertTrue(LayoutAheadPolicy.withinCap(0))
        assertTrue(LayoutAheadPolicy.withinCap(1_500_000))
        assertFalse(LayoutAheadPolicy.withinCap(1_500_001))
        // Tier gating: FULL + STANDARD on, MINIMAL off.
        assertTrue(LayoutAheadPolicy.enabled(minimalTier = false))
        assertFalse(LayoutAheadPolicy.enabled(minimalTier = true))
    }

    @Test
    fun emptyChapterIsOneEmptyPage() {
        val chapter = ChapterText(ChapterContent(emptyList()))
        val layout = ChapterLayoutService().layout(
            chapter, ReaderConfig(), 100f, listOf("fp"), LineWrapMeasurer(18)
        )
        assertEquals(1, layout.pages.size)
        assertEquals(0, layout.pages[0].startChar)
    }
}
