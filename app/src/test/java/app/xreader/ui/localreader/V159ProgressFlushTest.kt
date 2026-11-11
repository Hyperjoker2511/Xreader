package app.xreader.ui.localreader

import android.app.Application
import app.xreader.data.db.Book
import app.xreader.data.db.BookFormat
import app.xreader.data.db.SourceType
import app.xreader.di.AppContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.5.9 delta (Gemini sift A-7): the pending PROGRESS save
 * has a lifecycle flush. The shipped state was an 800 ms
 * debounce whose only flush was a racy `viewModelScope.launch`
 * inside `onCleared`. Here: a locator report schedules the
 * debounced save, `flushPendingProgressSave()` (what the UI's
 * ON_STOP observer calls) commits it immediately through the
 * same store path, and `onCleared` remains as backstop.
 *
 * Why the landed row proves the FLUSH (not the debounce):
 * under Robolectric the main looper is paused, so the
 * debounce's Main-dispatcher `delay(800)` cannot fire while
 * this test sleeps/polls — nothing pumps it. The flush's
 * commit rides the container's IO dispatcher (real threads),
 * so a row landing inside the poll window came from the
 * flush. The debounce itself is unchanged (800 ms).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V159ProgressFlushTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `flush commits the pending progress save without the debounce`() {
        val app = RuntimeEnvironment.getApplication()
        val container = AppContainer(app)
        val filesRoot = container.bookStorage.filesDir
        filesRoot.mkdirs()
        val epub = LocalReaderEpubFixture.writeEpub(filesRoot)

        // A real section-1 locator from a probe session over
        // the same fixture publication.
        val locator1 = runBlocking {
            val probe = LocalReaderEpubFixture.openSession(filesRoot, epub)
            try {
                probe.locatorForSection(1)
            } finally {
                probe.close()
            }
        }
        assertNotNull(locator1)

        val bookId = runBlocking {
            container.database.bookDao().insert(
                Book(
                    title = "Flush Book",
                    sourceType = SourceType.LOCAL_FILE,
                    localFormat = BookFormat.EPUB,
                    filePath = epub.relativeTo(filesRoot).path,
                    addedAt = 1L, updatedAt = 1L
                )
            )
        }

        val viewModel = LocalDocReaderViewModel(container, bookId, null)
        val readyDeadline = System.currentTimeMillis() + 60_000L
        var ready = false
        while (System.currentTimeMillis() < readyDeadline) {
            if (viewModel.state.value is LocalReaderState.Ready) {
                ready = true
                break
            }
            Thread.sleep(50L)
        }
        assertTrue("reader reached Ready", ready)

        // No progress row exists before any position report.
        val before = runBlocking {
            container.database.readingProgressDao().getForBook(bookId)
        }
        assertTrue("no progress before the report", before == null)

        // Report section 1: position derives, the 800 ms
        // debounce is now pending — then flush, as ON_STOP does.
        viewModel.onEpubLocator(locator1!!)
        viewModel.flushPendingProgressSave()

        val deadline = System.currentTimeMillis() + 30_000L
        var saved = runBlocking {
            container.database.readingProgressDao().getForBook(bookId)
        }
        while ((saved == null || saved.chapterIndex != 1) &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(50L)
            saved = runBlocking {
                container.database.readingProgressDao().getForBook(bookId)
            }
        }
        assertNotNull("the flush landed the progress row", saved)
        assertEquals(1, saved!!.chapterIndex)
        assertTrue(
            "the row carries the reported locator",
            !saved.locatorJson.isNullOrBlank()
        )

        // A flush with nothing pending is a no-op (no crash,
        // no duplicate write path).
        viewModel.flushPendingProgressSave()
    }
}
