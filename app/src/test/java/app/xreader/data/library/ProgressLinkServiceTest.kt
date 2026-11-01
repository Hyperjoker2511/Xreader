package app.xreader.data.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.xreader.data.FakeBookDao
import app.xreader.data.FakeChapterDao
import app.xreader.data.db.Book
import app.xreader.data.db.Chapter
import app.xreader.data.db.ReadingProgress
import app.xreader.data.db.ReadingProgressDao
import app.xreader.data.db.SourceType
import app.xreader.data.prefs.ProgressLinkPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Progress-linking service pins (Cycle 1, F3): reconcile
 * carries the furthest linked copy's position into a book's
 * own progress row + read flags, never pulls a book
 * backwards, ignores non-library books, honours "Not the
 * same book" from the moment it's tapped, and asks for
 * chapter-list growth instead of stranding the reader on a
 * partial list.
 */
class ProgressLinkServiceTest {

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

    private class FakeProgressDao : ReadingProgressDao {
        val rows = MutableStateFlow<List<ReadingProgress>>(emptyList())
        override suspend fun upsert(progress: ReadingProgress) {
            rows.value = rows.value.filterNot { it.bookId == progress.bookId } + progress
        }
        override suspend fun getForBook(bookId: Long): ReadingProgress? =
            rows.value.firstOrNull { it.bookId == bookId }
        override fun observeForBook(bookId: Long): Flow<ReadingProgress?> =
            rows.map { list -> list.firstOrNull { it.bookId == bookId } }
        override fun observeRecent(): Flow<List<ReadingProgress>> =
            rows.map { list -> list.sortedByDescending { it.updatedAt } }
        override suspend fun allProgress(): List<ReadingProgress> = rows.value
    }

    private val bookDao = FakeBookDao()
    private val chapterDao = FakeChapterDao()
    private val progressDao = FakeProgressDao()
    private val service = ProgressLinkService(
        bookDao = bookDao,
        chapterDao = chapterDao,
        progressDao = progressDao,
        prefs = ProgressLinkPrefs(FakeDataStore())
    )

    /** (bookId, index) → chapterId, for progress anchors. */
    private val chapterIds = HashMap<Pair<Long, Int>, Long>()

    private suspend fun addBook(
        title: String,
        author: String = "Guiltythree",
        chapters: Int = 100,
        inLibrary: Boolean = true,
        chapterListComplete: Boolean = true
    ): Long {
        val bookId = bookDao.insert(
            Book(
                title = title,
                author = author,
                sourceType = SourceType.EXTENSION,
                extensionId = 1L,
                remoteId = "src:$title",
                addedAt = 1L,
                updatedAt = 1L,
                inLibrary = inLibrary,
                chapterListComplete = chapterListComplete
            )
        )
        for (n in 1..chapters) {
            val chapterId = chapterDao.insert(
                Chapter(
                    bookId = bookId, index = n - 1,
                    title = "Chapter $n", updatedAt = 1L
                )
            )
            chapterIds[bookId to (n - 1)] = chapterId
        }
        return bookId
    }

    private suspend fun setProgress(
        bookId: Long,
        chapterNumber: Int,
        fraction: Float = 0f,
        updatedAt: Long = 5_000L
    ) {
        progressDao.upsert(
            ReadingProgress(
                bookId = bookId,
                chapterId = chapterIds.getValue(bookId to (chapterNumber - 1)),
                chapterIndex = chapterNumber - 1,
                fraction = fraction,
                scrollFraction = fraction,
                updatedAt = updatedAt
            )
        )
    }

    @Test
    fun `reconcile carries a further-along copy's position into this book`() = runTest {
        val siteA = addBook("Shadow Slave")
        val siteB = addBook("Shadow Slave")
        setProgress(siteA, chapterNumber = 30, fraction = 0.5f, updatedAt = 5_000L)

        val outcome = service.reconcile(siteB)

        val applied = outcome as ProgressLinkService.Outcome.Applied
        assertEquals(siteA, applied.sourceBook.id)
        assertTrue(applied.plan.exact)
        val progress = progressDao.getForBook(siteB)
        assertEquals(29, progress?.chapterIndex)
        assertEquals(0.5f, progress?.fraction)
        // The carried row keeps the position's true age.
        assertEquals(5_000L, progress?.updatedAt)
        // Read state followed: 1..29 read, 30 (in progress) not.
        val chaptersB = chapterDao.forBook(siteB)
        assertTrue(chaptersB.filter { it.index < 29 }.all { it.read })
        assertFalse(chaptersB[29].read)
        // And it converges: a second reconcile changes nothing.
        assertEquals(
            ProgressLinkService.Outcome.NoChange,
            service.reconcile(siteB)
        )
    }

