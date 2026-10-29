package app.xreader.data.extension

import app.xreader.data.db.Book
import app.xreader.extensionapi.SourceBook
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for "the library book is not showing synopsis
 * after one time" (v1.0.1): fetched book info is persisted onto the
 * library row at fetch time, and a later fetch whose page renders
 * without its synopsis block must NEVER blank what the row has.
 */
class BookInfoMergeTest {

    private fun book(description: String, author: String = "Stored Author") = Book(
        title = "Stored Title",
        author = author,
        description = description,
        addedAt = 1L,
        updatedAt = 1L
    )

    @Test
    fun `fetched synopsis lands on the row and survives reopen`() {
        val empty = book(description = "")
        val fetched = SourceBook(
            id = "x", title = "Stored Title", author = "Real Author",
            description = "A sweeping synopsis."
        )
        val merged = BookInfoMerge.apply(empty, fetched)
        // Persisted on the row — a reopen reads exactly this row.
        assertEquals("A sweeping synopsis.", merged.description)
        assertEquals("Real Author", merged.author)
    }

    @Test
    fun `a later blank fetch never erases the stored synopsis`() {
        val stored = book(description = "A sweeping synopsis.")
        val blankFetch = SourceBook(id = "x", title = "Stored Title", description = "")
        val nullFetch = SourceBook(id = "x", title = "Stored Title", description = null)
        assertEquals(
            "A sweeping synopsis.",
            BookInfoMerge.apply(stored, blankFetch).description
        )
        assertEquals(
            "A sweeping synopsis.",
            BookInfoMerge.apply(stored, nullFetch).description
        )
    }

    @Test
    fun `blank author and title never erase stored values`() {
        val stored = book(description = "d")
        val blank = SourceBook(id = "x", title = "", author = "", description = null)
        val merged = BookInfoMerge.apply(stored, blank)
        assertEquals("Stored Title", merged.title)
        assertEquals("Stored Author", merged.author)
    }

    @Test
    fun `a better synopsis replaces the old one`() {
        val stored = book(description = "Old synopsis.")
        val fetched = SourceBook(
            id = "x", title = "Stored Title", description = "New, fuller synopsis."
        )
        assertEquals(
            "New, fuller synopsis.",
            BookInfoMerge.apply(stored, fetched).description
        )
    }
}
