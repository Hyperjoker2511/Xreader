package app.xreader.data.files

import app.xreader.data.db.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v1.2.1 (Defect 3): the storage-accounting fixes.
 *
 * The on-device evidence (V120-CHECK.md): Downloads showed
 * "Novels · 44.4 MB" with ONE downloaded book after the user
 * had cleared his whole Library and removed junk books, and
 * "Cached covers · 0 B" while images plainly accumulated.
 * Code audit found (a) lore-art/<bookId> files counted by no
 * total and deleted by no removal, (b) NO orphan reaping at
 * all — "Remove book only" (files kept on purpose) plus any
 * row loss left per-book directories behind forever, owned by
 * no book but counted in every whole-tree total.
 *
 * These tests pin the fixes: lore art is counted and deleted
 * with its book, and the orphan sweep reaps exactly the ids
 * that have no row — never a live book's files.
 */
class V121StorageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun book(id: Long) = Book(
        id = id, title = "Book $id", addedAt = 0L, updatedAt = 0L
    )

    private fun write(rel: String, bytes: Int): File {
        val file = File(tmp.root, rel)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes))
        return file
    }

    @Test
    fun `book file size counts lore art`() {
        val storage = BookStorage(tmp.root)
        write("content/7/chapter.json", 10)
        write("lore-art/7/portrait.png", 20)
        // Pre-fix this returned 10 — the lore art was invisible
        // to every number built on bookFilesSizeBytes.
        assertEquals(30L, storage.bookFilesSizeBytes(book(7)))
    }

    @Test
    fun `delete book files removes lore art too`() {
        val storage = BookStorage(tmp.root)
        write("content/7/chapter.json", 10)
        write("lore-art/7/portrait.png", 20)
        storage.deleteBookFiles(book(7))
        assertFalse(File(tmp.root, "content/7").exists())
        // Pre-fix the lore-art directory survived removal as an
        // orphan nothing would ever reap.
        assertFalse(File(tmp.root, "lore-art/7").exists())
    }

    @Test
    fun `sweep reaps orphan files and keeps live books`() {
        val storage = BookStorage(tmp.root)
        // Live book 1: content + cover survive untouched.
        write("content/1/chapter.json", 10)
        write("covers/1.jpg", 5)
        // Orphan book 2 (row long gone): every root holds files.
        write("content/2/chapter.json", 10)
        write("books/2/book.epub", 10)
        write("lore-art/2/portrait.png", 10)
        write("covers/2.jpg", 5)
        // Orphan book 9: only a cover remains.
        write("covers/9.png", 5)

        val reaped = storage.sweepOrphanedBookFiles(validBookIds = setOf(1L))

        assertEquals(setOf(2L, 9L), reaped)
        assertTrue(File(tmp.root, "content/1/chapter.json").exists())
        assertTrue(File(tmp.root, "covers/1.jpg").exists())
        assertFalse(File(tmp.root, "content/2").exists())
        assertFalse(File(tmp.root, "books/2").exists())
        assertFalse(File(tmp.root, "lore-art/2").exists())
        assertFalse(File(tmp.root, "covers/2.jpg").exists())
        assertFalse(File(tmp.root, "covers/9.png").exists())
    }

    @Test
    fun `sweep with every book valid deletes nothing`() {
        val storage = BookStorage(tmp.root)
        write("content/3/chapter.json", 10)
        write("covers/3.jpg", 5)
        val reaped = storage.sweepOrphanedBookFiles(validBookIds = setOf(3L))
        assertTrue(reaped.isEmpty())
        assertTrue(File(tmp.root, "content/3/chapter.json").exists())
        assertTrue(File(tmp.root, "covers/3.jpg").exists())
    }
}
