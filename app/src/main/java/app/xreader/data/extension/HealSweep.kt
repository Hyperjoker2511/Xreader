package app.xreader.data.extension

import app.xreader.core.AppResult
import app.xreader.data.db.BookDao
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.InstalledExtensionDao
import app.xreader.data.db.TrustState
import app.xreader.data.prefs.SourcePrefs
import kotlinx.coroutines.flow.first

/**
 * v1.0.5 (E1): the heal-my-library sweep. Link sources installed
 * before the honest engine (v1.0.5) can carry rules the old,
 * forgiving test-drive waved through — the sweep re-drives every
 * installed LINK source once per app version, repairs the failing
 * ones through the same machinery as the Details-page Repair
 * (every automatic proposer, best passing candidate wins, saved
 * only when the drive passes), and refreshes the healed sources'
 * books so their chapters, covers and titles heal with them.
 *
 * The decision logic ([HealSweepLogic], [HealSweepGate],
 * [HealSweep.Outcome]) is pure and unit-tested; the runner is a
 * thin walk over the existing services. Two deliberate choices:
 *
 *  - The sweep never spends the user's AI quota: it drives the
 *    automatic proposers only (the AI detector stays behind the
 *    deliberate tap it has always required).
 *  - It is skipped — without burning its one-time flag — on no
 *    network, a metered connection, or a low battery that isn't
 *    charging, and simply runs on a later launch instead.
 */
object HealSweepLogic {

    /** Link sources only: they are the ones whose rules the app
     * itself inferred, so they are the ones the app can re-infer. */
    fun isEligible(extension: InstalledExtension): Boolean =
        extension.packageId.startsWith("link.") &&
            extension.enabled &&
            extension.trustState == TrustState.TRUSTED

    /** A verdict that means "a reader would trip over this":
     * the drive could not prove the source reads, or proved it
     * needs setup first. */
    fun isFailingState(state: SourceHealthState): Boolean =
        state == SourceHealthState.NEEDS_SETUP ||
            state == SourceHealthState.BROKEN

    fun isPassingState(state: SourceHealthState): Boolean =
        state == SourceHealthState.WORKING ||
            state == SourceHealthState.WORKING_WITH_LIMITS

    /**
     * Healed = the source was failing — by its stored record or
     * by today's drive — and after the repair it passes. A source
     * that already passed was checked, not healed, and a repair
     * that still fails is reported nowhere (no false comfort).
     */
    fun isHealed(
        priorState: SourceHealthState?,
        driveState: SourceHealthState,
        afterState: SourceHealthState
    ): Boolean {
        val wasFailing = isFailingState(driveState) ||
            (priorState != null && isFailingState(priorState))
        return wasFailing && isPassingState(afterState)
    }
}

/** The sweep's run conditions, from the signals the app already
 * has (DownloadPrefs' network gate + the battery level). */
object HealSweepGate {

    const val MIN_BATTERY_PERCENT = 20

    /**
     * @param batteryPercent -1 when the level can't be read;
     * an unreadable battery never blocks the sweep on its own.
     */
    fun shouldRun(
        hasNetwork: Boolean,
        onUnmeteredNetwork: Boolean,
        batteryPercent: Int,
        isCharging: Boolean
    ): Boolean {
        if (!hasNetwork || !onUnmeteredNetwork) return false
        if (isCharging || batteryPercent < 0) return true
        return batteryPercent >= MIN_BATTERY_PERCENT
    }
}

