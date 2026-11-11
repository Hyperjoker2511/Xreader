package app.xreader.ui.localreader

import android.app.Application
import app.xreader.data.db.Book
import app.xreader.data.db.BookFormat
import app.xreader.data.db.SourceType
import app.xreader.di.AppContainer
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.5.10 (G3/FXL, VERIFY-ONLY): the fixed-layout treatment
 * ALREADY SHIPS (v1.5.8 G10) — this class pins the whole
 * behaviour end-to-end so the verify is a regression gate,
 * not a re-build: the honest in-reader label
 * (EpubBookReader), detection from the publication's own
 * metadata (ReadiumEpubSession), and the paginated default
 * the ViewModel applies for an FXL book with no stored
 * scroll choice (LocalDocReaderViewModel) — driven here at
 * the real ViewModel level over a real container, beside a
 * reflowable control whose scroll default stands.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V1510FxlShippedBehaviourTest {

    @get:Rule val temp = TemporaryFolder()

    private fun openBook(
        container: AppContainer,
        fixedLayout: Boolean
    ): Pair<LocalDocReaderViewModel, LocalReaderState.Ready> {
        val filesRoot = container.bookStorage.filesDir
        filesRoot.mkdirs()
        val epub = LocalReaderEpubFixture.writeEpub(
            filesRoot, fixedLayout = fixedLayout
        )
        val bookId = runBlocking {
            container.database.bookDao().insert(
                Book(
                    title = "FXL Pin Book",
                    sourceType = SourceType.LOCAL_FILE,
                    localFormat = BookFormat.EPUB,
                    filePath = epub.relativeTo(filesRoot).path,
                    addedAt = 1L, updatedAt = 1L
                )
            )
        }
        val viewModel = LocalDocReaderViewModel(container, bookId, null)
        val deadline = System.currentTimeMillis() + 60_000L
        var ready: LocalReaderState.Ready? = null
        while (System.currentTimeMillis() < deadline) {
            val state = viewModel.state.value
            if (state is LocalReaderState.Ready) {
                ready = state
                break
            }
            check(state !is LocalReaderState.Failed) {
                "open failed: ${(state as LocalReaderState.Failed).message}"
            }
            Thread.sleep(50)
        }
        return viewModel to (ready ?: error("open never reached Ready"))
    }

    @Test
    fun `fxl book opens paginated by default and flagged fixed layout`() {
        val app = RuntimeEnvironment.getApplication()
        val container = AppContainer(app)
        val (viewModel, ready) = openBook(container, fixedLayout = true)
        assertTrue(
            "the Ready state carries the fixed-layout flag",
            ready.isFixedLayout
        )
        assertFalse(
            "an FXL book with no stored scroll choice opens PAGINATED",
            viewModel.epubSettings.value.scroll
        )
    }

    @Test
    fun `reflowable control keeps the scroll default`() {
        val app = RuntimeEnvironment.getApplication()
        val container = AppContainer(app)
        val (viewModel, ready) = openBook(container, fixedLayout = false)
        assertFalse(ready.isFixedLayout)
        assertTrue(
            "a reflowable book keeps the shipped scroll default",
            viewModel.epubSettings.value.scroll
        )
    }

    @Test
    fun `the honest label ships in the reader, gated on the flag`() {
        val source = sourceFile("EpubBookReader.kt").readText()
        assertTrue(
            "the label renders only for a fixed-layout book",
            source.contains("if (ready.isFixedLayout)")
        )
        assertTrue(
            "the label is the plain-words shipped copy",
            source.contains("\"Fixed-layout book\"") &&
                source.contains("\"Pages are fixed as published\"")
        )
        assertEquals(
            "detection reads the publication presentation layout",
            1,
            sourceFile(
                "../../data/engine/ReadiumEpubSession.kt",
                "app/src/main/java/app/xreader/data/engine/ReadiumEpubSession.kt"
            ).readText().split("EpubLayout.FIXED").size - 1
        )
    }

    private fun sourceFile(name: String): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(6) {
            val candidate = File(
                dir,
                "app/src/main/java/app/xreader/ui/localreader/$name"
            )
            if (candidate.isFile) return candidate
            val direct = File(
                dir, "src/main/java/app/xreader/ui/localreader/$name"
            )
            if (direct.isFile) return direct
            dir = dir.parentFile ?: return@repeat
        }
        error("cannot locate $name from ${System.getProperty("user.dir")}")
    }

    private fun sourceFile(name: String, path: String): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(6) {
            val candidate = File(dir, path)
            if (candidate.isFile) return candidate
            val stripped = File(dir, path.removePrefix("app/"))
            if (stripped.isFile) return stripped
            dir = dir.parentFile ?: return@repeat
        }
        error("cannot locate $path from ${System.getProperty("user.dir")}")
    }
}
