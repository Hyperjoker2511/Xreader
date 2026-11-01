package app.xreader.data.extension

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.data.net.HttpTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v1.3.4 (T6): mirror failover, QuickNovel-style backup
 * hosts.
 *
 *  - TRANSPORT silence on the primary (DNS death, connect
 *    failure, a 5xx with NO usable body) retries the same
 *    path on the backup host, in order;
 *  - a host that ANSWERED owns its answer: a wall, a 404,
 *    a 5xx WITH a body — no failover, ever;
 *  - identity is canonical: a chapter URL on the mirror is
 *    the primary's chapter (ChapterIdentity + MirrorHosts);
 *  - credentials never cross: a caller Authorization for
 *    the primary is dropped on the mirror attempt, and the
 *    session cookies stay per-host;
 *  - all hosts down → the T3 classification of the
 *    underlying failure (DNS words), not a new mystery.
 */
class V134MirrorTest {

    private val primary = "novels.example"
    private val mirror = "mirror.example"

    @Before
    fun reset() {
        MirrorHosts.clearForTests()
        PageShapeLog.clearForTests()
    }

    private fun client(
        transport: FakeTransport,
        store: SessionStore = testSessionStore(),
        key: String = "v134-mirror",
        bindings: () -> List<HeaderBinding> = { emptyList() }
    ) = ExtensionHttpClient(
        transport = transport,
        sessionStore = store,
        sessionKey = key,
        label = "MirrorTest",
        whitelist = {
            ExtensionPermissions(domains = listOf(primary, mirror))
        },
        rateLimiter = RateLimiter(minIntervalMs = 0),
        credentialBindings = bindings,
        backupHosts = { listOf(mirror) },
        cooldowns = AvailabilityCooldowns()
    )

    @Test
    fun `dns death on the primary fails over to the mirror`() = runBlocking {
        val transport = FakeTransport { request ->
            when (CookieParsing.hostOf(request.url)) {
                primary -> AppResult.Err(AppError.NoNetwork)
                else -> FakeTransport.ok("<html><title>Books</title></html>")
            }
        }
        val result = client(transport).get("https://novels.example/popular")
        assertTrue(result is AppResult.Ok)
        assertEquals(
            "https://mirror.example/popular",
            (result as AppResult.Ok).value.finalUrl
        )
        assertEquals(
            "the mirror answer is recorded in the page shape",
            mirror,
            PageShapeLog.lastFor("v134-mirror")?.host
        )
    }

    @Test
    fun `a 5xx with no usable body fails over, a 5xx with a body does not`() =
        runBlocking {
            val emptyDown = FakeTransport { request ->
                if (CookieParsing.hostOf(request.url) == primary) {
                    AppResult.Ok(FakeTransport.response(503, ""))
                } else {
                    FakeTransport.ok("mirror body")
                }
            }
            val rescued = client(emptyDown).get("https://novels.example/popular")
            assertTrue(rescued is AppResult.Ok)
            assertEquals(
                "mirror body", (rescued as AppResult.Ok).value.body
            )

            val answeredDown = FakeTransport { request ->
                if (CookieParsing.hostOf(request.url) == primary) {
                    AppResult.Ok(FakeTransport.response(
                        503, "{\"code\":\"SERVICE_UNAVAILABLE\"}"
                    ))
                } else {
                    FakeTransport.ok("mirror body")
                }
            }
            val answered = client(answeredDown, key = "v134-mirror-2")
                .get("https://novels.example/popular")
            assertTrue(answered is AppResult.Ok)
            assertEquals(
                "the primary's own answer stands",
                503, (answered as AppResult.Ok).value.status
            )
            assertTrue(
                "the mirror was never asked",
                answeredDown.requests.none {
                    CookieParsing.hostOf(it.url) == mirror
                }
            )
        }

    @Test
    fun `a wall and a 404 never fail over`() = runBlocking {
        val wall = FakeTransport { request ->
            if (CookieParsing.hostOf(request.url) == primary) {
                AppResult.Ok(FakeTransport.response(
                    403,
                    "<html><head><title>Attention Required!</title>" +
                        "</head><body>challenge-form</body></html>"
                ))
            } else {
                FakeTransport.ok("mirror body")
            }
        }
        val walled = client(wall, key = "v134-mirror-3")
            .get("https://novels.example/popular")
        assertEquals(403, (walled as AppResult.Ok).value.status)
        assertTrue(wall.requests.none {
            CookieParsing.hostOf(it.url) == mirror
        })

        val missing = FakeTransport { request ->
            if (CookieParsing.hostOf(request.url) == primary) {
                AppResult.Ok(FakeTransport.response(404, "not here"))
            } else {
                FakeTransport.ok("mirror body")
            }
        }
        val notFound = client(missing, key = "v134-mirror-4")
            .get("https://novels.example/popular")
        assertEquals(404, (notFound as AppResult.Ok).value.status)
        assertTrue(missing.requests.none {
            CookieParsing.hostOf(it.url) == mirror
        })
    }

