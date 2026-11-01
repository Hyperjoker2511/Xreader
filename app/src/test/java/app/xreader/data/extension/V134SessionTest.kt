package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.extensionapi.HostHttp
import app.xreader.extensionapi.HostResponse
import app.xreader.extensionapi.SourceChapter
import app.xreader.extensionapi.SourceException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * v1.3.4 (T1, session v2): the read-token coordinator and
 * the credential bindings, pinned against the site shapes
 * the research verified (TOKEN-SESSION-RESEARCH §1–2):
 *
 *  - single-flight: 8 concurrent locked chapters, ONE mint;
 *  - generation-aware invalidation (a stale failure never
 *    revokes a newer token);
 *  - monotonic expiry + renewal margin; a stored token is
 *    revalidated after restart, never trusted;
 *  - mint refusals map by shape: challenge_required → the
 *    check, auth-shaped → sign in, 429 → the shared park;
 *  - the FenrirRealm road end-to-end through
 *    DeclarativeSource: locked → mint → Bearer retry →
 *    prose; encrypted is terminal; locked with no contract
 *    is terminal with NO other fetch;
 *  - BOTI's binding: the userToken cookie rides verbatim
 *    as Authorization to api.mystorywave.com ONLY, and is
 *    gone after sign-out;
 *  - the encrypted store round trip + corrupt-record
 *    tolerance.
 */
class V134SessionTest {

    @Before
    fun resetRegistries() {
        ReadTokenCoordinators.clearForTests()
        PageShapeLog.clearForTests()
    }

    private val wallNow = AtomicLong(1_700_000_000_000L)
    private val elapsedNow = AtomicLong(1_000_000L)

    /** A scripted HostHttp for coordinator-level tests. */
    private class FakeHostHttp(
        val onPost: (url: String, body: String) -> HostResponse
    ) : HostHttp {
        val posts = ConcurrentLinkedQueue<Pair<String, String>>()
        override fun get(url: String, headers: Map<String, String>): HostResponse =
            HostResponse(200, "")

        override fun post(
            url: String,
            body: String,
            contentType: String,
            headers: Map<String, String>
        ): HostResponse {
            posts += url to body
            return onPost(url, body)
        }
    }

    private fun spec() = OperationTokenSpec(
        mintUrl = "https://fenrir.example/api/read-token",
        mintBodyTemplate = "{\"chapter_id\":\"{chapter_id}\"}",
        checkUrlTemplate = "https://fenrir.example/series/{seg-3}/{seg-1}"
    )

    private fun coordinator(
        http: FakeHostHttp,
        store: SessionStore? = null,
        key: String = ""
    ) = ReadTokenCoordinator(
        spec(), store, key,
        nowMs = { wallNow.get() },
        elapsedMs = { elapsedNow.get() }
    )

    private fun mintOk(token: String = "tok-1", lifetimeSec: Long = 300) =
        HostResponse(
            200,
            "{\"active\":true,\"token\":\"$token\"," +
                "\"expires_at\":${wallNow.get() / 1000 + lifetimeSec}}"
        )

    @Test
    fun `single flight - eight concurrent locked chapters mint once`() {
        val mints = AtomicInteger(0)
        val http = FakeHostHttp { _, _ ->
            mints.incrementAndGet()
            Thread.sleep(150)
            mintOk()
        }
        val coordinator = coordinator(http)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val outcomes = ConcurrentLinkedQueue<ReadTokenCoordinator.MintOutcome>()
        repeat(8) {
            pool.execute {
                start.await()
                outcomes += coordinator.token(http, "42")
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS))
        assertEquals("exactly one mint", 1, mints.get())
        assertEquals(8, outcomes.size)
        assertTrue(
            outcomes.all {
                it is ReadTokenCoordinator.MintOutcome.Ready && it.token == "tok-1"
            }
        )
    }

    @Test
    fun `generation race - a stale failure cannot revoke a newer token`() {
        val http = FakeHostHttp { _, _ -> mintOk("tok-1") }
        val coordinator = coordinator(http)
        val first = coordinator.token(http, "1")
            as ReadTokenCoordinator.MintOutcome.Ready
        // The site re-issues in-band: a new generation lands.
        coordinator.noteIssued(
            mapOf(
                "x-read-token-issued" to "tok-2",
                "x-read-token-expires-at" to
                    (wallNow.get() / 1000 + 300).toString()
            )
        )
        // The OLD generation's failure arrives late.
        coordinator.invalidate(first.generation)
        assertEquals("tok-2", coordinator.currentToken()?.value)
        // Invalidating the CURRENT generation does clear it.
        coordinator.invalidate(coordinator.currentToken()!!.generation)
        assertNull(coordinator.currentToken())
    }

