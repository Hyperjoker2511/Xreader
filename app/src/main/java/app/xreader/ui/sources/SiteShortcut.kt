package app.xreader.ui.sources

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon
import app.xreader.MainActivity

/**
 * Home-screen install for a Live Source (v1.0.9): pins a
 * shortcut that carries the SITE's name and icon and opens
 * Xreader in its own task window straight at that source —
 * the "installed on my device, behaves like an app" half of
 * the Live Source idea. Android shows its one system "Add"
 * confirmation; no app can skip that step.
 *
 * The shortcut's intent mirrors the widget handoff: extras on
 * MainActivity, which routes the shell to the source and
 * labels the task with the site's name.
 */
object SiteShortcut {

    const val EXTRA_SOURCE_EXT = "app.xreader.extra.SOURCE_EXT"
    const val EXTRA_SOURCE_ID = "app.xreader.extra.SOURCE_ID"
    const val EXTRA_SOURCE_NAME = "app.xreader.extra.SOURCE_NAME"

    /**
     * Ask the system to pin the shortcut. [iconBytes] is the
     * site's own icon when it could be fetched; otherwise a
     * generated letter tile in Xreader violet stands in.
     * Returns false when the launcher refuses (the source
     * itself is unaffected — it lives in Browse regardless).
     */
    fun requestPin(
        context: Context,
        extensionId: Long,
        sourceId: String,
        name: String,
        iconBytes: ByteArray?
    ): Boolean {
        val manager = context.getSystemService(ShortcutManager::class.java)
            ?: return false
        if (!manager.isRequestPinShortcutSupported) return false
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_NEW_DOCUMENT
            putExtra(EXTRA_SOURCE_EXT, extensionId)
            putExtra(EXTRA_SOURCE_ID, sourceId)
            putExtra(EXTRA_SOURCE_NAME, name)
        }
        val icon = decodeIcon(iconBytes) ?: letterIcon(name)
        val info = ShortcutInfo.Builder(context, "live-$extensionId")
            .setShortLabel(name.take(24))
            .setLongLabel(name)
            .setIcon(icon)
            .setIntent(intent)
            .build()
        return runCatching { manager.requestPinShortcut(info, null) }
            .getOrDefault(false)
    }

    private fun decodeIcon(bytes: ByteArray?): Icon? {
        if (bytes == null) return null
        val bitmap = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull() ?: return null
        return Icon.createWithBitmap(bitmap)
    }

    /** A violet rounded tile with the site's first letter —
     * the fallback identity when the site has no usable icon. */
    private fun letterIcon(name: String): Icon {
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x5B, 0x54, 0xD1)
        }
        canvas.drawRoundRect(
            RectF(0f, 0f, size.toFloat(), size.toFloat()), 44f, 44f, paint
        )
        paint.color = Color.WHITE
        paint.textSize = 104f
        paint.textAlign = Paint.Align.CENTER
        val letter = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "X"
        val baseline = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(letter, size / 2f, baseline, paint)
        return Icon.createWithBitmap(bitmap)
    }
}