    @Test
    fun `identity is canonical across the mirror`() {
        MirrorHosts.register("https://novels.example", listOf(mirror))
        assertEquals(
            ChapterIdentity.canonicalKey(
                "https://novels.example/book/sword-saga/chapter-1"
            ),
            ChapterIdentity.canonicalKey(
                "https://mirror.example/book/sword-saga/chapter-1"
            )
        )
        assertTrue(MirrorHosts.isMirrorOf(mirror, primary))
        assertFalse(MirrorHosts.isMirrorOf(primary, mirror))
    }

    @Test
    fun `a caller credential header never crosses to the mirror`() = runBlocking {
        val transport = FakeTransport { request ->
            if (CookieParsing.hostOf(request.url) == primary) {
                AppResult.Err(AppError.NoNetwork)
            } else {
                FakeTransport.ok("mirror body")
            }
        }
        val result = client(transport, key = "v134-mirror-5").get(
            "https://novels.example/popular",
            headers = mapOf("Authorization" to "Bearer primary-secret")
        )
        assertTrue(result is AppResult.Ok)
        val mirrorRequest = transport.requests.last()
        assertEquals(mirror, CookieParsing.hostOf(mirrorRequest.url))
        assertTrue(
            "the Bearer token stayed on the primary",
            mirrorRequest.headers.keys.none {
                it.equals("Authorization", ignoreCase = true)
            }
        )
    }

    @Test
    fun `sessions stay per host across a failover`() = runBlocking {
        val store = testSessionStore()
        store.save(
            "v134-mirror-6",
            SessionData().withCookies(primary, mapOf("sess" to "primary-cookie"))
        )
        val transport = FakeTransport { request ->
            if (CookieParsing.hostOf(request.url) == primary) {
                AppResult.Err(AppError.NoNetwork)
            } else {
                FakeTransport.ok("mirror body")
            }
        }
        client(transport, store, "v134-mirror-6")
            .get("https://novels.example/popular")
        val mirrorRequest = transport.requests.last()
        val cookie = mirrorRequest.headers.entries.firstOrNull {
            it.key.equals("Cookie", ignoreCase = true)
        }?.value
        assertTrue(
            "the primary's cookie never rode to the mirror: $cookie",
            cookie == null || !cookie.contains("primary-cookie")
        )
    }

    @Test
    fun `all hosts down classifies as the underlying dns failure`() = runBlocking {
        val transport = FakeTransport {
            AppResult.Err(AppError.NoNetwork)
        }
        val result = client(transport, key = "v134-mirror-7")
            .get("https://novels.example/popular")
        assertTrue(result is AppResult.Err)
        assertEquals(
            FetchClassification.Kind.DNS_FAILURE,
            FetchClassification.classify(
                null, (result as AppResult.Err).error
            ).kind
        )
        assertEquals(
            "both hosts were tried",
            2, transport.requests.size
        )
        assertTrue(
            transport.requests.any {
                CookieParsing.hostOf(it.url) == mirror
            }
        )
    }

    @Test
    fun `an undeclared mirror is never touched`() = runBlocking {
        val transport = FakeTransport { request ->
            if (CookieParsing.hostOf(request.url) == primary) {
                AppResult.Err(AppError.NoNetwork)
            } else {
                FakeTransport.ok("other body")
            }
        }
        val strictClient = ExtensionHttpClient(
            transport = transport,
            sessionStore = testSessionStore(),
            sessionKey = "v134-mirror-8",
            label = "MirrorTest",
            whitelist = {
                ExtensionPermissions(domains = listOf(primary))
            },
            rateLimiter = RateLimiter(minIntervalMs = 0),
            backupHosts = { listOf(mirror) },
            cooldowns = AvailabilityCooldowns()
        )
        val result = strictClient.get("https://novels.example/popular")
        assertTrue(result is AppResult.Err)
        assertTrue(
            "the undeclared mirror host was never requested",
            transport.requests.none {
                CookieParsing.hostOf(it.url) == mirror
            }
        )
    }
}