    @Test
    fun `renewal margin - a token inside its margin re-mints on the monotonic clock`() {
        val mints = AtomicInteger(0)
        val http = FakeHostHttp { _, _ ->
            mintOk("tok-${mints.incrementAndGet()}", lifetimeSec = 90)
        }
        val coordinator = coordinator(http)
        val first = coordinator.token(http, "1")
            as ReadTokenCoordinator.MintOutcome.Ready
        assertEquals("tok-1", first.token)
        // 40 monotonic seconds pass: inside the 60 s margin
        // (90 s life), so the next need re-mints — even
        // though the WALL clock never moved.
        elapsedNow.addAndGet(40_000)
        val second = coordinator.token(http, "2")
            as ReadTokenCoordinator.MintOutcome.Ready
        assertEquals("tok-2", second.token)
        assertEquals(2, mints.get())
    }

    @Test
    fun `wall clock jumps do not kill a live token`() {
        val http = FakeHostHttp { _, _ -> mintOk("tok-1", lifetimeSec = 300) }
        val coordinator = coordinator(http)
        coordinator.token(http, "1")
        // The wall clock leaps two hours (timezone travel);
        // only 10 monotonic seconds passed — still valid.
        wallNow.addAndGet(2 * 3600_000L)
        elapsedNow.addAndGet(10_000)
        assertEquals("tok-1", coordinator.currentToken()?.value)
    }

    @Test
    fun `a stored token is revalidated after restart, never trusted`() = runBlocking {
        val store = testSessionStore()
        store.saveReadToken(
            "fenrir",
            StoredReadToken(
                value = "stored-tok",
                expiresAtMs = wallNow.get() + 3600_000,
                generation = 7
            )
        )
        val mints = AtomicInteger(0)
        val http = FakeHostHttp { _, _ ->
            mints.incrementAndGet()
            mintOk("fresh-tok")
        }
        val coordinator = coordinator(http, store, "fenrir")
        // Present-but-unverified: no proactive attach…
        assertNull(coordinator.currentToken())
        // …and the first need mints fresh, generation continuing.
        val outcome = coordinator.token(http, "1")
            as ReadTokenCoordinator.MintOutcome.Ready
        assertEquals("fresh-tok", outcome.token)
        assertEquals(8L, outcome.generation)
        assertEquals(1, mints.get())
    }

    @Test
    fun `challenge_required mint maps to the check`() {
        val http = FakeHostHttp { _, _ ->
            HostResponse(403, "{\"challenge_required\":true}")
        }
        val outcome = coordinator(http).token(http, "1")
        assertTrue(outcome is ReadTokenCoordinator.MintOutcome.ChallengeRequired)
    }

    @Test
    fun `auth-shaped mint refusal maps to sign-in`() {
        val http = FakeHostHttp { _, _ ->
            HostResponse(401, "{\"error\":\"unauthorized\"}")
        }
        val outcome = coordinator(http).token(http, "1")
        assertTrue(outcome is ReadTokenCoordinator.MintOutcome.SessionDead)
    }

    @Test
    fun `a 429 mint parks every waiter on the shared cooldown`() {
        val mints = AtomicInteger(0)
        val http = FakeHostHttp { _, _ ->
            mints.incrementAndGet()
            HostResponse(429, "slow down", mapOf("Retry-After" to "45"))
        }
        val coordinator = coordinator(http)
        val first = coordinator.token(http, "1")
        assertTrue(first is ReadTokenCoordinator.MintOutcome.RateLimited)
        assertEquals(45L, (first as ReadTokenCoordinator.MintOutcome.RateLimited)
            .retryAfterSeconds)
        val second = coordinator.token(http, "2")
        assertTrue(second is ReadTokenCoordinator.MintOutcome.RateLimited)
        assertEquals("no second mint during the park", 1, mints.get())
    }

    @Test
    fun `encrypted store round trip and corrupt record tolerance`() = runBlocking {
        val store = testSessionStore()
        val token = StoredReadToken("sekret", 123_456_789L, 3)
        store.saveReadToken("src-1", token)
        assertEquals(token, store.loadReadToken("src-1"))
        store.clearReadToken("src-1")
        assertNull(store.loadReadToken("src-1"))

        // A record written by something else (or corrupted)
        // reads as ABSENT, never a crash.
        val vaultStore = testSessionStore()
        vaultStore.saveReadToken("src-2", token)
        val vaultField = SessionStore::class.java.getDeclaredField("vault")
            .apply { isAccessible = true }
        val vault = vaultField.get(vaultStore)
            as app.xreader.core.security.KeyVault
        vault.put(
            app.xreader.core.security.KeyVault.readTokenKey("src-2"),
            "{\"version\":99,\"value\":\"x\"}"
        )
        assertNull(vaultStore.loadReadToken("src-2"))
    }

