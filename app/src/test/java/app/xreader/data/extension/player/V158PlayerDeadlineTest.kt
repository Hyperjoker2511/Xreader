package app.xreader.data.extension.player

import app.xreader.core.error.AppResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.8 (A1): the player deadline split, LATCH-BASED. The
 * fake's fused update is parked on a hand-released gate past
 * the sync layer's short bound: refresh must return the
 * details-landed / chapters-loading state WITHOUT the fetch
 * being cancelled or re-issued, and releasing the gate —
 * with NO second refresh — must land the chapters through
 * the registered completion consumer.
 */
class V158PlayerDeadlineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun slowPlayerUpdateReturnsAtBoundAndCompletesViaConsumer() = runBlocking {
        val fake = V158FakeMihon()
        val gate = CompletableDeferred<Unit>()
        fake.updateGate = gate
        val stack = V158PlayerStacks.build(
            fake, tmp.newFolder(), pageLoadTimeoutMs = 600
        )
        val book = stack.seedBook()

        val refresh = async(Dispatchers.Default) { stack.sync.refresh(book) }
        val result = withTimeout(20_000) { refresh.await() }
        assertTrue("refresh did not return Ok: $result", result is AppResult.Ok)

        // The bound expired with the fetch still parked:
        // nothing stamped, nothing stored, exactly ONE fetch.
        val duringRow = stack.books.getById(book.id)!!
        assertFalse(duringRow.chapterListComplete)
        assertEquals(0, stack.chapters.forBook(book.id).size)
        assertEquals(1, fake.updateCalls.get())

        // Release by hand — no second refresh.
        gate.complete(Unit)
        var rows = stack.chapters.forBook(book.id)
        var waitedMs = 0L
        while (rows.size < 2 && waitedMs < 20_000) {
            delay(100)
            waitedMs += 100
            rows = stack.chapters.forBook(book.id)
        }
        assertEquals("consumer never landed the chapters", 2, rows.size)
        val afterRow = stack.books.getById(book.id)!!
        assertTrue(afterRow.chapterListComplete)
        assertEquals(2, afterRow.totalChapters)
        // Still exactly one fetch — the consumer applied the
        // surviving deferred, it did not re-drive the source.
        assertEquals(1, fake.updateCalls.get())
    }
}
