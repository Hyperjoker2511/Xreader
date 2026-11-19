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
import org.readium.r2.shared.publication.Locator
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.7 Patch 2 (item 0b): the exit save takes the LIVE place.
 *
 * The loss this pins: the reader moves a little within one
 * 0.1% total-progression bucket, so the derivation gate
 * suppresses the report; the derived position still holds the
 * OLD place; and the exit flush — which used to fire only for
 * a live debounce job — no-ops. Exit → reopen lands at the old
 * place (or the chapter top). Two roads now carry the live
 * place out, and both are pinned here:
 *
 *  1. the shared flush re-derives the kept raw locator under
 *     the force flag before it decides (this is what the
 *     ON_STOP sequence calls);
 *  2. a report made with `forceDerive = true` (the river's
 *     final-pull road) derives past the gate directly.
 *
 * Robolectric mechanics mirror V159ProgressFlushTest: the main
 * looper is paused, so the 800 ms debounce cannot fire while
 * the test polls — a row landing inside the poll window came
 * from the flush's IO-dispatcher commit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class Patch2EpubExitSaveTest {

    @get:Rule val temp = TemporaryFolder()

    private class Rig(
        val container: AppContainer,
        val viewModel: LocalDocReaderViewModel,
        val bookId: Long,
        val base: Locator
    ) {
        /** A section-1 locator at the given in-section
         * progression / total progression. */
        fun locatorAt(progression: Double, totalProgression: Double): Locator =
            base.copy(
                locations = base.locations.copy(
                    progression = progression,
                    totalProgression = totalProgression
                )
            )

        fun savedScrollFraction(): Float? = runBlocking {
            container.database.readingProgressDao()
                .getForBook(bookId)?.scrollFraction
        }

        /** Poll until the stored row's scrollFraction reads
         * [expected] (within tolerance) or the deadline runs
         * out; returns the last value seen. */
        fun awaitScrollFraction(expected: Float): Float? {
            val deadline = System.currentTimeMillis() + 30_000L
            var seen = savedScrollFraction()
            while ((seen == null || kotlin.math.abs(seen - expected) > 0.001f) &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(50L)
                seen = savedScrollFraction()
            }
            return seen
        }
    }

    private fun rig(): Rig {
        val app = RuntimeEnvironment.getApplication()
        val container = AppContainer(app)
        val filesRoot = container.bookStorage.filesDir
        filesRoot.mkdirs()
        val epub = LocalReaderEpubFixture.writeEpub(filesRoot)
        val base = runBlocking {
            val probe = LocalReaderEpubFixture.openSession(filesRoot, epub)
            try {
                probe.locatorForSection(1)
            } finally {
                probe.close()
            }
        }
        assertNotNull(base)
        val bookId = runBlocking {
            container.database.bookDao().insert(
                Book(
                    title = "Exit Save Book",
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
        return Rig(container, viewModel, bookId, base!!)
    }

    @Test
    fun `exit flush writes the gate-suppressed live place`() {
        val rig = rig()
        // A first place, derived and flushed (the ON_STOP call).
        rig.viewModel.onEpubLocator(rig.locatorAt(0.30, 0.1000))
        rig.viewModel.flushPendingProgressSave()
        assertEquals(
            0.30f, rig.awaitScrollFraction(0.30f) ?: -1f, 0.001f
        )
        // A small movement INSIDE the same 0.1% bucket (total
        // 0.1000 → 0.1004): the gate suppresses the derivation,
        // so the VM's derived position still holds 0.30 and no
        // debounce job is pending.
        rig.viewModel.onEpubLocator(rig.locatorAt(0.55, 0.1004))
        // The exit flush must still write the LIVE place.
        rig.viewModel.flushPendingProgressSave()
        assertEquals(
            "the flush writes the live (gate-suppressed) place",
            0.55f, rig.awaitScrollFraction(0.55f) ?: -1f, 0.001f
        )
    }

    @Test
    fun `forced report derives past the bucket gate`() {
        val rig = rig()
        rig.viewModel.onEpubLocator(rig.locatorAt(0.30, 0.1000))
        rig.viewModel.flushPendingProgressSave()
        assertEquals(
            0.30f, rig.awaitScrollFraction(0.30f) ?: -1f, 0.001f
        )
        // The river's final pull reports through the force
        // road: same bucket, but the derivation runs anyway.
        rig.viewModel.onEpubLocator(
            rig.locatorAt(0.55, 0.1004), forceDerive = true
        )
        rig.viewModel.flushPendingProgressSave()
        assertEquals(
            0.55f, rig.awaitScrollFraction(0.55f) ?: -1f, 0.001f
        )
    }
}
