package app.xreader.data.ai

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.prefs.AiSettings
import app.xreader.data.prefs.ImageEngine
import java.util.concurrent.atomic.AtomicReference

/**
 * One picture engine's outcome (Patch 10). The engines speak this
 * instead of [AppResult] so the router can tell an exhausted free
 * allowance apart from any other failure — the fallback note the
 * user reads depends on the difference.
 */
sealed interface PaintOutcome {
    data class Painted(val image: GeminiClient.ImageResult) : PaintOutcome

    data class Failed(
        val error: AppError,
        /** True when the engine's free allowance is used up (its
         * quota/credit refusal), as opposed to it merely failing. */
        val allowanceExhausted: Boolean = false
    ) : PaintOutcome
}

/** One Illustrate picture engine (Horde, Cloudflare, Pollinations). */
interface ImagePainter {
    /** Can this engine run at all right now (its key/account is
     * saved)? Horde always can — it is keyless. */
    suspend fun isConfigured(): Boolean

    suspend fun paint(prompt: String): PaintOutcome
}

/**
 * The fallback-note channel (Patch 10): when the picked engine
 * couldn't paint and Horde painted instead, the router leaves one
 * plain-word note here and the surface that asked for the picture
 * (the Lore screen, the Ask AI sheet) takes it at completion and
 * shows it with its success message. One note at a time is enough:
 * a screen runs one generation at a time, and a note that is never
 * taken is simply replaced by the next fallback's.
 */
object ImageFallbackNotes {
    private val pending = AtomicReference<String?>(null)

    fun record(note: String) {
        pending.set(note)
    }

    /** The pending note, cleared — each note is shown once. */
    fun take(): String? = pending.getAndSet(null)
}

/**
 * The Illustrate engine route (Patch 10, the owner's decision:
 * Horde is the normal engine; the better engines are a setting).
 *
 * The picked engine paints. ANY failure on it — or its free
 * allowance running out, or it not being set up yet — falls back
 * to Horde so a picture still arrives, and the fallback leaves
 * its plain-word note in [ImageFallbackNotes]. When Horde itself
 * is the pick (or the fallback), its own plain error is the
 * answer: there is no third engine to hide behind.
 */
class ImageEngineRouter(
    private val horde: ImagePainter,
    private val cloudflare: ImagePainter,
    private val pollinations: ImagePainter,
    private val settingsProvider: suspend () -> AiSettings
) {

    suspend fun generate(prompt: String): AppResult<GeminiClient.ImageResult> {
        val engine = settingsProvider().imageEngine
        if (engine == ImageEngine.HORDE) return paintOrError(horde, prompt)
        // The note names the service the way a person would say
        // it ("Cloudflare is out of its free allowance…"), not
        // the picker's longer row label.
        val (painter, label) = when (engine) {
            ImageEngine.CLOUDFLARE -> cloudflare to "Cloudflare"
            ImageEngine.POLLINATIONS -> pollinations to "Pollinations"
            ImageEngine.HORDE -> error("unreachable: Horde handled above")
        }
        if (!painter.isConfigured()) {
            return paintViaHorde(
                prompt,
                note = "$label isn't set up yet — painted with the free engine instead."
            )
        }
        return when (val outcome = painter.paint(prompt)) {
            is PaintOutcome.Painted -> AppResult.Ok(outcome.image)
            is PaintOutcome.Failed -> paintViaHorde(
                prompt,
                note = if (outcome.allowanceExhausted) {
                    "$label is out of its free allowance — " +
                        "painted with the free engine instead."
                } else {
                    "$label couldn't paint that one — " +
                        "painted with the free engine instead."
                }
            )
        }
    }

    /** The fallback leg: Horde paints, and the note is recorded
     * ONLY when it actually did — a note promising a picture that
     * never arrived would be a lie. When Horde fails too, its own
     * plain error is the result and no note is left behind. */
    private suspend fun paintViaHorde(
        prompt: String,
        note: String
    ): AppResult<GeminiClient.ImageResult> =
        when (val outcome = horde.paint(prompt)) {
            is PaintOutcome.Painted -> {
                ImageFallbackNotes.record(note)
                AppResult.Ok(outcome.image)
            }
            is PaintOutcome.Failed -> AppResult.Err(outcome.error)
        }

    private suspend fun paintOrError(
        painter: ImagePainter,
        prompt: String
    ): AppResult<GeminiClient.ImageResult> =
        when (val outcome = painter.paint(prompt)) {
            is PaintOutcome.Painted -> AppResult.Ok(outcome.image)
            is PaintOutcome.Failed -> AppResult.Err(outcome.error)
        }
}
