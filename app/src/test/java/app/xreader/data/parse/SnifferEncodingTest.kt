package app.xreader.data.parse

import app.xreader.data.db.BookFormat
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Format sniffing is content-based: extensions lie, bytes don't. */
class FormatSnifferTest {

    @get:Rule val temp = TemporaryFolder()

    private fun file(name: String, bytes: ByteArray): File =
        File(temp.root, name).apply { writeBytes(bytes) }

    private fun zipOf(entries: Map<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test fun pdfByMagicBytes() {
        val f = file("novel.bin", "%PDF-1.7\nstuff".toByteArray())
        assertEquals(BookFormat.PDF, FormatSniffer.sniff(f))
    }

    @Test fun epubByContainerXml() {
        val f = file("book.bin", zipOf(mapOf(
            "META-INF/container.xml" to "<container/>".toByteArray(),
            "content.opf" to "<package/>".toByteArray()
        )))
        assertEquals(BookFormat.EPUB, FormatSniffer.sniff(f))
    }

    @Test fun cbzByImagesOnlyZip() {
        val f = file("comic.bin", zipOf(mapOf(
            "001.jpg" to byteArrayOf(1, 2, 3),
            "002.png" to byteArrayOf(4, 5, 6)
        )))
        assertEquals(BookFormat.CBZ, FormatSniffer.sniff(f))
    }

    @Test fun mobiByBookMobiAtOffset60() {
        val bytes = ByteArray(200)
        "BOOKMOBI".toByteArray().copyInto(bytes, 60)
        assertEquals(BookFormat.MOBI, FormatSniffer.sniff(file("book.prc", bytes)))
        assertEquals(BookFormat.AZW3, FormatSniffer.sniff(file("book.azw3", bytes)))
    }

    @Test fun fb2ByRootElement() {
        val f = file("book.bin", "<?xml version=\"1.0\"?><FictionBook></FictionBook>".toByteArray())
        assertEquals(BookFormat.FB2, FormatSniffer.sniff(f))
    }

    @Test fun htmlByDoctype() {
        val f = file("page.bin", "<!DOCTYPE html><html><body>x</body></html>".toByteArray())
        assertEquals(BookFormat.HTML, FormatSniffer.sniff(f))
    }

    @Test fun markdownByExtensionAndHeading() {
        val f = file("notes.md", "# Title\n\nSome text here.".toByteArray())
        assertEquals(BookFormat.MARKDOWN, FormatSniffer.sniff(f))
    }

    @Test fun plainTextFallback() {
        val f = file("novel.txt", "Chapter 1\n\nIt was a bright day.".toByteArray())
        assertEquals(BookFormat.TXT, FormatSniffer.sniff(f))
    }

    @Test fun unknownForBinaryGarbage() {
        val f = file("blob.bin", byteArrayOf(0, 1, 2, 3, 0, 9, 0, 0, 7))
        assertEquals(BookFormat.UNKNOWN, FormatSniffer.sniff(f))
    }
}

/** Encoding detection: BOMs, strict UTF-8, and scored CJK legacy trials. */
class EncodingDetectorTest {

    @Test fun utf8Bom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "你好".toByteArray(Charsets.UTF_8)
        assertEquals(Charsets.UTF_8, EncodingDetector.detectBytes(bytes))
    }

    @Test fun utf16LeBom() {
        val text = "Hello world, this is a test."
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            text.toByteArray(Charsets.UTF_16LE)
        assertEquals(Charsets.UTF_16LE, EncodingDetector.detectBytes(bytes))
    }

    @Test fun gbkChinese() {
        val text = "第一章 开始的故事 这是一个中文小说的开头内容讲述了主角的冒险旅程"
        val bytes = text.toByteArray(charset("GBK"))
        assertEquals("GBK", EncodingDetector.detectBytes(bytes).name())
    }

    @Test fun big5Chinese() {
        val text = "第一章 開始的故事 這是一個中文小說的開頭內容講述了主角的冒險旅程"
        val bytes = text.toByteArray(charset("Big5"))
        assertEquals("Big5", EncodingDetector.detectBytes(bytes).name())
    }

    @Test fun shiftJisJapanese() {
        val text = "第一章 始まりの物語 これは日本の小説の始まりです 主人公の冒険が始まります"
        val bytes = text.toByteArray(charset("Shift_JIS"))
        assertEquals("Shift_JIS", EncodingDetector.detectBytes(bytes).name())
    }

    @Test fun eucKrKorean() {
        val text = "제1장 시작되는 이야기 이것은 한국 소설의 시작 부분입니다 주인공의 모험이 시작됩니다"
        val bytes = text.toByteArray(charset("EUC-KR"))
        assertEquals("EUC-KR", EncodingDetector.detectBytes(bytes).name())
    }

    @Test fun plainUtf8Chinese() {
        val text = "第一章 开始的故事 这是一个中文小说"
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(Charsets.UTF_8, EncodingDetector.detectBytes(bytes))
    }

    @Test fun asciiIsUtf8() {
        val bytes = "Chapter 1. A plain English opening line of prose.".toByteArray()
        assertEquals(Charsets.UTF_8, EncodingDetector.detectBytes(bytes))
    }
}
