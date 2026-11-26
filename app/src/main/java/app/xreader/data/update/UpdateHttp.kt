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
     * v1.4.3: the same GET, but the HTTP status comes back
     * with it — (body, status); either may be null (no answer
     * at all / a transport failure). The updater's failure
     * line names the status ("GitHub couldn't be reached
     * (403)") instead of shrugging; a bare null from
     * [getText] couldn't tell a rate-limit from a dead line.
     * The default delegates to [getText] with no status, so
     * existing fakes and callers are untouched.
     */
    suspend fun getTextWithStatus(url: String): Pair<String?, Int?> =
        getText(url) to null

    /**
     * Streams [url] into [target], reporting
     * (downloadedBytes, totalBytesOrMinusOne) as it goes.
     * False on any failure; the partial file is the caller's
     * to delete. Progress reports are CONFLATED (Patch B,
     * B-6): at most one per whole-percent step (or per
     * 250 ms when the length is unknown), plus a final exact
     * report — never one per read chunk, because consumers
     * do real work (state, notifications) per report.
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

    override suspend fun getTextWithStatus(
        url: String
    ): Pair<String?, Int?> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Xreader-Android")
                .build()
            client.newCall(request).execute().use { response ->
                val body = if (response.isSuccessful) {
                    response.body?.string()
                } else {
                    null
                }
                body to response.code
            }
        }.getOrNull() ?: (null to null)
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
                    target.outputStream().buffered(64 * 1024).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        // Patch B (B-6, B4a): progress is
                        // CONFLATED here, at the byte loop —
                        // never one callback per chunk. The
                        // pre-fix loop fired onProgress on
                        // every 64 KiB read (~1,100 callbacks
                        // for a ~70 MB APK), and every
                        // callback ran a StateFlow emission
                        // plus a full notification rebuild +
                        // NotificationManager IPC in
                        // UpdateDownloadManager/UpdateNotifier,
                        // synchronously on this IO thread —
                        // the byte loop was serialised behind
                        // the progress pipeline and the
                        // download crawled on every phone.
                        // Reports now fire only when the
                        // whole-percent bucket changes, or
                        // 250 ms have passed (unknown-length
                        // bodies), and always once at the end
                        // with the true final byte count —
                        // real bytes, monotonic, honest 100%.
                        var lastReportedPercent = -1
                        var lastReportNanos = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val percent = if (total > 0) {
                                ((downloaded * 100) / total).toInt()
                            } else {
                                -1
                            }
                            val nowNanos = System.nanoTime()
                            val due = if (total > 0) {
                                percent != lastReportedPercent
                            } else {
                                nowNanos - lastReportNanos >=
                                    250_000_000L
                            }
                            if (due) {
                                lastReportedPercent = percent
                                lastReportNanos = nowNanos
                                onProgress(downloaded, total)
                            }
                        }
                        // The final, exact state — the loop's
                        // last bucketed report may sit one
                        // chunk behind the true end.
                        onProgress(downloaded, total)
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
