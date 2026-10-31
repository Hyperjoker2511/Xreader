package app.xreader.ui.reader

import android.content.Context
import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import app.xreader.core.motion.SpringMotionEngine
import app.xreader.core.motion.toSpec
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Dimension
import coil.size.Size
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * An immutable zoom transform for one image: a [scale] applied
 * about the container's centre plus a [offsetX]/[offsetY]
 * translation in container pixels — exactly the values a
 * `graphicsLayer` needs. All zoom arithmetic lives in [ZoomMath]
 * as pure functions over this type (no Compose geometry), so the
 * maths is unit-testable on the JVM.
 */
data class ZoomState(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f
) {
    /** True while magnified past fit — the state in which drags
     * pan the image (and the host list must not scroll). */
    val isZoomedIn: Boolean get() = scale > 1f + ZoomMath.ZOOMED_IN_EPSILON
}

/**
 * Pure zoom mathematics for [ZoomableImage] (v1.2.0, WS2 — manga
 * zoom Option A, following Mihon/Legado: pinch 0.5×–3×, double-tap
 * toggles 1× ↔ 2×).
 *
 * The transform model matches `graphicsLayer` with its default
 * centre transform origin. A content point `p` (in unscaled image
 * coordinates, which coincide with container coordinates at fit)
 * is displayed at:
 *
 *     display(p) = centre + (p − centre) · scale + offset
 *
 * where `centre` is the container centre. Every gesture update
 * below is solved from that equation so the content point under
 * the user's finger(s) stays under them:
 *
 *     offset′ = centroid + pan − centre − (centroid − centre − offset) · (scale′/scale)
 */
object ZoomMath {

    /** Pinch ceiling — the Mihon/Legado-consistent 3×. */
    const val MAX_SCALE = 3f

    /** Pinch floor when zooming out is allowed ("Disable zoom
     * out" off): the image may shrink to half its fit size. */
    const val MIN_SCALE_WITH_ZOOM_OUT = 0.5f

    /** The double-tap stop: fit ↔ this scale. */
    const val DOUBLE_TAP_SCALE = 2f

    /** Scale must exceed 1× by more than this to count as zoomed
     * in (float dust from gesture arithmetic is not zoom). */
    const val ZOOMED_IN_EPSILON = 0.001f

    /** The smallest scale a gesture may reach. */
    fun minScale(disableZoomOut: Boolean): Float =
        if (disableZoomOut) 1f else MIN_SCALE_WITH_ZOOM_OUT

    fun clampScale(scale: Float, disableZoomOut: Boolean): Float =
        if (scale.isNaN()) 1f
        else scale.coerceIn(minScale(disableZoomOut), MAX_SCALE)

    /**
     * Brings [state] inside the legal envelope: scale within
     * [minScale]..[MAX_SCALE], and — whenever the scale is at or
     * below fit — offset exactly zero (a fit-or-smaller image is
     * centred and cannot be panned). Past fit, the offset is
     * bounded so the image's edges never move inside the
     * container's edges: |offset| ≤ (scale − 1) · size / 2 per
     * axis. An unknown container size (0) leaves a zoomed offset
     * unbounded for that frame rather than snapping it to zero.
     */
    fun clamp(
        state: ZoomState,
        containerWidthPx: Int,
        containerHeightPx: Int,
        disableZoomOut: Boolean
    ): ZoomState {
        val scale = clampScale(state.scale, disableZoomOut)
        if (scale <= 1f) return ZoomState(scale = scale, offsetX = 0f, offsetY = 0f)
        if (containerWidthPx <= 0 || containerHeightPx <= 0) {
            return ZoomState(scale = scale, offsetX = state.offsetX, offsetY = state.offsetY)
        }
        val maxX = (scale - 1f) * containerWidthPx / 2f
        val maxY = (scale - 1f) * containerHeightPx / 2f
        return ZoomState(
            scale = scale,
            offsetX = state.offsetX.coerceIn(-maxX, maxX),
            offsetY = state.offsetY.coerceIn(-maxY, maxY)
        )
    }

