package app.xreader.data.extension.player

import app.xreader.core.error.AppResult
import app.xreader.data.db.Chapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.8 (A2): ensureChapters on a Mihon-backed book with
 * ZERO stored chapters routes through refresh() (the fused
 * fetch) instead of dying at the non-declarative early
 * return — while a book that already has chapters never
 * re-fetches to satisfy the same ask.
 */
class V158EnsureChaptersPlayerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun zeroChapterPlayerBookFetchesThroughRefresh() = runBlocking {
        val fake = V158FakeMihon()
        val stack = V158PlayerStacks.build(fake, tmp.newFolder())
        val book = stack.seedBook(chapterListComplete = false)

        val result = stack.sync.ensureChapters(book, atLeast = 1)
        assertTrue("ensureChapters failed: $result", result is AppResult.Ok)
        assertEquals(2, stack.chapters.forBook(book.id).size)
        assertEquals(1, fake.updateCalls.get())
    }

    @Test
    fun stockedPlayerBookDoesNotRefetch() = runBlocking {
        val fake = V158FakeMihon()
        val stack = V158PlayerStacks.build(fake, tmp.newFolder())
        val book = stack.seedBook(chapterListComplete = false)
        stack.chapters.insert(
            Chapter(
                bookId = book.id, index = 0, title = "Chapter 1",
                remoteId = "/ch/1", updatedAt = 0
            )
        )

        val result = stack.sync.ensureChapters(book, atLeast = 1)
        assertTrue(result is AppResult.Ok)
        assertEquals(0, fake.updateCalls.get())
    }
}
