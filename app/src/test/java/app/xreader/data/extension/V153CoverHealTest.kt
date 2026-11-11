package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeBookDao
import app.xreader.data.FakeChapterDao
import app.xreader.data.FakeChapterSourceMapDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.data.db.Book
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.SourceType
import app.xreader.data.files.BookStorage
import app.xreader.data.net.HttpTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.3 (finding #3, item 8): the cover heal that never
 * fired. A book whose catalogue ROW carried a cover URL but
 * whose cover FILE was never stored could not heal on
 * refresh: the sync built its details ref from the stored
 * row WITHOUT the row's coverUrl, so a details read that
 * came back coverless (the source declares no details
 * cover; the page carries no meta cover) had nothing to
 * fall back to — details.coverUrl came back null and the
 * cover fetch was never even attempted. The row-built refs
 * now carry `coverUrl = book.coverUrl`, so the refresh
 * below heals the file from the row's own URL.
 */
class V153CoverHealTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val base = "https://novels.example"
    private val bookUrl = "$base/book/coverless-details"
    private val coverUrl = "$base/covers/stored.jpg"
    private val jpgBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4)

    private val config = DeclarativeConfig(
        id = "heal-source",
        name = "Heal Source",
        baseUrl = base,
        popular = ListSpec(
            url = "$base/popular",
            results = "div.result",
            title = "a.t",
            link = "a.t@href",
            cover = "img.c@src"
        ),
        details = DetailsSpec(
            // No cover spec, and the book page below carries
            // no og:image either — the details read is
            // coverless BY CONSTRUCTION; only the incoming
            // ref's cover can heal this book.
            title = "h1",
            chapters = ChapterListSpec(
                list = "ul.chapters li a",
                name = "@text",
                link = "@href"
            )
        ),
        chapter = ChapterSpec(content = "div.content")
    )

    private val bookPage = """
        <html><head><title>Stored Cover Tale</title></head><body>
        <h1>Stored Cover Tale</h1>
        <ul class="chapters">
          <li><a href="/book/coverless-details/chapter-1">Chapter 1</a></li>
        </ul>
        </body></html>
    """.trimIndent()

    @Test
    fun `refresh heals the cover file from the rows stored cover url`() =
        runBlocking {
            val bookDao = FakeBookDao()
            val chapterDao = FakeChapterDao()
            val mapDao = FakeChapterSourceMapDao()
            val extDao = FakeInstalledExtensionDao()
            val filesDir = tmp.newFolder()
            val store = ExtensionStore(filesDir)
            val transport = FakeTransport { request ->
                when (request.url) {
                    bookUrl -> FakeTransport.ok(bookPage)
                    coverUrl -> AppResult.Ok(
                        HttpTransport.Response(
                            code = 200,
                            headers = mapOf(
                                "Content-Length" to
                                    jpgBytes.size.toString()
                            ),
                            body = jpgBytes.inputStream(),
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
            store.writeText(
                store.definitionFile("test.heal"),
                DeclarativeFingerprints.encode(listOf(config))
            )
            val permissions = ExtensionPermissions(
                domains = listOf(FixtureSite.HOST)
            )
            val extId = extDao.insert(
                InstalledExtension(
                    repoId = 1,
                    displayName = "Heal Source",
                    packageId = "test.heal",
                    versionName = "1.0",
                    versionCode = 1,
                    kind = app.xreader.data.db.ExtensionKind.DECLARATIVE,
                    artifactPath = null,
                    sha256 = DeclarativeFingerprints.of(listOf(config)),
                    apiLevel = 1,
                    languages = "en",
                    sourceIds = "heal-source",
                    installedAt = 0,
                    updatedAt = 0,
                    permissionsJson = kotlinx.serialization.json.Json
                        .encodeToString(
                            ExtensionPermissions.serializer(),
                            permissions
                        )
                )
            )
            val storage = BookStorage(tmp.newFolder())
            val sync = SourceBookSync(
                bookDao = bookDao,
                chapterDao = chapterDao,
                sourceMapDao = mapDao,
                extensionDao = extDao,
                service = service,
                storage = storage,
                transport = transport,
                repairScheduler = {},
                nowMs = { 1_000L }
            )
            val bookId = bookDao.insert(
                Book(
                    title = "Stored Cover Tale",
                    sourceType = SourceType.EXTENSION,
                    extensionId = extId,
                    remoteId = SourceBookIds.encode(
                        "heal-source", bookUrl
                    ),
                    // The row carries the cover URL (its
                    // catalogue row had one) but NO cover file.
                    coverUrl = coverUrl,
                    coverPath = null,
                    addedAt = 0,
                    updatedAt = 0,
                    inLibrary = true
                )
            )
            val book = bookDao.getById(bookId)!!
            val result = sync.refresh(book)
            assertTrue("refresh failed: $result", result is AppResult.Ok)
            assertTrue(
                "the stored cover URL was never fetched",
                transport.requests.any { it.url == coverUrl }
            )
            val healed = bookDao.getById(bookId)!!
            assertNotNull(
                "the cover file must be healed by the refresh",
                healed.coverPath
            )
            val file = storage.resolve(healed.coverPath!!)
            assertTrue("healed cover file missing on disk", file.isFile)
            assertTrue(jpgBytes.contentEquals(file.readBytes()))
            assertEquals(coverUrl, healed.coverUrl)
        }
}
