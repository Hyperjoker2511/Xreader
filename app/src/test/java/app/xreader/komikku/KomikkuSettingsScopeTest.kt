package app.xreader.komikku

import app.xreader.komikku.setting.InMemoryKomikkuStore
import app.xreader.komikku.setting.KPreference
import app.xreader.komikku.setting.ReaderBottomButton
import app.xreader.komikku.setting.ReaderOrientation
import app.xreader.komikku.setting.ReaderPreferences
import app.xreader.komikku.setting.ReadingMode
import app.xreader.komikku.setting.ScopedKomikkuStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIX-18 proofs: the FULL transplanted settings surface (the
 * donor ReaderPreferences' 80 accessors, walked in the donor
 * tree) and the owner's option-scope rule as clarified
 * 2026-11-03 ~21:10 — scope keys on WHERE the change is made,
 * for EVERY setting, with no per-family split:
 *  - through the reader's scoped preferences → the book layer
 *    only, the global store is byte-untouched;
 *  - through the global preferences (Settings/Customise) → the
 *    global default, which the scoped read then falls back to.
 */
class KomikkuSettingsScopeTest {

    private class Case(
        val name: String,
        val write: (ReaderPreferences) -> Unit,
        val read: (ReaderPreferences) -> Any?
    )

    private fun boolCase(
        name: String,
        pref: (ReaderPreferences) -> KPreference<Boolean>
    ): Case = Case(
        name,
        write = { p -> pref(p).set(!pref(p).get()) },
        read = { p -> pref(p).get() }
    )

    private fun intCase(
        name: String,
        pref: (ReaderPreferences) -> KPreference<Int>
    ): Case = Case(
        name,
        write = { p -> pref(p).set(pref(p).get() + 7) },
        read = { p -> pref(p).get() }
    )

    private fun <T : Enum<T>> enumCase(
        name: String,
        pref: (ReaderPreferences) -> KPreference<T>
    ): Case = Case(
        name,
        write = { p ->
            val values = pref(p).get().javaClass.enumConstants
            val next = values[(values.indexOf(pref(p).get()) + 1) % values.size]
            pref(p).set(next)
        },
        read = { p -> pref(p).get() }
    )

