package app.xreader.ui.localreader

import app.xreader.data.db.Book
import app.xreader.data.db.BookFormat
import app.xreader.data.db.SourceType
import app.xreader.data.engine.ReadiumEpubSession
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.robolectric.RuntimeEnvironment

/**
 * v1.5.8: a minimal two-chapter EPUB for the local-reader tests,
 * opened through the REAL [ReadiumEpubSession] (the V156 harness
 * pattern) so preference submissions are asserted against the
 * toolkit's own editor output, not a copy. [fixedLayout] writes
 * `rendition:layout = pre-paginated` into the package metadata
 * (G10's classification input).
 */
object LocalReaderEpubFixture {

    fun writeEpub(dir: File, fixedLayout: Boolean = false): File {
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
            val layoutMeta = if (fixedLayout) {
                """<meta property="rendition:layout">pre-paginated</meta>"""
            } else {
                ""
            }
            entry(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
                   <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="bid" version="3.0">
                     <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                       <dc:identifier id="bid">urn:uuid:v158-fixture</dc:identifier>
                       <dc:title>V158 Fixture</dc:title>
                       <dc:language>en</dc:language>
                       <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
                       $layoutMeta
                     </metadata>
                     <manifest>
                       <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                       <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                       <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
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
                   </body></html>""".toByteArray()
            )
            entry(
                "OEBPS/ch2.xhtml",
                """<?xml version="1.0" encoding="utf-8"?>
                   <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter Two</title></head>
                   <body><h1>Chapter Two</h1>
                   <p>By dawn the ships had gone and the water lay quiet.</p>
                   </body></html>""".toByteArray()
            )
        }
        val file = File(dir, "fixture.epub")
        file.writeBytes(out.toByteArray())
        return file
    }

    /** Opens [epub] (written under [root]) as book 7 through the
     * production session. Caller closes the session. */
    suspend fun openSession(root: File, epub: File): ReadiumEpubSession {
        val book = Book(
            id = 7, title = "V158 Fixture",
            sourceType = SourceType.LOCAL_FILE, localFormat = BookFormat.EPUB,
            filePath = epub.relativeTo(root).path, addedAt = 0, updatedAt = 0
        )
        return ReadiumEpubSession.open(
            RuntimeEnvironment.getApplication(), book, root
        )
    }
}
