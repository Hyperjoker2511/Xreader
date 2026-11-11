package app.xreader.core.error

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * Last line of defence (PLAN §1.5): if anything still manages to throw past
 * every boundary, the crash is recorded to a local note the user can share,
 * a "crashed" marker is left for the next launch (which lands on the
 * friendly recovery screen), and the previous default handler still runs.
 *
 * What a note records (v1.5.10, Finding 7's D1 instrument): timestamp,
 * thread, error class, and — new — the throwable's top 3 stack frames as
 * `className.methodName` ONLY. Frames were added because the class alone
 * could not convict a single line: the v1.5.9 proof's repeated
 * `java.lang.NullPointerException` on a background worker matched dozens
 * of candidate seams, and class+method of the top frames is the smallest
 * addition that names the site. Frames carry no file names or line
 * numbers — class + method identifies code, not content.
 *
 * Cause chains (v1.5.21): a note now records the throwable's WHOLE
 * cause chain, not just the outer throwable. The 2026-11-06 22:24
 * crash died inside activity resume, where Android wraps the real
 * failure in a `RuntimeException` — the old note named only the
 * wrapper and its outer frames, so the actual culprit, sitting in
 * the cause chain, was unnameable forever. Each link of the chain
 * (the throwable, then each cause down to [MAX_CAUSE_DEPTH])
 * records its class, its message bounded to [MAX_MESSAGE_CHARS]
 * (a deliberate, bounded exception to the v1.5.10 no-messages rule:
 * a wrapped failure's identity lives in its causes' messages, and
 * the bound keeps the exposure to one short line per link) and its
 * top [MAX_CHAIN_FRAMES] frames.
 *
 * Contained failures (v1.5.10, A2): background scopes route their
 * uncaught failures here through [containmentHandler] instead of
 * letting them reach the thread's default handler. A contained failure
 * is written as its own note carrying the distinct [CONTAINED_MARKER]
 * wording and — critically — does NOT write the crash marker, so the
 * recovery screen appears only for real process deaths. The A4 soak
 * probes for the marker: any contained-failure note written during the
 * soak is a missed hardening seam, not a success.
 *
 * [formatNote] / [formatContainedNote] are pure and unit-tested; the
 * handler installs are thin shells.
 *
 * Stall + system-exit notes (v1.5.11, ANR forensics —
 * build-logs/V1511-RESEARCH-ANR-FORENSICS.md): an ANR throws
 * nothing, so the v1.5.10 proof's three "isn't responding" deaths
 * left zero records here. Two new layers write into this same
 * store as new note kinds: [StallWatchdog]'s two-sample main
 * thread captures ([formatStallNote]) and, on API 30+, the
 * system's own exit records imported by `ExitInfoHarvest`
 * ([formatExitInfoNote]). Like contained notes, NEITHER writes
 * [MARKER_FILE]: a stall the user waited out, or an exit record
 * imported on a later launch, must not summon the crash recovery
 * screen retroactively. The stall note keeps this store's
 * privacy class (frames are class + method only); the exit note
 * additionally carries the SYSTEM's own ANR trace verbatim —
 * system-generated code evidence for this process only, no book
 * content, no keys, no cookies — bounded by
 * [MAX_EXIT_TRACE_CHARS] so a note stays shareable.
 *
 * Diagnostics export (v1.5.13, F0): the notes in this store are
 * also readable back for About's "Copy app details" —
 * [recentNotes] reads the store newest-first and
 * [formatNotesSection] renders the bounded section
 * `ProblemDetails.formatAppDetails` appends to the copied
 * payload, so a failed phone proof can ship its own stall /
 * exit evidence with the report. Read-only: exporting never
 * writes, rotates or deletes a note.
 */
object CrashGuard {

    private const val TAG = "CrashGuard"
    private const val NOTES_DIR = "crash-notes"
    const val MARKER_FILE = "last-crash.txt"
    private const val MAX_NOTES = 5

    /** The distinct wording every contained-failure note carries —
     * the soak's probe (A4) and the human reader's tell that the app
     * did NOT crash. */
    const val CONTAINED_MARKER = "contained failure"

    /** v1.5.11: the distinct wording every stall note carries. */
    const val STALL_MARKER = "main-thread stall"

    /** v1.5.11: the distinct wording every imported system exit
     * note carries. */
    const val EXIT_INFO_MARKER = "system exit record"

    /** v1.5.11: bound on the system trace text an exit note
     * carries, so a pathological trace cannot bloat the store. */
    const val MAX_EXIT_TRACE_CHARS = 32_768

    /** v1.5.13 (F0): the total size cap, in chars, for the crash
     * records section of the exported app details. The store
     * holds at most [MAX_NOTES] notes and an exit note can carry
     * a 32 KB trace, so an uncapped paste could reach ~160 KB;
     * 48 KB keeps the clipboard payload shareable while the
     * newest records — the ones a fresh failure writes — always
     * fit whole in practice. */
    const val MAX_EXPORT_RECORD_CHARS = 48 * 1024

    /** v1.5.13 (F0): the line [formatNotesSection] ends a capped
     * section with, so a reader can tell older records were
     * dropped (oldest first) rather than never recorded. */
    const val EXPORT_TRUNCATION_MARKER =
        "[older records truncated to keep this report within the export size cap]"

    /** v1.5.11: the dedupe ledger for imported exit records —
     * one `timestamp:pid` key per line. Lives OUTSIDE the notes
     * dir so note rotation can never eat it. */
    private const val EXIT_INFO_KEYS_FILE = "exit-info-imported.txt"

    /** v1.5.11: how many imported-exit keys the ledger remembers;
     * the harvest window asks the system for at most 16 records,
     * so 32 keys always cover it. */
    private const val MAX_EXIT_INFO_KEYS = 32

    private val exitInfoLock = Any()

    /** How many stack frames a note records (Finding 7, D1). */
    const val MAX_FRAMES = 3

    /** v1.5.21: how many causes below the throwable a note's
     * cause chain follows. */
    const val MAX_CAUSE_DEPTH = 8

    /** v1.5.21: how many stack frames each link of a note's
     * cause chain records. */
    const val MAX_CHAIN_FRAMES = 5

    /** v1.5.21: the bound on one chain link's message text in
     * a note, so a pathological message cannot bloat the store. */
    const val MAX_MESSAGE_CHARS = 300

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

    /** The throwable's top [MAX_FRAMES] frames, each
     * `className.methodName` only — no file names, no line numbers,
     * no message text. Pure; the shape the bounded DiagnosticLog
     * detail lines ([framesText]) render. Notes render the richer
     * [causeChainLines] instead (v1.5.21). */
    fun topFrames(throwable: Throwable): List<String> =
        throwable.stackTrace.take(MAX_FRAMES).map { frame ->
            "${frame.className}.${frame.methodName}"
        }

    /** One chain link's top [MAX_CHAIN_FRAMES] frames, same
     * class + method shape as [topFrames]. */
    private fun chainFrames(throwable: Throwable): List<String> =
        throwable.stackTrace.take(MAX_CHAIN_FRAMES).map { frame ->
            "${frame.className}.${frame.methodName}"
        }

    /** One chain link's message as a single bounded note line:
     * line breaks flattened (a note is line-shaped) and the text
     * cut at [MAX_MESSAGE_CHARS] with a marker when cut. Null
     * when the link has no usable message. */
    private fun chainMessage(throwable: Throwable): String? {
        val raw = throwable.message?.replace('\n', ' ')?.replace('\r', ' ')
            ?.takeIf { it.isNotBlank() } ?: return null
        return if (raw.length > MAX_MESSAGE_CHARS) {
            raw.substring(0, MAX_MESSAGE_CHARS) + "…[truncated]"
        } else {
            raw
        }
    }

    /** The throwable's whole cause chain as note lines (v1.5.21):
     * for the throwable and each cause down to [MAX_CAUSE_DEPTH] —
     * a `Caused by:` header naming the link's class (the
     * throwable's own class rides the note's `Error:` line
     * instead), its bounded message when it has one, and its top
     * [MAX_CHAIN_FRAMES] frames. Pure; the one shape both
     * throwable note kinds share. The walk is depth-capped and
     * repeats no link, so a cyclic chain cannot spin it. */
    fun causeChainLines(throwable: Throwable): List<String> {
        val lines = mutableListOf<String>()
        val seen = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Throwable, Boolean>()
        )
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth <= MAX_CAUSE_DEPTH && seen.add(current)) {
            if (depth > 0) {
                lines += "Caused by: ${current.javaClass.name}"
            }
            chainMessage(current)?.let { lines += "Message: $it" }
            val frames = chainFrames(current)
            if (frames.isNotEmpty()) {
                lines += "Frames:"
                frames.forEach { lines += it }
            }
            current = current.cause
            depth++
        }
        return lines
    }

    /** [topFrames] as one line, for bounded log records. */
    fun framesText(throwable: Throwable): String =
        topFrames(throwable).joinToString(", ")

    /** The shareable crash note: when, which thread, which error
     * CLASS, and the full cause chain ([causeChainLines]) — each
     * link's bounded message and top frames. */
    fun formatNote(timestampMillis: Long, threadName: String, throwable: Throwable): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMillis))
        return buildString {
            append("Xreader crash note\n")
            append("When: $stamp\n")
            append("Thread: $threadName\n")
            append("Error: ${throwable.javaClass.name}\n")
            causeChainLines(throwable).forEach { append("$it\n") }
        }
    }

    /** A contained failure's note: same evidence as [formatNote],
     * plus the site that caught it and the [CONTAINED_MARKER]
     * wording. It is written WITHOUT the crash marker — the app
     * kept running, so no recovery screen follows from it. */
    fun formatContainedNote(
        timestampMillis: Long,
        threadName: String,
        site: String,
        throwable: Throwable
    ): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMillis))
        return buildString {
            append("Xreader $CONTAINED_MARKER note\n")
            append("When: $stamp\n")
            append("Thread: $threadName\n")
            append("Site: $site\n")
            append("Error: ${throwable.javaClass.name}\n")
            append(
                "Kind: $CONTAINED_MARKER — caught on a background scope; " +
                    "the app did not crash.\n"
            )
            causeChainLines(throwable).forEach { append("$it\n") }
        }
    }

    /** Where a contained failure goes: CrashGuard's note writer in
     * production; a capturing fake in tests. A SAM seam so the
     * handler itself is unit-testable without Android. */
    fun interface ContainedFailureRecorder {
        fun record(site: String, threadName: String, throwable: Throwable)
    }

    /** The A2 containment handler for root scopes: a child failure
     * is routed to [recorder] as a CONTAINED failure (note +
     * diagnostic log at the call site's recorder) instead of
     * reaching the default uncaught handler and killing the
     * process. Cancellation is never a failure and is ignored.
     * Install on a SupervisorJob scope so the failure also cannot
     * cancel siblings. */
    fun containmentHandler(
        site: String,
        recorder: ContainedFailureRecorder
    ): CoroutineExceptionHandler =
        CoroutineExceptionHandler { _, throwable ->
            if (throwable is CancellationException) {
                return@CoroutineExceptionHandler
            }
            runCatching {
                recorder.record(site, Thread.currentThread().name, throwable)
            }
        }

    /** Writes a contained-failure note under [filesDir] and returns
     * it. Deliberately does NOT write [MARKER_FILE]: the recovery
     * screen is only for real process deaths. File-based (not
     * Context) so JVM tests drive the real writer. */
    fun writeContainedNote(
        filesDir: File,
        threadName: String,
        site: String,
        throwable: Throwable,
        nowMillis: Long = System.currentTimeMillis()
    ): File {
        val dir = File(filesDir, NOTES_DIR).apply { mkdirs() }
        val note = formatContainedNote(nowMillis, threadName, site, throwable)
        val file = File(dir, "contained-$nowMillis.txt")
        file.writeText(note)
        rotateNotes(dir)
        return file
    }

    /** Production entry for [writeContainedNote]. */
    fun recordContainedFailure(
        context: Context,
        site: String,
        threadName: String,
        throwable: Throwable
    ) {
        runCatching {
            writeContainedNote(context.filesDir, threadName, site, throwable)
        }
        Log.w(TAG, "Contained failure at $site (${throwable.javaClass.simpleName})")
    }

    // ── v1.5.11 (ANR forensics): stall notes ─────────────────────

    /** A convicted stall's note: when, how long the main thread
     * was blocked, its state, and the TWO full main-thread samples
     * (class + method only, ~1 s apart — identical samples read
     * as a hard block, differing-but-repeating samples as a spin;
     * research §2), plus one line per other thread (name, state,
     * top frames) so a lock holder is visible in the same note.
     * Pure, in the shape of [formatNote]. */
    fun formatStallNote(
        timestampMillis: Long,
        blockedMs: Long,
        mainThreadName: String,
        mainState: String,
        mainSample1: List<String>,
        mainSample2: List<String>,
        otherThreads: List<String>
    ): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMillis))
        return buildString {
            append("Xreader $STALL_MARKER note\n")
            append("When: $stamp\n")
            append("Thread: $mainThreadName\n")
            append(
                "Kind: $STALL_MARKER — main thread blocked for " +
                    "~$blockedMs ms (two samples, watchdog-convicted); " +
                    "the app did not crash.\n"
            )
            append("Main state: $mainState\n")
            append("Sample 1:\n")
            mainSample1.forEach { append("$it\n") }
            append("Sample 2:\n")
            mainSample2.forEach { append("$it\n") }
            if (otherThreads.isNotEmpty()) {
                append("Other threads:\n")
                otherThreads.forEach { append("$it\n") }
            }
        }
    }

    /** Writes a stall note under [filesDir] and returns it.
     * Deliberately does NOT write [MARKER_FILE] (see the class
     * doc). File-based (not Context) so JVM tests drive the real
     * writer, like [writeContainedNote]. */
    fun writeStallNote(
        filesDir: File,
        blockedMs: Long,
        mainThreadName: String,
        mainState: String,
        mainSample1: List<String>,
        mainSample2: List<String>,
        otherThreads: List<String>,
        nowMillis: Long = System.currentTimeMillis()
    ): File {
        val dir = File(filesDir, NOTES_DIR).apply { mkdirs() }
        val note = formatStallNote(
            nowMillis, blockedMs, mainThreadName, mainState,
            mainSample1, mainSample2, otherThreads
        )
        val file = File(dir, "stall-$nowMillis.txt")
        file.writeText(note)
        rotateNotes(dir)
        return file
    }

    /** Production entry for [writeStallNote] — the watchdog's
     * recorder lands here. */
    fun recordStall(
        context: Context,
        blockedMs: Long,
        mainThreadName: String,
        mainState: String,
        mainSample1: List<String>,
        mainSample2: List<String>,
        otherThreads: List<String>
    ) {
        runCatching {
            writeStallNote(
                context.filesDir, blockedMs, mainThreadName, mainState,
                mainSample1, mainSample2, otherThreads
            )
        }
        Log.w(TAG, "Stall note written (main blocked ~$blockedMs ms)")
    }

    // ── v1.5.11 (ANR forensics): system exit-record notes ───────

    /** The dedupe key of one system exit record: its death
     * timestamp + pid. The harvester keys on the RECORD (never
     * on reason⇔trace assumptions — a trace can ride a later,
     * unrelated exit record; research §1.1). Pure. */
    fun exitInfoKey(timestampMillis: Long, pid: Int): String =
        "$timestampMillis:$pid"

    /** An imported system exit record's note: when the process
     * died, why (the system's reason label), its pid, the
     * system's description when it gave one, and the system's
     * own trace text when available (bounded by
     * [MAX_EXIT_TRACE_CHARS]). Pure, in the shape of [formatNote]. */
    fun formatExitInfoNote(
        timestampMillis: Long,
        exitTimestampMillis: Long,
        reasonLabel: String,
        pid: Int,
        description: String?,
        traceText: String?
    ): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMillis))
        val exitStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(exitTimestampMillis))
        return buildString {
            append("Xreader $EXIT_INFO_MARKER note\n")
            append("Imported: $stamp\n")
            append("Exited: $exitStamp\n")
            append("Reason: $reasonLabel\n")
            append("Pid: $pid\n")
            append(
                "Kind: $EXIT_INFO_MARKER — imported from the system's " +
                    "exit history on a later launch; the app did not " +
                    "crash in this process.\n"
            )
            if (!description.isNullOrBlank()) {
                append("Description: $description\n")
            }
            if (!traceText.isNullOrBlank()) {
                append("System trace:\n")
                if (traceText.length > MAX_EXIT_TRACE_CHARS) {
                    append(traceText.substring(0, MAX_EXIT_TRACE_CHARS))
                    append("\n[trace truncated at $MAX_EXIT_TRACE_CHARS chars]\n")
                } else {
                    append(traceText)
                    if (!traceText.endsWith("\n")) append("\n")
                }
            }
        }
    }

    /** Writes an exit-record note under [filesDir] unless
     * [dedupeKey] was already imported — returns the note file,
     * or null for a duplicate. The ledger update and the note
     * write are one synchronised step so two harvests can never
     * import the same record twice. Deliberately does NOT write
     * [MARKER_FILE] (see the class doc). File-based (not
     * Context) so JVM tests drive the real writer. */
    fun writeExitInfoNote(
        filesDir: File,
        dedupeKey: String,
        exitTimestampMillis: Long,
        reasonLabel: String,
        pid: Int,
        description: String?,
        traceText: String?,
        nowMillis: Long = System.currentTimeMillis()
    ): File? {
        synchronized(exitInfoLock) {
            val keysFile = File(filesDir, EXIT_INFO_KEYS_FILE)
            val keys = runCatching {
                keysFile.readLines().filter { it.isNotBlank() }
            }.getOrDefault(emptyList())
            if (dedupeKey in keys) return null
            val dir = File(filesDir, NOTES_DIR).apply { mkdirs() }
            val note = formatExitInfoNote(
                nowMillis, exitTimestampMillis, reasonLabel, pid,
                description, traceText
            )
            val file = File(dir, "exitinfo-$exitTimestampMillis-$pid.txt")
            file.writeText(note)
            runCatching {
                keysFile.writeText(
                    (keys + dedupeKey).takeLast(MAX_EXIT_INFO_KEYS)
                        .joinToString("\n", postfix = "\n")
                )
            }
            rotateNotes(dir)
            return file
        }
    }

    /** Production entry for [writeExitInfoNote]: true when the
     * record was new and a note was written. */
    fun recordExitInfo(
        context: Context,
        dedupeKey: String,
        exitTimestampMillis: Long,
        reasonLabel: String,
        pid: Int,
        description: String?,
        traceText: String?
    ): Boolean {
        val written = runCatching {
            writeExitInfoNote(
                context.filesDir, dedupeKey, exitTimestampMillis,
                reasonLabel, pid, description, traceText
            )
        }.getOrNull()
        if (written != null) {
            Log.w(TAG, "Exit record imported ($reasonLabel, pid $pid)")
        }
        return written != null
    }

    // ── v1.5.13 (F0): diagnostics export ─────────────────────

    /** One stored note, read back for export: its file [name],
     * its last-modified time (the ordering key — newest first)
     * and its full [text] (already bounded at write time). */
    data class CrashNote(
        val name: String,
        val lastModifiedMillis: Long,
        val text: String
    )

    /** The pure ordering half of [recentNotes]: sorts
     * (name, lastModified) pairs newest-first — modification
     * time descending, name descending as the deterministic
     * tie-break for notes written within the same millisecond —
     * and reads each note's text through [readText], dropping
     * notes that are unreadable or blank. JVM-testable without
     * a filesystem. */
    fun orderRecentNotes(
        entries: List<Pair<String, Long>>,
        readText: (String) -> String?
    ): List<CrashNote> =
        entries
            .sortedWith(
                compareByDescending<Pair<String, Long>> { it.second }
                    .thenByDescending { it.first }
            )
            .mapNotNull { (name, modified) ->
                readText(name)?.takeIf { it.isNotBlank() }
                    ?.let { CrashNote(name, modified, it) }
            }

    /** Every note in the crash-notes store under [filesDir],
     * newest first (crash, contained, stall and exit notes all
     * share the one store). A missing store reads as no notes;
     * a single unreadable file is skipped, never fatal. File-
     * based (not Context) so JVM tests drive the real reader,
     * like the writers above. */
    fun recentNotes(filesDir: File): List<CrashNote> {
        val dir = File(filesDir, NOTES_DIR)
        val files = runCatching {
            dir.listFiles()?.filter { it.isFile }.orEmpty()
        }.getOrDefault(emptyList())
        if (files.isEmpty()) return emptyList()
        val byName = files.associateBy { it.name }
        return orderRecentNotes(files.map { it.name to it.lastModified() }) { name ->
            byName[name]?.let { file ->
                runCatching { file.readText() }.getOrNull()
            }
        }
    }

    /** The exported records section for [notes]: a heading plus
     * each note, newest first, under its file name. Pure.
     *
     * The section never exceeds [maxChars]: when the whole
     * store does not fit, notes are dropped OLDEST FIRST (the
     * newest evidence is the valuable half) and the section
     * ends with [EXPORT_TRUNCATION_MARKER]; a single newest
     * note larger than the cap is cut to fit, with the same
     * marker. An empty store formats to an empty string, so
     * callers can append the result unconditionally. */
    fun formatNotesSection(
        notes: List<CrashNote>,
        maxChars: Int = MAX_EXPORT_RECORD_CHARS
    ): String {
        if (notes.isEmpty() || maxChars <= 0) return ""
        val ordered = notes.sortedWith(
            compareByDescending<CrashNote> { it.lastModifiedMillis }
                .thenByDescending { it.name }
        )
        val header = "Crash records (newest first):"
        val blocks = ordered.map { note ->
            "\n--- ${note.name} ---\n${note.text.trim()}"
        }
        if (header.length + blocks.sumOf { it.length } <= maxChars) {
            return header + blocks.joinToString("")
        }
        val out = StringBuilder(header)
        var included = 0
        for (block in blocks) {
            // Reserve room for the marker (plus its newline):
            // truncation is already certain on this path, so a
            // block is only kept whole if the marker still fits.
            if (out.length + block.length + EXPORT_TRUNCATION_MARKER.length + 1 <= maxChars) {
                out.append(block)
                included++
            } else {
                break
            }
        }
        if (included == 0) {
            val room = maxChars - out.length - EXPORT_TRUNCATION_MARKER.length - 1
            if (room > 0) out.append(blocks.first().take(room))
        }
        out.append("\n").append(EXPORT_TRUNCATION_MARKER)
        return out.toString()
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
        rotateNotes(dir)
        Log.e(TAG, "Crash note written (${throwable.javaClass.simpleName})")
    }

    /** Keep only the most recent few notes (crash, contained,
     * stall and exit notes all share the one bounded budget). */
    private fun rotateNotes(dir: File) {
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_NOTES)
            ?.forEach { it.delete() }
    }
}
