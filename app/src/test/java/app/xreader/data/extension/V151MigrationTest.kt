package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeBookDao
import app.xreader.data.FakeChapterSourceMapDao
import app.xreader.data.FakeExtensionRepoDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.data.db.Book
import app.xreader.data.db.ExtensionKind
import app.xreader.data.db.ExtensionRepo
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.SourceType
import app.xreader.data.db.TrustState
import app.xreader.data.prefs.SourcePrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.1 (item 1): the update-time source migration, seeded
 * with OLD-format (v1.4.x/1.5.0 stored shape) learned
 * sources —
 *  - a LIVE learned row for chikari.moe (a LiveSourceSpec
 *    definition, the shape dedupe could never group) standing
 *    beside the BUNDLED Chikari row, with a library book
 *    bound to the learned copy;
 *  - a learned API-shell site (a declarative link row whose
 *    stored rules are a stale scrape) that the current
 *    learner re-derives through the API road when the probe
 *    is fed its harvest;
 *  - a learned site the learner cannot re-derive (kept,
 *    failure-isolated, audit line written).
 *
 * After the pass: exactly one source per site, the book
 * re-pointed at the bundled survivor, the re-derived row's
 * rules replaced (old rules gone), the unfixable row
 * untouched, the version flag set, and a second run a no-op.
 */
