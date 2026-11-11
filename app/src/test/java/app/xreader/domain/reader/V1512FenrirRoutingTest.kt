package app.xreader.domain.reader

import app.xreader.data.FakeBookDao
import app.xreader.data.FakeChapterDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.data.db.Book
import app.xreader.data.db.Chapter
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.SourceType
import app.xreader.data.extension.BookKind152
import app.xreader.data.extension.BundledSingles
import app.xreader.data.extension.DeclarativeConfig
import app.xreader.data.extension.DeclarativeFingerprints
import app.xreader.data.extension.ExtensionPermissions
import app.xreader.data.extension.ExtensionService
import app.xreader.data.extension.ExtensionStore
import app.xreader.data.extension.FakeDexLoader
import app.xreader.data.extension.FakeJsRuntime
import app.xreader.data.extension.HostContextFactory
import app.xreader.data.extension.SourceBookIds
import app.xreader.data.extension.testSessionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.12 gate — FRONT 2, M7 (G2.6): per-book kind routing
 * for a both-kinds source, at the SHELF entry-point shape
 * (resolve by book id + chapter index), against FenrirRealm's
 * REAL bundled config — not a fixture copy. The existing
 * ReaderRouteResolverTest proves the stored-kind law on a
 * FixtureSite-shaped both-kinds config through resolve(book);
 * this pins the same law where the shelf actually calls it,
 * with the config production ships: a Fenrir book stored as
 * MANGA opens the Komikku reader (with its chapter row id),
 * a Fenrir book stored as TEXT opens the novel reader.
 */
class V1512FenrirRoutingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val bookDao = FakeBookDao()
    private val chapterDao = FakeChapterDao()

    private fun fenrirStack(): Pair<ExtensionService, Long> {
        val configs: List<DeclarativeConfig> = listOf(
            BundledSingles.fenrirRealm("fenrirealm", "FenrirRealm")
        )
        val packageId = "test.fenrir"
        val extDao = FakeInstalledExtensionDao()
        val store = ExtensionStore(tmp.newFolder())
        val factory = HostContextFactory(
            store, testSessionStore(), FakeTransport { FakeTransport.ok("") }
        )
        val service = ExtensionService(extDao, store, factory, FakeDexLoader()) {
            FakeJsRuntime()
        }
        store.writeText(
            store.definitionFile(packageId),
            DeclarativeFingerprints.encode(configs)
        )
        val extId = runBlocking {
            extDao.insert(
                InstalledExtension(
                    repoId = 1,
                    displayName = "FenrirRealm",
                    packageId = packageId,
                    versionName = "1.0",
                    versionCode = 1,
                    kind = app.xreader.data.db.ExtensionKind.DECLARATIVE,
                    artifactPath = null,
                    sha256 = DeclarativeFingerprints.of(configs),
                    apiLevel = 1,
                    languages = "en",
                    sourceIds = "fenrirealm",
                    installedAt = 0,
                    updatedAt = 0,
                    permissionsJson = ExtensionPermissions(
                        domains = listOf("fenrirealm.com")
                    ).let { permissions ->
                        kotlinx.serialization.json.Json.encodeToString(
                            ExtensionPermissions.serializer(), permissions
                        )
                    }
                )
            )
        }
        return service to extId
    }

    private suspend fun fenrirBook(extId: Long, kind: String?): Book {
        val id = bookDao.insert(
            Book(
                title = "Fenrir Book",
                sourceType = SourceType.EXTENSION,
                extensionId = extId,
                remoteId = SourceBookIds.encode(
                    "fenrirealm", "https://fenrirealm.com/series/dragon-slayer"
                ),
                kind = kind,
                addedAt = 0,
                updatedAt = 0
            )
        )
        return bookDao.getById(id)!!
    }

    @Test
    fun fenrirMangaBookFromShelfOpensKomikku() = runBlocking {
        val (service, extId) = fenrirStack()
        val book = fenrirBook(extId, kind = BookKind152.MANGA)
        val chapterRowId = chapterDao.insert(
            Chapter(
                bookId = book.id, index = 0, title = "Chapter 12",
                remoteId = "https://fenrirealm.com/api/new/v2/series/" +
                    "dragon-slayer/chapters/12",
                updatedAt = 0
            )
        )
        val resolver = ReaderRouteResolver(bookDao, chapterDao, service)
        assertEquals(
            ReaderRoute.Komikku(bookId = book.id, chapterRowId = chapterRowId),
            resolver.resolve(book.id, chapterIndex = 0)
        )
    }

    @Test
    fun fenrirNovelBookFromShelfOpensNovelReader() = runBlocking {
        val (service, extId) = fenrirStack()
        val book = fenrirBook(extId, kind = BookKind152.TEXT)
        chapterDao.insert(
            Chapter(
                bookId = book.id, index = 0, title = "Chapter 12",
                remoteId = "https://fenrirealm.com/api/new/v2/series/" +
                    "dragon-slayer/chapters/12",
                updatedAt = 0
            )
        )
        val resolver = ReaderRouteResolver(bookDao, chapterDao, service)
        assertEquals(
            ReaderRoute.NovelReader(bookId = book.id, chapterIndex = 0),
            resolver.resolve(book.id, chapterIndex = 0)
        )
    }
}
