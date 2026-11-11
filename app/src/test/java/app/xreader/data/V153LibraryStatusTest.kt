package app.xreader.data

import android.app.Application
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.xreader.core.error.AppResult
import app.xreader.data.db.Book
import app.xreader.data.db.ExtensionKind
import app.xreader.data.db.ExtensionRepo
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.SourceType
import app.xreader.data.db.TrustState
import app.xreader.data.download.DownloadController
import app.xreader.data.files.BookStorage
import app.xreader.data.library.BookMeta
import app.xreader.data.library.BookMetaPrefs
import app.xreader.data.library.LibraryPrefs
import app.xreader.data.library.LibraryRefresher
import app.xreader.data.library.ReadingStatus
import app.xreader.data.library.ShelfLogic
import app.xreader.di.AppContainer
import app.xreader.extensionapi.ExtensionApi
import app.xreader.ui.book.BookDetailViewModel
import app.xreader.ui.library.LibraryUiState
import app.xreader.ui.library.ShelfBook
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * v1.5.3 (item 31; Library package, owner decision 2026-11-04:
 * ALL FIVE donor statuses): the status model end to end.
 *
 *  (a) [BookMeta] encode/decode round-trips PLAN_TO_READ, and
 *      lines written by older builds (which could not contain
 *      it) still decode exactly as before — decode is by NAME,
 *      so appending the enum value needed no migration.
 *  (b) [ShelfLogic] membership: a book with each status appears
 *      in exactly its own status shelf and no other; a NONE book
 *      is in none of them — including a NONE book with downloads
 *      and unseen new chapters (the facts-level shape of "has
 *      progress"): membership reads the MANUAL status only, the
 *      legacy Filter-&-sort progress derivation must not leak in.
 *  (c) The production round-trip: [BookDetailViewModel.setReadingStatus]
 *      on a PREVIEW row promotes it (item 30: setting a status is
 *      a membership act) and the status reaches Library facts
 *      through the production read mapping — the same seam shape
 *      as item 28's favourite round-trip.
 *
 * Placement note: the BookMeta-level cases live here rather than
 * in `data/library/BookMetaTest.kt` because the v1.5.3 file
 * partition assigns this WS only the two NEW test files; they
 * complement (do not duplicate) that file's entry-loop coverage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V153LibraryStatusTest {

    // ── (a) BookMeta persistence ─────────────────────────────

    @Test
    fun `PLAN_TO_READ round-trips through BookMeta encode and decode`() {
        val meta = BookMeta(
            status = ReadingStatus.PLAN_TO_READ,
            tracked = true, lastSeenChapterCount = 12, newChapterCount = 3
        )
        assertEquals("PLAN_TO_READ|1|12|3", BookMeta.encode(meta))
        assertEquals(meta, BookMeta.decode(BookMeta.encode(meta)))
        // Every status round-trips, the new one included.
        for (status in ReadingStatus.entries) {
            val m = BookMeta(status = status)
            assertEquals(m, BookMeta.decode(BookMeta.encode(m)))
        }
    }

    @Test
    fun `legacy lines written before PLAN_TO_READ existed still decode`() {
        assertEquals(
            BookMeta(ReadingStatus.READING, true, 120, 3),
            BookMeta.decode("READING|1|120|3")
        )
        assertEquals(
            BookMeta(ReadingStatus.ON_HOLD),
            BookMeta.decode("ON_HOLD|0|0|0")
        )
        assertEquals(
            BookMeta(ReadingStatus.DROPPED),
            BookMeta.decode("DROPPED|0|0|0")
        )
        // Unknown names (e.g. a hand-edited line) degrade to
        // NONE, never crash — the pre-v1.5.3 contract, unchanged.
        assertEquals(
            ReadingStatus.NONE, BookMeta.decode("WOBBLY|1|2|3").status
        )
        assertEquals(BookMeta(), BookMeta.decode(null))
    }

    // ── (b) ShelfLogic membership ────────────────────────────

    private fun facts(
        bookId: Long,
        status: ReadingStatus,
        downloaded: Int = 0,
        unseenNew: Int = 0
    ) = ShelfLogic.BookFacts(
        bookId = bookId,
        favourite = false,
        downloadedChapters = downloaded,
        unreadDownloaded = 0,
        unseenNewChapters = unseenNew,
        lastUpdatedAt = 0L,
        status = status,
        tagSlugs = emptySet()
    )

    @Test
    fun `each status lands in exactly its own status shelf`() {
        val byStatus = listOf(
            ReadingStatus.READING to ShelfLogic.BUILTIN_STATUS_READING,
            ReadingStatus.ON_HOLD to ShelfLogic.BUILTIN_STATUS_ON_HOLD,
            ReadingStatus.PLAN_TO_READ to
                ShelfLogic.BUILTIN_STATUS_PLAN_TO_READ,
            ReadingStatus.COMPLETED to ShelfLogic.BUILTIN_STATUS_COMPLETED,
            ReadingStatus.DROPPED to ShelfLogic.BUILTIN_STATUS_DROPPED
        )
        val all = byStatus.mapIndexed { i, (status, _) ->
            facts(bookId = (i + 1).toLong(), status = status)
        } + facts(bookId = 99L, status = ReadingStatus.NONE)
        for ((status, key) in byStatus) {
            val expectedId = (byStatus.indexOfFirst { it.first == status } + 1)
                .toLong()
            assertEquals(
                "shelf $key holds exactly its own book",
                setOf(expectedId), ShelfLogic.builtinMembers(key, all)
            )
        }
        // The NONE book is in NO status shelf.
        for ((_, key) in byStatus) {
            assertFalse(99L in ShelfLogic.builtinMembers(key, all))
        }
    }

    @Test
    fun `progress-shaped facts on a NONE book never enter the Reading shelf`() {
        // A NONE book the owner is actively reading: downloads
        // present, new chapters unseen. The legacy Filter-&-sort
        // derivation would call this READING; shelf membership
        // must NOT — status is manual-only (donor rule).
        val active = facts(
            bookId = 7L, status = ReadingStatus.NONE,
            downloaded = 5, unseenNew = 2
        )
        val all = listOf(active)
        assertTrue(7L in ShelfLogic.builtinMembers(
            ShelfLogic.BUILTIN_DOWNLOADED, all
        ))
        assertTrue(7L in ShelfLogic.builtinMembers(
            ShelfLogic.BUILTIN_BEHIND_ON, all
        ))
        for (key in listOf(
            ShelfLogic.BUILTIN_STATUS_READING,
            ShelfLogic.BUILTIN_STATUS_ON_HOLD,
            ShelfLogic.BUILTIN_STATUS_PLAN_TO_READ,
            ShelfLogic.BUILTIN_STATUS_COMPLETED,
            ShelfLogic.BUILTIN_STATUS_DROPPED
        )) {
            assertFalse(
                "NONE book must not appear in $key",
                7L in ShelfLogic.builtinMembers(key, all)
            )
        }
    }

    @Test
    fun `the chip rail lists the status shelves after the original builtins`() {
        val chips = ShelfLogic.chips(
            facts = emptyList(), userShelves = emptyList(),
            manualMembers = emptyMap(), hiddenBuiltins = emptySet(),
            nowMillis = 0L
        )
        assertEquals(
            listOf(
                "all",
                ShelfLogic.BUILTIN_FAVOURITES,
                ShelfLogic.BUILTIN_DOWNLOADED,
                ShelfLogic.BUILTIN_BEHIND_ON,
                ShelfLogic.BUILTIN_STATUS_READING,
                ShelfLogic.BUILTIN_STATUS_ON_HOLD,
                ShelfLogic.BUILTIN_STATUS_PLAN_TO_READ,
                ShelfLogic.BUILTIN_STATUS_COMPLETED,
                ShelfLogic.BUILTIN_STATUS_DROPPED
            ),
            chips.map { it.ref }
        )
        assertEquals(
            listOf(
                "All", "Favourites", "Downloaded", "Behind on",
                "Reading", "On-Hold", "Plan to Read", "Completed",
                "Dropped"
            ),
            chips.map { it.name }
        )
        // Hideable like the other builtins: hiding one status
        // shelf removes only its chip.
        val hidden = ShelfLogic.chips(
            facts = emptyList(), userShelves = emptyList(),
            manualMembers = emptyMap(),
            hiddenBuiltins = setOf(ShelfLogic.BUILTIN_STATUS_COMPLETED),
            nowMillis = 0L
        )
        assertFalse(
            ShelfLogic.BUILTIN_STATUS_COMPLETED in hidden.map { it.ref }
        )
        assertTrue(
            ShelfLogic.BUILTIN_STATUS_READING in hidden.map { it.ref }
        )
    }

    // ── (c) Production round-trip ────────────────────────────

    private fun awaitCondition(
        what: String,
        // v1.5.3 (gate-repair, round-17 sift claim 19): 8 s
        // starved under full-gate load — the round-trip method
        // measures ~3.7 s isolated and passes there and in the
        // two-class run, so the budget is load headroom, not a
        // spec change: the assertion (the state transition
        // occurs) is untouched.
        timeoutMs: Long = 30_000,
        cond: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(25)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("timed out waiting for: $what", cond())
    }

    @Test
    fun `setting a status on a preview promotes it and the status reaches Library facts`() =
        runBlocking {
            val app = RuntimeEnvironment.getApplication()
            val container = AppContainer(app)
            val db = container.database
            val repoId = db.extensionRepoDao().insert(
                ExtensionRepo(
                    name = "Fixture-v153status",
                    indexUrl = "https://example.test/i"
                )
            )
            val extId = db.installedExtensionDao().insert(
                InstalledExtension(
                    repoId = repoId, displayName = "Status Source",
                    packageId = "bundled.bundled-v153status",
                    versionName = "1.0", versionCode = 1,
                    kind = ExtensionKind.DECLARATIVE, artifactPath = null,
                    sha256 = "x", apiLevel = ExtensionApi.API_LEVEL,
                    languages = "en", sourceIds = "status", enabled = true,
                    trustState = TrustState.TRUSTED, permissionsJson = "{}",
                    installedAt = 1L, updatedAt = 1L
                )
            )
            val bookId = db.bookDao().insert(
                Book(
                    title = "Preview Book",
                    author = "Some Author",
                    description = "A book the owner only browsed.",
                    sourceType = SourceType.EXTENSION,
                    extensionId = extId, remoteId = "preview-book",
                    addedAt = 1L, updatedAt = 1L, inLibrary = false
                )
            )
            assertFalse(db.bookDao().getById(bookId)!!.inLibrary)

            val vm = BookDetailViewModel(container, bookId)
            vm.setReadingStatus(ReadingStatus.PLAN_TO_READ)

            awaitCondition("status lands and the preview promotes") {
                runBlocking {
                    db.bookDao().getById(bookId)!!.inLibrary &&
                        container.bookMetaPrefs.all.first()[bookId]?.status ==
                        ReadingStatus.PLAN_TO_READ
                }
            }
            assertTrue(db.bookDao().getById(bookId)!!.inLibrary)

            // The production read mapping: shelf rows + metas →
            // Library facts → the Plan to Read shelf holds the
            // book, and no other status shelf does.
            val shelfBooks = db.bookDao().observeShelf().first()
            assertTrue(shelfBooks.any { it.id == bookId })
            val state = LibraryUiState(
                books = shelfBooks.map { ShelfBook(it, 0f) },
                metas = container.bookMetaPrefs.all.first()
            )
            val facts = state.shelfFacts(System.currentTimeMillis())
            assertTrue(bookId in ShelfLogic.builtinMembers(
                ShelfLogic.BUILTIN_STATUS_PLAN_TO_READ, facts
            ))
            for (key in listOf(
                ShelfLogic.BUILTIN_STATUS_READING,
                ShelfLogic.BUILTIN_STATUS_ON_HOLD,
                ShelfLogic.BUILTIN_STATUS_COMPLETED,
                ShelfLogic.BUILTIN_STATUS_DROPPED
            )) {
                assertFalse(bookId in ShelfLogic.builtinMembers(key, facts))
            }

            // Clearing the status (NONE) never demotes membership.
            vm.setReadingStatus(ReadingStatus.NONE)
            awaitCondition("status clears") {
                runBlocking {
                    container.bookMetaPrefs.all.first()[bookId]?.status ==
                        ReadingStatus.NONE
                }
            }
            assertTrue(db.bookDao().getById(bookId)!!.inLibrary)
            val clearedFacts = LibraryUiState(
                books = db.bookDao().observeShelf().first()
                    .map { ShelfBook(it, 0f) },
                metas = container.bookMetaPrefs.all.first()
            ).shelfFacts(System.currentTimeMillis())
            assertFalse(bookId in ShelfLogic.builtinMembers(
                ShelfLogic.BUILTIN_STATUS_PLAN_TO_READ, clearedFacts
            ))
        }
}

