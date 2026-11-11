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
 * v1.5.10 (H2a) gate: Library refresh warmth — hero books'
 * chapter fetches are requested before non-heroes' within a
 * group, and hero groups start first. Hero, pinned to the
 * book row's own fields: lastOpenedAt set (recorded reading
 * activity) and not finished; recent first within the hero
 * run. Driven through the REAL LibraryRefresher with a
 * recording refresh seam (the worker and the Library button
 * share this one implementation — the ordering lives in
 * refreshAll, so every caller inherits it).
 */
class V1510ChapterWarmthTest {

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
        lastOpenedAt: Long? = null,
        finished: Boolean = false
    ) = Book(
        id = id,
        title = "Book $id",
        sourceType = SourceType.EXTENSION,
        extensionId = ext,
        remoteId = "remote-$id",
        totalChapters = 10,
        addedAt = 0,
        updatedAt = 0,
        lastOpenedAt = lastOpenedAt,
        finished = finished
    )

    @Test
    fun `hero definition - opened and unfinished only, recent first`() {
        val heroOld = book(1, 7, lastOpenedAt = 100)
        val heroNew = book(2, 7, lastOpenedAt = 900)
        val finished = book(3, 7, lastOpenedAt = 950, finished = true)
        val neverOpened = book(4, 7, lastOpenedAt = null)
        assertTrue(isHeroBook(heroOld))
        assertTrue(isHeroBook(heroNew))
        assertTrue(!isHeroBook(finished))
        assertTrue(!isHeroBook(neverOpened))
        val ordered = heroOrderedGroups(
            listOf(heroOld, neverOpened, finished, heroNew)
        ).single()
        assertEquals(
            "heroes first, most-recently-read first; the rest " +
                "keep their relative order",
            listOf(2L, 1L, 4L, 3L), ordered.map { it.id }
        )
    }

    @Test
    fun `hero groups order before non-hero groups`() {
        val groups = heroOrderedGroups(
            listOf(
                book(10, 8),
                book(11, 9, lastOpenedAt = 5),
                book(12, 10)
            )
        )
        assertEquals(
            listOf(listOf(11L), listOf(10L), listOf(12L)),
            groups.map { g -> g.map { it.id } }
        )
    }

    @Test
    fun `recording refresher - heroes fetched first within group, hero group starts first`() =
        runBlocking {
            val dao = FakeBookDao()
            // Group ext=7: non-hero first in storage order,
            // hero second. Groups ext=8 and ext=9: non-hero
            // only. Group ext=11: hero only, LAST in storage
            // order — with MAX_PARALLEL_SOURCES = 3 permits,
            // the hero group must still start before ext=10's
            // group (also non-hero, storage order before it).
            dao.books.value = listOf(
                book(1, 7),
                book(2, 7, lastOpenedAt = 700),
                book(3, 8),
                book(4, 9),
                book(5, 10),
                book(6, 11, lastOpenedAt = 800)
            )
            val started = CopyOnWriteArrayList<Long>()
            val refresher = LibraryRefresher(
                bookDao = dao,
                bookMetaPrefs = BookMetaPrefs(FakeDataStore()),
                refreshBook = { b ->
                    started += b.id
                    // Hold the early groups briefly so start
                    // ORDER (not finish order) is what's read.
                    delay(30)
                    AppResult.Ok(b)
                },
                libraryPrefs = LibraryPrefs(FakeDataStore()),
                downloadController = DownloadController(
                    queueDao = FakeDownloadQueueDao(),
                    chapterDao = FakeChapterDao(),
                    bookDao = dao,
                    storage = BookStorage(tmp.newFolder()),
                    enqueuer = { }
                ),
                perBookTimeoutMs = 5_000
            )
            val outcome = withTimeout(15_000) {
                refresher.refreshAll()
            }
            assertEquals(6, outcome.booksChecked)
            assertEquals(6, started.size)
            // Within group ext=7: hero (2) before non-hero (1).
            assertTrue(
                "hero fetched before its group-mate: $started",
                started.indexOf(2L) < started.indexOf(1L)
            )
            // Hero group ext=11 starts before the non-hero
            // group ext=10 that storage order put ahead of it:
            // the first three permits go to ext=7, ext=11,
            // ext=8 (hero groups first), so book 6 starts
            // before book 5 can.
            assertTrue(
                "hero group starts first: $started",
                started.indexOf(6L) < started.indexOf(5L)
            )
            assertTrue(
                "the first wave is the hero groups + earliest others: $started",
                started.take(3).containsAll(listOf(2L, 6L))
            )
        }
}
