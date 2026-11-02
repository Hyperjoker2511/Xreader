package app.xreader.ui.sources

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/**
 * v1.3.2 (scope D): the all-sources search fan-out, Mihon-style.
 *
 * The v1.3.1 search walked the sources ONE BY ONE and published
 * nothing until every source had answered — with ~29 sources and
 * one slow site the screen sat silent for minutes, looking hung
 * (the app wasn't even pulling data for most of it). This runner
 * starts every search at once behind a small permit pool, reports
 * each result the moment it lands, counts progress as sources
 * settle, and treats one source's failure as that source's alone:
 * a failure never cancels the others, and cancelling the run
 * cancels the whole fan-out.
 */
/** v1.5.1 (item 2): one source exceeding its strict slice of
 * the fan-out. It settles as that source's failure alone —
 * the search completes with what the healthy sources gave. */
class SearchSourceTimeoutException(
    val timeoutMs: Long
) : Exception("the source took too long to answer")

object StreamingSearch {

    /** v1.5.1 (item 2): the per-source slice. A source that
     * hasn't answered inside it contributes nothing — on
     * device, one sick source (a catalogue render that never
     * settled) held its permit indefinitely and the whole
     * all-sources search sat stuck behind it. */
    const val DEFAULT_PER_ITEM_TIMEOUT_MS: Long = 20_000L

    suspend fun <T, R> run(
        items: List<T>,
        parallelism: Int = 5,
        perItemTimeoutMs: Long = DEFAULT_PER_ITEM_TIMEOUT_MS,
        search: suspend (T) -> R,
        onResult: (T, Result<R>) -> Unit,
        onProgress: (done: Int, total: Int) -> Unit
    ) {
        if (items.isEmpty()) {
            onProgress(0, 0)
            return
        }
        supervisorScope {
            val permits = Semaphore(parallelism.coerceAtLeast(1))
            val done = AtomicInteger(0)
            items.map { item ->
                async {
                    permits.withPermit {
                        val result = try {
                            val value = if (perItemTimeoutMs > 0) {
                                kotlinx.coroutines.withTimeoutOrNull(
                                    perItemTimeoutMs
                                ) { search(item) }
                            } else {
                                search(item)
                            }
                            if (value == null && perItemTimeoutMs > 0) {
                                Result.failure(
                                    SearchSourceTimeoutException(
                                        perItemTimeoutMs
                                    )
                                )
                            } else {
                                @Suppress("UNCHECKED_CAST")
                                Result.success(value as R)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            Result.failure(failure)
                        }
                        onResult(item, result)
                        onProgress(done.incrementAndGet(), items.size)
                    }
                }
            }.awaitAll()
        }
    }
}
