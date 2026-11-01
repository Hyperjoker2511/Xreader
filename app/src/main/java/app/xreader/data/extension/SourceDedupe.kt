package app.xreader.data.extension

import app.xreader.data.db.BookDao
import app.xreader.data.db.ChapterSourceMapDao
import app.xreader.data.db.ExtensionRepoDao
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.InstalledExtensionDao

/**
 * v1.3.1 (V120-CHECK 51/52 — the owner's decision, 06:44 IST:
 * "In the next install remove all duplicate — only one
 * original."): install-time source dedupe.
 *
 * Source rows are grouped by CANONICAL DOMAIN (the config's
 * base host, lowercased, one leading "www." stripped). Per
 * domain exactly ONE row survives: the built-in default where
 * one exists, otherwise the user's OLDEST row. Every other
 * row for the domain is removed. This is an explicit owner
 * instruction that overrides the never-auto-delete default
 * FOR SOURCE ROWS ONLY:
 *
 * - Library books, chapters, history, downloads and reading
 *   progress are NEVER deleted. A book bound to a removed row
 *   is REBOUND to the survivor: its extensionId moves and its
 *   stored remote id is re-encoded under the survivor's
 *   source id (the remote book id itself — a URL on every
 *   declarative road — is unchanged, so the book opens on
 *   the surviving source with its chapters and progress
 *   intact). Failover maps pointing at a removed row are
 *   dropped (they rebuild lazily in the background).
 * - Every removal is returned as an audit line (name, domain,
 *   origin) and logged by the caller.
 * - Idempotent: after one pass each domain has one row, so
 *   a second pass removes nothing. A domain with a single
 *   row is untouched. A row whose definition cannot be read
 *   is never grouped (it keeps its domain to itself).
 */
object SourceDedupePlanner {

    /** One installed source row, reduced to what the plan
     * needs. [canonicalDomain] null = ungroupable (kept). */
    data class Row(
        val extensionId: Long,
        val packageId: String,
        val displayName: String,
        val origin: String,
        val canonicalDomain: String?,
        val sourceIds: List<String>,
        val installedAt: Long
    )

    data class Removal(
        val removed: Row,
        val survivor: Row
    )

    /** The canonical domain of a config base URL: the host,
     * lowercased, one leading "www." removed. Mirrors SHOULD
     * NOT merge: a different registrable domain (.me vs .com)
     * is a different group — stated plainly in the report. */
    fun canonicalDomain(baseUrl: String?): String? {
        val host = baseUrl?.let { CookieParsing.hostOf(it) }
            ?: return null
        val clean = host.lowercase().removePrefix("www.")
        return clean.ifBlank { null }
    }

    /** The dedupe plan: which rows go, and which row each of
     * them yields to. Pure — the executor applies it. */
    fun plan(rows: List<Row>): List<Removal> {
        val groups = rows
            .filter { it.canonicalDomain != null }
            .groupBy { it.canonicalDomain!! }
        val removals = mutableListOf<Removal>()
        for ((_, group) in groups) {
            if (group.size < 2) continue
            val survivor = group.sortedWith(
                compareByDescending<Row> {
                    it.packageId.startsWith("bundled.")
                }.thenBy { it.installedAt }
                    .thenBy { it.extensionId }
            ).first()
            for (row in group) {
                if (row.extensionId != survivor.extensionId) {
                    removals += Removal(row, survivor)
                }
            }
        }
        return removals
    }
}

/** Applies [SourceDedupePlanner] to the installed rows.
 * Runs once per launch right after the bundled defaults
 * install (MainScaffold); cheap and offline. */
