package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeBookDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.db.Book
import app.xreader.data.db.SourceType
import app.xreader.data.library.BookKindResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.8 (B3): tier-2 arming, the plan's four cases.
 * (i) a learned-shaped declarative source WITHOUT a kind
 * field re-files an UNKNOWN-prior book on two image
 * verdicts (the widened gate); (ii) a novel-only source
 * never moves from prose verdicts; (iii) a Madara-shaped
 * book (TEXT pin, no kind field) routes to Manga on
 * evidence; (iv) a TEXT prior needs THREE consecutive image
 * verdicts with zero prose verdicts ever — one prose
 * verdict vetoes tier-2 for that book permanently.
 */
class V158Tier2ArmingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun config(id: String) = DeclarativeConfig(
        id = id,
        name = id,
        baseUrl = "https://$id.example",
        mediaKind = MediaKind.TEXT,
        popular = ListSpec(
            url = "https://$id.example/list?page={page}",
            results = "",
            title = "title",
            link = "slug",
            jsonPath = "data"
        ),
        details = DetailsSpec(
            chapters = ChapterListSpec(
                list = "li",
                name = "a",
                link = "a@href",
                jsonPath = "data"
            )
        ),
        chapter = ChapterSpec(content = ".content")
    )

    private class Rig(
        val resolver: BookKindResolver,
        val bookDao: FakeBookDao,
        val rowIds: Map<String, Long>
    )

    private fun rig(): Rig = runBlocking {
        val store = ExtensionStore(tmp.newFolder())
        val extDao = FakeInstalledExtensionDao()
        val sessionStore = testSessionStore()
        val transport = app.xreader.data.FakeTransport {
            AppResult.Ok(app.xreader.data.FakeTransport.response(404, ""))
        }
        val factory = HostContextFactory(store, sessionStore, transport)
        val service = ExtensionService(
            extDao, store, factory, FakeDexLoader()
        ) { FakeJsRuntime() }
        val manager = RepoManager(
            app.xreader.data.FakeExtensionRepoDao(),
            extDao, store, transport, service, sessionStore
        )
        val rows = mutableMapOf<String, Long>()
        for (id in listOf("learned", "novelonly", "madara")) {
            val saved = manager.saveLinkSource(config(id), "$id.example")
            rows[id] = (saved as? AppResult.Ok)?.value?.id
                ?: throw AssertionError("save failed for $id: $saved")
        }
        val bookDao = FakeBookDao()
        Rig(BookKindResolver(service, bookDao), bookDao, rows)
    }

    private fun book(extId: Long, kind: String?, sourceId: String) = Book(
        title = "Book",
        sourceType = SourceType.EXTENSION,
        extensionId = extId,
        remoteId = "$sourceId|book-1",
        kind = kind,
        addedAt = 1L,
        updatedAt = 1L
    )

    private suspend fun stored(
        rig: Rig, sourceKey: String, prior: String?
    ): Book {
        val id = rig.bookDao.insert(
            book(rig.rowIds.getValue(sourceKey), prior, sourceKey)
        )
        return rig.bookDao.getById(id)!!
    }

    private suspend fun kindOf(rig: Rig, book: Book): String? =
        rig.bookDao.getById(book.id)!!.kind

    @Test
    fun `case i - learned source without kind field re-files unknown prior on two image verdicts`() =
        runBlocking {
            val rig = rig()
            val book = stored(rig, "learned", null)
            rig.resolver.applyTier2Evidence(book, BookKind152.MANGA)
            assertNull(kindOf(rig, book))
            rig.resolver.applyTier2Evidence(book, BookKind152.MANGA)
            assertEquals(BookKind152.MANGA, kindOf(rig, book))
        }

    @Test
    fun `case ii - novel-only source never moves from prose verdicts`() =
        runBlocking {
            val rig = rig()
            val book = stored(rig, "novelonly", BookKind152.TEXT)
            repeat(5) {
                rig.resolver.applyTier2Evidence(book, BookKind152.TEXT)
            }
            assertEquals(BookKind152.TEXT, kindOf(rig, book))
        }

    @Test
    fun `case iii - madara-shaped text-pinned book routes to manga on evidence`() =
        runBlocking {
            val rig = rig()
            val book = stored(rig, "madara", BookKind152.TEXT)
            repeat(3) {
                rig.resolver.applyTier2Evidence(book, BookKind152.MANGA)
            }
            assertEquals(BookKind152.MANGA, kindOf(rig, book))
            assertEquals(
                MediaKind.MANGA,
                rig.resolver.kindOf(rig.bookDao.getById(book.id)!!)
            )
        }

    @Test
    fun `case iv - text prior needs three consecutive image verdicts and prose vetoes forever`() =
        runBlocking {
            val rig = rig()
            // Two consecutive: unmoved. Third: re-files.
            val climber = stored(rig, "novelonly", BookKind152.TEXT)
            rig.resolver.applyTier2Evidence(climber, BookKind152.MANGA)
            rig.resolver.applyTier2Evidence(climber, BookKind152.MANGA)
            assertEquals(BookKind152.TEXT, kindOf(rig, climber))
            rig.resolver.applyTier2Evidence(climber, BookKind152.MANGA)
            assertEquals(BookKind152.MANGA, kindOf(rig, climber))

            // One prose verdict anywhere: vetoed permanently.
            val vetoed = stored(rig, "madara", BookKind152.TEXT)
            rig.resolver.applyTier2Evidence(vetoed, BookKind152.MANGA)
            rig.resolver.applyTier2Evidence(vetoed, BookKind152.TEXT)
            repeat(4) {
                rig.resolver.applyTier2Evidence(vetoed, BookKind152.MANGA)
            }
            assertEquals(BookKind152.TEXT, kindOf(rig, vetoed))
        }
}
