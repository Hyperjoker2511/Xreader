package app.xreader.data.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.xreader.MainActivity

/**
 * The update download's notification (v1.3.5): the in-app
 * card is the primary progress surface; this is its companion
 * for when the user leaves the app mid-download. One channel,
 * one notification id, plain words. (The chapter download
 * engine keeps its own in-app surfaces and posts no
 * notifications, so there was no existing channel convention
 * to ride — this helper is deliberately self-contained.)
 */
class UpdateNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    // Patch B (B-6, B4a): the last progress this instance
    // actually posted. [showProgress] rebuilds the whole
    // notification (channel check, two PendingIntents) and
    // posts it through NotificationManager — a Binder IPC
    // the system rate-limits — so a post whose displayed
    // percent hasn't moved is pure cost. Callers conflate
    // already (UpdateHttp, UpdateDownloadManager); this is
    // the final guard at the IPC itself.
    private var lastPostedVersion: String? = null
    private var lastPostedPercent: Int? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App updates",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progress when Xreader downloads its own update"
            }
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun baseBuilder(): NotificationCompat.Builder {
        ensureChannel()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
    }

    /** Progress while the APK downloads; a null [fraction]
     * (unknown length) shows the indeterminate bar. A call
     * whose displayed percent is unchanged since the last
     * post (same version) is dropped — see the guard note
     * on [lastPostedPercent]. */
    fun showProgress(version: String, fraction: Float?) {
        if (!manager.areNotificationsEnabled()) return
        val percent = fraction?.let { (it * 100).toInt() }
        if (percent != null && version == lastPostedVersion &&
            percent == lastPostedPercent
        ) {
            return
        }
        lastPostedVersion = version
        lastPostedPercent = percent
        runCatching {
            manager.notify(
                NOTIFICATION_ID,
                buildProgressNotification(version, fraction)
            )
        }
    }

    /**
     * The progress notification itself, built without posting
     * (v1.5.42): UpdateDownloadService needs the object for
     * its startForeground call, and [showProgress] posts the
     * same object for the running updates. The Cancel action
     * routes to the service, which cancels the download in
     * [UpdateDownloadManager] — the download's one explicit
     * stop, available even with the app hidden.
     */
    fun buildProgressNotification(
        version: String,
        fraction: Float?
    ): android.app.Notification {
        val builder = baseBuilder()
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(
                if (version.isNotBlank()) {
                    "Downloading Xreader $version"
                } else {
                    "Downloading the Xreader update"
                }
            )
            .setContentText(
                if (fraction != null) {
                    "${(fraction * 100).toInt()}% downloaded"
                } else {
                    "Downloading…"
                }
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                PendingIntent.getService(
                    context, 1,
                    UpdateDownloadService.cancelIntent(context),
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
                )
            )
        if (fraction != null) {
            builder.setProgress(100, (fraction * 100).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    fun showReady(version: String) {
        lastPostedVersion = null
        lastPostedPercent = null
        if (!manager.areNotificationsEnabled()) return
        val builder = baseBuilder()
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentTitle("Xreader $version is ready")
            .setContentText("Checked and ready to install — tap to open Xreader.")
            .setProgress(0, 0, false)
        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
    }

    fun showFailed() {
        lastPostedVersion = null
        lastPostedPercent = null
        if (!manager.areNotificationsEnabled()) return
        val builder = baseBuilder()
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentTitle("The update didn't download")
            .setContentText("Open Xreader to try again.")
            .setProgress(0, 0, false)
        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
    }

    fun clear() {
        lastPostedVersion = null
        lastPostedPercent = null
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    companion object {
        private const val CHANNEL_ID = "app_updates"

        /** Shared with UpdateDownloadService, whose
         * startForeground call posts under the same id the
         * progress updates use — one notification throughout. */
        const val NOTIFICATION_ID = 4201
    }
}
