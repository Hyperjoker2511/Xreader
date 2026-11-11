package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.db.ExtensionKind
import app.xreader.data.db.ExtensionRepoDao
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.InstalledExtensionDao
import app.xreader.data.prefs.SourcePrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * v1.5.1 (item 1): the update-time source migration — the
 * user's rule, stated 2026-11-02: "If you are updating the
 * app you should remove the old sources and add new ones —
 * no duplicates."
 *
 * v1.5.0's mechanisms passed on the bench while every field
 * failure lived in SAVED state: a source learned by an old
 * learner kept its old rules forever (saved BOTI still had
 * "no catalogue page" — the API road never re-derived a
 * saved source), and a learned copy of a now-bundled site
 * (chikari.moe) stood beside the bundled source with its
 * books bound to the broken copy, because the dedupe pass
 * couldn't read a Live row's domain and so never grouped it.
 *
 * Once per app version (the versioned-flag shape of the
 * heal sweep; the flag is written only after the pass
 * completes, so a process death mid-pass re-runs it), on a
 * background dispatcher, never blocking first paint:
 *
 *  1. Every learned / link-added source (the link repo's
 *     rows, and every LIVE row) is re-derived with the
 *     CURRENT learner — API road included — from its own
 *     recorded sample. A passing derivation REPLACES the
 *     row's rules in place (same row, same package, same
 *     library links; [RepoManager.saveLinkSource] with the
 *     row's packageId): the old rules are deleted by being
 *     overwritten, never kept as a silent fallback.
 *  2. A learned row whose site the app now SHIPS (a bundled
 *     default or a pack source with the same canonical
 *     domain) is not re-derived at all — the dedupe pass
 *     that closes the migration removes it and rebinds its
 *     library books/history to the shipped survivor
 *     (SourceDedupe, which since this version also reads
 *     Live rows' domains).
 *  3. Failure is isolated per source: a site that can't be
 *     re-derived keeps its row and its books, earns an
 *     audit line, and never blocks the rest of the pass or
 *     the app opening. It is retried on the next update.
 *
 * Every step returns audit lines (the caller logs them);
 * nothing here deletes a book, a chapter, or reading
 * progress — dedupe rebinds, the migration replaces rules.
 */
class SourceUpdateMigration(
    private val extensionDao: InstalledExtensionDao,
    private val repoDao: ExtensionRepoDao,
    private val store: ExtensionStore,
    private val linkProber: LinkProber,
    private val repoManager: RepoManager,
    private val sourceDedupe: SourceDedupe,
    private val sourcePrefs: SourcePrefs
) {

    /** Runs the pass once per [currentVersionCode]; null
     * when it already ran for this version. Cancellation
     * propagates BEFORE the flag is written, so an
     * interrupted pass retries on a later launch. */
    suspend fun runIfNeeded(
        currentVersionCode: Int
    ): List<String>? {
        if (sourcePrefs.sourceMigrationDoneForCode.first() >=
            currentVersionCode
        ) {
            return null
        }
        val lines = withContext(Dispatchers.Default) { runPass() }
        sourcePrefs.setSourceMigrationDoneForCode(currentVersionCode)
        return lines
    }

    private suspend fun runPass(): List<String> {
        val lines = mutableListOf<String>()
        val rows = extensionDao.allNow()
        val learned = rows.filter { isLearned(it) }
        if (learned.isEmpty()) return lines
        // The shipped set's domains: a learned row on one of
        // these domains merges into the shipped survivor in
        // the closing dedupe — re-deriving it would only
        // re-create the duplicate the rule forbids.
        val shippedDomains = rows
            .filter { !isLearned(it) }
            .mapNotNull { domainOf(it) }
            .toSet()
        for (row in learned) {
            try {
                migrateRow(row, shippedDomains, lines)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lines += "MIGRATE kept \"${row.displayName}\" — " +
                    "its migration stopped on an error " +
                    "(${t.javaClass.simpleName}); it keeps its " +
                    "saved rules and retries on the next update"
            }
        }
        // The merge pass: one surviving source per site,
        // books rebound to the survivor (SourceDedupe's own
        // audit lines, re-prefixed so the log reads as one
        // migration story).
        runCatching { sourceDedupe.runOnce() }
            .getOrDefault(emptyList())
            .forEach { removed ->
                lines += "MIGRATE " + removed.logLine()
            }
        return lines
    }

    private suspend fun migrateRow(
        row: InstalledExtension,
        shippedDomains: Set<String>,
        lines: MutableList<String>
    ) {
        val domain = domainOf(row) ?: return
        if (domain in shippedDomains) {
            lines += "MIGRATE \"${row.displayName}\" ($domain) " +
                "now ships with the app — the learned copy is " +
                "removed below and its books move to the " +
                "shipped source"
            return
        }
        val sample = sampleUrlOf(row) ?: return
        val outcome = linkProber.probe(sample, tryAllProposers = true)
        when (outcome) {
            is LinkProber.Outcome.Ready -> {
                val saved = repoManager.saveLinkSource(
                    outcome.draft.config,
                    outcome.draft.host,
                    packageIdOverride = row.packageId
                )
                lines += if (saved is AppResult.Ok) {
                    "MIGRATE re-derived \"${row.displayName}\" " +
                        "($domain) with the current learner — " +
                        "its old rules were replaced"
                } else {
                    "MIGRATE kept \"${row.displayName}\" " +
                        "($domain) — the new rules couldn't be " +
                        "saved, so its old rules stay for now"
                }
            }
            is LinkProber.Outcome.LoginRequired -> {
                markNeedsChecking(row)
                lines += "MIGRATE kept \"${row.displayName}\" " +
                    "($domain) — re-deriving it needs its " +
                    "one-time sign-in/check first; its saved " +
                    "rules stay until then"
            }
            is LinkProber.Outcome.NotProven -> {
                markNeedsChecking(row)
                lines += "MIGRATE kept \"${row.displayName}\" " +
                    "($domain) — re-derivation found rules but " +
                    "couldn't prove reading (${outcome.missingLegs.joinToString()}); " +
                    "its saved rules stay until the next update"
            }
            is LinkProber.Outcome.Failed -> {
                markNeedsChecking(row)
                lines += "MIGRATE kept \"${row.displayName}\" " +
                    "($domain) — the current learner couldn't " +
                    "re-derive it (${outcome.message.take(120)}); " +
                    "its saved rules stay until the next update"
            }
        }
    }

    /** v1.5.1 (item 1, ChatGPT sift): a learned source the
     * migration could not supersede or re-derive is never
     * force-merged and never silently dropped — it stays a
     * user source MARKED as needing a check, in the health
     * system's own vocabulary (NEEDS_SETUP), so its Details
     * page offers the check instead of pretending all is
     * well. Best-effort: a failed mark never fails the pass. */
    private suspend fun markNeedsChecking(row: InstalledExtension) {
        runCatching {
            for (sourceId in row.sourceIds.split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
            ) {
                sourcePrefs.setSourceHealth(
                    SourceHealthRecord.key(row.id, sourceId),
                    SourceHealthRecord(
                        state = SourceHealthState.NEEDS_SETUP,
                        checkedAtMillis = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    /** A row the user added by link: it lives in the link
     * repo, or it is a Live row (Live installs file under
     * the link repo too — both marks are read so a row is
     * never missed by carrying only one of them). */
    private suspend fun isLearned(row: InstalledExtension): Boolean {
        if (row.kind == ExtensionKind.LIVE) return true
        val repo = runCatching { repoDao.getById(row.repoId) }
            .getOrNull()
        return repo?.indexUrl == RepoManager.LINK_REPO_URL
    }

    /** The row's canonical domain, from its own definition —
     * a declarative config's baseUrl, or a Live spec's. */
    private fun domainOf(row: InstalledExtension): String? {
        val raw = store.readText(store.definitionFile(row.packageId))
            ?: return null
        val configDomain = runCatching {
            DeclarativeFingerprints.decode(raw)
        }.getOrNull()?.firstOrNull()?.baseUrl
        val base = configDomain ?: runCatching {
            LiveSourceSpec.decode(raw)
        }.getOrNull()?.baseUrl
        return SourceDedupePlanner.canonicalDomain(base)
    }

    /** The address the re-derivation probes: the recorded
     * sample book page when the row has one (the probe's
     * home turf), else the site's base / the Live row's
     * pasted start link. */
    private fun sampleUrlOf(row: InstalledExtension): String? {
        val raw = store.readText(store.definitionFile(row.packageId))
            ?: return null
        val config = runCatching {
            DeclarativeFingerprints.decode(raw)
        }.getOrNull()?.firstOrNull()
        if (config != null) {
            return config.sampleBookUrl ?: config.baseUrl
        }
        return runCatching { LiveSourceSpec.decode(raw) }
            .getOrNull()?.startUrl
    }
}
