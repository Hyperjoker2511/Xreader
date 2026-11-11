package app.xreader.data.library

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.xreader.XreaderApp

/**
 * The Library refresh as unique background work (v1.0.2, R20):
 * the pull gesture enqueues ONE unique job; repeated pulls while
 * it runs are absorbed by ExistingWorkPolicy.KEEP. The job itself
 * is [LibraryRefresher.refreshAll] — the same code the button
 * runs — so behaviour cannot drift between entry points.
 */
class LibraryRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as XreaderApp).container
        return runCatching {
            // v1.0.6 (W5.2): the worker is background work — it
            // never renders. A browser-view source's refresh just
            // waits for the next foreground refresh (the direct
            // LibraryViewModel path, which does render).
            container.renderRuntime.asBackgroundWork {
                container.libraryRefresher.refreshAll()
            }
        }.fold(
            // v1.5.3 (finding #9, strand D): a walk that COMPLETED
            // is a success even when some books failed or were
            // skipped — per-book outcomes are counted inside the
            // Outcome, and partial success is still success.
            // Result.retry() is reserved for the walk itself
            // being unable to run at all (the exception path);
            // before, any per-book trouble funnelled into retry
            // and the pull-to-refresh indicator never cleared.
            onSuccess = { outcome ->
                android.util.Log.i(
                    TAG,
                    "Library refresh finished: checked=" +
                        "${outcome.booksChecked} withNew=" +
                        "${outcome.booksWithNew} totalNew=" +
                        "${outcome.totalNew} failures=" +
                        "${outcome.failures} skipped=${outcome.skipped}"
                )
                Result.success()
            },
            // v1.5.34 (item 1b): retries are BOUNDED. Before, ANY
            // exception returned Result.retry() and WorkManager
            // backed off and re-ran the job indefinitely — a
            // permanently failing walk re-appeared as "the
            // Library is refreshing again" on later visits, long
            // after the owner's manual refresh had completed.
            // Now the job retries at most MAX_RETRIES times and
            // then ends in failure: finished, not pending.
            onFailure = { error ->
                if (runAttemptCount < MAX_RETRIES) {
                    android.util.Log.w(
                        TAG,
                        "Library refresh attempt $runAttemptCount " +
                            "failed (${error.message}); will retry"
                    )
                    Result.retry()
                } else {
                    android.util.Log.e(
                        TAG,
                        "Library refresh failed after " +
                            "$runAttemptCount attempts; giving up",
                        error
                    )
                    Result.failure()
                }
            }
        )
    }

    companion object {
        private const val TAG = "LibraryRefreshWorker"
        const val UNIQUE_NAME = "library-refresh"

        /** v1.5.34 (item 1b): the retry budget — the initial
         * run plus at most this many retries, then the job
         * ends in failure instead of retrying forever. */
        private const val MAX_RETRIES = 2

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LibraryRefreshWorker>().build()
            )
        }
    }
}
