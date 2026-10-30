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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.0.6.2 (plugin images): chapters fetched through PLUGIN
 * (extension) sources keep their images, the way G4 kept them for
 * plain web chapters. The image bytes travel through the source's
 * own HTTP stack (whitelist + session), land in the book's content
 * dir as `img-<name>`, and the content model references the bare
 * name. A dead image is skipped, the text survives; constructions
 * without storage keep the pre-image behaviour exactly.
 */
class V1062PluginImagesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var bookDao: FakeBookDao
    private lateinit var chapterDao: FakeChapterDao
    private lateinit var mapDao: FakeChapterSourceMapDao
    private lateinit var extDao: FakeInstalledExtensionDao
    private lateinit var service: ExtensionService
    private lateinit var cleanerStore: CleanerRuleStore
    private var bookId: Long = 0

    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)

    private val imageChapterPage = """
        <html><head><title>Chapter 2</title></head><body>
        <div class="content">
          <p>Chapter two text, on the road, travelling far from home.</p>
          <img src="/img/map.png" alt="Site map"/>
          <p>After the map, the road bends north.</p>
        </div>
        </body></html>
    """.trimIndent()

    /** The fixture site, with chapter 2 replaced by an
     * image-bearing page; the image itself is served as raw bytes
     * (or 404s, when [serveImage] is false). */
    private fun transport(serveImage: Boolean): FakeTransport {
        val pages = FixtureSite.pages() + mapOf(
            FixtureSite.CHAPTER_2_URL to imageChapterPage
        )
        return FakeTransport { request ->
            when {
                request.url == IMAGE_URL && serveImage -> AppResult.Ok(
                    HttpTransport.Response(
                        code = 200,
                        headers = mapOf(
                            "Content-Length" to pngBytes.size.toString()
                        ),
                        body = pngBytes.inputStream(),
                        isPartialContent = false
                    )
                )
                request.url == IMAGE_URL -> AppResult.Ok(
                    FakeTransport.response(404, "gone")
                )
                else -> {
                    val body = pages[request.url]
                    if (body == null) {
                        AppResult.Ok(FakeTransport.response(404, "not found"))
                    } else {
                        FakeTransport.ok(body)
                    }
                }
            }
        }
    }

    private suspend fun setUp(transport: FakeTransport) {
        bookDao = FakeBookDao()
        chapterDao = FakeChapterDao()
        mapDao = FakeChapterSourceMapDao()
        extDao = FakeInstalledExtensionDao()
        val filesDir = tmp.newFolder()
        val store = ExtensionStore(filesDir)
        val sessionStore = testSessionStore()
        val factory = HostContextFactory(store, sessionStore, transport)
        service = ExtensionService(extDao, store, factory, FakeDexLoader()) {
            FakeJsRuntime()
        }
        cleanerStore = CleanerRuleStore(store)
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
        bookId = bookDao.insert(
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
    }

    private fun resolver(
        transport: FakeTransport,
        storage: BookStorage?
    ): ExtensionChapterResolver = ExtensionChapterResolver(
        bookDao, mapDao, service, cleanerStore,
        fallback = RoomChapterSourceResolver(mapDao) {
            UrlChapterFetcher(transport)
        },
        storage = storage
    )

    private suspend fun fetchChapter(
        resolver: ExtensionChapterResolver
    ): FetchOutcome {
        val chapter = chapterDao.forBook(bookId).first()
        val candidates = resolver.candidatesFor(chapter)
        assertEquals(1, candidates.size)
        return candidates.first().fetcher.fetch(candidates.first().candidate)
    }

    @Test
    fun pluginChapterKeepsItsImages() = runBlocking {
        val transport = transport(serveImage = true)
        setUp(transport)
        val storage = BookStorage(tmp.newFolder("plugin-img"))
        val outcome = fetchChapter(resolver(transport, storage))
        assertTrue("fetch failed: $outcome", outcome is FetchOutcome.Success)
        val content = (outcome as FetchOutcome.Success).content
        val image = content.blocks.filterIsInstance<ChapterBlock.Image>().single()
        assertEquals("map.png", image.fileName)
        assertEquals("Site map", image.alt)
        val stored = File(storage.contentDir(bookId), "img-map.png")
        assertTrue("image bytes were not stored", stored.isFile)
        assertTrue(pngBytes.contentEquals(stored.readBytes()))
        assertTrue(content.plainText().contains("travelling far from home"))
        assertTrue(content.plainText().contains("the road bends north"))
        // The image travelled through the extension HTTP stack
        // (the transport behind the source's client), like the page.
        assertTrue(
            "image was never requested",
            transport.requests.any { it.url == IMAGE_URL }
        )
    }

    @Test
    fun aDeadImageIsSkippedAndTheTextSurvives() = runBlocking {
        val transport = transport(serveImage = false)
        setUp(transport)
        val storage = BookStorage(tmp.newFolder("plugin-dead"))
        val outcome = fetchChapter(resolver(transport, storage))
        assertTrue("fetch failed: $outcome", outcome is FetchOutcome.Success)
        val content = (outcome as FetchOutcome.Success).content
        assertFalse(content.blocks.any { it is ChapterBlock.Image })
        assertTrue(content.plainText().contains("travelling far from home"))
        assertTrue(content.plainText().contains("the road bends north"))
        assertFalse(File(storage.contentDir(bookId), "img-map.png").exists())
    }

    @Test
    fun constructionsWithoutStorageKeepTheOldBehaviour() = runBlocking {
        val transport = transport(serveImage = true)
        setUp(transport)
        val outcome = fetchChapter(resolver(transport, storage = null))
        assertTrue("fetch failed: $outcome", outcome is FetchOutcome.Success)
        val content = (outcome as FetchOutcome.Success).content
        assertFalse(content.blocks.any { it is ChapterBlock.Image })
        assertTrue(content.plainText().contains("travelling far from home"))
        assertTrue(content.plainText().contains("the road bends north"))
        // And the shared converter's default still drops images —
        // the text-only callers (DeclarativeSource's internal
        // conversion, test-drive sampling) are untouched.
        val converted = HtmlToContent.fromFragment(
            "<p>Prose.</p><img src=\"/img/map.png\"/>",
            FixtureSite.CHAPTER_2_URL
        )
        assertFalse(converted.blocks.any { it is ChapterBlock.Image })
        assertTrue(converted.plainText().contains("Prose."))
    }

    private companion object {
        const val IMAGE_URL = "${FixtureSite.BASE}/img/map.png"
    }
}
