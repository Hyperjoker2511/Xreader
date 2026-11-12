package app.xreader.data.update

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import app.xreader.XreaderApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The update download's foreground-service shell (v1.5.42):
 * deliberately thin. The download itself lives in
 * [UpdateDownloadManager] (app-scoped); this service exists
 * only to keep the process at foreground priority while a
 * download runs and to receive the progress notification's
 * Cancel action. It is started when a download starts
 * (AppContainer's manager hook) and stops itself the moment
 * the manager's phase leaves Running/Verifying — the
 * manager's hooks have already posted the final word by
 * then (Ready/Failed notification, or a clear on cancel),
 * so a finished notification is detached, never removed.
 *
 * The installer is NEVER fired from here: the Ready
 * notification opens the app, and the install intent flows
 * through the UI's intents channel as before.
 */
class UpdateDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            // The progress notification's Cancel action. The
            // manager owns the state reset + notification
            // clear; this service just lets go.
            managerOrNull()?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val version = intent?.getStringExtra(EXTRA_VERSION).orEmpty()
        val notification =
            UpdateNotifier(this).buildProgressNotification(version, null)
        ServiceCompat.startForeground(
            this,
            UpdateNotifier.NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
        observeManager()
        return START_NOT_STICKY
    }

    private fun managerOrNull(): UpdateDownloadManager? =
        (application as? XreaderApp)?.container?.updateDownloadManager

    private fun observeManager() {
        if (observing) return
        observing = true
        val manager = managerOrNull() ?: run {
            stopSelf()
            return
        }
        scope.launch {
            manager.state.collect { state ->
                when (state.phase) {
                    is UpdateDownloadPhase.Running,
                    UpdateDownloadPhase.Verifying -> Unit
                    UpdateDownloadPhase.None -> {
                        // Cancelled (or never really started):
                        // the notification is already cleared.
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    else -> {
                        // Ready / Failed / permission detour:
                        // keep the posted final notification.
                        stopForeground(STOP_FOREGROUND_DETACH)
                        stopSelf()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "app.xreader.action.CANCEL_UPDATE_DOWNLOAD"
        private const val EXTRA_VERSION = "version"

        fun startIntent(context: Context, version: String): Intent =
            Intent(context, UpdateDownloadService::class.java)
                .putExtra(EXTRA_VERSION, version)

        fun cancelIntent(context: Context): Intent =
            Intent(context, UpdateDownloadService::class.java)
                .setAction(ACTION_CANCEL)
    }
}
