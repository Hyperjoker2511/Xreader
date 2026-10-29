package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeExtensionRepoDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.data.db.TrustState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The trust life-cycle end-to-end (PLAN §9.1 gate): TOFU index pinning,
 * hash-checked installs, fingerprint-change updates staging + freezing,
 * re-trust, safe auto-updates, and tamper detection — all against
 * fakes, no device.
 */
class RepoManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var repoDao: FakeExtensionRepoDao
    private lateinit var extDao: FakeInstalledExtensionDao
    private lateinit var store: ExtensionStore
    private lateinit var service: ExtensionService
    private lateinit var manager: RepoManager

    private var indexBody: String = indexJson(versionCode = 1)
    private var artifactBody: String = "BUNDLE-V1"

    private fun setUp() {
        repoDao = FakeExtensionRepoDao()
        extDao = FakeInstalledExtensionDao()
        val filesDir = tmp.newFolder()
        store = ExtensionStore(filesDir)
        val vault = testVault()
        val sessionStore = SessionStore(vault)
        val transport = FakeTransport { request ->
            when {
                request.url == INDEX_URL -> FakeTransport.ok(indexBody)
                request.url == ARTIFACT_URL ->
                    FakeTransport.ok(artifactBody)
                else -> AppResult.Ok(FakeTransport.response(404, ""))
            }
        }
        val contextFactory = HostContextFactory(store, sessionStore, transport)
        service = ExtensionService(
            extDao, store, contextFactory, FakeDexLoader()
        ) { FakeJsRuntime() }
        manager = RepoManager(repoDao, extDao, store, transport, service, sessionStore)
    }

    /** Refresh a repo and accept its (changed) index, returning the
     * now-current index — the real user flow (§9.1 TOFU). */
    private suspend fun refreshAndAccept(repoId: Long): RepoIndex {
        val repo = repoDao.getById(repoId)!!
        val fetch = (manager.refresh(repo) as AppResult.Ok).value
        if (fetch is RepoManager.IndexFetch.Changed) {
            val accepted = manager.acceptIndexChange(repoDao.getById(repoId)!!)
            assertTrue(accepted is AppResult.Ok)
        }
        return manager.cachedIndex(repoId)!!
    }

    @Test
    fun `first fetch pins the index and a changed index needs acceptance`() = runBlocking {
        setUp()
        val repo = (manager.addRepo("Repo", INDEX_URL) as AppResult.Ok).value
        assertNotNull("TOFU pin recorded", repo.indexSha256)

        // Same body again → plain fetch.
        val again = (manager.refresh(repo) as AppResult.Ok).value
        assertTrue(again is RepoManager.IndexFetch.Fetched)

        // Changed body → NOT accepted silently.
        indexBody = indexJson(versionCode = 1, name = "Community Repo v2")
        val changed = (manager.refresh(repo) as AppResult.Ok).value
        assertTrue(changed is RepoManager.IndexFetch.Changed)
        val pinnedRow = repoDao.getById(repo.id)!!
        assertEquals(repo.indexSha256, pinnedRow.indexSha256)

        // Explicit acceptance re-pins.
        val accepted = manager.acceptIndexChange(pinnedRow)
        assertTrue(accepted is AppResult.Ok)
        assertFalse(repoDao.getById(repo.id)!!.indexSha256 == repo.indexSha256)
    }

    @Test
    fun `declarative install writes rules and a trusted row`() = runBlocking {
        setUp()
        val repo = (manager.addRepo("Repo", INDEX_URL) as AppResult.Ok).value
        val index = manager.cachedIndex(repo.id)!!
        val pkg = index.packages.first { it.kindEnum == ExtensionKind.DECLARATIVE }
        val installed = manager.install(repo, pkg)
        assertTrue(installed is AppResult.Ok)
        val row = (installed as AppResult.Ok).value
        assertEquals(TrustState.TRUSTED, row.trustState)
        assertEquals(
            DeclarativeFingerprints.of(pkg.sources), row.sha256
        )
        assertNotNull(store.readText(store.definitionFile(pkg.pkg)))
    }

    @Test
    fun `js install with mismatched hash is refused and writes no row`() = runBlocking {
        setUp()
        val repo = (manager.addRepo("Repo", INDEX_URL) as AppResult.Ok).value
        val index = manager.cachedIndex(repo.id)!!
        val pkg = index.packages.first { it.kindEnum == ExtensionKind.JS }
        // The artifact served doesn't hash to the declared sha256.
        val result = manager.install(repo, pkg)
        assertTrue(result is AppResult.Err)
        assertNull(extDao.getByPackageId(pkg.pkg))
    }

    @Test
    fun `js install with matching hash succeeds`() = runBlocking {
        setUp()
        artifactBody = "BUNDLE-V1"
        indexBody = indexJson(versionCode = 1, jsSha = Hashes.sha256Hex("BUNDLE-V1"))
        val repo = (manager.addRepo("Repo", INDEX_URL) as AppResult.Ok).value
        val index = manager.cachedIndex(repo.id)!!
        val pkg = index.packages.first { it.kindEnum == ExtensionKind.JS }
        val result = manager.install(repo, pkg)
        assertTrue(result is AppResult.Ok)
        val row = (result as AppResult.Ok).value
        assertEquals(Hashes.sha256Hex("BUNDLE-V1"), row.sha256)
    }

    @Test
    fun `changed-fingerprint update stages, freezes, and re-trust applies it`() = runBlocking {
        setUp()
        val repo = (manager.addRepo("Repo", INDEX_URL) as AppResult.Ok).value
        val indexV1 = manager.cachedIndex(repo.id)!!
        val pkgV1 = indexV1.packages.first { it.kindEnum == ExtensionKind.DECLARATIVE }
        val row = (manager.install(repo, pkgV1) as AppResult.Ok).value

        // v2: same package, changed rules (chapter selector differs).
        indexBody = indexJson(versionCode = 2, contentSelector = "div.story")
        val indexV2 = refreshAndAccept(repo.id)
        val candidates = manager.findUpdates(indexV2)
        val candidate = candidates.first { it.installed.packageId == pkgV1.pkg }
        assertTrue(
            candidate.decision is TrustEngine.UpdateDecision.RetrustRequired
        )
        val applied = manager.applyUpdate(candidate)
        assertTrue(applied is AppResult.Ok)
        val frozen = extDao.getById(row.id)!!
        assertEquals(TrustState.CHANGED_REQUIRES_RETRUST, frozen.trustState)
        assertTrue("update staged, not applied", store.hasPending(pkgV1.pkg))
        assertEquals("live rules untouched", 1L, frozen.versionCode)

        // The frozen source refuses to run.
        val refused = service.sourcesOf(row.id)
        assertTrue(refused is AppResult.Err)

        // Re-trust applies the staged update.
        val retrusted = manager.acceptPendingUpdate(frozen)
        assertTrue(retrusted is AppResult.Ok)
        val after = extDao.getById(row.id)!!
        assertEquals(TrustState.TRUSTED, after.trustState)
        assertEquals(2L, after.versionCode)
        assertFalse(store.hasPending(pkgV1.pkg))
    }

    @Test
    fun `safe update with unchanged fingerprint applies on tap`() = runBlocking {
        setUp()
        val repo = (manager.addRepo("Repo", INDEX_URL) as AppResult.Ok).value
        val indexV1 = manager.cachedIndex(repo.id)!!
        val pkgV1 = indexV1.packages.first { it.kindEnum == ExtensionKind.DECLARATIVE }
        val row = (manager.install(repo, pkgV1) as AppResult.Ok).value

        // v2 with a version bump only (name change, same rules).
        indexBody = indexJson(versionCode = 2, displayName = "Novels Example!")
        val indexV2 = refreshAndAccept(repo.id)
        val candidates = manager.findUpdates(indexV2)
        val candidate = candidates.first { it.installed.packageId == pkgV1.pkg }
        assertTrue(
            candidate.decision is TrustEngine.UpdateDecision.ApplyOnTap ||
                candidate.decision is TrustEngine.UpdateDecision.AutoApply
        )
        manager.applyUpdate(candidate)
        val after = extDao.getById(row.id)!!
        assertEquals(2L, after.versionCode)
        assertEquals(TrustState.TRUSTED, after.trustState)
    }

    @Test
    fun `tampered artifact freezes the extension at load`() = runBlocking {
        setUp()
        indexBody = indexJson(versionCode = 1, jsSha = Hashes.sha256Hex("BUNDLE-V1"))
        val repo = (manager.addRepo("Repo", INDEX_URL) as AppResult.Ok).value
        val index = manager.cachedIndex(repo.id)!!
        val pkg = index.packages.first { it.kindEnum == ExtensionKind.JS }
        val row = (manager.install(repo, pkg) as AppResult.Ok).value

        // Someone (or something) rewrites the script on disk.
        store.artifactFile(pkg.pkg).writeText("EVIL-BUNDLE")
        val loaded = service.sourcesOf(row.id)
        assertTrue(loaded is AppResult.Err)
        assertEquals(
            TrustState.CHANGED_REQUIRES_RETRUST,
            extDao.getById(row.id)!!.trustState
        )
    }

    @Test
    fun `widened permissions in an update require re-trust`() = runBlocking {
        setUp()
        val repo = (manager.addRepo("Repo", INDEX_URL) as AppResult.Ok).value
        val pkgV1 = manager.cachedIndex(repo.id)!!.packages
            .first { it.kindEnum == ExtensionKind.DECLARATIVE }
        val row = (manager.install(repo, pkgV1) as AppResult.Ok).value

        indexBody = indexJson(
            versionCode = 2, extraDomain = "tracking.example"
        )
        val indexV2 = refreshAndAccept(repo.id)
        val candidates = manager.findUpdates(indexV2)
        val candidate = candidates.first { it.installed.packageId == pkgV1.pkg }
        val decision = candidate.decision
        assertTrue(decision is TrustEngine.UpdateDecision.RetrustRequired)
        assertTrue(
            (decision as TrustEngine.UpdateDecision.RetrustRequired)
                .reasons.any { it.contains("tracking.example") }
        )
        assertEquals(row.id, candidate.installed.id)
    }

    companion object {
        const val INDEX_URL = "https://repo.example/index.json"
        const val ARTIFACT_URL = "https://repo.example/bundle.js"

        fun indexJson(
            versionCode: Long,
            name: String = "Community Repo",
            displayName: String = "Novels Example",
            contentSelector: String = "div.content",
            extraDomain: String? = null,
            jsSha: String = "deadbeef"
        ): String {
            val domains = listOfNotNull("novels.example", extraDomain)
                .joinToString(", ") { "\"$it\"" }
            return """
                {
                  "name": "$name",
                  "packages": [
                    {
                      "name": "$displayName", "pkg": "com.example.novels",
                      "versionName": "1.$versionCode", "versionCode": $versionCode,
                      "kind": "declarative", "languages": ["en"],
                      "permissions": {"domains": [$domains]},
                      "sources": [{
                        "id": "novels-example", "name": "Novels Example",
                        "lang": "en", "baseUrl": "https://novels.example",
                        "details": {"chapters": {"list": "ul li", "name": "a", "link": "a@href"}},
                        "chapter": {"content": "$contentSelector"}
                      }]
                    },
                    {
                      "name": "Scripted", "pkg": "com.example.js",
                      "versionName": "1.$versionCode", "versionCode": $versionCode,
                      "kind": "js", "artifactUrl": "$ARTIFACT_URL",
                      "sha256": "$jsSha",
                      "sourcesMeta": [{"id": "s1", "name": "S1", "lang": "en",
                        "baseUrl": "https://novels.example"}]
                    }
                  ]
                }
            """.trimIndent()
        }
    }
}
