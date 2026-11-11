package app.xreader.data.parse

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.BookFormat
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobiParserTest : ParserTestBase() {

    /** Builds a minimal unencrypted MOBI (PalmDB + MOBI header + one
     * PalmDOC-compressed text record) entirely in memory. */
    private fun buildMobi(
        html: String,
        encryptionType: Int = 0,
        compression: Int = 2,
        title: String = "Kindle Fixture"
    ): ByteArray {
        val textRecord = if (compression == 2) palmDocCompress(html.toByteArray()) else html.toByteArray()
        val record0 = ByteArrayOutputStream()
        // PalmDOC header (16 bytes)
        fun w16(v: Int) { record0.write(v shr 8); record0.write(v and 0xFF) }
        fun w32(v: Int) {
            record0.write(v shr 24); record0.write(v shr 16); record0.write(v shr 8); record0.write(v and 0xFF)
        }
        w16(compression)          // compression
        w16(0)                    // unused
        w32(html.length)          // text length
        w16(1)                    // record count
        w16(4096)                 // record size
        w16(encryptionType)       // encryption type
        w16(0)                    // unknown
        // MOBI header at offset 16
        val mobiStart = record0.size()
        record0.write("MOBI".toByteArray())
        w32(232)                  // header length
        w32(2)                    // mobi type
        w32(65001)                // encoding UTF-8
        w32(1)                    // unique id
        w32(6)                    // file version
        repeat(9) { w32(0) }      // offsets 36..71 (orthogonal etc.)
        // name offset/length live at MOBI+84/+88: header so far is
        // 16 (palmdoc) + 8 + 36 = 60 bytes into record0 → MOBI-relative 44.
        // Pad to MOBI+84.
        while (record0.size() - mobiStart < 84) w32(0)
        val nameBytes = title.toByteArray()
        val nameOffset = 16 + 232 // right after the MOBI header
        w32(nameOffset)
        w32(nameBytes.size)
        while (record0.size() - mobiStart < 232) w32(0)
        record0.write(nameBytes)

        val records = listOf(record0.toByteArray(), textRecord)
        return buildPalmDb(records)
    }

    private fun buildPalmDb(records: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        val name = ByteArray(32)
        "Fixture".toByteArray().copyInto(name)
        out.write(name)                                    // 0..31
        fun w16(v: Int) { out.write(v shr 8); out.write(v and 0xFF) }
        fun w32(v: Int) {
            out.write(v shr 24); out.write(v shr 16); out.write(v shr 8); out.write(v and 0xFF)
        }
        w16(0); w16(0)                                     // attributes, version
        w32(0); w32(0); w32(0); w32(0)                     // dates, mod number
        w32(0); w32(0)                                     // appInfo, sortInfo
        out.write("BOOK".toByteArray())                    // type at 60
        out.write("MOBI".toByteArray())                    // creator at 64
        w32(0); w32(0)                                     // uid seed, next list
        w16(records.size)                                  // numRecords at 76
        var offset = 78 + records.size * 8
        for (record in records) {
            w32(offset)
            w32(0) // attributes + unique id
            offset += record.size
        }
        for (record in records) out.write(record)
        return out.toByteArray()
    }

    /** Reference PalmDOC encoder for fixtures (literals only, plus one
     * space-shortcut) — the decoder is what's under test. */
    private fun palmDocCompress(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            when {
                b == ' '.code && i + 1 < data.size && data[i + 1] in 0x40..0x7F -> {
                    out.write(0x80 or (data[i + 1].toInt() and 0x7F))
                    i += 2
                }
                b in 0x09..0x7F || b >= 0x80 -> {
                    out.write(b)
                    i++
                }
                else -> {
                    // run of control bytes → count-prefixed literal block
                    var end = i
                    while (end < data.size && end - i < 8) {
                        val c = data[end].toInt() and 0xFF
                        if (c in 0x09..0x7F || c >= 0x80) break
                        end++
                    }
                    if (end == i) end = i + 1
                    out.write(end - i)
                    out.write(data, i, end - i)
                    i = end
                }
            }
        }
        return out.toByteArray()
    }

    @Test fun palmDocRoundTrip() {
        val original = "Hello world, this is a PalmDOC test with  spaces and <html> tags."
            .toByteArray()
        val decoded = PalmDoc.decompress(palmDocCompress(original))
        assertEquals(original.toList(), decoded.toList())
    }

    @Test fun palmDocBackReference() {
        // "abcabcabc" encoded as literals a b c + backref(distance 3, len 6)
        val encoded = byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(),
            ((0b10000000 shl 8 or 0) shr 8).toByte(), 0)
        // Build the pair properly: value = (distance << 3) | (length - 3), top bits 10
        val pair = 0x8000 or (3 shl 3) or (6 - 3)
        val stream = byteArrayOf(
            'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(),
            (pair shr 8).toByte(), (pair and 0xFF).toByte()
        )
        assertEquals("abcabcabc", PalmDoc.decompress(stream).toString(Charsets.US_ASCII))
        @Suppress("UNUSED_VARIABLE") val unused = encoded
    }

    @Test fun unencryptedMobiParses() {
        val html = "<html><body><h1>Chapter 1</h1>" +
            "<p>The Kindle book opens here with real text.</p></body></html>"
        val (staged, work) = stage("book.mobi", buildMobi(html))
        val parsed = MobiParser(BookFormat.MOBI).parse(staged, work) {}.unwrap()
        assertEquals("Kindle Fixture", parsed.title)
        assertEquals(BookFormat.MOBI, parsed.format)
        assertTrue(chapterTexts(parsed).joinToString(" ").contains("Kindle book opens"))
    }

    @Test fun encryptedMobiRefusedAsDrm() {
        val html = "<html><body><p>Locked.</p></body></html>"
        val (staged, work) = stage("book.azw3", buildMobi(html, encryptionType = 1))
        val error = MobiParser(BookFormat.AZW3).parse(staged, work) {}.unwrapErr()
        assertEquals(AppError.DrmProtected, error)
    }

    @Test fun huffmanMobiFailsHonestly() {
        val html = "<html><body><p>Huffman coded.</p></body></html>"
        val (staged, work) = stage(
            "book.mobi", buildMobi(html, compression = MobiParser.HUFFMAN_COMPRESSION)
        )
        val error = MobiParser(BookFormat.MOBI).parse(staged, work) {}.unwrapErr()
        assertTrue(error is AppError.ParseFailed)
        assertTrue((error as AppError.ParseFailed).reason.contains("Calibre"))
    }
}

