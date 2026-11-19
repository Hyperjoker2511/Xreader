package app.xreader.data.prepare

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.core.error.ErrorText
import app.xreader.data.FakeChapterDao
import app.xreader.data.FakeBookmarkDao
import app.xreader.data.FakeHighlightDao
import app.xreader.data.FakeReadingProgressDao
import app.xreader.data.db.Book
import app.xreader.data.db.BookFormat
import app.xreader.data.db.Bookmark
import app.xreader.data.db.ChapterDao
import app.xreader.data.db.DownloadState
import app.xreader.data.db.ReadingProgress
import app.xreader.data.db.SourceType
import app.xreader.data.files.BookStorage
import app.xreader.data.library.ReadingMemoryCodec
import app.xreader.data.parse.CommercialEpubFixture
import app.xreader.data.prefs.BookReaderPrefs
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.reader.ChapterText
import app.xreader.ui.localreader.EpubBookSettings
import app.xreader.ui.localreader.EpubThemePreset
import app.xreader.ui.localreader.LocalReaderPrefs
import app.xreader.ui.localreader.SelectionHighlightStore
import app.xreader.ui.localreader.StoredSelectionHighlight
import app.xreader.ui.localreader.applyPreset
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.7 Patch 3 (Item 6): the prepare pipeline end to end on
 * the tree's fakes — landing shape, the record-last rule,
 * idempotence, the DRM refusal, the failure fallback, the
 * overlay survival, the settings mapping, the place
 * migration across the divergent index spaces, and the
 * in-place marks conversion.
 */
class Patch3PrepareServiceTest {

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

    /** The record-last sabotage: content is never written, so
     * verification must fail and NO record may exist. */
    private class NoContentChapterDao(
        private val inner: FakeChapterDao
    ) : ChapterDao by inner {
        override suspend fun setContent(
            id: Long, path: String, words: Int, state: DownloadState
        ) = Unit
    }

    private lateinit var filesDir: File
    private lateinit var storage: BookStorage
    private lateinit var chapterDao: FakeChapterDao
    private lateinit var progressDao: FakeReadingProgressDao
    private lateinit var bookmarkDao: FakeBookmarkDao
    private lateinit var highlightDao: FakeHighlightDao
    private lateinit var localPrefs: LocalReaderPrefs
    private lateinit var bookReaderPrefs: BookReaderPrefs
    private lateinit var selectionStore: SelectionHighlightStore

    @Before
    fun setUp() {
        filesDir = tmp.newFolder("files-${System.nanoTime()}")
        storage = BookStorage(filesDir)
        chapterDao = FakeChapterDao()
        progressDao = FakeReadingProgressDao()
        bookmarkDao = FakeBookmarkDao()
        highlightDao = FakeHighlightDao()
        localPrefs = LocalReaderPrefs(FakeDataStore())
        bookReaderPrefs = BookReaderPrefs(FakeDataStore())
        selectionStore = SelectionHighlightStore(FakeDataStore())
    }

    private fun service(
        chapters: ChapterDao = chapterDao,
        inTransaction: suspend (suspend () -> Unit) -> Unit = { block -> block() }
    ) = EpubPrepareService(
        chapterDao = chapters,
        readingProgressDao = progressDao,
        bookmarkDao = bookmarkDao,
        highlightDao = highlightDao,
        storage = storage,
        prefs = localPrefs,
        bookReaderPrefs = bookReaderPrefs,
        selectionHighlightStore = selectionStore,
        contentResolver = null,
        inTransaction = inTransaction
    )

    /** A LOCAL_FILE EPUB book row over a real file in the
     * storage root, with an honest fingerprint. */
    private fun bookRow(bytes: ByteArray, name: String = "book.epub"): Book {
        val file = File(filesDir, name)
        file.writeBytes(bytes)
        return Book(
            id = 42L,
            title = "Fixture",
            sourceType = SourceType.LOCAL_FILE,
            localFormat = BookFormat.EPUB,
            filePath = file.absolutePath,
            fileFingerprint = ReadingMemoryCodec.fingerprintOf(
                name, bytes.size.toLong(),
                ReadingMemoryCodec.sha256Hex(file)
            ),
            addedAt = 0,
            updatedAt = 0
        )
    }

    private fun detect(
        hrefs: List<String>,
        fx: Boolean = false,
        rtl: Boolean = false
    ) = EpubDetect(isFixedLayout = fx, isRtlProgression = rtl, sessionHrefs = hrefs)

    // ── Landing ─────────────────────────────────────────────

