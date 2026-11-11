/*
 * Copyright 2024 The Komikku Authors
 * Copyright 2015 Javier Tomás
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * FIX-18 (v1.5.2): transplanted from Komikku
 * (eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation +
 * util/lang/RectFExtensions + the viewer navigation classes). Geometry,
 * region tables and inversion math preserved exactly; android
 * RectF/PointF become the pure-Kotlin KRectF/KPointF below so the
 * geometry is JVM-provable, and the donor's Injekt lookup of
 * smallerTapZone becomes a constructor value fed from the same
 * preference.
 */
package app.xreader.komikku.viewer

import app.xreader.komikku.setting.ReaderPreferences

/** Pure-Kotlin stand-in for android.graphics.RectF (unit space). */
data class KRectF(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x < right && y >= top && y < bottom

    /** Transplanted from the donor's RectF.invert extension. */
    fun invert(invertMode: ReaderPreferences.TappingInvertMode): KRectF {
        val horizontal = invertMode.shouldInvertHorizontal
        val vertical = invertMode.shouldInvertVertical
        return when {
            horizontal && vertical ->
                KRectF(1f - right, 1f - bottom, 1f - left, 1f - top)
            vertical -> KRectF(left, 1f - bottom, right, 1f - top)
            horizontal -> KRectF(1f - right, top, 1f - left, bottom)
            else -> this
        }
    }
}

data class KPointF(val x: Float, val y: Float)

abstract class ViewerNavigation(
    private val smallerTapZone: Boolean = false
) {

    sealed class NavigationRegion(val label: String) {
        data object MENU : NavigationRegion("Menu")
        data object PREV : NavigationRegion("Previous")
        data object NEXT : NavigationRegion("Next")
        data object LEFT : NavigationRegion("Left")
        data object RIGHT : NavigationRegion("Right")
    }

    data class Region(
        val rectF: KRectF,
        val type: NavigationRegion
    ) {
        fun invert(invertMode: ReaderPreferences.TappingInvertMode): Region {
            if (invertMode == ReaderPreferences.TappingInvertMode.NONE) return this
            return copy(rectF = rectF.invert(invertMode))
        }
    }

    private var constantMenuRegion: KRectF = KRectF(0f, 0f, 1f, 0.05f)

    var invertMode: ReaderPreferences.TappingInvertMode =
        ReaderPreferences.TappingInvertMode.NONE

    protected abstract var regionList: List<Region>

    /** Returns regions with applied inversion. */
    fun getRegions(): List<Region> = regionList.map { it.invert(invertMode) }

    fun getAction(pos: KPointF): NavigationRegion {
        val region = getRegions().find { it.rectF.contains(pos.x, pos.y) }
        return when {
            region != null -> region.type
            constantMenuRegion.contains(pos.x, pos.y) -> NavigationRegion.MENU
            else -> NavigationRegion.MENU
        }
    }

    // KMK -->
    protected val regionSize1: Float
        get() = if (smallerTapZone) 0.25f else 0.33f
    protected val regionSize2: Float
        get() = 1f - regionSize1
    // KMK <--
}

/*
 * The five tap-zone layouts, transplanted from
 * eu.kanade.tachiyomi.ui.reader.viewer.navigation.* — region tables
 * unchanged (the ASCII maps in each donor file describe these).
 */
class DisabledNavigation(smallerTapZone: Boolean = false) :
    ViewerNavigation(smallerTapZone) {
    override var regionList: List<Region> = emptyList()
}

class EdgeNavigation(smallerTapZone: Boolean = false) :
    ViewerNavigation(smallerTapZone) {
    override var regionList: List<Region> = listOf(
        Region(KRectF(0f, 0f, regionSize1, 1f), NavigationRegion.NEXT),
        Region(KRectF(regionSize1, regionSize2, regionSize2, 1f), NavigationRegion.PREV),
        Region(KRectF(regionSize2, 0f, 1f, 1f), NavigationRegion.NEXT)
    )
}

class KindlishNavigation(smallerTapZone: Boolean = false) :
    ViewerNavigation(smallerTapZone) {
    override var regionList: List<Region> = listOf(
        Region(KRectF(regionSize1, regionSize1, 1f, 1f), NavigationRegion.NEXT),
        Region(KRectF(0f, regionSize1, regionSize1, 1f), NavigationRegion.PREV)
    )
}

open class LNavigation(smallerTapZone: Boolean = false) :
    ViewerNavigation(smallerTapZone) {
    override var regionList: List<Region> = listOf(
        Region(KRectF(0f, regionSize1, regionSize1, regionSize2), NavigationRegion.PREV),
        Region(KRectF(0f, 0f, 1f, regionSize1), NavigationRegion.PREV),
        Region(KRectF(regionSize2, regionSize1, 1f, regionSize2), NavigationRegion.NEXT),
        Region(KRectF(0f, regionSize2, 1f, 1f), NavigationRegion.NEXT)
    )
}

class RightAndLeftNavigation(smallerTapZone: Boolean = false) :
    ViewerNavigation(smallerTapZone) {
    override var regionList: List<Region> = listOf(
        Region(KRectF(0f, 0f, regionSize1, 1f), NavigationRegion.LEFT),
        Region(KRectF(regionSize2, 0f, 1f, 1f), NavigationRegion.RIGHT)
    )
}

/** The donor's navigation-mode table (ViewerConfig.updateNavigation). */
fun viewerNavigationFor(
    mode: Int,
    defaultNavigation: () -> ViewerNavigation,
    smallerTapZone: Boolean = false
): ViewerNavigation = when (mode) {
    0 -> defaultNavigation()
    1 -> LNavigation(smallerTapZone)
    2 -> KindlishNavigation(smallerTapZone)
    3 -> EdgeNavigation(smallerTapZone)
    4 -> RightAndLeftNavigation(smallerTapZone)
    5 -> DisabledNavigation(smallerTapZone)
    else -> defaultNavigation()
}