    /**
     * One pinch/pan step: the centroid of the gesture is at
     * ([centroidX], [centroidY]) in container pixels, it moved by
     * ([panX], [panY]) since the previous step, and the pointer
     * span changed by the factor [zoomChange]. The content point
     * under the centroid stays under the centroid (shifted by the
     * pan) — see the equation in this object's KDoc.
     */
    fun onTransform(
        state: ZoomState,
        centroidX: Float,
        centroidY: Float,
        panX: Float,
        panY: Float,
        zoomChange: Float,
        containerWidthPx: Int,
        containerHeightPx: Int,
        disableZoomOut: Boolean
    ): ZoomState {
        if (containerWidthPx <= 0 || containerHeightPx <= 0) return state
        if (zoomChange.isNaN() || zoomChange <= 0f) return state
        if (!panX.isFinite() || !panY.isFinite()) return state
        if (!centroidX.isFinite() || !centroidY.isFinite()) return state
        val newScale = clampScale(state.scale * zoomChange, disableZoomOut)
        val ratio = if (state.scale != 0f) newScale / state.scale else 1f
        val centreX = containerWidthPx / 2f
        val centreY = containerHeightPx / 2f
        val newOffsetX =
            centroidX + panX - centreX - (centroidX - centreX - state.offsetX) * ratio
        val newOffsetY =
            centroidY + panY - centreY - (centroidY - centreY - state.offsetY) * ratio
        return clamp(
            ZoomState(scale = newScale, offsetX = newOffsetX, offsetY = newOffsetY),
            containerWidthPx, containerHeightPx, disableZoomOut
        )
    }

    /**
     * The double-tap toggle: fit → [DOUBLE_TAP_SCALE] centred on
     * the tapped point (the content point under the finger stays
     * under the finger), zoomed → back to fit with zero offset.
     * A no-op when [doubleTapEnabled] is false (pinch still
     * works) or the container size is not known yet.
     */
    fun onDoubleTap(
        state: ZoomState,
        tapX: Float,
        tapY: Float,
        containerWidthPx: Int,
        containerHeightPx: Int,
        doubleTapEnabled: Boolean,
        disableZoomOut: Boolean
    ): ZoomState {
        if (!doubleTapEnabled) return state
        if (containerWidthPx <= 0 || containerHeightPx <= 0) return state
        if (!tapX.isFinite() || !tapY.isFinite()) return state
        val targetScale = if (state.isZoomedIn) 1f else DOUBLE_TAP_SCALE
        if (targetScale == state.scale) return state
        val ratio = targetScale / state.scale
        val centreX = containerWidthPx / 2f
        val centreY = containerHeightPx / 2f
        val newOffsetX = tapX - centreX - (tapX - centreX - state.offsetX) * ratio
        val newOffsetY = tapY - centreY - (tapY - centreY - state.offsetY) * ratio
        return clamp(
            ZoomState(scale = targetScale, offsetX = newOffsetX, offsetY = newOffsetY),
            containerWidthPx, containerHeightPx, disableZoomOut
        )
    }

    /**
     * The content (unscaled-image) coordinates of the point shown
     * at container position ([displayX], [displayY]) under
     * [state] — the inverse of the display equation in this
     * object's KDoc. Gesture code never needs it; it exists so
     * tests can pin the finger-invariance property directly.
     */
    fun contentPoint(
        state: ZoomState,
        displayX: Float,
        displayY: Float,
        containerWidthPx: Int,
        containerHeightPx: Int
    ): Pair<Float, Float> {
        val centreX = containerWidthPx / 2f
        val centreY = containerHeightPx / 2f
        return Pair(
            centreX + (displayX - state.offsetX - centreX) / state.scale,
            centreY + (displayY - state.offsetY - centreY) / state.scale
        )
    }
}

