package app.xreader.diag

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DIAGNOSTIC BUILD ONLY (code 109 / 1.6.2-mr13-diag) — temporary
 * instrumentation for the Contents-jump snap-back investigation.
 * This file and every call into it are removed with the fix that
 * the log convicts; nothing here is a feature.
 *
 * An in-memory ring buffer of the last [MAX_LINES] log lines, each
 * prefixed with milliseconds since the first line of the process.
 * Appends only: no Android Log, no I/O, no state reads beyond the
 * values the caller already holds — logging must not perturb the
 * timing-sensitive paths it observes.
 *
 * [dumpToDownloads] writes the buffer to a plain text file in the
 * public Downloads folder (MediaStore on API 29+; the app's
 * external Downloads dir, falling back to filesDir, below that),
 * named `xreader-diag-<yyyyMMdd-HHmmss>.txt`.
 */
object DiagLog {

    private const val MAX_LINES = 600

    private val lock = Any()
    private val lines = ArrayDeque<String>()

    @Volatile
    private var startMs: Long = 0L

    @Volatile
    private var versionLine: String = "unknown"

    /** Called once by the reader screen with the running build's
     * versionName/versionCode, so the dump's header names the
     * exact build that produced it. */
    fun init(version: String) {
        versionLine = version
        synchronized(lock) {
            if (startMs == 0L) startMs = System.currentTimeMillis()
        }
    }

    fun log(site: String, msg: String) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (startMs == 0L) startMs = now
            val rel = now - startMs
            lines.addLast("+$rel [$site] $msg")
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    /** Writes the buffered lines to Downloads. Synchronous and
     * best-effort (a diagnostic build; the dump happens on reader
     * close/dispose, where a launched coroutine could be cancelled
     * before it writes — the buffer is ~600 short lines). */
    fun dumpToDownloads(context: Context) {
        val snapshot: List<String>
        synchronized(lock) { snapshot = lines.toList() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .format(Date())
        val name = "xreader-diag-$stamp.txt"
        val body = buildString {
            appendLine("Xreader diagnostic log (code 109 / 1.6.2-mr13-diag)")
            appendLine("app: $versionLine")
            appendLine("dumped: $stamp, ${snapshot.size} lines")
            snapshot.forEach { appendLine(it) }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        out.write(body.toByteArray(Charsets.UTF_8))
                    }
                    val done = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    resolver.update(uri, done, null, null)
                }
            } else {
                val dir = context.getExternalFilesDir(
                    Environment.DIRECTORY_DOWNLOADS
                ) ?: context.filesDir
                File(dir, name).writeText(body)
            }
        }
        log("DUMP", "dumped ${snapshot.size} lines to $name")
    }
}
