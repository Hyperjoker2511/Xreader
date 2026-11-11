package app.xreader.ui.book

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.4 (WS-8, owner 2026-11-04, final scoping 18:38–18:39
 * IST): partiality must be INVISIBLE on the book page. His
 * Novelight books opened with "112 loaded · partial list"
 * on the source line, a Repair action beside it, and a
 * Chapters card announcing that more chapters load as you
 * read — together they read as "this source is broken" on
 * healthy books ("it's showing partial, repair option — I
 * thought it broke"; "We should not know it's partial load;
 * as we scroll it should load").
 *
 * The loading mechanism is untouched (progressive walk,
 * edge growth, flags, persistence). What these pins hold:
 *  - the source banner shows the plain loaded count — no
 *    "partial list" marker, no Repair beside the count;
 *  - the Chapters-section announcement card and the tail
 *    "load the full list" row are gone from the normal flow;
 *  - the ONE allowed surfacing is a failed load: a
 *    plain-words retry row gated on an actual chapters
 *    error (a failed load is the only "broke" signal);
 *  - Repair's genuine doors survive untouched — the
 *    Chapters recovery card's "Go to Repair" (failure
 *    states) and the source Details page.
 *
 * Source-structure pins in the V151UiAuditTest tradition:
 * the defect is a rendered presentation, invisible to
 * behaviour tests; the gate asserts the shape in source.
 */
class V154SourceBannerRepairTest {

    private fun source(relative: String): String {
        val roots = listOf(
            File("src/main/java/app/xreader"),
            File("app/src/main/java/app/xreader"),
            File(System.getProperty("user.dir") ?: ".")
                .resolve("src/main/java/app/xreader")
        )
        val file = roots.map { it.resolve(relative) }.firstOrNull { it.exists() }
            ?: throw AssertionError(
                "source file not found from any known root: $relative"
            )
        return file.readText()
    }

    /** The source-banner item's block: from its item key to
     * the next item key on the page. */
    private fun bannerBlock(src: String): String {
        val start = src.indexOf("item(key = \"source-banner\")")
        assertTrue("source-banner item not found", start >= 0)
        val end = src.indexOf("item(key = ", start + 1)
        assertTrue("no following item after source-banner", end > start)
        return src.substring(start, end)
    }

    @Test
    fun `source banner offers no Repair beside the chapter count`() {
        val banner = bannerBlock(source("ui/book/BookDetailScreen.kt"))
        assertFalse(
            "the banner must not pair the chapter-state line with Repair",
            banner.contains("Text(\"Repair\")")
        )
        assertFalse(
            "the banner must not route to the source details (Repair) page",
            banner.contains("onOpenSourceDetails")
        )
    }

    @Test
    fun `source banner shows the plain loaded count with no partial marker`() {
        val banner = bannerBlock(source("ui/book/BookDetailScreen.kt"))
        assertTrue(
            "the loaded count stays on the banner",
            banner.contains("loaded")
        )
        assertFalse(
            "partiality is invisible: no partial-list marker on the line",
            banner.contains("· partial list")
        )
    }

    @Test
    fun `no partiality announcement in the Chapters section or list tail`() {
        val screen = source("ui/book/BookDetailScreen.kt")
        assertFalse(
            "the announcement card's Load full list button is gone",
            screen.contains("Text(\"Load full list\")")
        )
        assertFalse(
            "the announcement card's copy is gone",
            screen.contains("chapters load as you read")
        )
        assertFalse(
            "the tail row announcing chapters beyond the loaded ones is gone",
            screen.contains("item(key = \"chapters-more\")")
        )
        assertFalse(
            "the tail row's copy is gone",
            screen.contains("More chapters beyond these")
        )
    }

    @Test
    fun `a failed load is the one surfacing - plain words retry row gated on a real error`() {
        val screen = source("ui/book/BookDetailScreen.kt")
        assertTrue(
            "the retry row carries plain-words failure copy",
            screen.contains("More chapters couldn't be loaded")
        )
        assertTrue(
            "the retry row offers the retry door",
            screen.contains("Text(\"Try again\")")
        )
        assertTrue(
            "the retry row is gated on an actual chapters error, " +
                "never on list-completeness bookkeeping",
            screen.contains("state.chaptersError != null")
        )
    }

    @Test
    fun `Repair's genuine doors survive`() {
        val screen = source("ui/book/BookDetailScreen.kt")
        assertTrue(
            "the Chapters recovery card keeps its Go to Repair door",
            screen.contains("Text(\"Go to Repair\")")
        )
        assertTrue(
            "the recovery door still routes to the source details page",
            screen.contains("section.canGoToRepair")
        )
    }

    @Test
    fun `edge growth surfaces failure instead of swallowing it`() {
        val vm = source("ui/book/BookDetailViewModel.kt")
        val start = vm.indexOf("private fun launchGrow")
        assertTrue("launchGrow not found", start >= 0)
        val end = vm.indexOf("\n    fun ", start + 1)
            .takeIf { it > start }
            ?: vm.indexOf("\n    private fun ", start + 1)
        assertTrue("launchGrow block not bounded", end > start)
        val grow = vm.substring(start, end)
        assertTrue(
            "a failed grow lands in the chapters error state",
            grow.contains("noteChaptersError(result.error)")
        )
        assertTrue(
            "the completion door the retry row calls still exists",
            vm.contains("fun loadFullChapterList()")
        )
    }
}
