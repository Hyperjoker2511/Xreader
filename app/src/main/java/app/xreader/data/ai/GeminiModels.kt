package app.xreader.data.ai

/**
 * The Gemini model layer (PLAN §7) — written around the hard
 * ReTranslate lesson: Google retired the gemini-2.5 models for NEW
 * users in Sept 2026, and a single pinned model ID silently broke the
 * sibling app. So every Gemini call walks a lite-first cascade,
 * stepping to the next model ONLY on a model-not-available 404 (auth
 * and quota failures surface immediately — waiting or switching models
 * cannot heal them), and the model that last worked is remembered for
 * the session so steady-state calls start there.
 */
object GeminiModels {

    /** Lite-first cascade — cheapest capable model first (§0.1). */
    val CASCADE: List<String> = listOf(
        "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite",
        "gemini-3.8-flash",
        "gemini-3.6-flash"
    )

    /** Image-generation candidates for Concept Art (§7). Availability
     * on a free key is NOT guaranteed (assumption A5) — the caller
     * cascades and fails in plain words if none answers. */
    val IMAGE_CASCADE: List<String> = listOf(
        "gemini-3.1-flash-image",
        "gemini-2.5-flash-image"
    )

    /** Picker value meaning "walk the cascade". */
    const val AUTO = "AUTO"

    fun isKnownModel(model: String): Boolean = model in CASCADE

    /**
     * The order to try models in. A remembered [sessionModel] (the one
     * that last answered) goes first, then the rest of the cascade in
     * order. An explicit picker choice goes first the same way — if it
     * has since been retired, the cascade still saves the call.
     */
    fun attemptOrder(pickerChoice: String?, sessionModel: String?): List<String> {
        val first = when {
            pickerChoice != null && pickerChoice != AUTO && pickerChoice in CASCADE ->
                pickerChoice
            sessionModel != null && sessionModel in CASCADE -> sessionModel
            else -> null
        }
        return if (first == null) CASCADE else listOf(first) + CASCADE.filter { it != first }
    }

    fun attemptOrderForImages(sessionModel: String?): List<String> =
        if (sessionModel != null && sessionModel in IMAGE_CASCADE) {
            listOf(sessionModel) + IMAGE_CASCADE.filter { it != sessionModel }
        } else {
            IMAGE_CASCADE
        }

    /**
     * True only for "this model isn't available to you" answers —
     * HTTP 404 whose body blames the model. Any other failure (auth,
     * quota, 5xx, a 404 about something else) must NOT step the
     * cascade: it surfaces to the user as-is.
     */
    fun isModelUnavailable(httpCode: Int, errorBody: String?): Boolean {
        if (httpCode != 404) return false
        val body = errorBody?.lowercase() ?: return false
        if (!body.contains("model")) return false
        return body.contains("no longer available") ||
            body.contains("not found") ||
            body.contains("not supported")
    }

    /** Google answers a bad key with HTTP 400 + API_KEY_INVALID. */
    fun isKeyInvalid(httpCode: Int, errorBody: String?): Boolean {
        if (httpCode != 400) return false
        val body = errorBody ?: return false
        return body.contains("API_KEY_INVALID") ||
            body.contains("api key not valid", ignoreCase = true)
    }

    /** Google's own error message out of an error body, capped for the
     * Details line (PLAN §1.5: HTTP code + ≤200 chars of their words). */
    fun googleMessage(errorBody: String?): String? {
        if (errorBody.isNullOrBlank()) return null
        val parsed = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(errorBody)
                .let { it as? kotlinx.serialization.json.JsonObject }
                ?.get("error")
                ?.let { it as? kotlinx.serialization.json.JsonObject }
                ?.get("message")
                ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                ?.content
        }.getOrNull()
        return (parsed ?: errorBody).replace(Regex("\\s+"), " ").trim()
            .take(200)
            .ifBlank { null }
    }
}