    // ── The FenrirRealm road through DeclarativeSource ──────

    private val fenrirHost = "fenrir.example"
    private val chapterUrl =
        "https://fenrir.example/api/series/absolute-regression/chapters/1"

    private fun fenrirConfig(withCredentials: Boolean): DeclarativeConfig =
        DeclarativeConfig(
            id = "fenrir-test-${if (withCredentials) "cred" else "plain"}",
            name = "Fenrir Test",
            lang = "en",
            baseUrl = "https://fenrir.example",
            search = null,
            popular = null,
            latest = null,
            details = DetailsSpec(
                chapters = ChapterListSpec(
                    list = "a", name = "@text", link = "@href"
                )
            ),
            chapter = ChapterSpec(
                content = "body",
                ajax = ChapterTextAjax(
                    urlTemplate = "https://fenrir.example/api" +
                        "/series/{seg-3}/chapters/{seg-1}",
                    jsonContentField = "content"
                )
            ),
            signInNeeded = true,
            credentials = if (withCredentials) {
                CredentialSpec(operationToken = spec())
            } else {
                null
            }
        )

    private fun tempDir(): File =
        java.nio.file.Files.createTempDirectory("v134").toFile()

    @Test
    fun `locked chapter mints once and retries with the bearer token`() {
        val mintBodies = ConcurrentLinkedQueue<String>()
        val chapterAuth = ConcurrentLinkedQueue<String?>()
        val transport = FakeTransport { request ->
            when {
                request.url == "https://fenrir.example/api/read-token" -> {
                    mintBodies += request.body ?: ""
                    FakeTransport.ok(
                        "{\"active\":true,\"token\":\"tok-abc\"," +
                            "\"expires_at\":9999999999}"
                    )
                }
                request.url == chapterUrl -> {
                    val auth = request.headers.entries.firstOrNull {
                        it.key.equals("Authorization", ignoreCase = true)
                    }?.value
                    // (ConcurrentLinkedQueue forbids nulls:
                    // the anonymous first fetch records "<none>".)
                    chapterAuth += auth ?: "<none>"
                    if (auth == "Bearer tok-abc") {
                        FakeTransport.ok(
                            "{\"content\":\"<p>The regression " +
                                "begins in earnest.</p>\"," +
                                "\"content_format\":\"html\"}"
                        )
                    } else {
                        FakeTransport.ok(
                            "{\"content\":\"\",\"content_format\":\"locked\"," +
                                "\"remint_chapter_id\":\"uuid-9\"}"
                        )
                    }
                }
                else -> AppResult.Ok(FakeTransport.response(404, "nope"))
            }
        }
        val source = DeclarativeSource(
            fenrirConfig(withCredentials = true),
            testContext(
                transport, tempDir(),
                ExtensionPermissions(domains = listOf(fenrirHost))
            )
        )
        val content = source.chapterContent(
            SourceChapter(id = chapterUrl, name = "Chapter 1")
        )
        assertTrue(content.html.contains("The regression begins"))
        assertEquals("one mint", 1, mintBodies.size)
        assertTrue(
            "the mint body carries the record's remint hint",
            mintBodies.single().contains("uuid-9")
        )
        assertEquals(
            "first fetch anonymous, retry with the token",
            listOf("<none>", "Bearer tok-abc"),
            chapterAuth.toList()
        )
    }

    @Test
    fun `locked chapter with a challenge mint surfaces the check marker`() {
        val transport = FakeTransport { request ->
            when (request.url) {
                "https://fenrir.example/api/read-token" ->
                    AppResult.Ok(FakeTransport.response(
                        403, "{\"challenge_required\":true}"
                    ))
                chapterUrl -> FakeTransport.ok(
                    "{\"content\":\"\",\"content_format\":\"locked\"}"
                )
                else -> AppResult.Ok(FakeTransport.response(404, "nope"))
            }
        }
        val source = DeclarativeSource(
            fenrirConfig(withCredentials = true),
            testContext(
                transport, tempDir(),
                ExtensionPermissions(domains = listOf(fenrirHost))
            )
        )
        try {
            source.chapterContent(
                SourceChapter(id = chapterUrl, name = "Chapter 1")
            )
            fail("expected the locked chapter to fail")
        } catch (e: SourceException) {
            assertTrue(
                e.message.orEmpty().contains(
                    FetchClassification.MINT_CHALLENGE_MARKER
                )
            )
            assertEquals(
                SessionNeed.Kind.SITE_CHECK,
                SessionNeed.classify(fenrirConfig(true), e.message, null)
            )
        }
    }

