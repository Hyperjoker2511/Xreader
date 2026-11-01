/*
 * Transplanted for Xreader from Komikku (app module, eu.kanade.tachiyomi.AppInfo,
 * Apache-2.0) — see the NOTICE file in this module.
 *
 * Xreader substitution (named in NOTICE): Komikku's getters read its app module's
 * BuildConfig. A library module cannot see the host app's BuildConfig, so the two
 * values live in internal vars that default to 0 / "" until the Xreader app sets
 * them at startup (slice K2). Komikku's third method, getSupportedImageMimeTypes(),
 * is not part of the extension-lib 1.6 contract and depends on Komikku's ImageUtil;
 * it is not carried.
 */
package eu.kanade.tachiyomi

/** Set by the host app at startup (K2); 0 until then. */
internal var appInfoVersionCode: Int = 0

/** Set by the host app at startup (K2); "" until then. */
internal var appInfoVersionName: String = ""

/**
 * Used by extensions.
 */
@Suppress("UNUSED")
object AppInfo {
    /**
     * Host-app hook (Xreader, slice K2): the host app calls this once at
     * startup so the getters below report its real version. Komikku needs no
     * such hook — its AppInfo lives in the app module and reads its own
     * BuildConfig directly; a library module cannot (see this file's header).
     */
    fun setVersionInfo(versionCode: Int, versionName: String) {
        appInfoVersionCode = versionCode
        appInfoVersionName = versionName
    }

    /**
     * Version code of the host application. May be useful for sharing as User-Agent information.
     * Note that this value differs between forks so logic should not rely on it.
     *
     * @since extension-lib 1.3
     */
    fun getVersionCode(): Int = appInfoVersionCode

    /**
     * Version name of the host application. May be useful for sharing as User-Agent information.
     * Note that this value differs between forks so logic should not rely on it.
     *
     * @since extension-lib 1.3
     */
    fun getVersionName(): String = appInfoVersionName
}
