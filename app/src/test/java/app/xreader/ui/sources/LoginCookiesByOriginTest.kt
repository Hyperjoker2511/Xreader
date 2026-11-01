package app.xreader.ui.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.5 (P0): the login capture is grouped PER ORIGIN — the
 * cookies the jar returns for one host are filed under that
 * host's key only. Before this, a sign-in that crossed origins
 * merged every origin's cookies into one map saved under the
 * final page's host.
 */
class LoginCookiesByOriginTest {

    private val currentUrl = "https://www.example.com/read/1"
    private val loginUrl = "https://example.com/auth/login"

    /** A scripted cookie jar: exact URL → Cookie header. */
    private fun jarOf(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = mapOf(*pairs)
        return { url -> map[url] }
    }

    @Test
    fun `cookies stay under the origin they were read for`() {
        val jar = jarOf(
            currentUrl to "wwwsess=1",
            loginUrl to "rootsess=2",
            "https://www.example.com/" to "wwwsess=1; shared=www",
            "https://example.com/" to "rootsess=2; shared=root"
        )
        val grouped = loginCookiesByOrigin(currentUrl, loginUrl, jar)
        assertEquals(setOf("www.example.com", "example.com"), grouped.keys)
        // Each origin keeps its own session cookie…
        assertEquals("1", grouped["www.example.com"]?.get("wwwsess"))
        assertEquals("2", grouped["example.com"]?.get("rootsess"))
        // …and neither origin's record contains the other's —
        // the old single-map merge put both under one host.
        assertFalse(grouped["www.example.com"]!!.containsKey("rootsess"))
        assertFalse(grouped["example.com"]!!.containsKey("wwwsess"))
        // Same-name cookies keep their per-origin values.
        assertEquals("www", grouped["www.example.com"]?.get("shared"))
        assertEquals("root", grouped["example.com"]?.get("shared"))
    }

    @Test
    fun `one origin produces one record`() {
        val page = "https://books.example.com/book/1"
        val jar = jarOf(
            page to "sess=abc",
            "https://books.example.com/" to "sess=abc; theme=dark"
        )
        val grouped = loginCookiesByOrigin(page, page, jar)
        assertEquals(setOf("books.example.com"), grouped.keys)
        assertEquals("abc", grouped["books.example.com"]?.get("sess"))
        assertEquals("dark", grouped["books.example.com"]?.get("theme"))
    }

    @Test
    fun `an empty jar groups to nothing`() {
        val grouped = loginCookiesByOrigin(currentUrl, loginUrl) { null }
        assertTrue(grouped.isEmpty())
    }

    @Test
    fun `origins with no cookies of their own are absent`() {
        val jar = jarOf(currentUrl to "wwwsess=1")
        val grouped = loginCookiesByOrigin(currentUrl, loginUrl, jar)
        assertEquals(setOf("www.example.com"), grouped.keys)
    }
}