/**
 * Coil decode sizing for reader images (v1.2.0, WS2). Reader
 * images used to decode at display width, so a graphics-layer
 * zoom past ~1.5× magnified a fit-width bitmap and went soft.
 * The sized request asks the decoder for at most
 * [MAX_WIDTH_PX] wide (height unconstrained, aspect preserved):
 * sources narrower than the cap decode at their real size (Coil
 * never upscales), wider sources downsample to the cap — a 2–3×
 * zoom stays as sharp as the source allows without ever
 * decoding a very tall strip page at full size on the 2 GB
 * tablet tier. The app-wide loader budgets live in
 * `di/AppImageLoader.kt`; this owns only the per-request size.
 */
object ReaderImageDecode {

    const val MAX_WIDTH_PX = 2048

    /** A sized request for [model]; an already-built
     * [ImageRequest] passes through untouched. */
    fun request(context: Context, model: Any?): ImageRequest =
        if (model is ImageRequest) {
            model
        } else {
            ImageRequest.Builder(context)
                .data(model)
                .size(Size(Dimension.Pixels(MAX_WIDTH_PX), Dimension.Undefined))
                .build()
        }
}

/**
 * A reader image with per-image pinch / pan / double-tap zoom
 * (v1.2.0, WS2 — manga zoom Option A).
 *
 * Behaviour:
 *  - Pinch zooms between [ZoomMath.MIN_SCALE_WITH_ZOOM_OUT] (or
 *    1× when [disableZoomOut] is on) and [ZoomMath.MAX_SCALE],
 *    centred on the gesture centroid; one-finger drags pan while
 *    zoomed in. Double-tap toggles 1× ↔ 2× at the tapped point
 *    when [doubleTapToZoom] is on, animated on the motion
 *    engine's PageAssist spring; pinch/pan tracking itself is a
 *    direct 1:1 snap (gestures never lag behind the finger).
 *  - The transform is a `graphicsLayer` scale/translation on the
 *    image only — layout never changes, so the scroll readers'
 *    position reporting and restore (item index + fraction) are
 *    untouched by zooming.
 *  - State is remembered per composition: inside a LazyColumn
 *    item it is created and disposed with the item, so zoom
 *    resets as the image scrolls away — Mihon's per-page rule.
 *    Nothing is hoisted to the screen.
 *  - Scroll lock: while a pinch/pan gesture is engaged or the
 *    image is zoomed in, [onZoomActiveChanged] reports `true`
 *    and the host must stop scrolling (the scroll readers wire
 *    it to the LazyColumn's `userScrollEnabled`). It reports
 *    `false` again at fit, and once more on disposal so a host
 *    can never be left locked by a disposed item.
 *
 * Gesture arbitration is a hand-rolled Initial-pass observer in
 * the style of `ReaderTapToggle` rather than
 * `detectTransformGestures`, for one decisive reason:
 * `detectTransformGestures` consumes single-finger drags, and a
 * comic page fills the screen — consuming them at fit would trap
 * the reader (no drag could ever scroll the list). Here a
 * single-finger drag at fit is never consumed (the list scrolls
 * exactly as before zoom existed); the observer engages only on
 * multi-touch (pinch) or on a drag while already zoomed (pan),
 * consuming from that point on. Known trap honoured: gesture end
 * is detected via `changedToUp()` / `!pressed` —
 * `changedToUpOrCanceled`/`isCanceled` do not resolve in this
 * tree's Compose version.
 *
 * REUSE (Wave 3): `PagedReader`'s PageContent image branch is
 * the intended second call site — pass the same config flags
 * ([doubleTapToZoom], [disableZoomOut] from `ReaderConfig`),
 * `contentScale = ContentScale.Fit`, an `imageModifier` of
 * `Modifier.fillMaxSize()`, and wire [onZoomActiveChanged] to
 * suppress page-turn drags while an image reports active. The
 * component takes no reader-specific state, so both hosts get
 * identical zoom behaviour.
 *
 * @param model anything Coil accepts (the readers pass a local
 *   [java.io.File]); wrapped in the sized request from
 *   [ReaderImageDecode] unless it already is an [ImageRequest].
 * @param imageModifier sizing for the image inside the zoom
 *   container; the scroll readers' default fills the width and
 *   wraps the height (aspect preserved by [contentScale]).
 */
