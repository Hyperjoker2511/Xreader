package app.xreader.data.ai.hub

import app.xreader.data.prefs.EnginePick

/**
 * The picking rule, as pure logic (v1.0.3) — the piece every AI
 * surface's engine chip is a picture of:
 *
 *  - **Automatic**: the hub may route across providers — the attempt
 *    order is every CONFIGURED provider, in registry order. Trying
 *    the next provider after a failure is allowed, and the result
 *    says which provider answered.
 *  - **Specific**: the attempt list is exactly the picked provider —
 *    one entry, configured or not (an unconfigured pick produces its
 *    own plain no-key error, never a quiet detour to another
 *    provider). A pick naming a provider that no longer exists
 *    resolves to an EMPTY list, which the hub reports plainly
 *    instead of substituting anything.
 *
 * Pure and unit-tested: no I/O, no Android.
 */
object EngineRouting {

    /** One provider's live state, in registry order. */
    data class ProviderState(
        val id: String,
        val name: String,
        /** A key is saved (or, for Ollama, a model is named). */
        val configured: Boolean,
        /** The model a call would use right now, when known. */
        val model: String?
    )

    /** The ordered providers a call may attempt under [pick]. */
    fun order(
        pick: EnginePick,
        providers: List<ProviderState>
    ): List<ProviderState> = when (pick) {
        EnginePick.Automatic -> providers.filter { it.configured }
        is EnginePick.Specific ->
            providers.filter { it.id == pick.providerId }.take(1)
    }

    /** True when [pick] pins one provider (no routing allowed). */
    fun isSpecific(pick: EnginePick): Boolean = pick is EnginePick.Specific

    /** The chip text for a pick, given the live provider states. */
    fun chipLabel(pick: EnginePick, providers: List<ProviderState>): String =
        when (pick) {
            EnginePick.Automatic -> "AI · Automatic"
            is EnginePick.Specific -> {
                val state = providers.firstOrNull { it.id == pick.providerId }
                val model = pick.model.ifBlank { state?.model ?: "" }
                when {
                    state == null -> "AI · provider removed"
                    model.isBlank() -> "AI · ${state.name}"
                    else -> "AI · ${state.name} · ${shortModel(model)}"
                }
            }
        }

    /** Model ids are long; the chip shows the tail that identifies
     * the model (a `free/` prefix is part of the identity, kept). */
    fun shortModel(model: String): String =
        model.trim().take(28)
}