class PdfReflowTest {

    @Test fun dehyphenatesAndJoinsLines() {
        val raw = "The quick brown fox jum-\nped over the lazy dog. It was\na sunny afternoon in the meadow."
        val paragraphs = PdfReflow.reflow(raw)
        assertEquals(1, paragraphs.size)
        assertEquals(
            "The quick brown fox jumped over the lazy dog. It was a sunny afternoon in the meadow.",
            paragraphs[0]
        )
    }

    @Test fun blankLinesAreParagraphBreaks() {
        val raw = "First paragraph here.\n\nSecond paragraph follows."
        assertEquals(
            listOf("First paragraph here.", "Second paragraph follows."),
            PdfReflow.reflow(raw)
        )
    }

    @Test fun headingsStayBoundaries() {
        val raw = "Some closing text.\n\nChapter 12\nThe new chapter text begins."
        val paragraphs = PdfReflow.reflow(raw)
        assertTrue(paragraphs.contains("Chapter 12"))
        assertTrue(PdfReflow.looksLikeHeading("Chapter 12"))
        assertTrue(PdfReflow.looksLikeHeading("第3章 始まり"))
        assertTrue(!PdfReflow.looksLikeHeading("He whispered chapter and verse to her."))
    }
}

class PdfBookParserTest : ParserTestBase() {

