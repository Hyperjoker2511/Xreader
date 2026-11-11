package app.xreader.data

import app.xreader.data.extension.BundledDefaults
import app.xreader.data.extension.DeclarativeConfig
import app.xreader.data.extension.DeclarativeSource
import app.xreader.data.extension.ExtensionPermissions
import app.xreader.data.extension.testContext
import app.xreader.ui.sources.StreamingSearch
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.2 (FIX-3, §4 / §10 item 4 — the reinforcement legs):
 * the player-source stall leg, and the SHIPPED REAL SOURCE SET
 * driven through the app's real search pipeline (StreamingSearch
 * + DeclarativeSource, the exact pair GlobalSearch fans out)
 * with the wall-clock bound asserted on that set.
 */
class V152SearchJourneyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Runs [block] on a daemon worker and fails (rather than
     * hanging the suite) if it does not settle in time. */
    private fun <T> runGuarded(seconds: Long, block: suspend () -> T): T {
        var result: Result<T>? = null
        val thread = Thread {
            result = runCatching { runBlocking { block() } }
        }
        thread.isDaemon = true
        thread.start()
        thread.join(seconds * 1_000L)
        assertFalse(
            "the search pipeline did not settle within ${seconds}s",
            thread.isAlive
        )
        return result!!.getOrThrow()
    }

    @Test
    fun `player-source stall leg - the run settles without the stalled source`() {
        val settled = mutableMapOf<String, Result<String>>()
        var footnotes: StreamingSearch.Footnotes<String>? = null
        val startedAt = System.currentTimeMillis()
        runGuarded(30) {
            StreamingSearch.run(
                items = listOf("fast-a", "player-staller", "fast-b"),
                parallelism = 5,
                perItemTimeoutMs = 500,
                search = { item ->
                    if (item == "player-staller") {
                        // The player-source shape: its search
                        // never answers inside its slice (a
                        // Mihon source stuck in its own call).
                        delay(60_000)
                        "never"
                    } else {
                        delay(10)
                        "hits-$item"
                    }
                },
                onResult = { item, result -> settled[item] = result },
                onProgress = { _, _ -> },
                onFootnotes = { footnotes = it }
            )
        }
        val elapsed = System.currentTimeMillis() - startedAt
        assertEquals("hits-fast-a", settled["fast-a"]!!.getOrNull())
        assertEquals("hits-fast-b", settled["fast-b"]!!.getOrNull())
        assertTrue(settled["player-staller"]!!.isFailure)
        assertEquals(listOf("player-staller"), footnotes!!.didntAnswer)
        assertEquals(emptyList<String>(), footnotes!!.notReached)
        assertTrue("elapsed ${elapsed}ms", elapsed < 10_000)
    }

    @Test
    fun `search-scoped requests carry the 15 second call timeout - reading requests do not`() {
        // §4's printed 15 s must be WIRED, not a dead constant:
        // a request issued inside SearchCallScope (the marker
        // GlobalSearch installs on every fan-out leg) leaves
        // ExtensionHttpClient stamped with the 15 s search call
        // timeout; the same client's ordinary request carries
        // none and keeps the reading budget.
        val transport = FakeTransport { FakeTransport.ok("<html>ok</html>") }
        val client = app.xreader.data.extension.ExtensionHttpClient(
            transport = transport,
            sessionStore = app.xreader.data.extension.testSessionStore(),
            sessionKey = "search-stamp-test",
            label = "Stamp Test",
            whitelist = {
                app.xreader.data.extension.ExtensionPermissions(
                    domains = listOf("novels.example")
                )
            },
            rateLimiter = app.xreader.data.extension.RateLimiter(
                minIntervalMs = 0
            )
        )
        runBlocking {
            client.get("https://novels.example/plain")
            kotlinx.coroutines.withContext(
                app.xreader.data.extension.SearchCallScope
            ) {
                client.get("https://novels.example/for-search")
            }
        }
        assertEquals(2, transport.requests.size)
        assertEquals(
            "reading request must not carry the search timeout",
            null, transport.requests[0].callTimeoutSeconds
        )
        assertEquals(
            "search request must carry the printed 15 s",
            app.xreader.data.net.OkHttpTransport.SEARCH_CALL_TIMEOUT_SECONDS,
            transport.requests[1].callTimeoutSeconds
        )
        assertEquals(15L,
            app.xreader.data.net.OkHttpTransport.SEARCH_CALL_TIMEOUT_SECONDS)
    }

    @Test
    fun `shipped real source set through the real pipeline inside the bound`() {
        val configs = BundledDefaults.configs()
        assertTrue("shipped set size ${configs.size}", configs.size >= 20)
        // Three real sources with real search specs stall; every
        // other shipped source answers instantly (an empty page
        // the source's own parser settles on, or its honest
        // "doesn't support search" failure — both are answers).
        val stallIds = configs
            .filter { it.search != null }
            .map { it.id }
            .filter {
                it in setOf(
                    "bundled-novelbuddy", "bundled-royalroad",
                    "bundled-scribblehub"
                )
            }
            .toSet()
        assertTrue("stall fixtures chosen: $stallIds", stallIds.isNotEmpty())

        // The per-item slice must sit BELOW the search call
        // timeout the fetch stack stamps (15 s): a staller is
        // classified "didn't answer" when the pipeline's own
        // per-item timeout cuts its leg, and a leg the call
        // timeout kills first has answered (with a failure).
        // 4 s exercises the classification; the WALL-CLOCK
        // assertion below is the spec's bound for this set —
        // min(120 s, ceil(N/5) × 20 s) — not the composed
        // 24 s, which a loaded test JVM cannot hold.
        val perItemMs = 4_000L
        val settled = mutableMapOf<String, Result<Any>>()
        var footnotes: StreamingSearch.Footnotes<DeclarativeConfig>? = null
        val startedAt = System.currentTimeMillis()
        runGuarded(150) {
            StreamingSearch.run(
                items = configs,
                parallelism = 5,
                perItemTimeoutMs = perItemMs,
                search = { config ->
                    val transport = FakeTransport { request ->
                        if (config.id in stallIds) {
                            // A hanging endpoint, modelled at the
                            // TRANSPORT CONTRACT: the call lives
                            // until the request's own call timeout
                            // kills it — OkHttpTransport honours
                            // callTimeoutSeconds exactly this way —
                            // then fails. (A raw cancellable delay
                            // is the player-stall leg's shape, not
                            // this one's: DeclarativeSource's
                            // blocking bridges cannot be cancelled
                            // mid-call, so the call timeout is the
                            // production bound mechanism here.)
                            val killMs =
                                (request.callTimeoutSeconds ?: 60L) *
                                    1_000L
                            delay(killMs)
                            app.xreader.core.error.AppResult.Err(
                                app.xreader.core.error.AppError
                                    .ProviderDown(
                                        serviceId = config.id,
                                        detail = "call timeout"
                                    )
                            )
                        } else {
                            FakeTransport.ok(
                                "<html><head><title>t</title></head>" +
                                    "<body></body></html>"
                            )
                        }
                    }
                    val hosts = mutableListOf<String>()
                    runCatching { URI(config.baseUrl).host }
                        .getOrNull()?.let { hosts += it }
                    hosts += config.extraDomains
                    val context = testContext(
                        transport,
                        tmp.newFolder(),
                        ExtensionPermissions(domains = hosts)
                    )
                    val source = DeclarativeSource(config, context)
                    // Production parity: GlobalSearch installs
                    // the SearchCallScope marker on every leg, and
                    // the fetch stack stamps the 15 s search call
                    // timeout from it.
                    withContext(
                        Dispatchers.IO +
                            app.xreader.data.extension.SearchCallScope
                    ) {
                        source.search("moon", 1)
                    }
                },
                onResult = { config, result ->
                    @Suppress("UNCHECKED_CAST")
                    settled[config.id] = result as Result<Any>
                },
                onProgress = { _, _ -> },
                onFootnotes = { footnotes = it }
            )
        }
        val elapsed = System.currentTimeMillis() - startedAt

        // Every shipped source settled — answered or honestly
        // failed — and exactly the stallers are "didn't answer".
        assertEquals(configs.map { it.id }.toSet(), settled.keys)
        assertEquals(
            stallIds,
            footnotes!!.didntAnswer.map { it.id }.toSet()
        )
        assertEquals(emptyList<DeclarativeConfig>(), footnotes!!.notReached)

        // The wall-clock bound the spec prints for the real
        // set: min(120 s, ceil(N/5) × 20 s) — 120 s at this N,
        // in the production form asserted just below.
        assertTrue(
            "wall clock ${elapsed}ms exceeded the spec bound 120000ms",
            elapsed <= 120_000L
        )
        assertEquals(
            120_000L,
            StreamingSearch.aggregateBoundMs(
                configs.size, parallelism = 5,
                perItemTimeoutMs = StreamingSearch.DEFAULT_PER_ITEM_TIMEOUT_MS
            )
        )
        assertEquals(
            StreamingSearch.AGGREGATE_DEADLINE_MS,
            StreamingSearch.aggregateBoundMs(
                configs.size, parallelism = 5,
                perItemTimeoutMs = StreamingSearch.DEFAULT_PER_ITEM_TIMEOUT_MS
            )
        )
    }
}
