package app.xreader.core.error

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last line of defence (PLAN §1.5): if anything still manages to throw past
 * every boundary, the crash is recorded — timestamp, thread, error class
 * only; NEVER book content, NEVER keys or cookies — to a local note the
 * user can share, a "crashed" marker is left for the next launch (which
 * lands on the friendly recovery screen), and the previous default
 * handler still runs.
 *
 * [formatNote] is pure and unit-tested; the handler install is a thin shell.
 */
object CrashGuard {

    private const val TAG = "CrashGuard"
    private const val NOTES_DIR = "crash-notes"
    const val MARKER_FILE = "last-crash.txt"
    private const val MAX_NOTES = 5

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeNote(appContext, thread.name, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** The shareable crash note: when, which thread, which error CLASS. Nothing else. */
    fun formatNote(timestampMillis: Long, threadName: String, throwable: Throwable): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMillis))
        return "Xreader crash note\n" +
            "When: $stamp\n" +
            "Thread: $threadName\n" +
            "Error: ${throwable.javaClass.name}\n"
    }

    /** True when a previous process crashed and the user hasn't seen the
     * recovery screen yet. Pure file check; safe to call on every launch. */
    fun hasUnseenCrash(context: Context): Boolean =
        File(context.filesDir, MARKER_FILE).exists()

    /** Clears the marker once the recovery screen has been shown. */
    fun acknowledgeCrash(context: Context) {
        runCatching { File(context.filesDir, MARKER_FILE).delete() }
    }

    private fun writeNote(context: Context, threadName: String, throwable: Throwable) {
        val dir = File(context.filesDir, NOTES_DIR).apply { mkdirs() }
        val note = formatNote(System.currentTimeMillis(), threadName, throwable)
        File(dir, "crash-${System.currentTimeMillis()}.txt").writeText(note)
        // The marker the next launch checks (same content; user-shareable).
        File(context.filesDir, MARKER_FILE).writeText(note)
        // Keep only the most recent few notes.
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_NOTES)
            ?.forEach { it.delete() }
        Log.e(TAG, "Crash note written (${throwable.javaClass.simpleName})")
    }
}
