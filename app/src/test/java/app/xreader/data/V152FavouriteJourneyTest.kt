package app.xreader.data

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import app.xreader.data.db.Book
import app.xreader.data.db.Chapter
import app.xreader.data.db.DownloadState
import app.xreader.data.db.SourceType
import app.xreader.data.db.XreaderDatabase
import app.xreader.data.download.BodyOrigin152
import app.xreader.data.library.BookMetaPrefs
import app.xreader.data.library.ShelfLogic
import app.xreader.data.sync.SyncKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.5.2 (FIX-7, §13 / §10 item 10): the favourite lifecycle
 * against the REAL store (BookMetaPrefs over a real DataStore
 * contract) and the shared shelf truth — favourite → row
 * recreated → still favourite; delete favourited → unrelated
 * add reusing the rowid → NOT favourite; merge → survivor
 * favourite (the production transfer); and the three builtin
 * shelves (Favourites / Downloaded / Behind on) computed from
 * production inputs against adversarial writers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V152FavouriteJourneyTest {

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

    private fun book(id: Long, remoteId: String, total: Int = 10) = Book(
        id = id,
        title = "Book $remoteId",
        sourceType = SourceType.EXTENSION,
        extensionId = 7L,
        remoteId = remoteId,
        totalChapters = total,
        addedAt = 1L,
        updatedAt = 1L
    )

    private val pkg = "bundled.bundled-fenrirealm"

    @Test
    fun `favourite survives row recreation and never leaks onto a rowid heir`() =
        runBlocking {
            val prefs = BookMetaPrefs(FakeDataStore())
            val original = book(id = 5, remoteId = "alpha")
            val key = SyncKeys.bookKey(original, pkg)
            prefs.setFavouriteByKey(key, true)
            assertTrue(key in prefs.favouritesByKey.first())

            // The row is recreated (re-import / re-sync): new
            // rowid, same natural identity → still favourite.
            val recreated = book(id = 91, remoteId = "alpha")
            assertEquals(key, SyncKeys.bookKey(recreated, pkg))
            assertTrue(
                SyncKeys.bookKey(recreated, pkg) in
                    prefs.favouritesByKey.first()
            )

            // The favourited book is deleted; an UNRELATED book
            // lands on the freed rowid 5 → NOT favourite (the
            // favourite lives on the natural key, never rowid).
            val heir = book(id = 5, remoteId = "totally-different")
            assertFalse(
                SyncKeys.bookKey(heir, pkg) in prefs.favouritesByKey.first()
            )
        }

    @Test
    fun `merge performs the favourite union transfer in production`() =
        runBlocking {
            val prefs = BookMetaPrefs(FakeDataStore())
            val survivor = book(id = 1, remoteId = "survivor")
            val duplicate = book(id = 2, remoteId = "survivor")
            val survivorKey = SyncKeys.bookKey(survivor, pkg)
            val duplicateKey = SyncKeys.bookKey(duplicate, "live.other.example")
            // Same book, two sources: only the DUPLICATE's key
            // is favourited. After the production merge transfer
            // the survivor holds it and the duplicate's is gone.
            assertTrue(survivorKey != duplicateKey)
            prefs.setFavouriteByKey(duplicateKey, true)
            prefs.transferFavouriteOnMerge(survivorKey, duplicateKey)
            val after = prefs.favouritesByKey.first()
            assertTrue(survivorKey in after)
            assertFalse(duplicateKey in after)

            // Neither favourited → transfer changes nothing.
            val prefs2 = BookMetaPrefs(FakeDataStore())
            prefs2.transferFavouriteOnMerge(survivorKey, duplicateKey)
            assertTrue(prefs2.favouritesByKey.first().isEmpty())
        }

    @Test
    fun `shelf truth over the three builtins against adversarial writers`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val db = Room.inMemoryDatabaseBuilder(
                context, XreaderDatabase::class.java
            ).allowMainThreadQueries().build()
            val prefs = BookMetaPrefs(FakeDataStore())
            val chapterDao = db.chapterDao()
            val bookDao = db.bookDao()

            suspend fun factsFor(book: Book): ShelfLogic.BookFacts {
                val key = SyncKeys.bookKey(book, pkg)
                return ShelfLogic.BookFacts(
                    bookId = book.id,
                    favourite = key in prefs.favouritesByKey.first(),
                    downloadedChapters = chapterDao.downloadedCount(book.id),
                    unreadDownloaded = 0,
                    unseenNewChapters = prefs.get(book.id).newChapterCount,
                    lastUpdatedAt = book.updatedAt,
                    status = app.xreader.data.library.ReadingStatus.READING,
                    tagSlugs = emptySet()
                )
            }

            // Book A: favourited + a USER download → Favourites
            // and Downloaded.
            val aId = bookDao.insert(book(0, "a"))
            val a = bookDao.getById(aId)!!
            prefs.setFavouriteByKey(SyncKeys.bookKey(a, pkg), true)
            chapterDao.insert(
                Chapter(
                    bookId = aId, index = 0, title = "c1",
                    contentPath = "content/$aId/1.txt", wordCount = 10,
                    downloadState = DownloadState.READY, updatedAt = 1L,
                    bodyOrigin = BodyOrigin152.USER
                )
            )
            // Book B: an adversarial writer stores an AUTOMATIC
            // body and claims a download — the shelf truth must
            // not move (AUTOMATIC is cache, not Downloaded).
            val bId = bookDao.insert(book(0, "b"))
            chapterDao.insert(
                Chapter(
                    bookId = bId, index = 0, title = "c1",
                    contentPath = "content/$bId/1.txt", wordCount = 10,
                    downloadState = DownloadState.READY, updatedAt = 1L,
                    bodyOrigin = BodyOrigin152.AUTOMATIC
                )
            )
            // Book C: the refresher recorded new chapters in its
            // meta → Behind on; without the meta write it would
            // not be (writer C2 proves the negative).
            val cId = bookDao.insert(book(0, "c"))
            val c = bookDao.getById(cId)!!
            prefs.update(cId) { it.copy(newChapterCount = 3) }
            val dId = bookDao.insert(book(0, "d"))

            val all = listOf(a, bookDao.getById(bId)!!, c, bookDao.getById(dId)!!)
            val facts = all.map { factsFor(it) }
            assertEquals(
                setOf(aId),
                ShelfLogic.builtinMembers(ShelfLogic.BUILTIN_FAVOURITES, facts)
            )
            assertEquals(
                setOf(aId),
                ShelfLogic.builtinMembers(ShelfLogic.BUILTIN_DOWNLOADED, facts)
            )
            assertEquals(
                setOf(cId),
                ShelfLogic.builtinMembers(ShelfLogic.BUILTIN_BEHIND_ON, facts)
            )
            db.close()
        }
}
