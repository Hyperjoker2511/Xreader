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
 * v1.5.7 (Komikku full): adapted from Komikku
 * (eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView) —
 * the donor's one-view-per-page tiling view, hosted by the
 * pager and webtoon holders. The SSIV configuration is the
 * donor's verbatim (max tile size = texture limit, minimum tile
 * DPI 180, ZOOM_FOCUS_CENTER double-tap, PAN_LIMIT_INSIDE,
 * minimum DPI 1, crop borders inside the view, max zoom =
 * fit × 5, double-tap zoom = fit × 2, zoom-start position,
 * landscape zoom). Changes from the donor (§4(b)):
 *  - Input is Xreader's page stream provider (() -> InputStream)
 *    plus an optional Coil model for the animated fallback,
 *    instead of okio BufferedSource; image-type sniffing and the
 *    hardware-bitmap decision read the stream's own bytes
 *    (bounds decode), not ImageUtil's Coil/okio helpers.
 *  - The webtoon long-strip path always decodes through SSIV
 *    from the raw stream (the donor's
 *    alwaysDecodeLongStripWithSSIV = ON behaviour): the donor's
 *    default Coil-bitmap-into-SSIV strip path is not reproduced,
 *    because its decode is sized by the view and the raw-stream
 *    path is strictly sharper. Named deviation, in the owner's
 *    favour (S-01).
 *  - Animated pages fall back to an AppCompatImageView fed by
 *    Coil (the donor uses PhotoView in the pager); pinch zoom on
 *    animated pager pages is therefore the frame's, not
 *    PhotoView's. Named deviation.
 *  - Split-half / rotate-to-fit transforms (the donor's
 *    PagerPageHolder.process seam: ImageUtil.splitInHalf /
 *    rotateImage over the byte source) are applied by decoding
 *    the affected (wide) page only and handing SSIV the
 *    resulting bitmap — same seam, same semantics, bitmap
 *    instead of a re-encoded byte source.
 */
