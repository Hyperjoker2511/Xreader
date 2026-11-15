package app.xreader.data.ai

import android.graphics.BitmapFactory
import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.net.HttpTransport
import java.util.Base64
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * AI Horde (Patch 10): the free, keyless Illustrate engine — a
 * volunteer-run Stable Diffusion swarm. Anonymous use (the
 * "0000000000" key) is a first-class Horde mode: no account, no
 * key, pictures at 512×512 (the anonymous size cap is 580×580).
 *
 * The flow: POST /generate/async → poll /generate/check/{id}
 * honouring its wait_time → GET /generate/status/{id} for the
 * finished picture (the `img` field is a URL or a base64 string;
 * both are handled).
 *
 * Failures are plain words, always (owner rule): the service not
 * answering and the service being busy are the two sentences this
 * client ever returns — never a provider code, never raw JSON.
 */
class HordeImageClient(
    private val transport: HttpTransport,
    /** Poll sleep — injected so tests run the loop on virtual time. */
    private val delayMs: suspend (Long) -> Unit = { delay(it) },
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** A FRESH seed per attempt, as digits. Serialised as a JSON
     * string in the request — proven live 2026-11-15: a numeric
     * seed is rejected by Horde's payload validation. */
    private val seedProvider: () -> String = {
        Random.nextInt(0, Int.MAX_VALUE).toString()
    },
    /** The censored-frame judgement over finished bytes — the
     * real pixel detector on device, a fixture in tests. */
    private val frameInspector: (ByteArray) -> Boolean =
        CensoredFrames::isCensoredImage
) : ImagePainter {

    override suspend fun isConfigured(): Boolean = true

    override suspend fun paint(prompt: String): PaintOutcome {
        var seed = seedProvider()
        var retried = false
        while (true) {
            when (val round = paintOnce(prompt, seed)) {
                is Round.Failed -> return round.outcome
                is Round.Done -> {
                    // Two censor signals: the service's own flag
                    // and the pixel detector over the real bytes.
                    // Either one means the frame is not shown.
                    if (!round.serviceCensored && !frameInspector(round.image.bytes)) {
                        return PaintOutcome.Painted(round.image)
                    }
                    // A censored frame is never shown as art: one
                    // retry with a fresh seed, then the plain word.
                    if (retried) {
                        return PaintOutcome.Failed(AppError.NeutralVerdict(CENSORED_WORDS))
                    }
                    retried = true
                    seed = seedProvider()
                }
            }
        }
    }

    /** One submit → poll → status round for one seed. Unlike the
     * public [PaintOutcome], the painted variant also carries the
     * service's own censored flag for [paint]'s retry decision. */
    private sealed interface Round {
        data class Done(
            val image: GeminiClient.ImageResult,
            val serviceCensored: Boolean
        ) : Round

        data class Failed(val outcome: PaintOutcome.Failed) : Round
    }

    private suspend fun paintOnce(prompt: String, seed: String): Round {
        val startedAt = nowMs()
        val submit = execute(
            HttpTransport.Request(
                url = "$BASE_URL/generate/async",
                method = "POST",
                headers = mapOf(
                    "apikey" to ANONYMOUS_KEY,
                    "Content-Type" to "application/json"
                ),
                body = HordeProtocol.submitBody(prompt, seed),
                callTimeoutSeconds = 60
            )
        )
        val submitBody = when (submit) {
            is Fetch.Failed -> return Round.Failed(submit.outcome)
            is Fetch.Answered -> {
                if (submit.code == 429 || submit.code == 503) {
                    return Round.Failed(
                        PaintOutcome.Failed(busyError(), allowanceExhausted = true)
                    )
                }
                if (submit.code !in 200..299) {
                    return Round.Failed(PaintOutcome.Failed(notAnsweringError()))
                }
                submit.text
            }
        }
        val jobId = HordeProtocol.parseSubmitId(submitBody)
            ?: return Round.Failed(PaintOutcome.Failed(notAnsweringError()))

        // Poll until done, faulted, or the overall deadline.
        while (true) {
            if (nowMs() - startedAt > OVERALL_TIMEOUT_MS) {
                return Round.Failed(PaintOutcome.Failed(notAnsweringError()))
            }
            when (val checkFetch = execute(
                HttpTransport.Request(url = "$BASE_URL/generate/check/$jobId")
            )) {
                is Fetch.Failed -> {
                    // A transport-level failure mid-poll is not
                    // yet a verdict: the job may still finish.
                    // Keep polling inside the same deadline.
                }
                is Fetch.Answered -> {
                    if (checkFetch.code == 404) {
                        return Round.Failed(PaintOutcome.Failed(notAnsweringError()))
                    }
                    if (checkFetch.code in 200..299) {
                        val check = HordeProtocol.parseCheck(checkFetch.text)
                            ?: return Round.Failed(PaintOutcome.Failed(notAnsweringError()))
                        if (check.faulted) {
                            // The queue itself rejected the job —
                            // the busy sentence (this mapping is
                            // what SERVICE_BUSY exists for).
                            return Round.Failed(PaintOutcome.Failed(busyError()))
                        }
                        if (check.done) break
                        val waitSeconds = (check.waitTimeSeconds ?: DEFAULT_POLL_SECONDS)
                            .coerceIn(MIN_POLL_SECONDS, MAX_POLL_SECONDS)
                        delayMs(waitSeconds * 1000)
                        continue
                    }
                    // A non-2xx check (a 5xx blip, a 429): keep
                    // polling inside the deadline rather than
                    // killing a job that may still finish.
                }
            }
            delayMs(DEFAULT_POLL_SECONDS * 1000)
        }

        val status = execute(
            HttpTransport.Request(url = "$BASE_URL/generate/status/$jobId")
        )
        val statusBody = when (status) {
            is Fetch.Failed -> return Round.Failed(status.outcome)
            is Fetch.Answered -> {
                if (status.code == 429 || status.code == 503) {
                    return Round.Failed(
                        PaintOutcome.Failed(busyError(), allowanceExhausted = true)
                    )
                }
                if (status.code !in 200..299) {
                    return Round.Failed(PaintOutcome.Failed(notAnsweringError()))
                }
                status.text
            }
        }
        val generation = HordeProtocol.parseGeneration(statusBody)
            ?: return Round.Failed(PaintOutcome.Failed(notAnsweringError()))
        val image = when (val fetched = fetchImageBytes(generation.img)) {
            is Fetch.Failed -> return Round.Failed(fetched.outcome)
            is Fetch.Answered -> fetched
        }
        return Round.Done(
            image = GeminiClient.ImageResult(
                bytes = image.bytes,
                mimeType = image.mimeType ?: "image/webp",
                modelUsed = "AI Horde · ${generation.model ?: DEFAULT_MODEL}"
            ),
            serviceCensored = generation.censored
        )
    }

    /** Resolves a status `img` value to bytes: a direct download
     * for the URL form, a decode for the base64 form. */
    private suspend fun fetchImageBytes(img: String): Fetch {
        if (img.startsWith("http://") || img.startsWith("https://")) {
            val fetched = execute(HttpTransport.Request(url = img, callTimeoutSeconds = 60))
            return when (fetched) {
                is Fetch.Failed -> fetched
                is Fetch.Answered -> Fetch.Answered(
                    code = fetched.code,
                    text = "",
                    bytes = fetched.bytes,
                    mimeType = fetched.mimeType ?: "image/webp"
                )
            }
        }
        // Base64 — possibly a data URI. Horde's default format is
        // WebP; a data URI names its own type.
        val dataUri = Regex("""^data:([^;,]+);base64,(.*)$""", RegexOption.DOT_MATCHES_ALL)
            .matchEntire(img.trim())
        val mime = dataUri?.groupValues?.get(1) ?: "image/webp"
        val payload = dataUri?.groupValues?.get(2) ?: img.trim()
        val bytes = runCatching { Base64.getMimeDecoder().decode(payload) }.getOrNull()
            ?: return Fetch.Failed(PaintOutcome.Failed(notAnsweringError()))
        return Fetch.Answered(code = 200, text = "", bytes = bytes, mimeType = mime)
    }

    /** One transport round, normalised: an answered response
     * (any status) with its body read, or a plain-word failure
     * for a transport-level error. */
    private sealed interface Fetch {
        data class Answered(
            val code: Int,
            val text: String,
            val bytes: ByteArray = ByteArray(0),
            val mimeType: String? = null
        ) : Fetch

        data class Failed(val outcome: PaintOutcome.Failed) : Fetch
    }

    private suspend fun execute(request: HttpTransport.Request): Fetch =
        when (val result = transport.execute(request)) {
            is AppResult.Err -> Fetch.Failed(PaintOutcome.Failed(notAnsweringError()))
            is AppResult.Ok -> {
                val response = result.value
                val bytes = runCatching { response.body?.readBytes() }
                    .getOrNull() ?: ByteArray(0)
                runCatching { response.body?.close() }
                Fetch.Answered(
                    code = response.code,
                    text = bytes.toString(Charsets.UTF_8),
                    bytes = bytes,
                    mimeType = response.headers.entries
                        .firstOrNull { it.key.equals("Content-Type", true) }
                        ?.value?.substringBefore(';')?.trim()
                )
            }
        }

    companion object {
        const val BASE_URL = "https://stablehorde.net/api/v2"
        const val ANONYMOUS_KEY = "0000000000"
        const val DEFAULT_MODEL = "stable_diffusion"
        const val OVERALL_TIMEOUT_MS = 180_000L
        const val DEFAULT_POLL_SECONDS = 5L
        const val MIN_POLL_SECONDS = 5L
        const val MAX_POLL_SECONDS = 60L

        const val NOT_ANSWERING_WORDS = "The picture service isn't answering right now."
        const val BUSY_WORDS = "The picture service is busy — try again in a bit."
        const val CENSORED_WORDS =
            "The picture came back censored, so it wasn't saved — try a different moment."

        fun notAnsweringError(): AppError = AppError.NeutralVerdict(NOT_ANSWERING_WORDS)
        fun busyError(): AppError = AppError.NeutralVerdict(BUSY_WORDS)
    }
}