    @Test
    fun prepareLandsRowsContentAndImages() = runBlocking {
        val book = bookRow(CommercialEpubFixture.bytes())
        val outcome = service().prepare(book, detect(emptyList()))
        assertTrue(outcome is EpubPrepareOutcome.Redirect)

        val rows = chapterDao.forBook(book.id)
        assertEquals(2, rows.size)
        for (row in rows) {
            assertNotNull(row.contentPath)
            assertTrue(row.wordCount > 0)
            assertTrue(row.title.isNotBlank())
            assertEquals(DownloadState.READY, row.downloadState)
            assertTrue(storage.resolve(row.contentPath!!).isFile)
        }
        // The chapter's images landed as content-dir assets,
        // and the content's Image blocks name files that exist.
        val contentDir = storage.contentDir(book.id)
        val imageBlocks = rows.flatMap { row ->
            val content = (storage.readChapterContent(book.id, row.id)
                as AppResult.Ok).value
            content.blocks.filterIsInstance<ChapterBlock.Image>()
        }
        assertTrue(imageBlocks.isNotEmpty())
        for (block in imageBlocks) {
            assertTrue(
                "img asset for ${block.fileName}",
                File(contentDir, "img-${block.fileName}").isFile
            )
        }
        // The record exists and is current for the file.
        val record = localPrefs.epubPrepareRecord(book.id)
        assertNotNull(record)
        assertTrue(EpubPrepareAccess.isPrepared(record, book))
    }

    @Test
    fun secondPrepareIsIdempotent() = runBlocking {
        val book = bookRow(Patch3EpubFixtures.simpleBook())
        val first = service().prepare(
            book, detect(Patch3EpubFixtures.SIMPLE_SESSION_HREFS)
        )
        assertTrue(first is EpubPrepareOutcome.Redirect)
        val rowsAfterFirst = chapterDao.forBook(book.id)
        // The core reader has since saved a core-shaped place.
        progressDao.upsert(
            ReadingProgress(
                bookId = book.id,
                chapterId = rowsAfterFirst[1].id,
                chapterIndex = 1,
                charOffset = 5,
                updatedAt = 0
            )
        )
        val second = service().prepare(
            book, detect(Patch3EpubFixtures.SIMPLE_SESSION_HREFS)
        )
        assertTrue(second is EpubPrepareOutcome.Redirect)
        assertEquals(1, (second as EpubPrepareOutcome.Redirect).chapterIndex)
        // No re-landing: the very same rows.
        assertEquals(
            rowsAfterFirst.map { it.id },
            chapterDao.forBook(book.id).map { it.id }
        )
    }

    // ── Record-last (Item 1e) ───────────────────────────────

    @Test
    fun failedVerificationWritesNoRecordThenHonestPrepareHeals() = runBlocking {
        val book = bookRow(Patch3EpubFixtures.simpleBook())
        val sabotaged = service(chapters = NoContentChapterDao(chapterDao))
        val outcome = sabotaged.prepare(
            book, detect(Patch3EpubFixtures.SIMPLE_SESSION_HREFS)
        )
        assertTrue(
            "sabotaged landing must fall back, was $outcome",
            outcome is EpubPrepareOutcome.Fallback
        )
        assertNull(localPrefs.epubPrepareRecord(book.id))

        // The same stores, an honest DAO: prepare completes and
        // the book is not stranded with duplicate rows.
        val healed = service().prepare(
            book, detect(Patch3EpubFixtures.SIMPLE_SESSION_HREFS)
        )
        assertTrue(healed is EpubPrepareOutcome.Redirect)
        assertEquals(3, chapterDao.forBook(book.id).size)
        assertNotNull(localPrefs.epubPrepareRecord(book.id))
    }

    @Test
    fun throwingTransactionWritesNoRecord() = runBlocking {
        val book = bookRow(Patch3EpubFixtures.simpleBook())
        val outcome = service(
            inTransaction = { throw IllegalStateException("disk gone") }
        ).prepare(book, detect(Patch3EpubFixtures.SIMPLE_SESSION_HREFS))
        assertTrue(outcome is EpubPrepareOutcome.Fallback)
        assertNull(localPrefs.epubPrepareRecord(book.id))
    }

    // ── Refusals and fallbacks (Items 1b, 1f) ───────────────

    @Test
    fun drmBookIsRefusedWithTheHonestWording() = runBlocking {
        val book = bookRow(Patch3EpubFixtures.drmBook())
        val outcome = service().prepare(book, detect(emptyList()))
        assertTrue(outcome is EpubPrepareOutcome.Refused)
        assertEquals(
            ErrorText.of(AppError.DrmProtected).message,
            (outcome as EpubPrepareOutcome.Refused).message
        )
        assertTrue(chapterDao.forBook(book.id).isEmpty())
        assertNull(localPrefs.epubPrepareRecord(book.id))
    }

