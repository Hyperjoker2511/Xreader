package app.xreader.data.extension.player

import app.xreader.core.security.KeyVault
import app.xreader.data.extension.CookieParsing
import app.xreader.data.extension.SessionStore
import app.xreader.data.extension.normaliseHost
import app.xreader.di.ImageSessionClient
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * K2 (Keiyoushi player): the player's cookie jar — the D2 rule applied to
 * extension traffic.
 *
 * READS resolve per-source-host from [SessionStore] (the encrypted vault),
 * through the exact resolver the image pipeline already uses
 * ([ImageSessionClient.cookieHeaderFromSessions]: every saved session's
 * cookies for the request's host, merged). The global WebView cookie jar
 * is NEVER read — a session the user created by signing in on the
 * sanctioned screen reaches an extension because it was saved into
 * SessionStore (per origin, by SourcesViewModel.saveLoginSession), not
 * because a WebView happens to hold it. A request to a host with no saved
 * session gets no cookies at all.
 *
 * WRITES (Set-Cookie on extension responses) persist into the player's
 * own session record, [PLAYER_SESSION_KEY], per host — so a source that
 * sets its own cookies (CSRF tokens, clearance refreshes) keeps them
 * across restarts without touching any other source's sign-in record.
 * Expired or emptied cookies are removed from the record, the way a
 * browser jar expires them.
 *
 * OkHttp calls a CookieJar synchronously on its dispatcher threads; the
 * store is suspend-based, so both directions bridge with runBlocking —
 * the same pattern ImageSessionClient's interceptor already uses. Any
 * store failure reads as "no cookies" / drops the write rather than
 * failing the request.
 */
class SessionStoreCookieJar(
    private val store: SessionStore,
    private val vault: KeyVault
) : CookieJar {

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = runCatching {
            runBlocking {
                ImageSessionClient.cookieHeaderFromSessions(store, vault, url.host)
            }
        }.getOrNull() ?: return emptyList()
        return CookieParsing.parseCookieHeader(header).mapNotNull { (name, value) ->
            Cookie.parse(url, "$name=$value")
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val now = System.currentTimeMillis()
        val live = cookies
            .filter { it.expiresAt > now && it.value.isNotEmpty() }
            .associate { it.name to it.value }
        val dead = cookies
            .filter { it.expiresAt <= now || it.value.isEmpty() }
            .map { it.name }
        runCatching {
            runBlocking {
                if (live.isNotEmpty()) {
                    store.mergeCookies(PLAYER_SESSION_KEY, url.host, live)
                }
                if (dead.isNotEmpty()) {
                    removeCookies(url.host, dead)
                }
            }
        }
    }

    /** Drop one cookie from the player record (the Cloudflare path
     * clears a stale cf_clearance before a fresh solve, the donor way). */
    fun removeCookie(host: String, name: String) {
        runCatching {
            runBlocking { removeCookies(host, listOf(name)) }
        }
    }

    private suspend fun removeCookies(host: String, names: List<String>) {
        val current = store.load(PLAYER_SESSION_KEY) ?: return
        val key = current.cookies.keys
            .firstOrNull { it.normaliseHost() == host.normaliseHost() } ?: return
        val remaining = current.cookies.getValue(key) - names.toSet()
        val next = current.cookies.toMutableMap().apply {
            if (remaining.isEmpty()) remove(key) else put(key, remaining)
        }
        if (next != current.cookies) {
            store.save(PLAYER_SESSION_KEY, current.copy(cookies = next))
        }
    }

    companion object {
        /** The player's own SessionStore record key. Sign-in sessions the
         * user creates for a source keep their per-source keys
         * ("ext-<id>"); this record holds only what extensions' own
         * responses set. Reads merge across all records per host (the
         * D2 resolver), so which record a cookie sits in is invisible
         * to the read path. */
        const val PLAYER_SESSION_KEY = "keiyoushi-player"
    }
}
