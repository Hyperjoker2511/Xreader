package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.net.HttpTransport
import app.xreader.data.net.OkHttpTransport
import app.xreader.extensionapi.SourceBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.2 (FIX-14): the LIVE half of the Lnori proof — the
 * shipped bundled config against the real site, all five
 * legs. TAGGED SMOKE (excluded from the deterministic gate;
 * the recorded twin LnoriRecordedJourneyTest carries it).
 */
@org.junit.experimental.categories.Category(app.xreader.data.LiveSmoke::class)
class LnoriLiveSmokeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        LiveNet.installEgressProxyFromEnv()
        LiveNet.warmUp("https://lnori.com/library")
    }

    @Test
    fun `lnori live five legs`() = runBlocking {
        val config = BundledDefaults.configs().first { it.id == "bundled-lnori" }
        val transport = OkHttpTransport()
        val factory = HostContextFactory(
            ExtensionStore(tmp.newFolder()), testSessionStore(), transport
        )
        val context = factory.contextFor(
            extensionRowId = 1,
            packageId = "bundled.bundled-lnori",
            label = "Lnori",
            permissions = {
                ExtensionPermissions(
                    domains = listOf(
                        "lnori.com", "cdn.lnori.com", "img.lnori.com"
                    )
                )
            }
        )
        val source = DeclarativeSource(config, context)
        // Catalogue.
        val page = withContext(Dispatchers.IO) { source.popular(1) }
        assertTrue(
            "live catalogue rows: ${page.books.size}",
            page.books.size >= 800
        )
        // Search (local filter over the live catalogue).
        val hits = withContext(Dispatchers.IO) { source.search("ishura", 1) }
        assertEquals(listOf("Ishura"), hits.books.map { it.title })
        val ishura = hits.books.first()
        // Book page + volume list.
        val chapters = withContext(Dispatchers.IO) {
            source.chapters(
                SourceBook(id = ishura.id, title = ishura.title)
            )
        }
        assertTrue("live volumes: ${chapters.size}", chapters.size >= 10)
        // Volume text: the whole volume as one chapter
        // (QuickNovel shape, owner-ruled).
        val content = withContext(Dispatchers.IO) {
            source.chapterContent(chapters.first())
        }
        val words = Jsoup.parseBodyFragment(content.html ?: "")
            .text().split(Regex("\\s+")).count { it.isNotBlank() }
        assertTrue("live volume words: $words", words > 50_000)
        assertTrue(
            "the volume's own chapter headings ride the one body",
            (content.html ?: "").contains("chapter-title")
        )
        Unit
    }

    /**
     * v1.5.4 (WS-5, item 23b): the illustration host's
     * reachability, REPORTED and never gated. img.lnori.com has
     * answered HTTP 530 (Cloudflare 1016 — origin DNS failure)
     * since before the 2026-11-03 probe; when it is down, the
     * illustrations leg is dark AT THE SOURCE and the reader's
     * labelled placeholders are the honest state. This probe
     * logs which world we are in — a dead host is a known site
     * outage, NOT a test failure — and the five prose legs above
     * keep their own verdicts either way. By construction this
     * test asserts nothing about the probe's outcome.
     */
    @Test
    fun `img lnori com reachability report - non-fatal`() = runBlocking {
        val transport = OkHttpTransport()
        val attempted = runCatching {
            withContext(Dispatchers.IO) {
                transport.execute(
                    HttpTransport.Request(
                        url = "https://img.lnori.com/14966-02.jpg"
                    )
                )
            }
        }
        val verdict = when {
            attempted.isFailure ->
                "KNOWN SITE OUTAGE — img.lnori.com gave no answer " +
                    "(${attempted.exceptionOrNull()?.message}); " +
                    "illustrations stay labelled placeholders " +
                    "until the host revives"
            else -> when (val result = attempted.getOrThrow()) {
                is AppResult.Ok -> {
                    val code = result.value.code
                    runCatching { result.value.body?.close() }
                    if (code in 200..299) {
                        "REACHABLE — img.lnori.com answered HTTP " +
                            "$code; illustrations can land again"
                    } else {
                        "KNOWN SITE OUTAGE — img.lnori.com " +
                            "answered HTTP $code; illustrations " +
                            "stay labelled placeholders until " +
                            "the host revives"
                    }
                }
                is AppResult.Err ->
                    "KNOWN SITE OUTAGE — img.lnori.com transport " +
                        "error (${result.error}); illustrations " +
                        "stay labelled placeholders until the " +
                        "host revives"
            }
        }
        println("[LnoriLiveSmoke] img.lnori.com reachability: $verdict")
        Unit
    }
}
