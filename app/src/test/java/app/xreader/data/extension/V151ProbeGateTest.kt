package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.data.net.HttpTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.1 (item 3): the probe's front door. The v1.5.0 field
 * failure: pasting an API-driven site whose front page wears
 * a human check (BOTI) stopped the probe at a bare
 * "site-check-needed" verdict with manual road-picking
 * buttons — the automatic escalation (render-and-record →
 * API harvest → browser-client tier) never ran.
 *
 * The shape pinned here is BOTI's: the web host serves a
 * Cloudflare check page; the site's real content lives on an
 * api.* host whose calls replay a JS-set cookie as an
 * Authorization header. The probe must escalate on its own,
 * synthesise the API recipe (cookie→header binding included),
 * and save it as working — or, when the API road truly isn't
 * there, surface the gate ONLY after the escalation ran
 * (proven by the request log: the forced render-and-record
 * pass happened before the verdict).
 */
class V151ProbeGateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun clearHarvest() {
        HarvestLog.clearForTests()
    }

    private val webHost = "www.boti.example"
    private val apiHost = "api.boti.example"
    private val prose = "The ferryman poled his raft across the " +
        "black river. The town bells counted the evening hour, " +
        "and every passenger held a lantern against the dark " +
        "water. Rain came on while the old pier groaned below " +
        "the boards. Nobody spoke. The far bank was only a line " +
        "of lights, and the river kept rising all the while."

    private val challengePage = """
        <html><head><title>Just a moment...</title></head>
        <body><div class="cf-turnstile"></div>
        <p>Checking your browser before accessing the site.</p>
        </body></html>
    """.trimIndent()

    private val listingJson = """[
      {"title":"Sword Saga","slug":"sword-saga","cover_url":"/c1.jpg"},
      {"title":"River Tale","slug":"river-tale","cover_url":"/c2.jpg"},
      {"title":"Night Ferry","slug":"night-ferry","cover_url":"/c3.jpg"}
    ]""".trimIndent()

    private fun transport(withApi: Boolean): FakeTransport =
        FakeTransport { request ->
            val url = request.url
            when {
                url.contains(apiHost) && !withApi ->
                    AppResult.Ok(FakeTransport.response(503, "down"))
                url.contains("/api/novels/search") ->
                    FakeTransport.ok(listingJson)
                url.contains("/api/novels/sword-saga/chapters") &&
                    url.contains("/read") ->
                    FakeTransport.ok(
                        """{"body":"$prose","locked":false}"""
                    )
                url.contains("/api/novels/sword-saga/chapters") ->
                    FakeTransport.ok(
                        """{"items":[
                          {"title":"Chapter 1","number":1},
                          {"title":"Chapter 2","number":2},
                          {"title":"Chapter 3","number":3}
                        ],"total":3,"limit":100,"offset":0}"""
                    )
                url.contains("/api/novels/sword-saga") ->
                    FakeTransport.ok(
                        """{"title":"Sword Saga","slug":"sword-saga","synopsis":"A saga."}"""
                    )
                url.contains(webHost) ->
                    FakeTransport.ok(challengePage)
                else -> AppResult.Ok(FakeTransport.response(404, "nope"))
            }
        }

    private fun prober(transport: FakeTransport): LinkProber {
        val factory = HostContextFactory(
            ExtensionStore(tmp.newFolder()), testSessionStore(), transport
        )
        return LinkProber(factory, testSessionStore())
    }

    private fun call(
        url: String,
        body: String
    ) = HarvestedCall(
        url = url,
        method = "GET",
        status = 200,
        contentType = "application/json",
        requestHeaders = mapOf("Authorization" to "tok-xyz"),
        bodySample = body,
        pageCookies = mapOf("userToken" to "tok-xyz")
    )

    private fun seedBotiHarvest() {
        val key = ExtensionHttpClient.linkProbeSessionKey(webHost)
        val calls = listOf(
            call(
                "https://$apiHost/api/novels/search?q=sword&limit=20",
                listingJson
            ),
            call(
                "https://$apiHost/api/novels/sword-saga",
                """{"title":"Sword Saga","slug":"sword-saga","synopsis":"A saga."}"""
            ),
            call(
                "https://$apiHost/api/novels/sword-saga/chapters?limit=100&offset=0",
                """{"items":[
                  {"title":"Chapter 1","number":1},
                  {"title":"Chapter 2","number":2},
                  {"title":"Chapter 3","number":3}
                ],"total":3,"limit":100,"offset":0}"""
            ),
            call(
                "https://$apiHost/api/novels/sword-saga/chapters/1/read",
                """{"body":"$prose","locked":false}"""
            )
        )
        for (harvested in calls) HarvestLog.record(key, harvested)
    }

    @Test
    fun `a check-gated api site is learned through the api road, not stopped at the gate`() =
        runBlocking {
            seedBotiHarvest()
            val outcome = prober(transport(withApi = true))
                .probe("https://$webHost")
            when (outcome) {
                is LinkProber.Outcome.Ready -> {
                    val config = outcome.draft.config
                    // The recipe is the site's API, not a scrape
                    // of the check page.
                    assertTrue(
                        config.popular?.url?.contains(apiHost) == true ||
                            config.baseUrl.contains(apiHost) ||
                            config.extraDomains.contains(apiHost)
                    )
                    // The JS-set cookie replays as a header on
                    // the API host — BOTI's exact shape.
                    val binding = config.credentials?.headerBinding
                    assertNotNull(
                        "the cookie-to-header binding must synthesise",
                        binding
                    )
                    assertEquals("userToken", binding!!.cookieName)
                    assertEquals("Authorization", binding.headerName)
                    assertTrue(binding.hosts.contains(apiHost))
                    // The direct API road won: no rendering mark.
                    assertTrue(!config.needsRendering)
                }
                is LinkProber.Outcome.LoginRequired ->
                    fail(
                        "the probe stopped at the gate " +
                            "(siteCheck=${outcome.siteCheck}) instead of " +
                            "escalating to the API road"
                    )
                is LinkProber.Outcome.Failed ->
                    fail("the probe failed outright: ${outcome.message}")
            }
        }

    @Test
    fun `with no api road the gate surfaces only after the escalation ran`() =
        runBlocking {
            // No harvest seeded, API host down: every automatic
            // tier fails, THEN the gate may surface.
            val transport = transport(withApi = false)
            val outcome = prober(transport).probe("https://$webHost")
            assertTrue(
                "expected the held gate outcome, got $outcome",
                outcome is LinkProber.Outcome.LoginRequired
            )
            assertTrue(
                (outcome as LinkProber.Outcome.LoginRequired).siteCheck
            )
            // Evidence the escalation preceded the verdict:
            // the root was fetched at least twice — the first
            // fetch AND the forced render-and-record pass.
            val rootFetches = transport.requests.count {
                it.url.contains(webHost)
            }
            assertTrue(
                "the forced escalation pass must run before the " +
                    "gate verdict (root fetches=$rootFetches)",
                rootFetches >= 2
            )
        }
}
