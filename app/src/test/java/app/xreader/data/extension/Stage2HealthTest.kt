package app.xreader.data.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.2 Stage-2 additions: the bookshelf capability (R24) is
 * optional — its failure must never mark a readable source as
 * broken — and the health record's persisted summary must mirror
 * the verdict wording.
 */
class Stage2HealthTest {

    private fun coreOk(): List<CapabilityResult> =
        ContractVerdict.READING_CORE.map {
            CapabilityResult(it, ok = true, detail = "ok")
        }

    @Test
    fun bookshelfFailure_onlyLimits() {
        val results = coreOk() + CapabilityResult(
            SourceCapability.BOOKSHELF, ok = false,
            detail = "the bookshelf didn't load"
        )
        val state = ContractVerdict.judge(results, checkedAtMillis = 5)
        assertEquals(SourceHealthState.WORKING_WITH_LIMITS, state)
        val verdict = ContractVerdict(state, results, checkedAtMillis = 5)
        assertTrue(verdict.failedCore().isEmpty())
        assertEquals(
            listOf(SourceCapability.BOOKSHELF), verdict.failedOptional()
        )
    }

    @Test
    fun bookshelfSuccess_keepsWorking() {
        val results = coreOk() + CapabilityResult(
            SourceCapability.BOOKSHELF, ok = true, detail = "12 books"
        )
        assertEquals(
            SourceHealthState.WORKING,
            ContractVerdict.judge(results, checkedAtMillis = 5)
        )
    }

    @Test
    fun recordSummary_matchesVerdict() {
        val verdict = ContractVerdict(
            SourceHealthState.WORKING, coreOk(), checkedAtMillis = 7
        )
        val record = verdict.toRecord()
        assertEquals("Working", record.summaryLine())
        assertEquals(7L, record.checkedAtMillis)
        assertEquals(SourceHealthState.WORKING, record.state)
    }

    @Test
    fun coreFailure_needsSetup_evenWithBookshelf() {
        val results = coreOk().map {
            if (it.capability == SourceCapability.TEXT) {
                it.copy(ok = false, detail = "empty")
            } else {
                it
            }
        } + CapabilityResult(SourceCapability.BOOKSHELF, ok = true)
        assertEquals(
            SourceHealthState.NEEDS_SETUP,
            ContractVerdict.judge(results, checkedAtMillis = 5)
        )
    }
}