/**
 * v1.5.3 (item 26 test obligation; finding #9, strand D):
 * the refresh layer's own bound. A fake source whose refresh
 * HANGS must not stall the walk: `refreshAll` completes within
 * a bounded time, reports the hanging book as SKIPPED (counted
 * separately from ordinary failures — an exception is a
 * failure, a timeout is a skip), and still refreshes the
 * healthy books, including the one queued behind the hung book
 * in its own source group. The production bound is pinned at
 * [LibraryRefresher.REFRESH_PER_BOOK_TIMEOUT_MS] = 75 s; the
 * constructor seam stands in for it here, exactly as the
 * v1.5.1 test used it. (Placement: this WS's owned test files
 * only — `V151LibraryRefreshTest.kt` is outside the partition
 * and keeps pinning the v1.5.1 wedge behaviour.)
 */
class V153LibraryRefreshBoundsTest {

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
    fun `the production per-book bound is 75 seconds`() {
        assertEquals(75_000L, LibraryRefresher.REFRESH_PER_BOOK_TIMEOUT_MS)
    }

    @Test
    fun `a hanging refresh is skipped and counted, the walk completes, healthy books refresh`() =
        runBlocking {
            val dao = FakeBookDao()
            // Source 7: book 1 hangs forever; book 2 queues
            // behind it in the same serial group. Source 9:
            // book 3 fails outright (an ordinary failure — NOT
            // a skip).
            dao.books.value = listOf(
                book(1, 7, 10), book(2, 7, 20), book(3, 9, 30)
            )
            val refreshed = mutableListOf<Long>()
            val refresher = LibraryRefresher(
                bookDao = dao,
                bookMetaPrefs = BookMetaPrefs(FakeDataStore()),
                refreshBook = { book ->
                    when (book.id) {
                        1L -> {
                            // The hang: never returns (cancellable
                            // delay — the bound must cut it).
                            delay(Long.MAX_VALUE)
                            AppResult.Ok(book)
                        }
                        3L -> throw IllegalStateException("source down")
                        else -> {
                            refreshed += book.id
                            val grown = book.copy(
                                totalChapters = book.totalChapters + 2
                            )
                            dao.update(grown)
                            AppResult.Ok(grown)
                        }
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
                "the hanging book is SKIPPED, not failed",
                1, outcome.skipped
            )
            assertEquals(
                "the throwing book is an ordinary failure",
                1, outcome.failures
            )
            assertEquals(
                "the healthy book reported its new chapters",
                1, outcome.booksWithNew
            )
            assertEquals(2, outcome.totalNew)
            assertTrue(
                "the book queued behind the hung one still refreshed",
                refreshed.contains(2L)
            )
        }
}
