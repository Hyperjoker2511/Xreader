package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.3 (finding #8, fix 4): the DEVICE-PATH twin of
 * [BotiRecordedProofTest]. The recorded proof deposits a full
 * browsing session into the HarvestLog by hand — a deposit no
 * device code path ever produces (the gate was green over a
 * world the app never creates, and the owner's add died live).
 *
 * This class models what a real device add actually collects:
 *
 *  - the BOTI test's harvest is filled ONLY with the calls one
 *    render of the pasted page produces (the homepage's own
 *    calls — orgCustomConfigs and genres; NO book, chapter-list
 *    or chapter-text call is hand-deposited). On the shipped
 *    v1.5.2 code the probe returns the generic shell failure
 *    here, because the held shell gate skips the KnownSites
 *    lane; after fix 1 the lane drives and the probe reaches
 *    Ready carrying the lane's own config.
 *  - the hop test pins fix 2's trigger: a one-page deposit
 *    holding a listing-shaped call makes the probe render the
 *    first record's book page as a second hop (the deposit a
 *    JVM rig cannot complete — harvest deposits are a WebView
 *    observer side effect — so the trigger, not the deposit,
 *    is what is asserted).
 *  - the check test pins fix 3: a held shell failure whose
 *    probe session picked up Cloudflare challenge evidence on
 *    a later page surfaces as LoginRequired(siteCheck = true)
 *    instead of the bare failure; without the evidence the
 *    failure is byte-for-byte the old one.
 */
class V153BotiDevicePathProofTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val apiBase =
        "https://api.mystorywave.com/story-wave-backend/api/v1"
    private val webHost = "botitranslation.com"
    private val siteHeaders = mapOf(
        "lang" to "en_US",
        "site-domain" to webHost
    )

    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) {
            "missing test resource $name"
        }.readBytes().toString(Charsets.UTF_8)

    private fun prober(transport: FakeTransport): LinkProber {
        val factory = HostContextFactory(
            ExtensionStore(tmp.newFolder()),
            testSessionStore(),
            transport
        )
        return LinkProber(factory, testSessionStore())
    }

    // ── the BOTI lane's rendered pages (V121 fixture shapes) ──

    /** The static root: the Vite shell, exactly as classified
     * on the device (empty mount, no readable structure). */
    private val shellPage =
        "<html><head><title>Home</title></head><body>" +
            "<div id=\"app\"></div>" +
            "<script type=\"module\" src=\"/assets/index.js\"></script>" +
            "</body></html>"

    private fun bookCard(id: Int, title: String): String =
        "<div class=\"swiper-slide\">" +
            "<a href=\"/mobile/book/$id\">" +
            "<img src=\"/covers/$id.jpg\" alt=\"$title\"/></a></div>"

    private val laneHome =
        "<html><head><title>Home</title></head>" +
            "<body class=\"skin-default home\">" +
            "<div class=\"main-container\">" +
            "<section class=\"rail\"><h2>Popular this week</h2>" +
            "<div class=\"swiper\"><div class=\"swiper-wrapper\">" +
            bookCard(101, "Sword Emperor of the Ninth Heaven") +
            bookCard(102, "Reborn as the Villain's Butler") +
            bookCard(103, "The Archmage's Second Life") +
            bookCard(104, "My System Levels Up Alone") +
            "</div></div></section>" +
            "</div></body></html>"

    private val laneBookPage =
        "<html><head><title>Sword Emperor of the Ninth Heaven</title></head>" +
            "<body class=\"skin-default\"><div class=\"main-container\">" +
            "<h1>Sword Emperor of the Ninth Heaven</h1>" +
            "<img class=\"cover\" src=\"/covers/101.jpg\" " +
            "alt=\"Sword Emperor of the Ninth Heaven\"/>" +
            "<ul class=\"chapter-list\">" +
            "<li><a href=\"/mobile/chapter/3001\">Chapter 1</a></li>" +
            "<li><a href=\"/mobile/chapter/3002\">Chapter 2</a></li>" +
            "<li><a href=\"/mobile/chapter/3003\">Chapter 3</a></li>" +
            "<li><a href=\"/mobile/chapter/3004\">Chapter 4</a></li>" +
            "</ul></div></body></html>"

    private val prose = listOf(
        "The rain had been falling on the harbour town for three " +
            "days when the letter arrived, sealed with black wax " +
            "and carrying no name but his own. Tomas read it twice " +
            "by the lamp, then fed it to the fire and watched the " +
            "words curl away into the dark above the chimney.",
        "Some messages are safer forgotten, and some debts are " +
            "only collected after midnight. He had been expecting " +
            "this one for years, ever since the winter the fleet " +
            "came home without its admiral and the town learned " +
            "to ask no questions of the sea.",
        "By dawn the rain had stopped. He packed a small bag, " +
            "left a note for the harbourmaster, and took the coast " +
            "road north, where the cliffs lean over the water and " +
            "the gulls circle the old watchtower, waiting for a " +
            "ship that never returns."
    )

    private val laneChapterPage =
        "<html><body><main><article><h1>Chapter 1</h1>" +
            prose.joinToString("") { "<p>$it</p>" } +
            "</article></main></body></html>"

    private fun laneTransport() = FakeTransport { request ->
        val url = request.url
        val body = when {
            url == "https://$webHost/" || url == "https://$webHost" ->
                shellPage
            url == "https://$webHost/mobile/" -> laneHome
            url.startsWith("https://$webHost/mobile/book/") ->
                laneBookPage
            url.startsWith("https://$webHost/mobile/chapter/") ->
                laneChapterPage
            else -> null
        }
        if (body != null) {
            AppResult.Ok(FakeTransport.response(200, body))
        } else {
            AppResult.Ok(FakeTransport.response(404, "no recorded shape"))
        }
    }

    // ── Item 23: the device-path gate test ────────────────────

    @Test
    fun `boti root paste with only a one-page render deposit reaches Ready through the lane`() =
        runBlocking {
            // ── deposited: ONLY what rendering the pasted
            // homepage produces — its own two calls. No book,
            // chapter-list or chapter-text call is deposited;
            // that hand-stocked session is the dishonesty this
            // test exists to kill.
            val sessionKey =
                ExtensionHttpClient.linkProbeSessionKey(webHost)
            HarvestLog.clearForTests()
            PageShapeLog.clearForTests()
            fun record(url: String, body: String) {
                HarvestLog.record(
                    sessionKey,
                    HarvestedCall(
                        url = url,
                        method = "GET",
                        status = 200,
                        contentType = "application/json",
                        requestHeaders = siteHeaders,
                        bodySample = body
                    )
                )
            }
            record(
                "$apiBase/custom/orgCustomConfigs?pageName=homepage",
                resource("boti/orgCustomConfigs.json")
            )
            record(
                "$apiBase/content/books/genres",
                resource("boti/genres.json")
            )

            val transport = laneTransport()
            val outcome = prober(transport).probe("https://$webHost/")
            val ready = outcome as? LinkProber.Outcome.Ready
                ?: throw AssertionError(
                    "the device-path BOTI probe did not reach Ready " +
                        "from a one-page deposit: $outcome"
                )
            // The winner is the KnownSites lane's own config —
            // since v1.5.7 (Scope E) that config IS the site's
            // /v1 JSON API recipe (KnownSites.botiConfig: the
            // rendered /mobile/ road never completed a catalogue
            // on device; the API answers every leg anonymously).
            // It is still not a HARVEST-DERIVED recipe: the
            // one-page deposit cannot synthesise one (it holds
            // no book or chapter calls to assemble from), and
            // the lane's catalogue URL below is the config's
            // own declared endpoint, page template included.
            // (v1.5.10 I6b: this assertion previously expected
            // the retired /mobile/ catalogue URL.)
            val popularUrl = ready.draft.config.popular?.url.orEmpty()
            assertTrue(
                "the winning config is not the BOTI lane's: $popularUrl",
                popularUrl ==
                    "https://api.mystorywave.com/story-wave-backend" +
                    "/api/v1/content/books?pageNumber={page}&pageSize=20"
            )
            assertTrue(
                "the lane's drive never fetched its API " +
                    "catalogue; requests were " +
                    transport.requests.map { it.url },
                transport.requests.any {
                    it.url.contains("api.mystorywave.com") &&
                        it.url.contains("/content/books")
                }
            )
        }

    // ── Item 21: the render-and-record hop's trigger ──────────

    private val hopHost = "hopsite.example"
    private val hopBookUrl = "https://$hopHost/book/first"

    private fun hopTransport() = FakeTransport { request ->
        val body = when (request.url) {
            "https://$hopHost/" ->
                "<html><head><title>Hop</title></head><body>" +
                    "<div id=\"app\"></div></body></html>"
            hopBookUrl ->
                "<html><head><title>First Book</title></head>" +
                    "<body><h1>First Book</h1></body></html>"
            else -> null
        }
        if (body != null) {
            AppResult.Ok(FakeTransport.response(200, body))
        } else {
            AppResult.Ok(FakeTransport.response(404, "no recorded shape"))
        }
    }

    private fun depositListingCall() {
        val sessionKey =
            ExtensionHttpClient.linkProbeSessionKey(hopHost)
        HarvestLog.record(
            sessionKey,
            HarvestedCall(
                url = "https://api.$hopHost/api/books?page=1",
                method = "GET",
                status = 200,
                contentType = "application/json",
                bodySample = """{"items":[
                    {"title":"First Book","url":"/book/first"},
                    {"title":"Second Book","url":"/book/second"},
                    {"title":"Third Book","url":"/book/third"}
                ]}""".trimIndent()
            )
        )
    }

    @Test
    fun `a listing-shaped deposit makes the probe render the first record's book page`() =
        runBlocking {
            HarvestLog.clearForTests()
            PageShapeLog.clearForTests()
            depositListingCall()
            val transport = hopTransport()
            prober(transport).probe("https://$hopHost/")
            assertTrue(
                "the hop never rendered the first record's book " +
                    "page; requests were " +
                    transport.requests.map { it.url },
                transport.requests.any { it.url == hopBookUrl }
            )
        }

    @Test
    fun `without a listing-shaped deposit no hop render happens`() =
        runBlocking {
            HarvestLog.clearForTests()
            PageShapeLog.clearForTests()
            val transport = hopTransport()
            val outcome = prober(transport).probe("https://$hopHost/")
            assertFalse(
                "a book page was rendered with no listing " +
                    "evidence in the harvest",
                transport.requests.any { it.url == hopBookUrl }
            )
            assertTrue(
                "the shell probe should still fail honestly: $outcome",
                outcome is LinkProber.Outcome.Failed
            )
        }

    // ── Item 22: held shell + challenge evidence ──────────────

    private val checkHost = "shellcheck.example"

    /** First answer: the bare shell (what the classifier
     * judges). Every later answer: the booted app, whose own
     * login widget has loaded the check's script — the marker
     * the owner's record showed arriving on a LATER page. */
    private fun checkTransport(markerOnLaterPages: Boolean) =
        FakeTransport { request ->
            val gets = requestCount(request.url)
            val booted = markerOnLaterPages && gets > 1
            val body =
                "<html><head><title>Home</title></head><body>" +
                    "<div id=\"app\"></div>" +
                    (if (booted) {
                        "<script src=\"https://challenges.cloudflare" +
                            ".com/turnstile/v0/api.js\"></script>"
                    } else {
                        ""
                    }) +
                    "</body></html>"
            AppResult.Ok(FakeTransport.response(200, body))
        }

    private val getCounts = mutableMapOf<String, Int>()
    private fun requestCount(url: String): Int {
        val next = (getCounts[url] ?: 0) + 1
        getCounts[url] = next
        return next
    }

    @Test
    fun `held shell with challenge evidence in the session surfaces as a site check`() =
        runBlocking {
            HarvestLog.clearForTests()
            PageShapeLog.clearForTests()
            getCounts.clear()
            val outcome = prober(
                checkTransport(markerOnLaterPages = true)
            ).probe("https://$checkHost/")
            val login = outcome as? LinkProber.Outcome.LoginRequired
                ?: throw AssertionError(
                    "the held shell failure with challenge " +
                        "evidence did not become a check outcome: " +
                        "$outcome"
                )
            assertTrue(
                "the outcome is not the site-check road",
                login.siteCheck
            )
            assertEquals(checkHost, login.host)
        }

    @Test
    fun `held shell without challenge evidence keeps the plain shell failure`() =
        runBlocking {
            HarvestLog.clearForTests()
            PageShapeLog.clearForTests()
            getCounts.clear()
            val outcome = prober(
                checkTransport(markerOnLaterPages = false)
            ).probe("https://$checkHost/")
            val failed = outcome as? LinkProber.Outcome.Failed
                ?: throw AssertionError(
                    "the markerless shell probe did not fail " +
                        "plainly: $outcome"
                )
            assertTrue(
                "the failure is not the shell refusal: " +
                    failed.message,
                failed.message.contains("JavaScript")
            )
        }
}
