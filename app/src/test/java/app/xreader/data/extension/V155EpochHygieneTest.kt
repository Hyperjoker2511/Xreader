package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeBookDao
import app.xreader.data.FakeChapterDao
import app.xreader.data.FakeChapterSourceMapDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.data.db.Book
import app.xreader.data.db.InstalledExtension
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
 * v1.5.5 (§2, epoch hygiene): a book whose chapter list was
 * completed under an older definition epoch must not be
 * trusted as complete after the definitions change — on the
 * next sync its completeness counts as false and its saved
 * walk cursor is discarded BEFORE the sync proceeds, while
 * its stored chapter rows are never deleted. Books whose
 * source carries NO definition epoch (every non-bundled
 * provenance) are always-current: the rule never engages.
 *
 * The seam: SourceBookSync.ensureChapters performs the
 * epoch reset ahead of any source resolution, so a bundled
 * book with a stale stamp shows the reset on its row even
 * when the (here deliberately unresolvable) source then
 * fails the sync — the reset is not conditional on the
 * re-walk succeeding.
 */
class V155EpochHygieneTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun harness(): Triple<SourceBookSync, FakeBookDao, FakeInstalledExtensionDao> {
        val bookDao = FakeBookDao()
        val chapterDao = FakeChapterDao()
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
        return Triple(sync, bookDao, extDao)
    }

    private suspend fun seedBook(
        extDao: FakeInstalledExtensionDao,
        bookDao: FakeBookDao,
        packageId: String
    ): Long {
        val extId = extDao.insert(
            InstalledExtension(
                repoId = 1,
                displayName = "Epoch Source",
                packageId = packageId,
                versionName = "1.0",
                versionCode = 1,
                kind = app.xreader.data.db.ExtensionKind.DECLARATIVE,
                artifactPath = null,
                sha256 = "x",
                apiLevel = 1,
                languages = "en",
                sourceIds = "epoch-source",
                installedAt = 0,
                updatedAt = 0,
                permissionsJson = "{}"
            )
        )
        return bookDao.insert(
            Book(
                title = "Epoch Tale",
                sourceType = SourceType.EXTENSION,
                extensionId = extId,
                remoteId = SourceBookIds.encode(
                    "epoch-source", "https://epoch.example/book/1"
                ),
                addedAt = 0,
                updatedAt = 0,
                inLibrary = true,
                // Completed + mid-walk cursor, stamped under
                // NO epoch (every pre-v1.5.5 row reads 0).
                chapterListComplete = true,
                chapterWalkNextPage = 4,
                chapterWalkTotalPages = 9,
                chapterWalkPageSize = 50,
                syncedEpoch = 0
            )
        )
    }

    @Test
    fun `bundled book with a stale epoch stamp loses completeness and cursor`() =
        runBlocking {
            val (sync, bookDao, extDao) = harness()
            val bookId = seedBook(extDao, bookDao, "bundled.epoch")
            val book = bookDao.getById(bookId)!!
            // The sync itself fails (no definition file was
            // installed for the row) — the epoch reset must
            // already be on the row regardless.
            sync.ensureChapters(book, atLeast = 1)
            val after = bookDao.getById(bookId)!!
            assertFalse(
                "a stale-epoch completeness stamp must not survive",
                after.chapterListComplete
            )
            assertEquals(0, after.chapterWalkNextPage)
            assertEquals(0, after.chapterWalkTotalPages)
            assertEquals(0, after.chapterWalkPageSize)
        }

    @Test
    fun `non bundled book is always current and untouched`() =
        runBlocking {
            val (sync, bookDao, extDao) = harness()
            val bookId = seedBook(extDao, bookDao, "test.epoch")
            val book = bookDao.getById(bookId)!!
            val result = sync.ensureChapters(book, atLeast = 1)
            assertTrue(result is AppResult.Ok)
            val after = bookDao.getById(bookId)!!
            assertTrue(after.chapterListComplete)
            assertEquals(4, after.chapterWalkNextPage)
            assertEquals(9, after.chapterWalkTotalPages)
        }
}