    private class FakeExtractor(private val pages: List<String>) : PdfTextExtractor {
        override fun extractPages(file: File): AppResult<List<String>> = AppResult.Ok(pages)
    }

    @Test fun chaptersSplitAtHeadings() {
        val pages = listOf(
            "Chapter 1\nIt began with a single step into the unknown lands beyond.\n",
            "The journey continued through forests and over cold rivers.\n\nChapter 2\nA new day dawned over the mountains of the north.\n"
        )
        val (staged, work) = stage("book.pdf", byteArrayOf(1, 2, 3))
        val parsed = PdfBookParser(FakeExtractor(pages)).parse(staged, work) {}.unwrap()
        assertEquals(2, parsed.chapters.size)
        assertEquals("Chapter 1", parsed.chapters[0].title)
        assertEquals("Chapter 2", parsed.chapters[1].title)
        assertTrue(chapterTexts(parsed)[0].contains("cold rivers"))
    }

    @Test fun scannedPdfRefusedPlainly() {
        val pages = listOf("", "  ", "")
        val (staged, work) = stage("scan.pdf", byteArrayOf(1))
        val error = PdfBookParser(FakeExtractor(pages)).parse(staged, work) {}.unwrapErr()
        assertTrue(error is AppError.ParseFailed)
        assertTrue((error as AppError.ParseFailed).reason.contains("scanned"))
    }

    // ── v1.5.2 (FIX-21): PDF illustrations ─────────────────────

    private class FakeExtractorWithImages(
        private val pages: List<String>,
        private val images: List<PdfPageImage>
    ) : PdfTextExtractor {
        override fun extractPages(file: File): AppResult<List<String>> =
            AppResult.Ok(pages)

        override fun extractImages(file: File): List<PdfPageImage> = images
    }

    private fun chapterBlocks(
        parsed: ParsedBook, chapterIndex: Int
    ): List<app.xreader.domain.content.ChapterBlock> {
        val file = File(parsed.chaptersDir, parsed.chapters[chapterIndex].contentFileName)
        return json.decodeChapter(file.readText()).blocks
    }

    @Test fun pdfIllustrationsLandPageAnchoredInPageOrder() {
        val prose = "The road wound on through the long valley and the river ran beside it all the way north."
        val pages = listOf(
            "Chapter 1\n$prose\n",
            "$prose And then the mountains rose ahead of them, white with the first snow of the year.\n",
            "$prose At last they saw the tower standing alone above the plain, its lamp already lit.\n"
        )
        val plate = byteArrayOf(9, 9, 9, 1)
        val sketch = byteArrayOf(7, 7, 2)
        val images = listOf(
            PdfPageImage(0, plate),
            PdfPageImage(2, sketch)
        )
        val (staged, work) = stage("illus.pdf", byteArrayOf(1))
        val parsed = PdfBookParser(FakeExtractorWithImages(pages, images))
            .parse(staged, work) {}.unwrap()
        // Assets staged for the importer's book-asset copy.
        val assets = requireNotNull(parsed.assetsDir) { "assets dir must be set" }
        assertEquals(2, assets.listFiles()?.size)
        val blocks = chapterBlocks(parsed, 0)
        val imageBlocks = blocks.filterIsInstance<app.xreader.domain.content.ChapterBlock.Image>()
        assertEquals(2, imageBlocks.size)
        // Page order: the page-1 plate precedes the page-3 sketch,
        // and both sit at their pages' positions in the flow.
        val firstImageAt = blocks.indexOfFirst { it is app.xreader.domain.content.ChapterBlock.Image }
        assertTrue("page 1's plate anchors at the page's own position (the chapter head)", firstImageAt == 0)
        val names = imageBlocks.map { it.fileName }
        assertTrue(names.all { File(assets, it).isFile })
        assertTrue(names[0] != names[1])
    }