    @Test
    fun fontOnlyEncryptionIsNotRefused() = runBlocking {
        val book = bookRow(Patch3EpubFixtures.fontDrmBook())
        val outcome = service().prepare(book, detect(emptyList()))
        assertTrue(outcome is EpubPrepareOutcome.Redirect)
    }

    @Test
    fun fixedLayoutAndRtlAreNotEligible() = runBlocking {
        val book = bookRow(Patch3EpubFixtures.simpleBook())
        // Item 3f: an FXL/RTL book's place NEVER migrates —
        // its locator row stays exactly as it was.
        val locatorRow = ReadingProgress(
            bookId = book.id,
            chapterId = 0,
            chapterIndex = 3,
            charOffset = 0,
            scrollFraction = 0.25f,
            updatedAt = 7,
            locatorJson = Patch3EpubFixtures.locatorJson(
                "OEBPS/Text/ch1.xhtml", 0.25f
            ),
            anchorText = "One"
        )
        progressDao.upsert(locatorRow)
        assertEquals(
            EpubPrepareOutcome.NotEligible,
            service().prepare(book, detect(emptyList(), fx = true))
        )
        assertEquals(
            EpubPrepareOutcome.NotEligible,
            service().prepare(book, detect(emptyList(), rtl = true))
        )
        assertTrue(chapterDao.forBook(book.id).isEmpty())
        assertNull(localPrefs.epubPrepareRecord(book.id))
        assertEquals(locatorRow, progressDao.getForBook(book.id))
    }

    @Test
    fun corruptFileFallsBackAndStaysUnprepared() = runBlocking {
        val book = bookRow(byteArrayOf(1, 2, 3, 4, 5))
        val outcome = service().prepare(book, detect(emptyList()))
        assertTrue(outcome is EpubPrepareOutcome.Fallback)
        assertTrue(chapterDao.forBook(book.id).isEmpty())
        assertNull(localPrefs.epubPrepareRecord(book.id))
    }

    // ── Overlays (Item 4h) ──────────────────────────────────

    @Test
    fun smilOverlaySurvivesLanding() = runBlocking {
        val book = bookRow(Patch3EpubFixtures.smilBook())
        val outcome = service().prepare(book, detect(emptyList()))
        assertTrue(outcome is EpubPrepareOutcome.Redirect)
        val row = chapterDao.forBook(book.id).single()
        val content = (storage.readChapterContent(book.id, row.id)
            as AppResult.Ok).value
        val overlay = content.mediaOverlay
        assertNotNull(overlay)
        assertEquals(2, overlay!!.clips.size)
        // The audio asset sits in the content dir under the
        // name the core's overlay player looks up.
        assertTrue(
            File(storage.contentDir(book.id), "img-${overlay.audioFileName}")
                .isFile
        )
    }

    // ── Settings mapping (Item 4g) ──────────────────────────

    @Test
    fun storedSepiaSettingsMapToTheCoreOverride() = runBlocking {
        val book = bookRow(Patch3EpubFixtures.simpleBook())
        localPrefs.saveEpub(
            book.id,
            EpubBookSettings().applyPreset(EpubThemePreset.SEPIA)
        )
        val outcome = service().prepare(
            book, detect(Patch3EpubFixtures.SIMPLE_SESSION_HREFS)
        )
        assertTrue(outcome is EpubPrepareOutcome.Redirect)
        val override = bookReaderPrefs.overrides(book.id).first()
        assertEquals("Sepia", override.paletteName)
    }

    @Test
    fun untouchedBookKeepsItsCoreOverridesAlone() = runBlocking {
        val book = bookRow(Patch3EpubFixtures.simpleBook())
        bookReaderPrefs.setPalette(book.id, "Paper")
        val outcome = service().prepare(
            book, detect(Patch3EpubFixtures.SIMPLE_SESSION_HREFS)
        )
        assertTrue(outcome is EpubPrepareOutcome.Redirect)
        // No stored EPUB settings → the mapping never ran.
        assertEquals("Paper", bookReaderPrefs.overrides(book.id).first().paletteName)
    }

    // ── Place migration (Item 3a, the B1 divergence) ────────

