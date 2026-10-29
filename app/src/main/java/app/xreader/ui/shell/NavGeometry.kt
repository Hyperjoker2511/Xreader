package app.xreader.ui.shell

/**
 * Pure geometry for the navigation selection pill — phone bottom bar
 * (horizontal) and tablet rail (vertical).
 *
 * Every output is clamped so the pill's rect stays inside the bar's own
 * measured bounds for ANY bar size, item count, index, inset, or
 * in-flight animated position (springs can overshoot their target).
 * This is the invariant ReTranslate's v1.1 bar violated by sizing the
 * pill from raw layout constraints (it measured a full screen tall);
 * the v1.1.1 fix — pill geometry ONLY from a pure clamped function
 * inside a fixed-size bar — is Xreader's starting point, and the unit
 * tests pin it.
 *
 * Plain ints/floats only — no Compose types — so the invariant is
 * unit-testable on the JVM (see NavGeometryTest).
 */
object NavGeometry {

    // ---- Horizontal (phone bottom bar) --------------------------------

    /** Width of one tab slot in px; 0 until the bar has been measured. */
    fun slotWidthPx(barWidthPx: Int, itemCount: Int): Int =
        if (barWidthPx <= 0 || itemCount <= 0) 0 else barWidthPx / itemCount

    /** Pill width: one slot minus the horizontal insets, clamped to the bar. */
    fun pillWidthPx(barWidthPx: Int, itemCount: Int, horizontalInsetPx: Int): Int {
        if (barWidthPx <= 0) return 0
        val slot = slotWidthPx(barWidthPx, itemCount)
        return (slot - 2 * horizontalInsetPx).coerceIn(0, barWidthPx)
    }

    /** Pill height: the bar height minus the vertical insets, clamped to the bar. */
    fun pillHeightPx(barHeightPx: Int, verticalInsetPx: Int): Int {
        if (barHeightPx <= 0) return 0
        return (barHeightPx - 2 * verticalInsetPx).coerceIn(0, barHeightPx)
    }

    /** The pill's settled left edge (px) for [index], clamped so the whole
     * pill stays inside the bar even for out-of-range indices. */
    fun pillTargetXPx(barWidthPx: Int, itemCount: Int, index: Int, horizontalInsetPx: Int): Int {
        if (barWidthPx <= 0 || itemCount <= 0) return 0
        val width = pillWidthPx(barWidthPx, itemCount, horizontalInsetPx)
        val slot = slotWidthPx(barWidthPx, itemCount)
        val raw = slot * index.coerceIn(0, itemCount - 1) + horizontalInsetPx
        return raw.coerceIn(0, (barWidthPx - width).coerceAtLeast(0))
    }

    /** The pill's top edge (px): the vertical inset, clamped inside the bar. */
    fun pillTargetYPx(barHeightPx: Int, verticalInsetPx: Int): Int =
        verticalInsetPx.coerceIn(0, barHeightPx.coerceAtLeast(0))

    /** Clamp an animated (possibly spring-overshooting) x so the pill
     * stays fully inside the bar on every frame of its travel. */
    fun clampPillXPx(xPx: Float, barWidthPx: Int, pillWidthPx: Int): Float =
        xPx.coerceIn(0f, (barWidthPx - pillWidthPx).coerceAtLeast(0).toFloat())

    // ---- Vertical (tablet rail) ----------------------------------------

    /** Height of one rail slot in px; 0 until the rail has been measured. */
    fun slotHeightPx(railHeightPx: Int, itemCount: Int): Int =
        if (railHeightPx <= 0 || itemCount <= 0) 0 else railHeightPx / itemCount

    /** Rail pill height: one slot minus the vertical insets, clamped. */
    fun pillHeightRailPx(railHeightPx: Int, itemCount: Int, verticalInsetPx: Int): Int {
        if (railHeightPx <= 0) return 0
        val slot = slotHeightPx(railHeightPx, itemCount)
        return (slot - 2 * verticalInsetPx).coerceIn(0, railHeightPx)
    }

    /** Rail pill width: the rail width minus the horizontal insets, clamped. */
    fun pillWidthRailPx(railWidthPx: Int, horizontalInsetPx: Int): Int {
        if (railWidthPx <= 0) return 0
        return (railWidthPx - 2 * horizontalInsetPx).coerceIn(0, railWidthPx)
    }

    /** The rail pill's settled top edge (px) for [index], clamped inside. */
    fun pillTargetYRailPx(railHeightPx: Int, itemCount: Int, index: Int, verticalInsetPx: Int): Int {
        if (railHeightPx <= 0 || itemCount <= 0) return 0
        val height = pillHeightRailPx(railHeightPx, itemCount, verticalInsetPx)
        val slot = slotHeightPx(railHeightPx, itemCount)
        val raw = slot * index.coerceIn(0, itemCount - 1) + verticalInsetPx
        return raw.coerceIn(0, (railHeightPx - height).coerceAtLeast(0))
    }

    /** The rail pill's left edge (px): the horizontal inset, clamped inside. */
    fun pillTargetXRailPx(railWidthPx: Int, horizontalInsetPx: Int): Int =
        horizontalInsetPx.coerceIn(0, railWidthPx.coerceAtLeast(0))

    /** Clamp an animated y so the rail pill stays fully inside the rail. */
    fun clampPillYPx(yPx: Float, railHeightPx: Int, pillHeightPx: Int): Float =
        yPx.coerceIn(0f, (railHeightPx - pillHeightPx).coerceAtLeast(0).toFloat())
}
