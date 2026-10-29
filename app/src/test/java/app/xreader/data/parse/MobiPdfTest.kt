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
}