    @Test
    fun sessionSpacePlaceMigratesByHrefNotByIndex() = runBlocking {
        val book = bookRow(Patch3EpubFixtures.divergentBook())
        // The saved place is SESSION-shaped: chapterIndex 2 is
        // "second" in session space but "appendix" in parser
        // space; the locator href names "second" outright.
        progressDao.upsert(
            ReadingProgress(
                bookId = book.id,
                chapterId = 0,
                chapterIndex = 2,
                charOffset = 0,
                fraction = 0f,
                scrollFraction = 0.5f,
                updatedAt = 0,
                locatorJson = Patch3EpubFixtures.locatorJson(
                    "OEBPS/Text/second.xhtml", 0.5f
                ),
                anchorText = "Second"
            )
        )
        val outcome = service().prepare(
            book, detect(Patch3EpubFixtures.DIVERGENT_SESSION_HREFS)
        )
        assertTrue(outcome is EpubPrepareOutcome.Redirect)
        assertEquals(1, (outcome as EpubPrepareOutcome.Redirect).chapterIndex)

        val rows = chapterDao.forBook(book.id)
        assertEquals(3, rows.size) // blank skipped, appendix kept
        val saved = progressDao.getForBook(book.id)
        assertNotNull(saved)
        assertEquals(1, saved!!.chapterIndex)
        assertEquals(rows[1].id, saved.chapterId)
        // The in-section fraction (0.5) applied to the
        // converted chapter's text length.
        val content = (storage.readChapterContent(book.id, rows[1].id)
            as AppResult.Ok).value
        val length = ChapterText(content).length
        assertEquals((0.5f * length).toInt(), saved.charOffset)
    }

    // ── Re-prepare (Item 3d) ────────────────────────────────

    @Test
    fun fingerprintChangeRepreparesAndCarriesTheCorePlace() = runBlocking {
        val v1 = bookRow(Patch3EpubFixtures.simpleBook(), name = "book.epub")
        val first = service().prepare(
            v1, detect(Patch3EpubFixtures.SIMPLE_SESSION_HREFS)
        )
        assertTrue(first is EpubPrepareOutcome.Redirect)
        val rowsV1 = chapterDao.forBook(v1.id)
        // The owner read into chapter 2 on the core road.
        val chapter2Text = ChapterText(
            (storage.readChapterContent(v1.id, rowsV1[1].id) as AppResult.Ok).value
        )
        progressDao.upsert(
            ReadingProgress(
                bookId = v1.id,
                chapterId = rowsV1[1].id,
                chapterIndex = 1,
                charOffset = chapter2Text.length / 2,
                fraction = 0.4f,
                scrollFraction = 0.5f,
                updatedAt = 0
            )
        )

        // The file is replaced (longer chapter 2), fingerprint
        // updated as a re-point would.
        val v2Bytes = Patch3EpubFixtures.book(
            listOf(
                Patch3EpubFixtures.Item(
                    "ch1", "Text/ch1.xhtml",
                    "<h1>One</h1><p>Alpha opens the tale beside the grey harbour.</p>"
                ),
                Patch3EpubFixtures.Item(
                    "ch2", "Text/ch2.xhtml",
                    "<h1>Two</h1><p>Bravo crosses the river valley under a low " +
                        "sky, and the road winds on far beyond the ferry " +
                        "landing before it finally rests.</p>"
                ),
                Patch3EpubFixtures.Item(
                    "ch3", "Text/ch3.xhtml",
                    "<h1>Three</h1><p>Charlie climbs the hill road.</p>"
                )
            )
        )
        val file = File(filesDir, "book.epub")
        file.writeBytes(v2Bytes)
        val v2 = v1.copy(
            fileFingerprint = ReadingMemoryCodec.fingerprintOf(
                "book.epub", v2Bytes.size.toLong(),
                ReadingMemoryCodec.sha256Hex(file)
            )
        )
        val second = service().prepare(
            v2, detect(Patch3EpubFixtures.SIMPLE_SESSION_HREFS)
        )
        assertTrue(second is EpubPrepareOutcome.Redirect)
        assertEquals(1, (second as EpubPrepareOutcome.Redirect).chapterIndex)

        val rowsV2 = chapterDao.forBook(v2.id)
        assertEquals(3, rowsV2.size)
        assertNotEquals(rowsV1[1].id, rowsV2[1].id)
        val saved = progressDao.getForBook(v2.id)
        assertNotNull(saved)
        assertEquals(1, saved!!.chapterIndex)
        assertEquals(rowsV2[1].id, saved.chapterId)
        // The offset kept its fraction of the chapter (≈half).
        val newText = ChapterText(
            (storage.readChapterContent(v2.id, rowsV2[1].id) as AppResult.Ok).value
        )
        val ratio = saved.charOffset.toDouble() / newText.length
        assertTrue("offset ratio $ratio", ratio in 0.4..0.6)
        // And the record now carries the NEW fingerprint.
        assertTrue(EpubPrepareAccess.isPrepared(
            localPrefs.epubPrepareRecord(v2.id), v2
        ))
    }

