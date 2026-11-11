package app.xreader.data.parse

import app.xreader.domain.content.ChapterBlock
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.5 (§5): EPUB illustrations in REAL publisher shapes —
 * the structures the V154 diagnosis named and WS-7's fixture
 * set didn't carry: a standalone plate page whose body is an
 * <svg> with viewBox and an <image> carrying BOTH href and
 * xlink:href, an <img srcset> with no src, a CSS-background
 * plate, a spine item that is an image file directly, and a
 * ../ reference that only resolves against its own spine
 * item's directory. One genuinely missing image is the only
 * loss the honesty counter may report.
 */
class V155EpubPlatesTest : ParserTestBase() {

    private val plateBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x51, 1, 2, 3)
    private val srcsetBigBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x52, 4, 5, 6)
    private val srcsetSmallBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x53, 7, 8, 9)
    private val cssBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x54, 10, 11, 12)
    private val directBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x55, 13, 14, 15)
    private val svgFallbackBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 16, 17, 18)

    private fun publisherEpub(): ByteArray {
        val out = ByteArrayOutputStream()
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
            entry(
                "OEBPS/content.opf",
                """<?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="bookid">urn:v155:plates</dc:identifier>
                <dc:title>Publisher Plates</dc:title>
                <dc:creator>Plate Author</dc:creator>
                <dc:language>en</dc:language>
                </metadata>
                <manifest>
                <item id="plate1" href="xhtml/p-001.xhtml" media-type="application/xhtml+xml"/>
                <item id="chap1" href="xhtml/chapter1.xhtml" media-type="application/xhtml+xml"/>
                <item id="plate2" href="xhtml/p-002.xhtml" media-type="application/xhtml+xml"/>
                <item id="direct" href="image/direct.jpg" media-type="image/jpeg"/>
                <item id="chap2" href="xhtml/chapter2.xhtml" media-type="application/xhtml+xml"/>
                <item id="img-plate" href="image/i-001.jpg" media-type="image/jpeg"/>
                <item id="img-big" href="images/big.jpg" media-type="image/jpeg"/>
                <item id="img-small" href="images/small.jpg" media-type="image/jpeg"/>
                <item id="img-css" href="images/css.jpg" media-type="image/jpeg"/>
                <item id="img-svg" href="images/svgplate.png" media-type="image/png"/>
                </manifest>
                <spine>
                <itemref idref="plate1"/>
                <itemref idref="chap1"/>
                <itemref idref="plate2"/>
                <itemref idref="direct"/>
                <itemref idref="chap2"/>
                </spine>
                </package>""".toByteArray()
            )
            // Plate page 1: standalone SVG plate. The reference
            // ../image/i-001.jpg resolves ONLY against this
            // item's own directory (OEBPS/xhtml/) — the §5 path
            // normalisation case: OEBPS/image/i-001.jpg.
            entry(
                "OEBPS/xhtml/p-001.xhtml",
                """<?xml version="1.0"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:xlink="http://www.w3.org/1999/xlink">
                <head><title>Plate 1</title></head>
                <body><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 800 1200" width="800" height="1200"><image href="../image/i-001.jpg" xlink:href="../image/i-001.jpg" width="800" height="1200"/></svg></body></html>"""
                    .toByteArray()
            )
            // Chapter 1: a srcset-only <img> (the widest entry
            // must win) and an SVG whose plain href is stale —
            // the xlink:href fallback carries the picture.
            entry(
                "OEBPS/xhtml/chapter1.xhtml",
                """<?xml version="1.0"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:xlink="http://www.w3.org/1999/xlink">
                <head><title>Chapter 1</title></head>
                <body><h1>Chapter 1</h1>
                <p>The story opens in the harbour town at first light.</p>
                <p><img srcset="../images/small.jpg 400w, ../images/big.jpg 1200w" alt="Harbour"/></p>
                <p>Later, the storm plate:</p>
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 600 900"><image href="../images/stale.png" xlink:href="../images/svgplate.png"/></svg>
                </body></html>""".toByteArray()
            )
            // Plate page 2: the illustration exists ONLY as a
            // CSS background on a div.
            entry(
                "OEBPS/xhtml/p-002.xhtml",
                """<?xml version="1.0"?>
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Plate 2</title></head>
                <body><div class="plate" style="background-image: url('../images/css.jpg'); background-size: cover;"></div></body></html>"""
                    .toByteArray()
            )
            // Chapter 2: real text + the one genuinely missing
            // picture of the book.
            entry(
                "OEBPS/xhtml/chapter2.xhtml",
                """<?xml version="1.0"?>
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter 2</title></head>
                <body><h1>Chapter 2</h1>
                <p>The second chapter crosses the cold river valley.</p>
                <p><img src="../images/genuinely-missing.jpg" alt="Lost"/></p>
                </body></html>""".toByteArray()
            )
            entry("OEBPS/image/i-001.jpg", plateBytes)
            entry("OEBPS/image/direct.jpg", directBytes)
            entry("OEBPS/images/big.jpg", srcsetBigBytes)
            entry("OEBPS/images/small.jpg", srcsetSmallBytes)
            entry("OEBPS/images/css.jpg", cssBytes)
            entry("OEBPS/images/svgplate.png", svgFallbackBytes)
        }
        return out.toByteArray()
    }

    private fun chapterBlocks(parsed: ParsedBook, index: Int): List<ChapterBlock> {
        val file = File(parsed.chaptersDir, parsed.chapters[index].contentFileName)
        return json.decodeChapter(file.readText()).blocks
    }

    private fun imageBytes(parsed: ParsedBook, block: ChapterBlock.Image): ByteArray {
        val file = File(parsed.assetsDir, block.fileName)
        assertTrue("asset ${block.fileName} must exist", file.isFile)
        return file.readBytes()
    }

    @Test fun everyPublisherPlateShapeLandsAndOnlyTheGenuineLossIsCounted() {
        val (staged, work) = stage("plates.epub", publisherEpub())
        val parsed = EpubParser().parse(staged, work) {}.unwrap()

        // All five spine items are chapters: two plate pages,
        // two text chapters, one direct image item. Nothing
        // image-bearing was skipped.
        assertEquals(5, parsed.chapters.size)

        // Plate page 1 (SVG viewBox, dual href, ../ resolved
        // against its own directory): emitted as its own
        // illustration chapter — image block only, 0 words.
        val plate1 = chapterBlocks(parsed, 0)
        assertEquals(1, plate1.size)
        val plate1Image = plate1.single() as ChapterBlock.Image
        assertTrue(imageBytes(parsed, plate1Image).contentEquals(plateBytes))
        assertEquals(0, parsed.chapters[0].wordCount)

        // Chapter 1: the srcset's WIDEST entry and the SVG
        // xlink fallback both landed.
        val ch1Images = chapterBlocks(parsed, 1).filterIsInstance<ChapterBlock.Image>()
        assertEquals(2, ch1Images.size)
        assertTrue(imageBytes(parsed, ch1Images[0]).contentEquals(srcsetBigBytes))
        assertTrue(imageBytes(parsed, ch1Images[1]).contentEquals(svgFallbackBytes))
        assertTrue(chapterTexts(parsed)[1].contains("harbour town"))

        // Plate page 2: the CSS-background plate landed.
        val plate2 = chapterBlocks(parsed, 2)
        val plate2Images = plate2.filterIsInstance<ChapterBlock.Image>()
        assertEquals(1, plate2Images.size)
        assertTrue(imageBytes(parsed, plate2Images[0]).contentEquals(cssBytes))

        // The direct image spine item is a chapter.
        val direct = chapterBlocks(parsed, 3)
        val directImages = direct.filterIsInstance<ChapterBlock.Image>()
        assertEquals(1, directImages.size)
        assertTrue(imageBytes(parsed, directImages[0]).contentEquals(directBytes))
        assertTrue(parsed.chapters[3].title.startsWith("Illustration"))

        // Chapter 2's text is intact; its missing picture is
        // the ONLY counted loss in the whole book.
        assertTrue(chapterTexts(parsed)[4].contains("cold river valley"))
        assertEquals(1, parsed.droppedImages)
    }

    @Test fun probeSkeletonListsEverySpineItemIncludingTheDirectImage() {
        val (staged, work) = stage("plates.epub", publisherEpub())
        val skeleton = EpubParser().probe(staged, work).unwrap()
        assertEquals("Publisher Plates", skeleton.title)
        assertEquals(5, skeleton.chapterTitles.size)
        assertTrue(skeleton.chapterTitles[3].startsWith("Illustration"))
    }
}
