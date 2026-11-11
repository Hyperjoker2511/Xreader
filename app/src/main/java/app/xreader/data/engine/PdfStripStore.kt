package app.xreader.data.engine

import app.xreader.data.parse.PdfPageBounds
import app.xreader.data.parse.PdfStripResult
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.5.8 (F4): the PDF text strip's persistence + background
 * session — the fix for Finding 5's M3 (the full-document strip
 * gated every open and was never persisted, so the wait repeated
 * on EVERY open).
 *
 * The strip is persisted per book in the book's own local-pdf
 * directory (beside the render cache, disposable by the same
 * construction), keyed by the file's fingerprint: the stored
 * record CARRIES the fingerprint it was stripped from, and a
 * read whose fingerprint differs is a miss — the strip is
 * invalidated on fingerprint change and re-run. Deleting the
 * book's directory deletes the strip with it.
 */
class PdfStripStore(private val dir: File) {

    private val file: File get() = File(dir, STRIP_FILE)

    /** The persisted strip, or null when absent, corrupt, or
     * stripped from a different file ([expectedFingerprint]). */
    fun read(expectedFingerprint: String): PdfStripResult? = runCatching {
        if (!file.isFile) return null
        val root = JSONObject(file.readText())
        if (root.optString("fingerprint") != expectedFingerprint) return null
        val textsJson = root.getJSONArray("texts")
        val boundsJson = root.getJSONArray("bounds")
        val texts = ArrayList<String>(textsJson.length())
        val bounds = ArrayList<PdfPageBounds?>(textsJson.length())
        for (i in 0 until textsJson.length()) {
            texts += textsJson.getString(i)
            val entry = boundsJson.optJSONArray(i)
            bounds += if (entry == null || entry.length() < 4) {
                null
            } else {
                PdfPageBounds(
                    left = entry.getDouble(0).toFloat(),
                    top = entry.getDouble(1).toFloat(),
                    right = entry.getDouble(2).toFloat(),
                    bottom = entry.getDouble(3).toFloat()
                )
            }
        }
        PdfStripResult(texts, bounds)
    }.getOrNull()

    /** Persists [result] under [fingerprint] (temp file + rename,
     * so a killed write never leaves a torn record). */
    fun write(fingerprint: String, result: PdfStripResult) {
        val boundsJson = JSONArray()
        result.bounds.forEach { bound ->
            if (bound == null) {
                boundsJson.put(JSONObject.NULL)
            } else {
                boundsJson.put(
                    JSONArray()
                        .put(bound.left.toDouble())
                        .put(bound.top.toDouble())
                        .put(bound.right.toDouble())
                        .put(bound.bottom.toDouble())
                )
            }
        }
        val root = JSONObject()
            .put("fingerprint", fingerprint)
            .put("texts", JSONArray(result.texts))
            .put("bounds", boundsJson)
        dir.mkdirs()
        val temp = File(dir, "$STRIP_FILE.tmp")
        temp.writeText(root.toString())
        if (!temp.renameTo(file)) {
            // Same-directory rename can still refuse on some
            // filesystems; a plain overwrite is the fallback.
            file.writeText(root.toString())
            temp.delete()
        }
    }

    fun delete() {
        runCatching { file.delete() }
        runCatching { File(dir, "$STRIP_FILE.tmp").delete() }
    }

    companion object {
        const val STRIP_FILE = "strip.json"
    }
}

/** The strip's availability, as the PDF surfaces consume it:
 * search / text view / read-aloud render their honest
 * "preparing" state while [Pending], work from [Ready], and say
 * the text layer is unavailable on [Unavailable]. */
sealed interface PdfStripState {
    data object Pending : PdfStripState
    data class Ready(val result: PdfStripResult) : PdfStripState
    data object Unavailable : PdfStripState
}

/**
 * v1.5.8 (F4): one book's background strip session. Construction
 * never strips: [start] first tries the persisted record (a hit
 * means the stripper NEVER runs — the second open is free), and
 * only on a miss runs [loader] in the background and persists
 * its result. The owning [PdfSession] is Ready (outline + labels
 * + PdfRenderer) without waiting for any of this.
 */
