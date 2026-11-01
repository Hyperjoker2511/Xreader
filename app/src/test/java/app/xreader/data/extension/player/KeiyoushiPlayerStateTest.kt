package app.xreader.data.extension.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * K3: the per-extension state machine — the legal path from
 * Not installed to Ready, the update-failure rule (a failed
 * update never sacrifices the working version), restart
 * normalisation, the load gate, and the uninstall plan.
 */
class KeiyoushiPlayerStateTest {

    private fun fresh() = KeiyoushiPlayerRecord(
        packageName = "eu.kanade.tachiyomi.extension.en.fake",
        displayName = "Fake Panels"
    )

    private fun installedReady(): KeiyoushiPlayerRecord {
        var record = fresh()
        record = KeiyoushiStateMachine.transition(
            record, KeiyoushiEvent.DownloadStarted
        )
        record = KeiyoushiStateMachine.transition(
            record, KeiyoushiEvent.Downloaded
        )
        record = record.copy(
            stagedSha256 = "aa", stagedVersionCode = 106005,
            stagedVersionName = "1.6.5"
        )
        record = KeiyoushiStateMachine.transition(
            record, KeiyoushiEvent.Verified
        )
        record = record.copy(
            consentedSha256 = "aa", apkSha256 = "aa",
            installedVersionCode = 106005,
            installedVersionName = "1.6.5"
        )
        record = KeiyoushiStateMachine.transition(
            record, KeiyoushiEvent.LoadStarted
        )
        record = KeiyoushiStateMachine.transition(
            record,
            KeiyoushiEvent.LoadSucceeded(
                sourceIds = listOf("keiyoushi:pkg:1"),
                sourceHosts = listOf("fake.example"),
                languages = "en"
            )
        )
        return record
    }

    @Test
    fun `the install path walks to ready`() {
        val ready = installedReady()
        assertEquals(KeiyoushiExtStatus.READY, ready.status)
        assertEquals(
            listOf("keiyoushi:pkg:1"), ready.sourceIds
        )
        assertNull(ready.failureReason)
        assertNull(ready.stagedSha256)
    }

