package app.xreader.ui.localreader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/**
 * v1.5.18 (F3 / C-E2): the one-time whole-book text sweep
 * (Chars/Words for the Book information sheet), extracted from
 * EpubBookReader's inline open-path loop into a controller the
 * gate can drive against a fake text source.
 *
 * The v1.5.12 shape launched the sweep AT OPEN, ungated: it
 * Jsoup-parsed every section (via `ReadiumEpubSession.textAt`)
 * concurrently with the cover→plate window, adding device CPU
 * pressure in exactly the seconds the EPUB ANR accumulated.
 * This controller changes WHEN and HOW, never WHAT:
 *
 *  - SETTLE-GATED: [run] processes no section until
 *    [markSettled] is called. The reader marks settle only
 *    after the first locator has landed plus a short idle
 *    delay (`EPUB_TEXT_SWEEP_SETTLE_DELAY_MS`), so the sweep
 *    never runs inside the open path.
 *  - CHUNKED: sections are processed in bounded chunks of
 *    [chunkSize]; between chunks [pauseBetweenChunks] runs
 *    (a [yield] by default), so the sweep interleaves with
 *    active reading instead of monopolising its dispatcher
 *    for a whole book.
 *  - CANCELLABLE: [run] is an ordinary suspending function —
 *    cancelling its coroutine (book close/change cancels the
 *    hosting LaunchedEffect) stops it at the next section or
 *    chunk boundary, and nothing is saved. A [CancellationException]
 *    from the text source is rethrown, never counted as an
 *    empty section.
 *  - SAME TOTALS: per section it sums `text.length` and
 *    [countWords] exactly as the inline loop did; a section
 *    whose read fails counts as empty text (the old
 *    `runCatching … getOrDefault("")` semantics); the result
 *    is `TextCounts(chars, words, complete = true)`, cached
 *    by [run] so a second call returns it without re-reading.
 *
 * Drive [run] from ONE coroutine at a time.
 */
internal class EpubTextSweepController(
    private val sectionCount: Int,
    private val textAt: suspend (Int) -> String,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val pauseBetweenChunks: suspend () -> Unit = { yield() },
    private val onChunkComplete: (processedSections: Int) -> Unit = {}
) {
    private val settleGate = CompletableDeferred<Unit>()

    /** Sections fully processed by the current/last [run]. */
    @Volatile
    var processedSections: Int = 0
        private set

    @Volatile
    private var result: TextCounts? = null

    /** True once [markSettled] has been called. */
    val isSettled: Boolean get() = settleGate.isCompleted

    /** Open the settle gate. Idempotent; later calls are no-ops. */
    fun markSettled() {
        settleGate.complete(Unit)
    }

    /**
     * Await settle, then sweep every section in chunks and
     * return the totals. Suspends indefinitely while unsettled;
     * throws [CancellationException] if the calling coroutine is
     * cancelled before or during the sweep.
     */
    suspend fun run(): TextCounts {
        result?.let { return it }
        settleGate.await()
        currentCoroutineContext().ensureActive()
        processedSections = 0
        val total = sectionCount.coerceAtLeast(0)
        val safeChunk = chunkSize.coerceAtLeast(1)
        var chars = 0L
        var words = 0L
        var index = 0
        while (index < total) {
            val chunkEnd = minOf(index + safeChunk, total)
            while (index < chunkEnd) {
                currentCoroutineContext().ensureActive()
                val text = try {
                    textAt(index)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ""
                }
                chars += text.length
                words += countWords(text)
                index++
                processedSections = index
            }
            onChunkComplete(index)
            if (index < total) pauseBetweenChunks()
        }
        return TextCounts(chars = chars, words = words, complete = true)
            .also { result = it }
    }

    companion object {
        /** Sections per chunk — small enough that a chunk's
         * Jsoup parses stay a background nibble, large enough
         * that a whole book still finishes promptly once the
         * reader has settled. */
        const val DEFAULT_CHUNK_SIZE = 4
    }
}
