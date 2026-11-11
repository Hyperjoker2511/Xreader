package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeExtensionRepoDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.2 FIX-11 (§8.1), doctrine superseded in v1.5.4
 * (spec §0.3): the shipped-fingerprint sets were the refresh
 * allow-list — an untouched legacy row refreshed only while
 * its stored fingerprint sat in a reconstructed set. That
 * gate stranded a shipped definition on the owner's phone
 * (finding #3: the stored hash matched no reconstructed
 * value), so the refresh decision is now the DEFINITIONS
 * EPOCH: a bundled-provenance row whose recorded epoch is
 * older than [BundledDefaults.BUNDLED_DEFINITIONS_EPOCH]
 * refreshes to the current rules whatever fingerprint it
 * carries — legacy vintages AND a user-edited bundled row
 * alike (provenance, not edit-detection, is the boundary).
 * The rows the pass must never touch are the ones it never
 * visits — link-added and pack rows — proven in
 * V154BrightNovelUpgradeTest. The fingerprint values below
 * stay as the vintage seeds they always were.
 */
class V152FingerprintRefreshTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** chikari's fingerprint as written by v1.4.1–v1.4.3. */
    private val chikariV141 =
        "d8b42db2b4dde71e9a5c3a31888ee624e4044f7c79935207695df32ad67eaf12"

    /** FenrirRealm's fingerprint as written by v1.5.0–v1.5.1. */
    private val fenrirV151 =
        "87f540da2bb8f398c85f7f80e34dc7f51a3cfaa4d902fc9d6032c5d6b4a19aeb"

    private fun managerWith(
        extDao: FakeInstalledExtensionDao,
        store: ExtensionStore
    ): RepoManager {
        val repoDao = FakeExtensionRepoDao()
        val sessionStore = SessionStore(testVault())
        val transport = FakeTransport {
            AppResult.Ok(FakeTransport.response(404, ""))
        }
        val contextFactory = HostContextFactory(store, sessionStore, transport)
        val service = ExtensionService(
            extDao, store, contextFactory, FakeDexLoader()
        ) { FakeJsRuntime() }
        return RepoManager(
            repoDao, extDao, store, transport, service, sessionStore
        )
    }

    @Test
    fun legacyRowsFromTwoVintagesRefreshAndEditedBundledRowReplaces() {
        runBlocking {
            val extDao = FakeInstalledExtensionDao()
            val store = ExtensionStore(tmp.newFolder())
            val manager = managerWith(extDao, store)
            // First install: every bundled row lands at the
            // current fingerprint.
            manager.installBundledDefaults()
            val current = BundledDefaults.configs().associate {
                it.id to DeclarativeFingerprints.of(listOf(it))
            }
            // Roll two rows back to legacy vintages (their
            // stored hashes + definition files as the old app
            // versions left them) and one row to a user edit.
            // v1.5.4: a pre-epoch install has no epoch sidecar,
            // so the rollback deletes the one the first install
            // stamped — that absence (epoch 0) is what makes
            // each row eligible for the epoch pass.
            suspend fun rollBack(packageId: String, sha: String, body: String) {
                val row = extDao.getByPackageId(packageId)!!
                extDao.update(row.copy(sha256 = sha))
                store.writeText(store.definitionFile(packageId), body)
                java.io.File(
                    store.packageDir(packageId), "bundled-epoch"
                ).delete()
            }
            rollBack("bundled.bundled-chikari", chikariV141, "LEGACY-V141")
            rollBack("bundled.bundled-fenrirealm", fenrirV151, "LEGACY-V151")
            rollBack(
                "bundled.bundled-novelbuddy",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "USER-EDITED"
            )
            manager.installBundledDefaults()
            // Both vintages refreshed to the current rules.
            for ((packageId, configId) in listOf(
                "bundled.bundled-chikari" to "bundled-chikari",
                "bundled.bundled-fenrirealm" to "bundled-fenrirealm"
            )) {
                val row = extDao.getByPackageId(packageId)!!
                assertEquals(
                    "$packageId must carry the current fingerprint",
                    current[configId], row.sha256
                )
                val decoded = DeclarativeFingerprints.decode(
                    store.readText(store.definitionFile(packageId))!!
                )
                assertEquals(
                    "$packageId definition must be the current rules",
                    configId, decoded.single().id
                )
            }
            // v1.5.4 (spec §0.3): the user-edited BUNDLED row
            // is replaced too — bundled provenance is the
            // app's own to move at an epoch boundary; the old
            // "an edit leaves the fingerprint set, so the row
            // is never touched" protection retired with the
            // gate that stranded BrightNovel. (Link-added and
            // pack rows keep the never-touched guarantee —
            // they are not bundled provenance and the pass
            // never visits them.)
            val edited = extDao.getByPackageId("bundled.bundled-novelbuddy")!!
            assertEquals(
                "the edited bundled row must carry the current fingerprint",
                current["bundled-novelbuddy"], edited.sha256
            )
            val editedDecoded = DeclarativeFingerprints.decode(
                store.readText(
                    store.definitionFile("bundled.bundled-novelbuddy")
                )!!
            )
            assertEquals(
                "bundled-novelbuddy", editedDecoded.single().id
            )
            assertTrue(true)
        }
    }
}