    @Test
    fun `a sibling behind never pulls this book backwards`() = runTest {
        val siteA = addBook("Shadow Slave")
        val siteB = addBook("Shadow Slave")
        setProgress(siteA, chapterNumber = 10)
        setProgress(siteB, chapterNumber = 40)
        assertEquals(
            ProgressLinkService.Outcome.NoChange,
            service.reconcile(siteB)
        )
        assertEquals(39, progressDao.getForBook(siteB)?.chapterIndex)
    }

    @Test
    fun `in a group of three the furthest copy wins`() = runTest {
        val siteA = addBook("Shadow Slave")
        val siteB = addBook("Shadow Slave")
        val siteC = addBook("Shadow Slave")
        setProgress(siteA, chapterNumber = 20)
        setProgress(siteC, chapterNumber = 80)
        val outcome = service.reconcile(siteB)
        val applied = outcome as ProgressLinkService.Outcome.Applied
        assertEquals(siteC, applied.sourceBook.id)
        assertEquals(79, progressDao.getForBook(siteB)?.chapterIndex)
    }

    @Test
    fun `unlink stops sharing immediately and for good`() = runTest {
        val siteA = addBook("Shadow Slave")
        val siteB = addBook("Shadow Slave")
        setProgress(siteA, chapterNumber = 30)
        service.unlink(siteB, siteA)
        assertTrue(service.linkedCopies(siteB).isEmpty())
        assertTrue(service.linkedCopies(siteA).isEmpty())
        assertEquals(
            ProgressLinkService.Outcome.NoChange,
            service.reconcile(siteB)
        )
        assertEquals(null, progressDao.getForBook(siteB))
        // Even after the other copy races further ahead, the
        // refusal stands — the matcher never re-links them.
        setProgress(siteA, chapterNumber = 90)
        assertEquals(
            ProgressLinkService.Outcome.NoChange,
            service.reconcile(siteB)
        )
    }

    @Test
    fun `books outside the library neither give nor receive progress`() = runTest {
        val preview = addBook("Shadow Slave", inLibrary = false)
        val libraryCopy = addBook("Shadow Slave")
        setProgress(preview, chapterNumber = 90)
        // The preview's 90 chapters must not leak into the
        // library copy — and the preview itself never moves.
        assertEquals(
            ProgressLinkService.Outcome.NoChange,
            service.reconcile(libraryCopy)
        )
        setProgress(libraryCopy, chapterNumber = 50)
        assertEquals(
            ProgressLinkService.Outcome.NoChange,
            service.reconcile(preview)
        )
        assertEquals(89, progressDao.getForBook(preview)?.chapterIndex)
    }

    @Test
    fun `a different work in the library shares nothing`() = runTest {
        val siteA = addBook("Shadow Slave")
        val other = addBook("Lord of the Mysteries", author = "Cuttlefish")
        setProgress(siteA, chapterNumber = 90)
        assertEquals(
            ProgressLinkService.Outcome.NoChange,
            service.reconcile(other)
        )
        assertTrue(service.linkedCopies(other).isEmpty())
    }

    @Test
    fun `a partial chapter list asks for growth instead of landing short`() = runTest {
        val siteA = addBook("Shadow Slave")
        val siteB = addBook("Shadow Slave", chapters = 10, chapterListComplete = false)
        setProgress(siteA, chapterNumber = 50)
        assertEquals(
            ProgressLinkService.Outcome.NeedsGrowth(50f),
            service.reconcile(siteB)
        )
        assertEquals(null, progressDao.getForBook(siteB))
    }

    @Test
    fun `a complete but shorter list lands on its last chapter with the notice flag`() = runTest {
        val siteA = addBook("Shadow Slave")
        val siteB = addBook("Shadow Slave", chapters = 10, chapterListComplete = true)
        setProgress(siteA, chapterNumber = 50)
        val outcome = service.reconcile(siteB)
        val applied = outcome as ProgressLinkService.Outcome.Applied
        assertFalse(applied.plan.exact)
        assertEquals(9, progressDao.getForBook(siteB)?.chapterIndex)
    }
}