    @Test fun pdfIllustrationsDedupeByContentHashFileNotReference() {
        val prose = "A long enough paragraph of real prose to carry this page well past the scanned threshold."
        val pages = listOf("Chapter 1\n$prose\n", "$prose The same small ornament prints at the head of every part.\n")
        val ornament = byteArrayOf(5, 5, 5, 5)
        val images = listOf(PdfPageImage(0, ornament), PdfPageImage(1, ornament))
        val (staged, work) = stage("dedupe.pdf", byteArrayOf(1))
        val parsed = PdfBookParser(FakeExtractorWithImages(pages, images))
            .parse(staged, work) {}.unwrap()
        val assets = requireNotNull(parsed.assetsDir)
        // One FILE for the repeated image…
        assertEquals(1, assets.listFiles()?.size)
        // …but a reference at EACH occurrence.
        val blocks = chapterBlocks(parsed, 0)
        val refs = blocks.filterIsInstance<app.xreader.domain.content.ChapterBlock.Image>()
        assertEquals(2, refs.size)
        assertEquals(refs[0].fileName, refs[1].fileName)
    }

    @Test fun scannedPdfWithImagesKeepsHonestScannedState() {
        val pages = listOf("", "", "")
        val images = listOf(PdfPageImage(0, byteArrayOf(1, 2, 3)))
        val (staged, work) = stage("scanimg.pdf", byteArrayOf(1))
        val error = PdfBookParser(FakeExtractorWithImages(pages, images))
            .parse(staged, work) {}.unwrapErr()
        assertTrue(error is AppError.ParseFailed)
        assertTrue((error as AppError.ParseFailed).reason.contains("scanned"))
    }

    // ── v1.5.2 (FIX-24): PDF first-page cover ──────────────────

    /** A fake modelling every cover-relevant extractor leg: the
     * FIX-21 image list (or a throwing extraction) and the FIX-24
     * render fallback (bytes, null, or a throw). */
    private class FakeCoverExtractor(
        private val pages: List<String>,
        private val images: List<PdfPageImage> = emptyList(),
        private val imagesThrow: Boolean = false,
        private val rendered: ByteArray? = null,
        private val renderThrows: Boolean = false
    ) : PdfTextExtractor {
        override fun extractPages(file: File): AppResult<List<String>> =
            AppResult.Ok(pages)

        override fun extractImages(file: File): List<PdfPageImage> {
            if (imagesThrow) throw IllegalStateException("image walk broke")
            return images
        }

        override fun renderFirstPageCover(file: File): ByteArray? {
            if (renderThrows) throw IllegalStateException("renderer broke")
            return rendered
        }
    }

    private val coverProse =
        "The road wound on through the long valley and the river ran beside it all the way north."
    private val coverPages = listOf(
        "Chapter 1\n$coverProse\n",
        "$coverProse And then the mountains rose ahead of them, white with the first snow.\n"
    )

    private fun sizedBytes(size: Int, fill: Byte): ByteArray =
        ByteArray(size) { fill }

    @Test fun pdfCoverPicksDominantFirstPageImage() {
        val logo = sizedBytes(20 * 1024, 3)          // above floor, not dominant
        val plate = sizedBytes(200 * 1024, 9)        // the page-1 plate
        val laterPage = sizedBytes(500 * 1024, 7)    // bigger, but page 2 — never a cover
        val images = listOf(
            PdfPageImage(0, logo),
            PdfPageImage(0, plate),
            PdfPageImage(1, laterPage)
        )
        val (staged, work) = stage("cover.pdf", byteArrayOf(1))
        val parsed = PdfBookParser(FakeCoverExtractor(coverPages, images))
            .parse(staged, work) {}.unwrap()
        val cover = requireNotNull(parsed.coverFile) { "a cover must be derived" }
        assertEquals("cover.png", cover.name)
        assertTrue(cover.readBytes().contentEquals(plate))
    }

