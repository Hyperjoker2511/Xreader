package app.xreader.data.ai.hub

import app.xreader.core.security.KeyVault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The provider registry (v1.0.3, AI hub) — the same mental model as
 * ReTranslate's hub, in Xreader's shapes.
 *
 * A provider is a PROTOCOL plus an address plus a key slot. The app
 * ships five built-ins (Gemini, OpenAI, Claude, the APInex gateway,
 * and keyless local Ollama); any other service that speaks one of the
 * two standard wire shapes (OpenAI-compatible or Anthropic-compatible)
 * can be added by the user pasting its site — see [ProviderDiscovery].
 * There is deliberately NO allow-list of sites: gateways come and go,
 * the protocols stay.
 */
enum class AiProtocol { GEMINI, OPENAI_COMPATIBLE, ANTHROPIC, OLLAMA }

data class AiProviderDef(
    val id: String,
    /** The name the user sees, on cards and on the engine chip. */
    val name: String,
    val protocol: AiProtocol,
    /** API root. For OPENAI_COMPATIBLE it ends in `/v1`; for
     * ANTHROPIC it is the root the client appends `/v1/…` to (a
     * trailing `/v1` is tolerated — the client de-duplicates). */
    val baseUrl: String,
    /** KeyVault entry name, or null for keyless providers (Ollama). */
    val keyVaultName: String?,
    /** Used until the live model list replaces it; user-changeable. */
    val defaultModel: String?,
    /** One plain line shown on the provider's card. */
    val blurb: String,
    val builtIn: Boolean
)

/** A user-added provider, persisted (WITHOUT its key — the key lives
 * in the KeyVault under [KeyVault.providerKey]). */
@Serializable
data class CustomProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val protocol: AiProtocol
) {
    fun toDef(): AiProviderDef = AiProviderDef(
        id = id,
        name = name,
        protocol = protocol,
        baseUrl = baseUrl,
        keyVaultName = KeyVault.providerKey(id),
        defaultModel = null,
        blurb = "Added by you from $baseUrl.",
        builtIn = false
    )
}

object AiProviders {

    const val GEMINI_ID = "gemini"
    const val OPENAI_ID = "openai"
    const val CLAUDE_ID = "claude"
    const val APINEX_ID = "apinex"
    const val OLLAMA_ID = "ollama"

    /** Registry order = the Automatic routing order. Gemini first
     * (it is the app's original engine and its free key is the one
     * most users have), then the other built-ins, then user-added
     * providers in the order they were added, Ollama last (it is
     * the most likely to be unreachable from a phone). */
    val BUILT_INS: List<AiProviderDef> = listOf(
        AiProviderDef(
            id = GEMINI_ID,
            name = "Google Gemini",
            protocol = AiProtocol.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            keyVaultName = KeyVault.GEMINI_KEY,
            defaultModel = null,
            blurb = "Google's models. A free key from AI Studio powers " +
                "every AI extra, including concept art (images).",
            builtIn = true
        ),
        AiProviderDef(
            id = OPENAI_ID,
            name = "OpenAI",
            protocol = AiProtocol.OPENAI_COMPATIBLE,
            baseUrl = "https://api.openai.com/v1",
            keyVaultName = KeyVault.OPENAI_KEY,
            defaultModel = "gpt-5-mini",
            blurb = "OpenAI's models, with your own OpenAI API key.",
            builtIn = true
        ),
        AiProviderDef(
            id = CLAUDE_ID,
            name = "Claude",
            protocol = AiProtocol.ANTHROPIC,
            baseUrl = "https://api.anthropic.com",
            keyVaultName = KeyVault.CLAUDE_KEY,
            defaultModel = "claude-haiku-4-5",
            blurb = "Anthropic's Claude models, with your own Anthropic API key.",
            builtIn = true
        ),
        AiProviderDef(
            id = APINEX_ID,
            name = "APInex",
            protocol = AiProtocol.OPENAI_COMPATIBLE,
            baseUrl = "https://api.apinex.bond/v1",
            keyVaultName = KeyVault.APINEX_KEY,
            defaultModel = "free/deepseek-v4.1-flash",
            blurb = "A third-party gateway with a promotional free tier " +
                "(20M free tokens a day on its free models, as listed by " +
                "APInex — the offer is theirs and can change). Your text " +
                "passes through their servers.",
            builtIn = true
        ),
        AiProviderDef(
            id = OLLAMA_ID,
            name = "Ollama (your computer)",
            protocol = AiProtocol.OLLAMA,
            baseUrl = "",
            keyVaultName = null,
            defaultModel = null,
            blurb = "A model running on your own computer via Ollama — " +
                "free, private, and offline from the internet. Needs the " +
                "computer on and reachable from this phone.",
            builtIn = true
        )
    )

