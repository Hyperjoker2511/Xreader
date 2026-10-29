package app.xreader.data.ai.hub

import app.xreader.core.error.AppError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The shared failure vocabulary for the non-Gemini engines (v1.0.3):
 * the same mapping [app.xreader.data.ai.GeminiClient.httpError]
 * applies to Google — HTTP code + the service's own short message in
 * the Details line (≤200 chars), auth failures and rate limits as
 * their own types so the UI's plain words stay identical no matter
 * which provider failed.
 */
object AiHttp {

    private val json = Json { ignoreUnknownKeys = true }

    /** The service's own error message out of an error body, trying
     * the OpenAI shape (`error.message`), the Anthropic shape (same
     * nesting) and a bare `message`, capped for the Details line. */
    fun errorMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val parsed = runCatching {
            val root = json.parseToJsonElement(body) as? JsonObject
            val error = root?.get("error") as? JsonObject
            (error?.get("message") as? JsonPrimitive)?.content
                ?: (root?.get("message") as? JsonPrimitive)?.content
        }.getOrNull()
        return (parsed ?: body).replace(Regex("\\s+"), " ").trim()
            .take(200)
            .ifBlank { null }
    }

    fun httpError(serviceId: String, code: Int, body: String?): AppError {
        val message = errorMessage(body)
        val detail = "HTTP $code" + (message?.let { ": $it" } ?: "")
        return when {
            code == 401 || code == 403 -> AppError.AuthFailed(serviceId, detail)
            code == 429 -> AppError.RateLimited(serviceId)
            code in 500..599 -> AppError.ProviderDown(serviceId, code, true, detail)
            else -> AppError.ProviderDown(serviceId, code, false, detail)
        }
    }

    /** The no-key error every keyed engine returns before any request. */
    fun noKeyError(serviceId: String): AppError =
        AppError.AuthFailed(serviceId, "no key is saved for $serviceId")

    /** The no-model error: a key without a chosen model can't run. */
    fun noModelError(serviceId: String): AppError =
        AppError.BadFile(
            "No model is chosen for $serviceId yet — open Providers & keys " +
                "and pick one."
        )
}
