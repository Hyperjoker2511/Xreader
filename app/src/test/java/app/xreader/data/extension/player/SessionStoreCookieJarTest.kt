package app.xreader.data.extension.player

import app.xreader.data.extension.CookieParsing
import app.xreader.data.extension.SessionStore
import app.xreader.data.extension.testVault
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K2: the cookie bridge (D2 rule) — cookies resolve per-source-host
 * from SessionStore; a host with no saved session gets nothing; the
 * WebView jar is never involved (these tests run with no WebView at
 * all, which is itself part of the proof).
 */
class SessionStoreCookieJarTest {

    private val vault = testVault()
    private val store = SessionStore(vault)
    private val jar = SessionStoreCookieJar(store, vault)

    private val asuraUrl = "https://asurascans.com/browse".toHttpUrl()
    private val otherUrl = "https://mangadex.org/titles".toHttpUrl()

    @Test
    fun `session cookie for the source host is attached`() = runBlocking {
        store.mergeCookies(
            SessionStoreCookieJar.PLAYER_SESSION_KEY,
            "asurascans.com",
            mapOf("cf_clearance" to "tok123")
        )
        val cookies = jar.loadForRequest(asuraUrl)
        assertEquals(listOf("cf_clearance"), cookies.map { it.name })
        assertEquals("tok123", cookies.single().value)
    }

    @Test
    fun `another host gets nothing`() = runBlocking {
        store.mergeCookies(
            SessionStoreCookieJar.PLAYER_SESSION_KEY,
            "asurascans.com",
            mapOf("cf_clearance" to "tok123")
        )
        assertTrue(jar.loadForRequest(otherUrl).isEmpty())
    }

    @Test
    fun `sign-in session saved under a per-source key reaches its host`() = runBlocking {
        // The sanctioned screen saves under "ext-<id>", not the player
        // record — the read path merges every record per host.
        store.mergeCookies("ext-42", "asurascans.com", mapOf("session" to "s-1"))
        val cookies = jar.loadForRequest(asuraUrl)
        assertTrue(cookies.any { it.name == "session" && it.value == "s-1" })
        assertTrue(jar.loadForRequest(otherUrl).isEmpty())
    }

    @Test
    fun `response cookies persist into the player record`() = runBlocking {
        val responseCookie = Cookie.Builder()
            .name("csrf").value("v9")
            .domain("asurascans.com").path("/")
            .build()
        jar.saveFromResponse(asuraUrl, listOf(responseCookie))

        val saved = store.load(SessionStoreCookieJar.PLAYER_SESSION_KEY)
        val header = saved?.cookieHeaderFor("asurascans.com")
        assertEquals("v9", header?.let { CookieParsing.parseCookieHeader(it)["csrf"] })
        // And the jar serves them back on the next request.
        assertTrue(jar.loadForRequest(asuraUrl).any { it.name == "csrf" })
    }

    @Test
    fun `expired response cookie removes the stored one`() = runBlocking {
        store.mergeCookies(
            SessionStoreCookieJar.PLAYER_SESSION_KEY,
            "asurascans.com",
            mapOf("csrf" to "v9", "keep" to "1")
        )
        val expired = Cookie.Builder()
            .name("csrf").value("")
            .domain("asurascans.com").path("/")
            .expiresAt(0)
            .build()
        jar.saveFromResponse(asuraUrl, listOf(expired))

        val cookies = jar.loadForRequest(asuraUrl)
        assertTrue(cookies.none { it.name == "csrf" })
        assertTrue(cookies.any { it.name == "keep" })
    }

    @Test
    fun `removeCookie drops only the named cookie`() = runBlocking {
        store.mergeCookies(
            SessionStoreCookieJar.PLAYER_SESSION_KEY,
            "asurascans.com",
            mapOf("cf_clearance" to "old", "session" to "s-1")
        )
        jar.removeCookie("asurascans.com", "cf_clearance")
        val cookies = jar.loadForRequest(asuraUrl)
        assertTrue(cookies.none { it.name == "cf_clearance" })
        assertTrue(cookies.any { it.name == "session" })
    }
}
