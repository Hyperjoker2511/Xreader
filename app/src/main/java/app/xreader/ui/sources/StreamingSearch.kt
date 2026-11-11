package app.xreader.ui.sources

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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

    /** v1.5.2 (§4): the aggregate deadline. Wall clock for a global
     * search is min(aggregate, ceil(N / parallelism) x per-source
     * budget) — the formula is the invariant. */
    const val AGGREGATE_DEADLINE_MS: Long = 120_000L

    fun aggregateBoundMs(
        itemCount: Int,
        parallelism: Int = 5,
        perItemTimeoutMs: Long = DEFAULT_PER_ITEM_TIMEOUT_MS
    ): Long {
        if (itemCount <= 0) return 0L
        val waves = (itemCount + parallelism - 1) / parallelism
        return minOf(AGGREGATE_DEADLINE_MS, waves * perItemTimeoutMs)
    }

    /** v1.5.2 (§4): the two footnotes, never conflated — a source
     * ASKED that exceeded its budget ("didn't answer") vs a source
     * never asked because the aggregate closed the queue first
     * ("not reached"). */
    data class Footnotes<T>(
        val didntAnswer: List<T> = emptyList(),
        val notReached: List<T> = emptyList()
    )

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
        aggregateDeadlineMs: Long = AGGREGATE_DEADLINE_MS,
        /** v1.5.2 (second-opinion delta 2): the leg dispatcher and
         * the clock are injectable so the envelope boundary can be
         * proven DETERMINISTICALLY under virtual time (runTest) —
         * the boundary classification must not depend on wall-clock
         * scheduling. Production callers use the defaults and are
         * unchanged. */
        legDispatcher: kotlinx.coroutines.CoroutineDispatcher =
            kotlinx.coroutines.Dispatchers.Default,
        nowMs: () -> Long = System::currentTimeMillis,
        // v1.5.2 (round-15 closure, §4): the two parameters above
        // are the envelope's ONLY time sources, and every timed
        // mechanism in this coordinator runs on them:
        //  - each leg's slice is a withTimeoutOrNull whose clock
        //    is the scheduler of [legDispatcher] — the timeout
        //    runs in a legScope awaiter, on the same scheduler
        //    as the leg body it awaits (previously the timeout
        //    ran on the CALLER's dispatcher while the legs ran
        //    on legDispatcher: two clocks, and the boundary
        //    flipped whenever they drifted under load);
        //  - the remaining-aggregate arithmetic reads [nowMs]
        //    and nothing else — no System.currentTimeMillis /
        //    nanoTime call exists in this file.
        // The wider envelope path was audited for stragglers:
        // ExtensionHttpClient's RateLimiter and the shared
        // AvailabilityCooldowns take injectable clocks of their
        // own (politeness/parking, never classification), its
        // PageShape timestamps are diagnostics only, and the
        // 15 s search call timeout is a transport configuration
        // stamped per request (SearchCallScope), not a clock
        // read. Classification — asked vs didn't-answer vs
        // not-reached — is decided HERE, from these two sources
        // alone.
        search: suspend (T) -> R,
        onResult: (T, Result<R>) -> Unit,
        onProgress: (done: Int, total: Int) -> Unit,
        onFootnotes: (Footnotes<T>) -> Unit = {}
    ) {
        if (items.isEmpty()) {
            onProgress(0, 0)
            onFootnotes(Footnotes())
            return
        }
        val started = java.util.Collections.synchronizedSet(mutableSetOf<T>())
        val timedOut = java.util.Collections.synchronizedList(mutableListOf<T>())
        val bound = minOf(
            aggregateDeadlineMs,
            aggregateBoundMs(items.size, parallelism, perItemTimeoutMs)
        )
        // v1.5.2 fix (FIX-3): whether a leg counts as ASKED is
        // decided deterministically UP FRONT from the bound's own
        // arithmetic — the number of full per-source waves that fit
        // inside the bound, times the parallelism. A leg inside
        // that count is asked even if scheduling delivers it to the
        // semaphore with only milliseconds of aggregate left (its
        // timeout then classifies it "didn't answer"); a leg
        // outside it is never started and classifies "not reached".
        // The previous shape decided by post-hoc wall-clock timing
        // at permit acquisition, so leg b of the boundary fixture
        // flipped classification under load (the flaky footnote).
        val askedLimit = if (perItemTimeoutMs > 0) {
            val waves = ((bound + perItemTimeoutMs - 1) / perItemTimeoutMs)
                .coerceAtLeast(0)
            (waves * parallelism.coerceAtLeast(1)).toInt()
                .coerceIn(0, items.size)
        } else items.size
        val runStart = nowMs()
        // v1.5.2 fix (FIX-3): legs run in a DETACHED scope, and
        // each leg's slice is enforced at the AWAIT — a
        // suspension point the timeout can always reach.
        // Declarative sources fetch through blocking
        // runBlocking bridges, so a timeout fired INTO the leg
        // could not be delivered until the bridge returned;
        // a leg killed by its search call timeout then threw
        // (SourceException) synchronously out of the bridge
        // and settled as an ordinary failure at ~15 s instead
        // of "didn't answer" at its slice — and the run's
        // wall clock waited for every stuck bridge, breaking
        // the aggregate bound for exactly the sick sources
        // the bound exists for. The orphaned deferred's late
        // result or failure is captured by the Deferred and
        // dropped; the scope is cancelled when the run ends.
        val legScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + legDispatcher
        )
        try {
            supervisorScope {
                val permits = Semaphore(parallelism.coerceAtLeast(1))
                val done = AtomicInteger(0)
                items.mapIndexed { index, item ->
                    async {
                        permits.withPermit {
                            // §4: a leg outside the deterministic
                            // asked count is "not reached" — never
                            // started, never failed.
                            if (index < askedLimit) {
                                // Remaining aggregate still caps the
                                // leg's own timeout (wall-clock bound),
                                // with a 1 ms floor so an asked leg
                                // always runs and times out honestly
                                // instead of being reclassified.
                                val remaining = (bound -
                                    (nowMs() - runStart))
                                    .coerceAtLeast(1L)
                                started += item
                                // The leg's budget is its per-source
                                // slice AND the remaining aggregate,
                                // whichever closes first — so the
                                // wall clock never exceeds the bound.
                                val legTimeout =
                                    minOf(perItemTimeoutMs, remaining)
                                val leg = legScope.async { search(item) }
                                val result = try {
                                    // Round-15 closure: the slice
                                    // is enforced by an awaiter ON
                                    // legScope, so the
                                    // withTimeoutOrNull clock is the
                                    // INJECTED dispatcher's scheduler
                                    // — the same scheduler the leg
                                    // body runs on. (Previously the
                                    // timeout ran in THIS coroutine,
                                    // on the caller's dispatcher:
                                    // two schedulers, and the
                                    // boundary flipped whenever
                                    // they drifted under load.) This
                                    // coroutine only awaits the
                                    // awaiter, untimed, so result
                                    // callbacks stay serialised on
                                    // the caller's dispatcher exactly
                                    // as before. The awaiter CAN
                                    // abandon its await at the
                                    // deadline even when the leg is
                                    // parked in a blocking bridge —
                                    // the FIX-3 settle property is
                                    // preserved.
                                    val awaiter = legScope.async {
                                        if (legTimeout > 0) {
                                            kotlinx.coroutines
                                                .withTimeoutOrNull(
                                                    legTimeout
                                                ) { leg.await() }
                                        } else {
                                            leg.await()
                                        }
                                    }
                                    val value = try {
                                        awaiter.await()
                                    } catch (cancelled: CancellationException) {
                                        awaiter.cancel()
                                        throw cancelled
                                    }
                                    if (value == null && legTimeout > 0) {
                                        timedOut += item
                                        Result.failure(
                                            SearchSourceTimeoutException(
                                                legTimeout
                                            )
                                        )
                                    } else {
                                        @Suppress("UNCHECKED_CAST")
                                        Result.success(value as R)
                                    }
                                } catch (cancelled: CancellationException) {
                                    leg.cancel()
                                    throw cancelled
                                } catch (failure: Throwable) {
                                    Result.failure(failure)
                                }
                                onResult(item, result)
                                onProgress(done.incrementAndGet(), items.size)
                            }
                        }
                    }
                }.awaitAll()
            }
        } finally {
            legScope.cancel()
            // Settle with what has settled (§4): legs never started
            // when the aggregate closed are "not reached", never
            // reported as failed; legs that were asked and timed
            // out are "didn't answer". The two are never conflated.
            onFootnotes(
                Footnotes(
                    didntAnswer = timedOut.toList(),
                    notReached = items.filter { it !in started }
                )
            )
        }
    }
}
