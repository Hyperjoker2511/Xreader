package app.xreader.data.extension

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.2 (FIX-1, §2 / §10 item 1): the gate in its REAL shapes.
 * (a) The challenge arrives as a fetch-layer Err (the shape
 * PageFetcher actually produces) — the probe must hold the
 * gate and answer LoginRequired(siteCheck), never Failed.
 * (b) The five-leg honest state EXISTS and is reachable: a
 * site whose catalogue, book page and chapter list read fine
 * but whose chapter TEXT cannot be proven ends at NotProven
 * ("added, reading not proven") with the missing legs named —
 * not saved-as-working, not a bare failure.
 */
class V152GateJourneyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun prober(transport: FakeTransport): LinkProber {
        val filesDir = tmp.newFolder()
        val factory = HostContextFactory(
            ExtensionStore(filesDir), testSessionStore(), transport
        )
        return LinkProber(factory, testSessionStore())
    }

    @Test
    fun `challenge served as a fetch Err holds the gate`() = runBlocking {
        val transport = FakeTransport {
            AppResult.Err(
                AppError.ProviderDown(
                    serviceId = "fetch",
                    httpCode = 403,
                    detail = SiteCheck.challengeDetail()
                )
            )
        }
        val outcome = prober(transport).probe("https://gated.example/")
        val login = outcome as? LinkProber.Outcome.LoginRequired
            ?: throw AssertionError("expected LoginRequired, got $outcome")
        assertTrue("the held gate is a site check", login.siteCheck)
        assertEquals("gated.example", login.host)
    }

    @Test
    fun `a 401 Err with challenge detail holds the gate - challenge wins over login`() =
        runBlocking {
            // §2 row 4 through the same Err shape: a refusal that
            // carries challenge evidence enters the held gate as
            // a SITE CHECK, never as a sign-in and never as a
            // terminal failure.
            val transport = FakeTransport {
                AppResult.Err(
                    AppError.ProviderDown(
                        serviceId = "fetch",
                        httpCode = 401,
                        detail = SiteCheck.challengeDetail()
                    )
                )
            }
            val outcome = prober(transport).probe("https://gated.example/")
            val login = outcome as? LinkProber.Outcome.LoginRequired
                ?: throw AssertionError("expected LoginRequired, got $outcome")
            assertTrue("challenge wins: the gate is a site check", login.siteCheck)
        }

    @Test
    fun `a plain fetch Err is still an honest failure, not a gate`() =
        runBlocking {
            val transport = FakeTransport {
                AppResult.Err(
                    AppError.ProviderDown(
                        serviceId = "fetch",
                        httpCode = 500,
                        detail = "HTTP 500 from origin"
                    )
                )
            }
            val outcome = prober(transport).probe("https://down.example/")
            assertTrue(
                "expected Failed, got $outcome",
                outcome is LinkProber.Outcome.Failed
            )
        }

    @Test
    fun `unproven text leg lands on the honest NotProven state`() =
        runBlocking {
            val outcome = prober(BrokenTextSite.transport())
                .probe(BrokenTextSite.BOOK_URL)
            val notProven = outcome as? LinkProber.Outcome.NotProven
                ?: throw AssertionError("expected NotProven, got $outcome")
            assertEquals(
                LinkProber.Outcome.NotProven.STATE_LABEL,
                "added, reading not proven"
            )
            assertTrue(
                "missing legs: ${notProven.missingLegs}",
                "chapter text" in notProven.missingLegs
            )
        }

    /** Catalogue + book + chapter list read; chapter pages 404,
     * so the text leg can never be proven. */
    private object BrokenTextSite {
        const val BASE = "https://brokentext.example"
        const val BOOK_URL = "$BASE/book/quiet-star"

        private val homePage = """
            <html><head><title>Broken Text Reads</title></head><body>
            <div class="shelf">
              <div class="card"><a href="/book/quiet-star">Quiet Star</a></div>
              <div class="card"><a href="/book/lonely-comet">Lonely Comet</a></div>
              <div class="card"><a href="/book/ember-tide">Ember Tide</a></div>
              <div class="card"><a href="/book/pale-harbour">Pale Harbour</a></div>
            </div>
            </body></html>
        """.trimIndent()

        private val bookPage = """
            <html><head>
              <title>Quiet Star - Broken Text Reads</title>
              <meta property="og:image" content="$BASE/covers/quiet.jpg"/>
            </head><body>
            <h1>Quiet Star</h1>
            <div class="blurb">A quiet star burns over the salt flats, and the cartographer who mapped it has vanished without a trace. Her apprentice crosses the flats with an unfinished atlas.</div>
            <div class="toc">
              <span><a href="/book/quiet-star/3">3</a></span>
              <span><a href="/book/quiet-star/2">2</a></span>
              <span><a href="/book/quiet-star/1">1</a></span>
            </div>
            </body></html>
        """.trimIndent()

        fun transport(): FakeTransport {
            val pages = mapOf(
                BASE to homePage,
                "$BASE/" to homePage,
                BOOK_URL to bookPage
            )
            return FakeTransport { request ->
                val body = pages[request.url]
                if (body == null) {
                    AppResult.Ok(FakeTransport.response(404, "not found"))
                } else {
                    FakeTransport.ok(body)
                }
            }
        }
    }
}
