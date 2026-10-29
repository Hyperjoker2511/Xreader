package app.xreader.data.extension

import app.xreader.data.FakeBookDao
import app.xreader.data.FakeChapterDao
import app.xreader.data.FakeChapterSourceMapDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.data.db.Book
import app.xreader.data.db.Chapter
import app.xreader.data.db.ChapterSourceMap
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.SourceType
import app.xreader.data.download.FetchOutcome
import app.xreader.data.download.RoomChapterSourceResolver
import app.xreader.data.download.UrlChapterFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Downloads wiring (PLAN §5 gate): an EXTENSION book's chapter
 * resolves to its source, downloads through the guarded runtime, gets
 * cleaned on the way in — and when the primary source can't serve it,
 * the failover candidate from chapter_source_map heals it.
 */
class ExtensionResolverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var bookDao: FakeBookDao
    private lateinit var chapterDao: FakeChapterDao
    private lateinit var mapDao: FakeChapterSourceMapDao
    private lateinit var extDao: FakeInstalledExtensionDao
    private lateinit var service: ExtensionService
    private lateinit var cleanerStore: CleanerRuleStore
    private lateinit var resolver: ExtensionChapterResolver

    private suspend fun setUp(transport: FakeTransport) {
        bookDao = FakeBookDao()
        chapterDao = FakeChapterDao()
        mapDao = FakeChapterSourceMapDao()
        extDao = FakeInstalledExtensionDao()
        val filesDir = tmp.newFolder()
        val store = ExtensionStore(filesDir)
        storeRef = store
        val sessionStore = testSessionStore()
        val factory = HostContextFactory(store, sessionStore, transport)
        service = ExtensionService(extDao, store, factory, FakeDexLoader()) {
            FakeJsRuntime()
        }
        cleanerStore = CleanerRuleStore(store)
        // Install the fixture declarative package directly.
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
                permissionsJson = ExtensionPermissions(
                    domains = listOf(FixtureSite.HOST)
                ).let { permissions ->
                    kotlinx.serialization.json.Json.encodeToString(
                        ExtensionPermissions.serializer(), permissions
                    )
                }
            )
        )
        val bookId = bookDao.insert(
            Book(
                title = "Sword Saga",
                sourceType = SourceType.EXTENSION,
                extensionId = extId,
                remoteId = SourceBookIds.encode("novels-example", FixtureSite.BOOK_URL),
                addedAt = 0,
                updatedAt = 0
            )
        )
        chapterDao.insert(
            Chapter(
                bookId = bookId, index = 0, title = "Chapter 1: The Beginning",
                remoteId = FixtureSite.CHAPTER_1_URL, updatedAt = 0
            )
        )
        resolver = ExtensionChapterResolver(
            bookDao, mapDao, service, cleanerStore,
            fallback = RoomChapterSourceResolver(mapDao) { UrlChapterFetcher(transport) }
        )
    }

    @Test
    fun `extension chapter resolves and fetches through its source`() = runBlocking {
        setUp(FixtureSite.transport())
        val chapter = chapterDao.forBook(bookDao.books.value.first().id).first()
        val candidates = resolver.candidatesFor(chapter)
        assertEquals(1, candidates.size)
        val outcome = candidates.first().fetcher.fetch(candidates.first().candidate)
        assertTrue(outcome is FetchOutcome.Success)
        val text = (outcome as FetchOutcome.Success).content.blocks
            .filterIsInstance<app.xreader.domain.content.ChapterBlock.Paragraph>()
            .joinToString("\n") { it.text }
        assertTrue(text.contains("Paragraph 1 of the first chapter"))
    }

    @Test
    fun `cleaner rules apply inside the fetch`() = runBlocking {
        setUp(FixtureSite.transport())
        cleanerStore.saveGlobal(
            listOf(CleanerRuleData("Paragraph 1 of the first chapter", "OPENING LINE"))
        )
        val chapter = chapterDao.forBook(bookDao.books.value.first().id).first()
        val candidates = resolver.candidatesFor(chapter)
        val outcome = candidates.first().fetcher.fetch(candidates.first().candidate)
        val text = (outcome as FetchOutcome.Success).content.blocks
            .filterIsInstance<app.xreader.domain.content.ChapterBlock.Paragraph>()
            .joinToString("\n") { it.text }
        assertTrue(text.contains("OPENING LINE"))
    }

    @Test
    fun `failover candidate heals a chapter the primary cannot serve`() = runBlocking {
        setUp(FixtureSite.transport())
        val mirrorId = installMirror()
        val book = bookDao.books.value.first()
        // The primary's copy of this chapter is gone (404 on the site).
        val brokenChapterId = chapterDao.insert(
            Chapter(
                bookId = book.id, index = 1, title = "Chapter 99",
                remoteId = "${FixtureSite.BASE}/book/sword-saga/chapter-99",
                updatedAt = 0
            )
        )
        val brokenChapter = chapterDao.getById(brokenChapterId)!!
        mapDao.upsert(
            ChapterSourceMap(
                chapterId = brokenChapter.id,
                altExtensionId = mirrorId,
                altRemoteId = app.xreader.data.download.FailoverOrder.joinLocator(
                    "mirror",
                    SourceBookIds.encodeAltLocator(
                        "novels-example", FixtureSite.CHAPTER_1_URL
                    )
                ),
                matchConfidence = 0.95f,
                verified = false
            )
        )
        val candidates = resolver.candidatesFor(brokenChapter)
        assertEquals(2, candidates.size)
        val first = candidates[0].fetcher.fetch(candidates[0].candidate)
        assertTrue(
            "primary must fail (its copy is gone)",
            first is FetchOutcome.Failure
        )
        val second = candidates[1].fetcher.fetch(candidates[1].candidate)
        assertTrue(
            "the mirror must heal the chapter",
            second is FetchOutcome.Success
        )
        val text = (second as FetchOutcome.Success).content.blocks
            .filterIsInstance<app.xreader.domain.content.ChapterBlock.Paragraph>()
            .joinToString("\n") { it.text }
        assertTrue(text.contains("Paragraph 1 of the first chapter"))
    }

    /** Install a second copy of the fixture package as a mirror. */
    private suspend fun installMirror(): Long {
        val store = storeRef ?: error("store not initialised")
        store.writeText(
            store.definitionFile("test.mirror"),
            DeclarativeFingerprints.encode(listOf(FixtureSite.config()))
        )
        return extDao.insert(
            InstalledExtension(
                repoId = 1,
                displayName = "Novels Mirror",
                packageId = "test.mirror",
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
    }

    private var storeRef: ExtensionStore? = null

    @Test
    fun `non-extension books fall back to the phase 2 resolver`() = runBlocking {
        setUp(FixtureSite.transport())
        val localId = bookDao.insert(
            Book(
                title = "Local", sourceType = SourceType.LOCAL_FILE,
                addedAt = 0, updatedAt = 0
            )
        )
        val localChapterId = chapterDao.insert(
            Chapter(
                bookId = localId, index = 0, title = "C1",
                remoteId = FixtureSite.CHAPTER_2_URL, updatedAt = 0
            )
        )
        val chapter = chapterDao.getById(localChapterId)!!
        val candidates = resolver.candidatesFor(chapter)
        assertEquals(1, candidates.size)
        val outcome = candidates.first().fetcher.fetch(candidates.first().candidate)
        assertTrue(outcome is FetchOutcome.Success)
    }
}
