package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeBookDao
import app.xreader.data.FakeChapterDao
import app.xreader.data.FakeChapterSourceMapDao
import app.xreader.data.FakeExtensionRepoDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.data.files.BookStorage
import app.xreader.data.db.Book
import app.xreader.data.db.Chapter
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.SourceType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.0 (Part A4/A5 + steer A6/A7): the stale-chapter
 * re-link and the multi-release rebind.
 *
 * - The test-drive's TEXT leg, served a chapter list whose
 *   addresses have gone stale (the link-Asura "Overgeared"
 *   / link-Comix field exhibits), refreshes the list ONCE
 *   and re-samples instead of failing the leg.
 * - A library book bound to a release with NO chapters
 *   rebinds to the same-title release that has them (the
 *   Comix multi-release exhibit).
 * - A refresh that comes back with an empty list NEVER
 *   overwrites a stored list (zero chapters is never
 *   stored as success).
 */
class V150RelinkRebindTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── A4: the test-drive re-link ──────────────────────────

    private object StaleSite {
        const val HOST = "stale.example"
        const val BASE = "https://stale.example"
        const val BOOK_URL = "$BASE/book/alpha"

        val prose = listOf(
            "The ferryman counted his coins twice before he " +
                "trusted the sum, then tucked the purse inside " +
                "his coat and looked out at the black water.",
            "Rain had swollen the river for three days, and the " +
                "ferry rope sang taut whenever the current " +
                "caught the hull broadside on.",
            "In the town behind him the lamps were going out " +
                "one by one, shutter by shutter, until only the " +
                "watchman's lantern moved along the wall.",
            "He thought about the letter in his boot, the one " +
                "he had carried unopened across two provinces " +
                "because the seal was not his to break.",
            "Tomorrow he would deliver it, and the woman at " +
                "the mill would finally learn why her brother " +
                "had never come home from the war."
        ).joinToString("\n")

        fun bookPage(stale: Boolean): String {
            val hrefs = if (stale) {
                listOf("/book/alpha/c2-old", "/book/alpha/c1-old")
            } else {
                listOf("/book/alpha/chapter-2", "/book/alpha/chapter-1")
            }
            val rows = hrefs.mapIndexed { i, href ->
                "<li><a href=\"$href\">Chapter ${2 - i}</a></li>"
            }.joinToString("\n")
            return """
                <html><head><title>Alpha - Stale</title>
                <meta name="description" content="The alpha book."/>
                </head><body>
                <h1>Alpha</h1>
                <ul class="chapters">$rows</ul>
                </body></html>
            """.trimIndent()
        }

        fun chapterPage(): String {
            val paras = prose.split("\n")
                .joinToString("\n") { "<p>$it</p>" }
            return """
                <html><head><title>Chapter</title></head><body>
                <div class="content">$paras</div>
                </body></html>
            """.trimIndent()
        }

        fun config(): DeclarativeConfig = DeclarativeConfig(
            id = "stale-test",
            name = "Stale Test",
            baseUrl = BASE,
            popular = ListSpec(
                url = "$BASE/popular?page={page}",
                results = "div.result",
                title = "a.t",
                link = "a.t@href"
            ),
            details = DetailsSpec(
                title = "h1",
                description = "meta[name=description]@content",
                chapters = ChapterListSpec(
                    list = "ul.chapters > li",
                    name = "a",
                    link = "a@href",
                    reverse = true
                )
            ),
            chapter = ChapterSpec(content = "div.content"),
            sampleBookUrl = BOOK_URL
        )
    }

    @Test
    fun `the test drive refreshes a stale chapter list before judging text`() =
        runBlocking {
            var staleServed = true
            val requestLog = mutableListOf<String>()
            val popularPage = """
                <html><body><div class="results">
                <div class="result">
                  <a class="t" href="/book/alpha">Alpha</a>
                </div></div></body></html>
            """.trimIndent()
            val transport = FakeTransport { request ->
                val url = request.url
                requestLog += url
                when {
                    url.contains("-old") -> {
                        // The stale address 404s; from here
                        // the site serves the fresh list.
                        staleServed = false
                        AppResult.Ok(
                            FakeTransport.response(404, "gone")
                        )
                    }
                    url == StaleSite.BOOK_URL ->
                        FakeTransport.ok(
                            StaleSite.bookPage(staleServed)
                        )
                    url.contains("/chapter-") ->
                        FakeTransport.ok(StaleSite.chapterPage())
                    url.startsWith("${StaleSite.BASE}/popular") ->
                        FakeTransport.ok(popularPage)
                    else -> AppResult.Ok(
                        FakeTransport.response(404, "no")
                    )
                }
            }
            val store = ExtensionStore(tmp.newFolder())
            val sessionStore = testSessionStore()
            val factory = HostContextFactory(store, sessionStore, transport)
            val extDao = FakeInstalledExtensionDao()
            val service = ExtensionService(
                extDao, store, factory, FakeDexLoader()
            ) { FakeJsRuntime() }
            val manager = RepoManager(
                FakeExtensionRepoDao(), extDao, store, transport,
                service, sessionStore
            )
            val saved = manager.saveLinkSource(
                StaleSite.config(), StaleSite.HOST
            )
            val row = (saved as AppResult.Ok).value
            val handle = (service.sourcesOf(row.id) as AppResult.Ok)
                .value.first()
            val verdict = ContractTester(service).test(handle)
            val text = verdict.results.first {
                it.capability == SourceCapability.TEXT
            }
            assertTrue(
                "TEXT leg should pass after the re-link: ${text.detail}" +
                    " | requests: $requestLog" +
                    " | all: ${verdict.results.map { it.capability to it.detail }}",
                text.ok
            )
            assertTrue(
                "the verdict should say the list was refreshed: " +
                    text.detail,
                text.detail.contains("refreshed")
            )
        }

    // ── A5/A7: rebind + the zero-chapter storage guard ─────

    private object TwinSite {
        const val HOST = "twin.example"
        const val BASE = "https://twin.example"
        const val REL_A = "$BASE/book/twin-a"
        const val REL_B = "$BASE/book/twin-b"

        fun config(): DeclarativeConfig = DeclarativeConfig(
            id = "twin",
            name = "Twin",
            baseUrl = BASE,
            popular = ListSpec(
                url = "$BASE/popular",
                results = "div.result",
                title = "a.t",
                link = "a.t@href"
            ),
            search = ListSpec(
                url = "$BASE/search?q={query}",
                results = "div.result",
                title = "a.t",
                link = "a.t@href"
            ),
            details = DetailsSpec(
                title = "h1",
                chapters = ChapterListSpec(
                    list = "ul.chapters > li",
                    name = "a",
                    link = "a@href",
                    reverse = true
                )
            ),
            chapter = ChapterSpec(content = "div.content"),
            sampleBookUrl = REL_B
        )

        fun bookPage(chapterCount: Int): String {
            val rows = (1..chapterCount).joinToString("\n") { n ->
                "<li><a href=\"/book/twin-b/chapter-$n\">" +
                    "Chapter $n</a></li>"
            }
            return """
                <html><head><title>Twin Tale</title></head><body>
                <h1>Twin Tale</h1>
                <ul class="chapters">$rows</ul>
                </body></html>
            """.trimIndent()
        }

        val searchPage = """
            <html><body><div class="results">
            <div class="result">
              <a class="t" href="/book/twin-a">Twin Tale</a>
            </div>
            <div class="result">
              <a class="t" href="/book/twin-b">Twin Tale</a>
            </div></div></body></html>
        """.trimIndent()
    }

    private class SyncWorld(
        val sync: SourceBookSync,
        val bookDao: FakeBookDao,
        val chapterDao: FakeChapterDao,
        val extId: Long
    )

    private suspend fun syncWorld(
        transport: FakeTransport
    ): SyncWorld {
        val bookDao = FakeBookDao()
        val chapterDao = FakeChapterDao()
        val mapDao = FakeChapterSourceMapDao()
        val extDao = FakeInstalledExtensionDao()
        val store = ExtensionStore(tmp.newFolder())
        val config = TwinSite.config()
        store.writeText(
            store.definitionFile("bundled.twin"),
            DeclarativeFingerprints.encode(listOf(config))
        )
        val extId = extDao.insert(
            InstalledExtension(
                repoId = 1,
                displayName = "Twin",
                packageId = "bundled.twin",
                versionName = "1.0",
                versionCode = 1,
                kind = app.xreader.data.db.ExtensionKind.DECLARATIVE,
                artifactPath = null,
                sha256 = DeclarativeFingerprints.of(listOf(config)),
                apiLevel = 1,
                languages = "en",
                sourceIds = "twin",
                installedAt = 0,
                updatedAt = 0,
                permissionsJson = Json.encodeToString(
                    ExtensionPermissions.serializer(),
                    ExtensionPermissions(
                        domains = listOf(TwinSite.HOST)
                    )
                )
            )
        )
        val service = ExtensionService(
            extDao, store,
            HostContextFactory(store, testSessionStore(), transport),
            FakeDexLoader()
        ) { FakeJsRuntime() }
        val sync = SourceBookSync(
            bookDao = bookDao,
            chapterDao = chapterDao,
            sourceMapDao = mapDao,
            extensionDao = extDao,
            service = service,
            storage = BookStorage(tmp.newFolder()),
            transport = transport,
            repairScheduler = {},
            nowMs = { 1_000L }
        )
        return SyncWorld(sync, bookDao, chapterDao, extId)
    }

    private fun storedBook(extId: Long, remoteUrl: String): Book =
        Book(
            title = "Twin Tale",
            author = "",
            coverPath = null,
            sourceType = SourceType.EXTENSION,
            extensionId = extId,
            remoteId = SourceBookIds.encode("twin", remoteUrl),
            totalChapters = 0,
            addedAt = 0,
            updatedAt = 0,
            inLibrary = true
        )

    @Test
    fun `a book on an empty release rebinds to the release with chapters`() =
        runBlocking {
            val transport = FakeTransport { request ->
                when (request.url) {
                    TwinSite.REL_A ->
                        FakeTransport.ok(TwinSite.bookPage(0))
                    TwinSite.REL_B ->
                        FakeTransport.ok(TwinSite.bookPage(3))
                    else -> {
                        if (request.url.startsWith(
                                "${TwinSite.BASE}/search"
                            )
                        ) {
                            FakeTransport.ok(TwinSite.searchPage)
                        } else {
                            AppResult.Ok(
                                FakeTransport.response(404, "no")
                            )
                        }
                    }
                }
            }
            val world = syncWorld(transport)
            val bookId = world.bookDao.insert(
                storedBook(world.extId, TwinSite.REL_A)
            )
            val book = world.bookDao.getById(bookId)!!
            val result = world.sync.refresh(book)
            assertTrue("refresh failed: $result", result is AppResult.Ok)
            val after = world.bookDao.getById(bookId)!!
            assertEquals(
                SourceBookIds.encode("twin", TwinSite.REL_B),
                after.remoteId
            )
            assertEquals(
                3, world.chapterDao.forBook(bookId).size
            )
        }

    @Test
    fun `an empty refresh never overwrites a stored chapter list`() =
        runBlocking {
            val transport = FakeTransport { request ->
                when (request.url) {
                    TwinSite.REL_B ->
                        FakeTransport.ok(TwinSite.bookPage(0))
                    else -> AppResult.Ok(
                        FakeTransport.response(404, "no")
                    )
                }
            }
            val world = syncWorld(transport)
            val bookId = world.bookDao.insert(
                storedBook(world.extId, TwinSite.REL_B)
                    .copy(totalChapters = 3)
            )
            world.chapterDao.insertAll(
                (1..3).map { n ->
                    Chapter(
                        bookId = bookId,
                        index = n - 1,
                        title = "Chapter $n",
                        remoteId = "${TwinSite.REL_B}/chapter-$n",
                        updatedAt = 0
                    )
                }
            )
            val book = world.bookDao.getById(bookId)!!
            val result = world.sync.refresh(book)
            assertTrue("refresh failed: $result", result is AppResult.Ok)
            assertEquals(
                3, world.chapterDao.forBook(bookId).size
            )
            assertEquals(
                3, world.bookDao.getById(bookId)!!.totalChapters
            )
        }
}
