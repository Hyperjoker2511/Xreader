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
 * (eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonConfig).
 */
package app.xreader.komikku.viewer.webtoon

import app.xreader.komikku.setting.ReaderPreferences
import app.xreader.komikku.viewer.LNavigation
import app.xreader.komikku.viewer.ViewerConfig
import app.xreader.komikku.viewer.ViewerNavigation
import app.xreader.komikku.viewer.viewerNavigationFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** Configuration used by webtoon viewers. (Donor: WebtoonConfig.) */
class WebtoonConfig(
    scope: CoroutineScope,
    readerPreferences: ReaderPreferences
) : ViewerConfig(readerPreferences, scope) {

    var themeChangedListener: (() -> Unit)? = null

    var imageCropBorders = false
        private set

    var zoomOutDisabled = false
        private set

    var zoomPropertyChangedListener: ((Boolean) -> Unit)? = null

    var sidePadding = 0
        private set

    var doubleTapZoom = true
        private set

    var doubleTapZoomChangedListener: ((Boolean) -> Unit)? = null

    // KMK -->
    var pinchToZoom = true
        private set

    var pinchToZoomChangedListener: ((Boolean) -> Unit)? = null

    var webtoonScaleType = readerPreferences.webtoonScaleType().get()
        private set

    var webtoonScaleTypeChangedListener: ((ReaderPreferences.WebtoonScaleType) -> Unit)? = null
    // KMK <--

    // SY -->
    var usePageTransitions = false

    var continuousCropBorders = false
        private set

    /** Donor SY: tapping in the gaps viewer moves by page. */
    var continuousTappingByPage = false
        private set

    /** KMK: the long-strip scale type also applies to the gaps
     * viewer when this is on (WebtoonViewer's smart-scale gate). */
    var longStripGapSmartScale = false
        private set
    // SY <--

    init {
        readerPreferences.continuousVerticalTappingByPage()
            .register({ continuousTappingByPage = it })

        readerPreferences.longStripGapSmartScale()
            .register({ longStripGapSmartScale = it })

        readerPreferences.cropBordersWebtoon()
            .register({ imageCropBorders = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.webtoonSidePadding()
            .register({ sidePadding = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.navigationModeWebtoon()
            .register({ navigationMode = it }, { updateNavigation(it) })

        readerPreferences.webtoonNavInverted()
            .register({ tappingInverted = it }, { navigator.invertMode = it })
        readerPreferences.webtoonNavInverted().changes()
            .drop(1)
            .onEach { navigationModeChangedListener?.invoke() }
            .launchIn(scope)
        // KMK -->
        readerPreferences.smallerTapZone().changes()
            .drop(1)
            .onEach { updateNavigation(navigationMode) }
            .launchIn(scope)
        // KMK <--

        readerPreferences.dualPageSplitWebtoon()
            .register({ dualPageSplit = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.dualPageInvertWebtoon()
            .register({ dualPageInvert = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.dualPageRotateToFitWebtoon()
            .register(
                { dualPageRotateToFit = it },
                { imagePropertyChangedListener?.invoke() }
            )

        readerPreferences.dualPageRotateToFitInvertWebtoon()
            .register(
                { dualPageRotateToFitInvert = it },
                { imagePropertyChangedListener?.invoke() }
            )

        readerPreferences.webtoonDisableZoomOut()
            .register(
                { zoomOutDisabled = it },
                { zoomPropertyChangedListener?.invoke(it) }
            )

        readerPreferences.webtoonDoubleTapZoomEnabled()
            .register(
                { doubleTapZoom = it },
                { doubleTapZoomChangedListener?.invoke(it) }
            )

        // KMK -->
        readerPreferences.webtoonPinchToZoomEnabled()
            .register(
                { pinchToZoom = it },
                { pinchToZoomChangedListener?.invoke(it) }
            )

        readerPreferences.webtoonScaleType()
            .register(
                { webtoonScaleType = it },
                { webtoonScaleTypeChangedListener?.invoke(it) }
            )
        // KMK <--

        readerPreferences.readerTheme().changes()
            .drop(1)
            .distinctUntilChanged()
            .onEach { themeChangedListener?.invoke() }
            .launchIn(scope)

        // SY -->
        readerPreferences.cropBordersContinuousVertical()
            .register({ continuousCropBorders = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.pageTransitionsWebtoon()
            .register({ usePageTransitions = it }, { imagePropertyChangedListener?.invoke() })
        // SY <--
    }

    override var navigator: ViewerNavigation = defaultNavigation()
        set(value) {
            field = value.also { it.invertMode = tappingInverted }
        }

    override fun defaultNavigation(): ViewerNavigation = LNavigation(smallerTapZone())

    override fun updateNavigation(navigationMode: Int) {
        this.navigator = viewerNavigationFor(navigationMode, ::defaultNavigation, smallerTapZone())
        navigationModeChangedListener?.invoke()
    }
}
