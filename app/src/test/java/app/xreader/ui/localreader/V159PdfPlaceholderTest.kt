package app.xreader.ui.localreader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.5.9 (Finding 3 §8.7 / F-7+F-8): a pending page composes
 * the PAGE-SHAPED placeholder in both scroll models. Both
 * models render the shared `PdfPagePlaceholder` composable,
 * whose entire geometry/content contract is the pure
 * [pdfPlaceholderSpec] pinned here: the page's true aspect from
 * the cached size table, the tier-1 surface (#0c0d12) and the
 * "Page N" label — never the shipped bare spinner on black
 * (paged) or blank reserved box (continuous).
 */
class V159PdfPlaceholderTest {

    @Test
    fun `placeholder carries the page's true aspect, label and tier-1 surface`() {
        // The owner's page: 396×612 pt, page 8 pending.
        val spec = pdfPlaceholderSpec(page = 7, points = 396 to 612)
        assertEquals(396f / 612f, spec.aspect, 0.0001f)
        assertEquals("Page 8", spec.label)
        assertEquals(0xFF0C0D12, spec.surfaceArgb)
    }

    @Test
    fun `placeholder falls back to the A-series aspect only when the table has no size`() {
        val spec = pdfPlaceholderSpec(page = 0, points = null)
        assertEquals(0.707f, spec.aspect, 0.0001f)
        assertEquals("Page 1", spec.label)
        assertEquals(0xFF0C0D12, spec.surfaceArgb)
        // A degenerate size is treated as missing, not as an
        // aspect that would divide by zero.
        assertEquals(
            0.707f, pdfPlaceholderSpec(0, 0 to 612).aspect, 0.0001f
        )
    }
}
