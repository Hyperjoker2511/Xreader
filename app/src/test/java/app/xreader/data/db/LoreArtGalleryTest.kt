package app.xreader.data.db

import android.app.Application
import androidx.room.Room
import app.xreader.data.ai.LoreArtStore
import app.xreader.data.files.BookStorage
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
 * Patch 10, Addendum 2: the gallery's data truths on the REAL
 * DAOs — observeAllArt is newest-first ACROSS books (the gallery
 * mixes every book's pictures in one feed), and the shared
 * [LoreArtStore] delete removes the row and the file together
 * (the one delete path the Lore page and the gallery share).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LoreArtGalleryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun db(): XreaderDatabase =
        Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            XreaderDatabase::class.java
        ).allowMainThreadQueries().build()

    private suspend fun book(db: XreaderDatabase, title: String): Long =
        db.bookDao().insert(Book(title = title, addedAt = 1L, updatedAt = 1L))

    private fun art(bookId: Long, quote: String, createdAt: Long) = LoreArt(
        bookId = bookId,
        passageQuote = quote,
        prompt = "prompt for $quote",
        imagePath = "lore-art/$bookId/$quote.webp",
        createdAt = createdAt
    )

    @Test
    fun `observeAllArt is newest first across books`() = runBlocking {
        val db = db()
        val one = book(db, "One")
        val two = book(db, "Two")
        val dao = db.loreArtDao()
        dao.insert(art(one, "oldest", createdAt = 100))
        dao.insert(art(two, "newest", createdAt = 300))
        dao.insert(art(one, "middle", createdAt = 200))
        val all = dao.observeAllArt().first()
        assertEquals(
            listOf("newest", "middle", "oldest"),
            all.map { it.passageQuote }
        )
        assertEquals(listOf(two, one, one), all.map { it.bookId })
        db.close()
    }

    @Test
    fun `observeAllArt starts empty`() = runBlocking {
        val db = db()
        assertTrue(db.loreArtDao().observeAllArt().first().isEmpty())
        db.close()
    }

    @Test
    fun `the shared store delete removes the row and the file together`() =
        runBlocking {
            val db = db()
            val storage = BookStorage(tmp.newFolder())
            val bookId = book(db, "One")
            val dir = storage.loreArtDir(bookId)
            val file = File(dir, "art-1.webp")
            file.writeBytes(byteArrayOf(1, 2, 3))
            val id = db.loreArtDao().insert(
                LoreArt(
                    bookId = bookId,
                    passageQuote = "a quote",
                    prompt = "a prompt",
                    imagePath = storage.loreArtRelative(bookId, "art-1.webp"),
                    createdAt = 5L
                )
            )
            val stored = db.loreArtDao().observeAllArt().first().single()
            assertEquals(id, stored.id)
            assertTrue(file.exists())

            LoreArtStore(db.loreArtDao(), storage).delete(stored)

            assertTrue(db.loreArtDao().observeAllArt().first().isEmpty())
            assertFalse("the picture file goes with its row", file.exists())
            db.close()
        }
}
