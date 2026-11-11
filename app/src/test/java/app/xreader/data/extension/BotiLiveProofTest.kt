package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeExtensionRepoDao
import app.xreader.data.net.HttpTransport
import app.xreader.data.net.OkHttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.2 (FIX-1c, §2 / §10 item 1c): BOTI, live, end to end over
 * the DERIVED road. The site's own calls are fetched live from
 * the real API (the story-wave backend the re-scan derived:
 * api.mystorywave.com/story-wave-backend/api/v1, `lang` +
 * `site-domain` headers), deposited in the HarvestLog exactly
 * as the browser-view scout deposits them, and the REAL probe
 * runs against the live root paste. The recipe it derives is
 * then the source that's saved — named "BotiTranslation"
 * (the SeedCatalog name), with the API host asserted in its
 * rules. No part of the road is a fixture: every response in
 * this test came off the live site during the run.
 *
 * v1.5.2 loop 2 (DELTA 3): TAGGED live smoke — excluded from
 * the deterministic gate (the recorded-shape twin,
 * BotiRecordedProofTest, carries the gate); run as the smoke
 * set, reported separately.
 */
@org.junit.experimental.categories.Category(app.xreader.data.LiveSmoke::class)
class BotiLiveProofTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @org.junit.Before
    fun setUp() {
        LiveNet.installEgressProxyFromEnv()
        // Absorb the sandbox proxy's cold-path stall (see
        // LiveNet.warmUp) before the proof's timed legs run.
        LiveNet.warmUp(
            "https://api.mystorywave.com/story-wave-backend/api/v1" +
                "/content/books/search?keyWord=magical&pageNumber=1&pageSize=1",
            "https://botitranslation.com/"
        )
    }

    private val apiBase =
        "https://api.mystorywave.com/story-wave-backend/api/v1"
    private val webHost = "botitranslation.com"
    private val apiHost = "api.mystorywave.com"
    private val siteHeaders = mapOf(
        "lang" to "en_US",
        "site-domain" to webHost
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** One live GET over the production transport. A retryable
     * timeout (the sandbox proxy's intermittent stalls — see
     * LiveNet.warmUp) is retried up to three attempts total:
     * the assertion below still demands a real 200 with the
     * live payload, so a genuinely dead endpoint still fails;
     * only the environment's transient stall is absorbed. */
    private suspend fun liveGet(
        transport: OkHttpTransport,
        url: String
    ): String {
        var result = transport.execute(
            HttpTransport.Request(url = url, headers = siteHeaders)
        )
        var attempts = 1
        while (attempts < 3) {
            val err = (result as? AppResult.Err)?.error
            if (err !is app.xreader.core.error.AppError.ProviderDown ||
                !err.retryable
            ) {
                break
            }
            attempts++
            kotlinx.coroutines.delay(3_000L)
            result = transport.execute(
                HttpTransport.Request(url = url, headers = siteHeaders)
            )
        }
        val response = (result as? AppResult.Ok)?.value
            ?: throw AssertionError("live GET failed for $url: $result")
        val body = withContext(Dispatchers.IO) {
            response.body?.readBytes()?.toString(Charsets.UTF_8) ?: ""
        }
        assertEquals(
            "live GET $url answered ${response.code}",
            200, response.code
        )
        return body
    }

    private fun firstRecordId(body: String): String {
        val root = json.parseToJsonElement(body) as JsonObject
        val data = root["data"] as JsonObject
        val list = data["list"] as kotlinx.serialization.json.JsonArray
        val first = list.first() as JsonObject
        return (first["id"] as JsonPrimitive).contentOrNull
            ?: throw AssertionError("the live record carries no id")
    }

    private fun firstChapterId(body: String): String {
        val root = json.parseToJsonElement(body) as JsonObject
        val data = root["data"] as JsonObject
        val list = data["list"] as kotlinx.serialization.json.JsonArray
        val first = list.first() as JsonObject
        return (first["id"] as JsonPrimitive).contentOrNull
            ?: throw AssertionError("the live chapter record carries no id")
    }

    @Test
    fun `boti root paste derives the api road live and saves as BotiTranslation`() =
        runBlocking {
            val transport = OkHttpTransport()

            // ── the site's own calls, fetched live ──────────
            val searchBody = liveGet(
                transport,
                "$apiBase/content/books/search" +
                    "?keyWord=magical&pageNumber=1&pageSize=20"
            )
            val bookId = firstRecordId(searchBody)
            val chaptersBody = liveGet(
                transport,
                "$apiBase/content/chapters/page" +
                    "?bookId=$bookId&pageNumber=1&pageSize=50"
            )
            val chapterId = firstChapterId(chaptersBody)
            val bookBody = liveGet(
                transport, "$apiBase/content/books/$bookId"
            )
            val chapterBody = liveGet(
                transport, "$apiBase/content/chapters/$chapterId"
            )
            val homeBody = liveGet(
                transport,
                "$apiBase/custom/orgCustomConfigs?pageName=homepage"
            )
            // The genres leg (round-15): the site's own genre
            // table over the same harvested shape — code 0 with
            // a non-empty table, or this leg has not travelled.
            val genresBody = liveGet(
                transport, "$apiBase/content/books/genres"
            )
            val genresRoot = json.parseToJsonElement(genresBody)
                as JsonObject
            assertEquals(
                "genres leg answered code " +
                    (genresRoot["code"] as? JsonPrimitive)?.contentOrNull,
                0,
                (genresRoot["code"] as JsonPrimitive).contentOrNull?.toInt()
            )
            assertTrue(
                "genres table is empty",
                (genresRoot["data"] as kotlinx.serialization.json.JsonArray)
                    .isNotEmpty()
            )

            // ── deposited exactly as the scout deposits ─────
            val sessionKey =
                ExtensionHttpClient.linkProbeSessionKey(webHost)
            HarvestLog.clearForTests()
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
                homeBody
            )
            record(
                "$apiBase/content/books/search" +
                    "?keyWord=magical&pageNumber=1&pageSize=20",
                searchBody
            )
            record("$apiBase/content/books/genres", genresBody)
            record("$apiBase/content/books/$bookId", bookBody)
            record(
                "$apiBase/content/chapters/page" +
                    "?bookId=$bookId&pageNumber=1&pageSize=50",
                chaptersBody
            )
            record("$apiBase/content/chapters/$chapterId", chapterBody)

            // The deposit must synthesise — asserted directly so
            // a synthesis failure is diagnosed here, not inferred
            // from the probe's downstream behaviour.
            val deposited = HarvestLog.forSession(sessionKey)
            val directSynthesis = ApiRecipeSynthesizer.synthesize(
                deposited,
                webBaseUrl = "https://$webHost/",
                siteName = "BotiTranslation",
                bookUrlHint = "https://$webHost/"
            )
            assertTrue(
                "the deposited live calls did not synthesise: " +
                    deposited.joinToString(" | ") {
                        "${it.url} [${it.bodySample.length} chars]"
                    },
                directSynthesis != null
            )

            // ── the real probe against the live root paste ──
            val factory = HostContextFactory(
                ExtensionStore(tmp.newFolder()),
                testSessionStore(),
                transport
            )
            val prober = LinkProber(factory, testSessionStore())
            val outcome = prober.probe("https://$webHost/")
            val ready = outcome as? LinkProber.Outcome.Ready
                ?: throw AssertionError(
                    "the BOTI probe did not reach Ready over the " +
                        "derived road: $outcome" +
                        " — post-probe harvest: " +
                        HarvestLog.forSession(sessionKey)
                            .joinToString(" | ") {
                                "${it.url} [json=${it.isJson}]"
                            } +
                        " — re-synthesis: " +
                        ApiRecipeSynthesizer.synthesize(
                            HarvestLog.forSession(sessionKey),
                            webBaseUrl = "https://$webHost/",
                            siteName = "BotiTranslation",
                            bookUrlHint = "https://$webHost/"
                        )
                )
            val config = ready.draft.config

            // The derived road IS the API road.
            val popularUrl = config.popular?.url.orEmpty()
            assertTrue(
                "popular rides the API host: $popularUrl",
                popularUrl.contains(apiHost) ||
                    config.extraDomains.contains(apiHost)
            )
            assertTrue(
                "the API host is a declared domain",
                config.extraDomains.contains(apiHost) ||
                    popularUrl.contains(apiHost)
            )
            // The test-drive proved the legs live: a book, its
            // chapters, and real chapter text over the API.
            assertTrue(
                "chapter count ${ready.draft.testDrive.chapterCount}" +
                    " — derived chapter spec: ${config.chapter}" +
                    " — stages: " + ready.stageLines.joinToString(
                        " | "
                    ) { it.render() },
                ready.draft.testDrive.chapterCount > 0
            )
            assertTrue(
                "chapter text ${ready.draft.testDrive.chapterTextLength}",
                ready.draft.testDrive.chapterTextLength > 100
            )
            assertTrue(
                "search verified: ${ready.draft.testDrive.searchProof}",
                ready.draft.testDrive.searchVerified
            )

            // ── persistence: the source saves under the seed
            // name with the API host in its stored rules ─────
            val repoDao = FakeExtensionRepoDao()
            val extDao = FakeInstalledExtensionDao()
            val store = ExtensionStore(tmp.newFolder())
            val sessionStore = testSessionStore()
            val service = ExtensionService(
                extDao, store,
                HostContextFactory(store, sessionStore, transport),
                FakeDexLoader()
            ) { FakeJsRuntime() }
            val manager = RepoManager(
                repoDao, extDao, store, transport, service, sessionStore
            )
            // Saved exactly as the probe drafted it — the
            // BotiTranslation name must come from the probe's
            // own identity precedence (seed over bare host),
            // not from the test.
            assertEquals("BotiTranslation", config.name)
            val saved = manager.saveLinkSource(config, webHost)
            val row = (saved as? AppResult.Ok)?.value
                ?: throw AssertionError("saveLinkSource failed: $saved")
            assertEquals("BotiTranslation", row.displayName)
            val storedRules = store.readText(
                store.definitionFile(row.packageId)
            ) ?: throw AssertionError("no stored definition for ${row.packageId}")
            assertTrue(
                "stored rules ride the API host — popular=" +
                    "${config.popular?.url} extra=${config.extraDomains}" +
                    " stored[0..600]=${storedRules.take(600)}",
                storedRules.contains(apiHost)
            )
        }
}
