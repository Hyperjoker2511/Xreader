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
 * FIX-18 (v1.5.2): the transplanted gesture contract. The donor
 * routes every tap through ONE GestureDetector per viewer
 * (GestureDetectorWithLongTap + Viewer.onSingleTapConfirmed /
 * onDoubleTap): the detector's single-tap callback fires only
 * after the double-tap window closes without a second tap, and a
 * double tap is consumed by the zoom toggle — so a double tap can
 * never also fire a tap-zone action. This dispatcher is that
 * contract as pure logic (the Compose surface feeds it from
 * detectTapGestures' onTap/onDoubleTap/onLongPress, whose
 * semantics are the detector's: onTap IS the confirmed single
 * tap). §6.1's rule — one arbiter, double tap = zoom and never a
 * zone action, in every mode — is the dispatcher's invariant.
 */
package app.xreader.komikku.viewer

import app.xreader.ui.reader.SlicePlan152

/** What one settled gesture means. */
sealed interface TapDecision {
    /** A confirmed single tap: run the tap-zone action for [region]. */
    data class Zone(val region: ViewerNavigation.NavigationRegion) : TapDecision

    /** A double tap: toggle zoom at the tap point. Never a zone. */
    data class ToggleZoom(val x: Float, val y: Float) : TapDecision

    /** A long tap (donor: readWithLongTap gates the page menu). */
    data class LongTap(val x: Float, val y: Float) : TapDecision

    /** The gesture is not the reader's (zoom disabled etc.). */
    data object Ignored : TapDecision
}

/**
 * The one tap arbiter for the transplanted reader, paged and
 * webtoon alike. [navigator] supplies the zone for a confirmed
 * single tap; whether a double tap zooms is the mode's config
 * (paged: PagerConfig.doubleTapZoom; webtoon:
 * WebtoonConfig.doubleTapZoom).
 */
class KomikkuTapArbiter(
    private val navigator: () -> ViewerNavigation,
    private val doubleTapZoomEnabled: () -> Boolean,
    private val longTapEnabled: () -> Boolean
) {
    /** A confirmed single tap (the detector's onSingleTapConfirmed). */
    fun onSingleTapConfirmed(x: Float, y: Float): TapDecision =
        TapDecision.Zone(navigator().getAction(KPointF(x, y)))

    /** A double tap: zoom, and ONLY zoom — no zone is ever read. */
    fun onDoubleTap(x: Float, y: Float): TapDecision =
        if (doubleTapZoomEnabled()) TapDecision.ToggleZoom(x, y)
        else TapDecision.Ignored

    fun onLongTap(x: Float, y: Float): TapDecision =
        if (longTapEnabled()) TapDecision.LongTap(x, y) else TapDecision.Ignored
}

/**
 * The zoom toggle state machine (donor ReaderPageImageView /
 * WebtoonSubsamplingImageView double-tap behaviour, with §6.3's
 * re-decode rule): fit → double tap → zoomed at the tap point →
 * double tap again → fit. While zoomed at or past
 * [SlicePlan152.ZOOM_REDECODE_TRIGGER] the visible region must be
 * re-decoded sharper — [zoomDecodeWidthPx] is that target: up to
 * 2× the 1× decode width, bounded by the source's native width,
 * with the §6.3 MINIMAL carve-out (bitmap magnification when the
 * region cannot be held inside the MINIMAL resident ceiling).
 */
class KomikkuZoomState(
    val doubleTapTargetScale: Float = DEFAULT_TARGET_SCALE
) {
    var scale: Float = 1f
        private set

    val isZoomed: Boolean get() = scale > 1f

    /** One double tap. Returns the new scale. */
    fun onDoubleTap(): Float {
        scale = if (isZoomed) 1f else doubleTapTargetScale
        return scale
    }

    fun onPinch(newScale: Float, disableZoomOut: Boolean, disableZoomIn: Boolean) {
        var clamped = newScale.coerceAtMost(MAX_SCALE)
        if (disableZoomOut) clamped = clamped.coerceAtLeast(1f)
        if (disableZoomIn) clamped = clamped.coerceAtMost(1f)
        scale = clamped.coerceAtLeast(MIN_SCALE)
    }

    fun reset() {
        scale = 1f
    }

    /** Whether the current zoom must re-decode (§6.3 trigger). */
    val needsRedeDecode: Boolean
        get() = scale >= SlicePlan152.ZOOM_REDECODE_TRIGGER

    /** §6.3: the re-decoded region's target decode width. */
    fun zoomDecodeWidthPx(
        oneXWidthPx: Int,
        sourceNativeWidthPx: Int,
        minimalTier: Boolean,
        regionHeightPx: Int
    ): Int {
        if (!needsRedeDecode) return oneXWidthPx
        if (minimalTier && SlicePlan152.minimalZoomFallsBack(oneXWidthPx * 2, regionHeightPx)) {
            return oneXWidthPx // MINIMAL carve-out: bitmap magnification
        }
        return SlicePlan152.zoomTargetWidthPx(oneXWidthPx, sourceNativeWidthPx)
    }

    companion object {
        const val DEFAULT_TARGET_SCALE = 2f
        const val MAX_SCALE = 5f
        const val MIN_SCALE = 0.5f
    }
}
