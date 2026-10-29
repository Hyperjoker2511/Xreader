package app.xreader.data.extension

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.data.net.HttpTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whitelist gate (PLAN §9.1 gate): direct hosts, subdomain rules,
 * user blocks, and — critically — REDIRECT hops. The transport never
 * follows redirects; the client re-checks every hop, so a whitelisted
 * page cannot bounce a source somewhere it isn't allowed.
 */
class ExtensionHttpClientTest {

    private fun client(
        transport: FakeTransport,
        permissions: ExtensionPermissions = ExtensionPermissions(
            domains = listOf("novels.example")
        ),
        sessionStore: SessionStore = testSessionStore()
    ): ExtensionHttpClient = ExtensionHttpClient(
        transport = transport,
        sessionStore = sessionStore,
        sessionKey = "test-session",
        label = "Test Source",
        whitelist = { permissions },
        rateLimiter = RateLimiter(minIntervalMs = 0)
    )

    @Test
    fun `whitelisted host loads`() = runBlocking {
        val transport = FakeTransport { FakeTransport.ok("<html>ok</html>") }
        val result = client(transport).get("https://novels.example/book/1")
        assertTrue(result is AppResult.Ok)
        assertEquals(200, (result as AppResult.Ok).value.status)
    }

    @Test
    fun `unknown host is refused before any request`() = runBlocking {
        val transport = FakeTransport { FakeTransport.ok("x") }
        val result = client(transport).get("https://evil.example/steal")
        assertTrue(result is AppResult.Err)
        val error = (result as AppResult.Err).error
        assertTrue(error is AppError.ExtensionFailed)
        assertTrue(error.detail!!.contains("blocked"))
        assertTrue("no request may leave the device", transport.requests.isEmpty())
    }

    @Test
    fun `subdomains of a declared domain are allowed, lookalikes are not`() = runBlocking {
        val transport = FakeTransport { FakeTransport.ok("x") }
        val client = client(transport)
        assertTrue(client.get("https://www.novels.example/") is AppResult.Ok)
        assertTrue(client.get("https://novels.example.evil.com/") is AppResult.Err)
        assertTrue(client.get("https://notnovels.example/") is AppResult.Err)
    }

    @Test
    fun `user blocked domain is refused`() = runBlocking {
        val transport = FakeTransport { FakeTransport.ok("x") }
        val client = client(
            transport,
            ExtensionPermissions(
                domains = listOf("novels.example"),
                blockedDomains = listOf("cdn.novels.example")
            )
        )
        assertTrue(client.get("https://novels.example/") is AppResult.Ok)
        assertTrue(client.get("https://cdn.novels.example/x") is AppResult.Err)
    }

    @Test
    fun `network master switch off refuses everything`() = runBlocking {
        val transport = FakeTransport { FakeTransport.ok("x") }
        val client = client(
            transport,
            ExtensionPermissions(domains = listOf("novels.example"), canUseNetwork = false)
        )
        assertTrue(client.get("https://novels.example/") is AppResult.Err)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `redirect inside the whitelist is followed`() = runBlocking {
        val transport = FakeTransport { request ->
            when (request.url) {
                "https://novels.example/old" -> AppResult.Ok(
                    FakeTransport.response(
                        302, "",
                        headers = mapOf("Location" to "/new")
                    )
                )
                "https://novels.example/new" -> FakeTransport.ok("landed")
                else -> AppResult.Ok(FakeTransport.response(404, ""))
            }
        }
        val result = client(transport).get("https://novels.example/old")
        assertTrue(result is AppResult.Ok)
        val response = (result as AppResult.Ok).value
        assertEquals("landed", response.body)
        assertEquals("https://novels.example/new", response.finalUrl)
    }

    @Test
    fun `redirect to a non-whitelisted host is blocked at the hop`() = runBlocking {
        val transport = FakeTransport { request ->
            when (request.url) {
                "https://novels.example/start" -> AppResult.Ok(
                    FakeTransport.response(
                        302, "",
                        headers = mapOf("Location" to "https://evil.example/landing")
                    )
                )
                else -> FakeTransport.ok("should never be fetched")
            }
        }
        val result = client(transport).get("https://novels.example/start")
        assertTrue(result is AppResult.Err)
        assertTrue(
            "the evil hop must never be requested",
            transport.requests.none { it.url.contains("evil.example") }
        )
    }

    @Test
    fun `session cookies and user agent are attached for the session host`() = runBlocking {
        val sessions = testSessionStore()
        sessions.save(
            "test-session",
            SessionData(
                cookies = mapOf("novels.example" to mapOf("session" to "abc123")),
                userAgent = "TestBrowser/1.0"
            )
        )
        var seen: HttpTransport.Request? = null
        val transport = FakeTransport { request ->
            seen = request
            FakeTransport.ok("ok")
        }
        val result = client(transport, sessionStore = sessions)
            .get("https://novels.example/book/1")
        assertTrue(result is AppResult.Ok)
        val headers = seen!!.headers
        assertEquals("session=abc123", headers["Cookie"])
        assertEquals("TestBrowser/1.0", headers["User-Agent"])
    }

    @Test
    fun `set-cookie responses are captured into the session`() = runBlocking {
        val sessions = testSessionStore()
        val transport = FakeTransport {
            AppResult.Ok(
                FakeTransport.response(
                    200, "ok",
                    headers = mapOf("Set-Cookie" to "token=xyz; Path=/; HttpOnly")
                )
            )
        }
        client(transport, sessionStore = sessions).get("https://novels.example/login")
        val saved = sessions.load("test-session")
        assertEquals("xyz", saved?.cookies?.get("novels.example")?.get("token"))
    }

    @Test
    fun `post sends the body through the transport`() = runBlocking {
        var seen: HttpTransport.Request? = null
        val transport = FakeTransport { request ->
            seen = request
            FakeTransport.ok("posted")
        }
        val result = client(transport).post(
            "https://novels.example/search", "q=sword",
            "application/x-www-form-urlencoded"
        )
        assertTrue(result is AppResult.Ok)
        assertEquals("POST", seen!!.method)
        assertEquals("q=sword", seen!!.body)
    }
}