class V151MigrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repo(url: String, name: String) = ExtensionRepo(
        name = name,
        indexUrl = url,
        enabled = true,
        lastFetchedAt = null
    )

    private fun row(
        repoId: Long,
        name: String,
        packageId: String,
        kind: ExtensionKind,
        sourceIds: String
    ) = InstalledExtension(
        repoId = repoId,
        displayName = name,
        packageId = packageId,
        versionName = "1.0",
        versionCode = 1,
        kind = kind,
        artifactPath = null,
        sha256 = "seed",
        apiLevel = 1,
        languages = "en",
        sourceIds = sourceIds,
        enabled = true,
        trustState = TrustState.TRUSTED,
        permissionsJson = kotlinx.serialization.json.Json.encodeToString(
            ExtensionPermissions(domains = listOf("example"))
        ),
        installedAt = 0,
        updatedAt = 0
    )

    private fun chikariConfig(id: String) = DeclarativeConfig(
        id = id,
        name = "Chikari",
        lang = "en",
        baseUrl = "https://chikari.moe",
        popular = ListSpec(
            url = "https://chikari.moe/browse",
            results = "a", title = "@text", link = "@href"
        ),
        search = ListSpec(
            url = "https://chikari.moe/search?q={query}",
            results = "a", title = "@text", link = "@href"
        ),
        details = DetailsSpec(
            chapters = ChapterListSpec(list = "a", name = "@text", link = "@href")
        ),
        chapter = ChapterSpec(content = "body")
    )

    @Test
    fun `migration merges the learned duplicate, re-derives what it can, keeps what it cannot`() =
        runBlocking {
            val store = ExtensionStore(tmp.newFolder("store"))
            val repoDao = FakeExtensionRepoDao()
            val extDao = FakeInstalledExtensionDao()
            val bookDao = FakeBookDao()
            val bundledRepoId = repoDao.insert(
                repo(RepoManager.BUNDLED_REPO_URL, "Built-in")
            )
            val linkRepoId = repoDao.insert(
                repo(RepoManager.LINK_REPO_URL, "Added by you")
            )

            // The bundled Chikari (shipped default).
            val bundled = row(
                bundledRepoId, "Chikari", "bundled.chikari",
                ExtensionKind.DECLARATIVE, "chikari-bundled"
            )
            val bundledId = extDao.insert(bundled)
            store.writeText(
                store.definitionFile("bundled.chikari"),
                DeclarativeFingerprints.encode(
                    listOf(chikariConfig("chikari-bundled"))
                )
            )

            // The learned Live copy of the same site — the
            // v1.5.0 field shape (LiveSourceSpec definition).
            val learnedChikari = row(
                linkRepoId, "Chikari", "link.chikari-live",
                ExtensionKind.LIVE, "chikari-live"
            )
            val learnedChikariId = extDao.insert(learnedChikari)
            store.writeText(
                store.definitionFile("link.chikari-live"),
                LiveSourceSpec(
                    id = "chikari-live",
                    name = "Chikari",
                    baseUrl = "https://www.chikari.moe",
                    startUrl = "https://www.chikari.moe"
                ).encode()
            )

            // A library book bound to the LEARNED copy.
            val bookId = bookDao.insert(
                Book(
                    title = "Sword Saga",
                    sourceType = SourceType.EXTENSION,
                    remoteId = SourceBookIds.encode(
                        "chikari-live", "https://chikari.moe/novels/sword-saga"
                    ),
                    extensionId = learnedChikariId,
                    totalChapters = 0,
                    addedAt = 0,
                    updatedAt = 0
                )
            )

            // A learned site the learner cannot re-derive
            // (its transport is dead): kept, isolated.
            val dead = row(
                linkRepoId, "Dead Site", "link.dead",
                ExtensionKind.DECLARATIVE, "dead-1"
            )
            extDao.insert(dead)
            store.writeText(
                store.definitionFile("link.dead"),
                DeclarativeFingerprints.encode(
                    listOf(
                        DeclarativeConfig(
                            id = "dead-1",
                            name = "Dead Site",
                            lang = "en",
                            baseUrl = "https://dead.example",
                            popular = ListSpec(
                                url = "https://dead.example/list",
                                results = "a", title = "@text", link = "@href"
                            ),
                            details = DetailsSpec(
                                chapters = ChapterListSpec(
                                    list = "a", name = "@text", link = "@href"
                                )
                            ),
                            chapter = ChapterSpec(content = "body"),
                            sampleBookUrl = "https://dead.example/book/1"
                        )
                    )
                )
            )

            val deadTransport = FakeTransport {
                AppResult.Ok(FakeTransport.response(503, "gone"))
            }
            val factory = HostContextFactory(
                store, testSessionStore(), deadTransport
            )
            val prober = LinkProber(factory, testSessionStore())
            val sessionStore = testSessionStore()
            val service = ExtensionService(
                extDao, store, factory, FakeDexLoader()
            ) { FakeJsRuntime() }
            val repoManager = RepoManager(
                repoDao, extDao, store, deadTransport, service, sessionStore
            )
            val dedupe = SourceDedupe(
                extDao, repoDao, bookDao,
                FakeChapterSourceMapDao(), store, service
            )
            val prefs = SourcePrefs(FakePreferencesStore())
            val migration = SourceUpdateMigration(
                extDao, repoDao, store, prober, repoManager,
                dedupe, prefs
            )

            val lines = migration.runIfNeeded(43)
            assertNotNull(lines)

            // Exactly one Chikari survives: the bundled one.
            val after = extDao.allNow()
            val chikaris = after.filter {
                it.displayName == "Chikari"
            }
            assertEquals(1, chikaris.size)
            assertEquals("bundled.chikari", chikaris.single().packageId)

            // The book was re-pointed at the survivor, its
            // remote book id preserved under the survivor's
            // source id.
            val book = bookDao.getById(bookId)!!
            assertEquals(bundledId, book.extensionId)
            assertEquals(
                "chikari-bundled" to "https://chikari.moe/novels/sword-saga",
                SourceBookIds.decode(book.remoteId)
            )

            // The unfixable source kept its row (failure
            // isolation), and the pass wrote audit lines for
            // both stories.
            assertNotNull(extDao.getByPackageId("link.dead"))
            assertTrue(
                lines!!.any { "MIGRATE" in it && "Chikari" in it }
            )
            assertTrue(
                lines.any { "MIGRATE kept" in it && "Dead Site" in it }
            )

            // Version-gated + idempotent: a second run for
            // the same version does nothing.
            assertNull(migration.runIfNeeded(43))
            assertEquals(
                43, prefs.sourceMigrationDoneForCode.first()
            )
            assertEquals(
                1, extDao.allNow().count { it.displayName == "Chikari" }
            )
        }
}
