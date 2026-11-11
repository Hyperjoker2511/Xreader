package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.extensionapi.HostResponse
import app.xreader.extensionapi.SourceChapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

/**
 * v1.5.8 (C1 / F7 F1): the token retry answers the
 * PROTECTED payload on the plain (OkHttp) context TWICE —
 * the token is necessary-but-not-sufficient there (the
 * chapter leg was never context-bound the way the mint
 * was). Post-solve, the chapter fetch escalates ONCE to an
 * in-context fetch parked on the chapter's reader page
 * with the Bearer attached, and the chapter is SERVED.
 * Driven against the SHIPPED [BundledSingles.fenrirRealm]
 * definition with a scripted transport + a fake
 * rendered-session fetcher.
 */
class V158FenrirInContextChapterFixture {

    @get:Rule
    val tmp = TemporaryFolder()

    // A real Fenrir chapter id IS its API URL — the shipped
    // chapter list's jsonLinkTemplate builds exactly this
    // shape — and the shipped AJAX template
    // (api/new/v2/series/{seg-3}/chapters/{seg-1}) derives
    // the endpoint from it as the id itself, while the
    // token spec's check template derives the reader page
    // below as the in-context park URL.
    private val endpoint =
        "https://fenrirealm.com/api/new/v2/series/slug-x/chapters/1"
    private val readerUrl = "https://fenrirealm.com/series/slug-x/1"

    @Before
    fun setUp() {
        FenrirHarvestSink.clearForTests()
    }

    @Test
    fun `protected payload twice on plain road, prose in-context, chapter served`() {
        val plainCalls = AtomicInteger(0)
        val inContextCalls = AtomicInteger(0)
        // A protected/scrambled payload: one long unspaced
        // token — the shape ProseSanity refuses as not-prose.
        val blob = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldZWQ".repeat(30)
        val prose = (1..4).joinToString("") { n ->
            "<p>Sentence $n of the chapter, and the hero walked " +
                "through the gate because the road was long and " +
                "the night had only just begun to fall.</p>"
        }
        val transport = FakeTransport { request ->
            when {
                request.method.equals("POST", ignoreCase = true) &&
                    request.url == "https://fenrirealm.com/api/read-token" ->
                    FakeTransport.ok(
                        "{\"active\":true,\"token\":\"c1-token\"," +
                            "\"expires_at\":9999999999}"
                    )
                request.url == endpoint -> {
                    plainCalls.incrementAndGet()
                    FakeTransport.ok(
                        "{\"content_format\":\"html\",\"content\":\"" +
                            blob + "\"}"
                    )
                }
                else -> AppResult.Ok(FakeTransport.response(404, "no shape"))
            }
        }
        val fakeFetcher = object : RenderedPageFetcher {
            override suspend fun fetchRendered(
                url: String,
                sessionKey: String,
                permissions: ExtensionPermissions,
                waitForSelector: String?
            ): AppResult<HostResponse> =
                AppResult.Ok(HostResponse(404, ""))

            override suspend fun fetchInContext(
                url: String,
                parkUrl: String,
                sessionKey: String?,
                permissions: ExtensionPermissions,
                method: String,
                body: String?,
                headers: Map<String, String>
            ): AppResult<HostResponse> {
                inContextCalls.incrementAndGet()
                assertEquals(
                    "the in-context fetch targets the chapter endpoint",
                    endpoint, url
                )
                assertEquals(
                    "parked on the chapter's reader page",
                    readerUrl, parkUrl
                )
                assertEquals(
                    "the minted Bearer rides the in-context fetch",
                    "Bearer c1-token",
                    headers.entries.firstOrNull {
                        it.key.equals("Authorization", ignoreCase = true)
                    }?.value
                )
                return AppResult.Ok(
                    HostResponse(
                        200,
                        "{\"content_format\":\"html\",\"content\":\"" +
                            prose.replace("\"", "\\\"") + "\"}"
                    )
                )
            }
        }
        val config = BundledSingles.fenrirRealm("v158-fenrir-c1", "Fenrir C1")
        val context = testContext(
            transport, tmp.newFolder(),
            ExtensionPermissions(domains = listOf("fenrirealm.com"))
        )
        val source = DeclarativeSource(
            config, context,
            readTokenStore = testSessionStore(),
            sessionAccess = DeclarativeSource.RenderedSessionAccess(
                fetcher = fakeFetcher,
                sessionKey = "v158-c1",
                permissions = ExtensionPermissions(
                    domains = listOf("fenrirealm.com")
                ),
                // The solve is represented by the session
                // access itself (the seam ExtensionService
                // builds only for a session that was saved);
                // the registry-window flag stays false so the
                // mint keeps its plain OkHttp road and the ONLY
                // in-context call in this fixture is C1's
                // chapter escalation.
                hasRecentSolve = { false }
            )
        )
        val content = runBlocking {
            source.chapterContent(SourceChapter(id = endpoint, name = "Ch 1"))
        }
        assertTrue(
            "the chapter is SERVED from the in-context fetch",
            content.html.contains("the hero walked through the gate")
        )
        assertEquals(
            "the plain road was tried exactly twice (first + token retry)",
            2, plainCalls.get()
        )
        assertEquals(
            "the in-context escalation fired exactly once",
            1, inContextCalls.get()
        )
    }
}
