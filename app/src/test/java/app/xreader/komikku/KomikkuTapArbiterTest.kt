package app.xreader.komikku

import app.xreader.komikku.viewer.EdgeNavigation
import app.xreader.komikku.viewer.KomikkuTapArbiter
import app.xreader.komikku.viewer.KomikkuZoomState
import app.xreader.komikku.viewer.LNavigation
import app.xreader.komikku.viewer.RightAndLeftNavigation
import app.xreader.komikku.viewer.TapDecision
import app.xreader.komikku.viewer.ViewerNavigation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIX-18 proofs: the one tap arbiter (§6.1) — a double tap
 * produces zoom and NEVER also fires a tap-zone action — and the
 * §6.3 zoom re-decode rule.
 */
class KomikkuTapArbiterTest {

    private fun arbiter(
        doubleTapZoom: Boolean = true,
        longTap: Boolean = true
    ) = KomikkuTapArbiter(
        navigator = { LNavigation() },
        doubleTapZoomEnabled = { doubleTapZoom },
        longTapEnabled = { longTap }
    )

    @Test
    fun singleTapInCentreIsMenuZone() {
        val decision = arbiter().onSingleTapConfirmed(0.5f, 0.5f)
        assertEquals(
            TapDecision.Zone(ViewerNavigation.NavigationRegion.MENU),
            decision
        )
    }

    @Test
    fun singleTapInNextZoneIsNext() {
        val decision = arbiter().onSingleTapConfirmed(0.5f, 0.9f)
        assertEquals(
            TapDecision.Zone(ViewerNavigation.NavigationRegion.NEXT),
            decision
        )
    }

    @Test
    fun doubleTapInMiddleIsZoomAndNeverAZone() {
        val decision = arbiter().onDoubleTap(0.5f, 0.5f)
        assertTrue(decision is TapDecision.ToggleZoom)
        assertFalse(decision is TapDecision.Zone)
    }

    @Test
    fun doubleTapInNextZoneIsStillOnlyZoom() {
        // The §6.1 rule in its sharpest form: even where a zone
        // action exists, a double tap must not fire it.
        val decision = arbiter().onDoubleTap(0.5f, 0.9f)
        assertTrue(decision is TapDecision.ToggleZoom)
        assertFalse(decision is TapDecision.Zone)
    }

    @Test
    fun doubleTapWithZoomDisabledIsIgnored() {
        assertEquals(
            TapDecision.Ignored,
            arbiter(doubleTapZoom = false).onDoubleTap(0.5f, 0.5f)
        )
    }

    @Test
    fun longTapHonoursTheGate() {
        assertTrue(arbiter().onLongTap(0.5f, 0.5f) is TapDecision.LongTap)
        assertEquals(
            TapDecision.Ignored,
            arbiter(longTap = false).onLongTap(0.5f, 0.5f)
        )
    }

    @Test
    fun zoomTogglesFitZoomFit() {
        val zoom = KomikkuZoomState()
        assertFalse(zoom.isZoomed)
        assertEquals(2f, zoom.onDoubleTap(), 0.001f)
        assertTrue(zoom.isZoomed)
        assertEquals(1f, zoom.onDoubleTap(), 0.001f)
        assertFalse(zoom.isZoomed)
    }

    @Test
    fun zoomRedeDecodeTargetIsSharper() {
        val zoom = KomikkuZoomState()
        zoom.onDoubleTap() // 2x — past the 1.5x trigger
        assertTrue(zoom.needsRedeDecode)
        val target = zoom.zoomDecodeWidthPx(
            oneXWidthPx = 1080,
            sourceNativeWidthPx = 4000,
            minimalTier = false,
            regionHeightPx = 1536
        )
        assertEquals(2160, target)
        assertTrue(target.toFloat() / 1080 >= 1.8f)
    }

    @Test
    fun zoomRedeDecodeBoundedBySourceNative() {
        val zoom = KomikkuZoomState()
        zoom.onDoubleTap()
        val target = zoom.zoomDecodeWidthPx(
            oneXWidthPx = 1080,
            sourceNativeWidthPx = 1500,
            minimalTier = false,
            regionHeightPx = 1536
        )
        assertEquals(1500, target)
    }

    @Test
    fun minimalTierFallsBackWhenRegionExceedsCeiling() {
        val zoom = KomikkuZoomState()
        zoom.onDoubleTap()
        // 4096 x 3072 x 4B = ~50MB > the 32MB MINIMAL ceiling:
        // bitmap magnification, designed (§6.3 carve-out).
        val target = zoom.zoomDecodeWidthPx(
            oneXWidthPx = 2048,
            sourceNativeWidthPx = 8192,
            minimalTier = true,
            regionHeightPx = 3072
        )
        assertEquals(2048, target)
    }

