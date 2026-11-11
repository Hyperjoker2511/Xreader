package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeExtensionRepoDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.data.extension.ExtensionPermissions.Companion.encode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.10 (C1) gate — renamed from the plan's
 * V1510EpochJournalTest per the build map's re-derivation:
 * RepoManager has no DataStore and the CURRENT branch never
 * decodes a definition, so there is NO journal. The fix is
 * Finding 7's string-equality pre-check: a row whose stored
 * blob already equals the derived set's encoding skips the
 * parse entirely; any inequality runs the exact pre-fix
 * path (parse, compare, write-on-drift), byte-identical in
 * outcome. The parse counter is RepoManager's named seam.
 */
class V1510EpochPreCheckTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** V159BrightNovelHealTest's rig shape: real RepoManager
     * + ExtensionService over fake DAOs, dead-end transport
     * (the bundled pass is offline). */
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

    private fun brightNovelRow(rig: Rig) = runBlocking {
        rig.extDao.getByPackageId("bundled.bundled-brightnovel")
    } ?: throw AssertionError("no bundled BrightNovel row")

    @Test
    fun `matching rows cost zero parses on the CURRENT branch`() {
        runBlocking {
            val rig = Rig()
            rig.manager.installBundledDefaults()
            val before = rig.manager.epochCurrentParseCount
            // Second pass: every bundled row is CURRENT with a
            // blob written by encode() — the pre-check must
            // skip every parse.
            rig.manager.installBundledDefaults()
            assertEquals(
                "a matching row must never reach ExtensionPermissions.parse",
                before, rig.manager.epochCurrentParseCount
            )
        }
    }

    @Test
    fun `drifted blob parses once and is rewritten exactly as before`() {
        runBlocking {
            val rig = Rig()
            rig.manager.installBundledDefaults()
            val installed = brightNovelRow(rig)
            val shipped = BundledDefaults.configs()
                .first { it.id == "bundled-brightnovel" }
            val derived = DeclarativeSource.derivedPermissions(shipped)
            // The §5.3 blind-spot shape: CURRENT epoch, wrong
            // stored grant (the parked singular host).
            rig.extDao.update(
                installed.copy(
                    permissionsJson = """{"domains":["brightnovel.com"]}"""
                )
            )
            val before = rig.manager.epochCurrentParseCount
            rig.manager.installBundledDefaults()
            assertTrue(
                "a drifted blob must run the parse path",
                rig.manager.epochCurrentParseCount > before
            )
            val healed = brightNovelRow(rig)
            assertEquals(
                "write-on-drift lands the derived set, byte-identical",
                derived.encode(), healed.permissionsJson
            )
        }
    }

    @Test
    fun `equal-meaning blob in different bytes parses but is NOT rewritten`() {
        runBlocking {
            val rig = Rig()
            rig.manager.installBundledDefaults()
            val installed = brightNovelRow(rig)
            // Same derived set, different byte shape (spacing):
            // string pre-check misses, parse compares EQUAL,
            // and today's semantics leave the row untouched.
            val spaced = "{ \"domains\" : [\"brightnovels.com\"] }"
            assertEquals(
                ExtensionPermissions.parse(installed.permissionsJson),
                ExtensionPermissions.parse(spaced)
            )
            rig.extDao.update(installed.copy(permissionsJson = spaced))
            val before = rig.manager.epochCurrentParseCount
            rig.manager.installBundledDefaults()
            assertTrue(
                "a byte-different blob must reach the parse",
                rig.manager.epochCurrentParseCount > before
            )
            val after = brightNovelRow(rig)
            assertEquals(
                "an equal parsed set is left byte-identical (no drift write)",
                spaced, after.permissionsJson
            )
            assertEquals(installed.updatedAt, after.updatedAt)
        }
    }

    @Test
    fun `epoch-eligible branch is untouched - its decode path still replaces`() {
        runBlocking {
            val rig = Rig()
            rig.manager.installBundledDefaults()
            val installed = brightNovelRow(rig)
            val packageId = installed.packageId
            // Make the row epoch-ELIGIBLE again (remove the
            // sidecar) and drift its stored definition: the
            // eligible branch must decode + REPLACE exactly as
            // it did before C1 (a different branch from the
            // CURRENT pre-check, asserted unchanged).
            File(
                rig.store.packageDir(packageId),
                RepoManager.BUNDLED_EPOCH_FILE
            ).delete()
            rig.store.writeText(
                rig.store.definitionFile(packageId),
                DeclarativeFingerprints.encode(
                    listOf(
                        BundledDefaults.configs()
                            .first { it.id == "bundled-brightnovel" }
                            .copy(name = "Drifted Name")
                    )
                )
            )
            val versionBefore = installed.versionCode
            rig.manager.installBundledDefaults()
            val replaced = brightNovelRow(rig)
            assertEquals("BrightNovel", replaced.displayName)
            assertTrue(
                "the REPLACE branch bumps the row version as before",
                replaced.versionCode > versionBefore
            )
            val shipped = BundledDefaults.configs()
                .first { it.id == "bundled-brightnovel" }
            assertEquals(
                DeclarativeSource.derivedPermissions(shipped).encode(),
                replaced.permissionsJson
            )
        }
    }
}
