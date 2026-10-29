package app.xreader.core.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The AppResult discipline (PLAN §1.5): values flow, failures are
 * typed, cancellation is never swallowed. */
class AppResultTest {

    @Test
    fun `ok flows through map flatMap fold`() {
        val result = AppResult.Ok(2)
            .map { it * 3 }
            .flatMap { AppResult.Ok(it + 1) }
        assertEquals(7, result.getOrNull())
        assertTrue(result.isOk)
        assertEquals("value 7", result.fold({ "value $it" }, { "error" }))
    }

    @Test
    fun `err passes through untouched`() {
        val err: AppResult<Int> = AppResult.Err(AppError.NoNetwork)
        val mapped = err.map { it * 3 }.flatMap { AppResult.Ok(it) }
        assertFalse(mapped.isOk)
        assertEquals(AppError.NoNetwork, mapped.errorOrNull())
        assertEquals("error", mapped.fold({ "value" }, { "error" }))
    }

    @Test
    fun `runCatchingApp maps exceptions to Unknown`() {
        val ok = runCatchingApp { 40 + 2 }
        assertEquals(42, ok.getOrNull())
        val bad = runCatchingApp { throw IllegalStateException("boom") }
        assertFalse(bad.isOk)
        assertEquals("IllegalStateException", (bad.errorOrNull() as AppError.Unknown).causeClass)
    }

    @Test
    fun `cancellation is never swallowed`() {
        try {
            runCatchingApp { throw kotlin.coroutines.cancellation.CancellationException() }
            fail("CancellationException must propagate")
        } catch (expected: kotlin.coroutines.cancellation.CancellationException) {
            // expected
        }
    }
}
