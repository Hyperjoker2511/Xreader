package app.xreader.ui.reader

import app.xreader.data.prefs.PerfTier

/**
 * v1.5.2 (§6.2): sliced decode budgets, printed per tier. Whole
 * strips decode as native-resolution slices via
 * [TallStripPolicy.sliceBounds] — never scaled down to the 3,072 px
 * guard cap (Failure F2: an 800×12,800 strip became ~192 px wide).
 * The resident ceiling binds FIRST when it is lower than the
 * slice-count limit.
 */
object SlicePlan152 {

    fun decodeWidthPx(tier: PerfTier, screenWidthPx: Int): Int = when (tier) {
        PerfTier.MINIMAL -> screenWidthPx.coerceAtLeast(1)
        PerfTier.STANDARD, PerfTier.FULL -> ReaderImageDecode.MAX_WIDTH_PX
    }

    fun sliceHeightPx(tier: PerfTier): Int = when (tier) {
        PerfTier.MINIMAL -> 1_536
        PerfTier.STANDARD, PerfTier.FULL -> TallStripPolicy.MAX_HEIGHT_PX
    }

    fun maxResidentSlices(tier: PerfTier): Int = when (tier) {
        PerfTier.MINIMAL -> 3
        PerfTier.STANDARD -> 4
        PerfTier.FULL -> 5
    }

    fun residentCeilingBytes(tier: PerfTier): Long = when (tier) {
        PerfTier.MINIMAL -> 32L * 1024 * 1024
        PerfTier.STANDARD -> 64L * 1024 * 1024
        PerfTier.FULL -> 96L * 1024 * 1024
    }

    fun decodeConcurrency(tier: PerfTier): Int = when (tier) {
        PerfTier.MINIMAL -> 1
        PerfTier.STANDARD, PerfTier.FULL -> 2
    }

    fun sliceBytes(widthPx: Int, heightPx: Int): Long =
        widthPx.toLong() * heightPx.toLong() * 4L

    /** How many slices of [widthPx]×[sliceHeightPx] may be resident:
     * the count limit AND the byte ceiling, whichever binds first. */
    fun residentSlicesFor(tier: PerfTier, widthPx: Int): Int {
        val perSlice = sliceBytes(widthPx, sliceHeightPx(tier))
        if (perSlice <= 0) return maxResidentSlices(tier)
        val byBytes = (residentCeilingBytes(tier) / perSlice).toInt()
        return minOf(maxResidentSlices(tier), byBytes).coerceAtLeast(1)
    }

    /** §6.3 zoom: the re-decode trigger and target. */
    const val ZOOM_REDECODE_TRIGGER: Float = 1.5f

    fun zoomTargetWidthPx(oneXWidthPx: Int, sourceNativeWidthPx: Int): Int =
        minOf(oneXWidthPx * 2, sourceNativeWidthPx)

    /** MINIMAL carve-out (§6.3): if the re-decoded region cannot be
     * held within the MINIMAL ceiling, MINIMAL falls back to bitmap
     * magnification — designed, not discovered. */
    fun minimalZoomFallsBack(regionWidthPx: Int, regionHeightPx: Int): Boolean =
        sliceBytes(regionWidthPx, regionHeightPx) >
            residentCeilingBytes(PerfTier.MINIMAL)
}
