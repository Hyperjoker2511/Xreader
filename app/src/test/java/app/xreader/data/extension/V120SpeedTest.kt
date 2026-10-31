package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeBookDao
import app.xreader.data.FakeChapterDao
import app.xreader.data.FakeChapterSourceMapDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.data.db.Book
import app.xreader.data.db.Chapter
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.SourceType
import app.xreader.data.download.FetchOutcome
import app.xreader.data.download.RoomChapterSourceResolver
import app.xreader.data.download.UrlChapterFetcher
import app.xreader.data.files.BookStorage
import app.xreader.data.net.HttpTransport
import app.xreader.domain.content.ChapterBlock
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceChapter
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.2.0 (Phase B, Worker A) — the chapter-speed pins:
 *
 * - B1: byte fetches are paced by the 60 ms BYTE limiter, never
 *   the 350 ms page limiter (and pages still use the page one).
 * - B1/B2: the extension image pass fetches in parallel, skips
 *   images already on disk, tolerates a dead image, and still
 *   returns the complete stored map.
 * - A2: a cold Live book open fetches the book page exactly
 *   once; a cold Live chapter-only open fetches the chapter
 *   page exactly once.
 * - Audit #4: DeclarativeSource's content cache is a bounded
 *   LRU — past the cap the eldest entry (content AND parsed
 *   document) is gone, recent entries still hit.
 */
