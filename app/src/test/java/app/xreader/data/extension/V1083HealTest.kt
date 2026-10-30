package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.domain.content.ChapterBlock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.0.8.3: the learner must recover on its own (the woopread
 * lesson). Three pins:
 *
 * 1. The extraction sliver guard: when the structured
 *    conversion keeps only a sliver of the text a chapter
 *    element holds, the plain-text reading wins — a chapter
 *    never ships as a promo card.
 * 2. The same guard stays OUT of faithful conversions:
 *    ordinary <p> chapters keep their exact block structure.
 * 3. The test-drive self-heal: a candidate whose chapter
 *    rules read a promo block instead of the story is
 *    re-analysed from the chapter page itself and redriven
 *    with the healed rules — the site is added, not refused.
 * 4. Deep sampling: a book whose opening chapters are
 *    non-prose notices is proven by a real chapter deeper
 *    in the list.
 */
class V1083HealTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun prober(
        transport: app.xreader.data.FakeTransport
    ): LinkProber {
        val factory = HostContextFactory(
            ExtensionStore(tmp.newFolder()), testSessionStore(), transport
        )
        return LinkProber(factory, testSessionStore())
    }

    // ── 1 + 2: the HtmlToContent sliver guard ───────────────

    @Test
    fun `conversion sliver falls back to the element's full text`() {
        // The story sits where the structured converter does
        // not walk (a noscript block); only the promo line
        // converts. Blocks would carry ~50 of ~610 readable
        // characters — the plain-text reading must win. (The
        // fixture must clear the guard's 400-char floor with
        // room to spare: the gate's first run of this test
        // used a ~330-char fixture that stayed under the
        // floor, so the guard rightly stayed out and the
        // test failed on its own arithmetic.)
        val prose = listOf(
            "The lighthouse keeper climbed the tower at dusk, as " +
                "he had done every evening for thirty years.",
            "Below him the harbour lights came on one by one, " +
                "and the tide worried at the rocks without rest.",
            "\"Storm by Thursday,\" he told the cat. \"You can " +
                "feel it in the stairs, same as I can.\"",
            "He lit the lamp, trimmed the wick, and opened the " +
                "log book to a fresh page.",
            "Outside, the first rain needled the glass, and the " +
                "light turned over the black water.",
            "He wrote the date, the wind, and the state of the " +
                "sea, in the small careful hand of a man with " +
                "nowhere else to be."
        )
        val fragment = "<div class=\"chapter-body\"><noscript>" +
            prose.joinToString("") { "<p>$it</p>" } +
            "</noscript>" +
            "<p>Get early access to chapters and exclusive content!</p>" +
            "</div>"
        val content = HtmlToContent.fromFragment(
            fragment, "https://woop.example/series/woop-book/chapter-1"
        )
        val text = content.blocks
            .filterIsInstance<ChapterBlock.Paragraph>()
            .joinToString("\n") { it.text }
        assertTrue(
            "the fallback must carry the story, got: ${text.take(120)}",
            text.contains("lighthouse keeper")
        )
        assertTrue(
            "the fallback must carry the whole chapter, got ${text.length}",
            text.length > 400
        )
    }

    @Test
    fun `faithful conversion keeps its block structure`() {
        val paragraphs = listOf(
            "The lighthouse keeper climbed the tower at dusk, as " +
                "he had done every evening for thirty years.",
            "Below him the harbour lights came on one by one, " +
                "and the tide worried at the rocks without rest.",
            "\"Storm by Thursday,\" he told the cat. \"You can " +
                "feel it in the stairs, same as I can.\"",
            "He lit the lamp, trimmed the wick, and opened the " +
                "log book to a fresh page."
        )
        val fragment = paragraphs.joinToString("") { "<p>$it</p>" }
        val content = HtmlToContent.fromFragment(
            fragment, "https://woop.example/series/woop-book/chapter-1"
        )
        assertEquals(
            paragraphs,
            content.blocks
                .filterIsInstance<ChapterBlock.Paragraph>()
                .map { it.text }
        )
    }

    // ── the woop-shaped site ────────────────────────────────

    private object WoopShape {
        const val HOST = "woop.example"
        const val BASE = "https://woop.example"
        const val BOOK_URL = "$BASE/series/woop-book"

        val PROSE = listOf(
            "The lighthouse keeper climbed the tower at dusk, as " +
                "he had done every evening for thirty years.",
            "Below him the harbour lights came on one by one, " +
                "and the tide worried at the rocks without rest.",
            "\"Storm by Thursday,\" he told the cat. \"You can " +
                "feel it in the stairs, same as I can.\"",
            "He lit the lamp, trimmed the wick, and opened the " +
                "log book to a fresh page.",
            "Outside, the first rain needled the glass, and the " +
                "light turned over the black water.",
            "He wrote the date, the wind, and the state of the " +
                "sea, in the small careful hand of a man with " +
                "nowhere else to be."
        )

        // ~250 readable characters, exactly three sentence
        // enders — long enough to clear the length floor, not
        // prose by the gate's count.
        val PROMO = """
            <div class="promos">
              <h3>Support the translation team</h3>
              <p>Get early access to chapters and exclusive content!</p>
              <p>Enjoying the story? Leave a review and rating!</p>
              <p>Discuss chapters, share theories, and join our community</p>
              <p>Patreon members, discord members, and kind readers keep the lights on</p>
            </div>
        """.trimIndent()

        fun chapterPage(n: Int): String {
            val paragraphs = PROSE.joinToString("\n") { p ->
                "<div data-paragraph-index=\"0\"><p>$p</p></div>"
            }
            return """
                <html><head><title>Chapter $n - Woop Book</title></head>
                <body>
                <main class="flex-1 overflow-auto p-4">
                  <h2>Chapter $n</h2>
                  <div class="space-y-4">
                $paragraphs
                  </div>
                $PROMO
                </main>
                </body></html>
            """.trimIndent()
        }

        val BOOK_PAGE = """
            <html><head><title>Woop Book</title></head><body>
            <main>
              <h1>Woop Book</h1>
              <p>A keeper, a lamp, and the long patience of the sea.</p>
              <div class="chapters">
                <a class="chapter-row" href="/series/woop-book/chapter-3">Chapter 3</a>
                <a class="chapter-row" href="/series/woop-book/chapter-2">Chapter 2</a>
                <a class="chapter-row" href="/series/woop-book/chapter-1">Chapter 1</a>
              </div>
            </main>
            </body></html>
        """.trimIndent()

        fun transport(): app.xreader.data.FakeTransport =
            app.xreader.data.FakeTransport { request ->
                when {
                    request.url == BOOK_URL ->
                        app.xreader.data.FakeTransport.ok(BOOK_PAGE)
                    request.url == "$BOOK_URL/chapter-1" ->
                        app.xreader.data.FakeTransport.ok(chapterPage(1))
                    request.url == "$BOOK_URL/chapter-2" ->
                        app.xreader.data.FakeTransport.ok(chapterPage(2))
                    request.url == "$BOOK_URL/chapter-3" ->
                        app.xreader.data.FakeTransport.ok(chapterPage(3))
                    else -> AppResult.Ok(
                        app.xreader.data.FakeTransport.response(404, "no")
                    )
                }
            }

        /** The rules a bad first read would build: the chapter
         * content points at the promo block, not the story. */
        fun brokenConfig() = DeclarativeConfig(
            id = "woopshape",
            name = "WoopShape",
            baseUrl = BASE,
            details = DetailsSpec(
                title = "h1",
                chapters = ChapterListSpec(
                    list = "a.chapter-row",
                    name = "@text",
                    link = "@href",
                    reverse = true
                )
            ),
            chapter = ChapterSpec(content = "div.promos")
        )
    }

    // ── 3: the self-heal ────────────────────────────────────

    @Test
    fun `drive heals chapter rules that read the promo block`() =
        runBlocking {
            val result = prober(WoopShape.transport())
                .testDrive(WoopShape.brokenConfig(), WoopShape.BOOK_URL)
            assertTrue(
                "expected the healed drive to pass, got $result",
                result is AppResult.Ok
            )
            val drive = (result as AppResult.Ok).value
            assertEquals("Woop Book", drive.bookTitle)
            assertEquals(3, drive.chapterCount)
            assertTrue(
                "preview must be story text, got: " +
                    drive.chapterTextPreview.take(120),
                drive.chapterTextPreview.contains("lighthouse keeper")
            )
            assertTrue(
                drive.chapterTextLength >= LinkInference.MIN_READABLE_CHARS
            )
        }

    // ── 4: deep sampling ────────────────────────────────────

    private object NoticeBook {
        const val BASE = "https://notice.example"
        const val BOOK_URL = "$BASE/series/notice-book"

        // ~225 characters, no sentence enders at all, no lock
        // words — a maintenance notice, not a chapter.
        const val NOTICE =
            "Site notice, chapter uploads pause during server " +
                "maintenance this week, uploads resume after the " +
                "migration completes, thank you for reading along " +
                "with us, more news follows in the next " +
                "announcement, posted by the team on duty"

        fun page(mainInner: String): String = """
            <html><head><title>Notice Book</title></head><body>
            <main>$mainInner</main>
            </body></html>
        """.trimIndent()

        fun chapterPage(n: Int): String =
            if (n == 5) {
                page(
                    WoopShape.PROSE.joinToString("\n") { "<p>$it</p>" }
                )
            } else {
                page("<p>$NOTICE</p>")
            }

        val BOOK_PAGE = """
            <html><head><title>Notice Book</title></head><body>
            <main>
              <h1>Notice Book</h1>
              <div class="chapters">
                <a class="chapter-row" href="/series/notice-book/chapter-7">Chapter 7</a>
                <a class="chapter-row" href="/series/notice-book/chapter-6">Chapter 6</a>
                <a class="chapter-row" href="/series/notice-book/chapter-5">Chapter 5</a>
                <a class="chapter-row" href="/series/notice-book/chapter-4">Chapter 4</a>
                <a class="chapter-row" href="/series/notice-book/chapter-3">Chapter 3</a>
                <a class="chapter-row" href="/series/notice-book/chapter-2">Chapter 2</a>
                <a class="chapter-row" href="/series/notice-book/chapter-1">Chapter 1</a>
              </div>
            </main>
            </body></html>
        """.trimIndent()

        fun transport(): app.xreader.data.FakeTransport =
            app.xreader.data.FakeTransport { request ->
                when {
                    request.url == BOOK_URL ->
                        app.xreader.data.FakeTransport.ok(BOOK_PAGE)
                    request.url.startsWith("$BOOK_URL/chapter-") -> {
                        val n = request.url.substringAfterLast("-")
                            .toInt()
                        app.xreader.data.FakeTransport.ok(chapterPage(n))
                    }
                    else -> AppResult.Ok(
                        app.xreader.data.FakeTransport.response(404, "no")
                    )
                }
            }

        fun config() = DeclarativeConfig(
            id = "noticebook",
            name = "NoticeBook",
            baseUrl = BASE,
            details = DetailsSpec(
                title = "h1",
                chapters = ChapterListSpec(
                    list = "a.chapter-row",
                    name = "@text",
                    link = "@href",
                    reverse = true
                )
            ),
            chapter = ChapterSpec(content = "main")
        )
    }

    @Test
    fun `drive proves text from a deeper chapter when openers are notices`() =
        runBlocking {
            val result = prober(NoticeBook.transport())
                .testDrive(NoticeBook.config(), NoticeBook.BOOK_URL)
            assertTrue(
                "expected the deep-sampled drive to pass, got $result",
                result is AppResult.Ok
            )
            val drive = (result as AppResult.Ok).value
            assertEquals(7, drive.chapterCount)
            assertTrue(
                "preview must be story text, got: " +
                    drive.chapterTextPreview.take(120),
                drive.chapterTextPreview.contains("lighthouse keeper")
            )
        }
}
