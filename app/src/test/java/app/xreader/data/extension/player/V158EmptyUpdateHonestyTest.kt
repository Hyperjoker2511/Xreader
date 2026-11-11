package app.xreader.data.extension.player

import app.xreader.core.error.AppResult
import app.xreader.data.extension.DiagnosticLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.8 (A3, sync side): an empty-but-successful player
 * update stamps NOTHING — chapterListComplete stays false
 * and totalChapters keeps its previous value (an earlier
 * non-empty reading is not erased by a later empty one) —
 * and every book-page sync outcome lands in the DiagnosticLog
 * under the `sync` tag with the error detail verbatim.
 */
class V158EmptyUpdateHonestyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun syncEntries() =
        DiagnosticLog.entries().filter { it.tag == "sync" }

    @Test
    fun emptyPlayerUpdateStampsNothing() = runBlocking {
        DiagnosticLog.clear()
        val fake = V158FakeMihon()
        fake.updateChapters = emptyList()
        val stack = V158PlayerStacks.build(fake, tmp.newFolder())
        val book = stack.seedBook(
            chapterListComplete = false, totalChapters = 7
        )

        val result = stack.sync.refresh(book)
        assertTrue("refresh failed: $result", result is AppResult.Ok)

        val row = stack.books.getById(book.id)!!
        assertFalse(
            "an empty update must not stamp the list complete",
            row.chapterListComplete
        )
        assertEquals(
            "an empty update must not restamp the total",
            7, row.totalChapters
        )
        assertEquals(1, syncEntries().size)
    }

    @Test
    fun failedSyncIsLoggedWithDetailVerbatim() = runBlocking {
        DiagnosticLog.clear()
        val fake = V158FakeMihon()
        fake.updateFailure = RuntimeException("boom chapter walk")
        val stack = V158PlayerStacks.build(fake, tmp.newFolder())
        val book = stack.seedBook()

        val result = stack.sync.refresh(book)
        assertTrue(result is AppResult.Err)

        val entries = syncEntries()
        assertEquals(1, entries.size)
        val carried = entries.single().message + "|" + entries.single().detail
        assertTrue(
            "sync record must carry the failure detail, got: $carried",
            carried.contains("boom chapter walk")
        )
    }
}