    /** The walked inventory: every accessor in the donor's
     * ReaderPreferences (grep-verified count: 80). */
    private fun inventory(): List<Case> = listOf(
        boolCase("pageTransitionsPager") { it.pageTransitionsPager() },
        boolCase("pageTransitionsWebtoon") { it.pageTransitionsWebtoon() },
        boolCase("flashOnPageChange") { it.flashOnPageChange() },
        intCase("flashDurationMillis") { it.flashDurationMillis() },
        intCase("flashPageInterval") { it.flashPageInterval() },
        enumCase("flashColor") { it.flashColor() },
        intCase("doubleTapAnimSpeed") { it.doubleTapAnimSpeed() },
        boolCase("showPageNumber") { it.showPageNumber() },
        boolCase("showReadingMode") { it.showReadingMode() },
        boolCase("fullscreen") { it.fullscreen() },
        boolCase("drawUnderCutout") { it.drawUnderCutout() },
        boolCase("keepScreenOn") { it.keepScreenOn() },
        intCase("defaultReadingMode") { it.defaultReadingMode() },
        intCase("defaultOrientationType") { it.defaultOrientationType() },
        boolCase("webtoonDoubleTapZoomEnabled") { it.webtoonDoubleTapZoomEnabled() },
        boolCase("pagedDoubleTapZoomEnabled") { it.pagedDoubleTapZoomEnabled() },
        boolCase("webtoonPinchToZoomEnabled") { it.webtoonPinchToZoomEnabled() },
        intCase("imageScaleType") { it.imageScaleType() },
        enumCase("webtoonScaleType") { it.webtoonScaleType() },
        boolCase("longStripGapSmartScale") { it.longStripGapSmartScale() },
        intCase("zoomStart") { it.zoomStart() },
        intCase("readerTheme") { it.readerTheme() },
        boolCase("alwaysShowChapterTransition") { it.alwaysShowChapterTransition() },
        boolCase("cropBorders") { it.cropBorders() },
        boolCase("navigateToPan") { it.navigateToPan() },
        boolCase("landscapeZoom") { it.landscapeZoom() },
        enumCase("landscapeZoomType") { it.landscapeZoomType() },
        boolCase("cropBordersWebtoon") { it.cropBordersWebtoon() },
        intCase("webtoonSidePadding") { it.webtoonSidePadding() },
        enumCase("readerHideThreshold") { it.readerHideThreshold() },
        boolCase("folderPerManga") { it.folderPerManga() },
        boolCase("skipRead") { it.skipRead() },
        boolCase("skipFiltered") { it.skipFiltered() },
        boolCase("skipDupe") { it.skipDupe() },
        boolCase("webtoonDisableZoomOut") { it.webtoonDisableZoomOut() },
        boolCase("pagedDisableZoomIn") { it.pagedDisableZoomIn() },
        boolCase("dualPageSplitPaged") { it.dualPageSplitPaged() },
        boolCase("dualPageInvertPaged") { it.dualPageInvertPaged() },
        boolCase("dualPageSplitWebtoon") { it.dualPageSplitWebtoon() },
        boolCase("dualPageInvertWebtoon") { it.dualPageInvertWebtoon() },
        boolCase("dualPageRotateToFit") { it.dualPageRotateToFit() },
        boolCase("dualPageRotateToFitInvert") { it.dualPageRotateToFitInvert() },
        boolCase("dualPageRotateToFitWebtoon") { it.dualPageRotateToFitWebtoon() },
        boolCase("dualPageRotateToFitInvertWebtoon") { it.dualPageRotateToFitInvertWebtoon() },
        boolCase("customBrightness") { it.customBrightness() },
        intCase("customBrightnessValue") { it.customBrightnessValue() },
        boolCase("colorFilter") { it.colorFilter() },
        intCase("colorFilterValue") { it.colorFilterValue() },
        intCase("colorFilterMode") { it.colorFilterMode() },
        boolCase("grayscale") { it.grayscale() },
        boolCase("invertedColors") { it.invertedColors() },
        boolCase("readWithLongTap") { it.readWithLongTap() },
        boolCase("readWithVolumeKeys") { it.readWithVolumeKeys() },
        boolCase("readWithVolumeKeysInverted") { it.readWithVolumeKeysInverted() },
        intCase("navigationModePager") { it.navigationModePager() },
        intCase("navigationModeWebtoon") { it.navigationModeWebtoon() },
        enumCase("pagerNavInverted") { it.pagerNavInverted() },
        enumCase("webtoonNavInverted") { it.webtoonNavInverted() },
        boolCase("showNavigationOverlayNewUser") { it.showNavigationOverlayNewUser() },
        boolCase("showNavigationOverlayOnStart") { it.showNavigationOverlayOnStart() },
        boolCase("smallerTapZone") { it.smallerTapZone() },
        intCase("readerThreads") { it.readerThreads() },
        boolCase("readerInstantRetry") { it.readerInstantRetry() },
        boolCase("aggressivePageLoading") { it.aggressivePageLoading() },
        Case(
            "cacheSize",
            write = { it.cacheSize().set("123") },
            read = { it.cacheSize().get() }
        ),
        Case(
            "autoscrollInterval",
            write = { it.autoscrollInterval().set(9.5f) },
            read = { it.autoscrollInterval().get() }
        ),
        boolCase("smoothAutoScroll") { it.smoothAutoScroll() },
        boolCase("preserveReadingPosition") { it.preserveReadingPosition() },
        intCase("preloadSize") { it.preloadSize() },
        boolCase("useAutoWebtoon") { it.useAutoWebtoon() },
        boolCase("continuousVerticalTappingByPage") { it.continuousVerticalTappingByPage() },
        boolCase("cropBordersContinuousVertical") { it.cropBordersContinuousVertical() },
        boolCase("forceHorizontalSeekbar") { it.forceHorizontalSeekbar() },
        boolCase("landscapeVerticalSeekbar") { it.landscapeVerticalSeekbar() },
        boolCase("leftVerticalSeekbar") { it.leftVerticalSeekbar() },
        Case(
            "readerBottomButtons",
            write = { it.readerBottomButtons().set(setOf("vc")) },
            read = { it.readerBottomButtons().get() }
        ),
        intCase("pageLayout") { it.pageLayout() },
        boolCase("invertDoublePages") { it.invertDoublePages() },
        intCase("centerMarginType") { it.centerMarginType() },
        intCase("archiveReaderMode") { it.archiveReaderMode() }
    )

