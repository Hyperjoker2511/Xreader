package app.xreader.data.engine

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v1.5.9 (Finding 3 §8.4 / F-4): page sizes are read ONCE into
 * a cached table; composition-facing lookups — the cached
 * table, the legacy accessor, the placeholder geometry —
 * never call `openPage()` from composition (the shipped code
 * called it on the main thread, unsynchronised against
 * renders — Finding 3 L5).
 *
 * v1.5.11 (step 6, research D8 — re-derived): the table is
 * LAZY. The v1.5.9 table was filled by an open-time walk —
 * one provider size call per page before first paint. Open
 * now makes ZERO provider size calls; a page's size accrues
 * on its first lookup (the session's background completer,
 * started when the pager first publishes a page, warms the
 * rest off the open path), each page is fetched at most
 * once, and every later lookup is served from the table.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V159PdfPageSizeTableTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `open makes zero size calls, first lookups fill the table, and no lookup touches the provider again`() {
        val provider = V159PdfSessionFixtures.CountingProvider()
        val session = V159PdfSessionFixtures.openSession(temp.root, 203, provider)
        try {
            assertEquals(
                "open is lazy: zero provider page-size calls",
                0, provider.sizeCalls.get()
            )
            // First lookups accrue the table: each page's
            // size is fetched exactly once, and the values
            // are the page's own.
            for (page in 0 until V159PdfSessionFixtures.PAGE_COUNT) {
                assertEquals(
                    396 to 612, session.pagePointsSizeCached(page)
                )
                assertEquals(
                    "page $page fetched exactly once on first lookup",
                    page + 1, provider.sizeCalls.get()
                )
            }
            assertEquals(
                "the first-lookup sweep filled the whole table",
                V159PdfSessionFixtures.PAGE_COUNT, provider.sizeCalls.get()
            )
            // After the fill, no lookup touches the provider
            // again — the substance of the v1.5.9 contract,
            // kept whole.
            repeat(3) {
                for (page in 0 until V159PdfSessionFixtures.PAGE_COUNT) {
                    assertEquals(
                        396 to 612, session.pagePointsSizeCached(page)
                    )
                    assertEquals(396 to 612, session.pagePointsSize(page))
                    // Placeholder geometry at the page's own
                    // width: 396 px wide → 612 px tall.
                    assertEquals(396 to 612, session.pageSizeAt(page, 396))
                }
            }
            assertEquals(
                "zero provider page-size calls after the table filled",
                V159PdfSessionFixtures.PAGE_COUNT, provider.sizeCalls.get()
            )
            assertNull(session.pagePointsSizeCached(-1))
            assertNull(
                session.pagePointsSizeCached(V159PdfSessionFixtures.PAGE_COUNT)
            )
            assertEquals(
                "out-of-range lookups never touch the provider either",
                V159PdfSessionFixtures.PAGE_COUNT, provider.sizeCalls.get()
            )
        } finally {
            session.close()
        }
    }
}
