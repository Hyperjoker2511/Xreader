package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeBookDao
import app.xreader.data.FakeChapterDao
import app.xreader.data.FakeChapterSourceMapDao
import app.xreader.data.FakeExtensionRepoDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.data.db.Book
import app.xreader.data.db.Chapter
import app.xreader.data.db.SourceType
import app.xreader.data.files.BookStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.8 (D2 rule 3, sync level): a book with REAL stored
 * chapters survives a pass that proves nothing about it.
 *
 *  - The furniture pass: the declared JSON endpoint answers
 *    a valid empty and the item-85 fallback substitutes the
 *    book page's scoped DOM rows (provenance FALLBACK). The
 *    substituted rows are NOT this book's chapters: they must
 *    never be stored, the stored list must never be
 *    drop-reconciled against them, and neither
 *    chapterListComplete nor totalChapters may be re-stamped
 *    from the pass.
 *  - The valid-empty pass: the endpoint answers
 *    `{"chapters": []}` and the DOM spec is a bare `a` sweep
 *    (unscoped, so no fallback survives). The stored list and
 *    its stamps stand exactly as they were.
 *
 * Pre-fix (tranche 6 unapplied), the furniture pass WAS
 * proven-complete (`!walkTruncated`), so refresh reconciled
 * with drop semantics: the three stored chapters were
 * deleted and replaced by the two fallback rows, stamped
 * complete with totalChapters = 2.
 */
class V158StoredListSurvivesEmptyPassTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val base = "https://api.example"
    private val bookUrl = "$base/series/my-book"
    private val endpointUrl = "$base/series/my-book/chapters?loaded=0"

    private fun config(listSelector: String) = DeclarativeConfig(
        id = "v158-stored-list",
        name = "Stored List",
        baseUrl = base,
        popular = ListSpec(
            url = "$base/list?page={page}",
            results = "",
            title = "title",
            link = "slug",
            jsonPath = "data"
        ),
        details = DetailsSpec(
            chapters = ChapterListSpec(
                list = listSelector,
                name = "@text",
                link = "@href",
                pagination = ChapterListPagination(
                    urlTemplate = "$base/series/{bookSlug}/chapters?loaded=0",
                    bookIdPattern = "",
                    jsonArrayPath = "chapters",
                    pageSize = 100
                ),
                jsonNameField = "name",
                jsonLinkField = "slug",
                jsonLinkTemplate = "$base/series/{bookSlug}/{value}"
            )
        ),
        chapter = ChapterSpec(content = "div.content")
    )

    private class World(
        val sync: SourceBookSync,
        val bookDao: FakeBookDao,
        val chapterDao: FakeChapterDao,
        val extId: Long
    )

    private suspend fun world(listSelector: String): World {
        val bookPage = """
            <html><body>
            <div class="chapters">
              <a href="/series/my-book/chapter-1">Chapter 1</a>
              <a href="/series/my-book/chapter-2">Chapter 2</a>
            </div>
            </body></html>
        """.trimIndent()
        val transport = FakeTransport { request ->
            when (request.url) {
                bookUrl -> FakeTransport.ok(bookPage)
                endpointUrl -> FakeTransport.ok("{\"chapters\": []}")
                else -> AppResult.Ok(FakeTransport.response(404, "no shape"))
            }
        }
        val bookDao = FakeBookDao()
        val chapterDao = FakeChapterDao()
        val extDao = FakeInstalledExtensionDao()
        val store = ExtensionStore(tmp.newFolder())
        val factory = HostContextFactory(store, testSessionStore(), transport)
        val service = ExtensionService(
            extDao, store, factory, FakeDexLoader()
        ) { FakeJsRuntime() }
        val manager = RepoManager(
            FakeExtensionRepoDao(), extDao, store, transport, service,
            testSessionStore()
        )
        val saved = manager.saveLinkSource(
            config(listSelector), "api.example"
        )
        val row = (saved as? AppResult.Ok)?.value
            ?: throw AssertionError("save failed: $saved")
        val sync = SourceBookSync(
            bookDao = bookDao,
            chapterDao = chapterDao,
            sourceMapDao = FakeChapterSourceMapDao(),
            extensionDao = extDao,
            service = service,
            storage = BookStorage(tmp.newFolder()),
            transport = transport,
            repairScheduler = {},
            nowMs = { 1_000L }
        )
        return World(sync, bookDao, chapterDao, row.id)
    }

    /** A book holding three REAL chapters, not yet complete. */
    private suspend fun seedStoredBook(world: World): Long {
        val bookId = world.bookDao.insert(
            Book(
                title = "My Book",
                sourceType = SourceType.EXTENSION,
                extensionId = world.extId,
                remoteId = SourceBookIds.encode("v158-stored-list", bookUrl),
                totalChapters = 3,
                chapterListComplete = false,
                addedAt = 0,
                updatedAt = 0,
                inLibrary = true
            )
        )
        world.chapterDao.insertAll(
            (1..3).map { n ->
                Chapter(
                    bookId = bookId,
                    index = n - 1,
                    title = "Real Chapter $n",
                    remoteId = "$bookUrl/real-$n",
                    updatedAt = 0
                )
            }
        )
        return bookId
    }

    private suspend fun assertStoredListSurvived(world: World, bookId: Long) {
        val stored = world.chapterDao.forBook(bookId).sortedBy { it.index }
        assertEquals(
            "the stored chapters must survive untouched, got $stored",
            listOf("Real Chapter 1", "Real Chapter 2", "Real Chapter 3"),
            stored.map { it.title }
        )
        assertEquals(
            listOf("$bookUrl/real-1", "$bookUrl/real-2", "$bookUrl/real-3"),
            stored.map { it.remoteId }
        )
        val book = world.bookDao.getById(bookId)!!
        assertFalse(
            "completeness must not be re-stamped by an unproven pass",
            book.chapterListComplete
        )
        assertEquals(
            "the total must not be re-stamped by an unproven pass",
            3, book.totalChapters
        )
    }

    @Test
    fun `furniture fallback pass never drops or replaces stored chapters`() =
        runBlocking {
            val world = world(listSelector = "div.chapters a")
            val bookId = seedStoredBook(world)
            val book = world.bookDao.getById(bookId)!!
            val result = world.sync.refresh(book)
            assertTrue("refresh failed: $result", result is AppResult.Ok)
            assertStoredListSurvived(world, bookId)
        }

    @Test
    fun `valid empty endpoint pass never drops stored chapters`() =
        runBlocking {
            val world = world(listSelector = "a")
            val bookId = seedStoredBook(world)
            val book = world.bookDao.getById(bookId)!!
            val result = world.sync.refresh(book)
            // D2's honest surface for a valid-empty endpoint
            // answer on a page that links chapters: the
            // zero-chapter guard refuses the read ("couldn't
            // be read") — the refresh Errs BEFORE any chapter
            // apply, so the stored list and its stamps stand.
            // (An Ok here would be equally acceptable; what
            // this test pins is the survival, not the shape of
            // the refusal.)
            assertTrue(
                "a valid-empty pass must land as Ok(details) " +
                    "or the honest Err, got $result",
                result is AppResult.Ok || result is AppResult.Err
            )
            assertStoredListSurvived(world, bookId)
        }
}
