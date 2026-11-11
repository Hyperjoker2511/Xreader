package app.xreader.fixtures

import app.xreader.data.db.Book
import app.xreader.data.db.BookFormat
import app.xreader.data.db.SourceType
import app.xreader.data.engine.ReadiumEpubSession
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.robolectric.RuntimeEnvironment

/**
 * v1.5.10 (L-FIX, build-map D3/I5): THE reader fixture book —
 * an EPUB whose spine is shaped like the owner's real light
 * novels, so scroll/plate tests run on the shapes that wedge
 * the shipped reader (Finding-8):
 *
 *  1. `cover.xhtml` — cover XHTML, ONE viewport-fitted image
 *     (non-overflowing: a single `<img>` sized to the page,
 *     no text, nothing to scroll);
 *  2. `plate1.xhtml`, `plate2.xhtml` — per-plate front-matter
 *     items, each one image fitted to the page
 *     (non-overflowing);
 *  3. `ch1.xhtml`, `ch2.xhtml` — overflowing text chapters
 *     (dozens of paragraphs — far taller than any viewport);
 *  4. `illus.xhtml` — a mid-book non-overflowing illustration
 *     item BETWEEN two text chapters;
 *  5. `ch3.xhtml` — overflowing text chapter.
 *
 * Standing rule (I5): every reader fixture book carries a
 * non-overflowing item AND a plate item — suites import this
 * fixture, never a private two-text-chapter copy (the shape
 * `LocalReaderEpubFixture` is limited to, and which D3
 * supersedes for gate purposes).
 *
 * The file is BUILT by this module (no checked-in binary),
 * and opens through the tree's real [ReadiumEpubSession]
 * path — the same seam `LocalReaderEpubFixture` uses.
 */
object D3EpubFixture {

    const val FILE_NAME = "d3-fixture.epub"

    /** The book id [LegacyShelf.BOOK_EPUB_D3] rows carry; the
     * session opener defaults to it so shelf + book agree. */
    const val BOOK_ID = 104L

    enum class SpineKind {
        /** One viewport-fitted image, nothing scrollable. */
        FITTED_IMAGE,

        /** Text far taller than a viewport. */
        OVERFLOWING_TEXT
    }

    data class SpineItem(
        val href: String,
        val title: String,
        val kind: SpineKind
    )

    /** The spine, in reading order — the inventory gate
     * suites assert against. */
    val SPINE: List<SpineItem> = listOf(
        SpineItem("cover.xhtml", "Cover", SpineKind.FITTED_IMAGE),
        SpineItem("plate1.xhtml", "Plate 1", SpineKind.FITTED_IMAGE),
        SpineItem("plate2.xhtml", "Plate 2", SpineKind.FITTED_IMAGE),
        SpineItem("ch1.xhtml", "Chapter One", SpineKind.OVERFLOWING_TEXT),
        SpineItem("ch2.xhtml", "Chapter Two", SpineKind.OVERFLOWING_TEXT),
        SpineItem("illus.xhtml", "Illustration", SpineKind.FITTED_IMAGE),
        SpineItem("ch3.xhtml", "Chapter Three", SpineKind.OVERFLOWING_TEXT)
    )

    /** A 1×1 transparent PNG — a real image payload, so the
     * fitted-image resources are genuine image items. */
    private val PNG_BYTES: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk" +
            "YPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
    )

    private fun imageItem(href: String, title: String): String {
        val img = href.removeSuffix(".xhtml") + ".png"
        return """<?xml version="1.0" encoding="utf-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><title>$title</title>
                <meta name="viewport" content="width=device-width, height=device-height"/>
                <style type="text/css">
                  html, body { margin: 0; padding: 0; height: 100%; }
                  div.page { width: 100%; height: 100%; text-align: center; }
                  img { max-width: 100%; max-height: 100%; }
                </style>
              </head>
              <body><div class="page"><img src="img/$img" alt="$title"/></div></body>
            </html>""".trimIndent()
    }

    private fun textChapter(title: String, seed: String): String {
        val paragraphs = (1..60).joinToString("\n") { n ->
            "<p>Paragraph $n of $title. $seed The rain had been " +
                "falling since dawn over the harbour town, and the " +
                "sentinel counted the ships as they came in, one " +
                "by one, through the grey water.</p>"
        }
        return """<?xml version="1.0" encoding="utf-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><title>$title</title></head>
              <body><h1>$title</h1>
              $paragraphs
              </body></html>""".trimIndent()
    }

    /** Builds the .epub under [dir] and returns the file. */
    fun writeBook(dir: File): File {
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
            val manifestItems = SPINE.joinToString("\n") { item ->
                val id = item.href.removeSuffix(".xhtml")
                """<item id="$id" href="${item.href}" media-type="application/xhtml+xml"/>"""
            }
            val imageItems = SPINE.filter { it.kind == SpineKind.FITTED_IMAGE }
                .joinToString("\n") { item ->
                    val id = item.href.removeSuffix(".xhtml") + "-img"
                    val img = item.href.removeSuffix(".xhtml") + ".png"
                    """<item id="$id" href="img/$img" media-type="image/png"/>"""
                }
            val spineRefs = SPINE.joinToString("") { item ->
                val id = item.href.removeSuffix(".xhtml")
                """<itemref idref="$id"/>"""
            }
            entry(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
                   <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="bid" version="3.0">
                     <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                       <dc:identifier id="bid">urn:uuid:v1510-d3-fixture</dc:identifier>
                       <dc:title>D3 Fixture Book</dc:title>
                       <dc:language>en</dc:language>
                       <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
                     </metadata>
                     <manifest>
                       <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                       $manifestItems
                       $imageItems
                     </manifest>
                     <spine>$spineRefs</spine>
                   </package>""".toByteArray()
            )
            val navLinks = SPINE.joinToString("\n") { item ->
                """<li><a href="${item.href}">${item.title}</a></li>"""
            }
            entry(
                "OEBPS/nav.xhtml",
                """<?xml version="1.0" encoding="utf-8"?>
                   <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                     <head><title>Contents</title></head><body>
                       <nav epub:type="toc"><ol>
                         $navLinks
                       </ol></nav>
                     </body></html>""".toByteArray()
            )
            for (item in SPINE) {
                val body = when (item.kind) {
                    SpineKind.FITTED_IMAGE ->
                        imageItem(item.href, item.title)
                    SpineKind.OVERFLOWING_TEXT ->
                        textChapter(item.title, "Seed for ${item.href}.")
                }
                entry("OEBPS/${item.href}", body.toByteArray())
                if (item.kind == SpineKind.FITTED_IMAGE) {
                    val img = item.href.removeSuffix(".xhtml") + ".png"
                    entry("OEBPS/img/$img", PNG_BYTES)
                }
            }
        }
        val file = File(dir, FILE_NAME)
        file.writeBytes(out.toByteArray())
        return file
    }

    /** Opens [epub] (written under [root]) through the
     * production [ReadiumEpubSession] seam, as book [bookId].
     * Caller closes the session. */
    suspend fun openSession(
        root: File,
        epub: File,
        bookId: Long = BOOK_ID
    ): ReadiumEpubSession {
        val book = Book(
            id = bookId, title = "D3 Fixture Book",
            sourceType = SourceType.LOCAL_FILE, localFormat = BookFormat.EPUB,
            filePath = epub.relativeTo(root).path, addedAt = 0, updatedAt = 0
        )
        return ReadiumEpubSession.open(
            RuntimeEnvironment.getApplication(), book, root
        )
    }
}
