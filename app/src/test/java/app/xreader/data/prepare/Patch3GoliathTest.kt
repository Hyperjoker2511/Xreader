package app.xreader.data.prepare

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.xreader.core.error.AppResult
import app.xreader.data.FakeBookmarkDao
import app.xreader.data.FakeChapterDao
import app.xreader.data.FakeHighlightDao
import app.xreader.data.FakeReadingProgressDao
import app.xreader.data.db.Book
import app.xreader.data.db.BookFormat
import app.xreader.data.db.ReadingProgress
import app.xreader.data.db.SourceType
import app.xreader.data.files.BookStorage
import app.xreader.data.library.ReadingMemoryCodec
import app.xreader.data.prefs.BookReaderPrefs
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.reader.BookDocument
import app.xreader.domain.reader.ChapterText
import app.xreader.ui.localreader.LocalReaderPrefs
import app.xreader.ui.localreader.SelectionHighlightStore
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.7 Patch 3 (Item 6): the goliath round trip — the exact
 * failure the owner filmed on Patch 2 (read past the big
 * illustration, exit, reopen ON the illustration), pinned at
 * the seam the spec names: prepare → a core progress-row
 * write past the image → the restore inputs openChapterAt
 * consumes (chapter index + charOffset into ChapterText).
 * On the core road the image is a zero-width block inside
 * the chapter's text, so a char offset past it can only land
 * past it.
 */
class Patch3GoliathTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences
        ): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    private lateinit var storage: BookStorage
    private lateinit var chapterDao: FakeChapterDao
    private lateinit var progressDao: FakeReadingProgressDao

    @Before
    fun setUp() {
        storage = BookStorage(tmp.newFolder("files-${System.nanoTime()}"))
        chapterDao = FakeChapterDao()
        progressDao = FakeReadingProgressDao()
    }

    @Test
    fun placePastTheGoliathImageReopensPastTheImage() = runBlocking {
        val bookFile = tmp.newFile("goliath.epub")
        val bytes = Patch3EpubFixtures.goliathBook()
        bookFile.writeBytes(bytes)
        val book = Book(
            id = 42L,
            title = "Goliath",
            sourceType = SourceType.LOCAL_FILE,
            localFormat = BookFormat.EPUB,
            filePath = bookFile.absolutePath,
            fileFingerprint = ReadingMemoryCodec.fingerprintOf(
                "goliath.epub", bytes.size.toLong(),
                ReadingMemoryCodec.sha256Hex(bookFile)
            ),
            addedAt = 0,
            updatedAt = 0
        )
        val service = EpubPrepareService(
            chapterDao = chapterDao,
            readingProgressDao = progressDao,
            bookmarkDao = FakeBookmarkDao(),
            highlightDao = FakeHighlightDao(),
            storage = storage,
            prefs = LocalReaderPrefs(FakeDataStore()),
            bookReaderPrefs = BookReaderPrefs(FakeDataStore()),
            selectionHighlightStore = SelectionHighlightStore(FakeDataStore()),
            contentResolver = null,
            inTransaction = { block -> block() }
        )
        val outcome = service.prepare(
            book,
            EpubDetect(
                isFixedLayout = false,
                isRtlProgression = false,
                sessionHrefs = listOf(
                    "OEBPS/Text/ch1.xhtml", "OEBPS/Text/ch2.xhtml"
                )
            )
        )
        assertTrue(outcome is EpubPrepareOutcome.Redirect)

        val rows = chapterDao.forBook(book.id)
        assertEquals(2, rows.size)
        val gate = rows[1]
        val text = ChapterText(
            (storage.readChapterContent(book.id, gate.id) as AppResult.Ok).value
        )
        val blocks = text.content.blocks
        val imageIndex = blocks.indexOfFirst { it is ChapterBlock.Image }
        assertTrue("the gate chapter carries the plate", imageIndex > 0)
        val afterIndex = imageIndex + 1
        assertTrue(blocks[afterIndex] is ChapterBlock.Paragraph)
        // The image is a real content-dir asset.
        val image = blocks[imageIndex] as ChapterBlock.Image
        assertTrue(
            File(storage.contentDir(book.id), "img-${image.fileName}").isFile
        )

        // The owner reads PAST the picture and exits: the core
        // save writes the place as (chapter, charOffset) with
        // the offset inside the after-picture paragraph.
        val afterStart = text.startOf(afterIndex)
        progressDao.upsert(
            ReadingProgress(
                bookId = book.id,
                chapterId = gate.id,
                chapterIndex = 1,
                charOffset = afterStart + 5,
                fraction = 0.5f,
                scrollFraction = 0.5f,
                updatedAt = 0
            )
        )

        // Reopen: the restore inputs are the saved row's
        // (chapterIndex, charOffset) — exactly what
        // ReaderViewModel hands openChapterAt.
        val saved = progressDao.getForBook(book.id)!!
        assertEquals(1, saved.chapterIndex)
        // The block the offset lands in is the AFTER-picture
        // paragraph — not the image, not the before text.
        assertEquals(afterIndex, text.blockIndexAt(saved.charOffset))
        // And the book-level fraction machinery round-trips
        // the same place (BookDocument is what the reader's
        // percent + restore share).
        val doc = BookDocument(rows)
        rows.forEach { row ->
            val content = (storage.readChapterContent(book.id, row.id)
                as AppResult.Ok).value
            doc.setExactLength(row.id, ChapterText(content).length)
        }
        val bookOffset = doc.bookOffset(saved.chapterIndex, saved.charOffset)
        assertEquals(
            saved.chapterIndex to saved.charOffset,
            doc.locate(bookOffset)
        )
    }
}
