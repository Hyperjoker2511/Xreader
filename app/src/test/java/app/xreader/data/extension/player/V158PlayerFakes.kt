package app.xreader.data.extension.player

import app.xreader.data.FakeBookDao
import app.xreader.data.FakeChapterDao
import app.xreader.data.FakeChapterSourceMapDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.data.db.Book
import app.xreader.data.db.ExtensionKind
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.SourceType
import app.xreader.data.db.TrustState
import app.xreader.data.extension.ExtensionService
import app.xreader.data.extension.ExtensionStore
import app.xreader.data.extension.FakeDexLoader
import app.xreader.data.extension.FakeJsRuntime
import app.xreader.data.extension.HostContextFactory
import app.xreader.data.extension.SourceBookIds
import app.xreader.data.extension.SourceBookSync
import app.xreader.data.extension.testSessionStore
import app.xreader.data.files.BookStorage
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

/**
 * v1.5.8: the shared fake player sources + sync stack for the
 * Builder-1 gate tests (E1 scanlator, A1 deadline/envelope,
 * A2 ensure, A3 honesty, A5 pages, B4 novels). The fake's
 * update leg is LATCH-GATED by hand ([updateGate]) — the
 * deadline proofs release it explicitly, never by sleeping
 * towards a wall-clock hope.
 */
open class V158FakeMihon(
    override val baseUrl: String = "https://panels.test"
) : HttpSource() {

    override val name: String = "FakePanels158"
    override val lang: String = "en"
    override val supportsLatest: Boolean = true
    override val client: OkHttpClient = OkHttpClient()

    val updateCalls = AtomicInteger(0)

    /** When set, getMangaUpdate parks on it until the test
     * completes it by hand. */
    @Volatile
    var updateGate: CompletableDeferred<Unit>? = null

    @Volatile
    var updateFailure: Throwable? = null

    @Volatile
    var updateChapters: List<SChapter> = listOf(
        SChapter(
            name = "Chapter 2", url = "/ch/2",
            date_upload = 1_700_000_002_000, chapter_number = 2f,
            scanlator = "Gengar"
        ),
        SChapter(
            name = "Chapter 1", url = "/ch/1",
            date_upload = 1_700_000_001_000, chapter_number = 1f,
            scanlator = "  Gengar  "
        )
    )

    /** Every SChapter the adapter handed to getPageList. */
    val pageListChapters = CopyOnWriteArrayList<SChapter>()

    /** When set, getPageList parks on it until released. */
    @Volatile
    var pageListGate: CompletableDeferred<Unit>? = null

    /** The pages getPageList answers; tests override for the
     * A5 tolerance proof. */
    @Volatile
    var pagesFor: (SChapter) -> List<Page> = { chapter ->
        listOf(
            Page(
                0, url = chapter.url,
                imageUrl = "https://cdn.test${chapter.url}/0.jpg"
            )
        )
    }

    /** getImageUrl behaviour; default resolves from the page. */
    @Volatile
    var imageUrlFor: (Page) -> String = { page ->
        page.imageUrl ?: throw IllegalStateException("no image")
    }

    override suspend fun getPopularManga(page: Int): MangasPage =
        MangasPage(
            listOf(
                SManga(
                    url = "/manga/alpha", title = "Alpha",
                    thumbnail_url = "/covers/alpha.jpg"
                )
            ),
            hasNextPage = false
        )

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        getPopularManga(page)

    override suspend fun getSearchManga(
        page: Int, query: String, filters: FilterList
    ): MangasPage = getPopularManga(page)

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean
    ): SMangaUpdate {
        updateCalls.incrementAndGet()
        updateGate?.await()
        updateFailure?.let { throw it }
        return SMangaUpdate(
            if (fetchDetails) detailsOf(manga) else manga,
            if (fetchChapters) updateChapters else emptyList()
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        pageListChapters += chapter
        pageListGate?.await()
        return pagesFor(chapter)
    }

    override suspend fun getImageUrl(page: Page): String =
        imageUrlFor(page)

    protected fun detailsOf(manga: SManga): SManga = SManga(
        url = manga.url,
        title = "Alpha",
        author = "Some Author",
        description = "A story.",
        thumbnail_url = "/covers/alpha-hd.jpg",
        status = SManga.ONGOING,
        initialized = true
    )
}

/** v1.5.8 (B4): a NovelSource extension — prose via
 * fetchPageText, no image pages at all. */
class V158FakeNovel : V158FakeMihon(), NovelSource {

    val fetchedPages = CopyOnWriteArrayList<Page>()

    override suspend fun fetchPageText(page: Page): String {
        fetchedPages += page
        return "Prose of ${page.url}. The lantern keeper counted " +
            "the harbour lights one by one and wrote each name " +
            "in the margin of the tide book."
    }
}

/** The production-shaped sync stack over the fake DAOs. */
class V158PlayerStack(
    val service: ExtensionService,
    val sync: SourceBookSync,
    val books: FakeBookDao,
    val chapters: FakeChapterDao,
    val sourceKey: String,
    val extRowId: Long
) {
    fun seedBook(
        chapterListComplete: Boolean = false,
        totalChapters: Int = 0
    ): Book = runBlocking {
        val id = books.insert(
            Book(
                title = "Alpha",
                author = "",
                description = "",
                sourceType = SourceType.EXTENSION,
                extensionId = extRowId,
                remoteId = SourceBookIds.encode(sourceKey, "/manga/alpha"),
                chapterListComplete = chapterListComplete,
                totalChapters = totalChapters,
                addedAt = 0,
                updatedAt = 0,
                inLibrary = true
            )
        )
        books.getById(id)!!
    }
}

object V158PlayerStacks {

    const val PKG = "eu.kanade.tachiyomi.extension.en.fakepanels158"
    const val NAMESPACED = "keiyoushi:$PKG:777"

    fun build(
        source: HttpSource,
        storageDir: File,
        pageLoadTimeoutMs: Long = SourceBookSync.BOOK_PAGE_LOAD_TIMEOUT_MS
    ): V158PlayerStack {
        val books = FakeBookDao()
        val chapters = FakeChapterDao()
        val exts = FakeInstalledExtensionDao()
        val rowId = runBlocking {
            exts.insert(
                InstalledExtension(
                    repoId = 1,
                    packageId = PKG,
                    displayName = "FakePanels158",
                    versionName = "1.0",
                    versionCode = 1,
                    kind = ExtensionKind.KEIYOUSHI,
                    sha256 = "abc",
                    apiLevel = 16,
                    languages = "all",
                    sourceIds = "777",
                    trustState = TrustState.TRUSTED,
                    installedAt = 0,
                    updatedAt = 0
                )
            )
        }
        val store = ExtensionStore(storageDir)
        val transport = FakeTransport { FakeTransport.ok("") }
        val service = ExtensionService(
            exts,
            store,
            HostContextFactory(store, testSessionStore(), transport),
            FakeDexLoader(),
            keiyoushiLiveSources = { mapOf(NAMESPACED to source) }
        ) { FakeJsRuntime() }
        val sync = SourceBookSync(
            bookDao = books,
            chapterDao = chapters,
            sourceMapDao = FakeChapterSourceMapDao(),
            extensionDao = exts,
            service = service,
            storage = BookStorage(storageDir),
            transport = FakeTransport { request ->
                if (request.url.contains("/covers/")) {
                    FakeTransport.ok("fake-cover-bytes")
                } else {
                    FakeTransport.ok("")
                }
            },
            pageLoadTimeoutMs = pageLoadTimeoutMs
        )
        return V158PlayerStack(
            service, sync, books, chapters, NAMESPACED, rowId
        )
    }
}
