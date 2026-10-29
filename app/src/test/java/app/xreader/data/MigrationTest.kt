package app.xreader.data

import app.xreader.core.error.AppResult
import app.xreader.data.db.InstalledExtension
import app.xreader.data.migration.CsvLibraryParser
import app.xreader.data.migration.JsonLibraryParser
import app.xreader.data.migration.MigrationParsers
import app.xreader.data.migration.MigrationService
import app.xreader.data.migration.TachibkParser
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6 gate (PLAN §8): the Migration Studio. A hand-built
 * .tachibk (gzip + protobuf, Mihon's pinned field map) must parse
 * into the shared model; the service must match sources by name,
 * import unmatched books anyway (listed, never dropped), merge
 * read state into books already present, and never duplicate.
 */
class MigrationTest {

    // -- a tiny protobuf writer, the mirror of ProtoWire -----------

    private class ProtoWriter {
        private val out = ByteArrayOutputStream()

        private fun varint(value: Long) {
            var v = value
            while (true) {
                val bits = (v and 0x7F).toInt()
                v = v ushr 7
                if (v == 0L) {
                    out.write(bits)
                    return
                }
                out.write(bits or 0x80)
            }
        }

        fun varintField(number: Int, value: Long): ProtoWriter {
            varint((number shl 3).toLong())
            varint(value)
            return this
        }

        fun bytesField(number: Int, bytes: ByteArray): ProtoWriter {
            varint(((number shl 3) or 2).toLong())
            varint(bytes.size.toLong())
            out.write(bytes)
            return this
        }

        fun stringField(number: Int, value: String): ProtoWriter =
            bytesField(number, value.toByteArray(Charsets.UTF_8))

        fun floatField(number: Int, value: Float): ProtoWriter {
            varint(((number shl 3) or 5).toLong())
            val bits = value.toRawBits()
            out.write(bits and 0xFF)
            out.write((bits shr 8) and 0xFF)
            out.write((bits shr 16) and 0xFF)
            out.write((bits shr 24) and 0xFF)
            return this
        }

        fun build(): ByteArray = out.toByteArray()
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    /** One manga, two chapters (first read), one category, one source. */
    private fun tachibkFixture(): ByteArray {
        val chapter1 = ProtoWriter()
            .stringField(1, "/alpha/ch1").stringField(2, "Chapter 1")
            .varintField(4, 1).floatField(9, 1f).varintField(10, 0).build()
        val chapter2 = ProtoWriter()
            .stringField(1, "/alpha/ch2").stringField(2, "Chapter 2")
            .varintField(4, 0).floatField(9, 2f).varintField(10, 1).build()
        val manga = ProtoWriter()
            .varintField(1, 777)
            .stringField(2, "/novel/alpha")
            .stringField(3, "Alpha Novel")
            .stringField(5, "Author A")
            .varintField(8, 2)
            .bytesField(16, chapter1).bytesField(16, chapter2)
            .varintField(17, 0)
            .build()
        val category = ProtoWriter().stringField(1, "Favourites")
            .varintField(2, 0).build()
        val source = ProtoWriter().stringField(1, "NovelSite")
            .varintField(2, 777).build()
        val backup = ProtoWriter()
            .bytesField(1, manga)
            .bytesField(2, category)
            .bytesField(101, source)
            .build()
        return gzip(backup)
    }

    @Test
    fun `tachibk parses into the shared model`() {
        val result = TachibkParser.parse(tachibkFixture())
        assertTrue(result is AppResult.Ok)
        val library = (result as AppResult.Ok).value
        assertEquals(listOf("Favourites"), library.categories)
        assertEquals(1, library.books.size)
        val book = library.books.single()
        assertEquals("Alpha Novel", book.title)
        assertEquals("Author A", book.author)
        assertEquals("NovelSite", book.sourceName)
        assertEquals(777L, book.sourceId)
        assertEquals(listOf("Favourites"), book.categoryNames)
        assertEquals(2, book.chapters.size)
        assertTrue(book.chapters[0].read)
        assertEquals(false, book.chapters[1].read)
        assertEquals(1f, book.chapters[0].number)
    }

    @Test
    fun `the sniffer routes tachibk bytes to the protobuf parser`() {
        val result = MigrationParsers.parse(tachibkFixture())
        assertTrue(result is AppResult.Ok)
        assertEquals(1, (result as AppResult.Ok).value.books.size)
    }

    @Test
    fun `legacy mihon json parses with read state`() {
        val json = """
            {"mangas": [
              {"manga": {"title": "Beta Tale", "author": "Bee", "source": 42,
                          "sourceName": "OtherSite", "url": "/beta"},
               "chapters": [{"url": "/beta/1", "name": "One", "read": true},
                            {"url": "/beta/2", "name": "Two", "read": false}],
               "categories": []}
            ],
             "categories": [{"name": "Reading"}]}
        """.trimIndent()
        val result = JsonLibraryParser.parse(json)
        assertTrue(result is AppResult.Ok)
        val library = (result as AppResult.Ok).value
        assertEquals(listOf("Reading"), library.categories)
        val book = library.books.single()
        assertEquals("Beta Tale", book.title)
        assertEquals("OtherSite", book.sourceName)
        assertTrue(book.chapters[0].read)
    }

    @Test
    fun `generic json and csv lists parse`() {
        val json = """[{"title": "Gamma", "author": "Gee", "source": "SiteG"}]"""
        val fromJson = JsonLibraryParser.parse(json)
        assertTrue(fromJson is AppResult.Ok)
        assertEquals("Gamma", (fromJson as AppResult.Ok).value.books.single().title)

        val csv = "title,author,source\n\"Delta, Part One\",Dee,SiteD\nEpsilon,,SiteE\n"
        val fromCsv = CsvLibraryParser.parse(csv)
        assertTrue(fromCsv is AppResult.Ok)
        val books = (fromCsv as AppResult.Ok).value.books
        assertEquals(2, books.size)
        assertEquals("Delta, Part One", books[0].title) // quoted comma survives
        assertEquals("SiteE", books[1].sourceName)
    }

    @Test
    fun `garbage input is an honest error, never a crash`() {
        assertTrue(TachibkParser.parse(byteArrayOf(1, 2, 3)) is AppResult.Err)
        assertTrue(JsonLibraryParser.parse("{not json") is AppResult.Err)
        assertTrue(CsvLibraryParser.parse("no commas here") is AppResult.Err)
    }

    // -- the service -------------------------------------------------

    private class Rig {
        val bookDao = FakeBookDao()
        val chapterDao = FakeChapterDao()
        val progressDao = FakeReadingProgressDao()
        val extensionDao = FakeInstalledExtensionDao()
        val service = MigrationService(
            bookDao, chapterDao, progressDao, extensionDao,
            clock = { 1_000L }
        )
    }

    @Test
    fun `apply matches by source name and lists the unmatched`() = runTest {
        val rig = Rig()
        rig.extensionDao.insert(
            InstalledExtension(
                repoId = 1, displayName = "NovelSite", packageId = "site.novel",
                versionName = "1", versionCode = 1, sha256 = "x",
                apiLevel = 1, installedAt = 1, updatedAt = 1
            )
        )
        val library = (TachibkParser.parse(tachibkFixture()) as AppResult.Ok).value
        val extra = library.copy(
            books = library.books + app.xreader.data.migration.MigratedBook(
                title = "Orphan Book", sourceName = "MissingSite",
                url = "/orphan"
            )
        )
        val preview = rig.service.preview(extra)
        assertEquals(2, preview.totalBooks)
        assertEquals(1, preview.matchedBooks)
        assertEquals(listOf("Orphan Book"), preview.unmatchedTitles)

        val report = (rig.service.apply(extra) as AppResult.Ok).value
        assertEquals(2, report.addedBooks)
        assertEquals(1, report.matchedBooks)
        assertEquals(listOf("Orphan Book"), report.unmatchedTitles)
        assertEquals(listOf("Favourites"), report.categoriesNoted)
        assertTrue(report.lines().any { it.contains("couldn't be linked") })

        // Both books are IN the library; the matched one is linked.
        val books = rig.bookDao.allBooks()
        assertEquals(2, books.size)
        val alpha = books.first { it.title == "Alpha Novel" }
        assertTrue(alpha.extensionId != null)
        assertEquals("/novel/alpha", alpha.remoteId)
        // Chapters arrived with their read marks + a progress row.
        val chapters = rig.chapterDao.forBook(alpha.id)
        assertEquals(2, chapters.size)
        assertTrue(chapters[0].read)
        assertEquals(0, rig.progressDao.getForBook(alpha.id)?.chapterIndex)
    }

    @Test
    fun `re-importing merges read marks instead of duplicating`() = runTest {
        val rig = Rig()
        val library = (TachibkParser.parse(tachibkFixture()) as AppResult.Ok).value
        rig.service.apply(library)
        val again = (rig.service.apply(library) as AppResult.Ok).value
        assertEquals(0, again.addedBooks)
        assertEquals(1, again.alreadyPresent)
        assertEquals(1, rig.bookDao.allBooks().size)

        // A backup where chapter 2 is now read merges into the
        // existing rows by chapter address.
        val progressed = library.copy(
            books = library.books.map { book ->
                book.copy(
                    chapters = book.chapters.map { it.copy(read = true) }
                )
            }
        )
        rig.service.apply(progressed)
        val alpha = rig.bookDao.allBooks().single()
        assertTrue(rig.chapterDao.forBook(alpha.id).all { it.read })
    }
}
