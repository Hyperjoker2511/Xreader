package app.xreader.data.engine

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v1.5.9 (Finding 3 §8.5 / F-6): the cache stores RAW renders
 * only — the filter is a draw-time colour filter in the cell.
 * Toggling the filter therefore produces ZERO new provider
 * renders and the SAME cache key/bitmap; the key carries the
 * format version (`v2`) and the disk artefact is JPEG, never
 * the old per-filter PNG.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V159PdfFilterDrawTimeTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `filter toggles never re-render and share one raw cache entry`() = runBlocking {
        val provider = V159PdfSessionFixtures.CountingProvider()
        val session = V159PdfSessionFixtures.openSession(temp.root, 204, provider)
        try {
            val plain = session.renderPage(3, 540, PdfPageFilter.NONE, 2f, priority = true)
            assertNotNull(plain)
            assertEquals(1, provider.rendersOf(3))

            val night = session.renderPage(
                3, 540, PdfPageFilter.INVERT_BLACK_AND_WHITE, 2f, priority = true
            )
            val sepia = session.renderPage(
                3, 540, PdfPageFilter.SEPIA_PAGE, 2f, priority = true
            )
            assertSame("night toggle returns the RAW cached bitmap", plain, night)
            assertSame("sepia toggle returns the RAW cached bitmap", plain, sepia)
            assertEquals(
                "zero new provider renders across filter toggles",
                1, provider.rendersOf(3)
            )

            // The one cache key: page + render width + v2 — no
            // filter component, and its disk artefact is JPEG.
            assertEquals("p3-1080-v2", session.renderCacheKey(3, 1080))
            // A-3: the disk encode is FIRE-AND-FORGET — the
            // bitmap above was already returned before any
            // encode; the artefact lands asynchronously.
            kotlinx.coroutines.withTimeout(15_000) {
                while (V159PdfSessionFixtures.cacheDirOf(temp.root, 204)
                        .listFiles().orEmpty()
                        .none { it.name == "p3-1080-v2.jpg" }
                ) {
                    kotlinx.coroutines.delay(25)
                }
            }
            val files = V159PdfSessionFixtures.cacheDirOf(temp.root, 204)
                .listFiles().orEmpty()
            assertTrue(
                "the v2 JPEG artefact exists: ${files.map { it.name }}",
                files.any { it.name == "p3-1080-v2.jpg" }
            )
            assertTrue(
                "no PNG-era artefacts are written",
                files.none { it.name.endsWith(".png") }
            )
            assertEquals(
                "the disk tier cap is the F-2 96 MB",
                96L * 1024 * 1024, PdfSession.CACHE_CAP_BYTES
            )
            assertEquals(
                "the memory tier is the F-3 48 MB",
                48L * 1024 * 1024, PdfSession.MEMORY_CACHE_CAP_BYTES
            )
        } finally {
            session.close()
        }
    }
}
