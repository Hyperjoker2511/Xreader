package app.xreader.fixtures

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred

/**
 * v1.5.10 (L-FIX, build-map I4): the HANG fixtures — fake
 * player/source roads whose fetches NEVER resolve, so B1/B4/H3
 * suites can prove the bounded honest state arrives while the
 * shared call stays alive and is never restarted.
 *
 * Each road parks on a [CompletableDeferred] that nothing in
 * the fixture completes. A test may complete it EXPLICITLY via
 * [HangGate.release] to prove the landed result still applies
 * (B1's "releasing the fixture's deferred lands chapters"
 * leg) — until then the call hangs forever, including under
 * virtual-time advancement (a deferred is not a delay).
 *
 * Probes on every gate:
 *  - [HangGate.walkCount] — how many walks (calls) were
 *    STARTED; the gate property is exactly 1 across open +
 *    Retry (the shared call is never restarted).
 *  - [HangGate.wasStarted] — the call was entered at all.
 *  - [HangGate.isAlive] — a started call is still suspended
 *    inside the gate (alive = started and not yet returned).
 */
class HangGate<T> {
    /** The parking deferred. NEVER completed by the fixture. */
    val deferred = CompletableDeferred<T>()

    private val walks = AtomicInteger(0)
    private val startedFlag = AtomicBoolean(false)
    private val aliveFlag = AtomicBoolean(false)

    /** How many times a caller entered (walked into) the gate. */
    val walkCount: Int get() = walks.get()

    /** True once any caller has entered the gate. */
    val wasStarted: Boolean get() = startedFlag.get()

    /** True while a caller is suspended inside the gate. */
    val isAlive: Boolean get() = aliveFlag.get()

    /** The road body: parks until [release] (test-only). */
    suspend fun hang(): T {
        walks.incrementAndGet()
        startedFlag.set(true)
        aliveFlag.set(true)
        try {
            return deferred.await()
        } finally {
            aliveFlag.set(false)
        }
    }

    /** Test-only escape hatch: completes the parked call(s)
     * with [value]. The fixture itself never calls this. */
    fun release(value: T) {
        deferred.complete(value)
    }
}

/** B1's road: a player/source whose CHAPTER FETCH never
 * resolves (the book page's chapters-pending walk). */
class HangChapterFetchSource {
    val gate = HangGate<List<String>>()

    /** Parks forever; returns chapter remote ids if released. */
    suspend fun fetchChapters(bookRemoteId: String): List<String> =
        gate.hang()
}

/** B4's roads: the page-list call never resolves — one gate
 * for the Komikku (manga page list) road, one for the novel
 * reader's chapter-text wait, so a suite can assert each
 * road's stall independently. */
class HangPageListSource {
    /** Komikku road: image page URLs for a chapter. */
    val komikkuGate = HangGate<List<String>>()

    /** Novel road: the chapter's text. */
    val novelGate = HangGate<String>()

    suspend fun fetchPages(chapterRemoteId: String): List<String> =
        komikkuGate.hang()

    suspend fun fetchChapterText(chapterRemoteId: String): String =
        novelGate.hang()
}

/** H3/B1's catalogue road: a catalogue (popular/latest/search)
 * fetch that never resolves, while stored rows must already
 * be on screen. */
class HangCatalogueSource {
    val gate = HangGate<List<String>>()

    /** Parks forever; returns book remote ids if released. */
    suspend fun fetchCatalogue(page: Int): List<String> = gate.hang()
}
