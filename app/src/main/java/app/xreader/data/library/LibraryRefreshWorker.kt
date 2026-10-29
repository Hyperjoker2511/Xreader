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
            container.libraryRefresher.refreshAll()
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() }
        )
    }

    companion object {
        const val UNIQUE_NAME = "library-refresh"

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LibraryRefreshWorker>().build()
            )
        }
    }
}
