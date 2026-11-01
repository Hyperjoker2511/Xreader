package app.xreader.data.library

import app.xreader.data.FakeBookDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.data.db.Book
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.SourceType
import app.xreader.data.extension.DeclarativeConfig
import app.xreader.data.extension.DeclarativeFingerprints
import app.xreader.data.extension.ExtensionPermissions
import app.xreader.data.extension.ExtensionService
import app.xreader.data.extension.ExtensionStore
import app.xreader.data.extension.FakeDexLoader
import app.xreader.data.extension.FakeJsRuntime
import app.xreader.data.extension.FixtureSite
import app.xreader.data.extension.HostContextFactory
import app.xreader.data.extension.MangaFixture
import app.xreader.data.extension.MediaKind
import app.xreader.data.extension.SourceBookIds
import app.xreader.data.extension.testSessionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * Manga programme (M3): how a book's kind is resolved for the
 * Library split — from its source's declarative config, with
 * everything else (local files, text sources, sources that
 * can't be loaded) falling back to Novels so no book ever
 * falls off both sides of the split.
 */
class BookKindResolverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val bookDao = FakeBookDao()
    private val extDao = FakeInstalledExtensionDao()

    /** Install one declarative package holding [config] and
     * return its extension row id. */
    private suspend fun install(
        service: ExtensionService,
        store: ExtensionStore,
        packageId: String,
        config: DeclarativeConfig,
        domains: List<String>
    ): Long {
        store.writeText(
            store.definitionFile(packageId),
            DeclarativeFingerprints.encode(listOf(config))
        )
        return extDao.insert(
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
                languages = "en",
                sourceIds = config.id,
                installedAt = 0,
                updatedAt = 0,
                permissionsJson = kotlinx.serialization.json.Json
                    .encodeToString(
                        ExtensionPermissions.serializer(),
                        ExtensionPermissions(domains = domains)
                    )
            )
        )
    }

    private fun service(store: ExtensionStore): ExtensionService {
        val factory = HostContextFactory(
            store, testSessionStore(), FakeTransport { FakeTransport.ok("") }
        )
        return ExtensionService(extDao, store, factory, FakeDexLoader()) {
            FakeJsRuntime()
        }
    }

    @Test
    fun `a manga source's book resolves to manga`() = runBlocking {
        val store = ExtensionStore(tmp.newFolder())
        val service = service(store)
        val extId = install(
            service, store, "test.manga", MangaFixture.config(),
            listOf(MangaFixture.HOST, "cdn.manga.example")
        )
        val bookId = bookDao.insert(
            Book(
                title = "Panel Saga",
                sourceType = SourceType.EXTENSION,
                extensionId = extId,
                remoteId = SourceBookIds.encode(
                    "manga-example", MangaFixture.BOOK_URL
                ),
                addedAt = 0, updatedAt = 0
            )
        )
        val resolver = BookKindResolver(service)
        assertEquals(MediaKind.MANGA, resolver.kindOf(bookDao.getById(bookId)!!))
        assertEquals(
            mapOf(bookId to MediaKind.MANGA),
            resolver.kindsOf(listOf(bookDao.getById(bookId)!!))
        )
    }

    @Test
    fun `a text source's book stays a novel`() = runBlocking {
        val store = ExtensionStore(tmp.newFolder())
        val service = service(store)
        val extId = install(
            service, store, "test.novels", FixtureSite.config(),
            listOf(FixtureSite.HOST)
        )
        val bookId = bookDao.insert(
            Book(
                title = "Sword Saga",
                sourceType = SourceType.EXTENSION,
                extensionId = extId,
                remoteId = SourceBookIds.encode(
                    "novels-example", FixtureSite.BOOK_URL
                ),
                addedAt = 0, updatedAt = 0
            )
        )
        val resolver = BookKindResolver(service)
        assertEquals(MediaKind.TEXT, resolver.kindOf(bookDao.getById(bookId)!!))
        // Novels are ABSENT from the kinds map — every consumer
        // reads absence as Novels.
        assertFalse(
            resolver.kindsOf(listOf(bookDao.getById(bookId)!!))
                .containsKey(bookId)
        )
    }

    @Test
    fun `local and opds books are novels`() = runBlocking {
        val store = ExtensionStore(tmp.newFolder())
        val resolver = BookKindResolver(service(store))
        val localId = bookDao.insert(
            Book(
                title = "Imported", sourceType = SourceType.LOCAL_FILE,
                addedAt = 0, updatedAt = 0
            )
        )
        val opdsId = bookDao.insert(
            Book(
                title = "Server Book", sourceType = SourceType.OPDS,
                addedAt = 0, updatedAt = 0
            )
        )
        val kinds = resolver.kindsOf(
            listOf(bookDao.getById(localId)!!, bookDao.getById(opdsId)!!)
        )
        assertTrue(kinds.isEmpty())
        assertEquals(
            MediaKind.TEXT, resolver.kindOf(bookDao.getById(localId)!!)
        )
    }

    @Test
    fun `a book whose source is gone falls back to novels`() = runBlocking {
        val store = ExtensionStore(tmp.newFolder())
        val resolver = BookKindResolver(service(store))
        // An extension book naming an extension that was never
        // installed (or was removed): the source cannot load,
        // so the book must still land on the Novels side.
        val bookId = bookDao.insert(
            Book(
                title = "Orphan",
                sourceType = SourceType.EXTENSION,
                extensionId = 999L,
                remoteId = SourceBookIds.encode(
                    "manga-example", MangaFixture.BOOK_URL
                ),
                addedAt = 0, updatedAt = 0
            )
        )
        assertEquals(
            MediaKind.TEXT, resolver.kindOf(bookDao.getById(bookId)!!)
        )
    }
}