package app.xreader.komikku.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.isVisible
import app.xreader.komikku.setting.ReaderPreferences.LandscapeZoomScaleType
import app.xreader.komikku.viewer.pager.ZoomStartPosition
import app.xreader.komikku.viewer.webtoon.WebtoonSubsamplingImageView
import coil.imageLoader
import coil.request.ImageRequest
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The donor's page view: one tiling view per page. */
open class ReaderPageImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var pageView: View? = null
    private var config: Config? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var imageJob: kotlinx.coroutines.Job? = null

    var isWebtoon: Boolean = false

    var onImageLoaded: (() -> Unit)? = null
    var onImageLoadError: ((Throwable?) -> Unit)? = null
    var onScaleChanged: ((Float) -> Unit)? = null

    /**
     * Fires when the page is tapped, carrying the tap point
     * as fractions of the tapped view's size (0f..1f) — tap
     * zones are decided from the point, so a bare `() -> Unit`
     * could never drive them. Assigned by the holder
     * (DonorReaderView's PageViewHolder).
     *
     * v1.5.12 (M1, Lane 1 — convicted on device): pre-v1.5.12
     * this property was assigned NOWHERE in the tree, so the
     * page image's taps — which the SubsamplingScaleImageView
     * fork CONSUMES and reports only through its click
     * listener — fired into the void, and the holder
     * container's own tap-zone detector sat behind the
     * consuming child and never received the event: tap-turn
     * was dead by construction in the paged modes. The
     * fractions come from the last ACTION_DOWN the inner view
     * saw (tracked non-consumingly — the page view's own
     * touch handling is untouched); a click with no tracked
     * touch (accessibility) reports the centre, the MENU
     * zone.
     */
    var onViewClicked: ((xFraction: Float, yFraction: Float) -> Unit)? = null

    private var lastDownX: Float = -1f
    private var lastDownY: Float = -1f

    /** Invoke [onViewClicked] with the tracked tap point as
     * fractions of [tapped] (the inner image view). */
    private fun fireViewClicked(tapped: View) {
        val callback = onViewClicked ?: return
        val fx = if (tapped.width > 0 && lastDownX >= 0f) {
            (lastDownX / tapped.width).coerceIn(0f, 1f)
        } else {
            0.5f
        }
        val fy = if (tapped.height > 0 && lastDownY >= 0f) {
            (lastDownY / tapped.height).coerceIn(0f, 1f)
        } else {
            0.5f
        }
        callback(fx, fy)
    }

    /** A touch listener that only REMEMBERS the down point
     * (for [fireViewClicked]) and never consumes an event. */
    private val downPointTracker = OnTouchListener { _, event ->
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            lastDownX = event.x
            lastDownY = event.y
        }
        false
    }

    /** The image's pixel size once known (bounds or SSIV ready). */
    var imageWidth: Int = 0
        private set
    var imageHeight: Int = 0
        private set

    val isSsivReady: Boolean
        get() = (pageView as? SubsamplingScaleImageView)?.isReady == true

    val currentScale: Float
        get() = (pageView as? SubsamplingScaleImageView)?.scale ?: 1f

    fun onPageSelected(forward: Boolean) {
        val view = pageView as? SubsamplingScaleImageView ?: return
        if (view.isReady) {
            landscapeZoom(view, forward)
        }
    }

    private fun landscapeZoom(view: SubsamplingScaleImageView, forward: Boolean) {
        val config = config ?: return
        if (!config.landscapeZoom) return
        if (config.minimumScaleType !=
            SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
        ) {
            return
        }
        if (view.sWidth <= view.sHeight) return
        if (view.scale != view.minScale) return
        view.handler?.postDelayed(
            {
                if (!view.isReady) return@postDelayed
                val point = when (config.zoomStartPosition) {
                    ZoomStartPosition.LEFT ->
                        if (forward) PointF(0f, 0f) else PointF(view.sWidth.toFloat(), 0f)
                    ZoomStartPosition.RIGHT ->
                        if (forward) PointF(view.sWidth.toFloat(), 0f) else PointF(0f, 0f)
                    ZoomStartPosition.CENTER -> view.center
                } ?: return@postDelayed
                val targetScale = when (config.landscapeZoomScaleType) {
                    LandscapeZoomScaleType.DOUBLE -> view.scale * 2
                    else -> view.height.toFloat() / view.sHeight.toFloat()
                }
                (view.animateScaleAndCenter(targetScale, point) ?: return@postDelayed)
                    .withDuration(500)
                    .withEasing(SubsamplingScaleImageView.EASE_IN_OUT_QUAD)
                    .withInterruptible(true)
                    .start()
            },
            500
        )
    }

    /**
     * Shows [stream] (re-openable through [openStream]) with
     * [config]. [coilModel] (a File or URL string) feeds the
     * animated fallback; [half] >= 0 shows only that half of a
     * wide page (dual-page split); [rotateToFit] rotates a wide
     * page 90° (dual-page rotate).
     */
    fun setImage(
        openStream: () -> InputStream,
        coilModel: Any?,
        config: Config,
        half: Int = -1,
        rotateToFit: Boolean = false,
        rotateInvert: Boolean = false,
        referer: String? = null
    ) {
        this.config = config
        imageJob?.cancel()
        imageJob = scope.launch {
            val prepared = withContext(Dispatchers.IO) {
                runCatching { prepare(openStream, half, rotateToFit, rotateInvert) }
                    .getOrNull()
            }
            if (prepared == null) {
                onImageLoadError?.invoke(
                    IllegalStateException("the page image couldn't be read")
                )
                return@launch
            }
            imageWidth = prepared.width
            imageHeight = prepared.height
            when (prepared) {
                is Prepared.Animated -> {
                    prepareAnimatedImageView()
                    setAnimatedImage(coilModel, referer)
                }
                is Prepared.Tiled -> {
                    prepareNonAnimatedImageView()
                    setNonAnimatedImage(openStream, null, config)
                }
                is Prepared.Transformed -> {
                    prepareNonAnimatedImageView()
                    setNonAnimatedImage(openStream, prepared.bitmap, config)
                }
            }
        }
    }

    private sealed interface Prepared {
        val width: Int
        val height: Int

        data class Animated(
            override val width: Int,
            override val height: Int
        ) : Prepared

        data class Tiled(
            override val width: Int,
            override val height: Int
        ) : Prepared

        data class Transformed(
            val bitmap: Bitmap,
            override val width: Int = bitmap.width,
            override val height: Int = bitmap.height
        ) : Prepared
    }

    /** Reads the stream's header (type + bounds) and applies
     * any byte-level transform the config demands. Runs on IO. */
    private fun prepare(
        openStream: () -> InputStream,
        half: Int,
        rotateToFit: Boolean,
        rotateInvert: Boolean
    ): Prepared {
        val header = openStream().use { stream ->
            val bytes = ByteArray(64 * 1024)
            var read = 0
            while (read < bytes.size) {
                val n = stream.read(bytes, read, bytes.size - read)
                if (n < 0) break
                read += n
            }
            bytes.copyOf(read)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(header, 0, header.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (isAnimated(header)) {
            return Prepared.Animated(width, height)
        }
        val isWide = width > height && width > 0
        if (half >= 0 && isWide) {
            val bitmap = openStream().use { stream ->
                val full = BitmapFactory.decodeStream(stream)
                    ?: return@use null
                val halfWidth = full.width / 2
                val x = if (half == 0) 0 else full.width - halfWidth
                Bitmap.createBitmap(full, x, 0, halfWidth, full.height)
            } ?: throw IllegalStateException("split decode failed")
            return Prepared.Transformed(bitmap)
        }
        if (rotateToFit && isWide) {
            val bitmap = openStream().use { stream ->
                val full = BitmapFactory.decodeStream(stream)
                    ?: return@use null
                val matrix = Matrix().apply {
                    postRotate(if (rotateInvert) -90f else 90f)
                }
                Bitmap.createBitmap(full, 0, 0, full.width, full.height, matrix, true)
            } ?: throw IllegalStateException("rotate decode failed")
            return Prepared.Transformed(bitmap)
        }
        return Prepared.Tiled(width, height)
    }

    /** GIF / animated WebP / APNG sniff over the header bytes
     * (the donor's ImageUtil.isAnimatedAndSupported, formats the
     * platform Coil decoders animate). */
    private fun isAnimated(header: ByteArray): Boolean {
        if (header.size < 12) return false
        // GIF87a / GIF89a
        if (header[0] == 'G'.code.toByte() && header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte()
        ) {
            return true
        }
        // WEBP: RIFF....WEBP + ANIM chunk marker in the header.
        if (header[0] == 'R'.code.toByte() && header[8] == 'W'.code.toByte() &&
            header[9] == 'E'.code.toByte() && header[10] == 'B'.code.toByte()
        ) {
            val text = String(header, Charsets.ISO_8859_1)
            return text.contains("ANIM") || text.contains("ANMF")
        }
        // PNG: APNG acTL chunk before IDAT.
        if (header[0] == 0x89.toByte() && header[1] == 'P'.code.toByte()) {
            val text = String(header, Charsets.ISO_8859_1)
            val actl = text.indexOf("acTL")
            val idat = text.indexOf("IDAT")
            return actl >= 0 && (idat < 0 || actl < idat)
        }
        return false
    }

    fun recycle() {
        imageJob?.cancel()
        (pageView as? SubsamplingScaleImageView)?.recycle()
        pageView?.isVisible = false
    }

    fun canPanLeft(): Boolean = canPan { it.left }
    fun canPanRight(): Boolean = canPan { it.right }
    fun canPanUp(): Boolean = canPan { it.top }
    fun canPanDown(): Boolean = canPan { it.bottom }

    private fun canPan(fn: (RectF) -> Float): Boolean {
        val view = pageView as? SubsamplingScaleImageView ?: return false
        if (!view.isReady) return false
        val rect = RectF()
        view.getPanRemaining(rect)
        return fn(rect) > 1
    }

    fun panLeft() = pan { center, view -> center.also { it.x -= view.width / view.scale } }
    fun panRight() = pan { center, view -> center.also { it.x += view.width / view.scale } }
    fun panDown() = pan { center, view -> center.also { it.y += view.height / view.scale } }
    fun panUp() = pan { center, view -> center.also { it.y -= view.height / view.scale } }

    private fun pan(fn: (PointF, SubsamplingScaleImageView) -> PointF) {
        val view = pageView as? SubsamplingScaleImageView ?: return
        val center = view.center ?: return
        val target = fn(center, view)
        (view.animateCenter(target) ?: return)
            .withEasing(SubsamplingScaleImageView.EASE_OUT_QUAD)
            .withDuration(250)
            .withInterruptible(true)
            .start()
    }

    /** Double-tap zoom toggle for the animated fallback and the
     * webtoon frame (SSIV pages zoom themselves). */
    fun resetZoom() {
        (pageView as? SubsamplingScaleImageView)?.resetScaleAndCenter()
    }

    private fun prepareNonAnimatedImageView() {
        if (pageView is SubsamplingScaleImageView) return
        pageView?.let { removeView(it) }
        pageView = if (isWebtoon) {
            WebtoonSubsamplingImageView(context)
        } else {
            SubsamplingScaleImageView(context)
        }.apply {
            // Donor ReaderPageImageView SSIV configuration, verbatim.
            setMaxTileSize(HARDWARE_BITMAP_THRESHOLD)
            setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
            setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
            setMinimumTileDpi(180)
            setOnStateChangedListener(
                object : SubsamplingScaleImageView.OnStateChangedListener {
                    override fun onScaleChanged(newScale: Float, origin: Int) {
                        onScaleChanged?.invoke(newScale)
                    }

                    override fun onCenterChanged(newCenter: PointF?, origin: Int) {
                        // Not used (donor).
                    }
                }
            )
            // v1.5.12 (M1): the tap point tracker rides the
            // SSIV non-consumingly; the click reports the
            // tracked point's fractions to the holder's
            // tap-zone handling (see onViewClicked).
            setOnTouchListener(downPointTracker)
            setOnClickListener { fireViewClicked(this) }
        }
        addView(
            pageView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    private fun SubsamplingScaleImageView.setupZoom(config: Config?) {
        // Donor: 5x zoom.
        maxScale = scale * MAX_ZOOM_SCALE
        if (config?.disableZoomIn == true) {
            isZoomEnabled = false
        } else if (config?.doubleTapZoom == false) {
            setDoubleTapZoomScale(scale)
        } else {
            setDoubleTapZoomScale(scale * 2)
        }
        when (config?.zoomStartPosition) {
            ZoomStartPosition.LEFT -> setScaleAndCenter(scale, PointF(0f, 0f))
            ZoomStartPosition.RIGHT ->
                setScaleAndCenter(scale, PointF(sWidth.toFloat(), 0f))
            ZoomStartPosition.CENTER -> center?.let { setScaleAndCenter(scale, it) }
            null -> {}
        }
    }

    private fun setNonAnimatedImage(
        openStream: () -> InputStream,
        bitmap: Bitmap?,
        config: Config
    ) {
        val view = pageView as? SubsamplingScaleImageView ?: return
        view.apply {
            setDoubleTapZoomDuration(config.zoomDuration)
            setMinimumScaleType(config.minimumScaleType)
            setMinimumDpi(1) // Just so that very small image will be fit for initial load
            setCropBorders(config.cropBorders)
            setOnImageEventListener(
                object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                    override fun onReady() {
                        setupZoom(config)
                        if (isVisibleOnScreen()) landscapeZoom(view, true)
                        onImageLoaded?.invoke()
                    }

                    override fun onImageLoadError(e: Exception) {
                        onImageLoadError?.invoke(e)
                    }
                }
            )
            if (bitmap != null) {
                setImage(ImageSource.bitmap(bitmap))
            } else {
                // Hardware bitmaps when the image fits the tile
                // budget (donor ImageUtil.canUseHardwareBitmap).
                setHardwareConfig(
                    maxOf(imageWidth, imageHeight) <= HARDWARE_BITMAP_THRESHOLD
                )
                setImage(ImageSource.inputStream(openStream()))
            }
            isVisible = true
        }
    }

    private fun prepareAnimatedImageView() {
        if (pageView is ImageView) return
        pageView?.let { removeView(it) }
        // Donor: AppCompatImageView / PhotoView. Xreader carries
        // no appcompat widget dependency on this surface, so a
        // plain ImageView hosts the animated drawable (named
        // deviation in the file header).
        pageView = ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            // v1.5.12 (M1): same tap wiring as the SSIV road.
            setOnTouchListener(downPointTracker)
            setOnClickListener { fireViewClicked(this) }
        }
        addView(
            pageView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    private fun setAnimatedImage(coilModel: Any?, referer: String?) {
        val view = pageView as? ImageView ?: return
        if (coilModel == null) {
            onImageLoadError?.invoke(
                IllegalStateException("animated page has no image model")
            )
            return
        }
        val request = ImageRequest.Builder(context)
            .data(coilModel)
            .apply { referer?.let { addHeader("Referer", it) } }
            .target(
                onSuccess = { drawable ->
                    view.setImageDrawable(drawable)
                    (drawable as? android.graphics.drawable.Animatable)?.start()
                    view.isVisible = true
                    onImageLoaded?.invoke()
                },
                onError = {
                    onImageLoadError?.invoke(
                        IllegalStateException("animated page couldn't be loaded")
                    )
                }
            )
            .crossfade(false)
            .allowHardware(false) // Disable hardware bitmaps for GIFs (donor KMK)
            .build()
        context.imageLoader.enqueue(request)
    }

    private fun View.isVisibleOnScreen(): Boolean {
        if (!isShown) return false
        val rect = Rect()
        return getGlobalVisibleRect(rect)
    }

    data class Config(
        val zoomDuration: Int,
        val minimumScaleType: Int =
            SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE,
        val cropBorders: Boolean = false,
        val zoomStartPosition: ZoomStartPosition = ZoomStartPosition.CENTER,
        val landscapeZoom: Boolean = false,
        // KMK -->
        val disableZoomIn: Boolean = false,
        val doubleTapZoom: Boolean = true,
        val landscapeZoomScaleType: LandscapeZoomScaleType =
            LandscapeZoomScaleType.FIT
        // KMK <--
    )

    companion object {
        private const val MAX_ZOOM_SCALE = 5f

        /** GLUtil.SAFE_TEXTURE_LIMIT — the donor's default
         * hardware bitmap threshold. */
        const val HARDWARE_BITMAP_THRESHOLD = 2048
    }
}
