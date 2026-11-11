package app.xreader.data.parse

import app.xreader.domain.content.ChapterBlock
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.4 (WS-7): the commercial-shape gate for the V154
 * EPUB-images finding — a fixture built like a real book
 * (subfolder package, guide-only cover, case-mismatched
 * references, root-absolute srcs), NOT like the parser's
 * assumptions. Pre-fix, the headline test's book imported with
 * its text perfect and its cover + every illustration silently
 * gone (both directions recorded in the WS-7 build report).
 */
class EpubCommercialTest : ParserTestBase() {

    private fun chapterBlocks(parsed: ParsedBook, index: Int): List<ChapterBlock> {
        val file = File(parsed.chaptersDir, parsed.chapters[index].contentFileName)
        return json.decodeChapter(file.readText()).blocks
    }

    private fun imageBytes(parsed: ParsedBook, block: ChapterBlock.Image): ByteArray {
        val file = File(parsed.assetsDir, block.fileName)
        assertTrue("asset ${block.fileName} must exist", file.isFile)
        return file.readBytes()
    }

    @Test fun commercialShapeImportsCoverAndEveryIllustration() {
        val bytes = CommercialEpubFixture.bytes(
            CommercialEpubFixture.Fixture(
                coverMode = CommercialEpubFixture.CoverMode.GUIDE_PAGE,
                mangleCase = true,
                chapter2Image = CommercialEpubFixture.Chapter2Image.ROOT_ABSOLUTE
            )
        )
        val (staged, work) = stage("commercial.epub", bytes)
        val parsed = EpubParser().parse(staged, work) {}.unwrap()

        // Text was never the problem — it must stay perfect.
        assertEquals("Commercial Fixture", parsed.title)
        assertEquals(2, parsed.chapters.size)
        assertTrue(chapterTexts(parsed)[0].contains("harbour town"))
        assertTrue(chapterTexts(parsed)[1].contains("cold river valley"))

        // The guide-only cover, through the cover page's own
        // (case-mismatched) picture reference.
        val cover = parsed.coverFile
        assertNotNull("guide-only cover must be extracted", cover)
        assertTrue(cover!!.readBytes().contentEquals(CommercialEpubFixture.COVER_BYTES))

        // Chapter 1: the inline plate AND the SVG-wrapped plate.
        val ch1Images = chapterBlocks(parsed, 0).filterIsInstance<ChapterBlock.Image>()
        assertEquals(2, ch1Images.size)
        assertTrue(imageBytes(parsed, ch1Images[0]).contentEquals(CommercialEpubFixture.PLATE1_BYTES))
        assertTrue(imageBytes(parsed, ch1Images[1]).contentEquals(CommercialEpubFixture.PLATE2_BYTES))

        // Chapter 2: the package-root-absolute src.
        val ch2Images = chapterBlocks(parsed, 1).filterIsInstance<ChapterBlock.Image>()
        assertEquals(1, ch2Images.size)
        assertTrue(imageBytes(parsed, ch2Images[0]).contentEquals(CommercialEpubFixture.PLATE1_BYTES))

        assertEquals(0, parsed.droppedImages)
    }

    @Test fun guideImageCoverSurvivesCaseMangledGuideHref() {
        val bytes = CommercialEpubFixture.bytes(
            CommercialEpubFixture.Fixture(
                coverMode = CommercialEpubFixture.CoverMode.GUIDE_IMAGE,
                mangleCase = false,
                mangleGuide = true,
                chapter2Image = CommercialEpubFixture.Chapter2Image.NONE
            )
        )
        val (staged, work) = stage("guide-image.epub", bytes)
        val parsed = EpubParser().parse(staged, work) {}.unwrap()
        val cover = parsed.coverFile
        assertNotNull("guide image cover must be extracted", cover)
        assertTrue(cover!!.readBytes().contentEquals(CommercialEpubFixture.COVER_BYTES))
        assertEquals(0, parsed.droppedImages)
    }

    @Test fun canonicalShapesStillImportIntact() {
        // The harness's surviving shapes, as one book: properties
        // cover, subfolder OPF, ../ refs, inline + SVG images,
        // percent-encoded spaced paths.
        val bytes = CommercialEpubFixture.bytes(
            CommercialEpubFixture.Fixture(
                coverMode = CommercialEpubFixture.CoverMode.PROPERTIES,
                mangleCase = false,
                chapter2Image = CommercialEpubFixture.Chapter2Image.PERCENT
            )
        )
        val (staged, work) = stage("canonical.epub", bytes)
        val parsed = EpubParser().parse(staged, work) {}.unwrap()
        val cover = parsed.coverFile
        assertNotNull(cover)
        assertTrue(cover!!.readBytes().contentEquals(CommercialEpubFixture.COVER_BYTES))
        val ch1Images = chapterBlocks(parsed, 0).filterIsInstance<ChapterBlock.Image>()
        assertEquals(2, ch1Images.size)
        val ch2Images = chapterBlocks(parsed, 1).filterIsInstance<ChapterBlock.Image>()
        assertEquals(1, ch2Images.size)
        assertTrue(imageBytes(parsed, ch2Images[0]).contentEquals(CommercialEpubFixture.PLATE3_BYTES))
        assertEquals(0, parsed.droppedImages)
    }

    @Test fun metaCoverStillImportsIntact() {
        val bytes = CommercialEpubFixture.bytes(
            CommercialEpubFixture.Fixture(
                coverMode = CommercialEpubFixture.CoverMode.META,
                mangleCase = false,
                chapter2Image = CommercialEpubFixture.Chapter2Image.NONE
            )
        )
        val (staged, work) = stage("meta.epub", bytes)
        val parsed = EpubParser().parse(staged, work) {}.unwrap()
        val cover = parsed.coverFile
        assertNotNull(cover)
        assertTrue(cover!!.readBytes().contentEquals(CommercialEpubFixture.COVER_BYTES))
    }

    @Test fun unresolvableImageIsCountedNotSilent() {
        val bytes = CommercialEpubFixture.bytes(
            CommercialEpubFixture.Fixture(
                coverMode = CommercialEpubFixture.CoverMode.PROPERTIES,
                mangleCase = false,
                chapter2Image = CommercialEpubFixture.Chapter2Image.NONE,
                missingImage = true
            )
        )
        val (staged, work) = stage("missing.epub", bytes)
        val parsed = EpubParser().parse(staged, work) {}.unwrap()
        // The book imports; the one genuinely-absent picture is
        // counted for the honesty line instead of vanishing.
        assertEquals(2, parsed.chapters.size)
        assertNotNull(parsed.coverFile)
        assertEquals(1, parsed.droppedImages)
        val ch1Images = chapterBlocks(parsed, 0).filterIsInstance<ChapterBlock.Image>()
        assertEquals(2, ch1Images.size)
    }
}
