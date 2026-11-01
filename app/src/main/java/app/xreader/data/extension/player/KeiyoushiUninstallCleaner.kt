package app.xreader.data.extension.player

import android.content.Context
import app.xreader.data.extension.SessionStore
import app.xreader.data.extension.normaliseHost
import java.io.File

/**
 * K3: the on-device half of the uninstall cascade
 * ([KeiyoushiUninstall.Step.REMOVE_NAMESPACED_DATA] and
 * [KeiyoushiUninstall.Step.REMOVE_SESSION_COOKIES]) — a seam
 * so the manager's cascade is JVM-testable against a fake
 * that records the checklist.
 */
interface KeiyoushiUninstallCleaner {
    suspend fun clean(record: KeiyoushiPlayerRecord)
}

/**
 * The Android cleaner. Removes, for ONE extension only:
 *
 * - its namespaced data: the `keiyoushi/<namespace>/` files
 *   and cache dirs (K2's wrapper layout) and every
 *   SharedPreferences file the extension wrote through the
 *   wrapper (`keiyoushi_<namespace>_*` in the app's
 *   shared_prefs directory — deleted via the platform API
 *   where the name is known, by file otherwise);
 * - its session cookies: the hosts its sources live on are
 *   removed from the player's own SessionStore record, and
 *   the extension row's sign-in session record
 *   ("ext-<rowId>", the same key every other source uses)
 *   is cleared whole.
 *
 * Other extensions' namespaces and other sources' sessions
 * are never touched — every path here is derived from THIS
 * record's package name and hosts.
 */
class AndroidKeiyoushiUninstallCleaner(
    context: Context,
    private val sessionStore: SessionStore
) : KeiyoushiUninstallCleaner {

    private val appContext: Context = context.applicationContext

    override suspend fun clean(record: KeiyoushiPlayerRecord) {
        val namespace = PlayerContextPolicy.sanitize(record.packageName)

        // Namespaced files + cache (K2's layout).
        runCatching {
            File(
                appContext.filesDir,
                "${PlayerContextPolicy.NAMESPACE_ROOT}/$namespace"
            ).deleteRecursively()
        }
        runCatching {
            File(
                appContext.cacheDir,
                "${PlayerContextPolicy.NAMESPACE_ROOT}/$namespace"
            ).deleteRecursively()
        }

        // Namespaced SharedPreferences files. The platform's
        // deleteSharedPreferences needs the exact file name;
        // the wrapper's naming rule makes the prefix exact,
        // so enumerate the directory and delete by prefix.
        runCatching {
            val prefsDir = File(appContext.dataDir, "shared_prefs")
            val prefix = "${PlayerContextPolicy.NAMESPACE_ROOT}_${namespace}_"
            prefsDir.listFiles()
                ?.filter {
                    it.isFile && it.name.startsWith(prefix) &&
                        it.name.endsWith(".xml")
                }
                ?.forEach { file ->
                    val name = file.name.removeSuffix(".xml")
                    runCatching {
                        appContext.deleteSharedPreferences(name)
                    }
                    runCatching { file.delete() }
                }
        }

        // The extension row's own sign-in session record.
        if (record.extensionRowId > 0) {
            runCatching {
                sessionStore.clear(
                    app.xreader.data.extension.ExtensionHttpClient
                        .sessionKeyFor(record.extensionRowId)
                )
            }
        }

        // Cookies the extension's own traffic set, for ITS
        // hosts only, out of the player's shared record.
        if (record.sourceHosts.isNotEmpty()) {
            runCatching { removePlayerRecordHosts(record.sourceHosts) }
        }
    }

    /** Drop [hosts] (and their parent-domain jars' matching
     * entries are left alone — only jars keyed BY one of
     * these hosts go) from the player's SessionStore record. */
    private suspend fun removePlayerRecordHosts(hosts: List<String>) {
        val current = sessionStore.load(
            SessionStoreCookieJar.PLAYER_SESSION_KEY
        ) ?: return
        val wanted = hosts.map { it.normaliseHost() }.toSet()
        val next = current.cookies.filterKeys {
            it.normaliseHost() !in wanted
        }
        if (next.size != current.cookies.size) {
            sessionStore.save(
                SessionStoreCookieJar.PLAYER_SESSION_KEY,
                current.copy(cookies = next)
            )
        }
    }
}
