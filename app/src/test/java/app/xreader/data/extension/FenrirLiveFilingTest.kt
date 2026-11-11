package app.xreader.data.extension

import androidx.room.Room
import app.xreader.core.error.AppResult
import app.xreader.data.db.ExtensionKind
import app.xreader.data.db.ExtensionRepo
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.TrustState
import app.xreader.data.db.XreaderDatabase
import app.xreader.data.extension.ExtensionPermissions.Companion.encode
import app.xreader.data.files.BookStorage
import app.xreader.data.library.BookKindResolver
import app.xreader.data.net.OkHttpTransport
import app.xreader.extensionapi.ExtensionApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.2 (FIX-5, §7 / §10 item 8 — the live filing leg):
 * FenrirRealm's LIVE catalogue, through the app's real path —
 * DeclarativeSource (the shipped bundled config) parses the
 * live API answer, SourceBookSync opens the books into rows,
 * and the fixed BookKindResolver files them: every text type
 * files Novels-side, and the live `type=webtoon` record
 * ("The Dragon of Dreams") files Manga-side. Tier-1 evidence
 * is the API's own `type` field, read per book in one pass.
 *
 * v1.5.2 loop 2 (DELTA 3 + 2B delta 3): TAGGED live smoke —
 * excluded from the deterministic gate (the recorded-shape
 * twin, FenrirRecordedFilingTest, carries the gate); run as
 * the smoke set. The sync harness runs on the REAL in-memory
 * Room database (the 2B harness change), exactly like the
 * deterministic twin.
 */
@org.junit.experimental.categories.Category(app.xreader.data.LiveSmoke::class)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class FenrirLiveFilingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @org.junit.Before
    fun setUp() {
        LiveNet.installEgressProxyFromEnv()
        // Absorb the sandbox proxy's cold-path stall (see
        // LiveNet.warmUp) before the proof's timed legs run.
        LiveNet.warmUp("https://fenrirealm.com/")
    }

    @Test
    fun `fenrir live catalogue files per book in one pass`() = runBlocking {
        // Live-network absorption, same discipline as the BOTI
        // proof's verify GETs: the sandbox proxy intermittently
        // stalls a call past the fetch timeout ("the server
        // took too long to answer"). Only that stall signature
        // is retried, with fully fresh state per attempt; every
        // assertion inside the journey runs unmodified on the
        // attempt that completes, and any wrong filing fails
        // immediately — it is never retried away.
        var attempt = 0
        while (true) {
            attempt++
            try {
                runOnce()
                return@runBlocking
            } catch (stall: app.xreader.extensionapi.SourceException) {
                if (attempt >= 3 ||
                    stall.message?.contains("took too long") != true
                ) {
                    throw stall
                }
                kotlinx.coroutines.delay(3_000L)
            }
        }
    }

    private suspend fun runOnce() {
        val config = BundledDefaults.configs()
            .first { it.id == "bundled-fenrirealm" }
        val transport = OkHttpTransport()
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

        // ── the live catalogue pass ─────────────────────────
        val page = withContext(Dispatchers.IO) { source.popular(1) }
        assertTrue(
            "live catalogue answered ${page.books.size} books",
            page.books.size >= 20
        )
        // The re-scan's live census: the popular pass is all
        // text types — every record's tier-1 kind is TEXT.
        assertTrue(
            "kinds seen: ${page.books.map { it.kind }.distinct()}",
            page.books.all { it.kind == BookKind152.TEXT }
        )

        // ── the live webtoon record ─────────────────────────
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
            "the live type=webtoon pass did not return " +
                "The Dragon of Dreams: ${webtoonPage.books.map { it.title }}"
        )
        assertEquals(BookKind152.MANGA, dragon.kind)

        // ── sync into rows + the resolver's filing ──────────
        // Real Room (2B delta 3): the same in-memory database
        // the deterministic twin uses — no fake DAOs.
        val db = Room.inMemoryDatabaseBuilder(
            org.robolectric.RuntimeEnvironment.getApplication(),
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

        val resolver = BookKindResolver(service, bookDao)
        val kinds = resolver.kindsOf(listOf(novelBook, dragonBook))
        // Filed through the consumer contract the Library itself
        // uses (LibrarySplit.kindOf: absent from the map reads as
        // TEXT — a book files Novels-side unless the map says
        // MANGA).
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

        // The UNKNOWN consumer table: a FenrirRealm book with
        // NO stored kind files visually under Novels — it never
        // vanishes and is never guessed into Manga.
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