    @Test
    fun `illegal transitions throw`() {
        try {
            KeiyoushiStateMachine.transition(
                fresh(), KeiyoushiEvent.Verified
            )
            fail("verify before download must not be legal")
        } catch (expected: IllegalStateException) {
            // expected
        }
        try {
            KeiyoushiStateMachine.transition(
                fresh(), KeiyoushiEvent.Downloaded
            )
            fail("downloaded before started must not be legal")
        } catch (expected: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `a verify failure on a fresh install lands in failed`() {
        var record = fresh()
        record = KeiyoushiStateMachine.transition(
            record, KeiyoushiEvent.DownloadStarted
        )
        record = KeiyoushiStateMachine.transition(
            record, KeiyoushiEvent.Downloaded
        )
        record = KeiyoushiStateMachine.transition(
            record,
            KeiyoushiEvent.VerifyFailed("signed by a different key")
        )
        assertEquals(KeiyoushiExtStatus.FAILED, record.status)
        assertEquals(
            "signed by a different key", record.failureReason
        )
    }

    @Test
    fun `an incompatible verdict names itself`() {
        var record = fresh()
        record = KeiyoushiStateMachine.transition(
            record, KeiyoushiEvent.DownloadStarted
        )
        record = KeiyoushiStateMachine.transition(
            record, KeiyoushiEvent.Downloaded
        )
        record = KeiyoushiStateMachine.transition(
            record,
            KeiyoushiEvent.MarkedIncompatible("needs library 1.4")
        )
        assertEquals(KeiyoushiExtStatus.INCOMPATIBLE, record.status)
        assertEquals("needs library 1.4", record.failureReason)
    }

    @Test
    fun `a failed update returns to ready with the notice kept`() {
        var record = installedReady()
        record = KeiyoushiStateMachine.transition(
            record, KeiyoushiEvent.DownloadStarted
        )
        record = KeiyoushiStateMachine.transition(
            record, KeiyoushiEvent.Downloaded
        )
        record = KeiyoushiStateMachine.transition(
            record,
            KeiyoushiEvent.VerifyFailed("the update file was damaged")
        )
        assertEquals(KeiyoushiExtStatus.READY, record.status)
        assertEquals(
            "the update file was damaged", record.failureReason
        )
        assertEquals(106005, record.installedVersionCode)
    }

    @Test
    fun `a failed load on a fresh install lands in failed`() {
        var record = fresh()
        record = KeiyoushiStateMachine.transition(
            record, KeiyoushiEvent.DownloadStarted
        )
        record = KeiyoushiStateMachine.transition(
            record, KeiyoushiEvent.Downloaded
        )
        record = KeiyoushiStateMachine.transition(
            record, KeiyoushiEvent.Verified
        )
        record = KeiyoushiStateMachine.transition(
            record, KeiyoushiEvent.LoadStarted
        )
        record = KeiyoushiStateMachine.transition(
            record,
            KeiyoushiEvent.LoadFailed("needs a script engine")
        )
        assertEquals(KeiyoushiExtStatus.FAILED, record.status)
        assertEquals("needs a script engine", record.failureReason)
    }

    @Test
    fun `restart normalisation revives a consented loading record`() {
        val loading = installedReady().copy(
            status = KeiyoushiExtStatus.LOADING
        )
        val normalised = KeiyoushiStateMachine
            .normaliseAfterRestart(loading)
        assertEquals(KeiyoushiExtStatus.LOADING, normalised.status)
    }

    @Test
    fun `restart normalisation fails an interrupted fresh install`() {
        val downloading = fresh().copy(
            status = KeiyoushiExtStatus.DOWNLOADING,
            stagedSha256 = "aa"
        )
        val normalised = KeiyoushiStateMachine
            .normaliseAfterRestart(downloading)
        assertEquals(KeiyoushiExtStatus.FAILED, normalised.status)
        assertTrue(
            normalised.failureReason!!.contains("interrupted")
        )
        assertNull(normalised.stagedSha256)
    }

    @Test
    fun `restart normalisation drops a staged consent`() {
        val awaiting = fresh().copy(
            status = KeiyoushiExtStatus.AWAITING_CONSENT,
            stagedSha256 = "aa"
        )
        val normalised = KeiyoushiStateMachine
            .normaliseAfterRestart(awaiting)
        assertEquals(
            KeiyoushiExtStatus.NOT_INSTALLED, normalised.status
        )
        assertNull(normalised.stagedSha256)
    }

    @Test
    fun `the load gate demands consent for exactly the pinned bytes`() {
        val record = installedReady()
        assertNull(KeiyoushiLoadGate.refusalReason(record, "aa"))
        // Case-insensitive digest compare.
        assertNull(KeiyoushiLoadGate.refusalReason(record, "AA"))
        // Tampered bytes refuse.
        assertTrue(
            KeiyoushiLoadGate.refusalReason(record, "bb")!!
                .contains("no longer matches")
        )
        // A missing file refuses.
        assertTrue(
            KeiyoushiLoadGate.refusalReason(record, null)!!
                .contains("missing")
        )
        // No consent refuses even intact bytes.
        val unconsented = record.copy(consentedSha256 = null)
        assertTrue(
            KeiyoushiLoadGate.refusalReason(unconsented, "aa")!!
                .contains("haven't agreed")
        )
        // Consent for OTHER bytes refuses.
        val otherConsent = record.copy(consentedSha256 = "cc")
        assertTrue(
            KeiyoushiLoadGate.refusalReason(otherConsent, "aa")!!
                .contains("changed since you agreed")
        )
    }

    @Test
    fun `the uninstall plan is the full cascade in order`() {
        val plan = KeiyoushiUninstall.plan(installedReady())
        assertEquals(
            listOf(
                KeiyoushiUninstall.Step.REMOVE_SOURCES,
                KeiyoushiUninstall.Step.REMOVE_APK,
                KeiyoushiUninstall.Step.REMOVE_NAMESPACED_DATA,
                KeiyoushiUninstall.Step.REMOVE_SESSION_COOKIES,
                KeiyoushiUninstall.Step.REMOVE_ROW,
                KeiyoushiUninstall.Step.REMOVE_RECORD
            ),
            plan
        )
    }

    @Test
    fun `namespaced ids carry package and mihon id`() {
        assertEquals(
            "keiyoushi:eu.kanade.tachiyomi.extension.en.fake:777",
            KeiyoushiPlayerRecord.namespacedSourceId(
                "eu.kanade.tachiyomi.extension.en.fake", 777L
            )
        )
    }

    // ── K6: crash safety ────────────────────────────────────

    @Test
    fun `a successful load proves the package`() {
        assertTrue(installedReady().hasLoadedOnce)
        assertTrue(!fresh().hasLoadedOnce)
    }

    @Test
    fun `a failed load on a proven package returns to ready with the notice`() {
        // A restore-time load of a package that HAS run
        // before fails with an ordinary error: the proven
        // version falls back to READY, reason kept — the
        // same rule as a failed update.
        var record = installedReady()
        record = KeiyoushiStateMachine.transition(
            record, KeiyoushiEvent.LoadStarted
        )
        record = KeiyoushiStateMachine.transition(
            record,
            KeiyoushiEvent.LoadFailed("the site moved its reader")
        )
        assertEquals(KeiyoushiExtStatus.READY, record.status)
        assertEquals(
            "the site moved its reader", record.failureReason
        )
    }

    @Test
    fun `a quarantined load lands in failed even for a proven package`() {
        var record = installedReady()
        record = KeiyoushiStateMachine.transition(
            record, KeiyoushiEvent.LoadStarted
        )
        record = KeiyoushiStateMachine.transition(
            record,
            KeiyoushiEvent.LoadQuarantined(
                "needs the newer network library (gate step G-K2-1)"
            )
        )
        assertEquals(KeiyoushiExtStatus.FAILED, record.status)
        assertTrue(
            record.failureReason!!.contains("G-K2-1")
        )
        // The install itself is untouched — only its running
        // is quarantined; a user retry re-earns the load.
        assertEquals(106005, record.installedVersionCode)
        assertTrue(record.hasLoadedOnce)
        assertNull(record.stagedSha256)
    }

    @Test
    fun `a verify failure on an unproven install lands in failed`() {
        // installedVersionCode is already pinned by the time
        // an install stages — provenance, not the version
        // fields, decides "a working version exists".
        var record = fresh()
        record = KeiyoushiStateMachine.transition(
            record, KeiyoushiEvent.DownloadStarted
        )
        record = record.copy(installedVersionCode = 106005)
        record = KeiyoushiStateMachine.transition(
            record, KeiyoushiEvent.Downloaded
        )
        record = KeiyoushiStateMachine.transition(
            record,
            KeiyoushiEvent.VerifyFailed("the update file was damaged")
        )
        assertEquals(KeiyoushiExtStatus.FAILED, record.status)
    }
}
