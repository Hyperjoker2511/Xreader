package app.xreader.data.extension

import app.xreader.ui.sources.JarSessionClear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.5 (P0 slice D2, session lifecycle): the WebView-jar
 * half of a sign-out is pure data — every stored cookie name
 * gets an already-expired Set-Cookie value in BOTH the
 * host-only and the Domain form, addressed to a real https
 * URL for its stored domain. And the render fetcher's seed
 * side builds a real URL from a stored domain.
 */
class P0SessionJarTest {

    @Test
    fun `expiry calls cover host and domain forms for every stored cookie`() {
        val data = SessionData(
            cookies = mapOf(
                "books.example" to mapOf("cf" to "ok", "uid" to "7"),
                ".cdn.example" to mapOf("s" to "1")
            )
        )
        val calls = JarSessionClear.expiryCalls(data)
        // 2 names x 2 forms + 1 name x 2 forms.
        assertEquals(6, calls.size)
        assertTrue(
            calls.contains(
                "https://books.example/" to
                    "cf=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT"
            )
        )
        assertTrue(
            calls.contains(
                "https://books.example/" to
                    "uid=; Domain=books.example; Path=/; " +
                    "Expires=Thu, 01 Jan 1970 00:00:00 GMT"
            )
        )
        // A leading-dot stored domain normalises to its host.
        assertTrue(
            calls.contains(
                "https://cdn.example/" to
                    "s=; Domain=cdn.example; Path=/; " +
                    "Expires=Thu, 01 Jan 1970 00:00:00 GMT"
            )
        )
    }

    @Test
    fun `no stored cookies means no expiry calls`() {
        assertTrue(JarSessionClear.expiryCalls(null).isEmpty())
        assertTrue(JarSessionClear.expiryCalls(SessionData()).isEmpty())
    }

    @Test
    fun `seed cookie target builds a real https url from a stored domain`() {
        assertEquals(
            "https://books.example/" to "books.example",
            seedCookieTarget("books.example")
        )
        assertEquals(
            "https://example.com/" to "example.com",
            seedCookieTarget(".example.com")
        )
        assertEquals(
            "https://books.example/" to "books.example",
            seedCookieTarget("  Books.Example  ")
        )
        assertNull(seedCookieTarget("   "))
        assertNull(seedCookieTarget("."))
    }
}
