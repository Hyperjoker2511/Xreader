package app.xreader.core.error

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * v1.5.11 (ANR forensics — build-logs/V1511-RESEARCH-ANR-FORENSICS.md
 * §1.1): the ApplicationExitInfo harvester, API 30+ only. On each
 * launch it asks the system for this app's OWN recent process-exit
 * records (no permission exists or is needed — an app can only
 * ever see its own exits) and imports any ANR / crash records not
 * already imported into [CrashGuard]'s store, including the
 * system's own trace text when the record carries one — the same
 * evidence class Play Console and Crashlytics use, and the
 * conviction-grade counterpart to [StallWatchdog]'s in-process
 * captures.
 *
 * Dedupe keys on the RECORD (timestamp + pid, [CrashGuard
 * .exitInfoKey]), never on reason⇔trace assumptions: a process
 * that ANRs, recovers, and later dies for another reason still
 * carries the ANR trace on that later record, so a record is
 * imported at most once whatever it carries. The system's
 * history is bounded — the harvest asks for at most
 * [MAX_RECORDS] and the store's ledger remembers more keys
 * than that window can hold.
 *
 * Android 9 (API 28) simply skips this layer: every entry point
 * is SDK-guarded and [StallWatchdog] is that device's only
 * forensics layer. The harvest itself runs on a short-lived
 * background thread — exit-history IO never touches the main
 * thread's startup path.
 */
object ExitInfoHarvest {

    /** The harvest window (research §1.1: request 10–16). */
    private const val MAX_RECORDS = 16

    /** Startup entry: no-op below API 30; on API 30+ the real
     * harvest runs on a daemon thread and any failure is
     * swallowed — forensics must never break a launch. */
    fun harvestAsync(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val appContext = context.applicationContext
        Thread({
            runCatching { harvest(appContext) }
        }, "exit-info-harvest").apply {
            isDaemon = true
            start()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun harvest(context: Context) {
        val manager = context.getSystemService(ActivityManager::class.java)
            ?: return
        val records = runCatching {
            manager.getHistoricalProcessExitReasons(
                context.packageName, 0, MAX_RECORDS
            )
        }.getOrNull() ?: return
        for (record in records) {
            val reason = record.reason
            if (reason != ApplicationExitInfo.REASON_ANR &&
                reason != ApplicationExitInfo.REASON_CRASH &&
                reason != ApplicationExitInfo.REASON_CRASH_NATIVE
            ) {
                continue
            }
            // The trace stream is the per-process subsection of
            // the system's ANR dump when present; it can also be
            // null (the system's own trace creation failed) —
            // the record is imported either way.
            val traceText = runCatching {
                record.traceInputStream?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
            }.getOrNull()
            CrashGuard.recordExitInfo(
                context = context,
                dedupeKey = CrashGuard.exitInfoKey(record.timestamp, record.pid),
                exitTimestampMillis = record.timestamp,
                reasonLabel = reasonLabel(reason),
                pid = record.pid,
                description = record.description,
                traceText = traceText
            )
        }
    }

    /** The system's reason constant as the stable label the
     * note records. Pure int mapping (the REASON_* values are
     * API-30 constants, inlined by the compiler). */
    internal fun reasonLabel(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "REASON_ANR"
        ApplicationExitInfo.REASON_CRASH -> "REASON_CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "REASON_CRASH_NATIVE"
        else -> "REASON_$reason"
    }
}
