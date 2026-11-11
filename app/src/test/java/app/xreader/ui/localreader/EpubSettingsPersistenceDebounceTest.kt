package app.xreader.ui.localreader

import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.9 (F-4a, Finding 4 P3): EPUB settings persistence is
 * debounced 500 ms like the PDF path. The mechanism under test
 * is the production [DebouncedSettingsSave] the ViewModel's
 * `updateEpubSettings` submits through: 50 rapid submits land
 * EXACTLY ONE save inside the window, carrying the FINAL
 * value; state-side, the VM applies the value instantly (the
 * structural assertion below pins that the VM still sets its
 * state flow synchronously and routes persistence through the
 * debounced saver rather than the old per-tick launch).
 *
 * v1.5.10 gate adjudication (I6b): the structural slice is
 * bounded to `updateEpubSettings`' OWN body (up to the next
 * top-level function). The pre-fix slice ran to
 * `updatePdfSettings` and so spanned v1.5.10 G3's
 * `setEpubUserFontAndReopen`, whose documented one-shot
 * `localPrefs.saveEpub` (the save must land synchronously
 * before the reopen reads it back) is a designed immediate
 * save, NOT the per-tick path this test prohibits. The
 * prohibition itself is unchanged and still asserted on the
 * per-tick path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EpubSettingsPersistenceDebounceTest {

    @Test
    fun `fifty rapid submits land one save with the final value`() = runTest {
        val saves = mutableListOf<EpubBookSettings>()
        val saver = DebouncedSettingsSave<EpubBookSettings>(
            scope = this,
            delayMs = 500L,
            save = { saves += it }
        )
        var last = EpubBookSettings()
        repeat(50) { i ->
            last = EpubBookSettings(fontSize = 1.0 + i * 0.01)
            saver.submit(last)
        }
        runCurrent()
        assertTrue("nothing saved before the window closes", saves.isEmpty())
        advanceTimeBy(499L); runCurrent()
        assertTrue("nothing saved inside the window", saves.isEmpty())
        advanceTimeBy(1L); runCurrent()
        assertEquals("exactly one save in the window", 1, saves.size)
        assertEquals("the saved value is the final one", last, saves.single())
        // A later, separate change saves again — debounce delays
        // a save, it never swallows one.
        val next = EpubBookSettings(fontSize = 2.0)
        saver.submit(next)
        advanceTimeBy(500L); runCurrent()
        assertEquals(2, saves.size)
        assertEquals(next, saves.last())
    }

    @Test
    fun `view model applies state instantly and persists through the debounced saver`() {
        val source = sourceFile("LocalDocReaderViewModel.kt").readText()
        val updateBody = source
            .substringAfter("fun updateEpubSettings(")
            .substringBefore("\n    fun ")
        assertTrue(
            "state applies synchronously",
            updateBody.contains("_epubSettings.value = next")
        )
        assertTrue(
            "persistence routes through the debounced saver",
            updateBody.contains("epubSave.submit(next)")
        )
        assertTrue(
            "the old per-tick DataStore launch is gone from this path",
            !updateBody.contains("localPrefs.saveEpub")
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
            val direct = File(dir, "src/main/java/app/xreader/ui/localreader/$name")
            if (direct.isFile) return direct
            dir = dir.parentFile ?: return@repeat
        }
        error("cannot locate $name from ${System.getProperty("user.dir")}")
    }
}
