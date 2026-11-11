package app.xreader.data

import android.app.Application
import androidx.room.Room
import app.xreader.data.db.Book
import app.xreader.data.db.Chapter
import app.xreader.data.db.DownloadState
import app.xreader.data.db.ExtensionKind
import app.xreader.data.db.ExtensionRepo
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.SourceType
import app.xreader.data.db.TrustState
import app.xreader.data.db.XreaderDatabase
import app.xreader.data.download.BodyOrigin152
import app.xreader.data.download.CacheEviction152
import app.xreader.data.download.ChapterTruth152
import app.xreader.data.files.BookStorage
import app.xreader.extensionapi.ExtensionApi
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.5.2 (FIX-6, §12 / §10 item 9): the storage truths on the
 * REAL DAOs — one USER+READY predicate behind every count, the
 * eviction caller enforcing per-kind caps as row+file
 * transactions with the needs-repair exemption (the
 * pass-composition fixture), the open book's pin, and the
 * partials leg (a cancelled download's partial file is never
 * counted and never marked).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V152StorageJourneyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ext(name: String, pkg: String, needsRepair: Boolean = false) =
        InstalledExtension(
            repoId = 1, displayName = name, packageId = pkg,
            versionName = "1.0", versionCode = 1,
            kind = ExtensionKind.DECLARATIVE, artifactPath = null,
            sha256 = "x", apiLevel = ExtensionApi.API_LEVEL,
            languages = "en", sourceIds = pkg, enabled = true,
            trustState = TrustState.TRUSTED, permissionsJson = "{}",
            installedAt = 1L, updatedAt = 1L,
            needsRepair = needsRepair,
            needsRepairReason = if (needsRepair) "site unreachable" else null
        )

    private fun book(title: String, extId: Long?, kind: String? = "TEXT") =
        Book(
            title = title, sourceType = SourceType.EXTENSION,
            extensionId = extId, remoteId = title, kind = kind,
            addedAt = 1L, updatedAt = 1L
        )

    private fun chapter(
        bookId: Long,
        index: Int,
        path: String?,
        origin: String?,
        state: DownloadState = DownloadState.READY,
        updatedAt: Long = index.toLong()
    ) = Chapter(
        bookId = bookId, index = index, title = "Chapter $index",
        contentPath = path, wordCount = 500, downloadState = state,
        updatedAt = updatedAt, bodyOrigin = origin
    )

    @Test
    fun `one predicate - counts, marks and the partials leg`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, XreaderDatabase::class.java)
            .allowMainThreadQueries().build()
        db.extensionRepoDao().insert(
            ExtensionRepo(name = "Fixture", indexUrl = "https://example.test/i")
        )
        val root = tmp.newFolder()
        val storage = BookStorage(root)
        val extId = db.installedExtensionDao()
            .insert(ext("Src", "bundled.bundled-src"))
        val bookId = db.bookDao().insert(book("Novel", extId))

        fun body(name: String, bytes: Int): String {
            val f = storage.contentDir(bookId).resolve(name)
            f.parentFile?.mkdirs()
            f.writeBytes(ByteArray(bytes))
            return "content/$bookId/${f.name}"
        }
        // USER download (counts), AUTOMATIC cache (does not),
        // and a cancelled download's leftover partial file.
        val userCh = db.chapterDao().insert(
            chapter(bookId, 0, body("u.txt", 100), BodyOrigin152.USER)
        )
        val autoCh = db.chapterDao().insert(
            chapter(bookId, 1, body("a.txt", 100), BodyOrigin152.AUTOMATIC)
        )
        val partialFile = storage.contentDir(bookId).resolve("p.part")
        partialFile.writeBytes(ByteArray(50))
        val partialCh = db.chapterDao().insert(
            chapter(
                bookId, 2, null, null,
                state = DownloadState.FAILED
            )
        )
        val dao = db.chapterDao()
        // The partial file exists on disk but is not a body:
        // never counted, never marked.
        assertTrue(partialFile.exists())
        assertEquals(1, dao.downloadedCount(bookId))
        val counts = dao.observeDownloadCounts().first()
            .first { it.bookId == bookId }
        assertEquals(1, counts.downloaded)
        assertEquals(
            ChapterTruth152.RowMark.DOWNLOADED,
            ChapterTruth152.rowMark(dao.getById(userCh)!!)
        )
        assertEquals(
            ChapterTruth152.RowMark.CACHED,
            ChapterTruth152.rowMark(dao.getById(autoCh)!!)
        )
        assertEquals(
            ChapterTruth152.RowMark.NONE,
            ChapterTruth152.rowMark(dao.getById(partialCh)!!)
        )
        db.close()
    }

    @Test
    fun `eviction composition - needs-repair bodies survive, ordinary cache evicts`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val db = Room.inMemoryDatabaseBuilder(
                context, XreaderDatabase::class.java
            ).allowMainThreadQueries().build()
        db.extensionRepoDao().insert(
            ExtensionRepo(name = "Fixture", indexUrl = "https://example.test/i")
        )
            val root = tmp.newFolder()
            val storage = BookStorage(root)
            val okExt = db.installedExtensionDao()
                .insert(ext("Fine", "bundled.bundled-fine"))
            val sickExt = db.installedExtensionDao().insert(
                ext("Sick", "link.sick.example", needsRepair = true)
            )
            val ordinaryId = db.bookDao().insert(book("Ordinary", okExt))
            val keptId = db.bookDao().insert(book("Kept", sickExt))

            // 8 KB bodies; the text cap is 20 MB so use many
            // chapters on the ordinary book to exceed it via
            // count — instead shrink the game: bodies of 3 MB.
            fun bigBody(bookId: Long, name: String): String {
                val f = storage.contentDir(bookId).resolve(name)
                f.parentFile?.mkdirs()
                f.writeBytes(ByteArray(3 * 1024 * 1024))
                return "content/$bookId/${f.name}"
            }
            val dao = db.chapterDao()
            // Ordinary book: 8 cached bodies = 24 MB > 20 MB cap.
            val ordinaryChapters = (0 until 8).map { i ->
                dao.insert(
                    chapter(
                        ordinaryId, i, bigBody(ordinaryId, "c$i.txt"),
                        BodyOrigin152.AUTOMATIC, updatedAt = 100L + i
                    )
                )
            }
            // Kept (needs-repair) book: 8 cached bodies too —
            // every one exempt.
            val keptChapters = (0 until 8).map { i ->
                dao.insert(
                    chapter(
                        keptId, i, bigBody(keptId, "k$i.txt"),
                        BodyOrigin152.AUTOMATIC, updatedAt = 100L + i
                    )
                )
            }
            val eviction = CacheEviction152(
                db.bookDao(), dao, db.installedExtensionDao(), storage
            )
            val result = eviction.enforce()
            // Ordinary book sheds its oldest until under the cap:
            // 24 MB → evict 2 (18 MB ≤ 20 MB).
            assertEquals(2, result.evictedChapterIds.size)
            assertEquals(
                ordinaryChapters.take(2).toSet(),
                result.evictedChapterIds.toSet()
            )
            for (id in result.evictedChapterIds) {
                val row = dao.getById(id)!!
                assertEquals(null, row.contentPath)
                assertEquals(DownloadState.NONE, row.downloadState)
            }
            // Kept book: not one body touched — row or file.
            for (id in keptChapters) {
                val row = dao.getById(id)!!
                assertTrue(row.contentPath != null)
                assertTrue(storage.resolve(row.contentPath!!).exists())
            }
            // USER downloads on the ordinary book are never in
            // the plan.
            val userCh = dao.insert(
                chapter(
                    ordinaryId, 99, bigBody(ordinaryId, "u.txt"),
                    BodyOrigin152.USER, updatedAt = 1L
                )
            )
            val second = eviction.enforce()
            assertFalse(userCh in second.evictedChapterIds)
            assertTrue(dao.getById(userCh)!!.contentPath != null)
            db.close()
        }

    @Test
    fun `eviction pins the open book current and next chapter`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, XreaderDatabase::class.java)
            .allowMainThreadQueries().build()
        db.extensionRepoDao().insert(
            ExtensionRepo(name = "Fixture", indexUrl = "https://example.test/i")
        )
        val root = tmp.newFolder()
        val storage = BookStorage(root)
        val extId = db.installedExtensionDao()
            .insert(ext("Src", "bundled.bundled-src"))
        val bookId = db.bookDao().insert(book("Pinned", extId))
        fun bigBody(name: String): String {
            val f = storage.contentDir(bookId).resolve(name)
            f.parentFile?.mkdirs()
            f.writeBytes(ByteArray(3 * 1024 * 1024))
            return "content/$bookId/${f.name}"
        }
        val dao = db.chapterDao()
        val ids = (0 until 8).map { i ->
            dao.insert(
                chapter(
                    bookId, i, bigBody("c$i.txt"),
                    BodyOrigin152.AUTOMATIC, updatedAt = 100L + i
                )
            )
        }
        val eviction = CacheEviction152(
            db.bookDao(), dao, db.installedExtensionDao(), storage
        )
        // Reading chapter 0: it and chapter 1 are pinned even
        // though they are the oldest.
        val result = eviction.enforce(
            openBookId = bookId, openChapterId = ids[0]
        )
        assertFalse(ids[0] in result.evictedChapterIds)
        assertFalse(ids[1] in result.evictedChapterIds)
        assertEquals(2, result.evictedChapterIds.size)
        db.close()
    }
}
