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
     * (unknown length) shows the indeterminate bar. */
    fun showProgress(version: String, fraction: Float?) {
        if (!manager.areNotificationsEnabled()) return
        val builder = baseBuilder()
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading Xreader $version")
            .setContentText(
                if (fraction != null) {
                    "${(fraction * 100).toInt()}% downloaded"
                } else {
                    "Downloading…"
                }
            )
        if (fraction != null) {
            builder.setProgress(100, (fraction * 100).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
    }

    fun showReady(version: String) {
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
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    companion object {
        private const val CHANNEL_ID = "app_updates"
        private const val NOTIFICATION_ID = 4201
    }
}