@Composable
fun ZoomableImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.FillWidth,
    imageModifier: Modifier = Modifier.fillMaxWidth(),
    doubleTapToZoom: Boolean = true,
    disableZoomOut: Boolean = false,
    onZoomActiveChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val request = remember(context, model) { ReaderImageDecode.request(context, model) }
    val animationScope = rememberCoroutineScope()

    val zoomState = remember { mutableStateOf(ZoomState()) }
    val gesturing = remember { mutableStateOf(false) }
    val containerSize = remember { mutableStateOf(IntSize.Zero) }
    val doubleTapEnabledState = rememberUpdatedState(doubleTapToZoom)
    val disableZoomOutState = rememberUpdatedState(disableZoomOut)
    val activeCallback = rememberUpdatedState(onZoomActiveChanged)

    val lockActive = gesturing.value || zoomState.value.isZoomedIn
    LaunchedEffect(lockActive) { activeCallback.value(lockActive) }
    DisposableEffect(Unit) {
        onDispose { activeCallback.value(false) }
    }

    Box(
        modifier
            .onSizeChanged { containerSize.value = it }
            .clipToBounds()
            .pointerInput(Unit) {
                var animationJob: Job? = null
                var hasLastTap = false
                var lastTapUptimeMs = 0L
                var lastTapPosition = Offset.Zero

                fun cancelAnimation() {
                    animationJob?.cancel()
                    animationJob = null
                }

                fun applyTransform(centroid: Offset, pan: Offset, zoomChange: Float) {
                    val size = containerSize.value
                    zoomState.value = ZoomMath.onTransform(
                        state = zoomState.value,
                        centroidX = centroid.x,
                        centroidY = centroid.y,
                        panX = pan.x,
                        panY = pan.y,
                        zoomChange = zoomChange,
                        containerWidthPx = size.width,
                        containerHeightPx = size.height,
                        disableZoomOut = disableZoomOutState.value
                    )
                }

                fun applyDoubleTap(tap: Offset) {
                    val size = containerSize.value
                    val current = zoomState.value
                    val target = ZoomMath.onDoubleTap(
                        state = current,
                        tapX = tap.x,
                        tapY = tap.y,
                        containerWidthPx = size.width,
                        containerHeightPx = size.height,
                        doubleTapEnabled = doubleTapEnabledState.value,
                        disableZoomOut = disableZoomOutState.value
                    )
                    if (target == current) return
                    cancelAnimation()
                    animationJob = animationScope.launch {
                        animate(
                            initialValue = 0f,
                            targetValue = 1f,
                            animationSpec = SpringMotionEngine.PageAssist.toSpec<Float>()
                        ) { fraction, _ ->
                            zoomState.value = ZoomState(
                                scale = current.scale +
                                    (target.scale - current.scale) * fraction,
                                offsetX = current.offsetX +
                                    (target.offsetX - current.offsetX) * fraction,
                                offsetY = current.offsetY +
                                    (target.offsetY - current.offsetY) * fraction
                            )
                        }
                        zoomState.value = target
                    }
                }

                awaitPointerEventScope {
                    while (true) {
                        val firstEvent = awaitPointerEvent(PointerEventPass.Initial)
                        val primaryDown = firstEvent.changes.firstOrNull { it.pressed }
                            ?: continue

                        // ── one gesture: first down → all up ──
                        val primaryId = primaryDown.id
                        val downPosition = primaryDown.position
                        val downUptimeMs = primaryDown.uptimeMillis
                        var maxDisplacement = 0f
                        var multiTouchSeen =
                            firstEvent.changes.count { it.pressed } > 1
                        var engaged = false
                        var pinchActive = false
                        var prevCentroid = Offset.Zero
                        var prevSpan = 0f
                        var prevSinglePosition = downPosition
                        var currentEvent = firstEvent
                        var gestureOver = false

                        while (!gestureOver) {
                            val pressed = currentEvent.changes.filter { it.pressed }
                            val primaryChange =
                                currentEvent.changes.firstOrNull { it.id == primaryId }
                            if (primaryChange != null) {
                                val displacement =
                                    (primaryChange.position - downPosition).getDistance()
                                if (displacement > maxDisplacement) {
                                    maxDisplacement = displacement
                                }
                            }

                            when {
                                pressed.size >= 2 -> {
                                    multiTouchSeen = true
                                    hasLastTap = false
                                    val centroid = pressed.fold(Offset.Zero) { acc, change ->
                                        acc + change.position
                                    } / pressed.size.toFloat()
                                    val span = (pressed[0].position - pressed[1].position)
                                        .getDistance()
                                    if (!pinchActive) {
                                        pinchActive = true
                                        if (!engaged) {
                                            engaged = true
                                            gesturing.value = true
                                            cancelAnimation()
                                        }
                                        prevCentroid = centroid
                                        prevSpan = span
                                    } else {
                                        val zoomChange =
                                            if (prevSpan > 0f) span / prevSpan else 1f
                                        applyTransform(
                                            centroid,
                                            centroid - prevCentroid,
                                            zoomChange
                                        )
                                        prevCentroid = centroid
                                        prevSpan = span
                                    }
                                    pressed.forEach { it.consume() }
                                }

                                pressed.size == 1 -> {
                                    val change = pressed[0]
                                    when {
                                        pinchActive -> {
                                            // One finger lifted mid-pinch:
                                            // re-anchor the pan on the
                                            // remaining finger — no jump.
                                            pinchActive = false
                                            prevSinglePosition = change.position
                                            change.consume()
                                        }

                                        engaged -> {
                                            val panDelta = change.position - prevSinglePosition
                                            prevSinglePosition = change.position
                                            applyTransform(change.position, panDelta, 1f)
                                            change.consume()
                                        }

                                        else -> {
                                            prevSinglePosition = change.position
                                            if (zoomState.value.isZoomedIn &&
                                                maxDisplacement > viewConfiguration.touchSlop
                                            ) {
                                                // A drag on a zoomed image
                                                // pans the image, never the
                                                // list. Apply the travel so
                                                // far so the pan starts
                                                // attached to the finger.
                                                engaged = true
                                                gesturing.value = true
                                                cancelAnimation()
                                                applyTransform(
                                                    change.position,
                                                    change.position - downPosition,
                                                    1f
                                                )
                                                change.consume()
                                            }
                                        }
                                    }
                                }
                            }

                            if (pressed.isEmpty()) {
                                // Gesture end: the tap verdict.
                                val upChange =
                                    currentEvent.changes.firstOrNull { it.changedToUp() }
                                if (upChange != null && !engaged && !multiTouchSeen &&
                                    upChange.uptimeMillis - downUptimeMs <
                                    viewConfiguration.longPressTimeoutMillis &&
                                    maxDisplacement <= viewConfiguration.touchSlop
                                ) {
                                    val isDoubleTap = hasLastTap &&
                                        upChange.uptimeMillis - lastTapUptimeMs <=
                                        viewConfiguration.doubleTapTimeoutMillis &&
                                        (upChange.position - lastTapPosition)
                                            .getDistance() <=
                                        viewConfiguration.touchSlop * 6f
                                    if (isDoubleTap) {
                                        hasLastTap = false
                                        if (doubleTapEnabledState.value) {
                                            upChange.consume()
                                            applyDoubleTap(upChange.position)
                                        }
                                    } else {
                                        hasLastTap = true
                                        lastTapUptimeMs = upChange.uptimeMillis
                                        lastTapPosition = upChange.position
                                    }
                                } else {
                                    hasLastTap = false
                                }
                                gesturing.value = false
                                gestureOver = true
                            } else {
                                currentEvent = awaitPointerEvent(PointerEventPass.Initial)
                            }
                        }
                    }
                }
            }
    ) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = imageModifier.graphicsLayer {
                val zoom = zoomState.value
                scaleX = zoom.scale
                scaleY = zoom.scale
                translationX = zoom.offsetX
                translationY = zoom.offsetY
            }
        )
    }
}
