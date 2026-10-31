package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeExtensionRepoDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.data.net.HttpTransport
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.2.2 hotfix — the crash-net regression suite.
 *
 * First external report (2026-10-31): the app CRASHED when a
 * user tapped through both flagship add flows — Add repository
 * and Add from link — around novelbuddy.me. Code audit found
 * the structural defect: both submit handlers launch uncaught
 * coroutines, and the probe / repo entry points let ANY
 * escaping Throwable (an unchecked exception from a
 * collaborator, an Error from a deep DOM/regex walk — stack
 * overflow and OOM bypass every `catch (Exception)` island)
 * reach the process handler.
 *
 * These tests pin the boundary contract: the entry points
 * convert every escape into an honest failure result. The
 * throwing-collaborator tests FAIL on the pre-fix tree (the
 * throw escapes to the caller) and pass after the net.
 *
 * The novelbuddy characterisation tests pin what the probe
 * honestly does on the real captures (fixtures-capture/
 * novelbuddy.com/): a book-page paste test-drives Ready with
 * the ~20 chapters the static page exposes (of 2,217 the page
 * states — the rest is JS pagination), and a root/listing
 * paste is refused, because the site's book links are
 * single-segment `/<slug>` hrefs that the book-shape rule
 * (v1.1.1, anti-junk) does not accept as books.
 */
