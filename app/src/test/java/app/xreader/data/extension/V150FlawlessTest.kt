package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceChapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.0 (the flawless round): the Part A mechanisms pinned
 * with synthetic fixtures —
 *
 *  - the API-harvest synthesizer (A2): a Chikari-shaped
 *    harvest becomes a recipe that drives all five legs;
 *    a BOTI-shaped harvest binds its cookie→header
 *    credential; a below-bar array is never a book list
 *    (steer A1);
 *  - the token roads (A7i + steer A3): a protected-format
 *    answer mints and retries; a scrambled answer in the
 *    readable format evicts, remints and retries; a source
 *    with no token contract never mints;
 *  - per-book kind sniffing (A6): an image chapter on a
 *    text source reads as pages, a prose chapter on a
 *    manga source reads as prose;
 *  - the login-wall heuristic (steer A8) and its
 *    classification;
 *  - the envelope unwrapper (steer A2): scan shapes, and a
 *    drifted envelope still listing books.
 */
class V150FlawlessTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── A2: the harvest synthesizer ────────────────────────

    private val prose = "The ferryman poled his raft across the " +
        "black river while the town bells counted the evening " +
        "hour, and every passenger held a lantern against the " +
        "dark water rising below the boards of the old pier " +
        "while the rain came on."

    private fun call(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap()
    ) = HarvestedCall(
        url = url,
        method = "GET",
        status = 200,
        contentType = "application/json",
        requestHeaders = headers,
        bodySample = body,
        pageCookies = cookies
    )

    private fun chikariCalls(): List<HarvestedCall> {
        val listing = """[
          {"title":"Sword Saga","slug":"sword-saga","cover_url":"/c1.jpg"},
          {"title":"River Tale","slug":"river-tale","cover_url":"/c2.jpg"},
          {"title":"Night Ferry","slug":"night-ferry","cover_url":"/c3.jpg"}
        ]""".trimIndent()
        return listOf(
            call(
                "https://chikari.example/api/novels/search?q=lord&limit=20",
                listing
            ),
            call(
                "https://chikari.example/api/novels/sword-saga",
                """{"title":"Sword Saga","slug":"sword-saga","synopsis":"A saga."}"""
            ),
            call(
                "https://chikari.example/api/novels/sword-saga/chapters?limit=100&offset=0",
                """{"items":[
                  {"title":"Chapter 1","number":1},
                  {"title":"Chapter 2","number":2},
                  {"title":"Chapter 3","number":3}
                ],"total":3,"limit":100,"offset":0}"""
            ),
            call(
                "https://chikari.example/api/novels/sword-saga/chapters/1/read",
                """{"body":"$prose","locked":false}"""
            )
        )
    }

    @Test
    fun `a chikari-shaped harvest synthesises a recipe that drives all five legs`() =
        runBlocking {
            val synthesis = ApiRecipeSynthesizer.synthesize(
                chikariCalls(),
                webBaseUrl = "https://chikari.example",
                siteName = "Chikari Shape",
                bookUrlHint = "https://chikari.example/novels/sword-saga"
            )
            assertNotNull("synthesis must succeed", synthesis)
            val config = synthesis!!.config.copy(id = "synth-test")

            // Shape pins: bare-array listing, offset
            // pagination over the items envelope, the ajax
            // read with its seg positions and lock field.
            assertEquals("", config.popular?.jsonPath)
            val pagination = config.details.chapters.pagination
            assertNotNull(pagination)
            assertEquals("items", pagination!!.jsonArrayPath)
            assertEquals("total", pagination.jsonTotalPath)
            val ajax = config.chapter.ajax
            assertNotNull(ajax)
            assertEquals("body", ajax!!.jsonContentField)
            assertTrue(ajax.plainTextField)
            assertEquals("locked", ajax.jsonLockField)
            assertEquals(
                "https://chikari.example/api/novels/" +
                    "{seg-3}/chapters/{seg-1}/read",
                ajax.urlTemplate
            )

            val transport = FakeTransport { request ->
                val url = request.url
                when {
                    url.contains("/api/novels/search") ->
                        FakeTransport.ok(
                            chikariCalls()[0].bodySample
                        )
                    url.contains("/chapters?") ->
                        FakeTransport.ok(
                            chikariCalls()[2].bodySample
                        )
                    url.contains("/read") ->
                        FakeTransport.ok(
                            chikariCalls()[3].bodySample
                        )
                    url == "https://chikari.example/api/novels/sword-saga" ->
                        FakeTransport.ok(
                            chikariCalls()[1].bodySample
                        )
                    else -> AppResult.Ok(
                        FakeTransport.response(404, "nope")
                    )
                }
            }
            val source = DeclarativeSource(
                config,
                testContext(
                    transport, tmp.newFolder(),
                    ExtensionPermissions(
                        domains = listOf("chikari.example")
                    )
                )
            )
            val popular = source.popular(1)
            assertEquals(3, popular.books.size)
            assertEquals("Sword Saga", popular.books[0].title)
            val searched = source.search("lord", 1)
            assertEquals(3, searched.books.size)
            val book = popular.books[0]
            val details = source.details(book)
            assertTrue(details.title.isNotBlank())
            val chapters = source.chapters(book)
            assertEquals(3, chapters.size)
            val content = source.chapterContent(chapters[0])
            assertTrue(content.html.contains("ferryman"))
        }

    @Test
    fun `a boti-shaped harvest binds the cookie as a header on the api host`() {
        val listing = """[
          {"title":"Wave One","slug":"wave-one"},
          {"title":"Wave Two","slug":"wave-two"},
          {"title":"Wave Three","slug":"wave-three"}
        ]""".trimIndent()
        val calls = listOf(
            call(
                "https://api.stories.example/titles/search?q=wave",
                listing,
                headers = mapOf("Authorization" to "tok-xyz"),
                cookies = mapOf("userToken" to "tok-xyz")
            ),
            call(
                "https://api.stories.example/titles/wave-one",
                """{"title":"Wave One","slug":"wave-one"}""",
                headers = mapOf("Authorization" to "tok-xyz"),
                cookies = mapOf("userToken" to "tok-xyz")
            ),
            call(
                "https://api.stories.example/titles/wave-one/chapters",
                """{"chapters":[
                  {"title":"Chapter 1","id":"c1"},
                  {"title":"Chapter 2","id":"c2"}
                ]}""",
                headers = mapOf("Authorization" to "tok-xyz"),
                cookies = mapOf("userToken" to "tok-xyz")
            )
        )
        val synthesis = ApiRecipeSynthesizer.synthesize(
            calls,
            webBaseUrl = "https://stories.example",
            siteName = "Stories Shape",
            bookUrlHint = "https://stories.example/titles/wave-one"
        )
        assertNotNull(synthesis)
        val binding = synthesis!!.config.credentials?.headerBinding
        assertNotNull(binding)
        assertEquals("userToken", binding!!.cookieName)
        assertEquals("Authorization", binding.headerName)
        assertEquals(listOf("api.stories.example"), binding.hosts)
        assertTrue(
            synthesis.apiHosts.contains("api.stories.example")
        )
    }

    @Test
    fun `an array below the book-list bar is never a book list`() {
        val calls = listOf(
            call(
                "https://chikari.example/api/telemetry",
                """[
                  {"title":"event","slug":"a"},
                  {"title":"event","slug":"b"}
                ]"""
            )
        )
        val synthesis = ApiRecipeSynthesizer.synthesize(
            calls,
            webBaseUrl = "https://chikari.example",
            siteName = "Telemetry",
            bookUrlHint = null
        )
        assertNull(synthesis)
    }

    // ── A7i / steer A3: the token roads ────────────────────

    private fun fenrirConfig(withCredentials: Boolean) =
        DeclarativeConfig(
            id = "v150-fenrir-${if (withCredentials) "cred" else "plain"}",
            name = "Fenrir Shape",
            lang = "en",
            baseUrl = "https://fenrir.example",
            details = DetailsSpec(
                chapters = ChapterListSpec(
                    list = "a", name = "@text", link = "@href"
                )
            ),
            chapter = ChapterSpec(
                content = "body",
                ajax = ChapterTextAjax(
                    urlTemplate = "https://fenrir.example/api" +
                        "/series/{seg-3}/chapters/{seg-1}",
                    jsonContentField = "content"
                )
            ),
            signInNeeded = true,
            credentials = if (withCredentials) {
                CredentialSpec(
                    operationToken = OperationTokenSpec(
                        mintUrl = "https://fenrir.example/api/read-token",
                        mintBodyTemplate = "{\"chapter_id\":\"{chapter_id}\"}",
                        checkUrlTemplate =
                            "https://fenrir.example/series/{seg-3}/{seg-1}",
                        renewalMarginSeconds = 120
                    )
                )
            } else {
                null
            }
        )

    private val fenrirChapterUrl =
        "https://fenrir.example/api/series/sword-saga/chapters/ch-1"

    private val blob = (1..8).joinToString("") {
        "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVpbXF5eXp7"
    }

    /** A Fenrir-shaped server: [anonymous] answers every
     * chapter fetch until the right Bearer token arrives. */
    private fun fenrirTransport(
        mints: MutableList<String>,
        anonymous: (auth: String?) -> String
    ): FakeTransport = FakeTransport { request ->
        when {
            request.url == "https://fenrir.example/api/read-token" -> {
                mints += request.body ?: ""
                FakeTransport.ok(
                    "{\"active\":true,\"token\":\"tok-fresh\"," +
                        "\"expires_at\":9999999999}"
                )
            }
            request.url == fenrirChapterUrl -> {
                val auth = request.headers.entries.firstOrNull {
                    it.key.equals("Authorization", ignoreCase = true)
                }?.value
                if (auth == "Bearer tok-fresh") {
                    FakeTransport.ok(
                        "{\"content\":\"<p>$prose</p>\"," +
                            "\"content_format\":\"html\"}"
                    )
                } else {
                    FakeTransport.ok(anonymous(auth))
                }
            }
            else -> AppResult.Ok(FakeTransport.response(404, "nope"))
        }
    }

    @Test
    fun `a protected-format answer mints once and retries to prose`() {
        ReadTokenCoordinators.clearForTests()
        val mints = mutableListOf<String>()
        val transport = fenrirTransport(mints) {
            "{\"content\":\"$blob\",\"content_format\":\"protected\"}"
        }
        val source = DeclarativeSource(
            fenrirConfig(withCredentials = true),
            testContext(
                transport, tmp.newFolder(),
                ExtensionPermissions(domains = listOf("fenrir.example"))
            )
        )
        val content = source.chapterContent(
            SourceChapter(id = fenrirChapterUrl, name = "Chapter 1")
        )
        assertTrue(content.html.contains("ferryman"))
        assertEquals(1, mints.size)
    }

    @Test
    fun `a scrambled answer in the readable format evicts and remints`() {
        ReadTokenCoordinators.clearForTests()
        val mints = mutableListOf<String>()
        val transport = fenrirTransport(mints) {
            "{\"content\":\"$blob\",\"content_format\":\"html\"}"
        }
        val source = DeclarativeSource(
            fenrirConfig(withCredentials = true),
            testContext(
                transport, tmp.newFolder(),
                ExtensionPermissions(domains = listOf("fenrir.example"))
            )
        )
        val content = source.chapterContent(
            SourceChapter(id = fenrirChapterUrl, name = "Chapter 1")
        )
        assertTrue(content.html.contains("ferryman"))
        assertEquals(1, mints.size)
    }

    @Test
    fun `a source with no token contract never mints`() {
        ReadTokenCoordinators.clearForTests()
        val mints = mutableListOf<String>()
        val transport = fenrirTransport(mints) {
            "{\"content\":\"$blob\",\"content_format\":\"protected\"}"
        }
        val source = DeclarativeSource(
            fenrirConfig(withCredentials = false),
            testContext(
                transport, tmp.newFolder(),
                ExtensionPermissions(domains = listOf("fenrir.example"))
            )
        )
        // Without a contract there is nothing to mint with —
        // and the protected blob must still never be SERVED:
        // the serve gate refuses it downstream (the honest
        // backstop), which is what this pins.
        val content = source.chapterContent(
            SourceChapter(id = fenrirChapterUrl, name = "Chapter 1")
        )
        val plain = org.jsoup.Jsoup.parseBodyFragment(content.html)
            .text()
        assertNotNull(ChapterServeGate.refusalFor(plain))
        assertEquals(0, mints.size)
    }

    // ── A6: per-book kind sniffing ─────────────────────────

    private fun sniffConfig(manga: Boolean) = DeclarativeConfig(
        id = "v150-sniff-${if (manga) "manga" else "text"}",
        name = "Sniff Shape",
        lang = "en",
        baseUrl = "https://novels.example",
        mediaKind = if (manga) MediaKind.MANGA else MediaKind.TEXT,
        details = DetailsSpec(
            chapters = ChapterListSpec(
                list = "a", name = "@text", link = "@href"
            )
        ),
        chapter = ChapterSpec(
            content = "div.content",
            pages = if (manga) "div.pages img" else null
        )
    )

    private val imageChapterPage = """
        <html><head><title>C1</title></head><body>
        <div class="pages">
          <img src="/p/1.jpg" width="800" height="1200"/>
          <img src="/p/2.jpg" width="800" height="1200"/>
          <img src="/p/3.jpg" width="800" height="1200"/>
          <img src="/p/4.jpg" width="800" height="1200"/>
        </div>
        </body></html>
    """.trimIndent()

    private val proseChapterPage = """
        <html><head><title>C1</title></head><body>
        <div class="content">
          <p>The ferryman poled his raft across the black river
          while the town bells counted the evening hour, and every
          passenger held a lantern against the dark water rising
          below the boards of the old pier near the harbour wall.</p>
        </div>
        </body></html>
    """.trimIndent()

    private fun sniffSource(
        manga: Boolean,
        page: String,
        url: String
    ): DeclarativeSource {
        val transport = FakeTransport { request ->
            if (request.url == url) {
                FakeTransport.ok(page)
            } else {
                AppResult.Ok(FakeTransport.response(404, "nope"))
            }
        }
        return DeclarativeSource(
            sniffConfig(manga),
            testContext(
                transport, tmp.newFolder(),
                ExtensionPermissions(domains = listOf("novels.example"))
            )
        )
    }

    @Test
    fun `an image chapter on a text source reads as pages`() {
        val url = "https://novels.example/book/x/chapter-1"
        val source = sniffSource(manga = false, imageChapterPage, url)
        val chapter = SourceChapter(id = url, name = "Chapter 1")
        val content = source.chapterContent(chapter)
        assertTrue(content.html.contains("<img"))
        val pages = source.pagesOf(url)
        assertNotNull(pages)
        assertEquals(4, pages!!.pages.size)
    }

    @Test
    fun `a prose chapter on a manga source reads as prose`() {
        val url = "https://novels.example/book/x/chapter-1"
        val source = sniffSource(manga = true, proseChapterPage, url)
        val chapter = SourceChapter(id = url, name = "Chapter 1")
        val content = source.chapterContent(chapter)
        assertTrue(content.html.contains("ferryman"))
        assertNull(source.pagesOf(url))
    }

    // ── steer A8: the login wall ───────────────────────────

    @Test
    fun `short login-phrased text is a wall, long prose mentioning login is not`() {
        assertNotNull(
            ChapterServeGate.loginWallRefusal(
                "Please sign in to read this chapter. Members only."
            )
        )
        val longProse = (1..12).joinToString(" ") {
            "The ferryman asked the traveller to sign in the " +
                "harbour ledger before the evening crossing $it."
        }
        assertNull(ChapterServeGate.loginWallRefusal(longProse))
        assertNull(
            ChapterServeGate.loginWallRefusal(
                "The rain had stopped by the time they reached the inn."
            )
        )
    }

    @Test
    fun `the login wall classifies as a sign-in need`() {
        val kind = SessionNeed.classify(
            null,
            "this chapter asks you to sign in to read it — " +
                "the site showed its sign-in wall instead of " +
                "the chapter text"
        )
        assertEquals(SessionNeed.Kind.SIGN_IN, kind)
    }

    // ── steer A2: the envelope unwrapper ───────────────────

    @Test
    fun `the envelope scan finds arrays under every standard key`() {
        val bare = EmbeddedJson.scanForArray(
            kotlinx.serialization.json.Json.parseToJsonElement("[1,2]")
        )
        assertEquals(2, bare?.size)
        for (key in listOf(
            "items", "data", "results", "chapters", "list",
            "rows", "novels", "books"
        )) {
            val found = EmbeddedJson.scanForArray(
                kotlinx.serialization.json.Json.parseToJsonElement(
                    """{"$key":[1,2,3]}"""
                )
            )
            assertEquals("key $key", 3, found?.size)
        }
        val nested = EmbeddedJson.scanForArray(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"data":{"items":[1,2,3,4]}}"""
            )
        )
        assertEquals(4, nested?.size)
        assertNull(
            EmbeddedJson.scanForArray(
                kotlinx.serialization.json.Json.parseToJsonElement(
                    """{"items":[]}"""
                )
            )
        )
    }

    @Test
    fun `a drifted listing envelope still lists books`() = runBlocking {
        // The recipe declares "results"; the site now wraps
        // its records in "items". The scan-on-miss keeps the
        // catalogue leg alive.
        val config = DeclarativeConfig(
            id = "v150-drift",
            name = "Drift Shape",
            lang = "en",
            baseUrl = "https://drift.example",
            popular = ListSpec(
                url = "https://drift.example/api/books",
                results = "",
                title = "title",
                link = "slug",
                jsonPath = "results",
                jsonLinkTemplate = "https://drift.example/book/{value}"
            ),
            details = DetailsSpec(
                chapters = ChapterListSpec(
                    list = "a", name = "@text", link = "@href"
                )
            ),
            chapter = ChapterSpec(content = "body")
        )
        val transport = FakeTransport { request ->
            if (request.url.contains("/api/books")) {
                FakeTransport.ok(
                    """{"items":[
                      {"title":"Drift One","slug":"drift-one"},
                      {"title":"Drift Two","slug":"drift-two"}
                    ]}"""
                )
            } else {
                AppResult.Ok(FakeTransport.response(404, "nope"))
            }
        }
        val source = DeclarativeSource(
            config,
            testContext(
                transport, tmp.newFolder(),
                ExtensionPermissions(domains = listOf("drift.example"))
            )
        )
        val popular = source.popular(1)
        assertEquals(2, popular.books.size)
        assertEquals("Drift One", popular.books[0].title)
    }
}
