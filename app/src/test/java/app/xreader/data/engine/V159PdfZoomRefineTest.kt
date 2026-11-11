package app.xreader.data.engine

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v1.5.9 (sift A-2 AMENDED, §8 addition): the cheaper 1.0×
 * base ships WITH its refine — this test is the sharpness
 * proof. A settled zoom renders the visible clip ONCE, at
 * dimensions strictly denser than the base render's same
 * region, capped at 4 MP; the refine is cached under its own
 * pass bucket and evicted on demand. The 0.35× preview pass
 * renders at its own scale under its own bucket.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V159PdfZoomRefineTest {

    @get:Rule val temp = TemporaryFolder()

    // The visible clip of a page zoomed 2× onto its top-right
    // quarter: normalised (0.5, 0) → (1, 0.5) of a 396×612 page.
    private val clip = PdfPageClip(0.5f, 0f, 1f, 0.5f)

    @Test
    fun `settled zoom refines the clip once, denser than base, capped, cached, evictable`() =
        runBlocking {
            val provider = V159PdfSessionFixtures.CountingProvider()
            val session = V159PdfSessionFixtures.openSession(temp.root, 214, provider)
            try {
                session.publishPagerState(settledPage = 3, targetPage = 3)
                // The 1.0× base (the reader's new base width
                // for a 1080 px fit of this page is 1080).
                val base = session.renderPage(
                    3, 1080, PdfPageFilter.NONE, 1f, slot = PdfRenderSlot.P0
                )
                assertNotNull(base)
                assertEquals(1080, base!!.width)
                assertEquals(1, provider.rendersOf(3))

                // The settle fires ONE refine for the clip,
                // which occupies 800 px of screen width.
                val refined = session.renderRefinedClip(3, clip, clipWidthPx = 800)
                assertNotNull(refined)
                assertEquals(listOf(3), provider.clipCalls.toList())
                // Final bitmap dimensions: the clip is
                // 198×306 pt → at 800 px wide, 1236 px tall.
                assertEquals(800, refined!!.width)
                assertEquals(1236, refined.height)
                assertTrue(
                    "refine stays under the 4 MP cap",
                    refined.width.toLong() * refined.height <=
                        PdfSession.REFINE_MAX_PIXELS
                )
                // Density proof: the base render's same region
                // is 0.5 × 1080 = 540 px wide; the refine's 800
                // is the sharpness the cheaper base promised.
                assertTrue(refined.width > (base.width * clip.widthFraction).toInt())

                // A second settle on the same clip is a cache
                // hit — the settled-zoom render count stays 1.
                val again = session.renderRefinedClip(3, clip, clipWidthPx = 800)
                assertNotNull(again)
                assertEquals(
                    "settled-zoom refine render count stays 1",
                    1, provider.clipCalls.size
                )

                // Zoom-out eviction: the next settle re-renders.
                session.evictRefinements(3)
                val third = session.renderRefinedClip(3, clip, clipWidthPx = 800)
                assertNotNull(third)
                assertEquals(2, provider.clipCalls.size)

                // The buckets are distinct cache entries.
                assertEquals(
                    "p3-1080-v2", session.renderCacheKey(3, 1080, PdfRenderPass.BASE)
                )
                assertEquals(
                    "p3-800-v2-${clip.bucket()}",
                    session.renderCacheKey(3, 800, PdfRenderPass.REFINE, clip.bucket())
                )
            } finally {
                session.close()
            }
        }

    @Test
    fun `preview pass renders at 0_35 scale under its own bucket`() = runBlocking {
        val provider = V159PdfSessionFixtures.CountingProvider()
        val session = V159PdfSessionFixtures.openSession(temp.root, 215, provider)
        try {
            session.publishPagerState(settledPage = 8, targetPage = 8)
            val preview = session.renderPreview(8, baseWidthPx = 1080)
            assertNotNull(preview)
            assertEquals(
                "preview width is 0.35× the base width",
                (1080 * PdfSession.PREVIEW_SCALE).toInt(), preview!!.width
            )
            assertEquals(1, provider.rendersOf(8))
            assertEquals(
                "p8-378-v2-p35",
                session.renderCacheKey(8, 378, PdfRenderPass.PREVIEW)
            )
        } finally {
            session.close()
        }
    }
}
