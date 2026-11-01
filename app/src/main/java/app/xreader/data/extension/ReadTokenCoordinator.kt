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
 *  - SINGLE-FLIGHT: one mint in flight per source — eight
 *    concurrent locked chapters share it (the shared
 *    operation is a standalone [CompletableDeferred] on the
 *    coordinator's own scope: a waiting caller being
 *    cancelled never cancels the mint);
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
 * The facade is BLOCKING because the Source API it serves
 * is blocking (HostHttpImpl already bridges with
 * runBlocking); the async machinery stays inside.
 * Coordinators are per-source and process-wide
 * ([ReadTokenCoordinators]) because DeclarativeSource
 * instances are rebuilt per operation.
 *
 * Two refinements from the second design review:
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
 *    comfortably valid token.
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

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var held: StoredReadToken? = null
    /** The held token's deadline on the MONOTONIC clock —
     * the only deadline validity is ever judged by. */
    private var heldDeadlineElapsedMs = 0L
    /** False for a token restored from the store after a
     * restart: present for generation purposes, never
     * trusted as valid until this process mints/harvests. */
    private var heldVerified = false
    private var loadedFromStore = false
    private var inFlight: CompletableDeferred<MintOutcome>? = null
    private var cooldownUntilMs = 0L
    private val json = Json { ignoreUnknownKeys = true }

    /** The currently held token when it is comfortably
     * valid — no mint, no wait. Null otherwise. */
    fun currentToken(): StoredReadToken? {
        val token = held ?: return null
        return token.takeIf { isComfortablyValid(it) }
    }

    private fun isComfortablyValid(token: StoredReadToken): Boolean =
        heldVerified &&
            elapsedMs() < heldDeadlineElapsedMs -
                spec.renewalMarginSeconds * 1000

    /** Adopt [token] as the held credential: its lifetime
     * is re-anchored onto the monotonic clock from the
     * wall-clock expiry the site stated. */
    private fun adopt(token: StoredReadToken) {
        held = token
        heldDeadlineElapsedMs =
            elapsedMs() + (token.expiresAtMs - nowMs()).coerceAtLeast(0)
        heldVerified = true
    }

    /**
     * A token for the caller, minting (single-flight) when
     * the held one is absent/stale. [http] is the CALLER's
     * host HTTP — the mint rides the caller's own session
     * cookies, exactly as the site's client does. Blocking.
     */
    fun token(http: HostHttp, chapterId: String?): MintOutcome = runBlocking {
        ensureLoaded()
        currentToken()?.let {
            return@runBlocking MintOutcome.Ready(it.value, it.generation)
        }
        if (elapsedMs() < cooldownUntilMs) {
            return@runBlocking MintOutcome.RateLimited(
                ((cooldownUntilMs - elapsedMs()) / 1000).coerceAtLeast(1)
            )
        }
        val deferred = mutex.withLock {
            // Recheck under the lock: another caller's mint
            // may have landed while this one queued.
            currentToken()?.let {
                return@withLock null
            }
            inFlight ?: CompletableDeferred<MintOutcome>().also { fresh ->
                inFlight = fresh
                scope.launch {
                    val outcome = runCatching { doMint(http, chapterId) }
                        .getOrElse {
                            MintOutcome.Unavailable(
                                it.message ?: "the token request failed"
                            )
                        }
                    mutex.withLock {
                        if (inFlight === fresh) inFlight = null
                    }
                    fresh.complete(outcome)
                }
            }
        } ?: return@runBlocking currentToken()!!.let {
            MintOutcome.Ready(it.value, it.generation)
        }
        deferred.await()
    }

    /** The mint itself. Runs on the coordinator's scope. */
    private suspend fun doMint(http: HostHttp, chapterId: String?): MintOutcome {
        val response: HostResponse = runCatching {
            http.post(
                spec.mintUrl, spec.mintBody(chapterId),
                contentType = "application/json"
            )
        }.getOrElse {
            return MintOutcome.Unavailable(it.message ?: "the token request failed")
        }
        if (response.status == 429) {
            val retryAfter = retryAfterSeconds(response.headers)
            cooldownUntilMs = elapsedMs() +
                (retryAfter ?: DEFAULT_MINT_COOLDOWN_SECONDS) * 1000
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
            val stored = StoredReadToken(
                value = tokenValue,
                expiresAtMs = expiresAt,
                generation = (held?.generation ?: 0) + 1
            )
            adopt(stored)
            persist(stored)
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
     * held token as a new generation — never the reverse.
     */
    fun noteIssued(headers: Map<String, String>) {
        val issued = headerValue(headers, spec.issuedHeader) ?: return
        if (issued.isBlank()) return
        val expiresAt = headerValue(headers, spec.issuedExpiresHeader)
            ?.toLongOrNull()?.times(1000)
            ?: (nowMs() + spec.defaultLifetimeSeconds * 1000)
        val stored = StoredReadToken(
            value = issued,
            expiresAtMs = expiresAt,
            generation = (held?.generation ?: 0) + 1
        )
        adopt(stored)
        scope.launch { persist(stored) }
    }

    /**
     * Forget the held token — ONLY when [generation] is the
     * generation currently held. A failure that rode an old
     * token must never revoke a newer one.
     */
    fun invalidate(generation: Long) {
        val current = held ?: return
        if (current.generation != generation) return
        held = null
        heldVerified = false
        heldDeadlineElapsedMs = 0
        val key = storeKey
        if (store != null && key.isNotBlank()) {
            scope.launch { runCatching { store.clearReadToken(key) } }
        }
    }

    /** Forget everything (sign-out): held token, stored
     * record, cooldown. */
    fun clear() {
        held = null
        heldVerified = false
        heldDeadlineElapsedMs = 0
        cooldownUntilMs = 0
        val key = storeKey
        if (store != null && key.isNotBlank()) {
            scope.launch { runCatching { store.clearReadToken(key) } }
        }
    }

    private suspend fun ensureLoaded() {
        if (loadedFromStore) return
        loadedFromStore = true
        val storeNow = store ?: return
        if (storeKey.isBlank()) return
        val stored = runCatching { storeNow.loadReadToken(storeKey) }
            .getOrNull() ?: return
        // REVALIDATION (second-review refinement): the
        // stored record seeds the generation counter, but
        // its wall-clock deadline is NOT trusted after a
        // restart — the token is held UNVERIFIED, so the
        // next locked chapter mints fresh instead of riding
        // a possibly-dead credential into a locked answer.
        held = stored
        heldVerified = false
        heldDeadlineElapsedMs = 0
    }

    private suspend fun persist(token: StoredReadToken) {
        val storeNow = store ?: return
        if (storeKey.isBlank()) return
        runCatching { storeNow.saveReadToken(storeKey, token) }
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
    }
}

/**
 * The process-wide coordinators, one per source id — the
 * registry exists because DeclarativeSource instances are
 * per-operation while the mint's single-flight must be
 * per-SOURCE. The first store offered wins (ExtensionService
 * passes the app SessionStore; probes pass null and stay
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

    /** Sign-out hook: forget a source's token everywhere. */
    fun clear(sourceId: String) {
        coordinators[sourceId]?.clear()
    }

    fun clearForTests() = coordinators.clear()
}
