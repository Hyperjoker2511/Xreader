package app.xreader.data.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.xreader.XreaderApp

/**
 * WorkManager drain (PLAN §1/§4): runs the [DownloadEngine] as a
 * foreground-capable job with a CONNECTED constraint, so downloads
 * survive the screen going off and the app being swiped away. The
 * Wi-Fi-only rule applies to prefetch items inside the engine, not to
 * the job as a whole (a download the user tapped starts on any
 * network).
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? XreaderApp ?: return Result.failure()
        return try {
            val container = app.container
            // v1.0.6 (W5.2/Q4): a drain started while the app is
            // closed is background work — it never renders, and
            // the engine defers browser-source items with a note.
            // A drain started while the app is open may use the
            // browser view (the person is watching Downloads).
            if (container.renderRuntime.appInForeground) {
                container.downloadEngine.runQueue()
            } else {
                container.renderRuntime.asBackgroundWork {
                    container.downloadEngine.runQueue()
                }
            }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "xreader-download-drain"
    }
}

class WorkManagerEnqueuer(context: Context) : WorkEnqueuer {

    private val appContext = context.applicationContext

    override fun enqueueDrain() {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(appContext)
            .enqueueUniqueWork(DownloadWorker.UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
