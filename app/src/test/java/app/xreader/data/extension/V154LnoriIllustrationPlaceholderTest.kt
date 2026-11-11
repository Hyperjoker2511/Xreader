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
import app.xreader.domain.content.ChapterBlock
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Inflater
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.4 (WS-5, item 23a): FAILED-IMAGE HONESTY against the
 * real Lnori shape. The fixture is a LIVE capture of the real
 * Classroom of the Elite Vol. 1 chapter page (captured
 * 2026-11-04 from lnori.com, HTTP 200; provenance header in the
 * fixture; byte-twin in build-logs/evidence/). The shipped
 * bundled Lnori definition is driven through the STORED
 * definition path (definition file + installed row, exactly as
 * installBundledDefaults writes them), and the fake transport
 * answers the page's image host — img.lnori.com, dead since
 * before the 2026-11-03 probe (HTTP 530 / Cloudflare 1016) —
 * with 530, as the live host does.
 *
 * Acceptance (spec §2, item 23): the chapter content carries
 * 16 image slots, ALL bound to item 21's placeholder, and the
 * chapter text is intact and complete. Pre-fix this same drive
 * yields ZERO image blocks (each 530 dropped silently) — the
 * both-directions evidence is in V154-BUILD-REPORT-WS5.md.
 */
class V154LnoriIllustrationPlaceholderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val packageId = "bundled.bundled-lnori"
    private val seriesUrl =
        "https://lnori.com/series/4557/classroom-of-the-elite"
    private val chapterUrl =
        "https://lnori.com/book/14966/classroom-of-the-elite-vol-1"

    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) {
            "missing test resource $name"
        }.readBytes().toString(Charsets.UTF_8)

    /** A decoded PNG's dimensions and pixels. The harness's
     * classpath has no java.desktop (no ImageIO), so the proof
     * decodes by hand: signature, IHDR, IDAT inflated with
     * java.util.zip — which also exercises the placeholder
     * encoder's output structurally, chunk by chunk. */
    internal class PngImage(
        val width: Int,
        val height: Int,
        private val rgb: ByteArray
    ) {
        fun pixel(x: Int, y: Int): Int {
            val i = (y * width + x) * 3
            return ((rgb[i].toInt() and 0xFF) shl 16) or
                ((rgb[i + 1].toInt() and 0xFF) shl 8) or
                (rgb[i + 2].toInt() and 0xFF)
        }
    }

    internal fun decodePng(bytes: ByteArray): PngImage {
        require(
            bytes.size > 8 && bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte()
        ) { "not a PNG" }
        fun intAt(offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
        var offset = 8
        var width = 0
        var height = 0
        val idat = ByteArrayOutputStream()
        while (offset + 8 <= bytes.size) {
            val length = intAt(offset)
            when (val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)) {
                "IHDR" -> {
                    width = intAt(offset + 8)
                    height = intAt(offset + 12)
                }
                "IDAT" -> idat.write(bytes, offset + 8, length)
                "IEND" -> break
                else -> throw AssertionError("unexpected chunk $type")
            }
            offset += 12 + length
        }
        require(width > 0 && height > 0) { "no IHDR" }
        val inflater = Inflater()
        inflater.setInput(idat.toByteArray())
        val raw = ByteArray((width * 3 + 1) * height)
        var produced = 0
        while (produced < raw.size) {
            val n = inflater.inflate(raw, produced, raw.size - produced)
            if (n <= 0) break
            produced += n
        }
        inflater.end()
        require(produced == raw.size) { "short IDAT" }
        val rgb = ByteArray(width * height * 3)
        for (y in 0 until height) {
            val rowStart = y * (width * 3 + 1)
            require(raw[rowStart].toInt() == 0) { "filter-0 rows only" }
            System.arraycopy(raw, rowStart + 1, rgb, y * width * 3, width * 3)
        }
        return PngImage(width, height, rgb)
    }

    @Test
    fun `sixteen dead-host illustrations render sixteen placeholders, text intact`() =
        runBlocking {
            val config = BundledDefaults.configs()
                .first { it.id == "bundled-lnori" }
            val bookDao = FakeBookDao()
            val chapterDao = FakeChapterDao()
            val mapDao = FakeChapterSourceMapDao()
            val extDao = FakeInstalledExtensionDao()
            val filesDir = tmp.newFolder()
            val store = ExtensionStore(filesDir)
            val imageRequests = AtomicInteger(0)
            val transport = FakeTransport { request ->
                when {
                    request.url == chapterUrl -> FakeTransport.ok(
                        resource("v154/lnori-vol1-chapter.html")
                    )
                    request.url.startsWith("https://img.lnori.com/") -> {
                        imageRequests.incrementAndGet()
                        AppResult.Ok(
                            FakeTransport.response(
                                530,
                                "error code: 1016"
                            )
                        )
                    }
                    else -> AppResult.Ok(
                        FakeTransport.response(404, "no recorded shape")
                    )
                }
            }
            val service = ExtensionService(
                extDao, store,
                HostContextFactory(store, testSessionStore(), transport),
                FakeDexLoader()
            ) { FakeJsRuntime() }
            val cleanerStore = CleanerRuleStore(store)
            // The stored-definition path: the shipped config
            // object, written exactly as a bundled install
            // writes it (RepoManager.installBundledDefaults).
            store.writeText(
                store.definitionFile(packageId),
                DeclarativeFingerprints.encode(listOf(config))
            )
            val extId = extDao.insert(
                InstalledExtension(
                    repoId = 1,
                    displayName = config.name,
                    packageId = packageId,
                    versionName = "1.0",
                    versionCode = 1,
                    kind = app.xreader.data.db.ExtensionKind.DECLARATIVE,
                    artifactPath = null,
                    sha256 = DeclarativeFingerprints.of(listOf(config)),
                    apiLevel = 1,
                    languages = config.lang,
                    sourceIds = config.id,
                    installedAt = 0,
                    updatedAt = 0,
                    permissionsJson = kotlinx.serialization.json.Json
                        .encodeToString(
                            ExtensionPermissions.serializer(),
                            ExtensionPermissions(
                                domains = listOf(
                                    "lnori.com",
                                    "cdn.lnori.com",
                                    "img.lnori.com"
                                )
                            )
                        )
                )
            )
            val bookId = bookDao.insert(
                Book(
                    title = "Classroom of the Elite",
                    sourceType = SourceType.EXTENSION,
                    extensionId = extId,
                    remoteId = SourceBookIds.encode(
                        "bundled-lnori", seriesUrl
                    ),
                    addedAt = 0,
                    updatedAt = 0
                )
            )
            chapterDao.insert(
                Chapter(
                    bookId = bookId, index = 0,
                    title = "Classroom of the Elite Vol. 1",
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

            // The 16 illustrations were each attempted exactly
            // once against the dead host.
            assertEquals(
                "image fetches against the 530 host",
                16, imageRequests.get()
            )
            // …and every one of them survives as a slot bound to
            // the placeholder — none dropped silently.
            val images = content.blocks
                .filterIsInstance<ChapterBlock.Image>()
            assertEquals(
                "image slots in the chapter content",
                16, images.size
            )
            assertTrue(
                "every slot binds the placeholder: " +
                    images.map { it.fileName }.distinct(),
                images.all { it.fileName == PlaceholderImage.FILE_NAME }
            )
            // The images' alt text rode along (the reader shows
            // it in the labelled slot).
            val alts = images.map { it.alt }
            assertTrue("Image - 01" in alts)
            assertTrue("Title Page - 07" in alts)
            assertEquals(
                "character gallery alts",
                4, alts.count { it.startsWith("Character Gallery") }
            )
            // The placeholder's stored file is a real image on
            // the same stored-file path as any chapter image.
            val placeholderFile = File(
                storage.contentDir(bookId),
                PlaceholderImage.STORED_FILE_NAME
            )
            assertTrue(
                "placeholder file stored",
                placeholderFile.isFile && placeholderFile.length() > 0L
            )
            val decoded = decodePng(placeholderFile.readBytes())
            assertEquals(PlaceholderImage.WIDTH, decoded.width)
            assertEquals(PlaceholderImage.HEIGHT, decoded.height)
            assertEquals(
                "placeholder sun pixel",
                0x8B5CF6, decoded.pixel(800, 290)
            )
            // Item 22's record was written for this chapter.
            val records = storage.contentDir(bookId)
                .listFiles { f -> f.name.startsWith("imgfail-") }
                .orEmpty()
            assertEquals("failure records", 1, records.size)
            // The text is intact and complete.
            val text = content.plainText()
            val words = text.split(Regex("\\s+"))
                .count { it.isNotBlank() }
            assertTrue(
                "chapter words $words — the volume's prose must arrive whole",
                words > 40_000
            )
            assertTrue(
                "the volume's opening line survives",
                text.contains("Are people equal or not?")
            )
            assertTrue(text.contains("Character Gallery"))
        }
}
