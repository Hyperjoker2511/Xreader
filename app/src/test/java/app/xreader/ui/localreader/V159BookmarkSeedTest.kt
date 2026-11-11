package app.xreader.ui.localreader

import android.app.Application
import app.xreader.data.db.Book
import app.xreader.data.db.BookFormat
import app.xreader.data.db.ReadingProgress
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
 * v1.5.9 (F-9, Finding 4 B-2): bookmark-here works from the
 * first second. Before ANY locator emission (this test never
 * calls `onEpubLocator` — the exact dead window the owner hit),
 * `bookmarkHere` falls back to the Ready state's seed: the
 * initial index (here section 1, from the saved progress) and
 * the initial locator. The "wait for the page to settle"
 * message remains only when no seed exists either.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V159BookmarkSeedTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `bookmark here before any locator inserts at the initial section`() {
        val app = RuntimeEnvironment.getApplication()
        val container = AppContainer(app)
        val filesRoot = container.bookStorage.filesDir
        filesRoot.mkdirs()
        val epub = LocalReaderEpubFixture.writeEpub(filesRoot)

        // The seed locator: section 1's real Readium locator,
        // taken from a probe session over the same fixture.
        val seedJson = runBlocking {
            val probe = LocalReaderEpubFixture.openSession(filesRoot, epub)
            try {
                probe.locatorForSection(1)!!.toJSON().toString()
            } finally {
                probe.close()
            }
        }

        val bookId = runBlocking {
            val id = container.database.bookDao().insert(
                Book(
                    title = "Seed Book",
                    sourceType = SourceType.LOCAL_FILE,
                    localFormat = BookFormat.EPUB,
                    filePath = epub.relativeTo(filesRoot).path,
                    addedAt = 1L, updatedAt = 1L
                )
            )
            container.database.readingProgressDao().upsert(
                ReadingProgress(
                    bookId = id, chapterId = 0, chapterIndex = 1,
                    charOffset = 0, fraction = 0.5f, scrollFraction = 0f,
                    updatedAt = 2L, locatorJson = seedJson,
                    anchorText = "Chapter Two"
                )
            )
            id
        }

        val viewModel = LocalDocReaderViewModel(container, bookId, null)
        // Wait for the real open to reach Ready.
        val deadline = System.currentTimeMillis() + 60_000L
        var ready: LocalReaderState.Ready? = null
        while (System.currentTimeMillis() < deadline) {
            val state = viewModel.state.value
            if (state is LocalReaderState.Ready) {
                ready = state
                break
            }
            Thread.sleep(50L)
        }
        assertNotNull("reader reached Ready", ready)
        assertEquals(1, ready!!.initialIndex)
        assertNotNull(ready.initialEpubLocator)

        // NO onEpubLocator call — position is still unset, the
        // shipped code's dead window. The seed must carry it.
        viewModel.bookmarkHere()

        val insertDeadline = System.currentTimeMillis() + 30_000L
        var bookmarks = runBlocking {
            container.database.bookmarkDao().forBook(bookId)
        }
        while (bookmarks.isEmpty() &&
            System.currentTimeMillis() < insertDeadline
        ) {
            Thread.sleep(50L)
            bookmarks = runBlocking {
                container.database.bookmarkDao().forBook(bookId)
            }
        }
        assertEquals("exactly one bookmark inserted", 1, bookmarks.size)
        val bookmark = bookmarks.single()
        assertEquals(
            "bookmark sits at the initial (seed) section",
            ready.initialIndex, bookmark.chapterIndex
        )
        assertEquals("Chapter Two", bookmark.label)
        assertTrue(
            "bookmark carries the seed locator",
            !bookmark.locatorJson.isNullOrBlank()
        )
    }
}