    @Test
    fun `encrypted format is terminal and never mints`() {
        val transport = FakeTransport { request ->
            when (request.url) {
                chapterUrl -> FakeTransport.ok(
                    "{\"content\":\"AAAA\",\"content_format\":\"encrypted\"}"
                )
                else -> AppResult.Ok(FakeTransport.response(404, "nope"))
            }
        }
        val source = DeclarativeSource(
            fenrirConfig(withCredentials = true),
            testContext(
                transport, tempDir(),
                ExtensionPermissions(domains = listOf(fenrirHost))
            )
        )
        try {
            source.chapterContent(
                SourceChapter(id = chapterUrl, name = "Chapter 1")
            )
            fail("expected the encrypted chapter to fail")
        } catch (e: SourceException) {
            assertTrue(
                e.message.orEmpty().contains(
                    FetchClassification.ENCRYPTED_MARKER
                )
            )
        }
        assertTrue(
            "no mint was attempted",
            transport.requests.none { it.url.contains("read-token") }
        )
    }

    @Test
    fun `locked with no credential contract is terminal and fetches nothing else`() {
        val webReaderUrl = "https://fenrir.example/series/absolute-regression/1"
        val transport = FakeTransport { request ->
            when (request.url) {
                chapterUrl -> FakeTransport.ok(
                    "{\"content\":\"\",\"content_format\":\"locked\"}"
                )
                webReaderUrl -> FakeTransport.ok(
                    "<html><body><article><p>Scraped prose that " +
                        "must never stand in for the chapter.</p>" +
                        "</article></body></html>"
                )
                else -> AppResult.Ok(FakeTransport.response(404, "nope"))
            }
        }
        val source = DeclarativeSource(
            fenrirConfig(withCredentials = false),
            testContext(
                transport, tempDir(),
                ExtensionPermissions(domains = listOf(fenrirHost))
            )
        )
        try {
            source.chapterContent(
                SourceChapter(id = chapterUrl, name = "Chapter 1")
            )
            fail("expected the locked chapter to fail")
        } catch (e: SourceException) {
            assertEquals(
                DeclarativeSource.AJAX_TEXT_EMPTY_MESSAGE, e.message
            )
        }
        assertEquals(
            "the chapter endpoint was the ONLY fetch",
            listOf(chapterUrl),
            transport.requests.map { it.url }
        )
    }

    // ── BOTI's header binding through the client ────────────

    @Test
    fun `boti binding replays userToken verbatim to the api host only`() = runBlocking {
        val store = testSessionStore()
        store.save(
            "ext-boti",
            SessionData().withCookies(
                "botitranslation.com", mapOf("userToken" to "tok-xyz")
            )
        )
        val transport = FakeTransport { FakeTransport.ok("{}") }
        val client = ExtensionHttpClient(
            transport = transport,
            sessionStore = store,
            sessionKey = "ext-boti",
            label = "BOTI",
            whitelist = {
                ExtensionPermissions(
                    domains = listOf(
                        "botitranslation.com", "api.mystorywave.com"
                    )
                )
            },
            rateLimiter = RateLimiter(minIntervalMs = 0),
            credentialBindings = {
                listOf(
                    HeaderBinding(
                        cookieName = "userToken",
                        headerName = "Authorization",
                        prefix = "",
                        hosts = listOf("api.mystorywave.com")
                    )
                )
            },
            cooldowns = AvailabilityCooldowns()
        )
        val apiResult = client.get(
            "https://api.mystorywave.com/story-wave-backend/api/feed"
        )
        assertTrue(apiResult is AppResult.Ok)
        val apiAuth = transport.requests.last().headers.entries
            .firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
        assertNotNull("the binding attached the header", apiAuth)
        assertEquals("verbatim, no prefix", "tok-xyz", apiAuth!!.value)

        client.get("https://botitranslation.com/mobile/")
        val webAuth = transport.requests.last().headers.entries
            .firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
        assertNull("never to another host", webAuth)

        // Sign-out drops the credential with the session.
        store.clear("ext-boti")
        client.get("https://api.mystorywave.com/story-wave-backend/api/feed")
        val goneAuth = transport.requests.last().headers.entries
            .firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
        assertNull("dropped on sign-out", goneAuth)
    }

    @Test
    fun `check url template expands against the chapter url`() {
        assertEquals(
            "https://fenrir.example/series/absolute-regression/1",
            spec().checkUrlFor(chapterUrl)
        )
    }
}
