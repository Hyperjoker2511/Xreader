package app.xreader.ui.localreader

import android.app.Application
import app.xreader.data.db.Book
import app.xreader.data.db.BookFormat
import app.xreader.data.db.SourceType
import app.xreader.data.engine.ReadiumEpubSession
import app.xreader.data.extension.DiagnosticLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.7 Patch 2 (item 0a): the conviction test for the EPUB
 * exit→reopen place loss — does a river-written locator
 * survive the save path's serialisation and the restore
 * path's parse?
 *
 * The locator is built exactly as production builds it (the
 * session's `locatorForSection` base + the char-derived
 * progression/totalProgression copy [EpubRiverPosition]
 * applies), serialised exactly as the save path serialises it
 * (`locator.toJSON().toString()` in
 * [LocalDocReaderViewModel.onEpubLocator]), and parsed back
 * through the production restore function
 * ([ReadiumEpubSession.locatorFromJson]). Progression AND
 * totalProgression are asserted.
 *
 * The fixtures are the HOSTILE hrefs, not tame ones (the
 * existing V156 round-trip passes on tame hrefs and proves
 * nothing here): a percent-encoded chapter file, a
 * fragment-bearing href, a percent-encoded href copy. Whatever
 * this test shows, it stays as the regression pin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class Patch2EpubLocatorRoundTripTest {

    @get:Rule val temp = TemporaryFolder()

    /** A river-style locator over [base]: the section base
     * with the char-derived fractions copied in — the exact
     * shape EpubRiverPosition.locator returns and the save
     * path stores. */
    private fun riverLocator(base: Locator): Locator =
        base.copy(
            locations = base.locations.copy(
                progression = 0.42,
                totalProgression = 0.21
            )
        )

    private fun assertRoundTrip(session: ReadiumEpubSession, base: Locator) {
        val json = riverLocator(base).toJSON().toString()
        val parsed = session.locatorFromJson(json)
        assertNotNull("the stored locator must parse back: $json", parsed)
        assertEquals(
            "in-section progression survives",
            0.42, parsed!!.locations.progression!!, 0.0001
        )
        assertEquals(
            "total progression survives",
            0.21, parsed.locations.totalProgression!!, 0.0001
        )
    }

    @Test
    fun `tame href round trip preserves both progressions`() {
        val epub = LocalReaderEpubFixture.writeEpub(temp.root)
        runBlocking {
            val session = LocalReaderEpubFixture.openSession(temp.root, epub)
            try {
                val base = session.locatorForSection(1)
                assertNotNull(base)
                assertRoundTrip(session, base!!)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun `percent-encoded chapter file round trip preserves both progressions`() {
        val epub = writeEncodedEpub(temp.root)
        runBlocking {
            val session = openSession(temp.root, epub)
            try {
                val base = session.locatorForSection(1)
                assertNotNull(base)
                assertRoundTrip(session, base!!)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun `fragment-bearing href round trip preserves both progressions`() {
        val epub = LocalReaderEpubFixture.writeEpub(temp.root)
        runBlocking {
            val session = LocalReaderEpubFixture.openSession(temp.root, epub)
            try {
                val base = session.locatorForSection(1)!!
                val fragmentHref = requireNotNull(
                    Url(base.href.toString() + "#start")
                )
                assertRoundTrip(session, base.copy(href = fragmentHref))
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun `percent-encoded href round trip preserves both progressions`() {
        val epub = LocalReaderEpubFixture.writeEpub(temp.root)
        runBlocking {
            val session = LocalReaderEpubFixture.openSession(temp.root, epub)
            try {
                val base = session.locatorForSection(1)!!
                val encodedHref = requireNotNull(Url("OEBPS/ch%202.xhtml"))
                assertRoundTrip(session, base.copy(href = encodedHref))
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun `locator parse failure is logged and blank stays silent`() {
        val epub = LocalReaderEpubFixture.writeEpub(temp.root)
        runBlocking {
            val session = LocalReaderEpubFixture.openSession(temp.root, epub)
            try {
                DiagnosticLog.clear()
                assertNull(session.locatorFromJson("{not a locator"))
                assertTrue(
                    "a failed parse lands in the diagnostic log",
                    DiagnosticLog.entries().any { it.tag == "epub-locator" }
                )
                DiagnosticLog.clear()
                assertNull(session.locatorFromJson(""))
                assertNull(session.locatorFromJson(null))
                assertTrue(
                    "blank input is not a failure and stays silent",
                    DiagnosticLog.entries().none { it.tag == "epub-locator" }
                )
                // A locator that parses logs nothing.
                val base = session.locatorForSection(1)!!
                val json = riverLocator(base).toJSON().toString()
                assertNotNull(session.locatorFromJson(json))
                assertTrue(
                    "a successful parse logs nothing",
                    DiagnosticLog.entries().none { it.tag == "epub-locator" }
                )
            } finally {
                session.close()
            }
        }
    }

    // ── The hostile fixture: chapter 2's file name carries a
    // space; its manifest href is the percent-encoded form, as
    // real-world producers write it. ─────────────────────────

    private fun writeEncodedEpub(dir: File): File {
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
                       <dc:identifier id="bid">urn:uuid:patch2-encoded</dc:identifier>
                       <dc:title>Encoded Fixture</dc:title>
                       <dc:language>en</dc:language>
                       <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
                     </metadata>
                     <manifest>
                       <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                       <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                       <item id="c2" href="ch%202.xhtml" media-type="application/xhtml+xml"/>
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
                         <li><a href="ch%202.xhtml">Chapter Two</a></li>
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
                "OEBPS/ch 2.xhtml",
                """<?xml version="1.0" encoding="utf-8"?>
                   <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter Two</title></head>
                   <body><h1>Chapter Two</h1>
                   <p>By dawn the ships had gone and the water lay quiet.</p>
                   </body></html>""".toByteArray()
            )
        }
        val file = File(dir, "encoded.epub")
        file.writeBytes(out.toByteArray())
        return file
    }

    private suspend fun openSession(root: File, epub: File): ReadiumEpubSession {
        val book = Book(
            id = 7, title = "Encoded Fixture",
            sourceType = SourceType.LOCAL_FILE, localFormat = BookFormat.EPUB,
            filePath = epub.relativeTo(root).path, addedAt = 0, updatedAt = 0
        )
        return ReadiumEpubSession.open(
            RuntimeEnvironment.getApplication(), book, root
        )
    }
}
