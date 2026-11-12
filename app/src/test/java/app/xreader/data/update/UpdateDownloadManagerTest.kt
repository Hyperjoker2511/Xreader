package app.xreader.data.update

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.42 pins: the update download's state machine, now
 * owned by [UpdateDownloadManager] (app-scoped — the
 * owner's "not background / can't back out" fix). The
 * download+verify boundary is faked; the repository's own
 * behaviour (checksum gate, partial deletion) is pinned by
 * AppUpdateCoreTest and untouched.
 *
 * Pinned here:
 *  - idle → Running (progress mapped to a fraction) →
 *    Ready, and the failure paths land on Failed with the
 *    plain-words copy;
 *  - hiding the card is not an input to the machine at
 *    all — nothing the UI does to visibility can stop a
 *    download; only cancel() can, and cancel resets to
 *    idle and sweeps the partial;
 *  - a second start while one runs is a no-op;
 *  - the orphan sweep only ever acts at idle: it removes
 *    a dead process's partial at relaunch and never
 *    touches a Ready file or a non-APK neighbour.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateDownloadManagerTest {

    private fun release(version: String = "1.5.42") = ReleaseInfo(
        tagName = "v$version",
        version = version,
        title = "Xreader $version",
        body = "## What's new\n- Something\n",
        htmlUrl = "https://example.com/releases/$version",
        apk = ReleaseAsset(
            name = "Xreader-v$version.apk",
            downloadUrl = "https://example.com/Xreader-v$version.apk",
            sizeBytes = 100L
        ),
        checksumAsset = null
    )

    private class Hooks {
        var started: String? = null
        var ready: String? = null
        var failedCount = 0
        var cancelledCount = 0
        var lastProgress: Float? = null
        var progressCalls = 0
    }

    private fun manager(
        scope: kotlinx.coroutines.CoroutineScope,
        dir: File,
        hooks: Hooks,
        boundary: suspend (
            ReleaseInfo, (Long, Long) -> Unit
        ) -> AppUpdateRepository.DownloadOutcome
    ) = UpdateDownloadManager(
        scope = scope,
        downloadAndVerify = boundary,
        updatesDir = { dir },
        onDownloadStarted = { hooks.started = it },
        onProgress = { _, fraction ->
            hooks.progressCalls++
            hooks.lastProgress = fraction
        },
        onReady = { hooks.ready = it },
        onFailed = { hooks.failedCount++ },
        onCancelled = { hooks.cancelledCount++ }
    )

    @Test
    fun `start runs progress to ready`() = runTest {
        val dir = Files.createTempDirectory("xr-mgr-ok").toFile()
        val hooks = Hooks()
        val gate = CompletableDeferred<Unit>()
        val apkFile = File(dir, "xreader-1.5.42.apk")
        val manager = manager(backgroundScope, dir, hooks) { _, onProgress ->
            onProgress(50L, 100L)
            gate.await()
            AppUpdateRepository.DownloadOutcome.Ready(
                apkFile,
                release()
            )
        }
        assertEquals(
            UpdateDownloadPhase.None,
            manager.state.value.phase
        )
        manager.start(release())
        runCurrent()
        val running = manager.state.value.phase as? UpdateDownloadPhase.Running
        assertTrue("expected Running, was ${manager.state.value.phase}", running != null)
        assertEquals(0.5, running!!.fraction!!.toDouble(), 0.001)
        assertEquals(50L, running.downloadedBytes)
        assertEquals(100L, running.totalBytes)
        assertEquals("1.5.42", hooks.started)
        assertEquals(0.5, hooks.lastProgress!!.toDouble(), 0.001)

        gate.complete(Unit)
        advanceUntilIdle()
        val ready = manager.state.value.phase as? UpdateDownloadPhase.Ready
        assertTrue("expected Ready, was ${manager.state.value.phase}", ready != null)
        assertEquals(apkFile.absolutePath, ready!!.filePath)
        assertEquals(apkFile.absolutePath, manager.state.value.readyFilePath)
        assertEquals("1.5.42", hooks.ready)
        assertEquals(0, hooks.failedCount)
        dir.deleteRecursively()
    }

    @Test
    fun `checksum failures land on failed with the plain words`() = runTest {
        val dir = Files.createTempDirectory("xr-mgr-bad").toFile()
        val hooks = Hooks()
        val manager = manager(backgroundScope, dir, hooks) { release, _ ->
            AppUpdateRepository.DownloadOutcome.ChecksumMismatch(release)
        }
        manager.start(release())
        advanceUntilIdle()
        val failed = manager.state.value.phase as? UpdateDownloadPhase.Failed
        assertTrue("expected Failed, was ${manager.state.value.phase}", failed != null)
        assertTrue(failed!!.text.contains("doesn't match"))
        assertNull(manager.state.value.readyFilePath)
        assertEquals(1, hooks.failedCount)
        dir.deleteRecursively()
    }

    @Test
    fun `a second start while running is a no-op`() = runTest {
        val dir = Files.createTempDirectory("xr-mgr-dupe").toFile()
        val hooks = Hooks()
        val gate = CompletableDeferred<Unit>()
        val manager = manager(backgroundScope, dir, hooks) { _, onProgress ->
            onProgress(10L, 100L)
            gate.await()
            AppUpdateRepository.DownloadOutcome.Failed("boom")
        }
        manager.start(release("1.5.42"))
        runCurrent()
        manager.start(release("9.9.9"))
        runCurrent()
        assertEquals("1.5.42", manager.state.value.release?.version)
        assertTrue(manager.state.value.phase is UpdateDownloadPhase.Running)
        gate.complete(Unit)
        advanceUntilIdle()
        dir.deleteRecursively()
    }

    @Test
    fun `cancel resets to idle and sweeps the partial`() = runTest {
        val dir = Files.createTempDirectory("xr-mgr-cancel").toFile()
        val hooks = Hooks()
        val partial = File(dir, "xreader-1.5.42.apk").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val never = CompletableDeferred<Unit>()
        val manager = manager(backgroundScope, dir, hooks) { _, onProgress ->
            onProgress(10L, 100L)
            never.await()
            AppUpdateRepository.DownloadOutcome.Failed("unreachable")
        }
        manager.start(release())
        runCurrent()
        assertTrue(manager.state.value.phase is UpdateDownloadPhase.Running)

        // Hiding the card is a UI act with no manager input:
        // the download is still Running afterwards. Only
        // cancel() stops it.
        assertTrue(manager.state.value.phase is UpdateDownloadPhase.Running)
        manager.cancel()
        assertEquals(UpdateDownloadPhase.None, manager.state.value.phase)
        assertNull(manager.state.value.readyFilePath)
        assertEquals(1, hooks.cancelledCount)
        advanceUntilIdle()
        assertFalse(
            "the cancelled download's partial must be swept",
            partial.exists()
        )
        dir.deleteRecursively()
    }

    @Test
    fun `sweep removes a dead process partial but never a ready file`() =
        runTest {
            val dir = Files.createTempDirectory("xr-mgr-sweep").toFile()
            val hooks = Hooks()
            val stale = File(dir, "xreader-1.5.41.apk").apply {
                writeBytes(byteArrayOf(9))
            }
            val neighbour = File(dir, "notes.txt").apply {
                writeText("keep me")
            }
            val manager = manager(backgroundScope, dir, hooks) { _, _ ->
                AppUpdateRepository.DownloadOutcome.Failed("unused")
            }
            // Fresh process, phase None: the orphan from a
            // killed download is removed; non-APK files are
            // not the manager's business.
            manager.sweepOrphanedDownloads()
            assertFalse(stale.exists())
            assertTrue(neighbour.exists())

            // Drive to Ready with the file inside the dir:
            // the sweep must not touch it (phase is Ready).
            val apkFile = File(dir, "xreader-1.5.42.apk").apply {
                writeBytes(byteArrayOf(7))
            }
            val readyManager = manager(backgroundScope, dir, hooks) { release, _ ->
                AppUpdateRepository.DownloadOutcome.Ready(apkFile, release)
            }
            readyManager.start(release())
            advanceUntilIdle()
            assertTrue(
                readyManager.state.value.phase is UpdateDownloadPhase.Ready
            )
            readyManager.sweepOrphanedDownloads()
            assertTrue(apkFile.exists())
            dir.deleteRecursively()
        }

    @Test
    fun `permission detour round-trips through ready`() = runTest {
        val dir = Files.createTempDirectory("xr-mgr-perm").toFile()
        val hooks = Hooks()
        val apkFile = File(dir, "xreader-1.5.42.apk")
        val manager = manager(backgroundScope, dir, hooks) { release, _ ->
            AppUpdateRepository.DownloadOutcome.Ready(apkFile, release)
        }
        // No verified file yet: the detour is unreachable.
        manager.showInstallPermissionNeeded()
        assertEquals(UpdateDownloadPhase.None, manager.state.value.phase)

        manager.start(release())
        advanceUntilIdle()
        manager.showInstallPermissionNeeded()
        assertEquals(
            UpdateDownloadPhase.NeedsInstallPermission,
            manager.state.value.phase
        )
        manager.restoreReadyAfterInstallSettings()
        val phase = manager.state.value.phase as? UpdateDownloadPhase.Ready
        assertTrue(phase != null)
        assertEquals(apkFile.absolutePath, phase!!.filePath)

        // The verified file vanishing is a plain-words
        // failure, and the path is forgotten.
        manager.reportReadyFileMissing()
        assertTrue(manager.state.value.phase is UpdateDownloadPhase.Failed)
        assertNull(manager.state.value.readyFilePath)
        dir.deleteRecursively()
    }
}
