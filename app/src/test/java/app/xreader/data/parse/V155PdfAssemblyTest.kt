package app.xreader.data.parse

import app.xreader.core.error.AppResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.5 (§4): the PDF correctness gates — outline-first
 * boundaries, TOC coalescing in the heading splitter, and the
 * per-chapter prose floor that replaced the global-average
 * "scanned" verdict. All JVM-side: the assembly is pure, and
 * the fakes below model pages/outline/images at the extractor
 * seam (the REAL extractor gets its own harness —
 * V155PdfRealHarnessTest).
 */
class V155PdfAssemblyTest : ParserTestBase() {

    private class FakeExtractor(
        private val pages: List<String>,
        private val outline: List<PdfOutlineEntry> = emptyList(),
        private val images: List<PdfPageImage> = emptyList()
    ) : PdfTextExtractor {
        var extractPagesCalls = 0
            private set

        override fun extractPages(file: File): AppResult<List<String>> {
            extractPagesCalls++
            return AppResult.Ok(pages)
        }

        override fun extractOutline(file: File): List<PdfOutlineEntry> = outline

        override fun extractImages(file: File): List<PdfPageImage> = images
    }

    private val proseA =
        "The amber sentinel watched the harbour through the long " +
            "evening and counted the returning boats one by one."
    private val proseB =
        "Under the cold river bridge the ferryman waited with " +
            "his lantern lit against the coming dark and rain."
    private val proseC =
        "At dawn the mountain road opened above the clouds and " +
            "the whole valley lay silver below the travellers."

    // ── Prose floor ────────────────────────────────────────────

    @Test fun headingOnlyChapterIsStoredButCounted() {
        // The V154 hollow shape in miniature: chapter 2's pages
        // yield its title and two stray words — under the old
        // hasProse rule (any non-Image block) it counted as
        // prose and imported silently.
        val pages = listOf(
            "Chapter 1\n$proseA\n",
            "Chapter 2\nStray words.\n",
            "Chapter 3\n$proseC\n"
        )
        val (staged, work) = stage("hollow.pdf", byteArrayOf(1))
        val parsed = PdfBookParser(FakeExtractor(pages)).parse(staged, work) {}.unwrap()
        assertEquals(3, parsed.chapters.size)
        assertEquals(1, parsed.droppedChapters)
    }

    @Test fun puaAndReplacementCharsDoNotCountAsProse() {
        // A body font extracting as Private-Use-Area codepoints
        // (the subset-CID failure shape) must not pass the
        // floor: those characters are excluded by definition.
        val puaBody = "\uE000\uE001\uE002 ".repeat(60)
        val pages = listOf(
            "Chapter 1\n$proseA\n",
            "Chapter 2\n$puaBody\n",
            "Chapter 3\n$proseC\n"
        )
        val (staged, work) = stage("pua.pdf", byteArrayOf(1))
        val parsed = PdfBookParser(FakeExtractor(pages)).parse(staged, work) {}.unwrap()
        assertEquals(1, parsed.droppedChapters)
    }

    @Test fun imageBearingChapterIsExemptFromTheFloor() {
        // Amendments: a chapter with ≥1 rendered image is a
        // valid illustrated chapter, however little prose it has.
        // The plate sits on its own (textless) page inside
        // chapter 2's span — page-anchored placement puts it in
        // the chapter open at that page.
        val pages = listOf(
            "Chapter 1\n$proseA\n",
            "Chapter 2\nA short caption.\n",
            "",
            "Chapter 3\n$proseC\n"
        )
        val images = listOf(PdfPageImage(2, byteArrayOf(9, 9, 9)))
        val (staged, work) = stage("plate.pdf", byteArrayOf(1))
        val parsed = PdfBookParser(FakeExtractor(pages, images = images))
            .parse(staged, work) {}.unwrap()
        assertEquals(0, parsed.droppedChapters)
        assertEquals(3, parsed.chapters.size)
    }

    @Test fun bookWithNoRealProseAnywhereIsStillScannedRefused() {
        val pages = listOf("Chapter 1\nTiny.\n", "Chapter 2\nSmall.\n")
        val (staged, work) = stage("scan.pdf", byteArrayOf(1))
        val error = PdfBookParser(FakeExtractor(pages)).parse(staged, work) {}.unwrapErr()
        assertTrue(error is app.xreader.core.error.AppError.ParseFailed)
        assertTrue(
            (error as app.xreader.core.error.AppError.ParseFailed)
                .reason.contains("scanned")
        )
    }

    // ── TOC coalescing ─────────────────────────────────────────