    @Test
    fun pinchRespectsDisableZoomOutAndIn() {
        val zoom = KomikkuZoomState()
        zoom.onPinch(0.6f, disableZoomOut = true, disableZoomIn = false)
        assertEquals(1f, zoom.scale, 0.001f)
        zoom.onPinch(3f, disableZoomOut = false, disableZoomIn = true)
        assertEquals(1f, zoom.scale, 0.001f)
    }

    @Test
    fun sliceBudgetsAreThePrintedSection62Numbers() {
        // §6.2 ceilings as acceptance numbers (MINIMAL/STANDARD/FULL).
        assertEquals(
            32L * 1024 * 1024,
            app.xreader.ui.reader.SlicePlan152.residentCeilingBytes(
                app.xreader.data.prefs.PerfTier.MINIMAL
            )
        )
        assertEquals(
            64L * 1024 * 1024,
            app.xreader.ui.reader.SlicePlan152.residentCeilingBytes(
                app.xreader.data.prefs.PerfTier.STANDARD
            )
        )
        assertEquals(
            96L * 1024 * 1024,
            app.xreader.ui.reader.SlicePlan152.residentCeilingBytes(
                app.xreader.data.prefs.PerfTier.FULL
            )
        )
        assertEquals(3, app.xreader.ui.reader.SlicePlan152.maxResidentSlices(app.xreader.data.prefs.PerfTier.MINIMAL))
        assertEquals(4, app.xreader.ui.reader.SlicePlan152.maxResidentSlices(app.xreader.data.prefs.PerfTier.STANDARD))
        assertEquals(5, app.xreader.ui.reader.SlicePlan152.maxResidentSlices(app.xreader.data.prefs.PerfTier.FULL))
        assertEquals(1536, app.xreader.ui.reader.SlicePlan152.sliceHeightPx(app.xreader.data.prefs.PerfTier.MINIMAL))
        assertEquals(3072, app.xreader.ui.reader.SlicePlan152.sliceHeightPx(app.xreader.data.prefs.PerfTier.FULL))
    }

    // ── v1.5.6 (§1.2 evidence step): the PAGER surface's tap
    // dispatch, at harness level. PagerSurface installs this
    // same arbiter with the PagerConfig's navigator; if the
    // pager's arbiter were dead, these would convict it on
    // the machine instead of on the phone. (The on-device
    // gesture question itself stays with §9 journey 2.)

    @Test
    fun pagerArbiterDispatchesLeftAndRightZones() {
        val pagerArbiter = KomikkuTapArbiter(
            navigator = { RightAndLeftNavigation() },
            doubleTapZoomEnabled = { true },
            longTapEnabled = { true }
        )
        assertEquals(
            TapDecision.Zone(ViewerNavigation.NavigationRegion.LEFT),
            pagerArbiter.onSingleTapConfirmed(0.1f, 0.5f)
        )
        assertEquals(
            TapDecision.Zone(ViewerNavigation.NavigationRegion.RIGHT),
            pagerArbiter.onSingleTapConfirmed(0.9f, 0.5f)
        )
        // Centre falls through to MENU (chrome toggle).
        assertEquals(
            TapDecision.Zone(ViewerNavigation.NavigationRegion.MENU),
            pagerArbiter.onSingleTapConfirmed(0.5f, 0.5f)
        )
    }

    @Test
    fun pagerArbiterDispatchesEdgeZonesAndDoubleTapNeverZones() {
        val pagerArbiter = KomikkuTapArbiter(
            navigator = { EdgeNavigation() },
            doubleTapZoomEnabled = { true },
            longTapEnabled = { true }
        )
        assertEquals(
            TapDecision.Zone(ViewerNavigation.NavigationRegion.NEXT),
            pagerArbiter.onSingleTapConfirmed(0.1f, 0.5f)
        )
        assertEquals(
            TapDecision.Zone(ViewerNavigation.NavigationRegion.PREV),
            pagerArbiter.onSingleTapConfirmed(0.5f, 0.9f)
        )
        // A double tap in a pager's NEXT zone is zoom only.
        val decision = pagerArbiter.onDoubleTap(0.1f, 0.5f)
        assertTrue(decision is TapDecision.ToggleZoom)
        assertFalse(decision is TapDecision.Zone)
    }
}
