package app.xreader.data.engine

import android.app.Application
import app.xreader.data.db.Book
import app.xreader.data.db.BookFormat
import app.xreader.data.db.SourceType
import app.xreader.data.parse.PdfOutlineReader
import app.xreader.data.parse.PdfStripperLoop
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.5.6 (§3 targeted checks): the engine-contract harness.
 *
 * EPUB leg is FULL: a fixture EPUB opens through the production
 * [ReadiumEpubSession], its outline enumerates, a section with an
 * illustration is reachable (the image resource reads back), its
 * text provider returns prose, and a locator round-trips
 * (save → restore lands on the same section).
 *
 * PDF leg is at the contract seam the brief allows: [PdfRenderer]
 * is a device API Robolectric cannot faithfully shadow, so the
 * JVM proof covers exactly the components [PdfSession] probes
 * with — [PdfOutlineReader] + [PdfStripperLoop] over a fixture
 * PDF with an outline — and page RENDERING is device-unproven
 * (stated in the build report, proven on the owner's phones).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V156EngineContractTest {

    @get:Rule val temp = TemporaryFolder()

    @Before fun initPdfBox() {
        PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication())
    }

    // ── Registry truth table ─────────────────────────────────────

    @Test fun registryClassifiesPopulations() {
        fun book(format: BookFormat, uri: String?, path: String?) = Book(
            title = "T", localFormat = format, sourceType = SourceType.LOCAL_FILE,
            documentUri = uri, filePath = path, addedAt = 0, updatedAt = 0
        )
        assertTrue(BookEngine.isInPlaceBook(book(BookFormat.EPUB, "content://x", null)))
        assertTrue(BookEngine.isInPlaceBook(book(BookFormat.PDF, null, "books/1/b.pdf")))
        assertTrue(!BookEngine.isInPlaceBook(book(BookFormat.CBZ, "content://x", null)))
        assertTrue(!BookEngine.isInPlaceBook(book(BookFormat.EPUB, null, null)))
        assertTrue(BookEngine.isLegacyLaneBook(book(BookFormat.EPUB, null, null)))
        assertTrue(!BookEngine.isLegacyLaneBook(book(BookFormat.PDF, null, "books/1/b.pdf")))
    }

    // ── EPUB leg ─────────────────────────────────────────────────

    private fun buildEpub(dir: File): File {
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, content: ByteArray, stored: Boolean = false) {
                val e = ZipEntry(name)
                if (stored) {
                    e.method = ZipEntry.STORED
                    e.size = content.size.toLong()
                    val crc = java.util.zip.CRC32(); crc.update(content)
                    e.crc = crc.value
                }
                zip.putNextEntry(e); zip.write(content); zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip".toByteArray(), stored = true)
            entry(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
                   <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                     <rootfiles><rootfile full-path="OEBPS/content.opf"
                       media-type="application/oebps-package+xml"/></rootfiles>
                   </container>""".toByteArray()
            )
            entry(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
                   <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="bid" version="3.0">
                     <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                       <dc:identifier id="bid">urn:uuid:engine-fixture</dc:identifier>
                       <dc:title>Engine Fixture</dc:title>
                       <dc:language>en</dc:language>
                       <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
                     </metadata>
                     <manifest>
                       <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                       <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                       <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                       <item id="plate" href="plate.png" media-type="image/png"/>
                     </manifest>
                     <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
                   </package>""".toByteArray()
            )
            entry(
                "OEBPS/nav.xhtml",
                """<?xml version="1.0" encoding="utf-8"?>
                   <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                     <head><title>Contents</title></head><body>
                       <nav epub:type="toc"><ol>
                         <li><a href="ch1.xhtml">Chapter One</a></li>
                         <li><a href="ch2.xhtml">Chapter Two</a></li>
                       </ol></nav>
                     </body></html>""".toByteArray()
            )
            entry(
                "OEBPS/ch1.xhtml",
                """<?xml version="1.0" encoding="utf-8"?>
                   <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter One</title></head>
                   <body><h1>Chapter One</h1>
                   <p>The sentinel kept watch over the harbour all night long.</p>
                   <p><img src="plate.png" alt="plate"/></p></body></html>""".toByteArray()
            )
            entry(
                "OEBPS/ch2.xhtml",
                """<?xml version="1.0" encoding="utf-8"?>
                   <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter Two</title></head>
                   <body><h1>Chapter Two</h1>
                   <p>By dawn the ships had gone and the water lay quiet.</p></body></html>"""
                    .toByteArray()
            )
            entry("OEBPS/plate.png", png)
        }
        val file = File(dir, "fixture.epub")
        file.writeBytes(out.toByteArray())
        return file
    }

    @Test fun epubOpensOutlinesAndRoundTripsLocator() = runBlocking {
        val root = temp.newFolder("files")
        val bookDir = File(root, "books/7").apply { mkdirs() }
        val epub = buildEpub(bookDir)
        val book = Book(
            id = 7, title = "Engine Fixture",
            sourceType = SourceType.LOCAL_FILE, localFormat = BookFormat.EPUB,
            filePath = "books/7/${epub.name}", addedAt = 0, updatedAt = 0
        )
        val context = RuntimeEnvironment.getApplication()
        val session = ReadiumEpubSession.open(context, book, root)
        try {
            assertEquals(2, session.sectionCount)
            assertEquals(
                listOf("Chapter One", "Chapter Two"),
                session.outline.map { it.title }
            )
            assertEquals(
                listOf("""{"section":0}""", """{"section":1}"""),
                session.outline.map { it.targetJson }
            )
            // Text provider: prose of section 1.
            assertTrue(session.textAt(0).contains("sentinel kept watch"))
            assertTrue(session.textAt(1).contains("ships had gone"))
            // The illustrated section's image resource is reachable.
            val imageLink = session.publication.resources
                .firstOrNull { it.mediaType?.isBitmap == true }
            assertTrue("illustration resource present", imageLink != null)
            val bytes = session.publication.get(imageLink!!)?.read()?.getOrNull()
            assertTrue("illustration bytes read", bytes != null && bytes.isNotEmpty())
            // Locator round-trip: save section 2's locator as JSON,
            // restore it, land on the same section.
            val locator = session.locatorForSection(1)!!
            val savedJson = locator.toJSON().toString()
            val restored = session.locatorFromJson(savedJson)!!
            assertEquals(1, session.sectionIndexOfLocator(savedJson))
            assertEquals(
                locator.href.toString(), restored.href.toString()
            )
        } finally {
            session.close()
        }
    }

    @Test fun epubMissingFileIsFileMissing() = runBlocking {
        val root = temp.newFolder("files2")
        val book = Book(
            id = 9, title = "Gone", sourceType = SourceType.LOCAL_FILE,
            localFormat = BookFormat.EPUB, filePath = "books/9/gone.epub",
            addedAt = 0, updatedAt = 0
        )
        val context = RuntimeEnvironment.getApplication()
        val error = runCatching { ReadiumEpubSession.open(context, book, root) }
            .exceptionOrNull()
        assertTrue(error is LocalFileMissingException)
    }

    // ── PDF leg (probe seam) ─────────────────────────────────────

    private fun buildPdfWithOutline(dir: File): File {
        val file = File(dir, "fixture.pdf")
        PDDocument().use { document ->
            val pages = mutableListOf<PDPage>()
            fun addPage(lines: List<String>): PDPage {
                val page = PDPage()
                document.addPage(page)
                pages += page
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 12f)
                    stream.newLineAtOffset(50f, 750f)
                    stream.setLeading(16f)
                    for (line in lines) {
                        stream.showText(line)
                        stream.newLine()
                    }
                    stream.endText()
                }
                return page
            }
            val p0 = addPage(listOf("Opening", "The plate page holds the frontispiece."))
            addPage(listOf("Middle", "Plain prose fills the middle pages fully."))
            val p2 = addPage(listOf("Finale", "The last page closes the story out."))
            val outline = PDDocumentOutline()
            document.documentCatalog.documentOutline = outline
            fun item(title: String, page: PDPage): PDOutlineItem {
                val dest = PDPageFitDestination().apply { this.page = page }
                return PDOutlineItem().apply {
                    this.title = title
                    action = PDActionGoTo().apply { destination = dest }
                }
            }
            outline.addLast(item("Opening", p0))
            outline.addLast(item("Finale", p2))
            document.save(file)
        }
        return file
    }

    @Test fun pdfProbeEnumeratesOutlineAndText() {
        val file = buildPdfWithOutline(temp.newFolder("pdf"))
        PDDocument.load(file).use { document ->
            assertEquals(3, document.numberOfPages)
            val outline = PdfOutlineReader.read(document)
            assertEquals(listOf("Opening", "Finale"), outline.map { it.title })
            assertEquals(listOf(0, 2), outline.map { it.pageIndex })
            assertEquals(
                listOf("""{"page":0}""", """{"page":2}"""),
                outline.map { LocatorJson.pdfPageTarget(it.pageIndex) }
            )
            val texts = PdfStripperLoop.strip(document)
            assertEquals(3, texts.size)
            assertTrue(texts[0].contains("frontispiece"))
            assertTrue(texts[2].contains("closes the story"))
        }
    }
}