class SourceDedupe(
    private val extensionDao: InstalledExtensionDao,
    private val repoDao: ExtensionRepoDao,
    private val bookDao: BookDao,
    private val sourceMapDao: ChapterSourceMapDao,
    private val store: ExtensionStore,
    private val service: ExtensionService
) {

    /** One removed row, for the audit log. */
    data class RemovedRow(
        val name: String,
        val domain: String,
        val origin: String,
        val packageId: String,
        val survivorPackageId: String,
        val booksRebound: Int
    ) {
        /** The audit line (also the report's log format):
         * `DEDUPE removed "<name>" (<domain>, <origin>, pkg
         * <packageId>) → survivor <survivorPackageId>; N
         * book(s) rebound`. */
        fun logLine(): String =
            "DEDUPE removed \"$name\" ($domain, $origin, pkg " +
                "$packageId) → survivor $survivorPackageId; " +
                "$booksRebound book(s) rebound"
    }

    suspend fun runOnce(): List<RemovedRow> {
        val rows = extensionDao.allNow()
        if (rows.size < 2) return emptyList()
        val snapshots = rows.map { snapshot(it) }
        val removals = SourceDedupePlanner.plan(snapshots)
        if (removals.isEmpty()) return emptyList()
        val byId = rows.associateBy { it.id }
        val books = bookDao.allBooks()
        val removed = mutableListOf<RemovedRow>()
        for (removal in removals) {
            val removedRow = byId[removal.removed.extensionId]
                ?: continue
            val survivorRow = byId[removal.survivor.extensionId]
                ?: continue
            var rebound = 0
            // Rebind BEFORE deleting: no book ever points at
            // a row that is already gone.
            for (book in books) {
                if (book.extensionId != removedRow.id) continue
                val decoded = SourceBookIds.decode(book.remoteId)
                val survivorSourceId = removal.survivor.sourceIds
                    .firstOrNull()
                val newRemote = if (decoded != null &&
                    survivorSourceId != null
                ) {
                    SourceBookIds.encode(
                        survivorSourceId, decoded.second
                    )
                } else {
                    book.remoteId
                }
                bookDao.update(
                    book.copy(
                        extensionId = survivorRow.id,
                        remoteId = newRemote
                    )
                )
                rebound++
            }
            sourceMapDao.deleteByAltExtension(removedRow.id)
            extensionDao.delete(removedRow)
            service.invalidate(removedRow.id)
            removed += RemovedRow(
                name = removal.removed.displayName,
                domain = removal.removed.canonicalDomain ?: "",
                origin = removal.removed.origin,
                packageId = removal.removed.packageId,
                survivorPackageId = removal.survivor.packageId,
                booksRebound = rebound
            )
        }
        return removed
    }

    /** A row's dedupe snapshot: canonical domain from its
     * FIRST config (every config in a row serves the row's
     * sites; multi-source repo rows group by their first —
     * stated in the report), origin from its repo. */
    private suspend fun snapshot(
        row: InstalledExtension
    ): SourceDedupePlanner.Row {
        val repo = runCatching { repoDao.getById(row.repoId) }
            .getOrNull()
        val origin = when {
            repo?.indexUrl == RepoManager.BUNDLED_REPO_URL ->
                "Built-in"
            repo?.indexUrl == RepoManager.LINK_REPO_URL ->
                "Added by you"
            else -> repo?.name ?: "Unknown repo"
        }
        val configs = if (
            row.kind == app.xreader.data.db.ExtensionKind.DECLARATIVE ||
            row.kind == app.xreader.data.db.ExtensionKind.LIVE
        ) {
            store.readText(store.definitionFile(row.packageId))
                ?.let { raw ->
                    runCatching {
                        DeclarativeFingerprints.decode(raw)
                    }.getOrNull()
                } ?: emptyList()
        } else {
            emptyList()
        }
        val first = configs.firstOrNull()
        return SourceDedupePlanner.Row(
            extensionId = row.id,
            packageId = row.packageId,
            displayName = row.displayName,
            origin = origin,
            canonicalDomain = SourceDedupePlanner.canonicalDomain(
                first?.baseUrl
            ),
            sourceIds = configs.map { it.id }.ifEmpty {
                row.sourceIds.split(",").map { it.trim() }
                    .filter { it.isNotBlank() }
            },
            installedAt = row.installedAt
        )
    }
}
