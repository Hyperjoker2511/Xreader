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
import app.xreader.data.download.ChapterFetcher
import app.xreader.data.download.FetchOutcome
import app.xreader.data.download.RoomChapterSourceResolver
import app.xreader.data.download.UrlChapterFetcher
import app.xreader.data.files.BookStorage
import app.xreader.data.net.HttpTransport
import app.xreader.domain.content.ChapterBlock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.4 (WS-5, item 22): the image-failure RECORD's economy.
 * A chapter whose images all fail (530 host) records the failed
 * URL set beside its stored content; a streamed re-open (the
 * reader's road — e.g. after the stored content was evicted)
 * does NOT re-attempt the recorded failures, rendering the
 * placeholders from the record; the download road
 * (fetchPageFiles) IGNORES the record and retries, so a revived
 * host heals the chapter with real images and the record dies.
 *
 * Both directions are asserted: pre-fix, the second streamed
 * fetch re-hammers the dead host (request count doubles) and
 * the content carries no image slots at all.
 */
class V154ImageFailureEconomyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val base = "https://novels.example"
    private val bookUrl = "$base/book/dead-tale"
    private val chapterUrl = "$base/book/dead-tale/chapter-1"
    private val imageUrls = listOf("a.png", "b.png", "c.png")
        .map { "$base/img/$it" }
    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 5, 6, 7)

    private val config = DeclarativeConfig(
        id = "deadimg",
        name = "Dead Images",
        baseUrl = base,
        popular = ListSpec(
            url = "$base/popular",
            results = "div.result",
            title = "a.t",
            link = "a.t@href"
        ),
        details = DetailsSpec(
            title = "h1",
            chapters = ChapterListSpec(
                list = "ul.chapters li a",
                name = "@text",
                link = "@href"
            )
        ),
        chapter = ChapterSpec(content = "div.content")
    )

    private val chapterPage = """
        <html><head><title>Chapter 1</title></head><body>
        <div class="content">
          <p>The road ran north through pine country, and the
          traveller walked it with a map he did not trust.</p>
          <img src="/img/a.png" alt="First"/>
          <p>Beyond the pass, the valley opened wide.</p>
          <img src="/img/b.png" alt="Second"/>
          <p>The river bent away toward the sea at last.</p>
          <img src="/img/c.png" alt="Third"/>
        </div>
        </body></html>
    """.trimIndent()

    private class Rig(
        val bookId: Long,
        val storage: BookStorage,
        val streamed: ChapterFetcher,
        val downloading: ChapterFetcher,
        val candidate: app.xreader.data.download.ChapterCandidate,
        val imageRequests: AtomicInteger,
        val healed: AtomicBoolean
    )

    private fun rig(): Rig = runBlocking {
        val bookDao = FakeBookDao()
        val chapterDao = FakeChapterDao()
        val mapDao = FakeChapterSourceMapDao()
        val extDao = FakeInstalledExtensionDao()
        val filesDir = tmp.newFolder()
        val store = ExtensionStore(filesDir)
        val imageRequests = AtomicInteger(0)
        val healed = AtomicBoolean(false)
        val transport = FakeTransport { request ->
            when {
                request.url == chapterUrl -> FakeTransport.ok(chapterPage)
                request.url in imageUrls -> {
                    imageRequests.incrementAndGet()
                    if (healed.get()) {
                        AppResult.Ok(
                            HttpTransport.Response(
                                code = 200,
                                headers = mapOf(
                                    "Content-Length" to
                                        pngBytes.size.toString()
                                ),
                                body = pngBytes.inputStream(),
                                isPartialContent = false
                            )
                        )
                    } else {
                        AppResult.Ok(
                            FakeTransport.response(530, "error code: 1016")
                        )
                    }
                }
                else -> AppResult.Ok(FakeTransport.response(404, "no"))
            }
        }
        val service = ExtensionService(
            extDao, store,
            HostContextFactory(store, testSessionStore(), transport),
            FakeDexLoader()
        ) { FakeJsRuntime() }
        val cleanerStore = CleanerRuleStore(store)
        store.writeText(
            store.definitionFile("test.deadimg"),
            DeclarativeFingerprints.encode(listOf(config))
        )
        val extId = extDao.insert(
            InstalledExtension(
                repoId = 1,
                displayName = "Dead Images",
                packageId = "test.deadimg",
                versionName = "1.0",
                versionCode = 1,
                kind = app.xreader.data.db.ExtensionKind.DECLARATIVE,
                artifactPath = null,
                sha256 = DeclarativeFingerprints.of(listOf(config)),
                apiLevel = 1,
                languages = "en",
                sourceIds = "deadimg",
                installedAt = 0,
                updatedAt = 0,
                permissionsJson = kotlinx.serialization.json.Json
                    .encodeToString(
                        ExtensionPermissions.serializer(),
                        ExtensionPermissions(
                            domains = listOf(FixtureSite.HOST)
                        )
                    )
            )
        )
        val bookId = bookDao.insert(
            Book(
                title = "Dead Tale",
                sourceType = SourceType.EXTENSION,
                extensionId = extId,
                remoteId = SourceBookIds.encode("deadimg", bookUrl),
                addedAt = 0,
                updatedAt = 0
            )
        )
        chapterDao.insert(
            Chapter(
                bookId = bookId, index = 0, title = "Chapter 1",
                remoteId = chapterUrl, updatedAt = 0
            )
        )
        val storage = BookStorage(tmp.newFolder())
        val fallback = RoomChapterSourceResolver(mapDao) {
            UrlChapterFetcher(transport)
        }
        val streamedResolver = ExtensionChapterResolver(
            bookDao, mapDao, service, cleanerStore,
            fallback = fallback, storage = storage
        )
        val downloadResolver = ExtensionChapterResolver(
            bookDao, mapDao, service, cleanerStore,
            fallback = fallback, storage = storage,
            fetchPageFiles = true
        )
        val chapter = chapterDao.forBook(bookId).first()
        val streamed = streamedResolver.candidatesFor(chapter).first()
        val downloading = downloadResolver.candidatesFor(chapter).first()
        Rig(
            bookId = bookId,
            storage = storage,
            streamed = streamed.fetcher,
            downloading = downloading.fetcher,
            candidate = streamed.candidate,
            imageRequests = imageRequests,
            healed = healed
        )
    }

    private fun recordFiles(rig: Rig): List<File> =
        rig.storage.contentDir(rig.bookId)
            .listFiles { f -> f.name.startsWith("imgfail-") }
            .orEmpty().toList()

    private suspend fun fetchImages(
        fetcher: ChapterFetcher,
        rig: Rig
    ): List<ChapterBlock.Image> {
        val outcome = fetcher.fetch(rig.candidate)
        assertTrue("fetch failed: $outcome", outcome is FetchOutcome.Success)
        return (outcome as FetchOutcome.Success).content.blocks
            .filterIsInstance<ChapterBlock.Image>()
    }

    @Test
    fun `streamed re-open does not re-attempt recorded failures, download retries and heals`() =
        runBlocking {
            val rig = rig()
            // First streamed open: every image attempted once,
            // all fail, all become placeholder slots, the
            // failure is recorded.
            val first = fetchImages(rig.streamed, rig)
            assertEquals(3, rig.imageRequests.get())
            assertEquals(3, first.size)
            assertTrue(
                first.all { it.fileName == PlaceholderImage.FILE_NAME }
            )
            assertEquals(listOf("First", "Second", "Third"), first.map { it.alt })
            assertEquals("record written", 1, recordFiles(rig).size)

            // Streamed re-open (stored content gone — the
            // re-read shape): the recorded failures are NOT
            // re-attempted; the slots render from the record.
            val second = fetchImages(rig.streamed, rig)
            assertEquals(
                "no re-hammering of the dead host",
                3, rig.imageRequests.get()
            )
            assertEquals(3, second.size)
            assertTrue(
                second.all { it.fileName == PlaceholderImage.FILE_NAME }
            )

            // The host revives; an explicit download RETRIES
            // despite the record — the real images land and the
            // record dies with the healed pass.
            rig.healed.set(true)
            val downloaded = fetchImages(rig.downloading, rig)
            assertEquals(
                "the download road retried every image",
                6, rig.imageRequests.get()
            )
            assertEquals(
                setOf("a.png", "b.png", "c.png"),
                downloaded.map { it.fileName }.toSet()
            )
            for (name in listOf("a.png", "b.png", "c.png")) {
                val file = File(
                    rig.storage.contentDir(rig.bookId), "img-$name"
                )
                assertTrue("$name stored", file.isFile)
                assertTrue(pngBytes.contentEquals(file.readBytes()))
            }
            assertEquals(
                "record dies on the clean pass",
                0, recordFiles(rig).size
            )
        }

    @Test
    fun `download retries while the host stays dead, record keeps guarding streamed opens`() =
        runBlocking {
            val rig = rig()
            fetchImages(rig.streamed, rig)
            assertEquals(3, rig.imageRequests.get())
            assertEquals(1, recordFiles(rig).size)

            // Download with the host still dead: it RETRIES
            // (the record never suppresses the download road)…
            val downloaded = fetchImages(rig.downloading, rig)
            assertEquals(6, rig.imageRequests.get())
            assertTrue(
                downloaded.all { it.fileName == PlaceholderImage.FILE_NAME }
            )
            // …and the failures are recorded afresh, so the next
            // streamed open is guarded again.
            assertEquals(1, recordFiles(rig).size)
            val reopened = fetchImages(rig.streamed, rig)
            assertEquals(
                "streamed open still guarded after the failed download",
                6, rig.imageRequests.get()
            )
            assertTrue(
                reopened.all { it.fileName == PlaceholderImage.FILE_NAME }
            )
        }
}
