package app.xreader.data.extension

import app.xreader.di.ImageSessionClient
import app.xreader.ui.sources.loginCookiesByOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.2 (second-opinion round 3, delta 1): the cookie-resolution
 * CONTRACT, pinned across the three bridges' pure helpers —
 * [loginCookiesByOrigin] (login capture), [SessionData.cookieHeaderFor]
 * (per-record resolution) and
 * [ImageSessionClient.cookieHeaderFromSessions] (the merged read).
 *
 * The pinned semantics (browser-shaped, deliberate):
 *  - a cookie stored under a PARENT domain (site.com) resolves
 *    for its subdomains (www., api.);
 *  - a cookie stored under one subdomain does NOT resolve for a
 *    sibling subdomain (www. ✗ api.) — siblings don't share;
 *  - on a name collision the exact-host jar wins over the parent.
 *
 * The two standing mitigations for the sibling gap are pinned as
 * part of the contract: the multi-origin login harvest
 * (configured hosts are capture candidates too, so a Domain
 * cookie is filed under each configured host it was read for),
 * and name-based token bindings ([SessionStore.cookieValueByName]
 * reads a cookie by name across EVERY jar of the record,
 * host-independent — BOTI's userToken shape). Checked against
 * shipped sources: BOTI uses the name-based binding;
 * FenrirRealm/BrightNovel are single-host. No shipped source
 * needs a sibling pattern this contract denies.
 */
class CookieResolutionContractTest {

    private fun headerPairs(header: String?): Map<String, String> =
        header?.let { CookieParsing.parseCookieHeader(it) } ?: emptyMap()

    @Test
    fun `parent-domain cookie resolves for subdomains`() {
        val data = SessionData(
            cookies = mapOf("site.com" to mapOf("shared" to "P"))
        )
        assertEquals("P", headerPairs(data.cookieHeaderFor("site.com"))["shared"])
        assertEquals("P", headerPairs(data.cookieHeaderFor("www.site.com"))["shared"])
        assertEquals("P", headerPairs(data.cookieHeaderFor("api.site.com"))["shared"])
        assertEquals("P", headerPairs(data.cookieHeaderFor("deep.www.site.com"))["shared"])
    }

    @Test
    fun `subdomain cookie does not resolve for a sibling or the parent`() {
        val data = SessionData(
            cookies = mapOf("www.site.com" to mapOf("wwwonly" to "W"))
        )
        assertEquals("W", headerPairs(data.cookieHeaderFor("www.site.com"))["wwwonly"])
        // www is a parent of deep.www, so it resolves there…
        assertEquals("W", headerPairs(data.cookieHeaderFor("deep.www.site.com"))["wwwonly"])
        // …but siblings don't share, and children don't leak upward.
        assertNull(data.cookieHeaderFor("api.site.com"))
        assertNull(data.cookieHeaderFor("site.com"))
    }

    @Test
    fun `exact host wins over parent on a name collision`() {
        val data = SessionData(
            cookies = mapOf(
                "site.com" to mapOf("shared" to "parent"),
                "www.site.com" to mapOf("shared" to "exact")
            )
        )
        assertEquals("exact", headerPairs(data.cookieHeaderFor("www.site.com"))["shared"])
        assertEquals("parent", headerPairs(data.cookieHeaderFor("api.site.com"))["shared"])
    }

    @Test
    fun `merged read composes the same semantics across records`() = runBlocking {
        val vault = testVault()
        val store = SessionStore(vault)
        // Record A (sorts first): the parent-domain cookie.
        store.save(
            "ext-1",
            SessionData(cookies = mapOf("site.com" to mapOf("shared" to "P")))
        )
        // Record B (sorts later): a www-only cookie.
        store.save(
            "ext-2",
            SessionData(cookies = mapOf("www.site.com" to mapOf("wwwonly" to "W")))
        )
        val www = headerPairs(
            ImageSessionClient.cookieHeaderFromSessions(store, vault, "www.site.com")
        )
        assertEquals("P", www["shared"])
        assertEquals("W", www["wwwonly"])
        val api = headerPairs(
            ImageSessionClient.cookieHeaderFromSessions(store, vault, "api.site.com")
        )
        assertEquals("P", api["shared"])
        assertTrue("sibling cookie leaked across records: $api", "wwwonly" !in api)
    }

    /** The browser-shaped jar: a Domain=site.com cookie is
     * returned for EVERY subdomain URL it is asked about. */
    private val browserJar: (String) -> String? = { url ->
        val host = CookieParsing.hostOf(url)
        if (host != null && (host == "site.com" || host.endsWith(".site.com"))) {
            "shared=D"
        } else {
            null
        }
    }

    @Test
    fun `sibling gap is real without the widened candidate set`() {
        // Sign-in and reading both live on www: the capture asks
        // about www origins only, so the cookie is filed under
        // www.site.com alone — and the sibling api host resolves
        // NOTHING from the record. This is the gap, pinned.
        val page = "https://www.site.com/read/1"
        val grouped = loginCookiesByOrigin(page, page, browserJar)
        assertEquals(setOf("www.site.com"), grouped.keys)
        val record = SessionData(cookies = grouped)
        assertNull(record.cookieHeaderFor("api.site.com"))
    }

    @Test
    fun `multi-origin harvest closes the sibling gap two ways`() {
        val page = "https://www.site.com/read/1"
        // Way one (the §1 widening): the source's configured
        // hosts join the candidate set, so the same Domain
        // cookie is ALSO filed under api.site.com.
        val widened = loginCookiesByOrigin(
            page, page, browserJar,
            configuredHosts = listOf("api.site.com")
        )
        assertEquals("D", widened["api.site.com"]?.get("shared"))
        // Way two (the parent filing): when the login flow
        // itself visited the parent origin (the SSO shape), the
        // cookie is filed under site.com — and then EVERY
        // subdomain resolves it through the parent chain.
        val sso = loginCookiesByOrigin(
            page, "https://site.com/auth/login", browserJar
        )
        assertEquals("D", sso["site.com"]?.get("shared"))
        val record = SessionData(cookies = sso)
        assertEquals(
            "D",
            headerPairs(record.cookieHeaderFor("api.site.com"))["shared"]
        )
    }

    @Test
    fun `name-based binding reads a token cookie across every jar`() = runBlocking {
        // BOTI's shape: the token cookie is set on the WEB host;
        // the binding replays it to the API host. cookieHeaderFor
        // (host-scoped) would not carry it across siblings — the
        // by-name read does, deliberately.
        val store = testSessionStore()
        store.save(
            "ext-boti",
            SessionData(
                cookies = mapOf(
                    "botitranslation.com" to mapOf("userToken" to "tok-9")
                )
            )
        )
        assertEquals(
            "tok-9",
            store.cookieValueByName("ext-boti", "userToken")
        )
        assertNull(store.cookieValueByName("ext-boti", "missing"))
    }
}
