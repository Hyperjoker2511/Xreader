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
import app.xreader.data.extension.MediaKind
import app.xreader.data.files.BookStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.4 (item 20, finding #2): the "Novels · 12121" counter.
 *
 * (a) Pure-function shape of the owner's screenshot
 * (2026-11-04 16:51 IST): his library held ONE novel ("All ·
 * 1") while the switch claimed 12,121 new novel chapters. The
 * fixture below reproduces that arithmetic exactly — a shelf
 * novel with a small true delta, a shelf manga, and a pile of
 * NON-shelf metas with fat counts, every unresolvable one
 * defaulting to Novels under the whole-map sum. The unscoped
 * (pre-fix) sum over this fixture is the photographed 12,121;
 * the shelf-scoped sum is the small true number per side.
 *
 * (b) Refresher level: a non-library book stored with a
 * PARTIAL chapter total (a progressive chunk) refreshed to a
 * fat full total must accrue NO badge and leave with its
 * baseline at the post-refresh total; a library book through
 * the same shape accrues exactly the delta. (Pre-fix, the
 * non-library half of (b) fails: the badge was written.)
 */
class V154LibraryCounterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------------------------------------------------------------
    // (a) The label sum
    // ---------------------------------------------------------------

    @Test
    fun `label sum counts shelf books only - the 12121 shape`() {
        // The shelf: one novel (id 1, true delta 3), one manga
        // (id 2, delta 4).
        val shelfIds = setOf(1L, 2L)
        val metas = mapOf(
            1L to BookMeta(newChapterCount = 3),
            2L to BookMeta(newChapterCount = 4),
            // Non-shelf rows the update pass had badge-bookkept:
            // fat serials, none of them in the library. Their
            // counts sum to 12,118 — with the shelf novel's 3
            // riding on top, the whole-map Novels sum is the
            // photographed 12,121.
            101L to BookMeta(newChapterCount = 2_847),
            102L to BookMeta(newChapterCount = 3_000),
            103L to BookMeta(newChapterCount = 2_500),
            104L to BookMeta(newChapterCount = 1_900),
            105L to BookMeta(newChapterCount = 1_871),
            // A non-shelf MANGA row whose kind IS resolvable:
            // excluded by the shelf filter, not by attribution.
            106L to BookMeta(newChapterCount = 640)
        )
        // The shelf's resolved kinds: manga entries only,
        // exactly as the VM's bookKindsFlow produces (absence
        // defaults to Novels). Note 106 is resolvable — and
        // still not the label's business.
        val kinds = mapOf(
            2L to MediaKind.MANGA,
            106L to MediaKind.MANGA
        )

        // The pre-fix shape: the whole-map sum (the signature
        // the phone ran) reproduces the screenshot's number.
        val unscoped = LibrarySplit.newChaptersBySide(metas, kinds)
        assertEquals(12_121, unscoped[MediaKind.TEXT])
        assertEquals(644, unscoped[MediaKind.MANGA])

        // The fixed shape: only the shelf's own deltas, each on
        // its own side.
        val scoped =
            LibrarySplit.newChaptersBySide(metas, kinds, shelfIds)
        assertEquals(3, scoped[MediaKind.TEXT])
        assertEquals(4, scoped[MediaKind.MANGA])
    }

    // ---------------------------------------------------------------
    // (b) The refresher's badge bookkeeping
    // ---------------------------------------------------------------

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
        chapters: Int,
        inLibrary: Boolean
    ) = Book(
        id = id,
        title = "Book $id",
        sourceType = SourceType.EXTENSION,
        extensionId = 7,
        remoteId = "remote-$id",
        totalChapters = chapters,
        inLibrary = inLibrary,
        addedAt = 0,
        updatedAt = 0
    )

    @Test
    fun `non-library refresh accrues no badge and rebaselines - library accrues the delta`() =
        runBlocking {
            val dao = FakeBookDao()
            // Both books stored at a PARTIAL total of 40 (a
            // progressive chunk); the source's full list is
            // 2,400. Book 1 was only browsed (not in the
            // library); book 2 is a library book.
            dao.books.value = listOf(
                book(1, 40, inLibrary = false),
                book(2, 40, inLibrary = true)
            )
            val metaPrefs = BookMetaPrefs(FakeDataStore())
            val refresher = LibraryRefresher(
                bookDao = dao,
                bookMetaPrefs = metaPrefs,
                refreshBook = { book ->
                    val grown = book.copy(totalChapters = 2_400)
                    dao.update(grown)
                    AppResult.Ok(grown)
                },
                libraryPrefs = LibraryPrefs(FakeDataStore()),
                downloadController = DownloadController(
                    queueDao = FakeDownloadQueueDao(),
                    chapterDao = FakeChapterDao(),
                    bookDao = dao,
                    storage = BookStorage(tmp.newFolder()),
                    enqueuer = { }
                )
            )
            val outcome = refresher.refreshAll()

            // The non-library row: NO badge, and the baseline
            // moved to the post-refresh total — nothing phantom
            // exists now or can surface if it joins the library.
            val browsed = metaPrefs.get(1)
            assertEquals(0, browsed.newChapterCount)
            assertEquals(2_400, browsed.lastSeenChapterCount)

            // The library book through the same shape accrues
            // exactly the delta over its stored partial total.
            val shelved = metaPrefs.get(2)
            assertEquals(2_360, shelved.newChapterCount)

            // And the sweep's totals count only the real badge.
            assertEquals(1, outcome.booksWithNew)
            assertEquals(2_360, outcome.totalNew)
            assertEquals(0, outcome.failures)
        }
}
