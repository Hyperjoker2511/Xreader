package app.xreader.ui.book

import app.xreader.data.db.Book
import app.xreader.data.db.Chapter
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 2 GB perf pass (F1): the book page's duplicates scan is its
 * own pipeline ([BookDuplicatesPipeline]) fed by the book and
 * the books table ONLY. The counting fake below proves the
 * property the split exists for: chapter-list emissions —
 * which re-emit the same book row through the base state —
 * never re-run the scan over all books, while a real change
 * to either of its two inputs does.
 */
class PerfPassBookDuplicatesPipelineTest {

    private fun book(id: Long, title: String = "Some Work") = Book(
        id = id, title = title, author = "An Author",
        addedAt = 1L, updatedAt = 1L
    )

    private fun chapter(id: Long) = Chapter(
        id = id, bookId = 7L, index = id.toInt(), title = "Chapter $id",
        updatedAt = id
    )

    @Test
    fun `chapter emissions never re-run the duplicates scan`() = runBlocking {
        val scanCalls = AtomicInteger(0)
        val pipeline = BookDuplicatesPipeline(bookId = 7L) { _, _, _ ->
            scanCalls.incrementAndGet()
            BookDuplicates()
        }
        val theBook = book(7L)
        // Chapter-list emissions drive the book page's base
        // state; the book it carries is the same row every time.
        // (This mirrors the VM wiring: the pipeline's book flow
        // is the base state mapped to its book.)
        val chapters = MutableStateFlow(listOf(chapter(1L)))
        val bookFlow = chapters.map { theBook }
        val allBooks = MutableStateFlow(listOf(theBook))

        val job = launch(Dispatchers.Unconfined) {
            pipeline.flow(bookFlow, allBooks).collect {}
        }
        try {
            assertEquals(1, scanCalls.get())

            // Chapter-list emissions (a download state flip, a
            // sync adding rows): the scan must NOT re-run.
            chapters.value = listOf(chapter(1L), chapter(2L))
            chapters.value = listOf(chapter(1L), chapter(2L), chapter(3L))
            assertEquals(1, scanCalls.get())

            // A books-table change is a real input: one re-run.
            allBooks.value = listOf(theBook, book(9L, "Another Work"))
            assertEquals(2, scanCalls.get())
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `a changed book re-runs the scan once`() = runBlocking {
        val scanCalls = AtomicInteger(0)
        val pipeline = BookDuplicatesPipeline(bookId = 7L) { _, _, _ ->
            scanCalls.incrementAndGet()
            BookDuplicates()
        }
        val bookFlow = MutableStateFlow<Book?>(book(7L))
        val allBooks = MutableStateFlow(listOf(book(7L)))

        val job = launch(Dispatchers.Unconfined) {
            pipeline.flow(bookFlow, allBooks).collect {}
        }
        try {
            assertEquals(1, scanCalls.get())
            // The opened book's own row changes (a retitle): the
            // scan's other real input — exactly one re-run.
            bookFlow.value = book(7L, "Retitled Work")
            assertEquals(2, scanCalls.get())
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `the production scan finds same-work duplicates and merged copies`() =
        runBlocking {
            val open = book(7L)
            val sameWork = book(9L) // same title + author as the open book
            val merged = book(11L, "Old Copy").copy(mergedIntoId = 7L)
            val unrelated = book(13L, "Different Work")
            val result = BookDuplicatesPipeline(bookId = 7L).flow(
                MutableStateFlow(open),
                MutableStateFlow(listOf(open, sameWork, merged, unrelated))
            )
            var seen: BookDuplicates? = null
            val job = launch(Dispatchers.Unconfined) {
                result.collect { seen = it }
            }
            try {
                assertEquals(listOf(9L), seen?.duplicates?.map { it.id })
                assertEquals(listOf(11L), seen?.mergedCopies?.map { it.id })
            } finally {
                job.cancel()
            }
        }
}
