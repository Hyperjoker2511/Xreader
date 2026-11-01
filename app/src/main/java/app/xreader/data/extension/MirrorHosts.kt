package app.xreader.data.extension

import java.util.concurrent.ConcurrentHashMap

/**
 * v1.3.4 (T6): the mirror-host registry — which hosts are
 * the SAME site as which primary host, from the sources'
 * own [DeclarativeConfig.backupHosts] declarations (seeded
 * only from sweep-verified mirrors).
 *
 * Two jobs:
 *  1. IDENTITY: [ChapterIdentity] canonicalises a chapter
 *     URL on any registered mirror host to the primary
 *     host's key, so a book fetched through a backup address
 *     is the SAME book — Library membership, progress,
 *     downloads and StoredBookRepair never split across
 *     hosts.
 *  2. HONESTY: the diagnostics and the source's state word
 *     can say "via backup address" because the fetch layer
 *     records which host actually answered ([PageShapeLog]).
 *
 * Registration is idempotent and process-wide; sources
 * register their declarations when they load
 * (ExtensionService) and the bundled defaults register at
 * container start, so identity holds before any repair or
 * merge runs.
 */
object MirrorHosts {

    /** mirror host (normalised) → primary host (normalised). */
    private val mirrorToPrimary = ConcurrentHashMap<String, String>()

    /** primary host (normalised) → its mirrors, in order. */
    private val primaryToMirrors = ConcurrentHashMap<String, List<String>>()

    fun register(primaryBaseUrl: String, backupHosts: List<String>) {
        if (backupHosts.isEmpty()) return
        val primary = CookieParsing.hostOf(primaryBaseUrl) ?: return
        val mirrors = backupHosts.mapNotNull { raw ->
            (CookieParsing.hostOf(raw) ?: raw.normaliseHost())
                .takeIf { it.isNotBlank() && it != primary }
        }.distinct()
        if (mirrors.isEmpty()) return
        for (mirror in mirrors) mirrorToPrimary[mirror] = primary
        primaryToMirrors[primary] = mirrors
    }

    /** The canonical host for identity purposes: a mirror
     * folds to its primary; anything else is itself. */
    fun canonicalHost(host: String): String {
        val h = host.normaliseHost()
        return mirrorToPrimary[h] ?: h
    }

    /** True when [host] is a registered mirror of [primaryHost]. */
    fun isMirrorOf(host: String, primaryHost: String): Boolean =
        mirrorToPrimary[host.normaliseHost()] == primaryHost.normaliseHost()

    fun mirrorsOf(primaryHost: String): List<String> =
        primaryToMirrors[primaryHost.normaliseHost()] ?: emptyList()

    /** Test-only reset (the registry is process state). */
    fun clearForTests() {
        mirrorToPrimary.clear()
        primaryToMirrors.clear()
    }
}
