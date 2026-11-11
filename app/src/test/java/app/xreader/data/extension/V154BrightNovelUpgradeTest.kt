package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeExtensionRepoDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.data.db.ExtensionKind
import app.xreader.data.db.ExtensionRepo
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.TrustState
import app.xreader.extensionapi.Source
import app.xreader.extensionapi.SourceException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v1.5.4 (finding #3 close-out, WS-2 items 9–10): the
 * definition-epoch contract and its upgrade proofs.
 *
 * Item 10a: the SHIPPED BrightNovel definition, installed by
 * [RepoManager.installBundledDefaults] and read back through
 * the stored-definition path ([ExtensionService.source] —
 * the row's own definition file, never a lookalike config),
 * reads the documented Inertia payload
 * (src/test/resources/v154/brightnovel-series.html — the
 * donor-documented shape; a live capture is impossible from
 * this egress, see the fixture's provenance header).
 *
 * Item 10b: a stored bundled row seeded with the v1.5.2-era
 * DOM-anchor definition shape-misses BEFORE the pass and
 * reads rows AFTER it — both directions in one test. The
 * seed's row fingerprint is the stale definition's OWN
 * fingerprint, deliberately NOT one of the reconstructed
 * historical values: that is the device situation the
 * finding proved (the stored fingerprint matched no
 * reconstructed set member, so the v1.5.3 gate never fired).
 *
 * Item 10c: reconstruction independence — the same upgrade
 * with the stored sha mutated to a value in no shipped set,
 * with a blank sha, and with a partial definition file. The
 * epoch, not any hash, is the refresh decision (spec §0.3).
 *
 * Item 9 (protection direction): link-added and pack rows
 * with unknown fingerprints are left byte-for-byte alone.
 */
class V154BrightNovelUpgradeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private companion object {
        const val PACKAGE_ID = "bundled.bundled-brightnovel"
        const val SOURCE_ID = "bundled-brightnovel"
        const val SERIES_URL = "https://brightnovels.com/series"
        /** The epoch sidecar's on-disk name — a storage-format
         * fact the rollback simulations below must clear to
         * represent a pre-epoch (≤ v1.5.3) install. */
        const val EPOCH_FILE = "bundled-epoch"
    }

    private fun resourceText(name: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) {
            "missing test resource $name"
        }.readBytes().toString(Charsets.UTF_8)

    private fun shippedBrightNovel(): DeclarativeConfig =
        BundledDefaults.configs().first { it.id == SOURCE_ID }

    /** The full stored-path rig: real RepoManager +
     * ExtensionService over fake DAOs and a FakeTransport
     * serving [pageHtml] at the catalogue URL. */
    private inner class Rig(pageHtml: String) {
        val store = ExtensionStore(tmp.newFolder())
        val extDao = FakeInstalledExtensionDao()
        val repoDao = FakeExtensionRepoDao()
        val sessionStore = SessionStore(testVault())
        val transport = FakeTransport { request ->
            if (request.url == SERIES_URL) {
                FakeTransport.ok(pageHtml)
            } else {
                AppResult.Ok(FakeTransport.response(404, ""))
            }
        }
        val service = ExtensionService(
            extDao, store,
            HostContextFactory(store, sessionStore, transport),
            FakeDexLoader()
        ) { FakeJsRuntime() }
        val manager = RepoManager(
            repoDao, extDao, store, transport, service, sessionStore
        )

        suspend fun brightNovelSource(): Source {
            val row = extDao.getByPackageId(PACKAGE_ID)
                ?: throw AssertionError("no bundled BrightNovel row")
            val handle = (service.source(row.id, SOURCE_ID)
                as? AppResult.Ok)?.value
                ?: throw AssertionError("no handle for BrightNovel")
            return handle.source
        }

        fun epochFile(): File =
            File(store.packageDir(PACKAGE_ID), EPOCH_FILE)
    }

    /** The v1.5.2-era BrightNovel definition: catalogue read
     * by DOM anchors, no embedded-JSON path — the shape that
     * shape-missed on the owner's phone in both v1.5.2 and
     * (because the refresh never landed) v1.5.3. */
    private fun staleV152BrightNovel(): DeclarativeConfig {
        val listing = ListSpec(
            url = "https://brightnovels.com/series",
            results = "a[href^='/series/']",
            title = "@text",
            link = "@href",
            cover = "img@src"
        )
        return DeclarativeConfig(
            id = SOURCE_ID,
            name = "BrightNovel",
            baseUrl = "https://brightnovels.com",
            search = listing.copy(
                url = "https://brightnovels.com/series?search={query}"
            ),
            popular = listing,
            details = DetailsSpec(
                title = "h1",
                chapters = ChapterListSpec(
                    list = "a",
                    name = "@text",
                    link = "@href"
                )
            ),
            chapter = ChapterSpec(content = "div")
        )
    }

    /**
     * Roll the installed bundled BrightNovel row back to a
     * pre-epoch stored state: the definition file and row
     * fingerprint are replaced as given, and the epoch
     * sidecar is REMOVED — a ≤ v1.5.3 install has none, which
     * is precisely what makes the row epoch-eligible.
     */
    private suspend fun Rig.seedStoredState(
        sha256: String,
        definitionText: String
    ) {
        val row = extDao.getByPackageId(PACKAGE_ID)
            ?: throw AssertionError("no bundled BrightNovel row")
        extDao.update(row.copy(sha256 = sha256))
        store.writeText(store.definitionFile(PACKAGE_ID), definitionText)
        epochFile().delete()
        service.invalidate(row.id)
    }

    private fun assertCatalogueRows(source: Source) {
        val page = source.popular(1)
        assertEquals(2, page.books.size)
        assertEquals("The Dullard", page.books[0].title)
        assertEquals(
            "https://brightnovels.com/series/the-dullard",
            page.books[0].id
        )
        assertEquals(
            "https://brightnovels.com/covers/dullard.jpg",
            page.books[0].coverUrl
        )
        assertEquals("Second Son", page.books[1].title)
        assertEquals(
            "https://brightnovels.com/series/second-son",
            page.books[1].id
        )
        assertEquals(
            "https://brightnovels.com/covers/second.jpg",
            page.books[1].coverUrl
        )
    }

    private suspend fun Rig.assertUpgradedToShipped() {
        val storedText = store.readText(store.definitionFile(PACKAGE_ID))
        assertEquals(
            "the stored definition must be the shipped one",
            listOf(shippedBrightNovel()),
            DeclarativeFingerprints.decode(storedText ?: "")
        )
        val row = extDao.getByPackageId(PACKAGE_ID)!!
        assertEquals(
            DeclarativeFingerprints.of(listOf(shippedBrightNovel())),
            row.sha256
        )
        assertEquals(
            "the epoch sidecar must be stamped with the spec epoch " +
                "(BundledDefaults.BUNDLED_DEFINITIONS_EPOCH)",
            // v1.5.9 (E-3): read the constant itself — this
            // expectation is coupled to
            // BundledDefaults.BUNDLED_DEFINITIONS_EPOCH by
            // construction, so an epoch bump can never strand
            // a hard-coded literal here again.
            BundledDefaults.BUNDLED_DEFINITIONS_EPOCH.toString(),
            epochFile().readText().trim()
        )
        assertCatalogueRows(brightNovelSource())
    }

    // ── item 10a: shipped config through the stored path ──

    @Test
    fun `shipped brightNovel catalogue reads through the stored path`() {
        runBlocking {
            val rig = Rig(resourceText("v154/brightnovel-series.html"))
            rig.manager.installBundledDefaults()
            assertCatalogueRows(rig.brightNovelSource())
        }
    }

    // ── item 10b: stale seed, both directions ─────────────

    @Test
    fun `stale v152 seed shape-misses before the pass and reads after it`() {
        runBlocking {
            val rig = Rig(resourceText("v154/brightnovel-series.html"))
            rig.manager.installBundledDefaults()
            val stale = staleV152BrightNovel()
            rig.seedStoredState(
                sha256 = DeclarativeFingerprints.of(listOf(stale)),
                definitionText =
                    DeclarativeFingerprints.encode(listOf(stale))
            )
            // BEFORE the pass: the stored (stale) definition
            // shape-misses on the documented payload — the
            // owner's v1.5.3 symptom, reproduced.
            val error = assertThrows(SourceException::class.java) {
                runBlocking { rig.brightNovelSource() }.popular(1)
            }
            assertTrue(
                "expected the shape-miss marker, got: ${error.message}",
                error.message?.contains(
                    DeclarativeSource.SHAPE_MISS_MARKER
                ) == true
            )
            // The pass replaces the stale definition (epoch 0
            // → eligible), and the same stored path now reads.
            rig.manager.installBundledDefaults()
            rig.assertUpgradedToShipped()
        }
    }

    // ── item 10c: reconstruction independence ─────────────

    @Test
    fun `seed with a sha in no shipped set still upgrades`() {
        runBlocking {
            val rig = Rig(resourceText("v154/brightnovel-series.html"))
            rig.manager.installBundledDefaults()
            val stale = staleV152BrightNovel()
            rig.seedStoredState(
                sha256 = "0".repeat(64),
                definitionText =
                    DeclarativeFingerprints.encode(listOf(stale))
            )
            rig.manager.installBundledDefaults()
            rig.assertUpgradedToShipped()
        }
    }

    @Test
    fun `seed with a blank sha still upgrades`() {
        runBlocking {
            val rig = Rig(resourceText("v154/brightnovel-series.html"))
            rig.manager.installBundledDefaults()
            val stale = staleV152BrightNovel()
            rig.seedStoredState(
                sha256 = "",
                definitionText =
                    DeclarativeFingerprints.encode(listOf(stale))
            )
            rig.manager.installBundledDefaults()
            rig.assertUpgradedToShipped()
        }
    }

    @Test
    fun `seed with a partial definition file still upgrades`() {
        runBlocking {
            val rig = Rig(resourceText("v154/brightnovel-series.html"))
            rig.manager.installBundledDefaults()
            val stale = staleV152BrightNovel()
            rig.seedStoredState(
                sha256 = DeclarativeFingerprints.of(listOf(stale)),
                definitionText = "[{\"id\":\"bundled-bright"
            )
            rig.manager.installBundledDefaults()
            rig.assertUpgradedToShipped()
        }
    }

    // ── item 9: non-bundled rows are never touched ────────

    @Test
    fun `link added and pack rows are untouched by the bundled pass`() {
        runBlocking {
            val rig = Rig(resourceText("v154/brightnovel-series.html"))
            val foreignRepoId = rig.repoDao.insert(
                ExtensionRepo(
                    name = "Some provider pack",
                    indexUrl = "https://packs.example/index.json",
                    indexSha256 = null,
                    lastFetchedAt = null,
                    enabled = true
                )
            )
            fun foreignRow(packageId: String, repoId: Long) =
                InstalledExtension(
                    repoId = repoId,
                    displayName = "Foreign $packageId",
                    packageId = packageId,
                    versionName = "1.0",
                    versionCode = 7,
                    kind = ExtensionKind.DECLARATIVE,
                    artifactPath = null,
                    sha256 = "f".repeat(64),
                    apiLevel =
                        app.xreader.extensionapi.ExtensionApi.API_LEVEL,
                    languages = "en",
                    sourceIds = "foreign",
                    enabled = true,
                    trustState = TrustState.TRUSTED,
                    permissionsJson = """{"domains":["example.com"]}""",
                    installedAt = 1L,
                    updatedAt = 1L
                )
            val linkRow = foreignRow("link.added.example", foreignRepoId)
                .let { it.copy(id = rig.extDao.insert(it)) }
            val packRow = foreignRow("pack.example", foreignRepoId)
                .let { it.copy(id = rig.extDao.insert(it)) }
            rig.store.writeText(
                rig.store.definitionFile("link.added.example"),
                "USER-DEFINITION-BYTES"
            )
            rig.store.writeText(
                rig.store.definitionFile("pack.example"),
                "PACK-DEFINITION-BYTES"
            )
            rig.manager.installBundledDefaults()
            assertEquals(
                linkRow,
                rig.extDao.getByPackageId("link.added.example")
            )
            assertEquals(
                packRow,
                rig.extDao.getByPackageId("pack.example")
            )
            assertEquals(
                "USER-DEFINITION-BYTES",
                rig.store.readText(
                    rig.store.definitionFile("link.added.example")
                )
            )
            assertEquals(
                "PACK-DEFINITION-BYTES",
                rig.store.readText(
                    rig.store.definitionFile("pack.example")
                )
            )
        }
    }
}