class V120SpeedTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── B1: the byte limiter ────────────────────────────────

    @Test
    fun `byte fetches are paced by the byte limiter not the page limiter`() =
        runBlocking {
            val pageDelays = mutableListOf<Long>()
            val byteDelays = mutableListOf<Long>()
            // A frozen clock: every limiter wait lands in full
            // in the recording delay function.
            val pageLimiter = RateLimiter(
                minIntervalMs = 350,
                nowMs = { 1_000L },
                delayMs = { pageDelays += it }
            )
            val byteLimiter = RateLimiter(
                minIntervalMs = ExtensionHttpClient.BYTE_MIN_INTERVAL_MS,
                nowMs = { 1_000L },
                delayMs = { byteDelays += it }
            )
            val transport = FakeTransport { FakeTransport.ok("x") }
            val client = ExtensionHttpClient(
                transport = transport,
                sessionStore = testSessionStore(),
                sessionKey = "byte-test",
                label = "Test Source",
                whitelist = {
                    ExtensionPermissions(domains = listOf("novels.example"))
                },
                rateLimiter = pageLimiter,
                byteRateLimiter = byteLimiter
            )
            client.getBytes("https://novels.example/img/a.png")
            client.getBytes("https://novels.example/img/b.png")
            assertEquals(
                "the second byte fetch must wait on the BYTE limiter",
                listOf(ExtensionHttpClient.BYTE_MIN_INTERVAL_MS),
                byteDelays
            )
            assertTrue(
                "byte fetches must never touch the page limiter: $pageDelays",
                pageDelays.isEmpty()
            )
            // And pages still pay the page limiter, only that one.
            client.get("https://novels.example/book/1")
            client.get("https://novels.example/book/2")
            assertEquals(listOf(350L), pageDelays)
            assertEquals(
                listOf(ExtensionHttpClient.BYTE_MIN_INTERVAL_MS),
                byteDelays
            )
        }

    // ── audit #4: the bounded content cache ─────────────────

    @Test
    fun `content cache evicts the eldest entry beyond the cap`() {
        val transport = FakeTransport { request ->
            FakeTransport.ok(
                """
                <html><body><div class="content">
                <p>Readable text for ${request.url}, long enough to be
                a paragraph of a chapter that a reader could read.</p>
                </div></body></html>
                """.trimIndent()
            )
        }
        // Same construction as testContext, but with a zero
        // page limiter pre-seeded for row 1: this test makes
        // 30 page fetches and must not pay 350 ms apiece.
        val factory = HostContextFactory(
            store = ExtensionStore(tmp.newFolder()),
            sessionStore = testSessionStore(),
            transport = transport,
            rateLimiters = mutableMapOf(1L to RateLimiter(minIntervalMs = 0))
        )
        val context = factory.contextFor(
            extensionRowId = 1,
            packageId = "test.pkg",
            label = "Test Source",
            permissions = {
                ExtensionPermissions(domains = listOf(FixtureSite.HOST))
            }
        )
        val source = DeclarativeSource(FixtureSite.config(), context)
        val total = DeclarativeSource.MAX_CONTENT_CACHE_ENTRIES + 6
        val ids = (1..total).map {
            "https://novels.example/book/sword-saga/chapter-$it"
        }
        for (id in ids) {
            source.chapterContent(SourceChapter(id = id, name = "c", number = null))
        }
        assertNull(
            "the eldest chapter must be evicted past the cap",
            source.contentOf(ids.first())
        )
        assertNull(
            "its parsed document shares the cache, so it evicts too",
            source.documentOf(ids.first())
        )
        assertNotNull(
            "the most recent chapter must still hit",
            source.contentOf(ids.last())
        )
        assertNotNull(source.documentOf(ids.last()))
    }

    // ── A2: Live one-fetch threading ────────────────────────

    companion object {
        private const val LIVE_BASE = "https://shelf.example"
        private const val LIVE_BOOK = "$LIVE_BASE/novels/alpha-flame"
        private const val LIVE_CH1 = "$LIVE_BOOK/1"

        private val LIVE_BOOK_PAGE = """
            <html><body><main>
              <h1>Alpha Flame</h1>
              <img src="/covers/alpha.jpg" alt="Alpha Flame"/>
              <div class="chapters">
                <a href="/novels/alpha-flame/3">Chapter 3</a>
                <a href="/novels/alpha-flame/2">Chapter 2</a>
                <a href="/novels/alpha-flame/1">Chapter 1</a>
              </div>
            </main></body></html>
        """.trimIndent()

        private const val LIVE_PROSE =
            "The harbour bell counted the hour before the fog had fully " +
                "lifted, and Wren stood at the end of the pier with her " +
                "brother's map folded small in her pocket. Three winters " +
                "the lighthouse had stood dark, and every soul in the " +
                "village had a theory about the night it went out. She " +
                "had come home to prove the kindest of them wrong. The " +
                "tide dragged at the pylons, gulls quarrelled over the " +
                "fish crates, and somewhere behind her a door opened " +
                "and closed without a voice to follow it."

        private val LIVE_CHAPTER_PAGE = """
            <html><body><main><article>
              <h1>Chapter 1</h1>
              <p>$LIVE_PROSE</p><p>$LIVE_PROSE</p>
            </article></main></body></html>
        """.trimIndent()
    }

    private fun liveRig(): Pair<LiveSource, FakeTransport> {
        val transport = FakeTransport { request ->
            when (request.url) {
                LIVE_BOOK -> FakeTransport.ok(LIVE_BOOK_PAGE)
                LIVE_CH1, "$LIVE_BOOK/2", "$LIVE_BOOK/3" ->
                    FakeTransport.ok(LIVE_CHAPTER_PAGE)
                else -> AppResult.Ok(FakeTransport.response(404, "not found"))
            }
        }
        val context = testContext(
            transport = transport,
            filesDir = tmp.newFolder(),
            permissions = ExtensionPermissions(domains = listOf("shelf.example"))
        )
        val source = LiveSource(
            LiveSourceSpec(
                id = "live.shelf-example",
                name = "Shelf",
                baseUrl = LIVE_BASE,
                startUrl = "$LIVE_BASE/"
            ),
            context
        )
        return source to transport
    }

    @Test
    fun `cold live book open fetches the book page exactly once`() {
        val (source, transport) = liveRig()
        val (details, chapters) = source.detailsAndChapters(
            SourceBook(id = LIVE_BOOK, title = "Alpha Flame")
        )
        assertEquals("Alpha Flame", details.title)
        assertEquals(3, chapters.size)
        assertEquals(
            "book page fetches: ${transport.requests.map { it.url }}",
            1,
            transport.requests.count { it.url == LIVE_BOOK }
        )
    }

    @Test
    fun `cold live chapter open fetches the chapter page exactly once`() {
        val (source, transport) = liveRig()
        val content = source.chapterContent(
            SourceChapter(id = LIVE_CH1, name = "Chapter 1", number = 1f)
        )
        assertTrue(content.html.contains("harbour bell"))
        assertEquals(
            "chapter page fetches: ${transport.requests.map { it.url }}",
            1,
            transport.requests.count { it.url == LIVE_CH1 }
        )
    }

    // ── B1/B2: the extension image pass ─────────────────────

    private class ImageRig(
        val resolver: ExtensionChapterResolver,
        val transport: FakeTransport,
        val storage: BookStorage,
        val bookId: Long,
        val chapterDao: FakeChapterDao
    )

    private fun bytesOk(bytes: ByteArray): AppResult<HttpTransport.Response> =
        AppResult.Ok(
            HttpTransport.Response(
                code = 200,
                headers = mapOf("Content-Length" to bytes.size.toString()),
                body = bytes.inputStream(),
                isPartialContent = false
            )
        )

    /** The V1062 plugin-images rig, parameterised: chapter 2 of
     * the fixture book serves [chapterHtml]; [respondImage] gets
     * first refusal on every request (null = not an image). */
    private suspend fun imageRig(
        chapterHtml: String,
        storageName: String,
        respondImage: suspend (HttpTransport.Request) ->
            AppResult<HttpTransport.Response>?
    ): ImageRig {
        val bookDao = FakeBookDao()
        val chapterDao = FakeChapterDao()
        val mapDao = FakeChapterSourceMapDao()
        val extDao = FakeInstalledExtensionDao()
        val pages = FixtureSite.pages() + mapOf(
            FixtureSite.CHAPTER_2_URL to chapterHtml
        )
        val transport = FakeTransport { request ->
            respondImage(request) ?: run {
                val body = pages[request.url]
                if (body == null) {
                    AppResult.Ok(FakeTransport.response(404, "not found"))
                } else {
                    FakeTransport.ok(body)
                }
            }
        }
        val filesDir = tmp.newFolder()
        val store = ExtensionStore(filesDir)
        val factory = HostContextFactory(store, testSessionStore(), transport)
        val service = ExtensionService(extDao, store, factory, FakeDexLoader()) {
            FakeJsRuntime()
        }
        val cleanerStore = CleanerRuleStore(store)
        store.writeText(
            store.definitionFile("test.novels"),
            DeclarativeFingerprints.encode(listOf(FixtureSite.config()))
        )
        val extId = extDao.insert(
            InstalledExtension(
                repoId = 1,
                displayName = "Novels Example",
                packageId = "test.novels",
                versionName = "1.0",
                versionCode = 1,
                kind = app.xreader.data.db.ExtensionKind.DECLARATIVE,
                artifactPath = null,
                sha256 = DeclarativeFingerprints.of(listOf(FixtureSite.config())),
                apiLevel = 1,
                languages = "en",
                sourceIds = "novels-example",
                installedAt = 0,
                updatedAt = 0,
                permissionsJson = kotlinx.serialization.json.Json.encodeToString(
                    ExtensionPermissions.serializer(),
                    ExtensionPermissions(domains = listOf(FixtureSite.HOST))
                )
            )
        )
        val bookId = bookDao.insert(
            Book(
                title = "Sword Saga",
                sourceType = SourceType.EXTENSION,
                extensionId = extId,
                remoteId = SourceBookIds.encode(
                    "novels-example", FixtureSite.BOOK_URL
                ),
                addedAt = 0,
                updatedAt = 0
            )
        )
        chapterDao.insert(
            Chapter(
                bookId = bookId, index = 1, title = "Chapter 2: The Road",
                remoteId = FixtureSite.CHAPTER_2_URL, updatedAt = 0
            )
        )
        val storage = BookStorage(tmp.newFolder(storageName))
        val resolver = ExtensionChapterResolver(
            bookDao, mapDao, service, cleanerStore,
            fallback = RoomChapterSourceResolver(mapDao) {
                UrlChapterFetcher(transport)
            },
            storage = storage
        )
        return ImageRig(resolver, transport, storage, bookId, chapterDao)
    }

    private suspend fun fetchRigChapter(rig: ImageRig): FetchOutcome {
        val chapter = rig.chapterDao.forBook(rig.bookId).first()
        val candidates = rig.resolver.candidatesFor(chapter)
        assertEquals(1, candidates.size)
        val resolved = candidates.first()
        return resolved.fetcher.fetch(resolved.candidate)
    }

    @Test
    fun `image pass skips files already on disk and tolerates a dead image`() =
        runBlocking {
            val servedB = byteArrayOf(0x42, 1, 2, 3)
            val sentinelA = byteArrayOf(0x41, 9, 9, 9)
            val chapterHtml = """
                <html><head><title>Chapter 2</title></head><body>
                <div class="content">
                  <p>Chapter two text, on the road, travelling far from home.</p>
                  <img src="/img/a.png" alt="First"/>
                  <img src="/img/b.png" alt="Second"/>
                  <img src="/img/c.png" alt="Third"/>
                  <p>After the pictures, the road bends north.</p>
                </div>
                </body></html>
            """.trimIndent()
            val rig = imageRig(chapterHtml, "skip-rig") { request ->
                when (request.url) {
                    "${FixtureSite.BASE}/img/a.png" -> bytesOk(byteArrayOf(0x0))
                    "${FixtureSite.BASE}/img/b.png" -> bytesOk(servedB)
                    "${FixtureSite.BASE}/img/c.png" ->
                        AppResult.Ok(FakeTransport.response(404, "gone"))
                    else -> null
                }
            }
            // a.png is already downloaded from an earlier read.
            val dir = rig.storage.contentDir(rig.bookId)
            File(dir, "img-a.png").writeBytes(sentinelA)

            val outcome = fetchRigChapter(rig)
            assertTrue("fetch failed: $outcome", outcome is FetchOutcome.Success)
            val content = (outcome as FetchOutcome.Success).content
            val imageNames = content.blocks
                .filterIsInstance<ChapterBlock.Image>()
                .map { it.fileName }
                .toSet()
            assertEquals(
                "stored + fetched images resolve; the dead one drops",
                setOf("a.png", "b.png"),
                imageNames
            )
            val imageRequests = rig.transport.requests
                .map { it.url }
                .filter { it.contains("/img/") }
            assertTrue(
                "the on-disk image must NOT be fetched again: $imageRequests",
                imageRequests.none { it.endsWith("/img/a.png") }
            )
            assertEquals(
                setOf(
                    "${FixtureSite.BASE}/img/b.png",
                    "${FixtureSite.BASE}/img/c.png"
                ),
                imageRequests.toSet()
            )
            assertTrue(
                "the on-disk file must not be overwritten",
                sentinelA.contentEquals(File(dir, "img-a.png").readBytes())
            )
            assertTrue(servedB.contentEquals(File(dir, "img-b.png").readBytes()))
            assertTrue(content.plainText().contains("the road bends north"))
        }

    @Test
    fun `image pass fetches in parallel and returns every stored image`() =
        runBlocking {
            val names = (1..5).map { "p$it.png" }
            val chapterHtml = """
                <html><head><title>Chapter 2</title></head><body>
                <div class="content">
                  <p>Chapter two text, on the road, travelling far from home.</p>
                  ${names.joinToString("\n") { "<img src=\"/img/$it\"/>" }}
                  <p>After the pictures, the road bends north.</p>
                </div>
                </body></html>
            """.trimIndent()
            val inFlight = AtomicInteger(0)
            val maxInFlight = AtomicInteger(0)
            val rig = imageRig(chapterHtml, "parallel-rig") { request ->
                if (request.url.contains("/img/")) {
                    val now = inFlight.incrementAndGet()
                    while (true) {
                        val current = maxInFlight.get()
                        if (now <= current ||
                            maxInFlight.compareAndSet(current, now)
                        ) {
                            break
                        }
                    }
                    delay(250)
                    inFlight.decrementAndGet()
                    bytesOk(byteArrayOf(0x50, 1))
                } else {
                    null
                }
            }
            val outcome = fetchRigChapter(rig)
            assertTrue("fetch failed: $outcome", outcome is FetchOutcome.Success)
            val content = (outcome as FetchOutcome.Success).content
            val imageNames = content.blocks
                .filterIsInstance<ChapterBlock.Image>()
                .map { it.fileName }
                .toSet()
            assertEquals(names.toSet(), imageNames)
            assertTrue(
                "image fetches must overlap (max in flight was " +
                    "${maxInFlight.get()}) — a sequential pass never " +
                    "exceeds 1",
                maxInFlight.get() >= 2
            )
            val dir = rig.storage.contentDir(rig.bookId)
            for (name in names) {
                assertTrue("missing stored file img-$name", File(dir, "img-$name").isFile)
            }
        }
}