    /** Every provider the user could pick: built-ins, then customs. */
    fun allDefs(customs: List<CustomProvider>): List<AiProviderDef> =
        BUILT_INS + customs.map { it.toDef() }

    fun defFor(id: String, customs: List<CustomProvider>): AiProviderDef? =
        allDefs(customs).firstOrNull { it.id == id }

    /** A fresh id for a user-added provider (collision-free by time). */
    fun newCustomId(nowMillis: Long): String = "custom-$nowMillis"
}

/**
 * "Listed as free" — the badge rule for gateway model lists. A model
 * counts as listed-free only when the PROVIDER's own listing says so:
 * a `free/` id prefix (APInex style), a `:free` suffix (OpenRouter
 * style), an explicit free flag, or zero pricing in the entry. The
 * badge always reads "Listed as free by <provider>" — it is their
 * claim, surfaced as theirs, never our guarantee.
 */
object FreeBadge {

    fun isListedFree(modelId: String, entry: JsonObject?): Boolean {
        if (modelId.startsWith("free/", ignoreCase = true)) return true
        if (modelId.endsWith(":free", ignoreCase = true)) return true
        if (entry == null) return false
        val flag = entry["free"] as? JsonPrimitive
        if (flag?.content?.equals("true", ignoreCase = true) == true) return true
        val pricing = entry["pricing"] as? JsonObject ?: return false
        val prompt = (pricing["prompt"] as? JsonPrimitive)?.content?.toDoubleOrNull()
        val completion = (pricing["completion"] as? JsonPrimitive)?.content
            ?.toDoubleOrNull()
        return prompt == 0.0 && completion == 0.0
    }
}

/**
 * Turning what a user pastes (a homepage, an API address, a bare
 * domain) into the candidate API roots the test-drive probes, in
 * order. Pure and unit-tested — the probe itself adds no state, so a
 * failed check provably adds nothing.
 */
object ProviderUrls {

    /** A pasted site → a clean absolute URL, or null when it doesn't
     * look like one at all. */
    fun normaliseSiteInput(raw: String): String? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        if (!text.contains("://")) text = "https://$text"
        val uri = runCatching { java.net.URI(text) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (!host.contains('.') && host != "localhost") return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        val path = (uri.path ?: "").trimEnd('/')
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "$scheme://$host$port$path"
    }

    /**
     * Candidate API roots for [url], most likely first: the address
     * itself when it already ends in `/v1`, then `+/v1`, then
     * `+/api/v1` (OpenRouter's shape), then the bare address.
     */
    fun candidateBases(url: String): List<String> {
        val clean = url.trimEnd('/')
        val candidates = mutableListOf<String>()
        if (clean.endsWith("/v1")) candidates += clean
        candidates += "$clean/v1"
        candidates += "$clean/api/v1"
        candidates += clean
        return candidates.distinct()
    }

    /** Where an Anthropic-shaped service keeps its model list,
     * given an API root that may or may not end in `/v1`. */
    fun anthropicModelsUrl(base: String): String =
        if (base.trimEnd('/').endsWith("/v1")) {
            "${base.trimEnd('/')}/models"
        } else {
            "${base.trimEnd('/')}/v1/models"
        }

    /** Where an Anthropic-shaped service takes messages. */
    fun anthropicMessagesUrl(base: String): String =
        if (base.trimEnd('/').endsWith("/v1")) {
            "${base.trimEnd('/')}/messages"
        } else {
            "${base.trimEnd('/')}/v1/messages"
        }

    /** A friendly provider name from a site address (its host). */
    fun displayNameFor(url: String): String =
        runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
            .removePrefix("www.")
            .removePrefix("api.")
}
