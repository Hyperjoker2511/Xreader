package app.xreader.data.net

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * v1.3.3 (V132-CHECK 24): FETCH-LANE ISOLATION.
 *
 * The device shape: a plain, healthy source (FreeWebNovel)
 * hung in endless loading while walled sources were being
 * worked on. The mechanism in code: every blocking network
 * call in the app parked a thread of the ONE shared
 * Dispatchers.IO pool — a stalled static read can hold its
 * thread for the call timeout (300 s), a rendered fetch
 * parks its caller for the whole render budget, and a
 * chapter walk chains dozens of them. Saturate that pool
 * and a healthy source's catalogue queues behind work it
 * has nothing to do with.
 *
 * The rule now: extension STATIC fetches travel their own
 * lane — a small dedicated thread pool that only static
 * page fetches use. Rendered fetches (which park on the
 * browser view) and everything else in the app keep the
 * general pool, so no amount of stalled or rendered work
 * can consume the lane a plain source's catalogue needs.
 * The lane is deliberately small: per-extension rate
 * limiters already pace sources, and 8 concurrent page
 * fetches is generous headroom for catalogue/list work on
 * the 2 GB tier.
 */
object FetchLanes {

    const val STATIC_THREADS: Int = 8

    /** The dedicated dispatcher for extension static
     * fetches (the extension transport's calls run here). */
    val static: CoroutineDispatcher by lazy {
        Executors.newFixedThreadPool(STATIC_THREADS) { runnable ->
            Thread(runnable, "xreader-static-fetch").apply {
                isDaemon = true
            }
        }.asCoroutineDispatcher()
    }
}