class PdfStripSession(
    private val store: PdfStripStore,
    private val fingerprint: String,
    private val loader: suspend () -> PdfStripResult,
    private val scope: CoroutineScope,
    /** v1.5.9 (A-5): the pause gate — while it reads false
     * (the pager is scrolling, or a P0/P1 render ticket is
     * outstanding in the owning session), the strip does NOT
     * start; it resumes when the gate opens. A persisted strip
     * is served without consulting the gate (serving it is no
     * work at all). Null = ungated (the pre-A-5 behaviour).
     * v1.5.13 (C-P2): the gate is now CONTINUOUS, not
     * start-only — see [gatedLoader] / [awaitGate]. */
    private val gate: StateFlow<Boolean>? = null,
    /** v1.5.13 (C-P2): the continuously-gated loader road.
     * When wired, it replaces [loader]: it receives this
     * session's [awaitGate] and must call it per page (or per
     * small chunk), so the strip PAUSES mid-book while a
     * turn/render is pending instead of only being held at
     * the start line, and yields its lane between chunks.
     * Null = the plain [loader] road (harness sessions and
     * any caller without a per-page strip loop). */
    private val gatedLoader: (suspend (suspend () -> Unit) -> PdfStripResult)? = null,
    /** v1.5.11 (step 7, research D7): the per-page extractor
     * — page index → that page's text alone, through the
     * session's dedicated short-lived PdfBox document
     * ([app.xreader.data.engine.PdfLazyTextExtractor] via
     * [app.xreader.data.parse.PdfStripperLoop.stripPage]).
     * Null = no per-page road (harness sessions): [textAt]
     * then keeps the pre-v1.5.11 whole-strip behaviour. */
    private val pageLoader: (suspend (Int) -> String?)? = null
) {
    private val _state = MutableStateFlow<PdfStripState>(PdfStripState.Pending)
    val state: StateFlow<PdfStripState> = _state

    @Volatile private var result: PdfStripResult? = null
    private val settled = CompletableDeferred<PdfStripResult?>()

    /** v1.5.11 (step 7, D7): texts already extracted PER PAGE
     * (the fast path's cache) — a page is extracted at most
     * once per session, and the landed whole strip supersedes
     * these entries when it arrives. */
    private val pageTexts = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /** Serialises per-page extractions (the extractor's
     * document is not thread-safe; the session mutex in the
     * extractor is the second lock, always taken after this
     * one — never the reverse). */
    private val pageMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * v1.5.13 (C-P2): the continuous gate the gated loader
     * awaits before each page — suspends (never spins) while
     * the gate reads closed, and propagates cancellation, so
     * a closed scope stops the strip promptly even mid-pause.
     * With no gate wired this is only a cancellation check.
     */
    suspend fun awaitGate() {
        currentCoroutineContext().ensureActive()
        gate?.first { open -> open }
        currentCoroutineContext().ensureActive()
    }

    fun start() {
        scope.launch {
            val persisted = runCatching { store.read(fingerprint) }.getOrNull()
            val loaded = persisted ?: run {
                // A-5: hold behind the gate — the strip never
                // competes with a flip sequence for the cores.
                // v1.5.13 (C-P2): this start check remains, and
                // the gated loader additionally re-checks the
                // gate per page for the whole book.
                awaitGate()
                val stripped = try {
                    if (gatedLoader != null) {
                        gatedLoader.invoke { awaitGate() }
                    } else {
                        loader()
                    }
                } catch (e: CancellationException) {
                    // Cancellable means cancellable: a cancelled
                    // strip settles nothing and persists nothing.
                    throw e
                } catch (e: Exception) {
                    null
                }
                stripped?.also {
                    runCatching { store.write(fingerprint, it) }
                }
            }
            if (loaded != null) {
                result = loaded
                // v1.5.11 (step 7): the landed strip is
                // authoritative — the per-page cache is dropped.
                pageTexts.clear()
                _state.value = PdfStripState.Ready(loaded)
            } else {
                _state.value = PdfStripState.Unavailable
            }
            settled.complete(loaded)
        }
    }

    /** Page [index]'s text: immediate when the strip has landed;
     * while it is still running, awaits it (search/TTS run async
     * anyway); empty when the strip is unavailable.
     *
     * v1.5.11 (step 7, D7): with a [pageLoader] wired, a page
     * the strip has not reached yet is extracted ON ITS OWN —
     * Text view no longer waits for a whole-book strip that
     * pauses whenever the user reads. The whole strip remains
     * the background completer: once it lands, its result is
     * authoritative and the per-page cache is dropped. When
     * the strip has already settled Unavailable, the per-page
     * road is not retried (the document has no readable text
     * layer through either road). */
    suspend fun textAt(index: Int): String {
        result?.texts?.getOrNull(index)?.let { return it }
        pageTexts[index]?.let { return it }
        val extract = pageLoader
        if (extract != null && _state.value !is PdfStripState.Unavailable) {
            val extracted = pageMutex.withLock {
                // Re-check under the lock: a racing caller (or
                // the landed strip) may have filled this page.
                result?.texts?.getOrNull(index)?.let { return@withLock it }
                pageTexts[index]?.let { return@withLock it }
                runCatching { extract(index) }.getOrNull()?.also {
                    pageTexts[index] = it
                }
            }
            if (extracted != null) return extracted
        }
        return (result ?: settled.await())?.texts?.getOrNull(index).orEmpty()
    }

    /** Page [index]'s already-known text (landed strip or an
     * earlier per-page extraction), or null when neither has
     * it yet. Never extracts and never awaits. */
    fun pageTextOrNull(index: Int): String? =
        result?.texts?.getOrNull(index) ?: pageTexts[index]

    /** Page [index]'s text bounds once the strip has landed;
     * null while pending (callers render the uncropped page). */
    fun boundsAt(index: Int): PdfPageBounds? = result?.bounds?.getOrNull(index)

    /** The landed strip, or null while pending/unavailable. */
    fun snapshot(): PdfStripResult? = result
}
