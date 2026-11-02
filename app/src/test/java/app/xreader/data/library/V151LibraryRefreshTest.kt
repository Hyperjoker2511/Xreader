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
 * terminate inside its per-book bound, count that book as a
 * failure, and refresh every other book — including the one
 * queued BEHIND the hung book in its own source group.
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

    private fun book(id: Long, ext: Long, chapters: Int) = Book(
        id = id,
        title = "Book $id",
        sourceType = SourceType.EXTENSION,
        extensionId = ext,
        remoteId = "remote-$id",
        totalChapters = chapters,
        addedAt = 0,
        updatedAt = 0
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
                "the hung book is an ordinary failure, not a wedge",
                1, outcome.failures
            )
            assertEquals(
                "both healthy books reported their new chapters",
                2, outcome.booksWithNew
            )
            assertEquals(4, outcome.totalNew)
            assertTrue(
                "the book queued behind the hung one still refreshed",
                refreshed.containsAll(listOf(2L, 3L))
            )
        }
}
