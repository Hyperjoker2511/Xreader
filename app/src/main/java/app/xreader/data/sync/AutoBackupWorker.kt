package app.xreader.data.sync

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.xreader.XreaderApp
import app.xreader.core.error.AppResult
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Weekly on-phone auto-backup (recommendation D, v1.0.1): writes a
 * fresh timestamped archive into the user's picked folder. Failure
 * is quiet by design — it records nothing and never blocks; the
 * Sync screen's "Last backup" line simply stays old, and the next
 * manual backup fixes it.
 */
class AutoBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? XreaderApp ?: return Result.failure()
        val container = app.container
        val settings = container.syncPrefs.settings.first()
        if (!settings.autoBackupWeekly || settings.backupFolderUri.isBlank()) {
            return Result.success()
        }
        val treeUri = Uri.parse(settings.backupFolderUri)
        val store = PhoneBackupStore(applicationContext)
        val name = BackupLogic.fileName(System.currentTimeMillis())
        return when (val opened = store.writeBackup(treeUri, name)) {
            is AppResult.Err -> Result.retry()
            is AppResult.Ok -> {
                val (docUri, stream) = opened.value
                val result = stream.use { container.syncService.exportBackup(it) }
                when (result) {
                    is AppResult.Ok -> {
                        val size = runCatching {
                            applicationContext.contentResolver
                                .openFileDescriptor(docUri, "r")?.statSize ?: 0L
                        }.getOrDefault(0L)
                        container.syncPrefs.recordBackup(System.currentTimeMillis(), size)
                        Result.success()
                    }
                    is AppResult.Err -> Result.retry()
                }
            }
        }
    }

    companion object {
        const val UNIQUE_NAME = "xreader-weekly-phone-backup"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(
                7, TimeUnit.DAYS
            ).build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(
                    UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
                )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
