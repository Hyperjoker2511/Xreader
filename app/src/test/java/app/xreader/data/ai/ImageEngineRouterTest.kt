package app.xreader.data.ai

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.prefs.AiSettings
import app.xreader.data.prefs.ImageEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Patch 10: the engine-choice routing + fallback decision. The
 * picked engine paints; any failure, an exhausted allowance, or
 * an engine that isn't set up falls back to Horde WITH the
 * plain-word note left in [ImageFallbackNotes]; when Horde itself
 * fails, its error is the answer and no note is left behind.
 */
class ImageEngineRouterTest {

    private class FakePainter(
        var configured: Boolean = true,
        var outcome: PaintOutcome
    ) : ImagePainter {
        var calls = 0
        override suspend fun isConfigured(): Boolean = configured
        override suspend fun paint(prompt: String): PaintOutcome {
            calls++
            return outcome
        }
    }

    private val painted = PaintOutcome.Painted(
        GeminiClient.ImageResult("IMG".toByteArray(), "image/webp", "fake engine")
    )
    private val hordeDown = PaintOutcome.Failed(
        AppError.NeutralVerdict(HordeImageClient.NOT_ANSWERING_WORDS)
    )

    private fun router(
        horde: FakePainter,
        cloudflare: FakePainter = FakePainter(outcome = painted),
        pollinations: FakePainter = FakePainter(outcome = painted),
        engine: ImageEngine
    ) = ImageEngineRouter(
        horde = horde,
        cloudflare = cloudflare,
        pollinations = pollinations,
        settingsProvider = { AiSettings(imageEngine = engine) }
    )

    @Before
    fun clearNotes() {
        ImageFallbackNotes.take()
    }

    @Test
    fun `horde is the default route and leaves no note`() = runBlocking {
        val horde = FakePainter(outcome = painted)
        val cloudflare = FakePainter(outcome = painted)
        val result = router(horde, cloudflare, engine = ImageEngine.HORDE)
            .generate("a lighthouse")
        assertTrue(result is AppResult.Ok)
        assertEquals(1, horde.calls)
        assertEquals(0, cloudflare.calls)
        assertNull(ImageFallbackNotes.take())
    }

    @Test
    fun `a configured picked engine paints alone`() = runBlocking {
        val horde = FakePainter(outcome = painted)
        val cloudflare = FakePainter(outcome = painted)
        val result = router(horde, cloudflare, engine = ImageEngine.CLOUDFLARE)
            .generate("a lighthouse")
        assertTrue(result is AppResult.Ok)
        assertEquals(1, cloudflare.calls)
        assertEquals(0, horde.calls)
        assertNull(ImageFallbackNotes.take())
    }

    @Test
    fun `a picked engine failure falls back to horde with the plain note`() =
        runBlocking {
            val horde = FakePainter(outcome = painted)
            val cloudflare = FakePainter(
                outcome = PaintOutcome.Failed(
                    AppError.NeutralVerdict("Cloudflare isn't answering right now.")
                )
            )
            val result = router(horde, cloudflare, engine = ImageEngine.CLOUDFLARE)
                .generate("a lighthouse")
            assertTrue(result is AppResult.Ok)
            assertEquals(1, horde.calls)
            assertEquals(
                "Cloudflare couldn't paint that one — " +
                    "painted with the free engine instead.",
                ImageFallbackNotes.take()
            )
        }

    @Test
    fun `an exhausted allowance gets the allowance note`() = runBlocking {
        val horde = FakePainter(outcome = painted)
        val cloudflare = FakePainter(
            outcome = PaintOutcome.Failed(
                AppError.NeutralVerdict("Cloudflare is out of its free allowance for today."),
                allowanceExhausted = true
            )
        )
        val result = router(horde, cloudflare, engine = ImageEngine.CLOUDFLARE)
            .generate("a lighthouse")
        assertTrue(result is AppResult.Ok)
        assertEquals(
            "Cloudflare is out of its free allowance — " +
                "painted with the free engine instead.",
            ImageFallbackNotes.take()
        )
    }

    @Test
    fun `an engine that is not set up is never called`() = runBlocking {
        val horde = FakePainter(outcome = painted)
        val cloudflare = FakePainter(configured = false, outcome = painted)
        val result = router(horde, cloudflare, engine = ImageEngine.CLOUDFLARE)
            .generate("a lighthouse")
        assertTrue(result is AppResult.Ok)
        assertEquals(0, cloudflare.calls)
        assertEquals(1, horde.calls)
        assertEquals(
            "Cloudflare isn't set up yet — painted with the free engine instead.",
            ImageFallbackNotes.take()
        )
    }

    @Test
    fun `pollinations without a key is never called`() = runBlocking {
        val horde = FakePainter(outcome = painted)
        val pollinations = FakePainter(configured = false, outcome = painted)
        val result = router(
            horde, pollinations = pollinations, engine = ImageEngine.POLLINATIONS
        ).generate("a lighthouse")
        assertTrue(result is AppResult.Ok)
        assertEquals(0, pollinations.calls)
        assertEquals(
            "Pollinations isn't set up yet — painted with the free engine instead.",
            ImageFallbackNotes.take()
        )
    }

    @Test
    fun `when the fallback also fails its error is the answer and no note remains`() =
        runBlocking {
            val horde = FakePainter(outcome = hordeDown)
            val pollinations = FakePainter(
                outcome = PaintOutcome.Failed(
                    AppError.NeutralVerdict("Pollinations isn't answering right now.")
                )
            )
            val result = router(
                horde, pollinations = pollinations, engine = ImageEngine.POLLINATIONS
            ).generate("a lighthouse")
            assertTrue(result is AppResult.Err)
            assertEquals(
                HordeImageClient.NOT_ANSWERING_WORDS,
                ((result as AppResult.Err).error as AppError.NeutralVerdict).message
            )
            assertNull(ImageFallbackNotes.take())
        }
}
