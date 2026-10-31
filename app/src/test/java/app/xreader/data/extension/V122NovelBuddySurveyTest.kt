package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeExtensionRepoDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * v1.2.2 CRASH SURVEY (temporary diagnostic — the regression tests
 * land in V122NovelBuddyFixTest once the throws are known).
 *
 * Drives BOTH flagship flows against the real novelbuddy.me
 * captures (fixtures-capture/novelbuddy.com/):
 *  - LinkProber.probe (book / root / latest entries, .me and .com)
 *  - RepoManager.addRepoOrDetect (site pasted as a repo)
 * plus stage-isolated LinkInference checks. Every stage is wrapped
 * individually so the survey records EVERY escape with its stack,
 * instead of dying on the first.
 */
class V122NovelBuddySurveyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val log = StringBuilder()

    private fun line(s: String = "") {
        log.appendLine(s)
        println(s)
    }

    private fun <T> stage(name: String, block: () -> T): T? {
        return try {
            val out = block()
            line("STAGE $name: OK -> $out")
            out
        } catch (t: Throwable) {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            line("STAGE $name: THREW ${t.javaClass.name}: ${t.message}")
            line(sw.toString().lines().take(25).joinToString("\n"))
            null
        }
    }

    private fun captureDir(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val base = dir ?: return null
            val candidate = File(base, "fixtures-capture/novelbuddy.com")
            if (candidate.isDirectory) return candidate
            dir = base.parentFile
        }
        return null
    }

    private lateinit var bodies: Map<String, String>

    private fun loadBodies(dir: File) {
        fun read(name: String) = File(dir, name).readText(Charsets.UTF_8)
        bodies = mapOf(
            "home" to read("home.html"),
            "latest" to read("latest.html"),
            "book" to read("book.html"),
            "chapter" to read("chapter.html"),
            "search" to read("search.html"),
            "notfound" to read("browse.html")
        )
    }

    private val bookUrl = "https://novelbuddy.me/eternal-holy-emperor"
    private val chapterUrl =
        "https://novelbuddy.me/eternal-holy-emperor/" +
            "chapter-2215-2041-not-divine-king-defeating-divine-king"

    /** Serves the captures the way the live site does: exact pages
     * at their URLs, any other chapter of the same book aliased to
     * the one captured chapter, index-shaped paths and everything
     * else answered with the site's real 404 template. */
    private fun transport(): FakeTransport {
        val exact = mapOf(
            "https://novelbuddy.me" to ("home" to 200),
            "https://novelbuddy.me/" to ("home" to 200),
            "https://novelbuddy.com" to ("home" to 200),
            "https://novelbuddy.com/" to ("home" to 200),
            "https://novelbuddy.me/latest" to ("latest" to 200),
            "https://novelbuddy.me/eternal-holy-emperor" to ("book" to 200),
            "https://novelbuddy.com/eternal-holy-emperor" to ("book" to 200),
            chapterUrl to ("chapter" to 200)
        )
        val chapterAlias =
            Regex("https://novelbuddy\\.(me|com)/eternal-holy-emperor/chapter-.*")
        return FakeTransport { request ->
            val url = request.url
            if (request.method != "GET") {
                return@FakeTransport AppResult.Ok(
                    FakeTransport.response(404, bodies.getValue("notfound"))
                )
            }
            exact[url]?.let { (key, code) ->
                return@FakeTransport AppResult.Ok(
                    FakeTransport.response(code, bodies.getValue(key))
                )
            }
            if (url.substringBefore('?') == "https://novelbuddy.me/search" ||
                url.substringBefore('?') == "https://novelbuddy.com/search"
            ) {
                return@FakeTransport AppResult.Ok(
                    FakeTransport.response(200, bodies.getValue("search"))
                )
            }
            if (chapterAlias.containsMatchIn(url)) {
                return@FakeTransport AppResult.Ok(
                    FakeTransport.response(200, bodies.getValue("chapter"))
                )
            }
            AppResult.Ok(FakeTransport.response(404, bodies.getValue("notfound")))
        }
    }

    private fun prober(transport: FakeTransport): LinkProber {
        val factory = HostContextFactory(
            ExtensionStore(tmp.newFolder()), testSessionStore(), transport
        )
        return LinkProber(factory, testSessionStore())
    }

    @Test(timeout = 900_000)
    fun survey() {
        val dir = captureDir()
        assumeTrue("novelbuddy capture not on disk", dir != null)
        loadBodies(dir!!)

        // ── stage-isolated inference ──
        val homeDoc = Jsoup.parse(bodies.getValue("home"), "https://novelbuddy.me/")
        val latestDoc = Jsoup.parse(bodies.getValue("latest"), "https://novelbuddy.me/latest")
        val bookDoc = Jsoup.parse(bodies.getValue("book"), bookUrl)
        val chapterDoc = Jsoup.parse(bodies.getValue("chapter"), chapterUrl)

        stage("analyseListingPage(home)") {
            LinkInference.analyseListingPage(homeDoc, "https://novelbuddy.me/", bookUrl)
                ?.let { "count=${it.count} sel=${it.resultsSelector}" } ?: "null"
        }
        stage("analyseListingPage(latest)") {
            LinkInference.analyseListingPage(latestDoc, "https://novelbuddy.me/latest", bookUrl)
                ?.let { "count=${it.count} sel=${it.resultsSelector}" } ?: "null"
        }
        stage("analyseBookPage(book)") {
            LinkInference.analyseBookPage(bookDoc, bookUrl)
                ?.let { "title=${it.title} chapters=${it.chapterCount} first=${it.firstChapterUrl}" }
                ?: "null"
        }
        stage("analyseChapterPage(chapter)") {
            LinkInference.analyseChapterPage(chapterDoc, chapterUrl)
                ?.let { "sel=${it.contentSelector} textLen=${it.textLength}" } ?: "null"
        }
        stage("isJsShell(home)") { LinkInference.isJsShell(homeDoc) }
        stage("needsLogin(home)") { LinkInference.needsLogin(homeDoc, "https://novelbuddy.me/") }

        // ── the probe, per entry ──
        val entries = listOf(
            "book" to bookUrl,
            "root" to "https://novelbuddy.me/",
            "latest" to "https://novelbuddy.me/latest",
            "book-com" to "https://novelbuddy.com/eternal-holy-emperor",
            "root-com" to "https://novelbuddy.com/"
        )
        for ((label, url) in entries) {
            stage("probe[$label] $url") {
                val outcome = runBlocking { prober(transport()).probe(url, aiAvailable = false) }
                when (outcome) {
                    is LinkProber.Outcome.Ready ->
                        "Ready title='${outcome.draft.testDrive.bookTitle}' " +
                            "chapters=${outcome.draft.testDrive.chapterCount} " +
                            "textLen=${outcome.draft.testDrive.chapterTextLength} " +
                            "searchVerified=${outcome.draft.testDrive.searchVerified}"
                    is LinkProber.Outcome.Failed ->
                        "Failed: ${outcome.message} | stages=" +
                            outcome.stageLines.joinToString(" ;; ") { it.render() }
                    is LinkProber.Outcome.LoginRequired ->
                        "LoginRequired ${outcome.loginUrl}"
                }
            }
        }

        // ── the repo-add flow, site pasted as a repo ──
        val repoDao = FakeExtensionRepoDao()
        val extDao = FakeInstalledExtensionDao()
        val store = ExtensionStore(tmp.newFolder())
        val sessionStore = testSessionStore()
        val repoTransport = transport()
        val factory = HostContextFactory(store, sessionStore, repoTransport)
        val service = ExtensionService(
            extDao, store, factory, FakeDexLoader()
        ) { FakeJsRuntime() }
        val manager = RepoManager(repoDao, extDao, store, repoTransport, service, sessionStore)
        for (paste in listOf(
            "https://novelbuddy.com", "https://novelbuddy.me",
            "https://novelbuddy.me/", bookUrl, "novelbuddy.me"
        )) {
            stage("addRepoOrDetect($paste)") {
                val outcome = runBlocking { manager.addRepoOrDetect("NovelBuddy", paste) }
                when (outcome) {
                    is RepoManager.AddRepoOutcome.Added -> "Added ${outcome.repo.indexUrl}"
                    is RepoManager.AddRepoOutcome.Foreign -> "Foreign $outcome"
                    is RepoManager.AddRepoOutcome.Failed -> "Failed ${outcome.error}"
                }
            }
        }

        val outDir = File("build-logs/ws2-raw").apply { mkdirs() }
        // user.dir is the app module dir under gradle; walk up to the xreader root.
        var root: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val base = root ?: return@repeat
            if (File(base, "fixtures-capture").isDirectory) {
                File(base, "build-logs/ws2-raw/novelbuddy-survey.txt")
                    .writeText(log.toString())
                return@repeat
            }
            root = base.parentFile
        }
        File(outDir, "novelbuddy-survey.txt").writeText(log.toString())
        assertTrue(log.isNotEmpty())
    }
}
