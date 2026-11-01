package app.xreader.data.extension.player

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import app.xreader.data.extension.Hashes
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import java.io.File

/**
 * K3: what the player needs to know about an extension APK
 * file before deciding anything — read from the archive
 * itself (never installed into the system), the donor's way
 * (Komikku's ExtensionLoader reads PackageManager archive
 * info with the same flags). Pure data, so the manager runs
 * against fakes on the JVM.
 */
data class KeiyoushiApkMetadata(
    val packageName: String,
    val label: String?,
    val versionName: String?,
    val versionCode: Long,
    /** The `tachiyomi.extension.class` metadata — one or
     * more entry classes, `;`-separated (donor shape). */
    val entryClass: String?,
    /** The `tachiyomi.extension.factory` metadata, when the
     * package declares one. */
    val pkgFactory: String?,
    /** The `tachiyomix.extensionLib` metadata as a float
     * (0.0f when absent — [KeiyoushiTrust.libVersionOf]
     * owns the fallback to the version name). */
    val extensionLib: Float,
    /** contentWarning > 0 or the legacy nsfw flag. */
    val isNsfw: Boolean,
    /** SHA-256 digests of the signing certificates. */
    val certSha256s: List<String>
)

interface KeiyoushiApkReader {
    /** Read [file] as an extension archive; null when it
     * isn't one (no extension feature flag, unreadable). */
    fun read(file: File): KeiyoushiApkMetadata?
}

/**
 * The Android reader — adapted from Komikku's
 * ExtensionLoader (Apache-2.0): same package flags, same
 * signature extraction (signingCertificateHistory /
 * apkContentsSigners on P+, legacy signatures below), same
 * metadata keys. The shared/system-installed half of the
 * donor's loader is NOT carried: Xreader extensions exist
 * only as private files we downloaded ourselves.
 */
class PackageManagerKeiyoushiApkReader(context: Context) : KeiyoushiApkReader {

    private val appContext: Context = context.applicationContext

    override fun read(file: File): KeiyoushiApkMetadata? {
        val pkgManager = appContext.packageManager
        val pkgInfo = pkgManager.getPackageArchiveInfo(
            file.absolutePath, PACKAGE_FLAGS
        ) ?: return null
        if (!isPackageAnExtension(pkgInfo)) return null
        val appInfo = pkgInfo.applicationInfo ?: return null
        // Android 13+: archive info may lack sourceDir, which
        // breaks later asset/icon reads (the donor's fix).
        appInfo.fixBasePaths(file.absolutePath)
        val meta = appInfo.metaData
        return KeiyoushiApkMetadata(
            packageName = pkgInfo.packageName,
            label = meta?.getString(METADATA_NAME)
                ?: runCatching {
                    pkgManager.getApplicationLabel(appInfo).toString()
                        .substringAfter("Tachiyomi: ")
                }.getOrNull(),
            versionName = pkgInfo.versionName,
            versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo),
            entryClass = meta?.getString(METADATA_SOURCE_CLASS),
            pkgFactory = meta?.getString(METADATA_SOURCE_FACTORY),
            extensionLib = meta?.getFloat(METADATA_EXTENSION_LIB) ?: 0.0f,
            isNsfw = (meta?.getInt(METADATA_CONTENT_WARNING) ?: 0) > 0 ||
                (meta?.getInt(METADATA_NSFW) ?: 0) == 1,
            certSha256s = getSignatures(pkgInfo).orEmpty()
        )
    }

    private fun isPackageAnExtension(pkgInfo: PackageInfo): Boolean =
        pkgInfo.reqFeatures.orEmpty().any { it.name == EXTENSION_FEATURE }

    /** The donor's signature extraction: SHA-256 hex of each
     * signing certificate. */
    private fun getSignatures(pkgInfo: PackageInfo): List<String>? {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo ?: return null
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures
        }
        return signatures?.map { Hashes.sha256Hex(it.toByteArray()) }
    }

    private fun ApplicationInfo.fixBasePaths(apkPath: String) {
        if (sourceDir == null) sourceDir = apkPath
        if (publicSourceDir == null) publicSourceDir = apkPath
    }

    companion object {
        private const val EXTENSION_FEATURE = "tachiyomi.extension"
        private const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
        private const val METADATA_SOURCE_FACTORY = "tachiyomi.extension.factory"
        private const val METADATA_NSFW = "tachiyomi.extension.nsfw"
        private const val METADATA_NAME = "tachiyomix.name"
        private const val METADATA_EXTENSION_LIB = "tachiyomix.extensionLib"
        private const val METADATA_CONTENT_WARNING = "tachiyomix.contentWarning"

        @Suppress("DEPRECATION")
        private val PACKAGE_FLAGS = PackageManager.GET_CONFIGURATIONS or
            PackageManager.GET_META_DATA or
            PackageManager.GET_SIGNATURES or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                0
            })
    }
}

/** Loads an extension file into live Mihon [Source]s — a
 * seam so the manager's flow is JVM-testable with fakes;
 * real DEX loading is device-proven in K6's smoke list. */
interface KeiyoushiExtensionLoader {
    fun load(file: File, metadata: KeiyoushiApkMetadata): List<Source>
}

/**
 * The Android loader — the donor's load path (Komikku's
 * ExtensionLoader private half): a [ChildFirstPathClassLoader]
 * over the file with the app classloader as parent (so the
 * :mihon-api classes bind to ours), then each metadata entry
 * class instantiated — a [Source] directly, or a
 * [SourceFactory] whose `createSources()` yields the list.
 *
 * Every instantiation is bracketed with
 * [PlayerContextState.withNamespace] (K2's rule): a source
 * constructor that reaches for the injected Application —
 * its prefs, its cache dir — lands in ITS OWN namespace
 * from the very first call.
 */
class AndroidKeiyoushiExtensionLoader(context: Context) : KeiyoushiExtensionLoader {

    private val appContext: Context = context.applicationContext

    override fun load(file: File, metadata: KeiyoushiApkMetadata): List<Source> {
        val entry = metadata.entryClass?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(
                "the extension doesn't name its entry class"
            )
        val classLoader = ChildFirstPathClassLoader(
            file.absolutePath, null, appContext.classLoader
        )
        return PlayerContextState.withNamespace(metadata.packageName) {
            entry.split(";").flatMap { raw ->
                val sourceClass = raw.trim()
                val fqcn = if (sourceClass.startsWith(".")) {
                    metadata.packageName + sourceClass
                } else {
                    sourceClass
                }
                val obj = Class.forName(fqcn, false, classLoader)
                    .getDeclaredConstructor().newInstance()
                when (obj) {
                    is Source -> listOf(obj)
                    is SourceFactory -> obj.createSources()
                    else -> throw IllegalStateException(
                        "unknown source class type: ${obj.javaClass.name}"
                    )
                }
            }
        }
    }
}
