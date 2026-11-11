package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.extension.ExtensionPermissions.Companion.encode
import app.xreader.data.FakeExtensionRepoDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.extensionapi.SourceBook
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.9 (Finding 2 §5.1 + §5.3): the stored-book-URL heal,
 * the derivation agreement pin, and the bundled permissions
 * blind-spot heal.
 *
 * §5.1 model: the SHIPPED BrightNovel definition and the
 * V158BrightNovelEndpointWinsFixture fake road (book page +
 * chapters endpoint on brightnovels.com). A book whose
 * STORED address sits on a foreign host — here the parked
 * singular brightnovel.com, the realistic legacy shape —
 * must heal at the walk's door: re-hosted onto the config's
 * baseUrl (path + query preserved), walked there, and the
 * details the walk returns carry the healed remote id.
 * The fake road answers ONLY the healed addresses, so a
 * walk that still used the stored address would 404.
 *
 * §5.3 harness: V154BrightNovelUpgradeTest's rig shape —
 * real RepoManager + ExtensionService over fake DAOs.
 */
class V159BrightNovelHealTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val bookUrl = "https://brightnovels.com/series/the-dullard"
    private val endpointUrl =
        "https://brightnovels.com/series/the-dullard/chapters/free" +
            "?loaded=0&sort_order=desc"
    /** The foreign stored address: the parked SINGULAR host. */
    private val foreignBookUrl =
        "https://brightnovel.com/series/the-dullard"

    private fun bookPageHtml(): String {
        val dataPage = "{&quot;component&quot;:&quot;Series/Show&quot;," +
            "&quot;props&quot;:{&quot;series&quot;:{&quot;title&quot;:" +
            "&quot;The Dullard&quot;}},&quot;url&quot;:&quot;/series/" +
            "the-dullard&quot;,&quot;version&quot;:&quot;1&quot;}"
        return """
            <html><head><title>The Dullard</title></head><body>
            <div id="app" data-page="$dataPage"></div>
            </body></html>
        """.trimIndent()
    }

    private fun endpointJson(): String =
        "{\"chapters\":[" +
            "{\"name\":\"Chapter 1\",\"slug\":\"chapter-1\"," +
            "\"updated_at\":\"2026-11-01\"}," +
            "{\"name\":\"Chapter 2\",\"slug\":\"chapter-2\"," +
            "\"updated_at\":\"2026-11-02\"}]}"

    private val expectedChapterIds = setOf(
        "https://brightnovels.com/series/the-dullard/chapter-1",
        "https://brightnovels.com/series/the-dullard/chapter-2"
    )

    private fun brightNovelSource(
        transport: FakeTransport
    ): DeclarativeSource {
        val config = BundledSingles.brightNovel(
            "bundled-brightnovel", "BrightNovel"
        )
        return DeclarativeSource(
            config,
            testContext(
                transport, tmp.newFolder(),
                ExtensionPermissions(domains = listOf("brightnovels.com"))
            )
        )
    }

    // ── (a) stored foreign host heals end-to-end ──────────

    @Test
    fun `foreign-host stored book heals and its chapters load`() {
        val transport = FakeTransport { request ->
            when (request.url) {
                bookUrl -> FakeTransport.ok(bookPageHtml())
                endpointUrl -> FakeTransport.ok(endpointJson())
                else -> AppResult.Ok(
                    FakeTransport.response(404, "no shape")
                )
            }
        }
        val source = brightNovelSource(transport)
        val (details, chapters) = source.detailsAndChapters(
            SourceBook(id = foreignBookUrl, title = "The Dullard")
        )
        assertEquals(
            "the healed remote id is the effective book address",
            bookUrl, details.id
        )
        assertEquals(
            expectedChapterIds, chapters.map { it.id }.toSet()
        )
        assertEquals(
            ChapterSuppliedBy.ENDPOINT, source.lastWalkSuppliedBy
        )
        val fetched = transport.requests.map { it.url }
        assertTrue(
            "the walk must fetch the healed book page, got $fetched",
            fetched.contains(bookUrl)
        )
        assertFalse(
            "the walk must never fetch the stored foreign " +
                "address, got $fetched",
            fetched.any { it.contains("brightnovel.com") }
        )
    }

    @Test
    fun `heal preserves path and query`() {
        val stored = "$foreignBookUrl?ref=legacy"
        val healed = "$bookUrl?ref=legacy"
        val transport = FakeTransport { request ->
            when (request.url) {
                healed -> FakeTransport.ok(bookPageHtml())
                endpointUrl -> FakeTransport.ok(endpointJson())
                else -> AppResult.Ok(
                    FakeTransport.response(404, "no shape")
                )
            }
        }
        val source = brightNovelSource(transport)
        assertEquals(healed, source.healedStoredBookUrl(stored))
        val (details, chapters) = source.detailsAndChapters(
            SourceBook(id = stored, title = "The Dullard")
        )
        assertEquals(healed, details.id)
        assertEquals(
            expectedChapterIds, chapters.map { it.id }.toSet()
        )
    }

    // ── (b) same-host + subdomain controls ────────────────

    @Test
    fun `book already on the allowed host is walked byte-identical`() {
        val transport = FakeTransport { request ->
            when (request.url) {
                bookUrl -> FakeTransport.ok(bookPageHtml())
                endpointUrl -> FakeTransport.ok(endpointJson())
                else -> AppResult.Ok(
                    FakeTransport.response(404, "no shape")
                )
            }
        }
        val source = brightNovelSource(transport)
        assertEquals(bookUrl, source.healedStoredBookUrl(bookUrl))
        val (details, chapters) = source.detailsAndChapters(
            SourceBook(id = bookUrl, title = "The Dullard")
        )
        assertEquals(bookUrl, details.id)
        assertEquals(
            expectedChapterIds, chapters.map { it.id }.toSet()
        )
        assertEquals(
            "the first fetch is the stored URL, byte-identical",
            bookUrl, transport.requests.first().url
        )
    }

    @Test
    fun `book on a subdomain of an allowed domain is untouched`() {
        val wwwBookUrl =
            "https://www.brightnovels.com/series/the-dullard"
        val transport = FakeTransport { request ->
            when (request.url) {
                wwwBookUrl -> FakeTransport.ok(bookPageHtml())
                endpointUrl -> FakeTransport.ok(endpointJson())
                else -> AppResult.Ok(
                    FakeTransport.response(404, "no shape")
                )
            }
        }
        val source = brightNovelSource(transport)
        assertEquals(
            wwwBookUrl, source.healedStoredBookUrl(wwwBookUrl)
        )
        val (details, chapters) = source.detailsAndChapters(
            SourceBook(id = wwwBookUrl, title = "The Dullard")
        )
        assertEquals(wwwBookUrl, details.id)
        assertEquals(
            expectedChapterIds, chapters.map { it.id }.toSet()
        )
        assertEquals(
            "the first fetch is the subdomain URL, byte-identical",
            wwwBookUrl, transport.requests.first().url
        )
    }

    // ── derivation agreement pin (§5.1) ───────────────────

    /** The full stored-path rig (V154BrightNovelUpgradeTest's
     * harness shape): real RepoManager + ExtensionService over
     * fake DAOs and a dead-end FakeTransport (the bundled pass
     * is offline). */
    private inner class Rig {
        val store = ExtensionStore(tmp.newFolder())
        val extDao = FakeInstalledExtensionDao()
        val repoDao = FakeExtensionRepoDao()
        val sessionStore = SessionStore(testVault())
        val transport = FakeTransport {
            AppResult.Ok(FakeTransport.response(404, ""))
        }
        val service = ExtensionService(
            extDao, store,
            HostContextFactory(store, sessionStore, transport),
            FakeDexLoader()
        ) { FakeJsRuntime() }
        val manager = RepoManager(
            repoDao, extDao, store, transport, service, sessionStore
        )
    }

    @Test
    fun `declarative derived permissions agree with RepoManager derivation`() {
        runBlocking {
            val rig = Rig()
            rig.manager.installBundledDefaults()
            val row = rig.extDao.getByPackageId(
                "bundled.bundled-brightnovel"
            ) ?: throw AssertionError("no bundled BrightNovel row")
            val shipped = BundledDefaults.configs()
                .first { it.id == "bundled-brightnovel" }
            assertEquals(
                "the heal's derived set must be the set " +
                    "RepoManager writes at install",
                ExtensionPermissions.parse(row.permissionsJson),
                DeclarativeSource.derivedPermissions(shipped)
            )
            assertEquals(
                setOf("brightnovels.com"),
                ExtensionPermissions.parse(row.permissionsJson)
                    .allowedDomains()
            )
        }
    }

    // ── (d) permissions heal on the CURRENT path (§5.3) ───

    @Test
    fun `stale permissionsJson at the current epoch is rewritten`() {
        runBlocking {
            val rig = Rig()
            rig.manager.installBundledDefaults()
            val installed = rig.extDao.getByPackageId(
                "bundled.bundled-brightnovel"
            ) ?: throw AssertionError("no bundled BrightNovel row")
            val shipped = BundledDefaults.configs()
                .first { it.id == "bundled-brightnovel" }
            val derived = DeclarativeSource.derivedPermissions(shipped)
            // The blind-spot row: definition + epoch CURRENT,
            // permissions written wrong by an earlier version
            // (the parked singular host instead of the real one).
            rig.extDao.update(
                installed.copy(
                    permissionsJson =
                        """{"domains":["brightnovel.com"]}"""
                )
            )
            rig.manager.installBundledDefaults()
            val healed = rig.extDao.getByPackageId(
                "bundled.bundled-brightnovel"
            ) ?: throw AssertionError("row vanished")
            assertEquals(
                "the CURRENT path must rewrite the stale " +
                    "permissions to the derived set",
                derived,
                ExtensionPermissions.parse(healed.permissionsJson)
            )
            assertEquals(
                derived.encode(), healed.permissionsJson
            )
        }
    }

    @Test
    fun `correct permissionsJson at the current epoch is left byte-identical`() {
        runBlocking {
            val rig = Rig()
            rig.manager.installBundledDefaults()
            val before = rig.extDao.getByPackageId(
                "bundled.bundled-brightnovel"
            ) ?: throw AssertionError("no bundled BrightNovel row")
            rig.manager.installBundledDefaults()
            val after = rig.extDao.getByPackageId(
                "bundled.bundled-brightnovel"
            ) ?: throw AssertionError("row vanished")
            assertEquals(
                "a correct row is left entirely alone — the " +
                    "whole row, permissionsJson bytes included",
                before, after
            )
        }
    }
}
