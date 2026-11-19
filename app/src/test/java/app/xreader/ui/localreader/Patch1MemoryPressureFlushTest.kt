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
 * v1.7 Patch 1 (item 6b): reading state is flushed on
 * memory pressure. Mirrors V159ProgressFlushTest's proof
 * shape — under Robolectric the main looper is paused, so
 * the debounce's Main-dispatcher delay cannot fire while
 * this test polls; the flush's commit rides the container's
 * IO dispatcher (real threads), so a row landing inside the
 * poll window came from the flush. The only difference from
 * that test is the TRIGGER: here the simulated trim signal
 * ([AppContainer.signalMemoryPressure] — what
 * XreaderApp.onTrimMemory / onLowMemory emit) flushes the
 * pending save, not a direct flush call and not ON_STOP.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class Patch1MemoryPressureFlushTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `memory pressure signal flushes the pending progress save`() {
        val app = RuntimeEnvironment.getApplication()
        val container = AppContainer(app)
        val filesRoot = container.bookStorage.filesDir
        filesRoot.mkdirs()
        // Post-Patch-3, the book stands on the local road as a
        // fixed-layout book (a convertible reflowable book
        // diverts to the core at open); the memory-pressure
        // flush under test is layout-agnostic.
        val epub = LocalReaderEpubFixture.writeEpub(
            filesRoot, fixedLayout = true
        )

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
                    title = "Pressure Book",
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
        // debounce is now pending — then the simulated trim.
        viewModel.onEpubLocator(locator1!!)
        container.signalMemoryPressure()

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
        assertNotNull("the trim flushed the progress row", saved)
        assertEquals(1, saved!!.chapterIndex)
        assertTrue(
            "the row carries the reported locator",
            !saved.locatorJson.isNullOrBlank()
        )

        // A signal with nothing pending is a no-op (no crash,
        // no duplicate write path).
        container.signalMemoryPressure()
    }
}
