package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeBookDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.db.Book
import app.xreader.data.db.SourceType
import app.xreader.data.library.BookKindResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.2 (FIX-5, §7 / §10 item 8): tier-2 in production, with
 * the gate ARMED and DISARMED in the same suite so neither leg
 * is vacuous — a single-kind source (the Chikari shape: its
 * listings carry no kind field) can NEVER re-file a book no
 * matter how the reader's content classifies, while a
 * both-kinds source re-files only on a consistent streak of
 * two (hysteresis), persisted through the resolver.
 */
class V152KindTierTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun config(id: String, bothKinds: Boolean) = DeclarativeConfig(
        id = id,
        name = id,
        baseUrl = "https://$id.example",
        popular = ListSpec(
            url = "https://$id.example/list?page={page}",
            results = "",
            title = "title",
            link = "slug",
            jsonPath = "data",
            kindField = if (bothKinds) "type" else null,
            kindValueMap = if (bothKinds) {
                BookKind152.FENRIR_TYPE_MAP
            } else {
                emptyMap()
            }
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
        val service: ExtensionService,
        val bookDao: FakeBookDao,
        val rowIds: Map<String, Long>
    )

    private fun rig(): Rig = runBlocking {
        val filesDir = tmp.newFolder()
        val store = ExtensionStore(filesDir)
        val extDao = FakeInstalledExtensionDao()
        val sessionStore = testSessionStore()
        val transport = app.xreader.data.FakeTransport {
            AppResult.Ok(app.xreader.data.FakeTransport.response(404, ""))
        }
        val factory = HostContextFactory(store, sessionStore, transport)
        val repoDao = app.xreader.data.FakeExtensionRepoDao()
        val service = ExtensionService(
            extDao, store, factory, FakeDexLoader()
        ) { FakeJsRuntime() }
        val manager = RepoManager(
            repoDao, extDao, store, transport, service, sessionStore
        )
        val single = manager.saveLinkSource(
            config("singlekind", bothKinds = false), "singlekind.example"
        )
        val both = manager.saveLinkSource(
            config("bothkinds", bothKinds = true), "bothkinds.example"
        )
        val singleRow = (single as? AppResult.Ok)?.value
            ?: throw AssertionError("single-kind save failed: $single")
        val bothRow = (both as? AppResult.Ok)?.value
            ?: throw AssertionError("both-kinds save failed: $both")
        val bookDao = FakeBookDao()
        Rig(
            BookKindResolver(service, bookDao),
            service,
            bookDao,
            mapOf("single" to singleRow.id, "both" to bothRow.id)
        )
    }

    private fun book(extId: Long, kind: String?, sourceId: String) = Book(
        title = "Book",
        sourceType = SourceType.EXTENSION,
        extensionId = extId,
        // The production shape: SourceBookIds-encoded remote id
        // (the resolver decodes it to find the book's handle).
        remoteId = "$sourceId|book-1",
        kind = kind,
        addedAt = 1L,
        updatedAt = 1L
    )

    @Test
    fun `single-kind source - v158 B3 text prior re-files on three image verdicts`() =
        runBlocking {
            val rig = rig()
            val extId = rig.rowIds.getValue("single")
            val id = rig.bookDao.insert(book(extId, BookKind152.TEXT, "singlekind"))
            val stored = rig.bookDao.getById(id)!!
            // The legacy v1.5.2 gate (a declared kindField map)
            // really is absent for this source…
            val handle = (rig.service.source(extId, "singlekind")
                as? AppResult.Ok)?.value
                ?: throw AssertionError("no handle for the single-kind source")
            assertFalse(rig.resolver.isBothKinds(handle))
            // …but v1.5.8 (B3) SUPERSEDES the pin: tier-2 is
            // armed for every declarative source, and a TEXT
            // prior re-files on THREE consecutive image
            // verdicts with zero prose verdicts (the plan's
            // case (iv) bar). Two verdicts are absorbed — a
            // novel's colour plates split across two chapters
            // must not move it.
            repeat(2) {
                rig.resolver.applyTier2Evidence(stored, BookKind152.MANGA)
            }
            assertEquals(
                BookKind152.TEXT, rig.bookDao.getById(id)!!.kind
            )
            rig.resolver.applyTier2Evidence(stored, BookKind152.MANGA)
            assertEquals(
                BookKind152.MANGA, rig.bookDao.getById(id)!!.kind
            )
        }

    @Test
    fun `both-kinds source re-files on a consistent streak of two`() =
        runBlocking {
            val rig = rig()
            val extId = rig.rowIds.getValue("both")
            val handle = (rig.service.source(extId, "bothkinds")
                as? AppResult.Ok)?.value
                ?: throw AssertionError("no handle for the both-kinds source")
            assertTrue(rig.resolver.isBothKinds(handle))
            val id = rig.bookDao.insert(book(extId, null, "bothkinds"))
            val stored = rig.bookDao.getById(id)!!
            // One verdict: hysteresis holds, nothing persists.
            rig.resolver.applyTier2Evidence(stored, BookKind152.MANGA)
            assertNull(rig.bookDao.getById(id)!!.kind)
            // A dissenting verdict resets the streak.
            rig.resolver.applyTier2Evidence(stored, BookKind152.TEXT)
            rig.resolver.applyTier2Evidence(stored, BookKind152.MANGA)
            assertNull(rig.bookDao.getById(id)!!.kind)
            // Two consistent verdicts: the flip lands + persists.
            rig.resolver.applyTier2Evidence(stored, BookKind152.MANGA)
            assertEquals(
                BookKind152.MANGA, rig.bookDao.getById(id)!!.kind
            )
            // Tier-1 overrides immediately, no streak needed.
            rig.resolver.applyTier1(
                rig.bookDao.getById(id)!!, BookKind152.TEXT
            )
            assertEquals(
                BookKind152.TEXT, rig.bookDao.getById(id)!!.kind
            )
        }
}
