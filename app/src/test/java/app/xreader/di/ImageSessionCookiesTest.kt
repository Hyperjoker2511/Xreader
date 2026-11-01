package app.xreader.di

import app.xreader.data.extension.SessionData
import app.xreader.data.extension.SessionStore
import app.xreader.data.extension.testVault
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.3.5 (P0 slice D2, session lifecycle): image requests
 * resolve their cookies from the SessionStore — the app's
 * own session authority — never from the global WebView
 * jar. What the store no longer holds (a signed-out source)
 * is exactly what an image request must not present.
 */
class ImageSessionCookiesTest {

    @Test
    fun `cookies resolve from the session store for the request host`() =
        runBlocking {
            val vault = testVault()
            val store = SessionStore(vault)
            store.save(
                "ext-1",
                SessionData(
                    cookies = mapOf(
                        "books.example" to mapOf("cf" to "ok", "uid" to "7")
                    )
                )
            )
            store.save(
                "ext-2",
                SessionData(
                    cookies = mapOf("other.example" to mapOf("x" to "y"))
                )
            )
            assertEquals(
                "cf=ok; uid=7",
                ImageSessionClient.cookieHeaderFromSessions(
                    store, vault, "books.example"
                )
            )
            // Another source's session never leaks into a host
            // it holds nothing for.
            assertNull(
                ImageSessionClient.cookieHeaderFromSessions(
                    store, vault, "unknown.example"
                )
            )
        }

    @Test
    fun `parent domain session applies to a subdomain image host`() =
        runBlocking {
            val vault = testVault()
            val store = SessionStore(vault)
            store.save(
                "ext-1",
                SessionData(
                    cookies = mapOf("books.example" to mapOf("cf" to "ok"))
                )
            )
            assertEquals(
                "cf=ok",
                ImageSessionClient.cookieHeaderFromSessions(
                    store, vault, "cdn.books.example"
                )
            )
        }

    @Test
    fun `a cleared session resolves to no cookies`() = runBlocking {
        val vault = testVault()
        val store = SessionStore(vault)
        store.save(
            "ext-1",
            SessionData(
                cookies = mapOf("books.example" to mapOf("cf" to "ok"))
            )
        )
        // The sign-out: the store forgets the record, and the
        // very next image resolution must come back empty.
        store.clear("ext-1")
        assertNull(
            ImageSessionClient.cookieHeaderFromSessions(
                store, vault, "books.example"
            )
        )
    }
}
