package app.xreader.komikku

import app.xreader.komikku.setting.ReaderPreferences
import app.xreader.komikku.viewer.DisabledNavigation
import app.xreader.komikku.viewer.EdgeNavigation
import app.xreader.komikku.viewer.KPointF
import app.xreader.komikku.viewer.KindlishNavigation
import app.xreader.komikku.viewer.LNavigation
import app.xreader.komikku.viewer.RightAndLeftNavigation
import app.xreader.komikku.viewer.ViewerNavigation.NavigationRegion
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FIX-18 proofs: the transplanted ViewerNavigation geometry
 * (donor region tables, inversion, smaller tap zones).
 */
class KomikkuNavigationTest {

    private fun action(
        nav: app.xreader.komikku.viewer.ViewerNavigation,
        x: Float,
        y: Float
    ): NavigationRegion = nav.getAction(KPointF(x, y))

    @Test
    fun lNavigationZones() {
        val nav = LNavigation()
        assertEquals(NavigationRegion.MENU, action(nav, 0.5f, 0.5f))
        assertEquals(NavigationRegion.PREV, action(nav, 0.5f, 0.1f))
        assertEquals(NavigationRegion.PREV, action(nav, 0.1f, 0.5f))
        assertEquals(NavigationRegion.NEXT, action(nav, 0.5f, 0.9f))
        assertEquals(NavigationRegion.NEXT, action(nav, 0.9f, 0.5f))
    }

    @Test
    fun rightAndLeftZones() {
        val nav = RightAndLeftNavigation()
        assertEquals(NavigationRegion.LEFT, action(nav, 0.1f, 0.5f))
        assertEquals(NavigationRegion.RIGHT, action(nav, 0.9f, 0.5f))
        assertEquals(NavigationRegion.MENU, action(nav, 0.5f, 0.5f))
    }

    @Test
    fun kindlishZones() {
        val nav = KindlishNavigation()
        assertEquals(NavigationRegion.NEXT, action(nav, 0.9f, 0.9f))
        assertEquals(NavigationRegion.PREV, action(nav, 0.1f, 0.9f))
        assertEquals(NavigationRegion.MENU, action(nav, 0.5f, 0.1f))
    }

    @Test
    fun edgeZones() {
        val nav = EdgeNavigation()
        assertEquals(NavigationRegion.NEXT, action(nav, 0.1f, 0.5f))
        assertEquals(NavigationRegion.NEXT, action(nav, 0.9f, 0.5f))
        assertEquals(NavigationRegion.PREV, action(nav, 0.5f, 0.9f))
        assertEquals(NavigationRegion.MENU, action(nav, 0.5f, 0.5f))
    }

    @Test
    fun disabledIsAllMenu() {
        val nav = DisabledNavigation()
        assertEquals(NavigationRegion.MENU, action(nav, 0.1f, 0.9f))
        assertEquals(NavigationRegion.MENU, action(nav, 0.9f, 0.1f))
        assertEquals(NavigationRegion.MENU, action(nav, 0.5f, 0.5f))
    }

    @Test
    fun horizontalInvertFlipsLeftRight() {
        val nav = LNavigation()
        nav.invertMode = ReaderPreferences.TappingInvertMode.HORIZONTAL
        // Un-inverted: left-middle is PREV; inverted it must be NEXT.
        assertEquals(NavigationRegion.NEXT, action(nav, 0.1f, 0.5f))
        assertEquals(NavigationRegion.PREV, action(nav, 0.9f, 0.5f))
    }

    @Test
    fun verticalInvertFlipsTopBottom() {
        val nav = LNavigation()
        nav.invertMode = ReaderPreferences.TappingInvertMode.VERTICAL
        assertEquals(NavigationRegion.NEXT, action(nav, 0.5f, 0.1f))
        assertEquals(NavigationRegion.PREV, action(nav, 0.5f, 0.9f))
    }

    @Test
    fun smallerTapZoneShrinksRegions() {
        val normal = LNavigation(smallerTapZone = false)
        val smaller = LNavigation(smallerTapZone = true)
        // x=0.30 is inside the default 0.33 PREV column but outside
        // the smaller 0.25 column.
        assertEquals(NavigationRegion.PREV, action(normal, 0.30f, 0.5f))
        assertEquals(NavigationRegion.MENU, action(smaller, 0.30f, 0.5f))
    }

    @Test
    fun navigationModeTableMatchesDonor() {
        val smaller = false
        fun def() = RightAndLeftNavigation(smaller)
        assertEquals(
            RightAndLeftNavigation::class,
            app.xreader.komikku.viewer.viewerNavigationFor(0, ::def, smaller)::class
        )
        assertEquals(
            LNavigation::class,
            app.xreader.komikku.viewer.viewerNavigationFor(1, ::def, smaller)::class
        )
        assertEquals(
            KindlishNavigation::class,
            app.xreader.komikku.viewer.viewerNavigationFor(2, ::def, smaller)::class
        )
        assertEquals(
            EdgeNavigation::class,
            app.xreader.komikku.viewer.viewerNavigationFor(3, ::def, smaller)::class
        )
        assertEquals(
            RightAndLeftNavigation::class,
            app.xreader.komikku.viewer.viewerNavigationFor(4, ::def, smaller)::class
        )
        assertEquals(
            DisabledNavigation::class,
            app.xreader.komikku.viewer.viewerNavigationFor(5, ::def, smaller)::class
        )
    }
}
