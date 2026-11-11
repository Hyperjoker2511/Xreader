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
 * The manga-source update notification (v1.5.39, item 4):
 * posted ONCE per newly-flagged update set by the daily
 * Keiyoushi check (AppContainer.checkKeiyoushiSourceUpdates
 * owns the once-only memory in SourcePrefs). Its own
 * "Source updates" channel; the tap deep-links to the
 * Keiyoushi shelf through MainActivity's intent extra —
 * the widget-tap pattern (EXTRA_OPEN_BOOK). Shaped like
 * [UpdateNotifier], deliberately self-contained like it.
 */
class SourceUpdateNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Source updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description =
                    "When a manga source has an update waiting"
            }
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun shelfIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_SOURCE_SHELF, true)
        }
        return PendingIntent.getActivity(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** One notification for the whole newly-flagged set:
     * a single update is named; several are counted. */
    fun showUpdates(displayNames: List<String>) {
        if (displayNames.isEmpty()) return
        if (!manager.areNotificationsEnabled()) return
        ensureChannel()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(shelfIntent())
            .setAutoCancel(true)
            .setContentTitle(
                if (displayNames.size == 1) {
                    "Source update available"
                } else {
                    "${displayNames.size} source updates available"
                }
            )
            .setContentText(
                if (displayNames.size == 1) {
                    "“${displayNames.first()}” has an update " +
                        "waiting — tap to open Manga sources."
                } else {
                    "Manga sources have updates waiting — " +
                        "tap to open Manga sources."
                }
            )
        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
    }

    companion object {
        /** MainActivity reads this extra and hands the tap
         * to the shell (AppContainer.pendingOpenSourceShelf). */
        const val EXTRA_OPEN_SOURCE_SHELF = "open_source_shelf"
        private const val CHANNEL_ID = "source_updates"
        private const val NOTIFICATION_ID = 4202
        private const val REQUEST_CODE = 7
    }
}