/** The pure wire shapes of the Horde flow — request building and
 * response parsing, independently testable, no transport. */
object HordeProtocol {

    /**
     * The async-submit body. The seed goes in as a JSON STRING —
     * proven live (2026-11-15): Horde's payload validation
     * rejects a numeric seed outright, while the same submit with
     * a string seed is accepted. Every other field is the
     * decision's contract: 512×512 (anonymous cap 580×580),
     * 20 steps, k_euler_a, cfg 7.0, nsfw off, censor on.
     */
    fun submitBody(prompt: String, seed: String): String =
        buildJsonObject {
            put("prompt", prompt)
            put("params", buildJsonObject {
                put("n", 1)
                put("width", 512)
                put("height", 512)
                put("steps", 20)
                put("sampler_name", "k_euler_a")
                put("cfg_scale", 7.0)
                put("seed", seed)
            })
            put("nsfw", false)
            put("censor_nsfw", true)
            put("models", buildJsonArray {
                add(JsonPrimitive(HordeImageClient.DEFAULT_MODEL))
            })
        }.toString()

    /** The job id from a submit response, or null when the body
     * doesn't carry one. */
    fun parseSubmitId(body: String): String? {
        val root = parseObject(body) ?: return null
        return root.stringField("id")
    }

    data class Check(
        val done: Boolean,
        val faulted: Boolean,
        val waitTimeSeconds: Long?
    )

