package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeBookDao
import app.xreader.data.FakeChapterDao
import app.xreader.data.FakeChapterSourceMapDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.data.db.Chapter
import app.xreader.data.db.ChapterDao
import app.xreader.data.files.BookStorage
import app.xreader.extensionapi.SourceChapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.39 (item 1): the crash fixture from
 * V1538-CRASH-FORENSICS §5. The convicted crash: two
 * refreshes of one book plan from the same pre-state; the
 * second plan replays onto the first's landed rows and
 * ChapterDao.insert (ABORT on the (bookId, index) unique
 * index) throws SQLiteConstraintException on the main
 * dispatcher.
 *
 * The seam is [SourceBookSync.applyPlan] driven directly
 * against a ChapterDao that enforces Room's ABORT
 * semantics (FakeChapterDao alone just appends, so the
 * wrapper below is what makes the fixture honest: a raw
 * duplicate insert throws exactly what Room threw on the
 * device). The fix under test is applyPlan's replay
 * filter: inserts already landed are dropped, inserts
 * whose target index is held by a different chapter are
 * reassigned to append-after-max.
 */
class V1539CrashFixTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Room's insert semantics (Daos.kt: bare @Insert =
     * ABORT) over the in-memory fake: a (bookId, index)
     * clash throws the device's exact exception. */
    private class AbortChapterDao(
        private val inner: FakeChapterDao
    ) : ChapterDao by inner {
        override suspend fun insert(chapter: Chapter): Long {
            val clash = inner.forBook(chapter.bookId)
                .any { it.index == chapter.index }
            if (clash) {
                throw android.database.sqlite
                    .SQLiteConstraintException(
                        "UNIQUE constraint failed: " +
                            "chapters.bookId, chapters.index " +
                            "(code 2067 SQLITE_CONSTRAINT_UNIQUE)"
                    )
            }
            return inner.insert(chapter)
        }
    }

    private fun harness(): Pair<SourceBookSync, AbortChapterDao> {
        val bookDao = FakeBookDao()
        val chapterDao = AbortChapterDao(FakeChapterDao())
        val mapDao = FakeChapterSourceMapDao()
        val extDao = FakeInstalledExtensionDao()
        val filesDir = tmp.newFolder()
        val store = ExtensionStore(filesDir)
        val transport = FakeTransport {
            AppResult.Ok(FakeTransport.response(404, "no"))
        }
        val service = ExtensionService(
            extDao, store,
            HostContextFactory(store, testSessionStore(), transport),
            FakeDexLoader()
        ) { FakeJsRuntime() }
        val sync = SourceBookSync(
            bookDao = bookDao,
            chapterDao = chapterDao,
            sourceMapDao = mapDao,
            extensionDao = extDao,
            service = service,
            storage = BookStorage(tmp.newFolder()),
            transport = transport,
            repairScheduler = {},
            nowMs = { 1_000L }
        )
        return sync to chapterDao
    }

    private fun storedChapter(bookId: Long, index: Int) = Chapter(
        bookId = bookId,
        index = index,
        title = "Chapter ${index + 1}",
        remoteId = "https://s.example/book/chapter-${index + 1}",
        updatedAt = 0L
    )

    private fun fetchedChapter(number: Int) = SourceChapter(
        id = "https://s.example/book/chapter-$number",
        name = "Chapter $number",
        number = number.toFloat()
    )

    /** The harness itself can see the crash class: a raw
     * duplicate insert throws, like Room did on device. */
    @Test
    fun abortHarnessDetectsRawDuplicates() {
        runBlocking {
            val (_, chapterDao) = harness()
            chapterDao.insert(storedChapter(bookId = 7L, index = 0))
            assertThrows(
                android.database.sqlite.SQLiteConstraintException::class.java
            ) {
                runBlocking {
                    chapterDao.insert(storedChapter(bookId = 7L, index = 0))
                }
            }
        }
    }

    /** THE convicted shape: a growth plan computed from a
     * 10-chapter store, applied TWICE (two refreshes that
     * planned from the same pre-state). The second apply
     * must land nothing twice: no throw, one row per index,
     * and the tail rows keep the ids the first apply gave
     * them (insert stays ABORT — never REPLACE). */
    @Test
    fun sameGrowthPlanAppliedTwiceLandsOnce() = runBlocking {
        val (sync, chapterDao) = harness()
        val bookId = 7L
        repeat(10) { chapterDao.insert(storedChapter(bookId, it)) }
        val existing = chapterDao.forBook(bookId)
        val plan = ChapterReconciler.reconcile(
            existing, (1..12).map { fetchedChapter(it) }
        )
        assertEquals(2, plan.inserts.size)

        sync.applyPlan(bookId, plan, now = 1_000L)
        val afterFirst = chapterDao.forBook(bookId)
        assertEquals(12, afterFirst.size)
        val firstTailIds = afterFirst
            .filter { it.index >= 10 }.map { it.id }

        // The replay — pre-fix this threw the UNIQUE error.
        sync.applyPlan(bookId, plan, now = 2_000L)
        val afterSecond = chapterDao.forBook(bookId)
        assertEquals(12, afterSecond.size)
        assertEquals((0..11).toList(), afterSecond.map { it.index })
        assertEquals(
            firstTailIds,
            afterSecond.filter { it.index >= 10 }.map { it.id }
        )
    }

    /** The delta variant: an append-after-max plan (the
     * S3 head-delta shape) applied twice — the second
     * apply is a no-op, row for row. */
    @Test
    fun deltaAppendPlanSecondApplyIsNoOp() = runBlocking {
        val (sync, chapterDao) = harness()
        val bookId = 9L
        repeat(10) { chapterDao.insert(storedChapter(bookId, it)) }
        val plan = ChapterReconciler.Plan(
            updates = emptyList(),
            inserts = listOf(
                ChapterReconciler.Insert(10, fetchedChapter(11)),
                ChapterReconciler.Insert(11, fetchedChapter(12))
            ),
            dropped = emptyList(),
            keptStale = emptyList()
        )
        sync.applyPlan(bookId, plan, now = 1_000L)
        val afterFirst = chapterDao.forBook(bookId)
        assertEquals(12, afterFirst.size)

        sync.applyPlan(bookId, plan, now = 2_000L)
        val afterSecond = chapterDao.forBook(bookId)
        assertEquals(
            afterFirst.map { it.id to it.index },
            afterSecond.map { it.id to it.index }
        )
    }

    /** The R4 shape: the plan's insert target is held by a
     * DIFFERENT chapter (a misaligned partial plan). The
     * insert must not throw and must not evict the
     * occupant — it appends after the stored maximum. */
    @Test
    fun insertOntoForeignOccupiedIndexAppends() = runBlocking {
        val (sync, chapterDao) = harness()
        val bookId = 11L
        repeat(11) { chapterDao.insert(storedChapter(bookId, it)) }
        val occupant = chapterDao.forBook(bookId)
            .first { it.index == 10 }
        val plan = ChapterReconciler.Plan(
            updates = emptyList(),
            inserts = listOf(
                ChapterReconciler.Insert(
                    10,
                    SourceChapter(
                        id = "https://s.example/book/brand-new",
                        name = "Brand new",
                        number = 99f
                    )
                )
            ),
            dropped = emptyList(),
            keptStale = emptyList()
        )
        sync.applyPlan(bookId, plan, now = 1_000L)
        val rows = chapterDao.forBook(bookId)
        assertEquals(12, rows.size)
        assertEquals(
            occupant.id,
            rows.first { it.index == 10 }.id
        )
        val appended = rows.first { it.index == 11 }
        assertEquals(
            "https://s.example/book/brand-new", appended.remoteId
        )
    }
}
