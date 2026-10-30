package app.xreader.data.parse

import app.xreader.data.extension.HtmlToContent
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import app.xreader.domain.reader.ChapterText
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.6.2 (F7b): paragraph structure must survive extraction on
 * BOTH paths that share XhtmlConverter — EPUB chapters and site
 * chapter HTML — including the wild shapes that used to flatten:
 * plain text with blank lines inside one element, and paragraphs
 * separated by <br/><br/>.
 */
class V1062ParagraphTest : ParserTestBase() {

    private fun paragraphsOf(content: ChapterContent): List<String> =
        content.blocks.filterIsInstance<ChapterBlock.Paragraph>().map { it.text }

    @Test fun siteParagraphsStaySeparateBlocks() {
        val content = HtmlToContent.fromFragment(
            "<p>First paragraph of the chapter.</p>" +
                "<p>Second paragraph follows here.</p>" +
                "<p>And a third one closes it.</p>",
            "https://site.example/book/1"
        )
        assertEquals(
            listOf(
                "First paragraph of the chapter.",
                "Second paragraph follows here.",
                "And a third one closes it."
            ),
            paragraphsOf(content)
        )
    }

    @Test fun sitePlainTextInOneDivSplitsOnBlankLines() {
        val content = HtmlToContent.fromFragment(
            "<div>Alpha paragraph opens the scene.\n\n" +
                "Bravo paragraph continues it.\n\n" +
                "Charlie paragraph ends it.</div>",
            "https://site.example/book/1"
        )
        assertEquals(
            listOf(
                "Alpha paragraph opens the scene.",
                "Bravo paragraph continues it.",
                "Charlie paragraph ends it."
            ),
            paragraphsOf(content)
        )
    }

    @Test fun siteDoubleBrIsAParagraphBreakSingleBrIsNot() {
        val content = HtmlToContent.fromFragment(
            "<p>One line<br/>same paragraph.</p>" +
                "<p>First block.</p>Second block after break.<br/><br/>Third block here.",
            "https://site.example/book/1"
        )
        val paragraphs = paragraphsOf(content)
        assertTrue(paragraphs.any { it.contains("One line") && it.contains("same paragraph.") })
        assertTrue(paragraphs.contains("First block."))
        assertTrue(paragraphs.contains("Second block after break."))
        assertTrue(paragraphs.contains("Third block here."))
    }

    @Test fun epubPlainTextChapterSplitsOnBlankLines() {
        val body = "<div>Opening paragraph of the EPUB chapter.\n\n" +
            "Middle paragraph with more story.\n\n" +
            "Closing paragraph of the chapter.</div>"
        val (staged, work) = stage("book.epub", epubZip(body))
        val parsed = EpubParser().parse(staged, work) {}.unwrap()
        val blocks = firstChapterBlocks(parsed)
            .filterIsInstance<ChapterBlock.Paragraph>().map { it.text }
        assertEquals(
            listOf(
                "Opening paragraph of the EPUB chapter.",
                "Middle paragraph with more story.",
                "Closing paragraph of the chapter."
            ),
            blocks
        )
    }

    @Test fun epubParagraphElementsStaySeparateBlocks() {
        val body = "<p>Para one stays its own block.</p><p>Para two as well.</p>"
        val (staged, work) = stage("book.epub", epubZip(body))
        val parsed = EpubParser().parse(staged, work) {}.unwrap()
        val blocks = firstChapterBlocks(parsed)
            .filterIsInstance<ChapterBlock.Paragraph>().map { it.text }
        assertEquals(listOf("Para one stays its own block.", "Para two as well."), blocks)
    }

    @Test fun epubFrontMatterPageIsNotAChapter() {
        // v1.0.6.2 (F7): a cover/title spine item that converts to
        // no readable blocks must not become chapter 1 — counting
        // it made every real chapter's number one higher than the
        // book's own headings.
        val (staged, work) = stage("book.epub", epubWithFrontMatter())
        val parsed = EpubParser().parse(staged, work) {}.unwrap()
        assertEquals(1, parsed.chapters.size)
        assertTrue(chapterTexts(parsed)[0].contains("The real first chapter."))
    }

    @Test fun flattenedTextJoinsBlocksWithABlankLine() {
        val text = ChapterText(
            ChapterContent(
                listOf(
                    ChapterBlock.Paragraph.plain("First."),
                    ChapterBlock.Paragraph.plain("Second.")
                )
            )
        )
        assertEquals("First.\n\nSecond.", text.text)
    }

    /** Two spine items: an empty front-matter page, then a real
     * chapter. */
    private fun epubWithFrontMatter(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>"""
            )
            entry(
                "OEBPS/content.opf",
                """<?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="bookid">urn:test:f7</dc:identifier>
                <dc:title>Front Matter Fixture</dc:title>
                <dc:language>en</dc:language>
                </metadata>
                <manifest>
                <item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/>
                <item id="chap1" href="chap1.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="cover"/><itemref idref="chap1"/></spine>
                </package>"""
            )
            entry(
                "OEBPS/cover.xhtml",
                """<?xml version="1.0"?>
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>cover</title></head>
                <body><div class="cover"></div></body></html>"""
            )
            entry(
                "OEBPS/chap1.xhtml",
                """<?xml version="1.0"?>
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>ch</title></head>
                <body><p>The real first chapter.</p></body></html>"""
            )
        }
        return out.toByteArray()
    }

    /** Minimal one-chapter EPUB around [body]. */
    private fun epubZip(body: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>"""
            )
            entry(
                "OEBPS/content.opf",
                """<?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="bookid">urn:test:f7b</dc:identifier>
                <dc:title>Paragraph Fixture</dc:title>
                <dc:language>en</dc:language>
                </metadata>
                <manifest><item id="chap1" href="chap1.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="chap1"/></spine>
                </package>"""
            )
            entry(
                "OEBPS/chap1.xhtml",
                """<?xml version="1.0"?>
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>ch</title></head>
                <body>$body</body></html>"""
            )
        }
        return out.toByteArray()
    }
}
