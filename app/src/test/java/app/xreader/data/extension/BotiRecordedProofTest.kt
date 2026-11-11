package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeExtensionRepoDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.2 loop 2 (DELTA 3): the DETERMINISTIC twin of
 * BotiLiveProofTest — the identical journey (harvest deposit →
 * ApiRecipeSynthesizer → real LinkProber probe of the root
 * paste → saveLinkSource persistence) driven against the
 * RECORDED live response shapes (captured from the real
 * story-wave API: the homepage config payload, the search
 * page, the genres table, and the consistent book 10291 chain
 * — book, chapter list, chapter text). The replay assertions
 * also pin the harvested request shape: the derived road's
 * calls stay on the harvested base and carry the harvested
 * lang / site-domain headers. This class carries the
 * deterministic gate for §10 item 1(c); the live class is
 * tagged smoke.
 * Site downtime can neither fail nor pass this gate.
 */
class BotiRecordedProofTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val apiBase =
        "https://api.mystorywave.com/story-wave-backend/api/v1"
    private val webHost = "botitranslation.com"
    private val apiHost = "api.mystorywave.com"
    private val siteHeaders = mapOf(
        "lang" to "en_US",
        "site-domain" to webHost
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) {
            "missing test resource $name"
        }.readBytes().toString(Charsets.UTF_8)

    /** The recorded search page with its envelope re-shaped
     * the way the real API answers a different query: the list
     * filtered by title word (the API's own semantics), or
     * emptied for the filter-proof's nonsense control. Shape
     * stays the recorded envelope; only the list contents —
     * and the total — move, exactly as the live API's do. */
    private fun searchPageFor(keyWord: String?): String {
        val root = json.parseToJsonElement(
            resource("boti/search.json")
        ) as JsonObject
        val data = root["data"] as JsonObject
        val list = data["list"] as kotlinx.serialization.json.JsonArray
        val filtered = when {
            keyWord == null -> list
            keyWord == "zxqwvkj" -> kotlinx.serialization.json.JsonArray(
                emptyList()
            )
            else -> kotlinx.serialization.json.JsonArray(
                list.filter { element ->
                    val title = (element as JsonObject)["title"]
                        ?.let { (it as JsonPrimitive).contentOrNull }
                        .orEmpty()
                    title.contains(keyWord, ignoreCase = true)
                }
            )
        }
        val newData = JsonObject(
            data + mapOf(
                "list" to filtered,
                "totalCount" to JsonPrimitive(filtered.size)
            )
        )
        return JsonObject(root + mapOf("data" to newData)).toString()
    }

    private fun keyWordOf(url: String): String? =
        url.substringAfter("keyWord=", "")
            .substringBefore("&")
            .takeIf { it.isNotEmpty() }

    /** Recorded-shape routing for the derived road's calls
     * during the probe's test-drive; the web root answers a
     * minimal shell (the harvest road is what proves); anything
     * else is a 404 so an unexpected fetch fails loudly. */
    private fun recordedTransport() = FakeTransport { request ->
        val url = request.url
        val body = when {
            url.contains("/custom/orgCustomConfigs") ->
                resource("boti/orgCustomConfigs.json")
            url.contains("/content/books/search") ->
                searchPageFor(keyWordOf(url))
            url.contains("/content/books/genres") ->
                resource("boti/genres.json")
            url.contains("/content/chapters/page") ->
                resource("boti/chapters10291.json")
            url.contains("/content/chapters/") ->
                resource("boti/text10291.json")
            url.contains("/content/books/") ->
                resource("boti/book10291.json")
            url == "https://$webHost/" || url == "https://$webHost" ->
                "<html><head><title>BotiTranslation</title></head>" +
                    "<body><div id=\"app\"></div></body></html>"
            else -> null
        }
        if (body != null) {
            AppResult.Ok(FakeTransport.response(200, body))
        } else {
            AppResult.Ok(FakeTransport.response(404, "no recorded shape"))
        }
    }

    private fun firstRecordId(body: String): String {
        val root = json.parseToJsonElement(body) as JsonObject
        val data = root["data"] as JsonObject
        val list = data["list"] as kotlinx.serialization.json.JsonArray
        val first = list.first() as JsonObject
        return (first["id"] as JsonPrimitive).contentOrNull
            ?: throw AssertionError("the recorded record carries no id")
    }

    private fun firstChapterId(body: String): String = firstRecordId(body)

    @Test
    fun `boti root paste derives the api road from recorded shapes and saves as BotiTranslation`() =
        runBlocking {
            val transport = recordedTransport()

            // ── the site's own calls, from the recordings ───
            val searchBody = resource("boti/search.json")
            val bookId = firstRecordId(searchBody)
            val chaptersBody = resource("boti/chapters10291.json")
            val chapterId = firstChapterId(chaptersBody)
            val bookBody = resource("boti/book10291.json")
            val chapterBody = resource("boti/text10291.json")
            val homeBody = resource("boti/orgCustomConfigs.json")
            val genresBody = resource("boti/genres.json")
            // The genres leg of the harvested shape set (round-15):
            // code 0 with a non-empty genre table, recorded live
            // from v1/content/books/genres.
            val genresRoot = json.parseToJsonElement(genresBody) as JsonObject
            assertEquals(
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

            val deposited = HarvestLog.forSession(sessionKey)
            val directSynthesis = ApiRecipeSynthesizer.synthesize(
                deposited,
                webBaseUrl = "https://$webHost/",
                siteName = "BotiTranslation",
                bookUrlHint = "https://$webHost/"
            )
            assertTrue(
                "the deposited recorded calls did not synthesise: " +
                    deposited.joinToString(" | ") {
                        "${it.url} [${it.bodySample.length} chars]"
                    },
                directSynthesis != null
            )

            // ── the real probe against the root paste ───────
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
                        "derived road: $outcome"
                )
            val config = ready.draft.config

            val popularUrl = config.popular?.url.orEmpty()
            assertTrue(
                "popular rides the API host: $popularUrl",
                popularUrl.contains(apiHost) ||
                    config.extraDomains.contains(apiHost)
            )
            assertTrue(
                "chapter count ${ready.draft.testDrive.chapterCount}",
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

            // ── the derived road replays the HARVESTED shape ─
            // (round-15): every API call the probe's test-drive
            // made went to the harvested base (…/api/v1/) and
            // carried the harvested headers — lang: en_US and
            // site-domain: <web hostname> — exactly as the site's
            // own interceptor sends them. A derived road that
            // dropped the headers would be a different road.
            val apiRequests = transport.requests
                .filter { it.url.contains(apiHost) }
            assertTrue(
                "the test-drive made no API requests",
                apiRequests.isNotEmpty()
            )
            assertTrue(
                "API requests left the harvested base: " +
                    apiRequests.map { it.url },
                apiRequests.all {
                    it.url.startsWith("$apiBase/")
                }
            )
            for (request in apiRequests) {
                val headers = request.headers.entries.associate {
                    it.key.lowercase() to it.value
                }
                assertEquals(
                    "lang header on ${request.url}",
                    "en_US", headers["lang"]
                )
                assertEquals(
                    "site-domain header on ${request.url}",
                    webHost, headers["site-domain"]
                )
            }
            // The five legs all travelled over that road:
            // catalogue (orgCustomConfigs), search, book,
            // chapter list, chapter text.
            val travelled = apiRequests.map { it.url }
            assertTrue(
                "catalogue leg missing: $travelled",
                travelled.any { it.contains("/custom/orgCustomConfigs") }
            )
            assertTrue(
                "search leg missing: $travelled",
                travelled.any { it.contains("/content/books/search") }
            )
            assertTrue(
                "book leg missing: $travelled",
                travelled.any {
                    it.contains("/content/books/") &&
                        !it.contains("/content/books/search") &&
                        !it.contains("/content/books/genres")
                }
            )
            assertTrue(
                "chapter-list leg missing: $travelled",
                travelled.any { it.contains("/content/chapters/page") }
            )
            assertTrue(
                "chapter-text leg missing: $travelled",
                travelled.any {
                    it.contains("/content/chapters/") &&
                        !it.contains("/content/chapters/page")
                }
            )

            // ── persistence, exactly as the live proof ──────
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
            assertEquals("BotiTranslation", config.name)
            val saved = manager.saveLinkSource(config, webHost)
            val row = (saved as? AppResult.Ok)?.value
                ?: throw AssertionError("saveLinkSource failed: $saved")
            assertEquals("BotiTranslation", row.displayName)
            val storedRules = store.readText(
                store.definitionFile(row.packageId)
            ) ?: throw AssertionError(
                "no stored definition for ${row.packageId}"
            )
            assertTrue(
                "stored rules ride the API host",
                storedRules.contains(apiHost)
            )
        }
}
