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
 * Google Drive backend contract against a scripted Drive REST
 * fixture: list-in-appDataFolder → download for reads, multipart
 * create then media PATCH for writes, and 401 → AuthFailed. (The
 * live account itself is device-bound; this pins the wire contract.)
 */
class DriveClientTest {

    private class FakeAuth(var token: String? = "token-1") : DriveAuthProvider {
        override suspend fun accountEmail(): String? =
            if (token == null) null else "reader@example.com"
        override suspend fun accessToken(): AppResult<String> =
            token?.let { AppResult.Ok(it) }
                ?: AppResult.Err(AppError.AuthFailed("Google Drive", "signed out"))
        override suspend fun signOut() {
            token = null
        }
    }

    private val listWithFile =
        """{"files":[{"id":"file-9","name":"xreader-sync.json","version":"7"}]}"""
    private val listEmpty = """{"files":[]}"""

    @Test
    fun `read downloads the app folder file`() = runBlocking {
        val transport = FakeTransport { request ->
            when {
                request.url.contains("alt=media") -> FakeTransport.ok("""{"format":1}""")
                request.url.contains("spaces=appDataFolder") ->
                    FakeTransport.ok(listWithFile)
                else -> AppResult.Ok(FakeTransport.response(404, ""))
            }
        }
        val client = DriveClient(transport, FakeAuth())
        val result = client.read(SyncBackend.SYNC_FILE_NAME)
        assertTrue(result is AppResult.Ok)
        assertEquals("""{"format":1}""", String((result as AppResult.Ok).value!!))
        assertTrue(
            transport.requests.all {
                it.headers["Authorization"] == "Bearer token-1"
            }
        )
    }

    @Test
    fun `read with no file yet is null not an error`() = runBlocking {
        val transport = FakeTransport { FakeTransport.ok(listEmpty) }
        val result = DriveClient(transport, FakeAuth()).read(SyncBackend.SYNC_FILE_NAME)
        assertTrue(result is AppResult.Ok)
        assertNull((result as AppResult.Ok).value)
    }

    @Test
    fun `first write creates multipart, later writes patch`() = runBlocking {
        var fileExists = false
        val methods = mutableListOf<String>()
        val transport = FakeTransport { request ->
            methods += request.method
            when {
                request.url.contains("uploadType=multipart") -> {
                    fileExists = true
                    AppResult.Ok(
                        FakeTransport.response(
                            200, """{"id":"file-9","version":"1"}"""
                        )
                    )
                }
                request.url.contains("uploadType=media") ->
                    AppResult.Ok(
                        FakeTransport.response(
                            200, """{"id":"file-9","version":"2"}"""
                        )
                    )
                request.url.contains("spaces=appDataFolder") ->
                    FakeTransport.ok(if (fileExists) listWithFile else listEmpty)
                else -> AppResult.Ok(FakeTransport.response(404, ""))
            }
        }
        val client = DriveClient(transport, FakeAuth())
        val first = client.write(SyncBackend.SYNC_FILE_NAME, "{}".toByteArray())
        assertTrue(first is AppResult.Ok)
        val second = client.write(SyncBackend.SYNC_FILE_NAME, "{}".toByteArray())
        assertTrue(second is AppResult.Ok)
        assertTrue("create used POST", methods.contains("POST"))
        assertTrue("update used PATCH", methods.contains("PATCH"))
    }

    @Test
    fun `expired sign-in surfaces as AuthFailed`() = runBlocking {
        val transport = FakeTransport {
            AppResult.Ok(FakeTransport.response(401, "unauthorized"))
        }
        val result = DriveClient(transport, FakeAuth())
            .read(SyncBackend.SYNC_FILE_NAME)
        assertTrue(result is AppResult.Err)
        assertTrue((result as AppResult.Err).error is AppError.AuthFailed)
    }

    @Test
    fun `signed out fails before any request`() = runBlocking {
        val transport = FakeTransport { FakeTransport.ok(listEmpty) }
        val result = DriveClient(transport, FakeAuth(token = null))
            .read(SyncBackend.SYNC_FILE_NAME)
        assertTrue(result is AppResult.Err)
        assertEquals(0, transport.requests.size)
    }
}
