package app.xreader.data.extension.player

import android.app.Application
import android.os.NetworkOnMainThreadException
import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.data.extension.ExtensionPermissions
import app.xreader.data.extension.ExtensionPermissions.Companion.encode
import app.xreader.data.extension.SourceBookSync
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.7 Patch 1 (item 0 — Murim Login crash, 2026-11-19):
 * opening Murim Login (Asura Scans, Keiyoushi, 0 stored
 * chapters) killed the app with NetworkOnMainThreadException
 * — an okio socket WRITE on the main thread — while the
 * book page sat on "Finding chapters…".
 *
 * Spec-vs-tree: the spec's stated mechanism (the chapter
 * fetch "bridges via runBlocking on the caller's thread")
 * does NOT match this tree — the Mihon fetch already runs
 * on the IO bridge executor end to end (callMihon,
 * ExtensionService.guarded, HttpTransport) and the main
 * thread only awaits it. What the tree DID get wrong is
 * containment: SourceBookSync.refresh() and
 * BookDetailViewModel.refreshChapters() caught ONLY the two
 * SQL exception classes, so a NetworkOnMainThreadException
 * from any leg on the book-open path escaped both nets and
 * died uncaught in a viewModelScope launch on Main.
 *
 * These tests pin the contract the fix establishes, with
 * the crash's own exception class: a transport that throws
 * NetworkOnMainThreadException on the calling thread (the
 * cover fetch inside applyDetails runs there) must surface
 * as an honest AppResult.Err — the failed-refresh state
 * with Retry — never as a throw out of refresh().
 *
 * Fail-before: on the pre-fix tree both containment tests
 * fail — the exception escapes refresh() because neither
 * SQL catch covers it, and runBlocking rethrows it. The
 * happy-path test proves the working road (Nano Machine /
 * SSS-Class Suicide Hunter) is unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class Patch1MurimCrashTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A 0-chapter Mihon book whose details carry a cover
     * on panels.test, over a sync transport that either
     * answers covers or throws the crash's exception class
     * from the cover fetch. The row's domains open
     * panels.test so the cover fetch reaches the transport
     * instead of stopping at RefusedPermission. */
    private fun stack(throwingTransport: Boolean): V158PlayerStack {
        val syncTransport = if (throwingTransport) {
            FakeTransport {
                throw NetworkOnMainThreadException()
            }
        } else {
            null
        }
        return V158PlayerStacks.build(
            source = V158FakeMihon(),
            storageDir = tmp.newFolder(),
            permissionsJson = ExtensionPermissions(
                domains = listOf("panels.test")
            ).encode(),
            syncTransport = syncTransport
        )
    }

    @Test
    fun `book open survives a main-thread network throw as an honest Err`() =
        runBlocking {
            val stack = stack(throwingTransport = true)
            val book = stack.seedBook()
            val result = stack.sync.ensureChapters(
                book, SourceBookSync.FIRST_CHUNK_MIN
            )
            assertTrue(
                "a NetworkOnMainThreadException on the book-open " +
                    "path must land as an honest Err, never a throw",
                result is AppResult.Err
            )
            assertTrue(
                "the failure is a source failure with a Retry " +
                    "door, not a storage failure",
                (result as AppResult.Err).error
                    is AppError.ExtensionFailed
            )
        }

    @Test
    fun `refresh survives a main-thread network throw as an honest Err`() =
        runBlocking {
            val stack = stack(throwingTransport = true)
            val book = stack.seedBook()
            val result = stack.sync.refresh(book)
            assertTrue(
                "a NetworkOnMainThreadException out of refresh() " +
                    "must land as an honest Err, never a throw",
                result is AppResult.Err
            )
            assertTrue(
                "the failure is a source failure with a Retry " +
                    "door, not a storage failure",
                (result as AppResult.Err).error
                    is AppError.ExtensionFailed
            )
        }

    @Test
    fun `happy path still lands the chapter list`() = runBlocking {
        val stack = stack(throwingTransport = false)
        val book = stack.seedBook()
        val result = stack.sync.ensureChapters(
            book, SourceBookSync.FIRST_CHUNK_MIN
        )
        assertTrue(
            "the working road must still land chapters",
            result is AppResult.Ok
        )
        assertEquals(
            "the fake's two chapters land",
            2, stack.chapters.forBook(book.id).size
        )
    }
}