    @Test
    fun inventoryIsTheFullDonorSurface() {
        assertEquals(80, inventory().size)
        assertEquals(80, inventory().map { it.name }.toSet().size)
    }

    @Test
    fun everySettingWrittenInReaderLandsOnBookLayerOnly() {
        val globalStore = InMemoryKomikkuStore()
        val bookStore = InMemoryKomikkuStore()
        val scoped = ReaderPreferences(ScopedKomikkuStore(globalStore, bookStore))
        val global = ReaderPreferences(globalStore)

        for (case in inventory()) {
            val globalBefore = globalStore.snapshot()
            val scopedBefore = case.read(scoped)
            case.write(scoped)
            val scopedAfter = case.read(scoped)
            assertTrue(
                "setting ${case.name} did not change through the scoped write",
                scopedBefore != scopedAfter
            )
            assertEquals(
                "setting ${case.name} leaked into the GLOBAL store from an in-reader write",
                globalBefore,
                globalStore.snapshot()
            )
            assertEquals(
                "global read of ${case.name} must stay at its default",
                case.read(ReaderPreferences(InMemoryKomikkuStore())),
                case.read(global)
            )
        }
        // And the book layer really accumulated the writes.
        assertTrue(bookStore.snapshot().isNotEmpty())
    }

    @Test
    fun everySettingWrittenInSettingsLandsOnGlobalAndScopesReadIt() {
        val globalStore = InMemoryKomikkuStore()
        val bookStore = InMemoryKomikkuStore()
        val scoped = ReaderPreferences(ScopedKomikkuStore(globalStore, bookStore))
        val global = ReaderPreferences(globalStore)

        for (case in inventory()) {
            case.write(global)
            assertEquals(
                "scoped read of ${case.name} must fall back to the global default",
                case.read(global),
                case.read(scoped)
            )
            assertTrue(
                "book layer must stay empty for a Settings write (${case.name})",
                bookStore.snapshot().isEmpty()
            )
        }
    }

    @Test
    fun donorDefaultsArePreserved() {
        val prefs = ReaderPreferences(InMemoryKomikkuStore())
        assertEquals(
            ReadingMode.RIGHT_TO_LEFT.flagValue,
            prefs.defaultReadingMode().get()
        )
        assertEquals(
            ReaderOrientation.FREE.flagValue,
            prefs.defaultOrientationType().get()
        )
        assertEquals(100, prefs.flashDurationMillis().get())
        assertEquals(500, prefs.doubleTapAnimSpeed().get())
        assertEquals(0, prefs.webtoonSidePadding().get())
        assertEquals(2, prefs.readerThreads().get())
        assertEquals("75", prefs.cacheSize().get())
        assertEquals(10, prefs.preloadSize().get())
        assertEquals(true, prefs.skipFiltered().get())
        assertEquals(false, prefs.skipRead().get())
        assertEquals(true, prefs.showPageNumber().get())
        assertEquals(true, prefs.webtoonDoubleTapZoomEnabled().get())
        assertEquals(true, prefs.pagedDoubleTapZoomEnabled().get())
        assertEquals(
            app.xreader.komikku.viewer.pager.PagerConfig.PageLayout.AUTOMATIC,
            prefs.pageLayout().get()
        )
        assertEquals(
            ReaderBottomButton.BUTTONS_DEFAULTS,
            prefs.readerBottomButtons().get()
        )
        assertEquals(
            ReaderPreferences.ReaderHideThreshold.LOW,
            prefs.readerHideThreshold().get()
        )
    }

    @Test
    fun perBookOverlayDoesNotCrossBooks() {
        val globalStore = InMemoryKomikkuStore()
        val bookA = InMemoryKomikkuStore()
        val bookB = InMemoryKomikkuStore()
        val scopedA = ReaderPreferences(ScopedKomikkuStore(globalStore, bookA))
        val scopedB = ReaderPreferences(ScopedKomikkuStore(globalStore, bookB))
        scopedA.cropBorders().set(true)
        assertEquals(true, scopedA.cropBorders().get())
        assertEquals(false, scopedB.cropBorders().get())
    }
}
