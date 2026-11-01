package app.xreader.data.parse

import app.xreader.core.error.AppError
import app.xreader.data.db.BookFormat
import app.xreader.domain.content.ChapterBlock
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic EPUB fixture builder + parser assertions. */
class EpubParserTest : ParserTestBase() {

    private fun epubZip(
        chapters: List<Pair<String, String>>,
        extraEntries: Map<String, ByteArray> = emptyMap(),
        encryptionXml: String? = null,
        withOverlay: Boolean = false
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, content: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip".toByteArray())
            entry(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>""".toByteArray()
            )
            if (encryptionXml != null) entry("META-INF/encryption.xml", encryptionXml.toByteArray())
            val manifest = StringBuilder()
            val spine = StringBuilder()
            chapters.forEachIndexed { i, _ ->
                val overlayAttr = if (withOverlay && i == 0) " media-overlay=\"smil1\"" else ""
                manifest.append(
                    "<item id=\"chap${i + 1}\" href=\"chap${i + 1}.xhtml\" " +
                        "media-type=\"application/xhtml+xml\"$overlayAttr/>"
                )
                spine.append("<itemref idref=\"chap${i + 1}\"/>")
            }
            if (withOverlay) {
                manifest.append(
                    "<item id=\"smil1\" href=\"overlay.smil\" media-type=\"application/smil+xml\"/>"
                )
                manifest.append(
                    "<item id=\"audio1\" href=\"audio/narration.mp3\" media-type=\"audio/mpeg\"/>"
                )
            }
            manifest.append(
                "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>"
            )
            manifest.append(
                "<item id=\"cover\" href=\"images/cover.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/>"
            )
            val opf = """<?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="bookid">urn:test:1</dc:identifier>
                <dc:title>Fixture Novel</dc:title>
                <dc:creator>Jane Author</dc:creator>
                <dc:description>A tale of testing.</dc:description>
                <dc:language>en</dc:language>
                </metadata>
                <manifest>$manifest</manifest>
                <spine toc="ncx">$spine</spine>
                </package>"""
            entry("OEBPS/content.opf", opf.toByteArray())
            val navPoints = chapters.mapIndexed { i, (title, _) ->
                "<navPoint id=\"np${i + 1}\" playOrder=\"${i + 1}\">" +
                    "<navLabel><text>$title</text></navLabel>" +
                    "<content src=\"chap${i + 1}.xhtml\"/></navPoint>"
            }.joinToString("")
            entry(
                "OEBPS/toc.ncx",
                """<?xml version="1.0"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                <docTitle><text>Fixture Novel</text></docTitle>
                <navMap>$navPoints</navMap></ncx>""".toByteArray()
            )
            chapters.forEachIndexed { i, (_, body) ->
                entry(
                    "OEBPS/chap${i + 1}.xhtml",
                    """<?xml version="1.0"?>
                    <html xmlns="http://www.w3.org/1999/xhtml"><head><title>ch</title></head>
                    <body>$body</body></html>""".toByteArray()
                )
            }
            entry("OEBPS/images/cover.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3))
            if (withOverlay) {
                entry(
                    "OEBPS/overlay.smil",
                    """<?xml version="1.0"?>
                    <smil xmlns="http://www.w3.org/ns/SMIL">
                    <body><seq>
                    <par><text src="chap1.xhtml#p1"/><audio src="audio/narration.mp3" clipBegin="0s" clipEnd="4.5s"/></par>
                    <par><text src="chap1.xhtml#p2"/><audio src="audio/narration.mp3" clipBegin="4.5s" clipEnd="9.75s"/></par>
                    </seq></body></smil>""".toByteArray()
                )
                entry("OEBPS/audio/narration.mp3", byteArrayOf(9, 9, 9, 9))
            }
            for ((name, bytes) in extraEntries) entry(name, bytes)
        }
        return out.toByteArray()
    }

    @Test fun metadataChaptersAndCover() {
        val bytes = epubZip(
            listOf(
                "Chapter One" to "<h1>Chapter One</h1><p>The story opens with a bang.</p>",
                "Chapter Two" to "<h1>Chapter Two</h1><p>Then things got <em>very</em> quiet.</p>"
            )
        )
        val (staged, work) = stage("book.epub", bytes)
        val parsed = EpubParser().parse(staged, work) {}.unwrap()
        assertEquals("Fixture Novel", parsed.title)
        assertEquals("Jane Author", parsed.author)
        assertEquals("A tale of testing.", parsed.description)
        assertEquals(BookFormat.EPUB, parsed.format)
        assertEquals(2, parsed.chapters.size)
        assertEquals("Chapter One", parsed.chapters[0].title)
        assertTrue(chapterTexts(parsed)[1].contains("very"))
        assertNotNull(parsed.coverFile)
        assertTrue(parsed.coverFile!!.isFile)
    }

    @Test fun imagesAreCopiedToAssets() {
        val bytes = epubZip(
            listOf("Ch" to "<p>Before.</p><p><img src=\"images/cover.jpg\"/></p><p>After.</p>")
        )
        val (staged, work) = stage("book.epub", bytes)
        val parsed = EpubParser().parse(staged, work) {}.unwrap()
        val blocks = firstChapterBlocks(parsed)
        val image = blocks.filterIsInstance<ChapterBlock.Image>().single()
        assertTrue(File(parsed.assetsDir, image.fileName).isFile)
    }

    @Test fun mediaOverlayParsedIntoSyncModel() {
        val bytes = epubZip(
            listOf(
                "Ch" to "<p id=\"p1\">First spoken line.</p><p id=\"p2\">Second spoken line.</p>"
            ),
            withOverlay = true
        )
        val (staged, work) = stage("book.epub", bytes)
        val parsed = EpubParser().parse(staged, work) {}.unwrap()
        val content = json.decodeChapter(
            File(parsed.chaptersDir, parsed.chapters[0].contentFileName).readText()
        )
        val overlay = content.mediaOverlay
        assertNotNull(overlay)
        assertEquals(2, overlay!!.clips.size)
        assertEquals("p1", overlay.clips[0].textId)
        assertEquals(0.0, overlay.clips[0].beginSec, 0.001)
        assertEquals(4.5, overlay.clips[0].endSec, 0.001)
        assertEquals(9.75, overlay.clips[1].endSec, 0.001)
        assertTrue(File(parsed.assetsDir, overlay.audioFileName).isFile)
    }

    @Test fun drmContentRefusedHonestly() {
        val bytes = epubZip(
            listOf("Ch" to "<p>Locked away.</p>"),
            encryptionXml = """<?xml version="1.0"?>
                <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"
                  xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
                <enc:EncryptedData><enc:CipherData>
                <enc:CipherReference URI="OEBPS/chap1.xhtml"/>
                </enc:CipherData></enc:EncryptedData></encryption>"""
        )
        val (staged, work) = stage("book.epub", bytes)
        val error = EpubParser().parse(staged, work) {}.unwrapErr()
        assertEquals(AppError.DrmProtected, error)
    }

    @Test fun fontOnlyEncryptionIsNotDrm() {
        val bytes = epubZip(
            listOf("Ch" to "<p>Perfectly readable.</p>"),
            encryptionXml = """<?xml version="1.0"?>
                <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"
                  xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
                <enc:EncryptedData><enc:CipherData>
                <enc:CipherReference URI="OEBPS/fonts/book.ttf"/>
                </enc:CipherData></enc:EncryptedData></encryption>"""
        )
        val (staged, work) = stage("book.epub", bytes)
        val parsed = EpubParser().parse(staged, work) {}.unwrap()
        assertEquals(1, parsed.chapters.size)
    }

    @Test fun smilClockValues() {
        assertEquals(4.5, SmilParser.parseClock("4.5s")!!, 0.001)
        assertEquals(0.5, SmilParser.parseClock("500ms")!!, 0.001)
        assertEquals(150.5, SmilParser.parseClock("02:30.5")!!, 0.001)
        assertEquals(3723.25, SmilParser.parseClock("01:02:03.25")!!, 0.001)
        assertEquals(120.0, SmilParser.parseClock("2min")!!, 0.001)
    }

    private fun rawZip(entries: List<Pair<String, ByteArray>>): ByteArray {
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

    @Test fun extractionStopsAtTheTotalSizeCap() {
        val bytes = rawZip(
            listOf(
                "a.bin" to ByteArray(600) { 1 },
                "b.bin" to ByteArray(600) { 2 }
            )
        )
        val (staged, work) = stage("book.epub", bytes)
        val dest = File(work, "unzipped-test")
        val error = EpubParser().extractZip(staged, dest, maxTotalBytes = 1_000)
        assertTrue("expected BadFile, got $error", error is AppError.BadFile)
        assertTrue((error as AppError.BadFile).reason.contains("too large"))
        // The partial extraction was cleaned up again.
        assertTrue(dest.listFiles().isNullOrEmpty())
    }

    @Test fun extractionStopsAtTheEntryCountCap() {
        val bytes = rawZip(
            listOf("a.bin" to byteArrayOf(1), "b.bin" to byteArrayOf(2))
        )
        val (staged, work) = stage("book.epub", bytes)
        val dest = File(work, "unzipped-test")
        val error = EpubParser().extractZip(staged, dest, maxEntries = 1)
        assertTrue("expected BadFile, got $error", error is AppError.BadFile)
        assertTrue((error as AppError.BadFile).reason.contains("too many files"))
    }

    @Test fun smallArchiveExtractsCleanly() {
        val bytes = rawZip(listOf("a.bin" to byteArrayOf(1, 2, 3)))
        val (staged, work) = stage("book.epub", bytes)
        val dest = File(work, "unzipped-test")
        assertEquals(null, EpubParser().extractZip(staged, dest))
        assertTrue(File(dest, "a.bin").isFile)
    }
}

class Fb2ParserTest : ParserTestBase() {

    @Test fun sectionsMetadataAndCover() {
        val coverB64 = java.util.Base64.getEncoder()
            .encodeToString(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 5, 6, 7))
        val fb2 = """<?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"
              xmlns:xlink="http://www.w3.org/1999/xlink">
            <description><title-info>
            <author><first-name>John</first-name><last-name>Writer</last-name></author>
            <book-title>FB2 Fixture</book-title>
            <annotation><p>A short annotation.</p></annotation>
            <coverpage><image xlink:href="#cover.jpg"/></coverpage>
            <lang>en</lang>
            </title-info></description>
            <body>
            <section><title><p>Chapter 1</p></title>
            <p>The first section's text is here.</p>
            <p>It has <emphasis>emphasis</emphasis> too.</p>
            </section>
            <section><title><p>Chapter 2</p></title>
            <p>The second section closes the book.</p>
            </section>
            </body>
            <binary id="cover.jpg" content-type="image/jpeg">$coverB64</binary>
            </FictionBook>"""
        val (staged, work) = stage("book.fb2", fb2.toByteArray())
        val parsed = Fb2Parser().parse(staged, work) {}.unwrap()
        assertEquals("FB2 Fixture", parsed.title)
        assertEquals("John Writer", parsed.author)
        assertEquals("A short annotation.", parsed.description)
        assertEquals(2, parsed.chapters.size)
        assertEquals("Chapter 1", parsed.chapters[0].title)
        assertTrue(chapterTexts(parsed)[0].contains("emphasis"))
        assertNotNull(parsed.coverFile)
    }
}

class CbzParserTest : ParserTestBase() {

    @Test fun naturalPageOrderAndCover() {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun entry(name: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
            entry("10.jpg")
            entry("2.jpg")
            entry("1.jpg")
            entry("ComicInfo.xml".replace("ComicInfo.xml", "notes.txt"))
        }
        val (staged, work) = stage("comic.cbz", out.toByteArray())
        val parsed = CbzParser().parse(staged, work) {}.unwrap()
        assertEquals(1, parsed.chapters.size)
        val images = firstChapterBlocks(parsed).filterIsInstance<ChapterBlock.Image>()
        assertEquals(3, images.size)
        // Natural order: page files renamed in archive order 1, 2, 10.
        val names = images.map { it.fileName }
        assertTrue(names[0].contains("1"))
        assertTrue(names[1].contains("2"))
        assertTrue(names[2].contains("10"))
        assertNotNull(parsed.coverFile)
    }

    @Test fun naturalSortComparator() {
        val sorted = listOf("10.jpg", "2.jpg", "1.jpg", "02.png", "a3.jpg")
            .sortedWith(NaturalSort)
        assertEquals(listOf("1.jpg", "2.jpg", "02.png", "10.jpg", "a3.jpg"), sorted)
    }

    private fun cbzBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
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

    @Test fun oversizedComicInfoIsRefused() {
        val bytes = cbzBytes(
            listOf(
                "ComicInfo.xml" to ByteArray(100) { 65 },
                "1.jpg" to byteArrayOf(1, 2, 3)
            )
        )
        val (staged, work) = stage("comic.cbz", bytes)
        val error = CbzParser().parse(
            staged, work, {},
            maxEntries = 100, maxMetadataBytes = 16,
            maxEntryBytes = 1_000_000, maxTotalBytes = 1_000_000
        ).unwrapErr()
        assertTrue("expected ParseFailed, got $error", error is AppError.ParseFailed)
        assertTrue((error as AppError.ParseFailed).reason.contains("too large"))
    }

    @Test fun tooManyEntriesAreRefused() {
        val bytes = cbzBytes(
            listOf(
                "1.jpg" to byteArrayOf(1),
                "2.jpg" to byteArrayOf(2),
                "3.jpg" to byteArrayOf(3)
            )
        )
        val (staged, work) = stage("comic.cbz", bytes)
        val error = CbzParser().parse(
            staged, work, {},
            maxEntries = 2, maxMetadataBytes = 1_000_000,
            maxEntryBytes = 1_000_000, maxTotalBytes = 1_000_000
        ).unwrapErr()
        assertTrue("expected ParseFailed, got $error", error is AppError.ParseFailed)
        assertTrue((error as AppError.ParseFailed).reason.contains("too many files"))
    }

    @Test fun totalExpandedOverflowIsRefused() {
        // Two 100-byte pages against a 150-byte total budget: the
        // second page crosses the cap mid-copy.
        val bytes = cbzBytes(
            listOf(
                "1.jpg" to ByteArray(100) { 1 },
                "2.jpg" to ByteArray(100) { 2 }
            )
        )
        val (staged, work) = stage("comic.cbz", bytes)
        val error = CbzParser().parse(
            staged, work, {},
            maxEntries = 100, maxMetadataBytes = 1_000_000,
            maxEntryBytes = 1_000_000, maxTotalBytes = 150
        ).unwrapErr()
        assertTrue("expected ParseFailed, got $error", error is AppError.ParseFailed)
        assertTrue((error as AppError.ParseFailed).reason.contains("too large"))
    }
}