    @Test fun tocPageDoesNotSpawnPseudoChapters() {
        val toc = buildString {
            append("Contents\n")
            append("Chapter 1 The Amber Sentinel ............ 2\n")
            append("Chapter 2 The Cold River ............ 3\n")
            append("Chapter 3 The Mountain Road ............ 4\n")
        }
        val pages = listOf(
            toc,
            "Chapter 1\n$proseA\n",
            "Chapter 2\n$proseB\n",
            "Chapter 3\n$proseC\n"
        )
        val (staged, work) = stage("toc.pdf", byteArrayOf(1))
        val parsed = PdfBookParser(FakeExtractor(pages)).parse(staged, work) {}.unwrap()
        val titles = parsed.chapters.map { it.title }
        assertEquals(
            listOf("Table of Contents", "Chapter 1", "Chapter 2", "Chapter 3"),
            titles
        )
        // The coalesced section keeps the TOC lines as readable
        // text and is NOT counted as a failed chapter.
        assertTrue(chapterTexts(parsed)[0].contains("Amber Sentinel"))
        assertEquals(0, parsed.droppedChapters)
        assertTrue(chapterTexts(parsed)[1].contains("amber sentinel"))
        assertTrue(chapterTexts(parsed)[3].contains("mountain road"))
    }

    // ── Outline first ──────────────────────────────────────────

    @Test fun outlineBoundariesWinOverHeadingDetection() {
        // Page 2 carries a line that LOOKS like a heading
        // mid-chapter; the outline says the chapter runs to
        // page 3, so no split may happen there.
        val pages = listOf(
            "Front matter page with a few words of introduction text here.\n",
            "Chapter 1\n$proseA\n",
            "Chapter 9\n$proseB\n",
            "Chapter 2\n$proseC\n"
        )
        val outline = listOf(
            PdfOutlineEntry("Chapter 1", 1),
            PdfOutlineEntry("Chapter 2", 3)
        )
        val (staged, work) = stage("outline.pdf", byteArrayOf(1))
        val parsed = PdfBookParser(FakeExtractor(pages, outline = outline))
            .parse(staged, work) {}.unwrap()
        val titles = parsed.chapters.map { it.title }
        assertEquals(listOf("Front Matter", "Chapter 1", "Chapter 2"), titles)
        // Chapter 1 holds BOTH its own prose and the page the
        // heading splitter would have torn off as "Chapter 9".
        assertTrue(chapterTexts(parsed)[1].contains("amber sentinel"))
        assertTrue(chapterTexts(parsed)[1].contains("cold river"))
        assertTrue(chapterTexts(parsed)[2].contains("mountain road"))
    }

    // ── Probe ──────────────────────────────────────────────────

    @Test fun probeUsesOutlineTitlesAndNeverStripsPages() {
        val pages = listOf("Chapter 1\n$proseA\n", "Chapter 2\n$proseB\n")
        val outline = listOf(
            PdfOutlineEntry("Chapter 1", 0),
            PdfOutlineEntry("Chapter 2", 1)
        )
        val extractor = object : PdfTextExtractor {
            var probeCalls = 0
            var stripCalls = 0

            override fun extractPages(file: File): AppResult<List<String>> {
                stripCalls++
                return AppResult.Ok(pages)
            }

            override fun probe(file: File): AppResult<PdfProbe> {
                probeCalls++
                return AppResult.Ok(
                    PdfProbe(
                        pageCount = 227,
                        title = "Probed Title",
                        author = "Probed Author",
                        description = null,
                        outline = outline
                    )
                )
            }
        }
        val (staged, work) = stage("probe.pdf", byteArrayOf(1))
        val probed = PdfBookParser(extractor).probe(staged, work).unwrap()
        assertEquals(1, extractor.probeCalls)
        assertEquals("the probe must not strip a single page", 0, extractor.stripCalls)
        assertEquals("Probed Title", probed.title)
        assertEquals(227, probed.pageCount)
        assertEquals(listOf("Chapter 1", "Chapter 2"), probed.provisionalTitles)
    }

    @Test fun probeWithoutOutlineFallsBackToPageRangeSections() {
        val extractor = object : PdfTextExtractor {
            override fun extractPages(file: File): AppResult<List<String>> =
                AppResult.Ok(emptyList())

            override fun probe(file: File): AppResult<PdfProbe> =
                AppResult.Ok(PdfProbe(25, null, null, null, emptyList()))
        }
        val (staged, work) = stage("ranges.pdf", byteArrayOf(1))
        val probed = PdfBookParser(extractor).probe(staged, work).unwrap()
        assertEquals(
            listOf("Pages 1–10", "Pages 11–20", "Pages 21–25"),
            probed.provisionalTitles
        )
    }
}
