package app.xreader.data.extension

import app.xreader.core.security.KeyVault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

/**
 * A source's saved login session (§9.3): cookies + the user-agent the
 * user was browsing with, stored ONLY in the KeyVault (encrypted at
 * rest), keyed per source. Cookie values are never surfaced in UI or
 * logs — they are attached to that source's requests and nothing else
 * can read them.
 */
@Serializable
data class SessionData(
    /** host (or parent domain) → cookie name → value. */
    val cookies: Map<String, Map<String, String>> = emptyMap(),
    val userAgent: String? = null
) {
    /** The Cookie header for [host]: exact-host jars first, then any
     * parent-domain jars (a cookie saved for example.com applies to
     * www.example.com, like a browser). */
    fun cookieHeaderFor(host: String): String? {
        val h = host.normaliseHost()
        val pairs = LinkedHashMap<String, String>()
        // Parent domains first so exact-host values win on collision.
        val chain = generateSequence(h) { current ->
            val dot = current.indexOf('.')
            if (dot < 0 || current.count { it == '.' } <= 1) null
            else current.substring(dot + 1)
        }.toList().asReversed()
        for (domain in chain) {
            cookies[domain]?.let { pairs.putAll(it) }
        }
        if (pairs.isEmpty()) return null
        return pairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun withCookies(host: String, newCookies: Map<String, String>): SessionData {
        if (newCookies.isEmpty()) return this
        val h = host.normaliseHost()
        val merged = cookies.toMutableMap()
        merged[h] = (merged[h] ?: emptyMap()) + newCookies
        return copy(cookies = merged)
    }
}

class SessionStore(private val vault: KeyVault) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(sessionKey: String): SessionData? {
        val raw = vault.get(KeyVault.sessionCookies(sessionKey)) ?: return null
        return runCatching {
            json.decodeFromString(SessionData.serializer(), raw)
        }.getOrNull()
    }

    suspend fun save(sessionKey: String, data: SessionData) {
        vault.put(
            KeyVault.sessionCookies(sessionKey),
            json.encodeToString(SessionData.serializer(), data)
        )
    }

    suspend fun has(sessionKey: String): Boolean =
        vault.get(KeyVault.sessionCookies(sessionKey)) != null

    suspend fun clear(sessionKey: String) {
        vault.remove(KeyVault.sessionCookies(sessionKey))
    }

    /** Move a provisional session (link-add probing) to its real key. */
    suspend fun copyTo(fromKey: String, toKey: String) {
        val raw = vault.get(KeyVault.sessionCookies(fromKey)) ?: return
        vault.put(KeyVault.sessionCookies(toKey), raw)
        vault.remove(KeyVault.sessionCookies(fromKey))
    }

    /** Merge freshly captured cookies (from a Set-Cookie capture or
     * the login WebView) into the saved session. */
    suspend fun mergeCookies(
        sessionKey: String,
        host: String,
        cookies: Map<String, String>,
        userAgent: String? = null
    ) {
        val current = load(sessionKey) ?: SessionData()
        save(
            sessionKey,
            current.withCookies(host, cookies)
                .let { if (userAgent != null) it.copy(userAgent = userAgent) else it }
        )
    }

    // ── v1.3.4 (session v2, T1): operation-token persistence ──
    //
    // Read tokens ride the SAME encrypted vault as the
    // sessions (KeyVault = AES-256/GCM, Keystore-wrapped) in a
    // versioned record, so a still-valid token survives an app
    // restart instead of re-minting on every cold open. A
    // record that fails to decode (an older shape, corruption)
    // reads as ABSENT — the source simply re-mints; the read
    // path never crashes on stored credential data. Sign-out
    // clears a source's record together with its cookies
    // (the coordinator's store is this class, see
    // ReadTokenCoordinator).

    /** The stored read token for [tokenKey], or null. */
    suspend fun loadReadToken(tokenKey: String): StoredReadToken? {
        val raw = vault.get(KeyVault.readTokenKey(tokenKey)) ?: return null
        return runCatching {
            json.decodeFromString(StoredReadToken.serializer(), raw)
        }.getOrNull()?.takeIf { it.version == StoredReadToken.CURRENT_VERSION }
    }

    suspend fun saveReadToken(tokenKey: String, token: StoredReadToken) {
        vault.put(
            KeyVault.readTokenKey(tokenKey),
            json.encodeToString(StoredReadToken.serializer(), token)
        )
    }

    suspend fun clearReadToken(tokenKey: String) {
        vault.remove(KeyVault.readTokenKey(tokenKey))
    }

    /** v1.3.4 (T1): the value of a session cookie by NAME,
     * searched across every host jar of the session — the
     * declared header binding's capture half (a site may set
     * its token cookie on the web host while the binding
     * replays it to the API host). Null when absent. */
    suspend fun cookieValueByName(sessionKey: String, cookieName: String): String? {
        val data = load(sessionKey) ?: return null
        for ((_, jar) in data.cookies) {
            jar[cookieName]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }
}

/**
 * v1.3.4 (T1): one stored operation token (FenrirRealm's
 * read token). [generation] increments on every mint /
 * re-issue: a failure may invalidate ONLY its own
 * generation, so a stale answer can never revoke a newer
 * token (the generation rule from the Prompt N reviews).
 * [version] versions the RECORD format, not the token.
 */
@kotlinx.serialization.Serializable
data class StoredReadToken(
    val value: String,
    val expiresAtMs: Long,
    val generation: Long,
    val version: Int = CURRENT_VERSION
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/** Cookie-string helpers — pure, so the login capture is testable
 * without a WebView. */
object CookieParsing {

    /** Parse a `Set-Cookie` response header into name→value for [host]
     * (attributes ignored; the session is scoped by the host that set
     * it, which is the response's own host). */
    fun parseSetCookie(header: String): Pair<String, String>? {
        val first = header.substringBefore(';').trim()
        val eq = first.indexOf('=')
        if (eq <= 0) return null
        val name = first.substring(0, eq).trim()
        val value = first.substring(eq + 1).trim()
        if (name.isEmpty()) return null
        return name to value
    }

    /** Parse a WebView `document.cookie`-style string ("a=1; b=2"). */
    fun parseCookieHeader(header: String?): Map<String, String> {
        if (header.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in header.split(';')) {
            val pair = parseSetCookie(part) ?: continue
            out[pair.first] = pair.second
        }
        return out
    }

    fun hostOf(url: String): String? =
        runCatching { URI(url).host }.getOrNull()?.normaliseHost()
}