class HealSweep(
    private val extensionDao: InstalledExtensionDao,
    private val bookDao: BookDao,
    private val extensionService: ExtensionService,
    private val contractTester: ContractTester,
    private val linkProber: LinkProber,
    private val repoManager: RepoManager,
    private val extensionStore: ExtensionStore,
    private val sourcePrefs: SourcePrefs,
    private val sourceBookSync: app.xreader.data.library.SourceBookSync,
    private val networkGate: app.xreader.data.download.NetworkGate,
    private val battery: () -> Battery
) {

    data class Battery(val percent: Int, val isCharging: Boolean)

    data class Outcome(
        val sourcesChecked: Int = 0,
        val sourcesHealed: Int = 0,
        val booksRefreshed: Int = 0
    ) {
        /** The one calm line for the Me page; null when nothing
         * was healed (a sweep that fixed nothing says nothing). */
        fun summaryLine(): String? {
            if (sourcesHealed <= 0) return null
            val sourcePart = if (sourcesHealed == 1) {
                "1 source was fixed"
            } else {
                "$sourcesHealed sources were fixed"
            }
            if (booksRefreshed <= 0) return sourcePart
            val bookPart = if (booksRefreshed == 1) {
                "1 book refreshed"
            } else {
                "$booksRefreshed books refreshed"
            }
            return "$sourcePart and $bookPart"
        }
    }

    /**
     * Runs the sweep once per app version. Returns null when the
     * sweep already ran for [currentVersionCode] or the run
     * conditions aren't met (the flag stays unburned then, so a
     * later launch can still sweep).
     */
    suspend fun runIfNeeded(currentVersionCode: Int): Outcome? {
        if (sourcePrefs.healSweepDoneForCode.first() >= currentVersionCode) {
            return null
        }
        val charge = battery()
        if (!HealSweepGate.shouldRun(
                hasNetwork = networkGate.hasNetwork(),
                onUnmeteredNetwork = networkGate.onUnmeteredNetwork(),
                batteryPercent = charge.percent,
                isCharging = charge.isCharging
            )
        ) {
            return null
        }
        sourcePrefs.setHealSweepDoneForCode(currentVersionCode)

        val eligible = extensionDao.allNow()
            .filter { HealSweepLogic.isEligible(it) }
        if (eligible.isEmpty()) return Outcome()
        val health = sourcePrefs.sourceHealth.first()
        var outcome = Outcome()
        for (extension in eligible) {
            outcome = try {
                sweepSource(extension, health, outcome)
            } catch (t: Throwable) {
                // One bad source never stops the sweep.
                outcome
            }
        }
        outcome.summaryLine()?.let { sourcePrefs.setHealNote(it) }
        return outcome
    }

    private suspend fun sweepSource(
        extension: InstalledExtension,
        health: Map<String, SourceHealthRecord>,
        outcome: Outcome
    ): Outcome {
        val handle = firstHandle(extension.id) ?: return outcome
        val sourceId = handle.source.id
        val key = SourceHealthRecord.key(extension.id, sourceId)
        val prior = health[key]
        val verdict = contractTester.test(handle)
        sourcePrefs.setSourceHealth(key, verdict.toRecord())
        var result = outcome.copy(sourcesChecked = outcome.sourcesChecked + 1)
        if (!HealSweepLogic.isFailingState(verdict.state)) return result

        // Failing: repair through the Stage-1 machinery — every
        // automatic proposer, best passing candidate wins, saved
        // only when its own drive passed (LinkProber guarantees
        // that for a Ready outcome).
        val file = extensionStore.definitionFile(extension.packageId)
        val backup = extensionStore.readText(file)
        val config = backup?.let { DeclarativeFingerprints.decode(it) }
            ?.firstOrNull() ?: return result
        val probed = linkProber.probe(
            config.sampleBookUrl ?: config.baseUrl,
            aiAvailable = false,
            tryAllProposers = true
        )
        val draft = (probed as? LinkProber.Outcome.Ready)?.draft
            ?: return result
        when (repoManager.saveLinkSource(draft.config, draft.host)) {
            is AppResult.Err -> {
                // Same courtesy as the manual Repair: a failed
                // save restores the previous rules untouched.
                if (backup != null) extensionStore.writeText(file, backup)
                return result
            }
            is AppResult.Ok -> Unit
        }
        val healedHandle = firstHandle(extension.id) ?: return result
        val after = contractTester.test(healedHandle)
        sourcePrefs.setSourceHealth(
            SourceHealthRecord.key(extension.id, healedHandle.source.id),
            after.toRecord()
        )
        if (!HealSweepLogic.isHealed(prior?.state, verdict.state, after.state)) {
            return result
        }
        var refreshed = 0
        bookDao.allBooks()
            .filter {
                it.extensionId == extension.id &&
                    it.mergedIntoId == null &&
                    it.sourceType == app.xreader.data.db.SourceType.EXTENSION
            }
            .forEach { book ->
                if (sourceBookSync.refresh(book) is AppResult.Ok) refreshed++
            }
        return result.copy(
            sourcesHealed = result.sourcesHealed + 1,
            booksRefreshed = result.booksRefreshed + refreshed
        )
    }

    private suspend fun firstHandle(
        extensionId: Long
    ): SourceHandle? = when (
        val sources = extensionService.sourcesOf(extensionId)
    ) {
        is AppResult.Ok -> sources.value.firstOrNull()
        is AppResult.Err -> null
    }
}
