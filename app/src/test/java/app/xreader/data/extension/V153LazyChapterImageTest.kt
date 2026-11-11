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
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.3 (finding #4, item 16): LAZY CHAPTER IMAGES. The
 * chapter image pass (ExtensionChapterFetcher.downloadImages)
 * collected only `img[src]`, and the content converter keys
 * its stored-file lookup on the raw src — so an illustration
 * that exists ONLY in a lazy attribute (src holding a data:
 * placeholder, the shape lazy-loading sites serve) was never
 * downloaded and never mapped. The pass now resolves each
 * img through the lazy ladder (data-src first, then the
 * other lazy attributes, then src; blank and data: values
 * skipped) and the fragment's srcs are promoted to the
 * resolved URL before conversion, so the image lands in the
 * book's content dir and the content carries its Image block.
 */
class V153LazyChapterImageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val base = "https://novels.example"
    private val bookUrl = "$base/book/lazy-tale"
    private val chapterUrl = "$base/book/lazy-tale/chapter-1"
    private val imageUrl = "$base/img/illus.png"
    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 5, 6, 7)

    private val config = DeclarativeConfig(
        id = "lazyimg",
        name = "Lazy Images",
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
          <img src="data:image/gif;base64,R0lGODlhAQABAAAAACw="
               data-src="/img/illus.png" alt="The north road"/>
          <p>Beyond the pass, the valley opened and the river
          bent away toward the sea.</p>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `illustration living only in data-src is downloaded and mapped`() =
        runBlocking {
            val bookDao = FakeBookDao()
            val chapterDao = FakeChapterDao()
            val mapDao = FakeChapterSourceMapDao()
            val extDao = FakeInstalledExtensionDao()
            val filesDir = tmp.newFolder()
            val store = ExtensionStore(filesDir)
            val transport = FakeTransport { request ->
                when (request.url) {
                    chapterUrl -> FakeTransport.ok(chapterPage)
                    imageUrl -> AppResult.Ok(
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
                    else -> AppResult.Ok(
                        FakeTransport.response(404, "no")
                    )
                }
            }
            val service = ExtensionService(
                extDao, store,
                HostContextFactory(store, testSessionStore(), transport),
                FakeDexLoader()
            ) { FakeJsRuntime() }
            val cleanerStore = CleanerRuleStore(store)
            store.writeText(
                store.definitionFile("test.lazyimg"),
                DeclarativeFingerprints.encode(listOf(config))
            )
            val extId = extDao.insert(
                InstalledExtension(
                    repoId = 1,
                    displayName = "Lazy Images",
                    packageId = "test.lazyimg",
                    versionName = "1.0",
                    versionCode = 1,
                    kind = app.xreader.data.db.ExtensionKind.DECLARATIVE,
                    artifactPath = null,
                    sha256 = DeclarativeFingerprints.of(listOf(config)),
                    apiLevel = 1,
                    languages = "en",
                    sourceIds = "lazyimg",
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
                    title = "Lazy Tale",
                    sourceType = SourceType.EXTENSION,
                    extensionId = extId,
                    remoteId = SourceBookIds.encode("lazyimg", bookUrl),
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
            val resolver = ExtensionChapterResolver(
                bookDao, mapDao, service, cleanerStore,
                fallback = RoomChapterSourceResolver(mapDao) {
                    UrlChapterFetcher(transport)
                },
                storage = storage
            )
            val chapter = chapterDao.forBook(bookId).first()
            val candidates = resolver.candidatesFor(chapter)
            assertEquals(1, candidates.size)
            val outcome = candidates.first().fetcher
                .fetch(candidates.first().candidate)
            assertTrue(
                "fetch failed: $outcome",
                outcome is FetchOutcome.Success
            )
            val content = (outcome as FetchOutcome.Success).content
            val image = content.blocks
                .filterIsInstance<ChapterBlock.Image>().single()
            assertEquals("illus.png", image.fileName)
            val stored = File(storage.contentDir(bookId), "img-illus.png")
            assertTrue("image bytes were not stored", stored.isFile)
            assertTrue(pngBytes.contentEquals(stored.readBytes()))
            assertTrue(
                "the image travelled through the source session",
                transport.requests.any { it.url == imageUrl }
            )
            assertTrue(
                content.plainText().contains("the traveller walked it")
            )
            assertTrue(
                content.plainText().contains("the valley opened")
            )
        }
}
