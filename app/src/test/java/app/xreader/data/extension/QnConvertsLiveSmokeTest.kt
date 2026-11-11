package app.xreader.data.extension

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
 * v1.5.2 (FIX-16): the LIVE half of the QuickNovel-convert
 * proofs — the shipped bundled configs against the real
 * sites, all five legs per provider. TAGGED SMOKE (excluded
 * from the deterministic gate; the recorded twin
 * QnConvertsRecordedJourneyTest carries the gate). Counts
 * are asserted loosely (sites drift); the recorded set pins
 * the exact numbers.
 */
@org.junit.experimental.categories.Category(app.xreader.data.LiveSmoke::class)
class QnConvertsLiveSmokeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        LiveNet.installEgressProxyFromEnv()
        LiveNet.warmUp("https://www.scrollerspub.com/")
    }

    private fun liveSource(
        configId: String,
        domains: List<String>
    ): DeclarativeSource {
        val config = BundledDefaults.configs().first { it.id == configId }
        val factory = HostContextFactory(
            ExtensionStore(tmp.newFolder()), testSessionStore(),
            OkHttpTransport()
        )
        val context = factory.contextFor(
            extensionRowId = 1,
            packageId = "bundled.$configId",
            label = config.name,
            permissions = { ExtensionPermissions(domains = domains) }
        )
        return DeclarativeSource(config, context)
    }

    private fun wordsOf(html: String): Int =
        Jsoup.parseBodyFragment(html).text()
            .split(Regex("\\s+")).count { it.isNotBlank() }

    @Test
    fun `scrollerspub live five legs`() = runBlocking {
        val source = liveSource(
            "bundled-scrollerspub", listOf("www.scrollerspub.com")
        )
        val page = withContext(Dispatchers.IO) { source.popular(1) }
        assertEquals(24, page.books.size)
        val hits = withContext(Dispatchers.IO) { source.search("lord", 1) }
        assertTrue(hits.books.isNotEmpty())
        val book = page.books.first()
        val chapters = withContext(Dispatchers.IO) { source.chapters(book) }
        assertTrue(
            "live chapters: ${chapters.size}", chapters.size > 100
        )
        val text = withContext(Dispatchers.IO) {
            source.chapterContent(chapters.first())
        }
        assertTrue("live text words: ${wordsOf(text.html)}",
            wordsOf(text.html) > 500)
        Unit
    }

    @Test
    fun `readhive live five legs`() = runBlocking {
        val source = liveSource("bundled-readhive", listOf("readhive.org"))
        val page = withContext(Dispatchers.IO) { source.popular(1) }
        assertTrue(
            "live catalogue: ${page.books.size}", page.books.size >= 30
        )
        val hits = withContext(Dispatchers.IO) {
            source.search("reincarnation", 1)
        }
        assertTrue(hits.books.isNotEmpty())
        val book = SourceBook(
            id = "https://readhive.org/series/110/",
            title = "I need sponsorship"
        )
        val chapters = withContext(Dispatchers.IO) { source.chapters(book) }
        assertTrue(
            "live chapters: ${chapters.size}", chapters.size >= 20
        )
        val text = withContext(Dispatchers.IO) {
            source.chapterContent(chapters.first())
        }
        assertTrue("live text words: ${wordsOf(text.html)}",
            wordsOf(text.html) > 400)
        Unit
    }

    @Test
    fun `wuxiaclick live five legs`() = runBlocking {
        val source = liveSource("bundled-wuxiaclick", listOf("wuxia.click"))
        val page = withContext(Dispatchers.IO) { source.popular(1) }
        assertEquals(12, page.books.size)
        val hits = withContext(Dispatchers.IO) { source.search("renegade", 1) }
        assertEquals(1, hits.books.size)
        val chapters = withContext(Dispatchers.IO) {
            source.chapters(hits.books.first())
        }
        assertTrue(
            "live chapters (full ranged walk): ${chapters.size}",
            chapters.size >= 2000
        )
        val text = withContext(Dispatchers.IO) {
            source.chapterContent(chapters.first())
        }
        assertTrue("live text words: ${wordsOf(text.html)}",
            wordsOf(text.html) > 500)
        Unit
    }

    @Test
    fun `faqwiki live five legs`() = runBlocking {
        val source = liveSource("bundled-faqwiki", listOf("faqwiki.xyz"))
        val page = withContext(Dispatchers.IO) { source.popular(1) }
        assertTrue(
            "live catalogue: ${page.books.size}", page.books.size >= 40
        )
        val hits = withContext(Dispatchers.IO) { source.search("fist", 1) }
        assertTrue(
            hits.books.any { it.title == "Reincarnation of the Fist King" }
        )
        val book = SourceBook(
            id = "https://faqwiki.xyz/reincarnation-of-the-fist-king/",
            title = "Reincarnation of the Fist King"
        )
        val chapters = withContext(Dispatchers.IO) { source.chapters(book) }
        assertTrue(
            "live chapters: ${chapters.size}", chapters.size >= 190
        )
        val text = withContext(Dispatchers.IO) {
            source.chapterContent(chapters.first())
        }
        assertTrue("live text words: ${wordsOf(text.html)}",
            wordsOf(text.html) > 400)
        Unit
    }
}
