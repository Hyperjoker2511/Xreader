package app.xreader.data.extension

import android.content.Context
import app.xreader.core.error.AppError
import app.xreader.data.db.InstalledExtension
import app.xreader.extensionapi.ExtensionFactory
import app.xreader.extensionapi.Source
import app.xreader.extensionapi.SourceContext
import dalvik.system.DexClassLoader
import java.io.File

/**
 * Dex loading (PLAN §5/§9.1) — read the spike conclusion before
 * touching this: **a same-UID DexClassLoader is NOT a sandbox.** The
 * loaded code runs with the app's full authority; a separate process
 * would contain crashes but not authority (same UID, and the API
 * surface would still be the host's own facades). Stock Android offers
 * no in-app code sandbox for dex. What ships instead is the plan's
 * mandated model:
 *
 *   fingerprint pinning (bytes checked before EVERY load) +
 *   explicit per-extension opt-in behind the strongest consent sheet +
 *   all network still forced through the whitelisted host client
 *   (the API facades are the only world the factory is handed).
 *
 * The checks below are the gate; [DexLoader] is a seam so the gate is
 * unit-testable without loading real dex on the JVM.
 */
object DexGate {

    /** Why dex may not load, or null when it may. Pure — tested. */
    fun refusalReason(
        installed: InstalledExtension,
        entryClass: String?,
        onDiskSha256: String?
    ): String? {
        if (installed.kind != app.xreader.data.db.ExtensionKind.DEX) return "not a dex source"
        if (!installed.enabled) return "the source is switched off"
        if (installed.trustState != app.xreader.data.db.TrustState.TRUSTED) {
            return "the source changed since you trusted it — re-trust it first"
        }
        val permissions = ExtensionPermissions.parse(installed.permissionsJson)
        if (!permissions.dexOptIn) {
            return "you haven't opted in to running this source's app code"
        }
        if (entryClass.isNullOrBlank()) return "the source doesn't name its entry class"
        if (!TrustEngine.artifactIntact(onDiskSha256, installed.sha256)) {
            return "the file on disk no longer matches the fingerprint you trusted"
        }
        return null
    }
}

interface DexLoader {
    /** Load [artifact] and create its sources. Assumes [DexGate] passed. */
    fun load(
        installed: InstalledExtension,
        artifact: File,
        optDir: File,
        entryClass: String,
        context: SourceContext
    ): List<Source>
}

/** The Android implementation: DexClassLoader whose parent is the
 * extension-api classloader, so the dex links against the SAME
 * app.xreader.extensionapi classes the host uses. */
class AndroidDexLoader(context: Context) : DexLoader {

    private val appContext: Context = context.applicationContext

    override fun load(
        installed: InstalledExtension,
        artifact: File,
        optDir: File,
        entryClass: String,
        context: SourceContext
    ): List<Source> {
        val apiClassLoader = ExtensionFactory::class.java.classLoader
            ?: appContext.classLoader
        val loader = DexClassLoader(
            artifact.absolutePath,
            optDir.absolutePath,
            null,
            apiClassLoader
        )
        val factoryClass = loader.loadClass(entryClass)
        val factory = factoryClass.getDeclaredConstructor().newInstance()
            as? ExtensionFactory
            ?: throw IllegalStateException(
                "entry class for ${installed.displayName} isn't an Xreader source factory"
            )
        return factory.create(context)
    }
}
