package app.xreader.data.sync

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.net.HttpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Where Drive access tokens come from. The production implementation
 * is Play-services Google Sign-In (the user's own account, signed in
 * inside the app — nothing pre-connected, PLAN §7); tests substitute
 * a fixed token. A token is a credential: implementations hold it
 * only for the call, like the KeyVault's rule.
 */
interface DriveAuthProvider {
    /** The signed-in account's address, or null when signed out. */
    suspend fun accountEmail(): String?

    suspend fun accessToken(): AppResult<String>

    suspend fun signOut()
}

/**
 * Google Drive REST v3, hand-rolled over [HttpTransport] (the app
 * pattern — no heavy SDK), restricted to the hidden `appDataFolder`:
 * the app can see ONLY its own sync file; the user's visible Drive
 * files are unreachable with the `drive.appdata` scope.
 */
class DriveClient(
    private val transport: HttpTransport,
    private val auth: DriveAuthProvider
) : SyncBackend {

    override val id: String = "drive"
    override val label: String = "Google Drive"

    internal data class RemoteFile(val id: String, val version: String?)

    override suspend fun read(name: String): AppResult<ByteArray?> {
        val file = when (val found = findFile(name)) {
            is AppResult.Err -> return found
            is AppResult.Ok -> found.value
        } ?: return AppResult.Ok(null)
        val token = when (val result = auth.accessToken()) {
            is AppResult.Err -> return result
            is AppResult.Ok -> result.value
        }
        val response = when (
            val result = transport.execute(
                HttpTransport.Request(
                    url = "$FILES/${file.id}?alt=media",
                    headers = mapOf("Authorization" to "Bearer $token")
                )
            )
        ) {
            is AppResult.Err -> return AppResult.Err(result.error)
            is AppResult.Ok -> result.value
        }
        return when (response.code) {
            in 200..299 -> {
                val bytes = runCatching { response.body?.readBytes() }.getOrNull()
                response.body?.close()
                AppResult.Ok(bytes)
            }
            404 -> {
                response.body?.close()
                AppResult.Ok(null)
            }
            401, 403 -> {
                response.body?.close()
                AppResult.Err(authError(response.code))
            }
            else -> {
                response.body?.close()
                AppResult.Err(httpError(response.code))
            }
        }
    }

    override suspend fun write(name: String, bytes: ByteArray): AppResult<String?> {
        val token = when (val result = auth.accessToken()) {
            is AppResult.Err -> return result
            is AppResult.Ok -> result.value
        }
        val existing = when (val found = findFile(name)) {
            is AppResult.Err -> return found
            is AppResult.Ok -> found.value
        }
        // Phase 6: bodies are bytes end-to-end — the JSON payload is
        // unchanged, and binary book files now survive the trip.
        val mediaType = if (name.endsWith(".json")) {
            "application/json"
        } else {
            "application/octet-stream"
        }
        val response = if (existing == null) {
            // Multipart create: metadata + media in one call.
            val boundary = "xreader-" + System.nanoTime().toString(16)
            val head = buildString {
                append("--")
                append(boundary)
                append("\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n")
                append("{\"name\":\"")
                append(name)
                append("\",\"parents\":[\"appDataFolder\"]}")
                append("\r\n--")
                append(boundary)
                append("\r\nContent-Type: ")
                append(mediaType)
                append("\r\n\r\n")
            }.toByteArray(Charsets.UTF_8)
            val tail = "\r\n--$boundary--".toByteArray(Charsets.UTF_8)
            val body = ByteArray(head.size + bytes.size + tail.size)
            head.copyInto(body, 0)
            bytes.copyInto(body, head.size)
            tail.copyInto(body, head.size + bytes.size)
            transport.execute(
                HttpTransport.Request(
                    url = "$UPLOAD?uploadType=multipart",
                    method = "POST",
                    headers = mapOf(
                        "Authorization" to "Bearer $token",
                        "Content-Type" to "multipart/related; boundary=$boundary"
                    ),
                    bodyBytes = body
                )
            )
        } else {
            transport.execute(
                HttpTransport.Request(
                    url = "$UPLOAD/${existing.id}?uploadType=media",
                    method = "PATCH",
                    headers = mapOf(
                        "Authorization" to "Bearer $token",
                        "Content-Type" to mediaType
                    ),
                    bodyBytes = bytes
                )
            )
        }
        val http = when (response) {
            is AppResult.Err -> return AppResult.Err(response.error)
            is AppResult.Ok -> response.value
        }
        val code = http.code
        val bodyText = runCatching {
            http.body?.readBytes()?.toString(Charsets.UTF_8)
        }.getOrNull()
        http.body?.close()
        return when (code) {
            in 200..299 -> {
                val version = bodyText?.let { parseFileJson(it)?.second }
                AppResult.Ok(version)
            }
            401, 403 -> AppResult.Err(authError(code))
            else -> AppResult.Err(httpError(code))
        }
    }

    private suspend fun findFile(name: String): AppResult<RemoteFile?> {
        val token = when (val result = auth.accessToken()) {
            is AppResult.Err -> return result
            is AppResult.Ok -> result.value
        }
        val query = java.net.URLEncoder.encode("name = '$name'", "UTF-8")
        val response = when (
            val result = transport.execute(
                HttpTransport.Request(
                    url = "$FILES?spaces=appDataFolder&q=$query" +
                        "&fields=files(id,name,version)",
                    headers = mapOf("Authorization" to "Bearer $token")
                )
            )
        ) {
            is AppResult.Err -> return AppResult.Err(result.error)
            is AppResult.Ok -> result.value
        }
        val code = response.code
        val bodyText = runCatching {
            response.body?.readBytes()?.toString(Charsets.UTF_8)
        }.getOrNull().orEmpty()
        response.body?.close()
        return when (code) {
            in 200..299 -> AppResult.Ok(parseListJson(bodyText))
            401, 403 -> AppResult.Err(authError(code))
            else -> AppResult.Err(httpError(code))
        }
    }

    private fun authError(code: Int): AppError =
        AppError.AuthFailed(
            "Google Drive",
            "HTTP $code — the sign-in may have expired; sign in again in Sync & backup"
        )

    private fun httpError(code: Int): AppError = when (code) {
        429 -> AppError.RateLimited("Google Drive")
        in 500..599 -> AppError.ProviderDown("Google Drive", code)
        else -> AppError.ProviderDown("Google Drive", code, retryable = false)
    }

    companion object {
        const val FILES = "https://www.googleapis.com/drive/v3/files"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"

        private val json = Json { ignoreUnknownKeys = true }

        /** files.list response → the first file's (id, version). */
        internal fun parseListJson(body: String): RemoteFile? {
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }
                .getOrNull() ?: return null
            val files = root["files"] as? kotlinx.serialization.json.JsonArray
                ?: return null
            val first = files.firstOrNull() as? JsonObject ?: return null
            val id = (first["id"] as? JsonPrimitive)?.content ?: return null
            val version = (first["version"] as? JsonPrimitive)?.content
            return RemoteFile(id, version)
        }

        /** A file resource → (id, version). */
        internal fun parseFileJson(body: String): Pair<String, String?>? {
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }
                .getOrNull() ?: return null
            val id = (root["id"] as? JsonPrimitive)?.content ?: return null
            val version = (root["version"] as? JsonPrimitive)?.content
            return id to version
        }
    }
}
