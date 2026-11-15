package app.xreader.data.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.xreader.core.error.AppResult
import app.xreader.data.FakeBookDao
import app.xreader.data.FakeChapterDao
import app.xreader.data.FakeDownloadQueueDao
import app.xreader.data.db.Book
import app.xreader.data.db.SourceType
import app.xreader.data.download.DownloadController
import app.xreader.data.files.BookStorage
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.1 (item 2, Library wedge exhibit 2026-11-02 17:47): on
 * v1.5.0 the Library home wedged around ONE book's refresh —
 * the Asura book's cover spun forever and every button was
 * dead. The mechanism: LibraryRefresher ran each book's sync
 * UNBOUNDED, so a refresh that never returned held its source
 * group, refreshAll's awaitAll, and the Library's update
 * state hostage with no terminal state.
 *
 * This is the never-finishing-render fixture's Library shape:
 * one book's refresh never completes; the sweep must still
 * terminate inside its per-book bound, count that book, and
 * refresh every other book — including the one queued BEHIND
 * the hung book in its own source group.
 *
 * v1.5.3 (item 26, gate-repair): the COUNTING changed — a
 * per-book TIMEOUT is now a SKIP (skipped = 1, failures = 0)
 * and the sweep reports success-with-partial, superseding
 * this test's v1.5.1 "count that book as a failure" pin. The
 * wedge assertions themselves (the sweep terminates; every
 * other book refreshes) are unchanged.
 *
 * v1.6.0 (library-refresh scope fix): the BADGE counting
 * changed again, by the same supersession logic. This
 * fixture's books are all first-sight (never baselined), and
 * a first-sight book's first completed sweep is now
 * badge-silent — the first-sight rule writes its baseline at
 * the post-refresh total and books nothing as new, because
 * its stored total may be a partial progressive chunk
 * (finding #2's shape). The healthy books below still
 * refresh and grow; on this first sweep they report
 * booksWithNew = 0 / totalNew = 0. Baselined growth counting
 * is pinned by the scope-fix tests at the bottom of this
 * file (the second-sweep test) — and the wedge assertions
 * above are unchanged again.
 */
class V151LibraryRefreshTest {

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

    private fun book(
        id: Long,
        ext: Long,
        chapters: Int,
        inLibrary: Boolean = true
    ) = Book(
        id = id,
        title = "Book $id",
        sourceType = SourceType.EXTENSION,
        extensionId = ext,
        remoteId = "remote-$id",
        totalChapters = chapters,
        inLibrary = inLibrary,
        addedAt = 0,
        updatedAt = 0
    )

    private fun buildRefresher(
        dao: FakeBookDao,
        metaPrefs: BookMetaPrefs,
        perBookTimeoutMs: Long =
            LibraryRefresher.REFRESH_PER_BOOK_TIMEOUT_MS,
        refreshBook: suspend (Book) -> AppResult<Book>
    ) = LibraryRefresher(
        bookDao = dao,
        bookMetaPrefs = metaPrefs,
        refreshBook = refreshBook,
        libraryPrefs = LibraryPrefs(FakeDataStore()),
        downloadController = DownloadController(
            queueDao = FakeDownloadQueueDao(),
            chapterDao = FakeChapterDao(),
            bookDao = dao,
            storage = BookStorage(tmp.newFolder()),
            enqueuer = { }
        ),
        perBookTimeoutMs = perBookTimeoutMs
    )

    @Test
    fun `a never finishing per-book refresh times out and the sweep completes`() =
        runBlocking {
            val dao = FakeBookDao()
            // Source 7: book 1 hangs forever; book 2 queues
            // behind it in the same serial group. Source 9:
            // book 3 is independent.
            dao.books.value = listOf(
                book(1, 7, 10), book(2, 7, 20), book(3, 9, 30)
            )
            val refreshed = mutableListOf<Long>()
            val refresher = LibraryRefresher(
                bookDao = dao,
                bookMetaPrefs = BookMetaPrefs(FakeDataStore()),
                refreshBook = { book ->
                    if (book.id == 1L) {
                        // The hang: never returns (cancellable
                        // delay — the timeout must cut it).
                        delay(Long.MAX_VALUE)
                        AppResult.Ok(book)
                    } else {
                        refreshed += book.id
                        val grown = book.copy(
                            totalChapters = book.totalChapters + 2
                        )
                        dao.update(grown)
                        AppResult.Ok(grown)
                    }
                },
                libraryPrefs = LibraryPrefs(FakeDataStore()),
                downloadController = DownloadController(
                    queueDao = FakeDownloadQueueDao(),
                    chapterDao = FakeChapterDao(),
                    bookDao = dao,
                    storage = BookStorage(tmp.newFolder()),
                    enqueuer = { }
                ),
                perBookTimeoutMs = 300
            )
            val outcome = withTimeout(15_000) { refresher.refreshAll() }
            assertEquals("all three books were attempted", 3, outcome.booksChecked)
            assertEquals(
                "the hung book is a skip, not a failure or a wedge",
                0, outcome.failures
            )
            assertEquals(
                "the hung book is counted as skipped, not a wedge",
                1, outcome.skipped
            )
            assertEquals(
                "first-sight books book nothing as new on " +
                    "their first sweep (v1.6.0 first-sight rule)",
                0, outcome.booksWithNew
            )
            assertEquals(0, outcome.totalNew)
            assertTrue(
                "the book queued behind the hung one still refreshed",
                refreshed.containsAll(listOf(2L, 3L))
            )
        }

    // ---------------------------------------------------------------
    // v1.6.0 (library-refresh scope fix): the sweep's scope and
    // the first-sight rule that replaced the wide sweep's
    // baseline-keeping.
    // ---------------------------------------------------------------

    @Test
    fun `the sweep refreshes library books only`() = runBlocking {
        val dao = FakeBookDao()
        // Two sources; each holds one Library book and one
        // preview/history row (inLibrary = false — every book
        // ever opened has such a row).
        dao.books.value = listOf(
            book(1, 7, 10, inLibrary = true),
            book(2, 7, 20, inLibrary = false),
            book(3, 9, 30, inLibrary = true),
            book(4, 9, 40, inLibrary = false)
        )
        val invoked = CopyOnWriteArrayList<Long>()
        val refresher = buildRefresher(dao, BookMetaPrefs(FakeDataStore())) { book ->
            invoked += book.id
            AppResult.Ok(book)
        }
        val outcome = refresher.refreshAll()
        assertEquals(
            "the refresh seam ran once per Library book, " +
                "never for a non-Library row",
            setOf(1L, 3L), invoked.toSet()
        )
        assertEquals(
            "booksChecked counts Library books only",
            2, outcome.booksChecked
        )
        assertEquals(0, outcome.failures)
        assertEquals(0, outcome.skipped)
    }

    @Test
    fun `a first-sight library book baselines silently at the post-refresh total`() =
        runBlocking {
            val dao = FakeBookDao()
            // The phantom shape (finding #2): a Library book
            // stored at a PARTIAL total of 40 (a progressive
            // chunk); the source's full list is 900. The book
            // has never been baselined.
            dao.books.value = listOf(book(1, 7, 40, inLibrary = true))
            val metaPrefs = BookMetaPrefs(FakeDataStore())
            val refresher = buildRefresher(dao, metaPrefs) { book ->
                val grown = book.copy(totalChapters = 900)
                dao.update(grown)
                AppResult.Ok(grown)
            }
            val outcome = refresher.refreshAll()
            val meta = metaPrefs.get(1)
            assertEquals(
                "the baseline lands at the post-refresh total",
                900, meta.lastSeenChapterCount
            )
            assertEquals(
                "nothing books as new — no 860-chapter phantom",
                0, meta.newChapterCount
            )
            assertEquals(1, outcome.booksChecked)
            assertEquals(0, outcome.booksWithNew)
            assertEquals(0, outcome.totalNew)
            assertEquals(0, outcome.failures)
        }

    @Test
    fun `a baselined book badges exactly the growth on the next sweep`() =
        runBlocking {
            val dao = FakeBookDao()
            dao.books.value = listOf(book(1, 7, 40, inLibrary = true))
            val metaPrefs = BookMetaPrefs(FakeDataStore())
            var refreshedTotal = 900
            val refresher = buildRefresher(dao, metaPrefs) { book ->
                val grown = book.copy(totalChapters = refreshedTotal)
                dao.update(grown)
                AppResult.Ok(grown)
            }
            // First sweep: first sight — the book syncs to
            // 900 and baselines silently there.
            refresher.refreshAll()
            assertEquals(900, metaPrefs.get(1).lastSeenChapterCount)
            assertEquals(0, metaPrefs.get(1).newChapterCount)
            // The source gains two chapters; the second sweep
            // — the book now baselined — counts exactly those.
            refreshedTotal = 902
            val outcome = refresher.refreshAll()
            assertEquals(2, metaPrefs.get(1).newChapterCount)
            assertEquals(1, outcome.booksWithNew)
            assertEquals(2, outcome.totalNew)
            assertEquals(0, outcome.failures)
        }
}
