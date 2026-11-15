package app.xreader.data.ai

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.net.HttpTransport
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Cloudflare FLUX (Patch 10, Addendum 1): FLUX.1 [schnell] on
 * Workers AI, bring-your-own account. The free tier is 10,000
 * neurons a day (reset 00:00 UTC) — roughly 230 pictures a day at
 * 512×512 / 4 steps. One POST, a base64 JPEG back; no queue.
 *
 * The account id is a plain setting (AiPrefs); the API token is a
 * secret and only ever arrives here from the KeyVault. When this
 * engine fails or its daily allowance is gone, the router falls
 * back to Horde — this client's error words are a last resort
 * that the fallback normally covers.
 */
class CloudflareImageClient(
    private val transport: HttpTransport,
    private val accountIdProvider: suspend () -> String?,
    private val tokenProvider: suspend () -> String?
) : ImagePainter {

    override suspend fun isConfigured(): Boolean =
        !accountIdProvider().isNullOrBlank() && !tokenProvider().isNullOrBlank()

    override suspend fun paint(prompt: String): PaintOutcome {
        val accountId = accountIdProvider()?.takeIf { it.isNotBlank() }
        val token = tokenProvider()?.takeIf { it.isNotBlank() }
        if (accountId == null || token == null) {
            return PaintOutcome.Failed(
                AppError.NeutralVerdict("Cloudflare isn't set up yet.")
            )
        }
        val result = transport.execute(
            HttpTransport.Request(
                url = "https://api.cloudflare.com/client/v4/accounts/$accountId" +
                    "/ai/run/@cf/black-forest-labs/flux-1-schnell",
                method = "POST",
                headers = mapOf(
                    "Authorization" to "Bearer $token",
                    "Content-Type" to "application/json"
                ),
                body = buildJsonObject {
                    put("prompt", prompt)
                    put("steps", STEPS)
                }.toString(),
                callTimeoutSeconds = 120
            )
        )
        val response = when (result) {
            is AppResult.Err -> return PaintOutcome.Failed(notAnsweringError())
            is AppResult.Ok -> result.value
        }
        val bytes = runCatching { response.body?.readBytes() }.getOrNull()
            ?: ByteArray(0)
        runCatching { response.body?.close() }
        if (response.code == 429) {
            return PaintOutcome.Failed(allowanceError(), allowanceExhausted = true)
        }
        if (response.code == 401 || response.code == 403) {
            return PaintOutcome.Failed(
                AppError.NeutralVerdict("Cloudflare didn't accept that token.")
            )
        }
        if (response.code !in 200..299) return PaintOutcome.Failed(notAnsweringError())
        val image = CloudflareProtocol.parseImage(bytes.toString(Charsets.UTF_8))
            ?: return PaintOutcome.Failed(notAnsweringError())
        return PaintOutcome.Painted(
            GeminiClient.ImageResult(
                bytes = image,
                mimeType = "image/jpeg",
                modelUsed = "Cloudflare · FLUX.1 schnell"
            )
        )
    }

    private fun notAnsweringError(): AppError =
        AppError.NeutralVerdict("Cloudflare isn't answering right now.")

    private fun allowanceError(): AppError =
        AppError.NeutralVerdict("Cloudflare is out of its free allowance for today.")

    companion object {
        /** FLUX.1 [schnell] is a distilled few-step model; the
         * endpoint caps steps at 8. 4 is the draft-quality point
         * the free allowance stretches furthest at. */
        const val STEPS = 4
    }
}

/** The pure response shape: {"success":true,"result":{"image":
 * "<base64 jpeg>"}}. Anything else is not a picture. */
object CloudflareProtocol {

    fun parseImage(body: String): ByteArray? {
        val root = runCatching {
            AiJson.parser.parseToJsonElement(body) as? JsonObject
        }.getOrNull() ?: return null
        val success = (root["success"] as? JsonPrimitive)?.boolean ?: false
        if (!success) return null
        val result = (root["result"] as? JsonObject) ?: return null
        val base64 = result.stringField("image") ?: return null
        return runCatching { Base64.getMimeDecoder().decode(base64) }.getOrNull()
    }
}
