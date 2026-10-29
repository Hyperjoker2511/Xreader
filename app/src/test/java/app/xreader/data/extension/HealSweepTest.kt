package app.xreader.data.extension

import app.xreader.data.db.ExtensionKind
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.TrustState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.5 (E1): the heal-my-library sweep's decisions are pure —
 * which sources are eligible, what counts as failing, when a
 * repair counts as healed, when the sweep may run at all, and
 * the one calm line it reports. All pinned here; the runner is a
 * thin walk over the same services the Details page uses.
 */
class HealSweepTest {

    private fun extension(
        packageId: String = "link.novelight",
        enabled: Boolean = true,
        trustState: TrustState = TrustState.TRUSTED
    ) = InstalledExtension(
        id = 7,
        repoId = 3,
        displayName = "Novelight",
        packageId = packageId,
        versionName = "1.0",
        versionCode = 1,
        kind = ExtensionKind.DECLARATIVE,
        sha256 = "abc",
        apiLevel = 1,
        enabled = enabled,
        trustState = trustState,
        installedAt = 0,
        updatedAt = 0
    )

    @Test
    fun onlyEnabledTrustedLinkSources_areEligible() {
        assertTrue(HealSweepLogic.isEligible(extension()))
        assertFalse(
            HealSweepLogic.isEligible(extension(packageId = "repo.some.site"))
        )
        assertFalse(HealSweepLogic.isEligible(extension(enabled = false)))
        assertFalse(
            HealSweepLogic.isEligible(
                extension(trustState = TrustState.CHANGED_REQUIRES_RETRUST)
            )
        )
    }

    @Test
    fun failingAndPassing_statesAreDisjoint() {
        assertTrue(HealSweepLogic.isFailingState(SourceHealthState.BROKEN))
        assertTrue(HealSweepLogic.isFailingState(SourceHealthState.NEEDS_SETUP))
        assertFalse(HealSweepLogic.isFailingState(SourceHealthState.WORKING))
        assertFalse(HealSweepLogic.isFailingState(SourceHealthState.UNKNOWN))
        assertTrue(HealSweepLogic.isPassingState(SourceHealthState.WORKING))
        assertTrue(
            HealSweepLogic.isPassingState(SourceHealthState.WORKING_WITH_LIMITS)
        )
        assertFalse(HealSweepLogic.isPassingState(SourceHealthState.BROKEN))
    }

    @Test
    fun healed_requiresFailingBeforeAndPassingAfter() {
        // Broken on record, still broken on the drive, fixed by
        // the repair.
        assertTrue(
            HealSweepLogic.isHealed(
                priorState = SourceHealthState.BROKEN,
                driveState = SourceHealthState.BROKEN,
                afterState = SourceHealthState.WORKING
            )
        )
        // Never checked before, the drive itself proves it
        // broken, the repair fixes it: healed.
        assertTrue(
            HealSweepLogic.isHealed(
                priorState = null,
                driveState = SourceHealthState.NEEDS_SETUP,
                afterState = SourceHealthState.WORKING_WITH_LIMITS
            )
        )
        // Already working: checked, not healed.
        assertFalse(
            HealSweepLogic.isHealed(
                priorState = SourceHealthState.WORKING,
                driveState = SourceHealthState.WORKING,
                afterState = SourceHealthState.WORKING
            )
        )
        // Repair attempt still fails: not healed, no false comfort.
        assertFalse(
            HealSweepLogic.isHealed(
                priorState = SourceHealthState.BROKEN,
                driveState = SourceHealthState.BROKEN,
                afterState = SourceHealthState.BROKEN
            )
        )
    }

    @Test
    fun gate_needsNetworkUnmeteredAndEnoughBattery() {
        assertTrue(
            HealSweepGate.shouldRun(
                hasNetwork = true, onUnmeteredNetwork = true,
                batteryPercent = 80, isCharging = false
            )
        )
        assertFalse(
            HealSweepGate.shouldRun(
                hasNetwork = false, onUnmeteredNetwork = true,
                batteryPercent = 80, isCharging = false
            )
        )
        assertFalse(
            HealSweepGate.shouldRun(
                hasNetwork = true, onUnmeteredNetwork = false,
                batteryPercent = 80, isCharging = false
            )
        )
        assertFalse(
            HealSweepGate.shouldRun(
                hasNetwork = true, onUnmeteredNetwork = true,
                batteryPercent = 10, isCharging = false
            )
        )
        // Low battery is fine while charging; an unreadable
        // battery never blocks on its own.
        assertTrue(
            HealSweepGate.shouldRun(
                hasNetwork = true, onUnmeteredNetwork = true,
                batteryPercent = 10, isCharging = true
            )
        )
        assertTrue(
            HealSweepGate.shouldRun(
                hasNetwork = true, onUnmeteredNetwork = true,
                batteryPercent = -1, isCharging = false
            )
        )
    }

    @Test
    fun summaryLine_speaksPlainlyAndStaysSilentWhenNothingHealed() {
        assertNull(HealSweep.Outcome(sourcesChecked = 4).summaryLine())
        assertEquals(
            "1 source was fixed and 1 book refreshed",
            HealSweep.Outcome(
                sourcesChecked = 4, sourcesHealed = 1, booksRefreshed = 1
            ).summaryLine()
        )
        assertEquals(
            "3 sources were fixed and 6 books refreshed",
            HealSweep.Outcome(
                sourcesChecked = 5, sourcesHealed = 3, booksRefreshed = 6
            ).summaryLine()
        )
        assertEquals(
            "2 sources were fixed",
            HealSweep.Outcome(
                sourcesChecked = 5, sourcesHealed = 2, booksRefreshed = 0
            ).summaryLine()
        )
    }
}
