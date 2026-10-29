package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.extensionapi.SourceChapter
import app.xreader.extensionapi.SourceException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.0.5 Stage 1.5 — the AJAX chapter-text mode, pinned against
 * the verified novelight shape (fast lane + fixture endpoint):
 * id derivation from chapter URLs, the required header riding
 * through the app's fetcher, the JSON content field read as the
 * chapter, empty content reported as the typed paid/locked
 * failure, and the config codec's backward compatibility (an
 * old config without the block parses with the mode absent; a
 * config with it round-trips intact).
 */
class V105AjaxTextTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun laneConfig(): DeclarativeConfig {
        val entry = KnownSites.entryFor(NovelightSite.HOST)!!
        return KnownSites.fullConfig(
            entry, "link-test", "Novelight", NovelightSite.BASE
        )!!
    }

    private fun source(
        config: DeclarativeConfig,
        transport: FakeTransport
    ): DeclarativeSource {
        val context = testContext(
            transport, tmp.newFolder(),
            ExtensionPermissions(domains = listOf(NovelightSite.HOST))
        )
        return DeclarativeSource(config, context)
    }

    private fun chapter(id: String) = SourceChapter(id = id, name = "x", number = null)

    private fun plainText(source: DeclarativeSource, chapterId: String): String {
        val content = source.contentOf(chapterId) ?: error("no normalised content")
        return content.blocks.joinToString("\n") { block ->
            when (block) {
                is app.xreader.domain.content.ChapterBlock.Paragraph -> block.text
                is app.xreader.domain.content.ChapterBlock.Heading -> block.text
                else -> ""
            }
        }
    }

    // ── URL derivation ──────────────────────────────────────────

    @Test
    fun `chapter id is the trailing numeric segment of the chapter url`() {
        val ajax = ChapterTextAjax(
            urlTemplate = "https://novelight.net/book/ajax/read-chapter/{id}"
        )
        assertEquals("346043", ajax.chapterId("https://novelight.net/book/chapter/346043"))
        assertEquals("346043", ajax.chapterId("https://novelight.net/book/chapter/346043/"))
        assertEquals(
            "https://novelight.net/book/ajax/read-chapter/346043",
            ajax.endpointFor("https://novelight.net/book/chapter/346043")
        )
        // No numeric tail -> the mode simply doesn't apply.
        assertNull(ajax.chapterId("https://novelight.net/book/chapter/finale-5"))
        assertNull(ajax.endpointFor("https://novelight.net/book/chapter/finale-5"))
        assertNull(ajax.chapterId("https://novelfull.com/martial-peak/chapter-1"))
        assertNull(ajax.chapterId("https://novelight.net/book/lord-of-talents"))
    }

    // ── The read itself ─────────────────────────────────────────

    @Test
    fun `ajax read sends the declared header and returns the prose`() = runBlocking {
        val transport = NovelightSite.transport()
        val source = source(laneConfig(), transport)
        val url = "${NovelightSite.BASE}/book/chapter/101"
        val result = source.chapterContent(chapter(url))
        assertTrue(result.html.contains("lord of talents"))
        val text = plainText(source, url)
        assertTrue(text.contains("counted his quiet coins"))
        // The endpoint was hit, with the XMLHttpRequest header
        // the site demands (it 403s without it).
        val ajaxRequests = transport.requests.filter {
            it.url == "${NovelightSite.AJAX_PREFIX}101"
        }
        assertEquals(1, ajaxRequests.size)
        assertTrue(ajaxRequests.single().headers.entries.any {
            it.key.equals("X-Requested-With", ignoreCase = true) &&
                it.value == "XMLHttpRequest"
        })
        // The shell chapter page itself was never fetched.
        assertFalse(transport.requests.any { it.url == url })
    }

    @Test
    fun `without the ajax header the endpoints refusal surfaces honestly`() = runBlocking {
        val config = laneConfig()
        val bare = config.copy(
            chapter = config.chapter.copy(
                ajax = config.chapter.ajax!!.copy(headers = emptyMap())
            )
        )
        val source = source(bare, NovelightSite.transport())
        val thrown = runCatching {
            source.chapterContent(chapter("${NovelightSite.BASE}/book/chapter/101"))
        }.exceptionOrNull()
        assertTrue(thrown is SourceException)
        assertTrue(thrown!!.message!!.contains("403"))
    }

    @Test
    fun `locked chapters surface the typed empty-text failure`() = runBlocking {
        val source = source(laneConfig(), NovelightSite.transport())
        for (locked in listOf("104", "105")) {
            val thrown = runCatching {
                source.chapterContent(
                    chapter("${NovelightSite.BASE}/book/chapter/$locked")
                )
            }.exceptionOrNull()
            assertTrue("chapter $locked: $thrown", thrown is SourceException)
            assertEquals(
                DeclarativeSource.AJAX_TEXT_EMPTY_MESSAGE, thrown!!.message
            )
            assertTrue(thrown.message!!.contains("paid or locked"))
        }
    }

    @Test
    fun `a chapter url without a numeric tail bypasses the ajax mode`() = runBlocking {
        val transport = NovelightSite.transport()
        val source = source(laneConfig(), transport)
        val thrown = runCatching {
            source.chapterContent(
                chapter("${NovelightSite.BASE}/book/chapter/finale-x")
            )
        }.exceptionOrNull()
        // The static path ran instead (and 404'd on the fixture,
        // exactly as it would have before the mode existed).
        assertTrue(thrown is SourceException)
        assertTrue(thrown!!.message!!.contains("404"))
        assertFalse(transport.requests.any { it.url.startsWith(NovelightSite.AJAX_PREFIX) })
    }

    // ── Drive-level honesty ─────────────────────────────────────

    @Test
    fun `drive reports paid or locked when every ajax sample is empty`() = runBlocking {
        val base = NovelightSite.transport()
        val transport = FakeTransport { request ->
            if (request.url.startsWith(NovelightSite.AJAX_PREFIX)) {
                FakeTransport.ok(NovelightSite.ajaxChapterJson(prose = false))
            } else {
                base.execute(request)
            }
        }
        val factory = HostContextFactory(
            ExtensionStore(tmp.newFolder()), testSessionStore(), transport
        )
        val prober = LinkProber(factory, testSessionStore())
        val result = prober.testDrive(laneConfig(), NovelightSite.BOOK_URL)
        assertTrue("expected Err, got $result", result is AppResult.Err)
        val message = (result as AppResult.Err).error.let {
            (it as? app.xreader.core.error.AppError.BadFile)?.reason ?: it.toString()
        }
        assertTrue(message.contains("paid or locked"))
    }

    // ── Codec: the new block is optional and round-trips ────────

    @Test
    fun `config codec round trip keeps the ajax block`() {
        val config = laneConfig()
        val decoded = DeclarativeFingerprints.decode(
            DeclarativeFingerprints.encode(listOf(config))
        )
        assertEquals(listOf(config), decoded)
        assertEquals(
            "${NovelightSite.BASE}/book/ajax/read-chapter/{id}",
            decoded.single().chapter.ajax?.urlTemplate
        )
        assertEquals(
            "XMLHttpRequest",
            decoded.single().chapter.ajax?.headers?.get("X-Requested-With")
        )
    }

    @Test
    fun `old json without the ajax block parses with the mode absent`() {
        // Serialise a pre-mode config the way an OLD app version
        // would (defaults omitted -> no "ajax" key at all), then
        // read it back through the canonical codec.
        val legacy = Json { ignoreUnknownKeys = true }
        val raw = legacy.encodeToString(
            ListSerializer(DeclarativeConfig.serializer()),
            listOf(FixtureSite.config())
        )
        assertFalse(raw.contains("\"ajax\""))
        val decoded = DeclarativeFingerprints.decode(raw)
        assertEquals(1, decoded.size)
        assertNull(decoded.single().chapter.ajax)
    }
}
