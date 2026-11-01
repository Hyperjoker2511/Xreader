package app.xreader.data.extension

import app.xreader.extensionapi.HostHttp
import app.xreader.extensionapi.HostResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.3.4 (session v2, T1): the OPERATION-TOKEN coordinator —
 * the FenrirRealm read-token lifecycle, built to the spec
 * the site's own client follows (TOKEN-SESSION-RESEARCH §1):
 *
 *  - a chapter that answers `locked` triggers ONE mint:
 *    `POST /api/read-token` carrying the account session's
 *    cookies (the fetch road attaches them); the answer's
 *    token then rides the retry as `Authorization: Bearer`;
 *  - GENERATION-AWARE: every mint/re-issue bumps the
 *    generation; [invalidate] clears the held token ONLY
 *    when the generation matches, so a stale chapter's
 *    failure can never revoke a newer token;
 *  - RENEWAL: a token inside its renewal margin (or past
 *    expiry) is re-minted before use; re-issues arriving
 *    through the `x-read-token-issued` response headers are
 *    harvested opportunistically ([noteIssued]);
 *  - failure maps BY THE MINT'S OWN ANSWER: a
 *    `challenge_required` refusal = the site wants its
 *    human check ([MintOutcome.ChallengeRequired]); an
 *    auth-shaped refusal (401, or "unauthorized"/"session"
 *    in the body) = the account session is dead
 *    ([MintOutcome.SessionDead]); a 429 parks ALL waiters
 *    on one shared cooldown ([MintOutcome.RateLimited]);
 *    anything else is [MintOutcome.Unavailable] and the
 *    caller falls back to the honest locked state. A token
 *    problem NEVER masquerades as a sign-in problem.
 *
 * v1.3.5 (P0, slice D1) — the coordinator rebuild:
 *  - PER-CHAPTER TOKEN SCOPE: whether the site binds a
 *    read token to the chapter it was minted for is
 *    unproven either way, so the conservative shape holds:
 *    token state is a map keyed by chapter scope (see
 *    [chapterScopeKey]). Single-flight is PER SCOPE — two
 *    different chapters never share a mint or a token —
 *    while the mint POST itself is serialised source-wide
 *    behind a mint gate, and the 429 cooldown stays
 *    source-wide (one scope's 429 parks every scope).
 *    [MintOutcome.ChallengeRequired] and
 *    [MintOutcome.SessionDead] describe the SESSION, never
 *    create a scoped token, and clear nothing.
 *  - ONE LOCK DISCIPLINE: ALL coordinator state (every
 *    scope's held token/deadline/verified/loaded/in-flight,
 *    the shared cooldown, the clear-epoch) sits behind
 *    [stateMutex]; the old shape guarded only the in-flight
 *    slot and force-unwrapped a token after lock release
 *    (a crash path). No force-unwrap remains. The persisted
 *    record load COMPLETES under the lock before the scope
 *    is treated as loaded, and a load never overwrites a
 *    token this process already holds — a late load can no
 *    longer clobber a fresher mint.
 *  - CLOCKS: expiry is judged on a MONOTONIC clock
 *    ([elapsedMs]) while the token lives in this process —
 *    a wall-clock jump (timezone travel, NTP sync, the user
 *    changing the clock) can neither kill a live token nor
 *    resurrect a dead one. The persisted record carries the
 *    wall-clock expiry for the record's sake, but after a
 *    process restart a stored token is REVALIDATED, never
 *    trusted: it seeds the generation counter and is
 *    treated as stale, so the next locked chapter mints
 *    fresh (and a dead stored token can never wedge reads).
 *  - ON DEMAND ONLY: a mint happens only inside [token],
 *    which the source calls only when a chapter actually
 *    answered locked. Nothing mints because a download was
 *    scheduled or a book was opened — the proactive path
 *    ([currentToken]) only ever ATTACHES an already-held,
 *    comfortably valid token for the exact scope fetched.
 *
 * The facade is BLOCKING because the Source API it serves
 * is blocking (HostHttpImpl already bridges with
 * runBlocking); the async machinery stays inside.
 * Coordinators are per-source and process-wide
 * ([ReadTokenCoordinators]) because DeclarativeSource
 * instances are rebuilt per operation.
 */
class ReadTokenCoordinator(
    private val spec: OperationTokenSpec,
    private val store: SessionStore? = null,
    private val storeKey: String = "",
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {

    /** What a caller learns when it needs a token. */
    sealed interface MintOutcome {
        /** A token to present, and the generation it belongs to. */
        data class Ready(val token: String, val generation: Long) : MintOutcome
        /** The mint demanded the site's human check first. */
        data object ChallengeRequired : MintOutcome
        /** The account session itself is dead — sign in again. */
        data object SessionDead : MintOutcome
        /** The mint endpoint rate-limited; the shared
         * cooldown (seconds, when the site named one) runs. */
        data class RateLimited(val retryAfterSeconds: Long?) : MintOutcome
        /** The mint failed otherwise; [detail] is for diagnostics. */
        data class Unavailable(val detail: String) : MintOutcome
    }

    /** One chapter scope's credential state. Every field is
     * guarded by [stateMutex] — no exceptions. */
    private class ScopeState {
        var held: StoredReadToken? = null
        /** The held token's deadline on the MONOTONIC clock —
         * the only deadline validity is ever judged by. */
        var heldDeadlineElapsedMs = 0L
        /** False for a token restored from the store after a
         * restart: present for generation purposes, never
         * trusted as valid until this process mints/harvests. */
        var heldVerified = false
        /** True only AFTER the persisted-record load for this
         * scope has completed (see ensureLoadedLocked). */
        var loadedFromStore = false
        var inFlight: CompletableDeferred<MintOutcome>? = null
    }

    /** The single lock behind ALL coordinator state. */
    private val stateMutex = Mutex()
    /** Serialises the actual mint POST across scopes: one
     * mint on the wire at a time per source, so a chapter
     * burst can never become a mint storm. */
    private val mintGate = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val states = HashMap<String, ScopeState>()
    /** Source-wide 429 park (monotonic deadline), guarded
     * by [stateMutex]. */
    private var cooldownUntilMs = 0L
    /** Bumped by [clear]: a mint launched before a sign-out
     * must not resurrect a token after it. Guarded by
     * [stateMutex]. */
    private var clearEpoch = 0L
    private val json = Json { ignoreUnknownKeys = true }

    private fun stateFor(scopeKey: String): ScopeState =
        states.getOrPut(scopeKey) { ScopeState() }

    /** The currently held token for [scopeKey] when it is
     * comfortably valid — no mint, no wait. Null otherwise.
     * Only ever answers for the exact scope asked about. */
    fun currentToken(scopeKey: String): StoredReadToken? = runBlocking {
        stateMutex.withLock {
            val state = states[scopeKey] ?: return@withLock null
            currentTokenLocked(state)
        }
    }

    /** [stateMutex] must be held. */
    private fun currentTokenLocked(state: ScopeState): StoredReadToken? {
        val token = state.held ?: return null
        return token.takeIf {
            state.heldVerified &&
                elapsedMs() < state.heldDeadlineElapsedMs -
                    spec.renewalMarginSeconds * 1000
        }
    }

    /** Adopt [token] as [state]'s held credential: its
     * lifetime is re-anchored onto the monotonic clock from
     * the wall-clock expiry the site stated.
     * [stateMutex] must be held. */
    private fun adoptLocked(state: ScopeState, token: StoredReadToken) {
        state.held = token
        state.heldDeadlineElapsedMs =
            elapsedMs() + (token.expiresAtMs - nowMs()).coerceAtLeast(0)
        state.heldVerified = true
    }

    /**
     * A token for [scopeKey], minting (single-flight per
     * scope) when the held one is absent/stale. [http] is
     * the CALLER's host HTTP — the mint rides the caller's
     * own session cookies, exactly as the site's client
     * does. [mintChapterId] is the value the mint body
     * carries (the locked record's remint hint or the
     * chapter's own number) — a different concept from
     * [scopeKey], the identity the token is held under.
     * Blocking.
     */
    fun token(
        http: HostHttp,
        scopeKey: String,
        mintChapterId: String?
    ): MintOutcome = runBlocking {
        val decision = stateMutex.withLock {
            val state = stateFor(scopeKey)
            ensureLoadedLocked(scopeKey, state)
            currentTokenLocked(state)?.let {
                return@withLock Decision.Done(
                    MintOutcome.Ready(it.value, it.generation)
                )
            }
            if (elapsedMs() < cooldownUntilMs) {
                return@withLock Decision.Done(
                    MintOutcome.RateLimited(
                        ((cooldownUntilMs - elapsedMs()) / 1000)
                            .coerceAtLeast(1)
                    )
                )
            }
            val existing = state.inFlight
            if (existing != null) {
                Decision.Join(existing)
            } else {
                val fresh = CompletableDeferred<MintOutcome>()
                state.inFlight = fresh
                Decision.Start(fresh, clearEpoch)
            }
        }
        when (decision) {
            is Decision.Done -> decision.outcome
            is Decision.Join -> decision.deferred.await()
            is Decision.Start -> {
                scope.launch {
                    val outcome = runCatching {
                        gatedMint(http, scopeKey, mintChapterId, decision.epoch)
                    }.getOrElse {
                        MintOutcome.Unavailable(
                            it.message ?: "the token request failed"
                        )
                    }
                    stateMutex.withLock {
                        val state = states[scopeKey]
                        if (state?.inFlight === decision.deferred) {
                            state.inFlight = null
                        }
                    }
                    decision.deferred.complete(outcome)
                }
                decision.deferred.await()
            }
        }
    }

    private sealed interface Decision {
        data class Done(val outcome: MintOutcome) : Decision
        data class Join(val deferred: CompletableDeferred<MintOutcome>) : Decision
        data class Start(
            val deferred: CompletableDeferred<MintOutcome>,
            val epoch: Long
        ) : Decision
    }

    /** The mint gate: at most one mint POST per source on
     * the wire at any moment, whatever the scope. A waiter
     * re-checks its scope (and the shared cooldown) after
     * acquiring the gate — an earlier scope's mint may have
     * parked the source, or a harvest may have landed. */
    private suspend fun gatedMint(
        http: HostHttp,
        scopeKey: String,
        mintChapterId: String?,
        epoch: Long
    ): MintOutcome = mintGate.withLock {
        val recheck: MintOutcome? = stateMutex.withLock {
            val state = states[scopeKey]
            when {
                state == null -> null
                else -> currentTokenLocked(state)?.let {
                    MintOutcome.Ready(it.value, it.generation)
                } ?: if (elapsedMs() < cooldownUntilMs) {
                    MintOutcome.RateLimited(
                        ((cooldownUntilMs - elapsedMs()) / 1000)
                            .coerceAtLeast(1)
                    )
                } else {
                    null
                }
            }
        }
        recheck ?: doMint(http, scopeKey, mintChapterId, epoch)
    }

    /** The mint itself. Runs under [mintGate] on the
     * coordinator's scope; state transitions happen under
     * [stateMutex]. */
    private suspend fun doMint(
        http: HostHttp,
        scopeKey: String,
        mintChapterId: String?,
        epoch: Long
    ): MintOutcome {
        val response: HostResponse = runCatching {
            http.post(
                spec.mintUrl, spec.mintBody(mintChapterId),
                contentType = "application/json"
            )
        }.getOrElse {
            return MintOutcome.Unavailable(
                it.message ?: "the token request failed"
            )
        }
        if (response.status == 429) {
            val retryAfter = retryAfterSeconds(response.headers)
            stateMutex.withLock {
                cooldownUntilMs = elapsedMs() +
                    (retryAfter ?: DEFAULT_MINT_COOLDOWN_SECONDS) * 1000
            }
            return MintOutcome.RateLimited(retryAfter)
        }
        val root = runCatching {
            json.parseToJsonElement(response.body).jsonObject
        }.getOrNull()
        val challenge = root?.get("challenge_required")
            ?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        if (response.status in 200..299) {
            val tokenValue = root?.get("token")
                ?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: return MintOutcome.Unavailable(
                    "the site answered the token request without a token"
                )
            val expiresAt = root["expires_at"]?.jsonPrimitive?.content
                ?.toLongOrNull()?.times(1000)
                ?: (nowMs() + spec.defaultLifetimeSeconds * 1000)
            // Adopt + persist under the state lock, and only
            // if no sign-out landed while the mint was on the
            // wire (the epoch rule): a cleared source must
            // never see this token resurrect.
            val stored = stateMutex.withLock {
                if (epoch != clearEpoch) return@withLock null
                val state = stateFor(scopeKey)
                val fresh = StoredReadToken(
                    value = tokenValue,
                    expiresAtMs = expiresAt,
                    generation = (state.held?.generation ?: 0) + 1
                )
                adoptLocked(state, fresh)
                persistLocked(scopeKey, fresh)
                fresh
            } ?: return MintOutcome.Unavailable(
                "the session was cleared while the token was issued"
            )
            return MintOutcome.Ready(stored.value, stored.generation)
        }
        if (challenge || response.status == 403 &&
            SiteCheck.hasChallengeMarkersIn(response.body)
        ) {
            return MintOutcome.ChallengeRequired
        }
        val bodyText = response.body.lowercase()
        if (response.status == 401 ||
            "unauthorized" in bodyText || "not authenticated" in bodyText ||
            "session expired" in bodyText || "sign in" in bodyText
        ) {
            return MintOutcome.SessionDead
        }
        return MintOutcome.Unavailable(
            "the site answered the token request with HTTP ${response.status}"
        )
    }

    /**
     * Harvest a re-issued token from a chapter response's
     * headers (the site refreshes the token in-band; its
     * own client does exactly this). A harvest REPLACES the
     * held token OF THE RESPONDING CHAPTER'S SCOPE as a new
     * generation — it never touches another scope, and
     * never the reverse.
     */
    fun noteIssued(headers: Map<String, String>, scopeKey: String) {
        val issued = headerValue(headers, spec.issuedHeader) ?: return
        if (issued.isBlank()) return
        val expiresAt = headerValue(headers, spec.issuedExpiresHeader)
            ?.toLongOrNull()?.times(1000)
            ?: (nowMs() + spec.defaultLifetimeSeconds * 1000)
        runBlocking {
            stateMutex.withLock {
                val state = stateFor(scopeKey)
                val stored = StoredReadToken(
                    value = issued,
                    expiresAtMs = expiresAt,
                    generation = (state.held?.generation ?: 0) + 1
                )
                adoptLocked(state, stored)
                persistLocked(scopeKey, stored)
            }
        }
    }

    /**
     * Forget [scopeKey]'s held token — ONLY when
     * [generation] is the generation that scope currently
     * holds. A failure that rode an old token must never
     * revoke a newer one, and one chapter's failure must
     * never revoke another chapter's token.
     */
    fun invalidate(scopeKey: String, generation: Long) {
        runBlocking {
            stateMutex.withLock {
                val state = states[scopeKey] ?: return@withLock
                val current = state.held ?: return@withLock
                if (current.generation != generation) return@withLock
                state.held = null
                state.heldVerified = false
                state.heldDeadlineElapsedMs = 0
                val storeNow = store
                if (storeNow != null && storeKey.isNotBlank()) {
                    runCatching {
                        storeNow.clearReadToken(storeKey, scopeKey)
                    }
                }
            }
        }
    }

    /** Forget everything (sign-out): every scope's held
     * token and stored record, plus the shared cooldown. */
    fun clear() {
        runBlocking {
            stateMutex.withLock {
                states.clear()
                cooldownUntilMs = 0
                clearEpoch += 1
                val storeNow = store
                if (storeNow != null && storeKey.isNotBlank()) {
                    runCatching { storeNow.clearReadTokens(storeKey) }
                }
            }
        }
    }

    /**
     * Load [scopeKey]'s persisted record. Called with
     * [stateMutex] held; the scope is marked loaded only
     * AFTER the store read has completed, and the record is
     * adopted only when the scope holds nothing yet — a
     * token this process minted/harvested is always fresher
     * than anything on disk.
     *
     * REVALIDATION (second-review refinement): the stored
     * record seeds the generation counter, but its
     * wall-clock deadline is NOT trusted after a restart —
     * the token is held UNVERIFIED, so the next locked
     * chapter mints fresh instead of riding a possibly-dead
     * credential into a locked answer.
     */
    private suspend fun ensureLoadedLocked(scopeKey: String, state: ScopeState) {
        if (state.loadedFromStore) return
        val storeNow = store
        if (storeNow == null || storeKey.isBlank()) {
            state.loadedFromStore = true
            return
        }
        val stored = runCatching {
            storeNow.loadReadToken(storeKey, scopeKey)
        }.getOrNull()
        state.loadedFromStore = true
        if (stored != null && state.held == null) {
            state.held = stored
            state.heldVerified = false
            state.heldDeadlineElapsedMs = 0
        }
    }

    /** [stateMutex] must be held. */
    private suspend fun persistLocked(scopeKey: String, token: StoredReadToken) {
        val storeNow = store ?: return
        if (storeKey.isBlank()) return
        runCatching { storeNow.saveReadToken(storeKey, scopeKey, token) }
    }

    private fun headerValue(headers: Map<String, String>, name: String): String? {
        headers[name]?.let { return it }
        val lower = name.lowercase()
        return headers.entries.firstOrNull { it.key.lowercase() == lower }?.value
    }

    private fun retryAfterSeconds(headers: Map<String, String>): Long? =
        headerValue(headers, "Retry-After")?.trim()?.toLongOrNull()

    companion object {
        const val DEFAULT_MINT_COOLDOWN_SECONDS = 60L

        /**
         * The canonical chapter scope key for a chapter
         * identity (the resolved chapter id or chapter API
         * URL): query and fragment are volatile and stripped;
         * scheme and authority are case-insensitive and
         * lowered; a trailing slash is normalised away. The
         * mint body's chapter hint stays a separate concept
         * (see [token]).
         */
        fun chapterScopeKey(chapterIdentity: String): String {
            val trimmed = chapterIdentity.trim()
            val bare = trimmed.substringBefore('#').substringBefore('?')
            val schemeSep = bare.indexOf("://")
            val normalised = if (schemeSep > 0) {
                val rest = bare.substring(schemeSep + 3)
                val slash = rest.indexOf('/')
                val authority =
                    if (slash >= 0) rest.substring(0, slash) else rest
                val path = if (slash >= 0) rest.substring(slash) else ""
                bare.substring(0, schemeSep).lowercase() + "://" +
                    authority.lowercase() + path
            } else {
                bare
            }
            val noTrailing = if (normalised.length > 1) {
                normalised.trimEnd('/')
            } else {
                normalised
            }
            return noTrailing.ifBlank { trimmed }
        }
    }
}

/**
 * The process-wide coordinators, one per source id — the
 * registry exists because DeclarativeSource instances are
 * per-operation while the mint's single-flight must be
 * per-SOURCE (per chapter scope within the source). The
 * first store offered wins (ExtensionService passes the
 * app SessionStore; probes pass null and stay
 * memory-only).
 */
object ReadTokenCoordinators {

    private val coordinators = ConcurrentHashMap<String, ReadTokenCoordinator>()

    fun forSource(
        sourceId: String,
        spec: OperationTokenSpec,
        store: SessionStore?
    ): ReadTokenCoordinator =
        coordinators.computeIfAbsent(sourceId) {
            ReadTokenCoordinator(spec, store, storeKey = sourceId)
        }

    /** Sign-out hook: forget a source's tokens everywhere —
     * every chapter scope, in memory and in the store. */
    fun clear(sourceId: String) {
        coordinators[sourceId]?.clear()
    }

    fun clearForTests() = coordinators.clear()
}