class V122CrashNetTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── harness ────────────────────────────────────────────────

    private fun prober(transport: FakeTransport): LinkProber {
        val factory = HostContextFactory(
            ExtensionStore(tmp.newFolder()), testSessionStore(), transport
        )
        return LinkProber(factory, testSessionStore())
    }

    private fun manager(transport: FakeTransport): RepoManager {
        val repoDao = FakeExtensionRepoDao()
        val extDao = FakeInstalledExtensionDao()
        val store = ExtensionStore(tmp.newFolder())
        val sessionStore = SessionStore(testVault())
        val service = ExtensionService(
            extDao, store,
            HostContextFactory(store, sessionStore, transport),
            FakeDexLoader()
        ) { FakeJsRuntime() }
        return RepoManager(repoDao, extDao, store, transport, service, sessionStore)
    }

    private fun bytesResponse(code: Int, bytes: ByteArray) =
        HttpTransport.Response(
            code = code,
            headers = mapOf("Content-Length" to bytes.size.toString()),
            body = bytes.inputStream(),
            isPartialContent = false
        )

    // ── the repo-add net (fail-before) ─────────────────────────

    @Test
    fun `repo add converts a transport runtime exception into an honest failure`() =
        runBlocking {
            val transport = FakeTransport { throw RuntimeException("boom-transport") }
            val outcome = manager(transport)
                .addRepoOrDetect("Anything", "https://example.com/index.json")
            assertTrue(
                "expected Failed, got $outcome",
                outcome is RepoManager.AddRepoOutcome.Failed
            )
        }

    @Test
    fun `repo add converts a transport error into an honest failure`() =
        runBlocking {
            val transport = FakeTransport { throw StackOverflowError("boom-deep") }
            val outcome = manager(transport)
                .addRepoOrDetect("Anything", "https://example.com/index.json")
            assertTrue(
                "expected Failed, got $outcome",
                outcome is RepoManager.AddRepoOutcome.Failed
            )
        }

    // ── the probe net (fail-before) ────────────────────────────
    // The borderline AI verify is invoked with NO try/catch at
    // the call site (LinkProber.consider): anything the wired
    // verifier throws — a Gemini call's unchecked failure, an
    // Error — escapes the probe. The net must convert it.

    @Test
    fun `probe converts a verifier runtime exception into an honest failure`() =
        runBlocking {
            val outcome = prober(BorderlineSite.transport()).probe(
                BorderlineSite.BOOK_URL,
                semanticVerifier = { _, _ -> throw RuntimeException("boom-ai") }
            )
            assertTrue(
                "expected Failed, got $outcome",
                outcome is LinkProber.Outcome.Failed
            )
        }

    @Test
    fun `probe converts a verifier error into an honest failure`() =
        runBlocking {
            val outcome = prober(BorderlineSite.transport()).probe(
                BorderlineSite.BOOK_URL,
                semanticVerifier = { _, _ -> throw AssertionError("boom-ai-error") }
            )
            assertTrue(
                "expected Failed, got $outcome",
                outcome is LinkProber.Outcome.Failed
            )
        }

    // ── adversarial repo bodies (evidence: parsers hold) ───────

    @Test
    fun `repo add survives a deeply nested json body`() = runBlocking {
        val depth = 20_000
        val nested = "[".repeat(depth) + "]".repeat(depth)
        val transport = FakeTransport { request ->
            if (request.url.endsWith("index.json")) {
                FakeTransport.ok(nested)
            } else {
                AppResult.Ok(FakeTransport.response(404, ""))
            }
        }
        val outcome = manager(transport)
            .addRepoOrDetect("Deep", "https://example.com/index.json")
        assertTrue(
            "expected Failed, got $outcome",
            outcome is RepoManager.AddRepoOutcome.Failed
        )
    }

    @Test
    fun `repo add survives random bytes at a pb url`() = runBlocking {
        val garbage = ByteArray(4_096) { (it * 31 % 251).toByte() }
        val transport = FakeTransport { request ->
            if (request.url.endsWith(".pb")) {
                AppResult.Ok(bytesResponse(200, garbage))
            } else {
                AppResult.Ok(bytesResponse(404, ByteArray(0)))
            }
        }
        val outcome = manager(transport)
            .addRepoOrDetect("Garbage", "https://example.com/index.pb")
        assertTrue(
            "expected Failed, got $outcome",
            outcome is RepoManager.AddRepoOutcome.Failed
        )
    }

    @Test
    fun `repo add survives a truncated real pb`() = runBlocking {
        val raw = captureFile("fixtures-capture/keiyoushi/index-raw.pb")
        assumeTrue("keiyoushi capture not on disk", raw != null)
        val truncated = raw!!.readBytes().copyOf(64)
        val transport = FakeTransport { request ->
            if (request.url.endsWith(".pb")) {
                AppResult.Ok(bytesResponse(200, truncated))
            } else {
                AppResult.Ok(bytesResponse(404, ByteArray(0)))
            }
        }
        val outcome = manager(transport).addRepoOrDetect(
            "Truncated", "https://example.com/index.pb"
        )
        assertTrue(
            "expected Failed, got $outcome",
            outcome is RepoManager.AddRepoOutcome.Failed
        )
    }

    @Test
    fun `repo add survives a gzip bomb at a pb url`() = runBlocking {
        // ~70 MB of zeros compresses to a few KB; the wire
        // reader's decompression cap must refuse it, not OOM.
        val sink = ByteArrayOutputStream()
        GZIPOutputStream(sink).use { gz ->
            val chunk = ByteArray(1 shl 20)
            repeat(70) { gz.write(chunk) }
        }
        val bomb = sink.toByteArray()
        val transport = FakeTransport { request ->
            if (request.url.endsWith(".pb")) {
                AppResult.Ok(bytesResponse(200, bomb))
            } else {
                AppResult.Ok(bytesResponse(404, ByteArray(0)))
            }
        }
        val outcome = manager(transport).addRepoOrDetect(
            "Bomb", "https://example.com/index.pb"
        )
        assertTrue(
            "expected Failed, got $outcome",
            outcome is RepoManager.AddRepoOutcome.Failed
        )
    }

    // ── novelbuddy characterisation (real captures) ────────────

    private fun captureFile(relative: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val base = dir ?: return null
            val candidate = File(base, relative)
            if (candidate.exists()) return candidate
            dir = base.parentFile
        }
        return null
    }

    private fun novelbuddyTransport(): FakeTransport {
        val dir = captureFile("fixtures-capture/novelbuddy.com")
        fun read(name: String) = File(dir!!, name).readText(Charsets.UTF_8)
        val bodies = mapOf(
            "home" to read("home.html"),
            "latest" to read("latest.html"),
            "book" to read("book.html"),
            "chapter" to read("chapter.html"),
            "search" to read("search.html"),
            "notfound" to read("browse.html")
        )
        val bookUrl = "https://novelbuddy.me/eternal-holy-emperor"
        val chapterUrl = "$bookUrl/chapter-2215-2041-not-divine-" +
            "king-defeating-divine-king"
        val exact = mapOf(
            "https://novelbuddy.me" to "home",
            "https://novelbuddy.me/" to "home",
            "https://novelbuddy.me/latest" to "latest",
            bookUrl to "book",
            chapterUrl to "chapter"
        )
        val chapterAlias = Regex(
            "https://novelbuddy\\.me/eternal-holy-emperor/chapter-.*"
        )
        return FakeTransport { request ->
            val url = request.url
            if (request.method != "GET") {
                return@FakeTransport AppResult.Ok(
                    FakeTransport.response(404, bodies.getValue("notfound"))
                )
            }
            exact[url]?.let {
                return@FakeTransport FakeTransport.ok(bodies.getValue(it))
            }
            if (url.substringBefore('?') == "https://novelbuddy.me/search") {
                return@FakeTransport FakeTransport.ok(bodies.getValue("search"))
            }
            if (chapterAlias.containsMatchIn(url)) {
                return@FakeTransport FakeTransport.ok(bodies.getValue("chapter"))
            }
            AppResult.Ok(FakeTransport.response(404, bodies.getValue("notfound")))
        }
    }

    @Test(timeout = 300_000)
    fun `novelbuddy book page paste test-drives ready with the exposed chapters`() =
        runBlocking {
            assumeTrue(
                "novelbuddy capture not on disk",
                captureFile("fixtures-capture/novelbuddy.com") != null
            )
            val outcome = prober(novelbuddyTransport())
                .probe("https://novelbuddy.me/eternal-holy-emperor")
            assertTrue(
                "expected Ready, got $outcome",
                outcome is LinkProber.Outcome.Ready
            )
            val ready = outcome as LinkProber.Outcome.Ready
            assertEquals("Eternal Holy Emperor", ready.draft.testDrive.bookTitle)
            // v1.2.3: the static book page exposes ~20 chapter
            // anchors, and its embedded JSON carries the latest
            // 50 chapter records — the engine now merges both,
            // so the drive reads 50 chapters. Still NOT the
            // 2,217 the page states: the rest sits behind the
            // site's unidentified client-side endpoint with
            // non-patternable slugs. This is why novelbuddy is
            // still NOT bundled as a default provider: the
            // verified config would silently truncate long
            // novels.
            assertEquals(50, ready.draft.testDrive.chapterCount)
        }

    @Test(timeout = 300_000)
    fun `novelbuddy root paste is honestly refused`() = runBlocking {
        assumeTrue(
            "novelbuddy capture not on disk",
            captureFile("fixtures-capture/novelbuddy.com") != null
        )
        val outcome = prober(novelbuddyTransport()).probe("https://novelbuddy.me")
        assertTrue(
            "expected Failed, got $outcome",
            outcome is LinkProber.Outcome.Failed
        )
    }
}

