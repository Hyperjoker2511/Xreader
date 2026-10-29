package app.xreader.data.sync

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebDAV backend contract (Amendment 3 gate) against a scripted
 * fixture server: 404 reads as "nothing synced yet", writes go to
 * the folder URL with Basic auth, auth failures are AuthFailed, and
 * the probe uses PROPFIND.
 */
class WebDavBackendTest {

    private fun backend(transport: FakeTransport) = WebDavBackend(
        transport = transport,
        baseUrlProvider = { "https://dav.example.com/xreader/" },
        credentialsProvider = { "reader" to "s3cret" }
    )

    @Test
    fun `missing file reads as nothing synced yet`() = runBlocking {
        val transport = FakeTransport {
            AppResult.Ok(FakeTransport.response(404, "not found"))
        }
        val result = backend(transport).read(SyncBackend.SYNC_FILE_NAME)
        assertTrue(result is AppResult.Ok)
        assertNull((result as AppResult.Ok).value)
        assertEquals(
            "https://dav.example.com/xreader/xreader-sync.json",
            transport.requests.single().url
        )
        assertEquals("GET", transport.requests.single().method)
    }

    @Test
    fun `existing file reads its bytes`() = runBlocking {
        val transport = FakeTransport { FakeTransport.ok("""{"format":1}""") }
        val result = backend(transport).read(SyncBackend.SYNC_FILE_NAME)
        assertTrue(result is AppResult.Ok)
        assertEquals("""{"format":1}""", String((result as AppResult.Ok).value!!))
    }

    @Test
    fun `write PUTs the payload with basic auth`() = runBlocking {
        val transport = FakeTransport {
            AppResult.Ok(FakeTransport.response(201, ""))
        }
        val result = backend(transport)
            .write(SyncBackend.SYNC_FILE_NAME, "{}".toByteArray())
        assertTrue(result is AppResult.Ok)
        val request = transport.requests.single()
        assertEquals("PUT", request.method)
        assertEquals("reader" to "s3cret", request.basicAuth)
    }

    @Test
    fun `auth failure is an AuthFailed not a retry loop`() = runBlocking {
        val transport = FakeTransport {
            AppResult.Ok(FakeTransport.response(401, "unauthorized"))
        }
        val result = backend(transport).read(SyncBackend.SYNC_FILE_NAME)
        assertTrue(result is AppResult.Err)
        assertTrue(
            (result as AppResult.Err).error is AppError.AuthFailed
        )
    }

    @Test
    fun `probe uses PROPFIND on the folder`() = runBlocking {
        val transport = FakeTransport {
            AppResult.Ok(FakeTransport.response(207, "<multistatus/>"))
        }
        val result = backend(transport).probe()
        assertTrue(result is AppResult.Ok)
        assertEquals("PROPFIND", transport.requests.single().method)
        assertEquals("https://dav.example.com/xreader", transport.requests.single().url)
    }

    @Test
    fun `blank server url fails plainly`() = runBlocking {
        val backend = WebDavBackend(
            transport = FakeTransport { FakeTransport.ok("") },
            baseUrlProvider = { "" },
            credentialsProvider = { null }
        )
        val result = backend.read(SyncBackend.SYNC_FILE_NAME)
        assertTrue(result is AppResult.Err)
        assertTrue((result as AppResult.Err).error is AppError.BadFile)
    }
}
