package app.xreader.data.extension

import app.xreader.data.net.OkHttpTransport
import app.xreader.ui.sources.StreamingSearch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.2 loop 2 (DELTA 3): the LIVE half of item 4's real-set
 * leg — the shipped real source set driven through the app's
 * real search envelope (StreamingSearch at production
 * settings) against the live sites. TAGGED SMOKE: reported
 * separately from the deterministic gate (whose real-set leg
 * is V152SearchJourneyTest over recorded-shape sources).
 *
 * The assertions are the envelope's own: every leg settles
 * inside the bound min(120 s, ceil(N/5) × 20 s) — a leg that
 * answers, errors or times out all count as settled; a leg
 * that never settles, or a wall clock past the bound, fails.
 * Per-site success is reported (printed), never asserted —
 * a down site is smoke information, not a wiring verdict.
 */
@org.junit.experimental.categories.Category(app.xreader.data.LiveSmoke::class)
class LiveRealSetSearchSmokeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        LiveNet.installEgressProxyFromEnv()
        LiveNet.warmUp("https://fenrirealm.com/")
    }

    @Test
    fun `live real set settles inside the envelope bound`() = runBlocking {
        val configs = BundledDefaults.configs()
            .filter { it.search != null }
        val n = configs.size
        val boundMs = minOf(
            120_000L,
            ((n + 4) / 5) * 20_000L
        )
        val transport = OkHttpTransport()
        val sessionStore = testSessionStore()
        val factory = HostContextFactory(
            ExtensionStore(tmp.newFolder()), sessionStore, transport
        )
        val settled = LinkedHashMap<String, Result<List<String>>>()
        var footnotes: StreamingSearch.Footnotes<DeclarativeConfig>? = null
        val startedAt = System.currentTimeMillis()
        StreamingSearch.run(
            items = configs,
            search = { config ->
                val host = java.net.URI(config.baseUrl).host
                    ?: config.baseUrl
                val context = factory.contextFor(
                    extensionRowId = 1,
                    packageId = "smoke.${config.id}",
                    label = config.name,
                    permissions = {
                        ExtensionPermissions(
                            domains = listOf(host) + config.extraDomains
                        )
                    }
                )
                val source = DeclarativeSource(config, context)
                source.search("the", 1).books.map { it.title }
            },
            onResult = { config, result -> settled[config.id] = result },
            onProgress = { _, _ -> },
            onFootnotes = { footnotes = it }
        )
        val elapsed = System.currentTimeMillis() - startedAt
        val answered = settled.values.count { it.isSuccess }
        println(
            "LIVE REAL-SET SMOKE: N=$n bound=${boundMs}ms " +
                "elapsed=${elapsed}ms answered=$answered " +
                "didntAnswer=${footnotes?.didntAnswer?.size} " +
                "notReached=${footnotes?.notReached?.size}"
        )
        assertEquals(
            "every leg must settle (settled ${settled.size} of $n)",
            n, settled.size
        )
        assertTrue(
            "wall clock ${elapsed}ms exceeded the bound ${boundMs}ms " +
                "(+30 s harness slack)",
            elapsed <= boundMs + 30_000L
        )
    }
}