/** A borderline-shape site (mirrors the private fixture in
 * V1062ProbeSemanticsTest): three books and one post in the
 * catalogue grid — the one band where the probe spends its
 * single AI verify call, whose invoke is the probe's exposed
 * collaborator surface. */
private object BorderlineSite {
    const val BASE = "https://mixed.example"
    const val BOOK_URL = "$BASE/book/quiet-star"

    private val chapterText = (1..5).joinToString("\n") {
        "The quiet star watched over the harbour in part $it, " +
            "and nobody below noticed at all."
    }

    private fun chapterPage(n: Int): String = """
        <html><head><title>Chapter $n</title></head><body>
        <h1>Chapter $n</h1>
        <div id="chapter-content">
          ${chapterText.split("\n").joinToString("\n") { "<p>$it</p>" }}
        </div>
        </body></html>
    """.trimIndent()

    private val bookPage = """
        <html><head>
          <title>Quiet Star - MixedBooks</title>
          <meta property="og:title" content="Quiet Star"/>
          <meta name="description" content="A quiet star saga."/>
        </head><body>
        <form action="/catalogue" method="get"><input type="text" name="q"/></form>
        <h1>Quiet Star</h1>
        <ul id="chapter-list">
          <li><a href="/book/quiet-star/chapter-3">Chapter 3</a></li>
          <li><a href="/book/quiet-star/chapter-2">Chapter 2</a></li>
          <li><a href="/book/quiet-star/chapter-1">Chapter 1</a></li>
        </ul>
        </body></html>
    """.trimIndent()

    private fun anchor(href: String, img: String, text: String): String =
        "<a href=\"$href\"><img src=\"$img\"/>$text</a>"

    private val mixedHome = """
        <html><body><div class="grid">
          ${anchor("/book/quiet-star", "/covers/quiet.jpg", "Quiet Star")}
          ${anchor("/book/loud-moon", "/covers/moon.jpg", "Loud Moon")}
          ${anchor("/book/dim-sun", "/covers/sun.jpg", "Dim Sun")}
          ${anchor("/post/1", "/avatars/m.jpg", "Monarch just now Ruined the mood")}
        </div></body></html>
    """.trimIndent()

    private val cataloguePage = """
        <html><body><div class="grid">
          ${anchor("/book/quiet-star", "/covers/quiet.jpg", "Quiet Star")}
          ${anchor("/book/loud-moon", "/covers/moon.jpg", "Loud Moon")}
          ${anchor("/book/dim-sun", "/covers/sun.jpg", "Dim Sun")}
        </div></body></html>
    """.trimIndent()

    private val postPage = """
        <html><head><title>Monarch's post</title></head><body>
        <h1>Monarch</h1>
        <p>Ruined the mood.</p>
        </body></html>
    """.trimIndent()

    fun transport(): FakeTransport = FakeTransport { request ->
        val url = request.url
        val body = when {
            url == BOOK_URL -> bookPage
            url.startsWith("$BASE/book/quiet-star/chapter-") ->
                chapterPage(url.substringAfterLast("chapter-").toInt())
            url.startsWith("$BASE/catalogue") -> cataloguePage
            url.startsWith("$BASE/post/") -> postPage
            url == BASE || url == "$BASE/" -> mixedHome
            else -> null
        }
        if (body == null) {
            AppResult.Ok(FakeTransport.response(404, "not found"))
        } else {
            FakeTransport.ok(body)
        }
    }
}
