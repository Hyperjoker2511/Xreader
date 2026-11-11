package app.xreader.data.extension

import androidx.room.Room
import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.data.db.ExtensionKind
import app.xreader.data.db.ExtensionRepo
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.TrustState
import app.xreader.data.db.XreaderDatabase
import app.xreader.data.extension.ExtensionPermissions.Companion.encode
import app.xreader.data.files.BookStorage
import app.xreader.data.library.BookKindResolver
import app.xreader.extensionapi.ExtensionApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.5.2 loop 2 (DELTA 3): the DETERMINISTIC twin of
 * FenrirLiveFilingTest — the same production pipeline
 * (DeclarativeSource over the shipped bundled config →
 * SourceBookSync into REAL Room rows → BookKindResolver
 * filing) driven against the RECORDED live response shapes
 * (captured from fenrirealm.com's API on 2026-11-03: popular
 * page 1 = 24 records, the type=webtoon listing, both chapter
 * lists, both detail documents). This class carries the
 * deterministic gate for §10 item 8's filing leg; the live
 * class is tagged smoke. Site downtime can neither fail nor
 * pass this gate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FenrirRecordedFilingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) {
            "missing test resource $name"
        }.readBytes().toString(Charsets.UTF_8)

    /** Recorded-shape routing: every URL the journey touches
     * answers its captured body; anything else is a 404 so an
     * unexpected fetch fails loudly instead of passing silently. */
    private fun recordedTransport() = FakeTransport { request ->
        val url = request.url
        val body = when {
            url.contains("type=webtoon") ->
                resource("fenrir/popular-webtoon.json")
            url.contains("sort=popular") ->
                resource("fenrir/popular-p1.json")
            url.endsWith("/series/absolute-regression/chapters") ->
                resource("fenrir/chapters-absolute-regression.json")
            url.endsWith("/series/the-dragon-of-dreams/chapters") ->
                resource("fenrir/chapters-dragon.json")
            url.endsWith("/series/absolute-regression") ->
                resource("fenrir/detail-absolute-regression.json")
            url.endsWith("/series/the-dragon-of-dreams") ->
                resource("fenrir/detail-dragon.json")
            else -> null
        }
        if (body != null) {
            AppResult.Ok(FakeTransport.response(200, body))
        } else {
            AppResult.Ok(FakeTransport.response(404, "no recorded shape"))
        }
    }

    @Test
    fun `recorded fenrir catalogue files per book in one pass`() = runBlocking {
        val config = BundledDefaults.configs()
            .first { it.id == "bundled-fenrirealm" }
        val transport = recordedTransport()
        val filesDir = tmp.newFolder()
        val sessionStore = testSessionStore()
        val factory = HostContextFactory(
            ExtensionStore(filesDir), sessionStore, transport
        )
        val context = factory.contextFor(
            extensionRowId = 1,
            packageId = "bundled.bundled-fenrirealm",
            label = "FenrirRealm",
            permissions = {
                ExtensionPermissions(domains = listOf("fenrirealm.com"))
            }
        )
        val source = DeclarativeSource(config, context)

        // ── the recorded catalogue pass ─────────────────────
        val page = withContext(Dispatchers.IO) { source.popular(1) }
        assertTrue(
            "recorded catalogue answered ${page.books.size} books",
            page.books.size >= 20
        )
        assertTrue(
            "kinds seen: ${page.books.map { it.kind }.distinct()}",
            page.books.all { it.kind == BookKind152.TEXT }
        )

        // ── the recorded webtoon record ─────────────────────
        val webtoonListing = config.popular!!.copy(
            url = config.popular!!.url + "&type=webtoon"
        )
        val webtoonSource = DeclarativeSource(
            config.copy(popular = webtoonListing, latest = null),
            context
        )
        val webtoonPage = withContext(Dispatchers.IO) {
            webtoonSource.popular(1)
        }
        val dragon = webtoonPage.books.firstOrNull {
            it.title == "The Dragon of Dreams"
        } ?: throw AssertionError(
            "the recorded type=webtoon pass did not return " +
                "The Dragon of Dreams: ${webtoonPage.books.map { it.title }}"
        )
        assertEquals(BookKind152.MANGA, dragon.kind)

        // ── sync into REAL Room rows + the resolver's filing ─
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            XreaderDatabase::class.java
        ).allowMainThreadQueries().build()
        val repoId = db.extensionRepoDao().insert(
            ExtensionRepo(
                name = "Bundled",
                indexUrl = "https://bundled.invalid/index.json"
            )
        )
        val bookDao = db.bookDao()
        val chapterDao = db.chapterDao()
        val extDao = db.installedExtensionDao()
        val store = ExtensionStore(filesDir)
        val service = ExtensionService(
            extDao, store, factory, FakeDexLoader()
        ) { FakeJsRuntime() }
        val rowId = extDao.insert(
            InstalledExtension(
                repoId = repoId,
                displayName = "FenrirRealm",
                packageId = "bundled.bundled-fenrirealm",
                versionName = "1.0",
                versionCode = 1,
                kind = ExtensionKind.DECLARATIVE,
                artifactPath = null,
                sha256 = DeclarativeFingerprints.of(listOf(config)),
                apiLevel = ExtensionApi.API_LEVEL,
                languages = "en",
                sourceIds = config.id,
                enabled = true,
                trustState = TrustState.TRUSTED,
                permissionsJson = ExtensionPermissions(
                    domains = listOf("fenrirealm.com")
                ).encode(),
                installedAt = 1L,
                updatedAt = 1L
            )
        )
        val row = extDao.getById(rowId)!!
        val handle = SourceHandle(row, source)
        val sync = SourceBookSync(
            bookDao = bookDao,
            chapterDao = chapterDao,
            sourceMapDao = db.chapterSourceMapDao(),
            extensionDao = extDao,
            service = service,
            storage = BookStorage(filesDir),
            transport = transport
        )
        val novelRef = page.books.first()
        val novelBook = (sync.openBook(handle, novelRef)
            as? AppResult.Ok)?.value
            ?: throw AssertionError("openBook failed for the novel ref")
        val dragonBook = (sync.openBook(
            SourceHandle(row, webtoonSource), dragon
        ) as? AppResult.Ok)?.value
            ?: throw AssertionError("openBook failed for the webtoon ref")

        // Tier-1 kinds landed on the rows at sync time.
        assertEquals(BookKind152.TEXT, novelBook.kind)
        assertEquals(BookKind152.MANGA, dragonBook.kind)
        // The chapter lists flow through the sync's refresh —
        // the call the book page and the reader make (the
        // combined details+chapters read), with the handles
        // this journey already holds: the recorded lists land
        // as rows (896 + 19 on the captured shapes).
        sync.refresh(novelBook, handle)
        sync.refresh(dragonBook, SourceHandle(row, webtoonSource))
        assertTrue(
            "novel chapters synced: ${chapterDao.forBook(novelBook.id).size}" +
                " — requests: " +
                transport.requests.map { it.url }.joinToString(" | "),
            chapterDao.forBook(novelBook.id).size > 100
        )
        assertTrue(
            "dragon chapters synced: ${chapterDao.forBook(dragonBook.id).size}",
            chapterDao.forBook(dragonBook.id).size > 0
        )

        val resolver = BookKindResolver(service, bookDao)
        val kinds = resolver.kindsOf(listOf(novelBook, dragonBook))
        assertEquals(
            MediaKind.TEXT,
            app.xreader.data.library.LibrarySplit
                .kindOf(novelBook.id, kinds)
        )
        assertEquals(MediaKind.MANGA, kinds[dragonBook.id])
        assertEquals(
            MediaKind.MANGA,
            app.xreader.data.library.LibrarySplit
                .kindOf(dragonBook.id, kinds)
        )

        // The UNKNOWN consumer table: no stored kind files
        // visually under Novels — never vanishes, never Manga.
        val unknownBook = novelBook.copy(kind = null)
        assertEquals(
            MediaKind.TEXT,
            app.xreader.data.library.LibrarySplit.kindOf(
                unknownBook.id,
                resolver.kindsOf(listOf(unknownBook))
            )
        )
    }
}
