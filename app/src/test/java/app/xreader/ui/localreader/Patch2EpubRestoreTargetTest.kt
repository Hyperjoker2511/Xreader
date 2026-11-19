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
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.7 Patch 2 (item 0f): the restore-target fallback.
 *
 * `saveProgressNow` writes the row's `scrollFraction` as the
 * in-section progression, but nothing on the EPUB restore path
 * read it — so a saved locator whose progression did not
 * survive (or a section-start fallback locator) restored to
 * the chapter top even though the row knew the place. The
 * rule: when the resolved initial locator carries NO
 * in-section progression, the restore target's progression is
 * taken from the saved row's scrollFraction for that chapter.
 * It fires only when progression is absent, and only for the
 * chapter the row names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class Patch2EpubRestoreTargetTest {

    @get:Rule val temp = TemporaryFolder()

    /** Open a fresh VM over a book whose stored row carries
     * [locatorJson] + [scrollFraction], and return the Ready
     * state's initial locator. */
    private fun initialLocatorFor(
        locatorJson: String,
        rowChapterIndex: Int,
        scrollFraction: Float
    ): org.readium.r2.shared.publication.Locator? {
        val app = RuntimeEnvironment.getApplication()
        val container = AppContainer(app)
        val filesRoot = container.bookStorage.filesDir
        filesRoot.mkdirs()
        val epub = LocalReaderEpubFixture.writeEpub(filesRoot)
        val bookId = runBlocking {
            val id = container.database.bookDao().insert(
                Book(
                    title = "Restore Target Book",
                    sourceType = SourceType.LOCAL_FILE,
                    localFormat = BookFormat.EPUB,
                    filePath = epub.relativeTo(filesRoot).path,
                    addedAt = 1L, updatedAt = 1L
                )
            )
            container.database.readingProgressDao().upsert(
                ReadingProgress(
                    bookId = id,
                    chapterId = 0,
                    chapterIndex = rowChapterIndex,
                    charOffset = 0,
                    fraction = 0.21f,
                    scrollFraction = scrollFraction,
                    updatedAt = 1L,
                    locatorJson = locatorJson
                )
            )
            id
        }
        val viewModel = LocalDocReaderViewModel(container, bookId, null)
        val deadline = System.currentTimeMillis() + 60_000L
        while (System.currentTimeMillis() < deadline) {
            val state = viewModel.state.value
            if (state is LocalReaderState.Ready) {
                return state.initialEpubLocator
            }
            Thread.sleep(50L)
        }
        error("reader never reached Ready")
    }

    /** A section-1 locator JSON with the given in-section
     * progression (null = stripped, the 0f input shape). */
    private fun sectionOneJson(progression: Double?): String {
        val app = RuntimeEnvironment.getApplication()
        val container = AppContainer(app)
        val filesRoot = container.bookStorage.filesDir
        filesRoot.mkdirs()
        val epub = LocalReaderEpubFixture.writeEpub(filesRoot)
        return runBlocking {
            val probe = LocalReaderEpubFixture.openSession(filesRoot, epub)
            try {
                val base = probe.locatorForSection(1)!!
                base.copy(
                    locations = base.locations.copy(
                        progression = progression,
                        totalProgression = 0.21
                    )
                ).toJSON().toString()
            } finally {
                probe.close()
            }
        }
    }

    @Test
    fun `progression-stripped locator is repaired from the row scrollFraction`() {
        val initial = initialLocatorFor(
            locatorJson = sectionOneJson(progression = null),
            rowChapterIndex = 1,
            scrollFraction = 0.42f
        )
        assertNotNull(initial)
        assertEquals(
            "the restore target's progression is the row's scrollFraction",
            0.42, initial!!.locations.progression!!, 0.001
        )
    }

    @Test
    fun `a locator carrying progression is never repaired over`() {
        val initial = initialLocatorFor(
            locatorJson = sectionOneJson(progression = 0.25),
            rowChapterIndex = 1,
            scrollFraction = 0.42f
        )
        assertNotNull(initial)
        assertEquals(
            0.25, initial!!.locations.progression!!, 0.001
        )
    }

    @Test
    fun `the repair does not cross chapters`() {
        // The row names chapter 0 (index 0) but its locator
        // points at section 1: the resolved target is section
        // 1's, and the row's scrollFraction — chapter 0's place
        // — must not be painted onto it.
        val initial = initialLocatorFor(
            locatorJson = sectionOneJson(progression = null),
            rowChapterIndex = 0,
            scrollFraction = 0.42f
        )
        assertNotNull(initial)
        assertNull(initial!!.locations.progression)
    }

    @Test
    fun `zero scrollFraction leaves the honest chapter start`() {
        val initial = initialLocatorFor(
            locatorJson = sectionOneJson(progression = null),
            rowChapterIndex = 1,
            scrollFraction = 0f
        )
        assertNotNull(initial)
        assertNull(initial!!.locations.progression)
    }
}
