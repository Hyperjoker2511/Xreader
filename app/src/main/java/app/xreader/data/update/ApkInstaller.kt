package app.xreader.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Handing a verified APK to Android's installer (v1.3.5).
 * Android always shows its own install prompt — the app can
 * only ask. The first time, the system also needs the
 * "install unknown apps" grant for Xreader; when it's
 * missing we route the user to that exact system setting
 * with plain-words copy in the UI, never a dead end.
 */
object ApkInstaller {

    const val FILE_PROVIDER_AUTHORITY = "app.xreader.fileprovider"

    /** True when Android will let Xreader start an install. */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** The system-installer intent for a downloaded APK,
     * served through the app's FileProvider (the `updates/`
     * cache path is declared in res/xml/file_paths.xml). */
    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** The system setting where the user allows Xreader to
     * install updates — opened only on the user's tap. */
    fun installPermissionIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
