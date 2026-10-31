package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeExtensionRepoDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.2.2 investigation, second evidence round (2026-10-31):
 *
 * (A) novelbuddy.me — the user's phone SAVED a link source for
 * it, but opening the saved source failed ("didn't show a book
 * list or a book page Xreader can read") and its name was the
 * site's raw page title. This replays the exact device path in
 * the harness: probe → saveLinkSource → reload through
 * ExtensionService → ContractTester (the same full-path drive
 * the source page's test-drive runs).
 *
 * (B) botitranslation — Repair reported "test-drive passed"
 * and saved rebuilt rules, after which the standalone
 * test-drive failed Book list with "has no catalogue page".
 * This confirms the mechanism end-to-end: a saved config with
 * no catalogue specs (popular/latest/search all null) fails
 * the contract tester's LIST step with exactly that message,
 * before any fetch — a pass-gate mismatch, not a render
 * persistence failure (needsRendering IS serialized on the
 * config, set on rendered drafts, and honoured by the source
 * stack).
 *
 * Output: build-logs/ws2-raw/saved-source-survey.txt
 *
 * v1.2.3 UPDATE: this survey now documents the FIXED chain —
 * (A) prints a draft WITH a catalogue (the /latest hunt +
 * embedded-JSON search) and a passing contract, and (B)
 * asserts the catalogue-less save is refused outright.
 */
class V122SavedSourceSurveyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val out = StringBuilder()
    private fun line(s: String) {
        out.append(s).append('\n')
        println(s)
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

    /** Novelbuddy captures, with every book page aliased to the
     * one captured book and every chapter to the one captured
     * chapter (the catalogue lists 24 books; only one book's
     * pages were captured — the aliasing exercises the real
     * parse path for whichever book the drive picks). */
    private fun novelbuddyTransport(dir: File): FakeTransport {
        fun read(name: String) = File(dir, name).readText(Charsets.UTF_8)
        val bodies = mapOf(
            "home" to read("home.html"),
            "latest" to read("latest.html"),
            "book" to read("book.html"),
            "chapter" to read("chapter.html"),
            "search" to read("search.html"),
            "notfound" to read("browse.html")
        )
        val bookShape = Regex("https://novelbuddy\\.me/[a-z0-9][a-z0-9-]*")
        val chapterShape =
            Regex("https://novelbuddy\\.me/[a-z0-9-]+/chapter-.*")
        val chrome = setOf(
            "https://novelbuddy.me/latest", "https://novelbuddy.me/ranking",
            "https://novelbuddy.me/search", "https://novelbuddy.me/browse"
        )
        return FakeTransport { request ->
            val url = request.url
            if (request.method != "GET") {
                return@FakeTransport AppResult.Ok(
                    FakeTransport.response(404, bodies.getValue("notfound"))
                )
            }
            val body = when {
                url == "https://novelbuddy.me" ||
                    url == "https://novelbuddy.me/" -> bodies.getValue("home")
                url == "https://novelbuddy.me/latest" ->
                    bodies.getValue("latest")
                url.substringBefore('?') == "https://novelbuddy.me/search" ->
                    bodies.getValue("search")
                chapterShape.matches(url) -> bodies.getValue("chapter")
                bookShape.matches(url) && url !in chrome ->
                    bodies.getValue("book")
                else -> null
            }
            if (body == null) {
                AppResult.Ok(
                    FakeTransport.response(404, bodies.getValue("notfound"))
                )
            } else {
                FakeTransport.ok(body)
            }
        }
    }

    private class Stack(
        val manager: RepoManager,
        val service: ExtensionService,
        val prober: LinkProber
    )

    private fun stack(transport: FakeTransport): Stack {
        val repoDao = FakeExtensionRepoDao()
        val extDao = FakeInstalledExtensionDao()
        val store = ExtensionStore(tmp.newFolder())
        val sessionStore = SessionStore(testVault())
        val factory = HostContextFactory(store, sessionStore, transport)
        val service = ExtensionService(
            extDao, store, factory, FakeDexLoader()
        ) { FakeJsRuntime() }
        val manager = RepoManager(
            repoDao, extDao, store, transport, service, sessionStore
        )
        return Stack(manager, service, LinkProber(factory, sessionStore))
    }

    @Test(timeout = 900_000)
    fun survey() = runBlocking {
        val dir = captureDir()
        assumeTrue("novelbuddy capture not on disk", dir != null)

        // ── (A) novelbuddy: probe → save → contract drive ──
        val nb = stack(novelbuddyTransport(dir!!))
        val outcome = nb.prober.probe("https://novelbuddy.me/eternal-holy-emperor")
        line("NB probe outcome: ${outcome.javaClass.simpleName}")
        if (outcome is LinkProber.Outcome.Ready) {
            val cfg = outcome.draft.config
            line("NB draft name: ${cfg.name}")
            line("NB draft id: ${cfg.id}")
            line("NB popular: ${cfg.popular?.url}")
            line("NB latest: ${cfg.latest?.url}")
            line("NB search: ${cfg.search?.url} (needsSetup=${cfg.searchNeedsSetup})")
            line("NB needsRendering: ${cfg.needsRendering}")
            line("NB sampleBookUrl: ${cfg.sampleBookUrl}")
            line("NB drive: chapters=${outcome.draft.testDrive.chapterCount} " +
                "searchVerified=${outcome.draft.testDrive.searchVerified}")
            val saved = nb.manager.saveLinkSource(cfg, outcome.draft.host)
            line("NB save: $saved")
            if (saved is AppResult.Ok) {
                val handles = nb.service.sourcesOf(saved.value.id).getOrNull()
                val handle = handles?.firstOrNull()
                line("NB handles: ${handles?.size} first=${handle?.source?.id}")
                if (handle != null) {
                    val reloaded = nb.service.declarativeConfigOf(handle)
                    line("NB reloaded name: ${reloaded?.name}")
                    line("NB reloaded popular: ${reloaded?.popular?.url}")
                    line("NB reloaded latest: ${reloaded?.latest?.url}")
                    val verdict = ContractTester(nb.service).test(handle)
                    verdict.results.forEach { r ->
                        line("NB contract ${r.capability}: ok=${r.ok} — ${r.detail}")
                    }
                }
            }
        }

        // ── (B) boti mechanism: catalogue-less config ────────
        val botiConfig = DeclarativeConfig(
            id = "boti-like",
            name = "BotiLike",
            baseUrl = "https://boti.example",
            details = DetailsSpec(
                title = "h1",
                chapters = ChapterListSpec(
                    list = "ul#chapter-list > li",
                    name = "a",
                    link = "a@href",
                    reverse = true
                )
            ),
            chapter = ChapterSpec(content = "#chapter-content")
        )
        val boti = stack(FakeTransport {
            AppResult.Ok(FakeTransport.response(404, "not found"))
        })
        val botiSaved = boti.manager.saveLinkSource(botiConfig, "boti.example")
        line("BOTI save: $botiSaved")
        // v1.2.3 POST-FIX: the catalogue-less config is REFUSED
        // at the save path — the mechanism that produced the
        // on-device failure can no longer persist anything.
        // (Pre-fix this asserted the LIST failure of the saved
        // config; that state is now unreachable through save.)
        assertTrue(
            "v1.2.3: a catalogue-less config must be refused at save",
            botiSaved is AppResult.Err
        )
        if (botiSaved is AppResult.Ok) {
            val handle = boti.service.sourcesOf(botiSaved.value.id)
                .getOrNull()?.firstOrNull()
            if (handle != null) {
                val verdict = ContractTester(boti.service).test(handle)
                verdict.results.forEach { r ->
                    line("BOTI contract ${r.capability}: ok=${r.ok} — ${r.detail}")
                }
                val list = verdict.results.firstOrNull {
                    it.capability == SourceCapability.LIST
                }
                assertTrue(
                    "LIST step must fail with the catalogue-page message, was: ${list?.detail}",
                    list != null && !list.ok &&
                        list.detail.contains("has no catalogue page")
                )
            }
        }

        val logsDir = File("build-logs/ws2-raw")
        // user.dir is the module dir; the repo root is two up.
        val root = File(System.getProperty("user.dir") ?: ".")
            .absoluteFile.parentFile?.parentFile
        val target = File(root, "build-logs/ws2-raw/saved-source-survey.txt")
        target.parentFile?.mkdirs()
        target.writeText(out.toString())
        Unit
    }
}
