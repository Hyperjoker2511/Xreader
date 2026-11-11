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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.8 (B2 test harness): the ExtensionResolverTest stack
 * shape — a declarative package installed into a real
 * [ExtensionService] over a scripted [FakeTransport], books
 * and chapters in fake DAOs, fetches driven through the
 * PRODUCTION resolver ([ExtensionChapterResolver
 * .candidatesFor] → [ExtensionChapterFetcher.fetch]) so the
 * B2 seam is exercised exactly as the reader/downloads ride
 * it, including the resolver's new book hand-in.
 */
internal class V158FetcherStack(
    val resolver: ExtensionChapterResolver,
    val bookDao: FakeBookDao,
    val bookId: Long,
    val chaptersByUrl: Map<String, Chapter>
)

internal fun v158FetcherStack(
    tmp: TemporaryFolder,
    config: DeclarativeConfig,
    transport: FakeTransport,
    bookKind: String?,
    bookUrl: String,
    chapterUrls: List<String>
): V158FetcherStack {
    val bookDao = FakeBookDao()
    val chapterDao = FakeChapterDao()
    val mapDao = FakeChapterSourceMapDao()
    val extDao = FakeInstalledExtensionDao()
    val filesDir = tmp.newFolder()
    val store = ExtensionStore(filesDir)
    val sessionStore = testSessionStore()
    val factory = HostContextFactory(store, sessionStore, transport)
    val service = ExtensionService(extDao, store, factory, FakeDexLoader()) {
        FakeJsRuntime()
    }
    val cleanerStore = CleanerRuleStore(store)
    val host = java.net.URI(config.baseUrl).host ?: ""
    store.writeText(
        store.definitionFile("test.v158"),
        DeclarativeFingerprints.encode(listOf(config))
    )
    var stack: V158FetcherStack? = null
    runBlocking {
        val extId = extDao.insert(
            InstalledExtension(
                repoId = 1,
                displayName = config.name,
                packageId = "test.v158",
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
                permissionsJson = ExtensionPermissions(
                    domains = listOf(host)
                ).let { permissions ->
                    kotlinx.serialization.json.Json.encodeToString(
                        ExtensionPermissions.serializer(), permissions
                    )
                }
            )
        )
        val bookId = bookDao.insert(
            Book(
                title = "V158 Book",
                sourceType = SourceType.EXTENSION,
                extensionId = extId,
                remoteId = SourceBookIds.encode(config.id, bookUrl),
                kind = bookKind,
                addedAt = 0,
                updatedAt = 0
            )
        )
        val byUrl = LinkedHashMap<String, Chapter>()
        chapterUrls.forEachIndexed { index, url ->
            val id = chapterDao.insert(
                Chapter(
                    bookId = bookId, index = index,
                    title = "Chapter ${index + 1}",
                    remoteId = url, updatedAt = 0
                )
            )
            byUrl[url] = chapterDao.chapters.value.first { it.id == id }
        }
        val resolver = ExtensionChapterResolver(
            bookDao, mapDao, service, cleanerStore,
            fallback = RoomChapterSourceResolver(mapDao) {
                UrlChapterFetcher(transport)
            }
        )
        stack = V158FetcherStack(resolver, bookDao, bookId, byUrl)
    }
    return stack!!
}

/** Drive one chapter through the production resolver and
 * return its fetch outcome. */
internal fun V158FetcherStack.fetch(url: String): FetchOutcome =
    runBlocking {
        val chapter = chaptersByUrl.getValue(url)
        val candidates = resolver.candidatesFor(chapter)
        assertEquals(
            "one production candidate for $url", 1, candidates.size
        )
        candidates.first().fetcher.fetch(candidates.first().candidate)
    }

/**
 * v1.5.8 (B2 / F3 Fix 2): a Fenrir-shaped AJAX chapter
 * endpoint answering IMAGE-STRIP HTML for a manhwa chapter
 * — pre-fix the AJAX road produced no pages record at all
 * (tier-2 testified TEXT, the fail-closed guard never
 * armed); post-fix the fetcher seam's evidence pass stores
 * the pages record alongside the content.
 */
class V158AjaxImageChapterEvidenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val base = "https://fenrir.example"
    private val bookUrl = "$base/api/series/my-manhwa"
    private val chapterUrl = "$base/api/series/my-manhwa/chapters/7"

    private fun fenrirShapedConfig() = DeclarativeConfig(
        id = "v158-ajax-evidence",
        name = "Fenrir Shaped",
        baseUrl = base,
        details = DetailsSpec(
            chapters = ChapterListSpec(
                list = "a", name = "@text", link = "@href",
                jsonNameField = "title",
                jsonLinkField = "slug",
                jsonLinkTemplate = "$base/api/series/{bookSlug}/{value}"
            )
        ),
        chapter = ChapterSpec(
            content = "body",
            ajax = ChapterTextAjax(
                urlTemplate = "$base/api/series/{seg-3}/chapters/{seg-1}",
                jsonContentField = "content"
            )
        ),
        popular = ListSpec(
            url = "$base/api/series",
            results = "",
            title = "title",
            link = "slug",
            jsonPath = "data",
            kindField = "type",
            kindValueMap = mapOf("webtoon" to "MANGA", "novel" to "TEXT")
        )
    )

    @Test
    fun `ajax image-strip chapter stores a pages record as evidence`() {
        val imageStrip = (1..5).joinToString("") { n ->
            "<img src=\"https://cdn.example/pages/p$n.jpg\"/>"
        }
        val transport = FakeTransport { request ->
            if (request.url == chapterUrl) {
                FakeTransport.ok(
                    "{\"content_format\":\"html\",\"content\":\"" +
                        imageStrip.replace("\"", "\\\"") + "\"}"
                )
            } else {
                AppResult.Ok(FakeTransport.response(404, "no shape"))
            }
        }
        val stack = v158FetcherStack(
            tmp, fenrirShapedConfig(), transport,
            bookKind = BookKind152.MANGA,
            bookUrl = bookUrl,
            chapterUrls = listOf(chapterUrl)
        )
        val outcome = stack.fetch(chapterUrl)
        assertTrue(
            "expected Success, got $outcome",
            outcome is FetchOutcome.Success
        )
        val content = (outcome as FetchOutcome.Success).content
        assertNotNull(
            "the AJAX road must store a pages record for an " +
                "image chapter (B2's evidence pass)",
            content.pages
        )
        assertEquals(
            (1..5).map { "https://cdn.example/pages/p$it.jpg" },
            content.pages!!.urls
        )
        assertTrue(
            "tier-2's witness (isImageChapter) must testify MANGA",
            content.isImageChapter
        )
    }
}