    @Test fun pdfCoverRejectsTinyFirstPageImages() {
        // A 100-byte ornament is below the dominance floor: with no
        // render fallback the book imports with NO cover rather
        // than dressing the shelf in a logo.
        val images = listOf(PdfPageImage(0, sizedBytes(100, 5)))
        val (staged, work) = stage("tiny.pdf", byteArrayOf(1))
        val parsed = PdfBookParser(FakeCoverExtractor(coverPages, images))
            .parse(staged, work) {}.unwrap()
        assertTrue(parsed.chapters.isNotEmpty())
        assertTrue(parsed.coverFile == null)
    }

    @Test fun pdfCoverOversizedFallsBackToNextCandidate() {
        val oversized = sizedBytes(PdfBookParser.MAX_COVER_BYTES + 1, 8)
        val inBound = sizedBytes(100 * 1024, 4)
        val images = listOf(PdfPageImage(0, oversized), PdfPageImage(0, inBound))
        val (staged, work) = stage("oversized.pdf", byteArrayOf(1))
        val parsed = PdfBookParser(FakeCoverExtractor(coverPages, images))
            .parse(staged, work) {}.unwrap()
        val cover = requireNotNull(parsed.coverFile)
        assertTrue(cover.readBytes().contentEquals(inBound))
    }

    @Test fun pdfCoverOversizedOnlyFallsBackToRenderLeg() {
        val oversized = sizedBytes(PdfBookParser.MAX_COVER_BYTES + 1, 8)
        val rendered = sizedBytes(50 * 1024, 6)
        val (staged, work) = stage("oversizedonly.pdf", byteArrayOf(1))
        val parsed = PdfBookParser(
            FakeCoverExtractor(
                coverPages,
                images = listOf(PdfPageImage(0, oversized)),
                rendered = rendered
            )
        ).parse(staged, work) {}.unwrap()
        val cover = requireNotNull(parsed.coverFile)
        assertEquals("cover.jpg", cover.name)
        assertTrue(cover.readBytes().contentEquals(rendered))
    }

    @Test fun pdfCoverRenderFallbackForPlainTextPdf() {
        // No embedded images at all: the rendered first page is
        // the cover (the Android leg itself is device-proven; here
        // the parser seam is what is pinned).
        val rendered = sizedBytes(80 * 1024, 2)
        val (staged, work) = stage("plain.pdf", byteArrayOf(1))
        val parsed = PdfBookParser(
            FakeCoverExtractor(coverPages, rendered = rendered)
        ).parse(staged, work) {}.unwrap()
        val cover = requireNotNull(parsed.coverFile)
        assertEquals("cover.jpg", cover.name)
        assertTrue(cover.readBytes().contentEquals(rendered))
    }

    @Test fun pdfCoverRenderedBytesOverBoundRejected() {
        val rendered = sizedBytes(PdfBookParser.MAX_COVER_BYTES + 1, 1)
        val (staged, work) = stage("bigrender.pdf", byteArrayOf(1))
        val parsed = PdfBookParser(
            FakeCoverExtractor(coverPages, rendered = rendered)
        ).parse(staged, work) {}.unwrap()
        assertTrue(parsed.coverFile == null)
        assertTrue(parsed.chapters.isNotEmpty())
    }

    @Test fun pdfCoverExtractionFailureLeavesImportUntouched() {
        // The image walk throws AND the render leg throws: the
        // book still imports, chapters intact, simply coverless —
        // exactly as it did before FIX-24.
        val (staged, work) = stage("brokenimg.pdf", byteArrayOf(1))
        val parsed = PdfBookParser(
            FakeCoverExtractor(coverPages, imagesThrow = true, renderThrows = true)
        ).parse(staged, work) {}.unwrap()
        assertTrue(parsed.coverFile == null)
        assertTrue(parsed.chapters.isNotEmpty())
        assertTrue(chapterTexts(parsed)[0].contains("long valley"))
    }
}