    fun parseCheck(body: String): Check? {
        val root = parseObject(body) ?: return null
        val done = (root["done"] as? JsonPrimitive)?.boolean ?: return null
        val faulted = (root["faulted"] as? JsonPrimitive)?.boolean ?: false
        val wait = (root["wait_time"] as? JsonPrimitive)
            ?.content?.toDoubleOrNull()?.toLong()
        return Check(done = done, faulted = faulted, waitTimeSeconds = wait)
    }

    data class Generation(
        val img: String,
        val model: String?,
        /** The service's own censor verdict for this frame. */
        val censored: Boolean
    )

    /** generations[0] of a status response, or null when the
     * status carries no finished picture. */
    fun parseGeneration(body: String): Generation? {
        val root = parseObject(body) ?: return null
        val generations = (root["generations"] as? JsonArray) ?: return null
        val first = generations.firstOrNull() as? JsonObject ?: return null
        val img = first.stringField("img") ?: return null
        val censored = (first["censored"] as? JsonPrimitive)?.boolean ?: false
        return Generation(
            img = img,
            model = first.stringField("model"),
            censored = censored
        )
    }

    private fun parseObject(body: String): JsonObject? =
        runCatching {
            AiJson.parser.parseToJsonElement(body) as? JsonObject
        }.getOrNull()
}

/**
 * The censored-frame detector (Patch 10). Horde's censor does not
 * return a plain black frame — measured live (2026-11-15) it
 * returns a black CARD bearing white "CENSORED" text: mean luma
 * 22.6, variance ~4,791, but 89.7% of pixels near-black. So a
 * frame is censored when EITHER the plain-black test passes
 * (mean < 8 AND variance < 4) OR the near-black fraction (luma
 * < 16) reaches 0.75 — the card's shape. A merely dark picture
 * (a night scene, ~60% near-black with real mid-tones) passes
 * neither and is shown.
 */
object CensoredFrames {

    const val NEAR_BLACK_LUMA = 16
    const val NEAR_BLACK_FRACTION = 0.75
    const val PLAIN_BLACK_MEAN = 8.0
    const val PLAIN_BLACK_VARIANCE = 4.0

    /** The pure judgement over sampled luminance values (0–255). */
    fun isCensored(lumas: IntArray): Boolean {
        if (lumas.isEmpty()) return false
        var sum = 0L
        var nearBlack = 0
        for (luma in lumas) {
            sum += luma
            if (luma < NEAR_BLACK_LUMA) nearBlack++
        }
        if (nearBlack.toDouble() / lumas.size >= NEAR_BLACK_FRACTION) return true
        val mean = sum.toDouble() / lumas.size
        var squares = 0.0
        for (luma in lumas) {
            val d = luma - mean
            squares += d * d
        }
        val variance = squares / lumas.size
        return mean < PLAIN_BLACK_MEAN && variance < PLAIN_BLACK_VARIANCE
    }

    /** The device judgement over finished image bytes: decode
     * small, sample luminance, judge. Undecodable bytes are not
     * flagged — storage/transcoding already has its own answer
     * for those, and a broken picture is not a censored one. */
    fun isCensoredImage(bytes: ByteArray): Boolean =
        runCatching { lumasOf(bytes)?.let { isCensored(it) } ?: false }
            .getOrDefault(false)

    /** Luminance samples of an image, decoded at a small size —
     * the detector needs the frame's shape, not its detail. */
    fun lumasOf(bytes: ByteArray): IntArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 64 &&
            bounds.outHeight / (sample * 2) >= 64
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return null
        try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            return IntArray(pixels.size) { i ->
                val px = pixels[i]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                (299 * r + 587 * g + 114 * b) / 1000
            }
        } finally {
            bitmap.recycle()
        }
    }
}