    // ── Marks conversion (Item 3e) ──────────────────────────

    @Test
    fun marksConvertInPlaceAndUnplaceableOnesAreCounted() = runBlocking {
        val book = bookRow(Patch3EpubFixtures.simpleBook())
        // A bookmark whose locator carries a quote from
        // chapter 2's text ("ferry lantern").
        val bookmarkId = bookmarkDao.insert(
            Bookmark(
                bookId = book.id,
                chapterId = 0,
                chapterIndex = 1,
                createdAt = 0,
                updatedAt = 0,
                locatorJson = """{"href":"OEBPS/Text/ch2.xhtml",""" +
                    """"locations":{"progression":0.1},""" +
                    """"text":{"highlight":"ferry lantern"}}"""
            )
        )
        // A bookmark that names nothing real: unplaceable.
        val lostId = bookmarkDao.insert(
            Bookmark(
                bookId = book.id,
                chapterId = 0,
                chapterIndex = 99,
                createdAt = 0,
                updatedAt = 0,
                locatorJson = Patch3EpubFixtures.locatorJson(
                    "OEBPS/Text/gone.xhtml", 0.5f
                )
            )
        )
        // A DB highlight row (locator-shaped) quoting
        // chapter 1's "grey harbour".
        val dbHighlightId = highlightDao.insert(
            app.xreader.data.db.Highlight(
                bookId = book.id,
                chapterId = 0,
                startOffset = 0,
                endOffset = 0,
                quotedText = "grey harbour",
                createdAt = 0,
                updatedAt = 0,
                locatorJson = Patch3EpubFixtures.locatorJson(
                    "OEBPS/Text/ch1.xhtml", 0.3f
                )
            )
        )
        // A river selection-store highlight quoting chapter 3.
        selectionStore.save(
            book.id,
            listOf(
                StoredSelectionHighlight(
                    id = "sel-1",
                    locatorJson = Patch3EpubFixtures.locatorJson(
                        "OEBPS/Text/ch3.xhtml", 0.2f
                    ),
                    selectedText = "the whole coast",
                    createdAtEpochMillis = 1234L
                )
            )
        )

        val outcome = service().prepare(
            book, detect(Patch3EpubFixtures.SIMPLE_SESSION_HREFS)
        )
        assertTrue(outcome is EpubPrepareOutcome.Redirect)
        val redirect = outcome as EpubPrepareOutcome.Redirect
        assertEquals(3, redirect.marksConverted)
        assertEquals(1, redirect.marksUnplaced)

        val rows = chapterDao.forBook(book.id)
        val bookmarks = bookmarkDao.forBook(book.id)
        assertEquals(2, bookmarks.size) // flipped in place, never copied
        val placed = bookmarks.single { it.id == bookmarkId }
        assertNull(placed.locatorJson)
        assertEquals(rows[1].id, placed.chapterId)
        val chapter2 = ChapterText(
            (storage.readChapterContent(book.id, rows[1].id) as AppResult.Ok).value
        )
        assertEquals(
            chapter2.text.indexOf("ferry lantern"), placed.charOffset
        )
        // The unplaceable one keeps its old shape, untouched.
        val lost = bookmarks.single { it.id == lostId }
        assertNotNull(lost.locatorJson)

        // The DB highlight flipped in place by its quote.
        val highlights = highlightDao.forBook(book.id)
        assertEquals(2, highlights.size)
        val dbHighlight = highlights.single { it.id == dbHighlightId }
        assertNull(dbHighlight.locatorJson)
        assertEquals(rows[0].id, dbHighlight.chapterId)
        val chapter1 = ChapterText(
            (storage.readChapterContent(book.id, rows[0].id) as AppResult.Ok).value
        )
        assertEquals(
            chapter1.text.indexOf("grey harbour"), dbHighlight.startOffset
        )
        assertEquals(
            dbHighlight.startOffset + "grey harbour".length,
            dbHighlight.endOffset
        )
        // The selection-store entry became a core highlight row.
        val highlight = highlights.single { it.quotedText == "the whole coast" }
        assertNull(highlight.locatorJson)
        assertEquals(rows[2].id, highlight.chapterId)
        assertEquals(1234L, highlight.createdAt)
        val chapter3 = ChapterText(
            (storage.readChapterContent(book.id, rows[2].id) as AppResult.Ok).value
        )
        assertEquals(
            chapter3.text.indexOf("the whole coast"), highlight.startOffset
        )
        // The store itself was never written by prepare.
        assertEquals(1, selectionStore.highlights(book.id).first().size)
    }
}
