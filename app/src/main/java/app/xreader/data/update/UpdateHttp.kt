package app.xreader.data.update

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The updater's HTTP seam: the two operations it needs,
 * nothing more — so the repository is testable against a
 * fake and the real client stays a thin OkHttp adapter.
 */
interface UpdateHttp {

    /** A small text GET (the release JSON, a checksum file).
     * Null on any failure — callers degrade to their cache. */
    suspend fun getText(url: String): String?

    /**
     * Streams [url] into [target], reporting
     * (downloadedBytes, totalBytesOrMinusOne) as it goes.
     * False on any failure; the partial file is the caller's
     * to delete.
     */
    suspend fun download(
        url: String,
        target: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Boolean
}

class OkHttpUpdateHttp(
    private val client: OkHttpClient = defaultClient()
) : UpdateHttp {

    override suspend fun getText(url: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "Xreader-Android")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    response.body?.string()
                }
            }.getOrNull()
        }

    override suspend fun download(
        url: String,
        target: File,
        onProgress: (Long, Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Xreader-Android")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching false
                val body = response.body ?: return@runCatching false
                val total = body.contentLength()
                target.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }
                total <= 0 || target.length() == total
            }
        }.getOrDefault(false)
    }

    companion object {
        /** The check wants a snappy timeout; the APK download
         * wants patience on the read side only. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
}
