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
 * (eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerConfig). The
 * donor keys its zoom-start mapping off the concrete viewer class;
 * here the pager's reading direction is the constructor input —
 * the same three cases (L2R / R2L / vertical-centre).
 */
package app.xreader.komikku.viewer.pager

import app.xreader.komikku.setting.ReaderPreferences
import app.xreader.komikku.setting.ReaderPreferences.LandscapeZoomScaleType
import app.xreader.komikku.viewer.LNavigation
import app.xreader.komikku.viewer.ViewerConfig
import app.xreader.komikku.viewer.ViewerNavigation
import app.xreader.komikku.viewer.RightAndLeftNavigation
import app.xreader.komikku.viewer.viewerNavigationFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

enum class ZoomStartPosition { LEFT, RIGHT, CENTER }

enum class PagerDirection { L2R, R2L, VERTICAL }

/** Configuration used by pager viewers. (Donor: PagerConfig.) */
class PagerConfig(
    private val direction: PagerDirection,
    scope: CoroutineScope,
    readerPreferences: ReaderPreferences
) : ViewerConfig(readerPreferences, scope) {

    var automaticBackground = false
        private set

    var dualPageSplitChangedListener: ((Boolean) -> Unit)? = null

    var reloadChapterListener: ((Boolean) -> Unit)? = null

    var imageScaleType = 1
        private set

    var imageZoomType = ZoomStartPosition.LEFT
        private set

    var imageCropBorders = false
        private set

    var navigateToPan = false
        private set

    var landscapeZoom = false
        private set

    // KMK -->
    var disableZoomIn = false
        private set

    var doubleTapZoom = true
        private set

    var landscapeZoomScaleType = LandscapeZoomScaleType.FIT
        private set
    // KMK <--

    // SY -->
    var usePageTransitions = false

    var shiftDoublePage = false

    var doublePages =
        readerPreferences.pageLayout().get() == PageLayout.DOUBLE_PAGES &&
            !readerPreferences.dualPageSplitPaged().get()
        set(value) {
            field = value
            if (!value) {
                shiftDoublePage = false
            }
        }

    var invertDoublePages = false

    var autoDoublePages = readerPreferences.pageLayout().get() == PageLayout.AUTOMATIC

    var pageCanvasColor = android.graphics.Color.WHITE

    var centerMarginType = CenterMarginType.NONE
    // SY <--

    init {
        readerPreferences.readerTheme()
            .register(
                {
                    themeToColor(it)
                    automaticBackground = it == 3
                },
                {
                    imagePropertyChangedListener?.invoke()
                    themeToColor(it)
                    reloadChapterListener?.invoke(doublePages)
                }
            )

        readerPreferences.imageScaleType()
            .register({ imageScaleType = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.zoomStart()
            .register({ zoomTypeFromPreference(it) }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.cropBorders()
            .register({ imageCropBorders = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.navigateToPan()
            .register({ navigateToPan = it })

        readerPreferences.landscapeZoom()
            .register({ landscapeZoom = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.navigationModePager()
            .register({ navigationMode = it }, { updateNavigation(navigationMode) })

        readerPreferences.pagerNavInverted()
            .register({ tappingInverted = it }, { navigator.invertMode = it })
        readerPreferences.pagerNavInverted().changes()
            .drop(1)
            .onEach { navigationModeChangedListener?.invoke() }
            .launchIn(scope)

        // KMK -->
        readerPreferences.smallerTapZone().changes()
            .drop(1)
            .onEach { updateNavigation(navigationMode) }
            .launchIn(scope)
        // KMK <--

        readerPreferences.dualPageSplitPaged()
            .register(
                { dualPageSplit = it },
                {
                    imagePropertyChangedListener?.invoke()
                    dualPageSplitChangedListener?.invoke(it)
                }
            )

        readerPreferences.dualPageInvertPaged()
            .register({ dualPageInvert = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.dualPageRotateToFit()
            .register(
                { dualPageRotateToFit = it },
                { imagePropertyChangedListener?.invoke() }
            )

        readerPreferences.dualPageRotateToFitInvert()
            .register(
                { dualPageRotateToFitInvert = it },
                { imagePropertyChangedListener?.invoke() }
            )

        // KMK -->
        readerPreferences.pagedDisableZoomIn()
            .register(
                { disableZoomIn = it },
                { imagePropertyChangedListener?.invoke() }
            )
        readerPreferences.pagedDoubleTapZoomEnabled()
            .register(
                { doubleTapZoom = it },
                { imagePropertyChangedListener?.invoke() }
            )

        readerPreferences.landscapeZoomType()
            .register(
                { landscapeZoomScaleType = it },
                { imagePropertyChangedListener?.invoke() }
            )
        // KMK <--

        // SY -->
        readerPreferences.pageTransitionsPager()
            .register({ usePageTransitions = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.pageLayout()
            .register(
                {
                    autoDoublePages = it == PageLayout.AUTOMATIC
                    if (!autoDoublePages) {
                        doublePages = it == PageLayout.DOUBLE_PAGES && dualPageSplit == false
                    }
                },
                {
                    autoDoublePages = it == PageLayout.AUTOMATIC
                    if (!autoDoublePages) {
                        doublePages = it == PageLayout.DOUBLE_PAGES && dualPageSplit == false
                    }
                    reloadChapterListener?.invoke(doublePages)
                }
            )

        readerPreferences.centerMarginType()
            .register({ centerMarginType = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.invertDoublePages()
            .register(
                { invertDoublePages = it && dualPageSplit == false },
                { imagePropertyChangedListener?.invoke() }
            )
        // SY <--
    }

    private fun zoomTypeFromPreference(value: Int) {
        imageZoomType = when (value) {
            // Auto
            1 -> when (direction) {
                PagerDirection.L2R -> ZoomStartPosition.LEFT
                PagerDirection.R2L -> ZoomStartPosition.RIGHT
                PagerDirection.VERTICAL -> ZoomStartPosition.CENTER
            }
            // Left
            2 -> ZoomStartPosition.LEFT
            // Right
            3 -> ZoomStartPosition.RIGHT
            // Center
            else -> ZoomStartPosition.CENTER
        }
    }

    override var navigator: ViewerNavigation = defaultNavigation()
        set(value) {
            field = value.also { it.invertMode = this.tappingInverted }
        }

    override fun defaultNavigation(): ViewerNavigation {
        return when (direction) {
            PagerDirection.VERTICAL -> LNavigation(smallerTapZone())
            else -> RightAndLeftNavigation(smallerTapZone())
        }
    }

    override fun updateNavigation(navigationMode: Int) {
        navigator = viewerNavigationFor(navigationMode, ::defaultNavigation, smallerTapZone())
        navigationModeChangedListener?.invoke()
    }

    object CenterMarginType {
        const val NONE = 0
        const val DOUBLE_PAGE_CENTER_MARGIN = 1
        const val WIDE_PAGE_CENTER_MARGIN = 2
        const val DOUBLE_AND_WIDE_CENTER_MARGIN = 3
    }

    object PageLayout {
        const val SINGLE_PAGE = 0
        const val DOUBLE_PAGES = 1
        const val AUTOMATIC = 2
    }

    fun themeToColor(theme: Int) {
        pageCanvasColor = when (theme) {
            1 -> android.graphics.Color.BLACK
            2 -> 0x202125
            else -> android.graphics.Color.WHITE
        }
    }
}
