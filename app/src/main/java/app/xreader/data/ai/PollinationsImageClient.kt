package app.xreader.data.ai

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.net.HttpTransport
import java.net.URLEncoder
import kotlin.random.Random

/**
 * Pollinations FLUX (Patch 10, Addendum 1) — KEYED ONLY. Verified
 * at design time: anonymous Pollinations no longer serves FLUX at
 * all (its model list is a different model and `model=flux` is
 * silently ignored), so this client NEVER calls the service
 * without the user's free key (from enter.pollinations.ai, held
 * in the KeyVault). With a key, FLUX is genuinely served and
 * free (a "Pollen" balance).
 *
 * The wire format lives in [PollinationsWire] alone, so if the
 * service's shape moves, the fix is one small object.
 */
class PollinationsImageClient(
    private val transport: HttpTransport,
    private val keyProvider: suspend () -> String?,
    private val seedProvider: () -> Int = { Random.nextInt(0, 1_000_000) }
) : ImagePainter {

    override suspend fun isConfigured(): Boolean =
        !keyProvider().isNullOrBlank()

    override suspend fun paint(prompt: String): PaintOutcome {
        val key = keyProvider()?.takeIf { it.isNotBlank() }
            ?: return PaintOutcome.Failed(
                AppError.NeutralVerdict("Pollinations needs its key first.")
            )
        val result = transport.execute(
            HttpTransport.Request(
                url = PollinationsWire.imageUrl(prompt, seedProvider()),
                headers = mapOf("Authorization" to "Bearer $key"),
                callTimeoutSeconds = 180
            )
        )
        val response = when (result) {
            is AppResult.Err -> return PaintOutcome.Failed(notAnsweringError())
            is AppResult.Ok -> result.value
        }
        val bytes = runCatching { response.body?.readBytes() }.getOrNull()
            ?: ByteArray(0)
        runCatching { response.body?.close() }
        return when {
            response.code == 402 -> PaintOutcome.Failed(
                allowanceError(),
                allowanceExhausted = true
            )
            response.code == 401 || response.code == 403 -> PaintOutcome.Failed(
                AppError.NeutralVerdict("Pollinations didn't accept that key.")
            )
            response.code !in 200..299 || bytes.isEmpty() ->
                PaintOutcome.Failed(notAnsweringError())
            else -> PaintOutcome.Painted(
                GeminiClient.ImageResult(
                    bytes = bytes,
                    mimeType = response.headers.entries
                        .firstOrNull { it.key.equals("Content-Type", true) }
                        ?.value?.substringBefore(';')?.trim()
                        ?.takeIf { it.startsWith("image/") }
                        ?: "image/jpeg",
                    modelUsed = "Pollinations · FLUX"
                )
            )
        }
    }

    private fun notAnsweringError(): AppError =
        AppError.NeutralVerdict("Pollinations isn't answering right now.")

    private fun allowanceError(): AppError =
        AppError.NeutralVerdict("Pollinations is out of its free allowance.")

    companion object {
        const val BASE_URL = "https://gen.pollinations.ai/image"
    }
}

/** The one place that knows Pollinations' URL shape: the prompt
 * rides in the path (percent-encoded), the model and size ride
 * in the query. */
object PollinationsWire {

    fun imageUrl(prompt: String, seed: Int): String {
        val encoded = URLEncoder.encode(prompt, "UTF-8").replace("+", "%20")
        return "${PollinationsImageClient.BASE_URL}/$encoded" +
            "?model=flux&width=512&height=512&seed=$seed"
    }
}
