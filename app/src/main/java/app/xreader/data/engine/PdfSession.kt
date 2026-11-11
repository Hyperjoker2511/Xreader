package app.xreader.data.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.app.ActivityManager
import android.graphics.Matrix
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import app.xreader.data.prefs.PerfTier
import app.xreader.data.prefs.PerformancePolicy
import app.xreader.data.db.Book
import app.xreader.data.db.BookFormat
import app.xreader.data.parse.PdfOutlineReader
import app.xreader.data.parse.PdfStripperLoop
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer as PdfBoxRenderer
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.math.roundToInt

/**
 * v1.5.6 (§3.1 row 5): the PDF session — platform [PdfRenderer]
 * renders pages on demand; the already-shipped PdfBox probes the
 * SAME bytes (through a resolver InputStream for document URIs —
 * copying the file is forbidden by the no-copy rule, §3.4(d)) for
 * the outline and per-page text (TTS/search).
 *
 * Passwords (§3.1 row 6): an owner-password PDF (empty user
 * password) opens through PdfRenderer directly. A user-password
 * PDF cannot be rendered by the platform renderer below API 35,
 * so open surfaces [LocalPasswordRequiredException]; when a
 * session password is supplied it is used for the PdfBox probe
 * and, on API 35+, for the renderer's LoadParams. Never stored.
 *
 * v1.5.8 (H10): the locked-file fallback. When the platform
 * renderer cannot open a locked file at all (every device below
 * API 35 — both owner phones), the entered password opens the
 * document through PdfBox instead and pages are served by
 * [PdfBoxPageProvider] through the SAME session seam
 * ([PdfPageProvider]) the platform path feeds: same page count,
 * same ×2.0-oversample renders, same disk cache and filters.
 * The fallback engages ONLY on the locked branch — every file
 * the platform renderer can open keeps the platform road. The
 * spike's proof bar (V158LockedPdfSpikeTest: 210-page locked
 * fixture, heaviest page 170 ms at 254 dpi against a 2,000 ms
 * budget, wrong password = InvalidPasswordException) is what
 * licensed shipping it. The password lives only in memory for
 * the session (the open call, the fallback document and the
 * background strip's loader) and is NEVER persisted.
 *
 * Render cache (§3.1 row 10): renders under cacheDir, keyed by
 * (book, page, width), evicted oldest-first past [CACHE_CAP_BYTES].
 * Disposable by construction — deleting it loses nothing.
 *
 * v1.5.9 (Finding 3, F-2/F-3/F-4/F-6): the pipeline around the
 * (exonerated) render engine was rebuilt — JPEG-90 disk artefacts
 * encoded OUTSIDE the render slot, a 48 MB decoded-bitmap memory
 * tier, a priority scheduler with a ±2 staleness window driven by
 * the pager's published visible page, RAW-only cache keys (the
 * display filter moved to draw time in the cell), and a page-size
 * table read once at open so composition never opens a page.
 */
/**
 * v1.5.7 (Scope C — PDF document reader): a page-render DISPLAY
 * FILTER. This is deliberately NOT text theming: the PDF's pages
 * are fixed images, so "night mode" can only ever be a colour
 * matrix applied to the rendered bitmap (the Librera pattern from
 * the EPUB/PDF split research). The UI labels it exactly that.
 */
enum class PdfPageFilter {
    NONE,
    INVERT,
    BLACK_AND_WHITE,
    INVERT_BLACK_AND_WHITE,
    /** v1.5.8 (H3): the classic sepia treatment — Moon+'s fuller
     * PDF theme set, over the same colour-matrix seam. */
    SEPIA_PAGE,
    /** v1.5.8 (H3): a low-saturation, slightly lifted grey —
     * softer than full black & white for long reads. */
    SOFT_GREY;

    /** The colour matrix for this filter, or null for [NONE]. */
    fun colorMatrix(): ColorMatrix? = when (this) {
        NONE -> null
        INVERT -> ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        BLACK_AND_WHITE -> ColorMatrix().apply { setSaturation(0f) }
        INVERT_BLACK_AND_WHITE -> ColorMatrix().apply {
            setSaturation(0f)
            postConcat(
                ColorMatrix(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        SEPIA_PAGE -> ColorMatrix(
            floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        SOFT_GREY -> ColorMatrix().apply {
            setSaturation(0.3f)
            postConcat(
                ColorMatrix(
                    floatArrayOf(
                        0.92f, 0f, 0f, 0f, 14f,
                        0f, 0.92f, 0f, 0f, 14f,
                        0f, 0f, 0.92f, 0f, 14f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
    }

    /**
     * v1.5.8 (H3): the surround colour the reader paints BEHIND
     * the page picture for this preset (the filter + surround
     * pair), as an ARGB int; null = the reader's own default
     * surround.
     */
    fun surroundArgb(): Int? = when (this) {
        NONE, INVERT -> null
        BLACK_AND_WHITE -> 0xFF9E9E9E.toInt()
        INVERT_BLACK_AND_WHITE -> 0xFF000000.toInt()
        SEPIA_PAGE -> 0xFF2A2019.toInt()
        SOFT_GREY -> 0xFF4A4A4A.toInt()
    }
}

/**
 * v1.5.8 (H10): the page-provider seam [PdfSession] feeds from.
 * The platform road ([PdfRendererPageProvider]) and the locked-
 * file fallback ([PdfBoxPageProvider]) both implement it, so the
 * session above them — page count, the oversampled render + disk
 * cache + display filters, page geometry — is identical either
 * way. Renders are serialised by the session's render mutex;
 * providers additionally guard their own document where the
 * engine demands it.
 */
internal interface PdfPageProvider {
    val pageCount: Int

    /** True only for the PdfBox fallback (locked files the
     * platform renderer cannot open). */
    val isPdfBoxFallback: Boolean get() = false

    /** The RAW (unfiltered) page picture at exactly
     * [targetWidthPx] wide, height by the page's own aspect;
     * null when the page cannot be rendered. */
    fun renderRaw(page: Int, targetWidthPx: Int): Bitmap?

    /**
     * v1.5.9 (A-2 zoom-refine): renders only the normalised
     * [clip] region of the page, [targetClipWidthPx] wide.
     * The default renders the full page at the equivalent
     * width and crops (the PdfBox road); the platform road
     * overrides with a true clip render through
     * `Page.render(bitmap, clip, matrix, …)`. Either way the
     * result is capped at [PdfSession.REFINE_MAX_PIXELS].
     */
    fun renderClip(page: Int, clip: PdfPageClip, targetClipWidthPx: Int): Bitmap? {
        val points = pagePointsSize(page) ?: return null
        if (points.first <= 0 || points.second <= 0) return null
        val clipWidthPt = points.first * clip.widthFraction
        if (clipWidthPt <= 0f) return null
        var fullWidth = (targetClipWidthPx / clip.widthFraction).toInt().coerceAtLeast(1)
        // ≤4 MP cap on the equivalent full render.
        val fullHeight = fullWidth.toFloat() * points.second / points.first
        val maxFullWidth = kotlin.math.sqrt(
            PdfSession.REFINE_MAX_PIXELS.toDouble() * points.first / points.second
        ).toInt().coerceAtLeast(1)
        if (fullWidth > maxFullWidth) fullWidth = maxFullWidth
        val raw = renderRaw(page, fullWidth) ?: return null
        val x = (raw.width * clip.left).toInt().coerceIn(0, raw.width - 1)
        val y = (raw.height * clip.top).toInt().coerceIn(0, raw.height - 1)
        val w = (raw.width * clip.widthFraction).toInt().coerceAtLeast(1)
            .coerceAtMost(raw.width - x)
        val h = (raw.height * clip.heightFraction).toInt().coerceAtLeast(1)
            .coerceAtMost(raw.height - y)
        if (w <= 0 || h <= 0) return null
        return if (x == 0 && y == 0 && w == raw.width && h == raw.height) {
            raw
        } else {
            Bitmap.createBitmap(raw, x, y, w, h)
        }
    }

    /** The page's own size in POINTS, or null. */
    fun pagePointsSize(page: Int): Pair<Int, Int>?

    fun close()
}

/**
 * v1.5.11 (PDF fix step 4, research D3): a small pool of
 * reusable bitmap buffers, keyed by exact (width, height,
 * config). The platform render path and the disk-decode path
 * OBTAIN their target bitmap here instead of allocating a
 * fresh ~7 MB buffer per render (1080×1669×4 B on the owner's
 * phone); evicted cache bitmaps are RETURNED here by the
 * memory tier (see PdfSession's entryRemoved) instead of
 * waiting for GC. A pooled bitmap's contents are meaningless:
 * the renderer erases to white before drawing and a decode
 * overwrites every pixel, so reuse never leaks a stale page.
 * Ownership is exclusive — [obtain] removes the buffer from
 * the pool, and only the owner's eviction may [recycle] it
 * back. Buffers that are recycled, immutable, or over the
 * pool's byte budget are dropped (recycled for real) rather
 * than pooled.
 */
internal object PdfBitmapPool {

    /** At most a few page-sized buffers: three 1080-px pages
     * already cost ~21 MB of native heap. */
    private const val MAX_POOLED_BYTES = 24L * 1024 * 1024
    private const val MAX_POOLED_COUNT = 4

    private val lock = Any()
    private val buffers = ArrayList<Bitmap>()
    private var pooledBytes = 0L

    /** A pooled buffer of exactly [widthPx]×[heightPx] in
     * [config], or null when the pool holds none. The caller
     * owns the returned bitmap until it evicts it back. */
    fun obtain(widthPx: Int, heightPx: Int, config: Bitmap.Config): Bitmap? {
        if (widthPx <= 0 || heightPx <= 0) return null
        synchronized(lock) {
            val index = buffers.indexOfFirst {
                it.width == widthPx && it.height == heightPx && it.config == config &&
                    !it.isRecycled
            }
            if (index < 0) return null
            val bitmap = buffers.removeAt(index)
            pooledBytes -= bitmap.byteCount.toLong()
            return bitmap
        }
    }

    /** Returns [bitmap] to the pool for reuse, or recycles it
     * for real when it cannot be pooled (already recycled,
     * immutable, or the pool is full). Never throws. */
    fun recycle(bitmap: Bitmap) {
        runCatching {
            if (bitmap.isRecycled || !bitmap.isMutable) return
            synchronized(lock) {
                if (buffers.size >= MAX_POOLED_COUNT ||
                    pooledBytes + bitmap.byteCount > MAX_POOLED_BYTES
                ) {
                    bitmap.recycle()
                    return
                }
                buffers += bitmap
                pooledBytes += bitmap.byteCount.toLong()
            }
        }
    }

    /** Diagnostic/test seam: buffers currently pooled. */
    fun pooledCount(): Int = synchronized(lock) { buffers.size }

    /** Drops every pooled buffer (session teardown in tests). */
    fun clear() {
        synchronized(lock) {
            buffers.forEach { runCatching { it.recycle() } }
            buffers.clear()
            pooledBytes = 0
        }
    }
}

/**
 * v1.5.13 (C-P3): the disk-encode ledger — the backpressure
 * the fire-and-forget JPEG queue never had. Every scheduled
 * encode RESERVES its bitmap's bytes here at schedule time
 * and releases them when the encode job finishes, so bitmaps
 * retained by queued encode jobs count against a stated
 * budget instead of escaping the memory tier's ledger.
 * Reservations are deduplicated by cache key (page + width,
 * carried in the cache file's name): a page already queued
 * is not queued twice — the newer duplicate is dropped, the
 * render itself never is. When the job cap or the byte
 * budget is full, [tryReserve] refuses and the caller skips
 * the encode — the disk cache is an optimisation, never a
 * reason to hold memory or to delay a page.
 */
internal class PdfEncodeLedger(
    private val maxJobs: Int,
    private val maxBytes: Long
) {
    private val lock = Any()
    private val reserved = HashMap<String, Long>()
    private var reservedBytes = 0L

    /** Reserves [bytes] for [key]; false = drop this encode
     * (duplicate key, job cap reached, or byte budget full). */
    fun tryReserve(key: String, bytes: Long): Boolean = synchronized(lock) {
        if (reserved.containsKey(key)) return false
        if (reserved.size >= maxJobs) return false
        if (bytes > 0 && reservedBytes + bytes > maxBytes) return false
        reserved[key] = bytes.coerceAtLeast(0)
        reservedBytes += bytes.coerceAtLeast(0)
        true
    }

    /** Releases [key]'s reservation (encode finished, failed,
     * or was cancelled). Unknown keys are a no-op. */
    fun release(key: String) {
        synchronized(lock) {
            reserved.remove(key)?.let { reservedBytes -= it }
        }
    }

    /** Drops every reservation (session close: the encode
     * scope is cancelled, so no job will release its own). */
    fun clear() {
        synchronized(lock) {
            reserved.clear()
            reservedBytes = 0
        }
    }

    fun pendingCount(): Int = synchronized(lock) { reserved.size }
    fun pendingBytes(): Long = synchronized(lock) { reservedBytes }
}

/** The platform road: pages from android.graphics.pdf.PdfRenderer. */
internal class PdfRendererPageProvider(
    private val renderer: PdfRenderer,
    private val descriptor: ParcelFileDescriptor
) : PdfPageProvider {

    /** v1.5.11 (step 6, D8): serialises EVERY `openPage` on
     * this provider — renders (already serialised by the
     * session's scheduler) AND the lazy size table's fetches,
     * which now happen after open. The platform renderer
     * allows only a single open page at a time, so a size
     * fetch must never overlap an in-flight render. The
     * PdfBox fallback provider has always locked this way. */
    private val pageLock = Any()

    override val pageCount: Int get() = renderer.pageCount

    override fun renderRaw(page: Int, targetWidthPx: Int): Bitmap? =
        synchronized(pageLock) {
            renderer.openPage(page).use { pdfPage ->
                val height = (targetWidthPx.toFloat() * pdfPage.height / pdfPage.width)
                    .toInt().coerceAtLeast(1)
                // v1.5.11 (step 4, D3): reuse a pooled buffer of
                // this exact size when one is free — a fresh
                // allocation is the fallback, never the default.
                val bmp = PdfBitmapPool.obtain(targetWidthPx, height, Bitmap.Config.ARGB_8888)
                    ?: Bitmap.createBitmap(targetWidthPx, height, Bitmap.Config.ARGB_8888)
                try {
                    bmp.eraseColor(Color.WHITE)
                    pdfPage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                } catch (e: Exception) {
                    // A failed render owns nothing: the buffer
                    // goes back to the pool unharmed.
                    PdfBitmapPool.recycle(bmp)
                    throw e
                }
                bmp
            }
        }

    override fun pagePointsSize(page: Int): Pair<Int, Int>? = synchronized(pageLock) {
        runCatching {
            renderer.openPage(page).use { pdfPage -> pdfPage.width to pdfPage.height }
        }.getOrNull()
    }

    /** v1.5.9 (A-2): the true clip render — the page is drawn
     * through a scale+translate matrix so only the clip region
     * lands in the bitmap, at reading resolution, capped at
     * [PdfSession.REFINE_MAX_PIXELS]. */
    override fun renderClip(
        page: Int,
        clip: PdfPageClip,
        targetClipWidthPx: Int
    ): Bitmap? = synchronized(pageLock) {
        runCatching {
            renderer.openPage(page).use { pdfPage ->
            val clipWidthPt = pdfPage.width * clip.widthFraction
            val clipHeightPt = pdfPage.height * clip.heightFraction
            if (clipWidthPt <= 0f || clipHeightPt <= 0f) return@use null
            var scale = targetClipWidthPx / clipWidthPt
            val maxScale = kotlin.math.sqrt(
                PdfSession.REFINE_MAX_PIXELS.toDouble() / (clipWidthPt * clipHeightPt)
            ).toFloat()
            if (scale > maxScale) scale = maxScale
            // v1.5.11 (step 4, D3): clip renders reuse the
            // bitmap pool on the same terms as full renders.
            val clipW = (clipWidthPt * scale).toInt().coerceAtLeast(1)
            val clipH = (clipHeightPt * scale).toInt().coerceAtLeast(1)
            val bmp = PdfBitmapPool.obtain(clipW, clipH, Bitmap.Config.ARGB_8888)
                ?: Bitmap.createBitmap(clipW, clipH, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val matrix = Matrix().apply {
                setScale(scale, scale)
                // v1.5.38 (Fix 2, PDF-ZOOM-ANCHOR-FORENSICS
                // M2): postTranslate acts in OUTPUT space —
                // the translate terms must carry the scale.
                // Without it the depicted origin was the
                // requested origin DIVIDED by scale, so every
                // settle-refine installed a bitmap of the
                // wrong region (its content stepped toward
                // the page's top-left by origin·(1 − 1/scale)
                // at the sharpening instant, and snapped back
                // when the next gesture reverted the slot to
                // the base). Both terms now scale.
                postTranslate(
                    -clip.left * pdfPage.width * scale,
                    -clip.top * pdfPage.height * scale
                )
            }
            pdfPage.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
            }
        }.getOrNull()
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }

    companion object {
        /**
         * Opens the book's descriptor + platform renderer,
         * honouring the session [password] through LoadParams on
         * API 35+ (below that the platform has no password road
         * and a locked file throws [SecurityException] — the
         * signal the H10 fallback routes on). Throws
         * [LocalFileMissingException] when the file itself is
         * gone; the descriptor is closed on any failure.
         */
        fun create(
            context: Context,
            book: Book,
            filesRoot: File,
            password: String?
        ): PdfPageProvider {
            val descriptor: ParcelFileDescriptor = when {
                book.documentUri != null ->
                    context.contentResolver.openFileDescriptor(
                        Uri.parse(book.documentUri!!), "r"
                    ) ?: throw LocalFileMissingException("file not found")
                book.filePath != null -> {
                    val file = File(filesRoot, book.filePath!!)
                    if (!file.isFile) throw LocalFileMissingException("file not found")
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                }
                else -> throw LocalFileMissingException("file not found")
            }
            val renderer = try {
                if (!password.isNullOrEmpty() && Build.VERSION.SDK_INT >= 35) {
                    val params = android.graphics.pdf.LoadParams.Builder()
                        .setPassword(password).build()
                    PdfRenderer(descriptor, params)
                } else {
                    PdfRenderer(descriptor)
                }
            } catch (e: Exception) {
                runCatching { descriptor.close() }
                throw e
            }
            return PdfRendererPageProvider(renderer, descriptor)
        }
    }
}

/**
 * v1.5.8 (H10): the fallback road — a password-opened PdfBox
 * document rendering its own pages. Engaged ONLY for locked
 * files the platform renderer cannot open. The document (and
 * the session [password] that opened it) lives in memory for
 * the session and dies at [close]; nothing is persisted.
 * Renders go through PdfBox's own renderer at the DPI that
 * yields the requested pixel width — the shipped ×2.0
 * oversample is 254-dpi class for a 612 pt page (the spike's
 * measured shape).
 */
internal class PdfBoxPageProvider private constructor(
    private val document: PDDocument
) : PdfPageProvider {

    override val isPdfBoxFallback: Boolean = true

    private val renderer = PdfBoxRenderer(document)
    private val lock = Any()

    override val pageCount: Int get() = synchronized(lock) { document.numberOfPages }

    override fun renderRaw(page: Int, targetWidthPx: Int): Bitmap? = synchronized(lock) {
        runCatching {
            val box = document.getPage(page).cropBox
            if (box.width <= 0f) return@runCatching null
            val dpi = targetWidthPx * 72f / box.width
            renderer.renderImageWithDPI(page, dpi, ImageType.RGB)
        }.getOrNull()
    }

    override fun pagePointsSize(page: Int): Pair<Int, Int>? = synchronized(lock) {
        runCatching {
            val box = document.getPage(page).cropBox
            box.width.toInt() to box.height.toInt()
        }.getOrNull()
    }

    override fun close() {
        synchronized(lock) { runCatching { document.close() } }
    }

    companion object {
        /**
         * Opens the book through PdfBox with [password]. A wrong
         * password surfaces as [InvalidPasswordException] (the
         * honest failure the session maps to its password
         * state); a gone file as [LocalFileMissingException].
         * Document-URI books are read into memory ONCE at open
         * (the session's own document must outlive any single
         * stream; the file itself is never copied to disk).
         */
        fun create(
            context: Context,
            book: Book,
            filesRoot: File,
            password: String
        ): PdfPageProvider {
            runCatching {
                com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
            }
            val document = when {
                book.documentUri != null -> {
                    val bytes = context.contentResolver
                        .openInputStream(Uri.parse(book.documentUri!!))
                        ?.use { it.readBytes() }
                        ?: throw LocalFileMissingException("file not found")
                    PDDocument.load(ByteArrayInputStream(bytes), password)
                }
                book.filePath != null -> {
                    val file = File(filesRoot, book.filePath!!)
                    if (!file.isFile) throw LocalFileMissingException("file not found")
                    PDDocument.load(file, password)
                }
                else -> throw LocalFileMissingException("file not found")
            }
            return PdfBoxPageProvider(document)
        }
    }
}

/**
 * v1.5.9 (A-1, sift-amended F-3): a render ticket's priority
 * slot. P0 = the settled page (both pages of a dual-page
 * landscape spread), P1 = the pager's `targetPage` while a
 * turn is in flight, P2 = the prefetch window.
 */
enum class PdfRenderSlot { P0, P1, P2 }

/** v1.5.9 (A-2 amended): which render pass a ticket belongs
 * to — the 1.0× BASE, the 0.35× PREVIEW (non-sequential
 * landings / cold misses), or the zoom REFINE clip. The pass
 * is part of the cache key's bucket. */
enum class PdfRenderPass { BASE, PREVIEW, REFINE }

/** One scheduler ticket (A-1): page + request size + the
 * pager's generation at enqueue time. */
data class PdfRenderTicket(
    val page: Int,
    val widthPx: Int,
    val heightPx: Int,
    val slot: PdfRenderSlot,
    val pass: PdfRenderPass,
    val generation: Int
)

/** A normalised sub-rectangle of a page (0..1 per axis) — the
 * zoom-refine clip (A-2). */
data class PdfPageClip(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val widthFraction: Float get() = (right - left).coerceIn(0f, 1f)
    val heightFraction: Float get() = (bottom - top).coerceIn(0f, 1f)

    /** The cache bucket this clip quantises to (1/16 grid —
     * nearby pans share one refine entry). Retained for the
     * pinned key-format test; the refine ticket no longer
     * keys on it (see [exactKey]). */
    fun bucket(): String {
        fun q(v: Float) = (v * 16).toInt().coerceIn(0, 16)
        return "r${q(left)}-${q(top)}-${q(right)}-${q(bottom)}"
    }

    /** v1.5.38 (Fix 2): the EXACT refine key — the clip
     * itself on a 1/10000-of-page grid (sub-pixel at any
     * realistic page size, so an identical rest still hits
     * its own entry). The 1/16 [bucket] let a cache hit
     * depict a clip displaced by up to 1/16 of the page
     * from the one on screen — the largest surviving step
     * source after the renderClip matrix correction. The
     * refine ticket keys on this instead. */
    fun exactKey(): String {
        fun q(v: Float) = (v * 10000).roundToInt()
            .coerceIn(0, 10000)
        return "r${q(left)}-${q(top)}-${q(right)}-${q(bottom)}"
    }
}

/**
 * v1.5.9 (F-3 as amended by sift A-1): the render scheduler.
 * One render runs at a time (the providers are not
 * thread-safe), and the queue is an explicit 3-slot contract:
 * P0 (settled) before P1 (`targetPage`) before P2 (prefetch),
 * FIFO within P0/P1, and the pending P2 set ordered LIFO —
 * most recent direction first, so a reversed flip kills the
 * oldest prefetch hopes first.
 *
 * Staleness is re-checked IMMEDIATELY BEFORE the provider
 * render (the `openPage` seam) against the LIVE published
 * pager state — [isStillValid] — and again cheaply at pump
 * time. HARD RULE (A-1): an in-flight `Page.render()` is a
 * synchronous native call and CANNOT be cancelled; nothing
 * here pretends otherwise. Coroutine cancellation only ever
 * removes a ticket that has not been granted yet — drop-
 * before-openPage is the only staleness seam.
 *
 * v1.5.11 (PDF fix step 1, research D1): P2 (prefetch)
 * validity is DISTANCE-BASED ONLY. The v1.5.9/v1.5.10 rule
 * also demanded the ticket's enqueue-time generation still
 * equal the live generation — but the pager bumps that
 * generation on EVERY settle/target change, so any next
 * gesture conflated the whole queued prefetch set stale
 * before `openPage`: the +6 window only ever filled while
 * the reader sat still (dwell), never during the consecutive
 * turns it exists for. A P2 ticket is now judged solely by
 * its page's distance from the live settled/target page
 * against the (budget-derived, step 2) window, re-checked at
 * grant time exactly as before. The generation field stays
 * on the ticket for source compatibility and diagnostics;
 * nothing judges P2 by it any more. P0/P1 strictness is
 * unchanged.
 */
internal class PdfRenderScheduler {

    internal class Waiter(val ticket: PdfRenderTicket) {
        /** Completed with this waiter on grant, null on drop. */
        val gate = CompletableDeferred<Waiter?>()
    }

    private val mutex = Mutex()
    private val queue = ArrayDeque<Waiter>()
    @Volatile private var holder: Waiter? = null

    @Volatile private var pending = 0

    /** Diagnostic/test seam: tickets currently WAITING for
     * the render slot (the holder is not counted). Lets tests
     * observe enqueue order deterministically instead of
     * sleeping. */
    fun pendingCount(): Int = pending

    /** v1.5.11 (step 3, D6): advisory idle check for the jump-
     * landing protocol — true when no render holds the slot
     * and none is queued, i.e. a landing page's base render
     * can start immediately and earns NO preview pass. Read
     * without the mutex: it only ever chooses between the
     * preview-then-base order and the base-only order, never
     * correctness. */
    fun isIdle(): Boolean = holder == null && pending == 0

    /**
     * v1.5.11 (step 3, D6): the jump-landing flush — every
     * QUEUED P2 (prefetch) waiter is dropped (completed null,
     * never granted) so a non-sequential landing does not wait
     * behind other pages' prefetches. The in-flight render, if
     * any, is untouched (A-1's hard rule: it cannot be
     * cancelled). Returns how many waiters were dropped.
     */
    suspend fun dropQueuedPrefetch(): Int = mutex.withLock {
        var dropped = 0
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val waiter = iterator.next()
            if (waiter.ticket.slot == PdfRenderSlot.P2) {
                iterator.remove()
                pending--
                dropped++
                waiter.gate.complete(null)
            }
        }
        dropped
    }

    /** The live pager publication (A-1): settled page, the
     * spread's second settled page (-1 = none), the in-flight
     * target page (-1 = settled == target), and the
     * generation, bumped on every settle/target change. */
    @Volatile var settledPage: Int = -1
    @Volatile var settledSecondPage: Int = -1
    @Volatile var targetPage: Int = -1
    @Volatile var generation: Int = 0

    /** The tier's prefetch window (A-4): pages the settled
     * page may legitimately prefetch around — AND, by the
     * same fields, the staleness window [inWindow] judges P2
     * tickets against (the coupling is deliberate: H1's
     * widening keeps dwell-prefetched pages valid). Defaults
     * here are the FULL tier's; PdfSession overwrites them
     * from prefetchWindowFor(tier) at construction. */
    @Volatile var windowForward: Int = 6
    @Volatile var windowBackward: Int = 1

    private var highPriorityOutstanding = 0
    private val _highPriorityBusy = MutableStateFlow(false)

    /** True while any P0/P1 ticket is pending or running —
     * the A-5 strip gate reads this. */
    val highPriorityBusy: StateFlow<Boolean> = _highPriorityBusy

    private fun refreshBusyLocked() {
        _highPriorityBusy.value = highPriorityOutstanding > 0
    }

    private fun isSettledPage(page: Int): Boolean =
        page == settledPage || page == settledSecondPage

    /**
     * THE staleness seam (A-1): is this ticket still wanted,
     * judged against the live pager publication at the moment
     * just before `openPage`? Before anything is published
     * (settled == -1) nothing is stale.
     */
    fun isStillValid(ticket: PdfRenderTicket): Boolean {
        if (settledPage < 0) return true
        return when (ticket.slot) {
            PdfRenderSlot.P0 -> isSettledPage(ticket.page)
            PdfRenderSlot.P1 ->
                ticket.page == targetPage || isSettledPage(ticket.page)
            PdfRenderSlot.P2 -> {
                // v1.5.11 (step 1, D1): distance ONLY — the
                // enqueue-time generation no longer judges a
                // prefetch, so prefetches enqueued at one
                // settle survive the next turns and land while
                // turning is still in progress. A prefetch is
                // stale only when its page has fallen outside
                // the window around the LIVE settled page (or
                // the in-flight target, mid-turn).
                inWindow(ticket.page, settledPage) ||
                    (targetPage >= 0 && targetPage != settledPage &&
                        inWindow(ticket.page, targetPage))
            }
        }
    }

    private fun inWindow(page: Int, anchor: Int): Boolean =
        page in (anchor - windowBackward)..(anchor + windowForward)

    /**
     * v1.5.12 (P1 / contract §3.3+§3.4): the slot [page] holds
     * against the LIVE publication RIGHT NOW — the coherent
     * re-judgement a born-stale retry re-derives its ticket
     * under, so slot and validity are judged against the same
     * moment: settled (or its spread partner) → P0; the
     * in-flight target → P1; anything else keeps the caller's
     * [fallback] (a prefetch stays a prefetch — distance rules
     * judge it). Before the first publication nothing has a
     * coherent slot yet, so [fallback] stands.
     */
    fun coherentSlotFor(page: Int, fallback: PdfRenderSlot): PdfRenderSlot {
        if (settledPage < 0) return fallback
        return when {
            isSettledPage(page) -> PdfRenderSlot.P0
            page == targetPage -> PdfRenderSlot.P1
            else -> fallback
        }
    }

    /** Grants the next waiter, if the render slot is free.
     * Must be called under [mutex]. Provably-dead tickets
     * (per [isStillValid]) are completed null — dropped —
     * and never granted. */
    private fun pumpLocked() {
        if (holder != null) return
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val waiter = iterator.next()
            if (!isStillValid(waiter.ticket)) {
                iterator.remove()
                pending--
                if (waiter.ticket.slot != PdfRenderSlot.P2) {
                    highPriorityOutstanding--
                }
                waiter.gate.complete(null)
            }
        }
        refreshBusyLocked()
        val next = queue.firstOrNull { it.ticket.slot == PdfRenderSlot.P0 }
            ?: queue.firstOrNull { it.ticket.slot == PdfRenderSlot.P1 }
            ?: queue.lastOrNull { it.ticket.slot == PdfRenderSlot.P2 }
            ?: return
        queue.remove(next)
        pending--
        holder = next
        next.gate.complete(next)
    }

    /**
     * Enqueues [ticket] and waits for the render slot.
     * Returns the granted waiter, or null when the ticket was
     * dropped as stale (at enqueue or while queued). A ticket
     * found stale at enqueue never enters the queue.
     * Cancellation while waiting removes the PENDING ticket
     * only — a granted render is never interrupted (A-1's
     * hard rule).
     */
    suspend fun acquire(ticket: PdfRenderTicket): Waiter? {
        if (!isStillValid(ticket)) return null
        val waiter = Waiter(ticket)
        mutex.withLock {
            if (ticket.slot != PdfRenderSlot.P2) highPriorityOutstanding++
            queue.addLast(waiter)
            pending++
            pumpLocked()
        }
        return try {
            waiter.gate.await()
        } catch (e: CancellationException) {
            mutex.withLock {
                if (queue.remove(waiter)) {
                    pending--
                    if (ticket.slot != PdfRenderSlot.P2) {
                        highPriorityOutstanding--
                    }
                    refreshBusyLocked()
                } else if (holder === waiter) {
                    // Granted in the same instant as the
                    // cancellation: free the slot rather than
                    // strand it (the render never started —
                    // this is still before openPage).
                    holder = null
                    if (ticket.slot != PdfRenderSlot.P2) {
                        highPriorityOutstanding--
                    }
                    pumpLocked()
                }
            }
            throw e
        }
    }

    /** Releases the render slot and grants the next waiter. */
    suspend fun release(waiter: Waiter) {
        mutex.withLock {
            if (holder === waiter) holder = null
            if (waiter.ticket.slot != PdfRenderSlot.P2) {
                highPriorityOutstanding--
            }
            pumpLocked()
            refreshBusyLocked()
        }
    }
}

/**
 * v1.5.11 (PDF fix step 6, research D8): the LAZY page-size
 * table. The v1.5.9 F-4 table was filled by an open-time walk
 * — `pageCount` synchronous `openPage` round trips (227 on
 * the owner's book) before first paint, on top of the PdfBox
 * probe parse of the same file. This table starts EMPTY:
 * [get] fetches one page's size from the provider on first
 * need and caches it; [peek] never fetches (the render path
 * uses it for its height estimate, where a fallback is
 * fine); and the session's background completer
 * ([fillAround] expanding outward from the published page)
 * warms the rest off the open path. A page's size is fetched
 * at most once per session — concurrent fetches of the same
 * page are single-flighted by the lock-held re-check.
 */
internal class PdfPageSizeTable(
    private val provider: PdfPageProvider,
    val pageCount: Int
) {
    private val lock = Any()
    private val sizes = arrayOfNulls<Pair<Int, Int>>(pageCount.coerceAtLeast(0))

    /** v1.5.12 (P4 / contract §3.6): bumped every time a NEW
     * size lands in the table (via [get]'s fetch or the
     * completer's fills). Composition observes this instead of
     * calling [get]: a cell renders from [peek] only, and this
     * version landing is what recomposes it with the true size
     * once the off-composition fetch completes. */
    private val _filledVersion = MutableStateFlow(0)
    val filledVersion: StateFlow<Int> = _filledVersion

    private fun noteFilledLocked() {
        _filledVersion.value = _filledVersion.value + 1
    }

    /** How many entries are filled (diagnostic/test seam). */
    fun filledCount(): Int = synchronized(lock) { sizes.count { it != null } }

    /** The cached size, or null without touching the provider. */
    fun peek(page: Int): Pair<Int, Int>? {
        if (page !in 0 until pageCount) return null
        return synchronized(lock) { sizes[page] }
    }

    /** The page's size, fetched from the provider on first
     * need and cached from then on; null when out of range or
     * the provider cannot size the page. */
    fun get(page: Int): Pair<Int, Int>? {
        if (page !in 0 until pageCount) return null
        peek(page)?.let { return it }
        val fetched = runCatching { provider.pagePointsSize(page) }.getOrNull()
        if (fetched != null) {
            synchronized(lock) {
                // Single-flight by re-check: a racing fetch of
                // the same page keeps the first stored value.
                if (sizes[page] == null) {
                    sizes[page] = fetched
                    noteFilledLocked()
                }
                return sizes[page]
            }
        }
        return fetched
    }

    /** Warms [anchor] ± [radius] (the visible ± window pass
     * the completer runs first). Already-filled pages cost
     * nothing. */
    fun fillAround(anchor: Int, radius: Int) {
        for (page in (anchor - radius)..(anchor + radius)) {
            if (page in 0 until pageCount && peek(page) == null) get(page)
        }
    }

    /** One completer step outward: fills the ring at exactly
     * [distance] from [anchor]. Returns false when the ring
     * lies wholly outside the book (the completer is done). */
    fun fillRing(anchor: Int, distance: Int): Boolean {
        var anyInBook = false
        for (page in listOf(anchor - distance, anchor + distance)) {
            if (page in 0 until pageCount) {
                anyInBook = true
                if (peek(page) == null) get(page)
            }
        }
        return anyInBook
    }
}

/**
 * v1.5.11 (PDF fix step 7, research D7): per-page text
 * extraction over a DEDICATED short-lived PdfBox document.
 * The whole-book strip (F4) is one sequential pass that
 * pauses behind the strip gate whenever the reader turns a
 * page, so Text view could sit at "Preparing" for minutes.
 * This extractor opens the document ONCE (lazily, on first
 * per-page request), keeps it for the session, and extracts
 * exactly the requested page through [PdfStripperLoop]'s
 * per-page road — the strip stays as the background
 * completer for TTS/full search only. The document (and a
 * locked book's session password) lives in memory only and
 * dies at [close]; nothing is persisted. All extraction is
 * serialised on [lock] (PdfBox documents are not
 * thread-safe). Document-URI books are read into memory once
 * at first use, exactly as the H10 fallback provider does —
 * the file itself is never copied to disk.
 */
internal class PdfLazyTextExtractor(
    private val context: Context,
    private val book: Book,
    private val filesRoot: File,
    private val password: String?
) : java.io.Closeable {

    private val lock = Any()
    private var document: PDDocument? = null
    private var failed = false

    private fun openLocked(): PDDocument? {
        document?.let { return it }
        if (failed) return null
        return runCatching {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
            val loaded = when {
                book.documentUri != null -> {
                    val bytes = context.contentResolver
                        .openInputStream(Uri.parse(book.documentUri!!))
                        ?.use { it.readBytes() }
                        ?: throw LocalFileMissingException("file not found")
                    if (password.isNullOrEmpty()) {
                        PDDocument.load(ByteArrayInputStream(bytes))
                    } else {
                        PDDocument.load(ByteArrayInputStream(bytes), password)
                    }
                }
                book.filePath != null -> {
                    val file = File(filesRoot, book.filePath!!)
                    if (!file.isFile) throw LocalFileMissingException("file not found")
                    if (password.isNullOrEmpty()) {
                        PDDocument.load(file)
                    } else {
                        PDDocument.load(file, password)
                    }
                }
                else -> throw LocalFileMissingException("file not found")
            }
            document = loaded
            loaded
        }.getOrElse {
            failed = true
            null
        }
    }

    /** Page [page]'s (text, bounds), extracted on demand;
     * null when the document cannot be opened or the page is
     * out of range. A textless page yields ("", null), not
     * null — the honest empty page. */
    fun extractPage(page: Int): Pair<String, app.xreader.data.parse.PdfPageBounds?>? =
        synchronized(lock) {
            val doc = openLocked() ?: return@synchronized null
            if (page !in 0 until doc.numberOfPages) return@synchronized null
            runCatching {
                app.xreader.data.parse.PdfStripperLoop.stripPage(doc, page)
            }.getOrNull()
        }

    override fun close() {
        synchronized(lock) {
            runCatching { document?.close() }
            document = null
        }
    }
}

/** v1.5.27 (programme item 1): the deferred probe's state, as
 * the PDF surfaces consume it. While [Pending], the outline and
 * printed labels have not landed yet — surfaces must say
 * "loading", never "absent". [Landed] and [Unavailable] both
 * settle the payload as final: present, or honestly absent
 * (the probe ran and the document yielded nothing, or PdfBox
 * could not read it — the same end state a failed synchronous
 * probe has always produced). */
sealed interface PdfProbeState {
    data object Pending : PdfProbeState
    data object Landed : PdfProbeState
    data object Unavailable : PdfProbeState
}

class PdfSession private constructor(
    private val provider: PdfPageProvider,
    initialOutline: List<OutlineEntry>,
    /** v1.5.8 (F4): the background text strip — the session is
     * open and rendering WITHOUT it; it lands when it lands and
     * persists per book (see [PdfStripSession]). */
    private val strip: PdfStripSession,
    private val stripScope: kotlinx.coroutines.CoroutineScope,
    private val cacheRoot: File,
    val passwordRequired: Boolean = false,
    /** The document's own page labels (/PageLabels), one per file
     * page, when the PDF carries them — the printed pagination.
     * Empty when the document declares none. v1.5.27: the INITIAL
     * snapshot — on the deferred-probe road it starts empty and
     * the landed labels swap in (see labelsSnapshot). */
    initialLabels: List<String?> = emptyList(),
    /** v1.5.9 (F-4): every page's size in points, read from
     * the size table so composition never opens a page.
     * v1.5.11 (step 6, D8): the table is LAZY — open no longer
     * walks every page synchronously; entries fill on first
     * need plus a background completer (see
     * [PdfPageSizeTable]). */
    private val sizeTable: PdfPageSizeTable? = null,
    /** v1.5.11 (step 7, D7): the per-page text extractor the
     * strip session's fast path delegates to; null in harness
     * sessions that wire no PdfBox road (the strip's whole-
     * book behaviour is then unchanged). */
    private val textExtractor: PdfLazyTextExtractor? = null,
    /** v1.5.9 (A-4 amended): the device performance tier —
     * budgets the memory cache and the prefetch window. */
    val tier: PerfTier = PerfTier.FULL,
    /** v1.5.9 (A-5): the strip pause gate, shared with the
     * [PdfStripSession] created alongside this session: true =
     * the strip may run; the session drives it from the pager
     * publication + the scheduler's P0/P1 load. */
    internal val stripGate: MutableStateFlow<Boolean> = MutableStateFlow(true),
    /** v1.5.27 (programme item 1): the deferred PdfBox probe —
     * runs the full-document load behind the first pager
     * publication and returns outline + labels (null when the
     * probe could not read the document). Null = no deferral:
     * the session was constructed with the probe's payload
     * already in hand (the open failure road probed
     * synchronously), or a harness session wired none. */
    private val probeLoader: (suspend () -> Pair<List<OutlineEntry>, List<String?>>?)? = null
) : DocSession {

    override val format: BookFormat = BookFormat.PDF
    override val sectionCount: Int = provider.pageCount

    /** v1.5.27: the probe payload as it currently stands — the
     * constructor snapshot until the deferred probe lands, the
     * landed payload after. Swapped atomically (volatile
     * references, read fresh by every consumer on every call);
     * [probeState] is the observable that tells composing
     * surfaces to re-read. */
    @Volatile private var outlineSnapshot: List<OutlineEntry> = initialOutline
    override val outline: List<OutlineEntry> get() = outlineSnapshot

    @Volatile private var labelsSnapshot: List<String?> = initialLabels

    private val _probeState = MutableStateFlow(
        if (probeLoader == null) PdfProbeState.Landed else PdfProbeState.Pending
    )

    /** The deferred probe's state (see [PdfProbeState]). */
    val probeState: StateFlow<PdfProbeState> = _probeState

    /** v1.5.8 (H10): true when this session's pages come from
     * the PdfBox fallback — a locked file the platform renderer
     * could not open. False for every platform-road session. */
    val usesPdfBoxFallback: Boolean = provider.isPdfBoxFallback

    /** v1.5.9 (F-3/A-1): the 3-slot render scheduler (the
     * single FIFO mutex + in-lock PNG encode was Finding 3's
     * L1/L3). Its staleness window is this tier's prefetch
     * window (A-4). */
    private val scheduler = PdfRenderScheduler().also {
        val (forward, backward) = prefetchWindowFor(tier)
        it.windowForward = forward
        it.windowBackward = backward
    }

    /** v1.5.9 (A-4 amended): this tier's full-res prefetch
     * distances from the settled page. v1.5.10 (H1): +6/−1
     * FULL/STANDARD, +2/−1 MINIMAL (see [prefetchWindowFor]);
     * the preview ring is +4/−2 everywhere — on FULL/STANDARD
     * the ring now sits INSIDE the full-res window, so the
     * paged reader's ring loop (delta from the ring's forward
     * edge down to prefetchForward + 1) is simply empty there
     * and those pages arrive full-res instead. */
    val prefetchForward: Int get() = scheduler.windowForward
    val prefetchBackward: Int get() = scheduler.windowBackward

    /** v1.5.9 (F-3/A-3/A-4): the AUTHORITATIVE decoded-bitmap
     * memory tier, budgeted by [tier] (48/48/20 MB) — checked
     * before disk, filled on every render/decode, and a bitmap
     * is returned from it BEFORE any disk encode exists.
     * v1.5.11 (step 4, D3): an EVICTED bitmap is offered to
     * [PdfBitmapPool] for reuse instead of waiting for GC —
     * unless its page is one the pager currently shows or is
     * turning to (a live cell may still draw it; pooling a
     * bitmap out from under a live cell would corrupt the
     * visible page). Explicit removals (replaced / removed /
     * zoom-out refine eviction) are NOT pooled: their caller
     * owns that decision. */
    private val memoryCache =
        object : LruCache<String, Bitmap>(memoryCapBytesFor(tier).toInt()) {
            override fun sizeOf(key: String, value: Bitmap): Int =
                value.byteCount.coerceAtLeast(1)

            override fun entryRemoved(
                evicted: Boolean,
                key: String,
                oldValue: Bitmap,
                newValue: Bitmap?
            ) {
                if (!evicted) return
                val page = key.removePrefix("p").substringBefore('-').toIntOrNull()
                if (page == null || !isLivePage(page)) {
                    PdfBitmapPool.recycle(oldValue)
                }
            }
        }

    /** v1.5.11 (step 8, research D10): the refine tier,
     * SEPARATE from the base memory tier. One refine bitmap is
     * up to [REFINE_MAX_PIXELS] = 16 MB; inside the shared
     * 48 MB cache a single zoom could evict two prefetched
     * reading pages, so zooming out and turning landed on
     * placeholders. Refines now live under their own small cap
     * ([REFINE_CACHE_CAP_BYTES]) and can never evict the
     * reading window; their evictions feed the bitmap pool on
     * the same live-page guard as the base tier. */
    private val refineCache =
        object : LruCache<String, Bitmap>(REFINE_CACHE_CAP_BYTES.toInt()) {
            override fun sizeOf(key: String, value: Bitmap): Int =
                value.byteCount.coerceAtLeast(1)

            override fun entryRemoved(
                evicted: Boolean,
                key: String,
                oldValue: Bitmap,
                newValue: Bitmap?
            ) {
                if (!evicted) return
                val page = key.removePrefix("p").substringBefore('-').toIntOrNull()
                if (page == null || !isLivePage(page)) {
                    PdfBitmapPool.recycle(oldValue)
                }
            }
        }

    /**
     * v1.5.11 (step 4 guard): is [page] close enough to the
     * live pager position that a composed cell may still be
     * DRAWING its bitmap (the settled page, its spread partner,
     * the in-flight target, and the pager's beyond-viewport /
     * lazy-list neighbours)? Such a bitmap must never be
     * pooled out from under its cell — eviction leaves it to
     * GC instead. Pages outside this band have no composed
     * cell (the pager disposes them), so pooling is safe.
     */
    private fun isLivePage(page: Int): Boolean {
        val settled = scheduler.settledPage
        if (settled >= 0 && page in (settled - 2)..(settled + 3)) return true
        val target = scheduler.targetPage
        if (target >= 0 && page in (target - 2)..(target + 3)) return true
        return page == scheduler.settledSecondPage
    }

    /** The cache a render [pass] lives in (step 8: REFINE is
     * its own tier; BASE and PREVIEW share the base tier and
     * are counted in its budget ledger knowingly, step 2). */
    private fun cacheFor(pass: PdfRenderPass): LruCache<String, Bitmap> =
        if (pass == PdfRenderPass.REFINE) refineCache else memoryCache

    /** v1.5.11 (step 2, D2): the last BASE render's measured
     * page bytes — the input the budgeted prefetch window is
     * derived from (real render width, not an assumed one). */
    @Volatile private var lastBasePageBytes: Long = 0

    /** v1.5.11 (step 6, D8): the size completer runs at most
     * once per session (launched on the strip scope — IO, and
     * cancelled by [close] with it). */
    @Volatile private var sizeCompleterStarted = false

    /** v1.5.9 (A-3): the fire-and-forget disk-encode queue —
     * a single low-parallelism IO lane. Encodes are launched,
     * never awaited: a page is never "not ready" because its
     * cache file is still being written. */
    private val encodeScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1)
    )

    /** Serialises disk-cache writes + eviction among the
     * encode lane's own jobs. */
    private val cacheWriteLock = Any()

    /** v1.5.13 (C-P3): the encode queue's backpressure ledger
     * — see [PdfEncodeLedger]. Bounded by job count AND by
     * retained bytes; reservations are held from schedule
     * until the encode job finishes, so queued bitmaps count
     * against [ENCODE_MAX_PENDING_BYTES] instead of escaping
     * accounting outside the 48 MB memory tier. */
    private val encodeLedger = PdfEncodeLedger(
        maxJobs = ENCODE_MAX_PENDING_JOBS,
        maxBytes = ENCODE_MAX_PENDING_BYTES
    )

    /** Test/diagnostic seams: encode jobs currently reserved
     * (queued or in flight) and the bytes they retain. */
    internal fun encodePendingCount(): Int = encodeLedger.pendingCount()
    internal fun encodePendingBytes(): Long = encodeLedger.pendingBytes()

    /** The refine cache keys currently live for [page] —
     * tracked so zoom-out can evict them by name (A-2). */
    private val refineKeys = mutableSetOf<String>()
    private val refineKeysLock = Any()

    @Volatile private var closed = false
    @Volatile private var pagerScrolling = false

    /** v1.5.9 (F-3): the pager's settled page, as last
     * published; -1 until the pager first reports. */
    @Volatile var visiblePage: Int = -1
        private set

    /**
     * v1.5.12 (P1 / contract §3.3): the pager publication as an
     * observable flow — (settledPage, settledSecondPage,
     * targetPage), updated ONLY when one of the three actually
     * changes. The publication reaches the scheduler through a
     * `snapshotFlow` collector in the reader, i.e.
     * ASYNCHRONOUSLY after the pager state that also drives the
     * page cells' composition; a cell whose render ticket was
     * dropped as born-stale (its slot was chosen from live
     * pager state, its validity judged against this session's
     * not-yet-caught-up copy) keys its render effect on this
     * flow, so the publication catching up RE-TRIGGERS the
     * request at the slot the page now coherently holds. This
     * flow is also the cell's cache-fill trigger: a prefetch
     * landing for the cell's page bumps nothing here, but the
     * next publication (every settle) re-runs the effect, whose
     * first step is the memory-cache read inside the render
     * road — a later cache hit therefore fills the cell.
     */
    data class PagerPublication(
        val settledPage: Int,
        val settledSecondPage: Int,
        val targetPage: Int
    )

    private val _pagerPublication = MutableStateFlow(
        PagerPublication(settledPage = -1, settledSecondPage = -1, targetPage = -1)
    )
    val pagerPublication: StateFlow<PagerPublication> = _pagerPublication

    /** The size table's fill version (P4): composition keys on
     * this so a size fetched off the composition path lands in
     * the already-composed cells. 0 for harness sessions with
     * no size table. */
    val sizeTableVersion: StateFlow<Int>
        get() = sizeTable?.filledVersion ?: MutableStateFlow(0)

    /** The current pager generation (A-1) — bumped whenever
     * the settled or target page changes. v1.5.11 (step 1,
     * D1): P2 tickets still CARRY the generation they were
     * enqueued under, but validity no longer judges it —
     * prefetch staleness is distance-based (see
     * [PdfRenderScheduler.isStillValid]). The generation
     * remains as the pager's change counter / diagnostic. */
    fun currentGeneration(): Int = scheduler.generation

    /** Test/diagnostic seam: render tickets currently waiting
     * for the scheduler's slot. */
    internal fun pendingRenderCount(): Int = scheduler.pendingCount()

    /**
     * v1.5.9 (A-1): THE pager publication seam. The pager
     * publishes its settled page(s), its in-flight
     * [targetPage] and whether it is scrolling, at the same
     * points it reports position to the view model. A change
     * of settled or target bumps the generation counter.
     * v1.5.11 (step 1, D1): the bump no longer conflates P2
     * tickets — prefetch validity is distance from THIS
     * publication, judged at grant time.
     */
    fun publishPagerState(
        settledPage: Int,
        settledSecondPage: Int? = null,
        targetPage: Int = settledPage,
        scrolling: Boolean = false
    ) {
        val bumped = scheduler.settledPage != settledPage ||
            scheduler.targetPage != targetPage
        scheduler.settledPage = settledPage
        scheduler.settledSecondPage = settledSecondPage ?: -1
        scheduler.targetPage = targetPage
        if (bumped) scheduler.generation++
        // v1.5.12 (P1): the observable publication — emitted
        // only on a real (settled, second, target) change so
        // cell effects keyed on it restart exactly when the
        // published state catches up, not on scroll flags.
        val publication = PagerPublication(
            settledPage = settledPage,
            settledSecondPage = settledSecondPage ?: -1,
            targetPage = targetPage
        )
        if (_pagerPublication.value != publication) {
            _pagerPublication.value = publication
        }
        visiblePage = settledPage
        pagerScrolling = scrolling
        refreshStripGate()
        // v1.5.27 (programme item 1): the first publication is
        // also the starting gun for everything open() deferred —
        // the probe and the strip begin only once a first page
        // is on its way to the screen, never inside open().
        startDeferredProbe()
        startStripOnce()
        // v1.5.11 (step 6, D8): the first publication starts
        // the lazy size table's background completer, anchored
        // at the page the reader actually opened on.
        startSizeCompleter(settledPage)
    }

    /** v1.5.27 (programme item 1): the strip's start guard —
     * the strip starts ONCE, from whichever comes first: the
     * first pager publication, or the first consumer that
     * actually needs text ([textAt] / [pageText] — the
     * consumer-driven fallback, so search / TTS / text view
     * trigger the computation exactly as they did when open()
     * started it, and never await a strip nobody started). */
    private val stripStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun startStripOnce() {
        if (!stripStarted.compareAndSet(false, true)) return
        strip.start()
    }

    @Volatile private var deferredProbeStarted = false

    /** v1.5.27 (programme item 1): the deferred probe's launch —
     * once, behind the same background gate the size completer
     * awaits, so the full-document load starts only at an idle
     * moment and never competes with a render for the session's
     * background lane. Landing swaps outline + labels in
     * atomically and settles [probeState]; a probe that cannot
     * read the document settles [PdfProbeState.Unavailable] and
     * the payload stays empty. The launch rides [stripScope],
     * so closing the reader cancels it with the strip. */
    private fun startDeferredProbe() {
        val loader = probeLoader ?: return
        if (deferredProbeStarted) return
        deferredProbeStarted = true
        stripScope.launch {
            try {
                awaitBackgroundGate()
                val landed = runCatching { loader() }.getOrNull()
                if (landed != null) {
                    outlineSnapshot = landed.first
                    labelsSnapshot = landed.second
                    _probeState.value = PdfProbeState.Landed
                } else {
                    _probeState.value = PdfProbeState.Unavailable
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _probeState.value = PdfProbeState.Unavailable
            }
        }
    }

    /**
     * v1.5.13 (C-P1): the background-work gate for the size
     * completer — suspends (never spins, never blocks the
     * shared lane) while reading is busy, and returns only
     * when the completer may take the provider's page lock
     * for ONE page's size read. Busy = the strip gate is
     * closed (pager scrolling, or a P0/P1 ticket outstanding —
     * the [stripGate] the session already derives from the
     * scheduler's `highPriorityBusy` bridge) OR the render
     * scheduler is not idle (any slot's render pending or in
     * flight, P2 prefetch included, via its existing
     * [PdfRenderScheduler.isIdle]). Checking between pages
     * bounds a render's wait on the completer to the one
     * size read already in flight — the completer never
     * holds, queues for, or walks the page lock across the
     * book again. Cancellation (scope close) and session
     * close both abort the wait promptly.
     */
    private suspend fun awaitBackgroundGate() {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (closed) throw CancellationException("PdfSession closed")
            if (!stripGate.value) {
                // Suspend until the gate opens; the suspension
                // frees the shared single lane for the bridge
                // collector that will reopen it.
                stripGate.first { open -> open }
                continue
            }
            if (scheduler.isIdle()) return
            delay(BACKGROUND_GATE_POLL_MS)
        }
    }

    /**
     * v1.5.11 (step 6, D8): the background completer for the
     * lazy page-size table — visible ± window first, then one
     * ring at a time outward, on the session's IO strip scope
     * (so [close] cancels it with the strip). It never runs on
     * the open path: opening pays zero page-size round trips.
     *
     * v1.5.13 (C-P1): the walk is now GATED and CANCELLABLE,
     * one page at a time. The v1.5.12 completer ran
     * [PdfPageSizeTable.fillAround]/[PdfPageSizeTable.fillRing]
     * as a blocking, non-suspending, ungated loop over ALL
     * pages, taking the provider's page lock per `openPage`
     * in direct contention with renders for the whole book.
     * Now every single page step first awaits
     * [awaitBackgroundGate], and every
     * [SIZE_COMPLETER_CHUNK_PAGES] steps the walk yields the
     * shared lane — so renders never wait on the completer
     * beyond one in-flight size read, and close()/render
     * pressure stop the walk promptly instead of at the end
     * of the book.
     */
    private fun startSizeCompleter(anchor: Int) {
        val table = sizeTable ?: return
        if (sizeCompleterStarted) return
        sizeCompleterStarted = true
        stripScope.launch {
            try {
                var stepsInChunk = 0
                // One page step: gate → fetch-if-missing, with
                // a lane yield every chunk. Returns false when
                // [page] lies outside the book.
                suspend fun step(page: Int): Boolean {
                    if (page !in 0 until table.pageCount) return false
                    awaitBackgroundGate()
                    if (table.peek(page) == null) table.get(page)
                    stepsInChunk++
                    if (stepsInChunk >= SIZE_COMPLETER_CHUNK_PAGES) {
                        stepsInChunk = 0
                        yield()
                    }
                    return true
                }
                val radius = scheduler.windowForward + 1
                for (page in (anchor - radius)..(anchor + radius)) {
                    step(page)
                }
                var distance = scheduler.windowForward + 2
                while (!closed) {
                    val lowInBook = step(anchor - distance)
                    val highInBook = step(anchor + distance)
                    if (!lowInBook && !highInBook) break
                    distance++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A completer failure never harms reading:
                // unfilled sizes still fill on first need.
            }
        }
    }

    /** v1.5.11 (step 3, D6): the jump-landing flush — drops
     * every queued prefetch so the landing page's own render
     * is the next thing the slot serves. The reader calls
     * this BEFORE scrolling to a non-sequential landing.
     * Returns how many queued prefetches were dropped. */
    suspend fun flushQueuedPrefetch(): Int = scheduler.dropQueuedPrefetch()

    /** v1.5.11 (step 3, D6): true when the render slot is
     * idle (nothing rendering, nothing queued) — a landing
     * whose base render can start immediately earns no
     * preview pass. */
    fun isRenderSlotIdle(): Boolean = scheduler.isIdle()

    /**
     * v1.5.11 (step 2, D2): re-derives the scheduler's
     * prefetch/staleness window from a MEASURED base page
     * size (the real render width's bytes, from an actual
     * render ticket) against this tier's memory budget.
     * Called from the render road on every BASE render; a
     * no-op when the size has not changed. The window only
     * ever shrinks from the tier ceiling at large widths —
     * at the harness's 540 px it stays the full +6/−1.
     */
    private fun noteBaseRenderSize(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        val bytes = widthPx.toLong() * heightPx.toLong() * 4L
        if (bytes == lastBasePageBytes) return
        lastBasePageBytes = bytes
        val (forward, backward) = budgetedPrefetchWindow(tier, bytes)
        scheduler.windowForward = forward
        scheduler.windowBackward = backward
    }

    /** The single-page publication the first F-3 cut used;
     * retained for the continuous body and existing callers —
     * it publishes a settled page with no turn in flight. */
    fun publishVisiblePage(page: Int) {
        publishPagerState(settledPage = page, targetPage = page)
    }

    /** v1.5.9 (A-5): the strip gate — the strip pauses
     * whenever the pager is scrolling or any P0/P1 ticket is
     * outstanding, and resumes when the flip sequence settles. */
    private fun refreshStripGate() {
        stripGate.value = !pagerScrolling && !scheduler.highPriorityBusy.value
    }

    init {
        // Bridge the scheduler's P0/P1 load into the strip
        // gate for the session's whole life.
        stripScope.launch {
            scheduler.highPriorityBusy.collect { refreshStripGate() }
        }
    }

    /** v1.5.8 (F4): the strip's availability for the surfaces
     * that stand on it (search, text view H5, read-aloud H6). */
    val stripState: kotlinx.coroutines.flow.StateFlow<PdfStripState> = strip.state

    /** The strip's landed result, or null while it runs. */
    fun stripSnapshot(): app.xreader.data.parse.PdfStripResult? = strip.snapshot()

    /** v1.5.8 (F4/H4): page [page]'s persisted text bounds (page
     * points), once the strip has landed; null before that and
     * for textless pages. */
    fun pageTextBounds(page: Int): app.xreader.data.parse.PdfPageBounds? =
        strip.boundsAt(page)

    override suspend fun textAt(index: Int): String {
        // v1.5.27: a text consumer is a strip start trigger
        // (see startStripOnce) — it never awaits an unstarted
        // strip.
        startStripOnce()
        return strip.textAt(index)
    }

    /** v1.5.11 (step 7, D7): page [page]'s text WITHOUT
     * waiting for the whole-book strip — the strip session
     * serves it from its landed result when present, else
     * extracts just this page through [PdfLazyTextExtractor].
     * Text view and search hits read through here; TTS and
     * full search still sweep [textAt] page by page, which
     * now also lands page-by-page instead of all-or-nothing. */
    suspend fun pageText(page: Int): String {
        // v1.5.27: consumer-driven strip start, as in [textAt].
        startStripOnce()
        return strip.textAt(page)
    }

    /** A synchronous peek at page [page]'s already-known text
     * (landed strip or an earlier per-page extraction); null
     * when neither has it yet. Never extracts. */
    fun pageTextOrNull(page: Int): String? = strip.pageTextOrNull(page)

    /** The outline title nearest at-or-before [page] — the anchor
     * label progress rows carry (§3.2 contract). */
    fun anchorTitleFor(page: Int): String? =
        outline.lastOrNull { (LocatorJson.targetIndex(it.targetJson) ?: Int.MAX_VALUE) <= page }
            ?.title

    /** The printed page label the document itself assigns to file
     * page [page] (0-based), or null when the document carries no
     * usable label for it. Never synthesised: an absent label
     * stays absent so the UI can show the file page only. */
    fun printedLabelFor(page: Int): String? =
        labelsSnapshot.getOrNull(page)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The constant offset between file pages and printed numbers
     * (printed − file, both 1-based), when the document's labels
     * are plain numbers that agree on ONE offset; null when the
     * offset is unknown, non-numeric (roman front matter) or
     * inconsistent — in which case the UI shows per-page labels
     * only and never renumbers anything.
     */
    /** v1.5.27: the offset memo, keyed by the labels snapshot
     * it was computed from — the deferred probe's landing swaps
     * the snapshot, and the next read recomputes instead of
     * serving the pre-landing (label-less) answer forever. */
    @Volatile private var offsetMemoFor: List<String?>? = null
    @Volatile private var offsetMemo: Int? = null

    val printedNumberOffset: Int?
        get() {
            val labels = labelsSnapshot
            if (offsetMemoFor === labels) return offsetMemo
            var offset: Int? = null
            var seenNumeric = false
            var inconsistent = false
            for (page in labels.indices) {
                val numeric = labels[page]?.trim()?.toIntOrNull() ?: continue
                seenNumeric = true
                val candidate = numeric - (page + 1)
                if (offset == null) {
                    offset = candidate
                } else if (offset != candidate) {
                    inconsistent = true
                    break
                }
            }
            val computed = if (inconsistent || !seenNumeric) null else offset
            offsetMemoFor = labels
            offsetMemo = computed
            return computed
        }

    /** "Page 24 · printed p. 12" when a printed label exists and
     * differs from the file page; plain "Page 24" otherwise. */
    fun displayLabelFor(page: Int): String {
        val filePage = page + 1
        val printed = printedLabelFor(page)
        return if (printed != null && printed != filePage.toString()) {
            "Page $filePage · printed p. $printed"
        } else {
            "Page $filePage"
        }
    }

    /**
     * Renders [page] at [widthPx] (height follows the page's own
     * aspect). Served from the memory cache, then the bounded
     * disk cache, when present. Returns null after close.
     */
    suspend fun renderPage(page: Int, widthPx: Int): Bitmap? =
        renderPage(page, widthPx, PdfPageFilter.NONE, oversample = 1f)

    /**
     * v1.5.9 (F-6/A-2): the cache key for a render — page +
     * render width + the format version (`v2`) + the pass
     * bucket (A-2: keys gain the pass/scale bucket — BASE has
     * no suffix, PREVIEW is `p35`, REFINE carries its clip
     * bucket). The display FILTER is deliberately NOT in the
     * key: the cache stores RAW renders only and the reader
     * applies the filter as a draw-time colour filter.
     */
    internal fun renderCacheKey(
        page: Int,
        renderWidthPx: Int,
        pass: PdfRenderPass = PdfRenderPass.BASE,
        clipKey: String = ""
    ): String = when (pass) {
        PdfRenderPass.BASE -> "p$page-$renderWidthPx-$CACHE_FORMAT_VERSION"
        PdfRenderPass.PREVIEW ->
            "p$page-$renderWidthPx-$CACHE_FORMAT_VERSION-p35"
        PdfRenderPass.REFINE ->
            "p$page-$renderWidthPx-$CACHE_FORMAT_VERSION-$clipKey"
    }

    /**
     * Renders [page] for the document reader — at [widthPx] ×
     * [oversample] device pixels. v1.5.9 (A-2 AMENDED): the
     * reader's base scale is 1.0× fit on ALL tiers (the shipped
     * ×2.0 base is superseded); sharpness under zoom is the
     * refine pass's job, not the base render's.
     *
     *  - F-6: [filter] is accepted for source compatibility but
     *    is NOT applied or keyed — renders are RAW.
     *  - A-1: the request travels as a [PdfRenderTicket] in
     *    [slot] (default: P0 when [priority], else P2) under
     *    [generation]; staleness is re-checked immediately
     *    before the provider render against the live pager
     *    publication. A dropped ticket returns null.
     *  - A-3: the memory cache is authoritative — the bitmap
     *    is filled into it and RETURNED before any encode; the
     *    JPEG-90 disk write is fire-and-forget on the encode
     *    lane.
     */
    suspend fun renderPage(
        page: Int,
        widthPx: Int,
        filter: PdfPageFilter,
        oversample: Float,
        priority: Boolean = false,
        slot: PdfRenderSlot? = null,
        pass: PdfRenderPass = PdfRenderPass.BASE,
        generation: Int = currentGeneration()
    ): Bitmap? {
        val renderWidth = (widthPx * oversample.coerceIn(1f, 2.5f))
            .toInt().coerceAtLeast(1)
        return renderTicket(
            page = page,
            renderWidthPx = renderWidth,
            slot = slot ?: if (priority) PdfRenderSlot.P0 else PdfRenderSlot.P2,
            pass = pass,
            generation = generation,
            clip = null
        )
    }

    /**
     * v1.5.9 (A-2): the 0.35× PREVIEW pass — a fast, cheap
     * picture for non-sequential landings (scrubber / TOC /
     * go-to jumps) and cold visible-page misses, shown under
     * the base render's crossfade until the base lands.
     * [baseWidthPx] is the width the BASE render will use.
     */
    suspend fun renderPreview(
        page: Int,
        baseWidthPx: Int,
        slot: PdfRenderSlot = PdfRenderSlot.P0,
        generation: Int = currentGeneration()
    ): Bitmap? = renderTicket(
        page = page,
        renderWidthPx = (baseWidthPx * PREVIEW_SCALE).toInt().coerceAtLeast(1),
        slot = slot,
        pass = PdfRenderPass.PREVIEW,
        generation = generation,
        clip = null
    )

    /**
     * v1.5.9 (A-2): the zoom REFINE pass — the settled page's
     * visible [clip] rendered at reading resolution (true clip
     * render on the platform road), capped at
     * [REFINE_MAX_PIXELS]. [clipWidthPx] is the width the clip
     * occupies on screen. Evicted by [evictRefinements] when
     * the zoom leaves or the page stops being settled.
     */
    suspend fun renderRefinedClip(
        page: Int,
        clip: PdfPageClip,
        clipWidthPx: Int,
        generation: Int = currentGeneration()
    ): Bitmap? = renderTicket(
        page = page,
        renderWidthPx = clipWidthPx.coerceAtLeast(1),
        slot = PdfRenderSlot.P0,
        pass = PdfRenderPass.REFINE,
        generation = generation,
        clip = clip
    )

    /** A cheap memory-tier peek (no disk, no render): is
     * [page]'s BASE render at [renderWidthPx] already in the
     * authoritative memory cache? The reader uses it to decide
     * whether a cold visible page earns a preview pass first
     * (A-2). */
    fun isBaseCached(page: Int, renderWidthPx: Int): Boolean =
        memoryCache.get(renderCacheKey(page, renderWidthPx))
            ?.takeIf { !it.isRecycled } != null

    /** v1.5.9 (A-2): drops [page]'s refine entries (zoom back
     * to 1.0, or the page is no longer the settled one).
     * v1.5.11 (step 8, D10): they live in the separate refine
     * tier now, so eviction reads/removes there. Disk
     * artefacts age out under the cap (refines never encode). */
    fun evictRefinements(page: Int) {
        val keys = synchronized(refineKeysLock) {
            refineKeys.filter { it.startsWith("p$page-") }
                .also { refineKeys.removeAll(it.toSet()) }
        }
        keys.forEach { refineCache.remove(it) }
    }

    /**
     * v1.5.11 (step 5, research D4): decodes the disk tier's
     * JPEG for [cacheFile] into the pass's cache — called ONLY
     * outside the render slot (before `acquire`). The decode
     * runs through the bitmap pool (`inBitmap` reuse, step 4):
     * bounds first, a pooled buffer of the exact size when one
     * is free, then the real decode into it. A decode that
     * cannot reuse the pooled buffer returns it to the pool.
     * Null on any failure (the caller falls through to a real
     * render — the disk tier is disposable by construction).
     */
    private fun decodeDiskTier(
        cacheFile: File,
        cache: LruCache<String, Bitmap>,
        key: String
    ): Bitmap? {
        if (!cacheFile.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(cacheFile.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val pooled = PdfBitmapPool.obtain(
            bounds.outWidth, bounds.outHeight, Bitmap.Config.ARGB_8888
        )
        val options = BitmapFactory.Options().apply {
            if (pooled != null) {
                inBitmap = pooled
                inMutable = true
            }
        }
        val decoded = runCatching {
            BitmapFactory.decodeFile(cacheFile.path, options)
        }.getOrNull()
        if (decoded == null) {
            if (pooled != null) PdfBitmapPool.recycle(pooled)
            return null
        }
        if (pooled != null && decoded !== pooled) PdfBitmapPool.recycle(pooled)
        cache.put(key, decoded)
        return decoded
    }

    /** The one render road every pass travels: memory → disk →
     * scheduler ticket → pre-render staleness re-check →
     * provider → memory fill → return (encode fire-and-forget,
     * A-3). The REFINE pass is memory-only by design: it is a
     * transient view artefact with an explicit eviction rule
     * (A-2), so it neither reads nor writes the disk tier —
     * eviction really does free it. Runs on IO.
     *
     * v1.5.11 changes on this road:
     *  - (step 5, D4) the disk decode happens BEFORE the slot
     *    is acquired, and ONLY there. The v1.5.9 code re-checked
     *    the disk tier again after acquiring — a JPEG decode
     *    held the ONLY render slot (serialisation is platform
     *    law for `Page.render()`, but a decode needs no
     *    renderer at all), stalling every queued page behind
     *    it. The in-slot re-check is gone; the rare duplicate
     *    render it prevented is accepted (the memory re-check
     *    under the slot stays — it is a map lookup, not a
     *    decode). The slot now only renders.
     *  - (step 8, D10) REFINE reads/writes the separate refine
     *    cache, never the base tier.
     *  - (step 2, D2) every BASE ticket re-derives the
     *    budgeted prefetch window from its measured page size.
     *  - (step 6, D8) the height estimate peeks at the lazy
     *    size table — it never fetches on the render road. */
    private suspend fun renderTicket(
        page: Int,
        renderWidthPx: Int,
        slot: PdfRenderSlot,
        pass: PdfRenderPass,
        generation: Int,
        clip: PdfPageClip?
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (closed || page !in 0 until sectionCount || renderWidthPx <= 0) {
            return@withContext null
        }
        val key = renderCacheKey(
            // v1.5.38 (Fix 2): refines key on the EXACT clip
            // (PdfPageClip.exactKey), not the 1/16 bucket —
            // a bucket hit could depict a clip displaced by
            // up to 1/16 of the page from the one on screen.
            page, renderWidthPx, pass, clip?.exactKey().orEmpty()
        )
        val cache = cacheFor(pass)
        cache.get(key)?.takeIf { !it.isRecycled }
            ?.let { return@withContext it }
        val cacheFile = File(cacheRoot, "$key.jpg")
        if (pass != PdfRenderPass.REFINE) {
            // Step 5 (D4): the decode runs HERE, before the
            // slot — never while holding it.
            decodeDiskTier(cacheFile, cache, key)?.let { return@withContext it }
        }
        val points = sizeTable?.peek(page)
        val ticket = PdfRenderTicket(
            page = page,
            widthPx = renderWidthPx,
            heightPx = if (points != null && points.first > 0) {
                (renderWidthPx.toFloat() * points.second / points.first)
                    .toInt().coerceAtLeast(1)
            } else {
                renderWidthPx
            },
            slot = slot,
            pass = pass,
            generation = generation
        )
        if (pass == PdfRenderPass.BASE) {
            noteBaseRenderSize(ticket.widthPx, ticket.heightPx)
        }
        // v1.5.12 (P1 / contract §3.3): a dropped P0/P1 ticket
        // is NON-TERMINAL. The forensics conviction: a cell
        // derives its slot from LIVE pager state in
        // composition, while validity is judged here against
        // the session's COPIED publication, written by a
        // separate snapshotFlow collector with NO ordering
        // guarantee vs the cell effect — so the turned-to
        // page's P0 ticket can be judged before the publication
        // catches up and dropped as born-stale, and the cell
        // (one-shot effect, no retry) showed a placeholder
        // forever. Now: when a HIGH-PRIORITY (P0/P1) request is
        // dropped for staleness — at acquire, or at the
        // pre-render re-check — it is re-issued under the slot
        // the page COHERENTLY holds against the live
        // publication ([PdfRenderScheduler.coherentSlotFor]),
        // after a short wait for the publication to catch up,
        // until it renders or the bounded retry budget runs
        // out. P2 (prefetch) drops stay terminal exactly as
        // before: a prefetch the reader turned away from must
        // still die (the distance rule's whole point), and the
        // cell-side publication key re-asks for visible pages.
        var attemptTicket = ticket
        var retriesLeft = if (slot == PdfRenderSlot.P2) {
            0
        } else {
            STALE_RETRY_BUDGET
        }
        while (true) {
            val waiter = scheduler.acquire(attemptTicket)
            if (waiter == null) {
                // Dropped at acquire (stale at enqueue/grant).
                if (retriesLeft <= 0 || closed) return@withContext null
                retriesLeft--
                delay(STALE_RETRY_DELAY_MS)
                if (closed) return@withContext null
                // A sibling (prefetch) may have landed the page
                // while this request waited to retry.
                cache.get(key)?.takeIf { !it.isRecycled }
                    ?.let { return@withContext it }
                attemptTicket = attemptTicket.copy(
                    slot = scheduler.coherentSlotFor(page, attemptTicket.slot)
                )
                continue
            }
            val bitmap: Bitmap?
            var droppedStale = false
            var fromCache = false
            try {
                if (closed) return@withContext null
                // A-1's seam: the staleness re-check IMMEDIATELY
                // BEFORE the provider render (openPage) — the
                // pager may have moved while this ticket waited.
                if (!scheduler.isStillValid(attemptTicket)) {
                    droppedStale = true
                    bitmap = null
                } else {
                    // Re-check under the slot: a sibling render may
                    // have landed while we waited. (Memory only — the
                    // disk decode deliberately does NOT repeat here;
                    // step 5, D4.)
                    val cached = cache.get(key)?.takeIf { !it.isRecycled }
                    if (cached != null) {
                        bitmap = cached
                        fromCache = true
                    } else {
                        // The raw picture comes from whichever provider
                        // feeds this session (platform renderer, or the
                        // PdfBox fallback for locked files). This call is
                        // a synchronous native render: it is NEVER
                        // cancelled mid-flight (A-1's hard rule).
                        bitmap = if (clip != null) {
                            provider.renderClip(page, clip, renderWidthPx)
                        } else {
                            provider.renderRaw(page, renderWidthPx)
                        } ?: return@withContext null
                        // A-3: fill the authoritative memory tier FIRST.
                        cache.put(key, bitmap)
                        if (pass == PdfRenderPass.REFINE) {
                            synchronized(refineKeysLock) { refineKeys += key }
                        }
                    }
                }
            } finally {
                // v1.5.12 (P2 hardening): release is a SUSPEND
                // mutex call; the cell effect that owns this
                // ticket is cancelled on every slot/publication
                // change, so a plain finally could throw
                // CancellationException at a contended mutex and
                // strand the scheduler's holder forever
                // (pumpLocked refuses to grant while a holder
                // stands). NonCancellable makes the cleanup
                // complete even while the caller is cancelling.
                withContext(NonCancellable) { scheduler.release(waiter) }
            }
            if (!droppedStale) {
                val rendered = bitmap ?: return@withContext null
                // A-3: the JPEG-90 disk encode is fire-and-forget on
                // the low-priority encode lane — the bitmap above is
                // already returned to the reader; no page waits on
                // its cache file. REFINE never encodes (above), and
                // an under-slot CACHE hit does not re-encode (the
                // shipped road returned it directly).
                if (pass != PdfRenderPass.REFINE && !fromCache) {
                    scheduleEncode(cacheFile, rendered)
                }
                return@withContext rendered
            }
            // Dropped at the pre-render re-check: same retry
            // contract as an acquire drop.
            if (retriesLeft <= 0 || closed) return@withContext null
            retriesLeft--
            delay(STALE_RETRY_DELAY_MS)
            if (closed) return@withContext null
            cache.get(key)?.takeIf { !it.isRecycled }
                ?.let { return@withContext it }
            attemptTicket = attemptTicket.copy(
                slot = scheduler.coherentSlotFor(page, attemptTicket.slot)
            )
        }
        // Unreachable: the retry loop above only exits through
        // its returns. (Present so the withContext lambda's
        // result type is the bitmap type, not the loop's Unit.)
        @Suppress("UNREACHABLE_CODE")
        null
    }

    /**
     * v1.5.13 (C-P3): backpressured encode scheduling. The
     * bitmap's bytes are reserved in [encodeLedger] under its
     * cache key (page + width) BEFORE the job is launched:
     * a duplicate of a page+width already queued is dropped,
     * and when the queue is at its job cap or byte budget the
     * encode is skipped — in both cases the RENDER is
     * untouched (it was already returned to the reader; the
     * disk tier is an optimisation). The reservation releases
     * when the job ends, however it ends, keeping
     * [evictCache]'s disk accounting and this memory
     * accounting coherent: a bitmap is counted exactly while
     * an encode job retains it, and the disk cap still trims
     * the landed files after each write.
     */
    private fun scheduleEncode(cacheFile: File, bitmap: Bitmap) {
        if (closed) return
        val key = cacheFile.name
        val bytes = bitmap.byteCount.toLong().coerceAtLeast(1)
        if (!encodeLedger.tryReserve(key, bytes)) return
        encodeScope.launch {
            try {
                runCatching {
                    synchronized(cacheWriteLock) {
                        cacheRoot.mkdirs()
                        cacheFile.outputStream().use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        }
                        evictCache()
                    }
                }
            } finally {
                encodeLedger.release(key)
            }
        }
    }

    /**
     * v1.5.12 (P4 / contract §3.6): the page's size in POINTS
     * from the off-composition cache ONLY — a table PEEK that
     * never touches the provider, so calling it during
     * composition can never run a lock-contending `openPage`
     * on the main thread. Null on a cache miss: the caller
     * renders its placeholder/fallback geometry and calls
     * [requestPageSizeAsync]; when the size lands, the table's
     * fill version ([sizeTableVersion]) bumps and the cell
     * recomposes with the true size.
     */
    fun pagePointsSizePeek(page: Int): Pair<Int, Int>? {
        if (closed || page !in 0 until sectionCount) return null
        return sizeTable?.peek(page)
    }

    /**
     * v1.5.12 (P4): fills [page]'s size OFF the composition
     * path (the session's IO strip scope — the same lane the
     * background completer runs on, cancelled by [close]). A
     * no-op when the size is already cached. The fill bumps
     * [sizeTableVersion] via the table, which is what brings
     * the size into the composed cells.
     */
    fun requestPageSizeAsync(page: Int) {
        if (closed || page !in 0 until sectionCount) return
        val table = sizeTable ?: return
        if (table.peek(page) != null) return
        stripScope.launch {
            runCatching { table.get(page) }
        }
    }

    /** v1.5.9 (F-4): the page's own size in POINTS from the
     * size table, so composition never opens a page itself.
     * v1.5.11 (step 6, D8): the table is lazy — a miss fetches
     * exactly that one page's size from the provider (once,
     * then cached; the background completer warms the rest),
     * replacing the v1.5.9 open-time walk of every page.
     * v1.5.12 (P4): NOT for composition callers any more — a
     * miss synchronously fetches through the provider; reader
     * composition uses [pagePointsSizePeek] +
     * [requestPageSizeAsync] instead. Off-composition callers
     * (text bounds, crop derivation inputs already landed) may
     * still use it. */
    fun pagePointsSizeCached(page: Int): Pair<Int, Int>? {
        if (closed || page !in 0 until sectionCount) return null
        return sizeTable?.get(page)
    }

    /** v1.5.8 (H1/H4): the page's own size in POINTS (the
     * provider page's native dimensions) — the space F4's
     * text bounds and the fit geometry are computed in.
     * v1.5.9 (F-4): now served from the same open-time table
     * as [pagePointsSizeCached]; kept for existing callers. */
    fun pagePointsSize(page: Int): Pair<Int, Int>? = pagePointsSizeCached(page)

    /** Page pixel size at [widthPx], without rendering — the
     * placeholder geometry the scrolling surface reserves.
     * v1.5.9 (F-4): computed from the cached size table. */
    fun pageSizeAt(page: Int, widthPx: Int): Pair<Int, Int>? {
        val points = pagePointsSizeCached(page) ?: return null
        if (points.first <= 0) return null
        val height = (widthPx.toFloat() * points.second / points.first)
            .toInt().coerceAtLeast(1)
        return widthPx to height
    }

    private fun evictCache() {
        val files = cacheRoot.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= CACHE_CAP_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= CACHE_CAP_BYTES) break
            total -= file.length()
            file.delete()
        }
    }

    override fun close() {
        closed = true
        encodeScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        // v1.5.13 (C-P3): cancelled encode jobs cannot be
        // relied on to release their reservations (a job
        // cancelled before it starts never runs its finally),
        // so the ledger is cleared with the scope.
        encodeLedger.clear()
        stripScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        // v1.5.11 (step 7, D7): the per-page extractor's
        // short-lived PdfBox document dies with the session.
        runCatching { textExtractor?.close() }
        // Closing the provider is also where a fallback
        // session's password-opened document (and with it the
        // only in-memory copy of the password's effect) dies.
        runCatching { provider.close() }
    }

    companion object {
        /** v1.5.12 (P1): how many times a dropped P0/P1 ticket
         * is re-issued (under its coherently re-derived slot)
         * before the render road gives up and returns null —
         * with [STALE_RETRY_DELAY_MS] between attempts the
         * budget covers ~2 s of publication lag, far beyond the
         * snapshotFlow collector's real lag (a frame or two),
         * while a page the reader genuinely abandoned exhausts
         * it and dies quietly instead of retrying forever. */
        const val STALE_RETRY_BUDGET = 40

        /** v1.5.12 (P1): the wait between born-stale retries —
         * long enough for the pager publication collector to
         * run, short enough that a healed page lands within a
         * beat of the settle it belongs to. */
        const val STALE_RETRY_DELAY_MS = 50L

        /** v1.5.13 (C-P1): pages the size completer fills
         * between lane yields — small enough that the shared
         * single IO lane's other tenants (strip chunks, size
         * fills, the gate bridge) never wait a whole walk. */
        const val SIZE_COMPLETER_CHUNK_PAGES = 4

        /** v1.5.13 (C-P1): the completer's poll cadence while
         * the strip gate is open but a render is in flight —
         * a suspending delay, never a spin. */
        const val BACKGROUND_GATE_POLL_MS = 15L

        /** v1.5.13 (C-P3): the encode queue's job cap — at
         * most this many JPEG encodes are queued/in flight at
         * once; further renders skip their encode. */
        const val ENCODE_MAX_PENDING_JOBS = 4

        /** v1.5.13 (C-P3): the encode queue's byte budget —
         * bitmaps retained by pending encodes count against
         * this (16 MB ≈ two 1080-px pages plus change);
         * reservations that would exceed it are refused. */
        const val ENCODE_MAX_PENDING_BYTES = 16L * 1024 * 1024

        /** v1.5.9 (F-2): the disk tier grew 24 MB → 96 MB (a
         * plate JPEG-90 runs a fraction of its PNG-100 size, so
         * the cache now holds a real reading run of pages). */
        const val CACHE_CAP_BYTES = 96L * 1024 * 1024

        /** v1.5.9 (F-3): the decoded-bitmap memory tier. */
        const val MEMORY_CACHE_CAP_BYTES = 48L * 1024 * 1024

        /** v1.5.9 (A-4 amended): the MINIMAL tier's memory
         * budget (FULL and STANDARD keep the 48 MB above). */
        const val MEMORY_CACHE_MINIMAL_BYTES = 20L * 1024 * 1024

        /** v1.5.9 (A-4 amended): the memory budget for [tier]. */
        fun memoryCapBytesFor(tier: PerfTier): Long = when (tier) {
            PerfTier.MINIMAL -> MEMORY_CACHE_MINIMAL_BYTES
            PerfTier.STANDARD, PerfTier.FULL -> MEMORY_CACHE_CAP_BYTES
        }

        /** v1.5.9 (A-4 amended): the full-res prefetch window
         * CEILING for [tier] as (forward, backward) from the
         * settled page. v1.5.10 (H1): +6 forward on
         * FULL/STANDARD, +2 on MINIMAL (the plan's numbers
         * govern; the Gemini return's +4 was rejected in the
         * sift).
         *
         * v1.5.11 (step 2, research D2): this is now only the
         * ceiling — the EFFECTIVE window a session runs is
         * [budgetedPrefetchWindow], derived from the MEASURED
         * page bytes at the real render width. The v1.5.10
         * arithmetic below this comment sized a base render at
         * "≈1.7 MB at 540 px wide" — the SPREAD half-width.
         * The single-page road on the owner's M31 renders
         * 1080 px wide → 1080×1669×4 B ≈ 7.2 MB per page, so
         * the full +6/−1 set is ≈58 MB against the 48 MB cap:
         * prefetched pages evicted each other before the
         * reader reached them. At 1080 px the budgeted window
         * is +4/−1 (6 base pages ≈ 43 MB, previews counted in
         * the same ledger); at 540 px it stays the full +6/−1. */
        fun prefetchWindowFor(tier: PerfTier): Pair<Int, Int> = when (tier) {
            PerfTier.MINIMAL -> 2 to 1
            PerfTier.STANDARD, PerfTier.FULL -> 6 to 1
        }

        /** v1.5.11 (step 8, D10): the refine tier's own cap —
         * separate from the base tier's 48/48/20 MB, so a zoom
         * refine (up to 16 MB at the 4 MP ceiling) can never
         * evict the reading window. 16 MB holds one maximum
         * refine or several reading-resolution clips. */
        const val REFINE_CACHE_CAP_BYTES = 16L * 1024 * 1024

        /**
         * v1.5.11 (step 2, D2): the EFFECTIVE prefetch window
         * for [tier] when one base page costs [pageBytes]
         * (measured at the session's real render width):
         * resident base pages = the tier budget minus a
         * preview reserve (two preview pages at
         * [PREVIEW_SCALE]² of a base page — previews live in
         * the same base tier and are counted knowingly),
         * divided by the page cost, capped by the
         * [prefetchWindowFor] ceiling. The settled page always
         * fits (at least one resident page); backward keeps
         * its 1 before forward takes the rest. A non-positive
         * [pageBytes] (no render measured yet) yields the
         * ceiling unchanged.
         */
        fun budgetedPrefetchWindow(tier: PerfTier, pageBytes: Long): Pair<Int, Int> {
            val (maxForward, maxBackward) = prefetchWindowFor(tier)
            if (pageBytes <= 0) return maxForward to maxBackward
            val previewBytes = (pageBytes * PREVIEW_SCALE * PREVIEW_SCALE).toLong()
            val usable = memoryCapBytesFor(tier) - previewBytes * 2
            val basePages = (usable / pageBytes).toInt().coerceAtLeast(1)
            val backward = minOf(maxBackward, (basePages - 1).coerceAtLeast(0))
            val forward = minOf(
                maxForward, (basePages - 1 - backward).coerceAtLeast(0)
            )
            return forward to backward
        }

        /** v1.5.9 (A-2): the preview pass's scale of the base
         * render width. */
        const val PREVIEW_SCALE = 0.35f

        /** v1.5.9 (A-4): the preview ring around the settled
         * page, where the preview pass exists. */
        const val PREVIEW_RING_FORWARD = 4
        const val PREVIEW_RING_BACKWARD = 2

        /** v1.5.9 (A-2): the refine pass's pixel ceiling. */
        const val REFINE_MAX_PIXELS = 4_000_000

        /** v1.5.9 (A-2): zoom past this, once settled ~150 ms,
         * earns a refine render; double-tap refines at once. */
        const val REFINE_ZOOM_THRESHOLD = 1.25f

        /** The tier this device runs, from its total RAM —
         * the AppContainer convention (unknown reads as FULL,
         * never stranding a device on MINIMAL by accident). */
        internal fun tierForContext(context: Context): PerfTier {
            val manager = runCatching {
                context.getSystemService(ActivityManager::class.java)
            }.getOrNull() ?: return PerfTier.FULL
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            return PerformancePolicy.tierForRam(info.totalMem / (1024 * 1024))
        }

        /** v1.5.9 (F-2/F-6): the cache format version carried
         * in every key — `v2` = raw JPEG-90 renders. */
        const val CACHE_FORMAT_VERSION = "v2"

        /**
         * Loads the book's bytes into a PdfBox document for the
         * duration of [block]. Bytes come from the file itself or
         * a resolver stream; the file is NEVER copied into the
         * app (§3.4(d)). The document is fully consumed INSIDE
         * the stream's lifetime — PdfBox may read lazily, so
         * nothing outlives the stream.
         */
        // v1.5.13 (C-P2): inline so a SUSPENDING block (the
        // continuously-gated strip) can run inside the same
        // document lifetime the synchronous callers use; the
        // document is still fully consumed inside the
        // stream's lifetime either way.
        private inline fun <T> withPdfDocument(
            context: Context,
            book: Book,
            filesRoot: File,
            password: String?,
            block: (PDDocument) -> T
        ): T {
            runCatching {
                com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
            }
            return when {
                book.documentUri != null -> {
                    val stream = context.contentResolver
                        .openInputStream(Uri.parse(book.documentUri!!))
                        ?: throw LocalFileMissingException("file not found")
                    stream.use { input ->
                        val doc = if (password.isNullOrEmpty()) {
                            PDDocument.load(input)
                        } else {
                            PDDocument.load(input, password)
                        }
                        doc.use(block)
                    }
                }
                book.filePath != null -> {
                    val file = File(filesRoot, book.filePath!!)
                    if (!file.isFile) throw LocalFileMissingException("file not found")
                    val doc = if (password.isNullOrEmpty()) {
                        PDDocument.load(file)
                    } else {
                        PDDocument.load(file, password)
                    }
                    doc.use(block)
                }
                else -> throw LocalFileMissingException("file not found")
            }
        }

        /** v1.5.8 (H10): the PdfBox probe's classified outcome —
         * outline + labels when the document opened, a distinct
         * password failure when it demanded a (different) user
         * password, anything else as a plain failure. */
        private sealed interface ProbeOutcome {
            data class Ok(
                val outline: List<OutlineEntry>,
                val labels: List<String?>
            ) : ProbeOutcome
            data object PasswordFailed : ProbeOutcome
            data class Failed(val error: Exception) : ProbeOutcome
        }

        /**
         * The probe: outline + labels ONLY, through PdfBox with
         * [password] (null = the empty-password attempt that
         * classifies locked-ness). A gone file propagates as
         * [LocalFileMissingException] — that is never a password
         * state.
         */
        private fun probeDocument(
            context: Context,
            book: Book,
            filesRoot: File,
            password: String?
        ): ProbeOutcome {
            var outline: List<OutlineEntry> = emptyList()
            var labels: List<String?> = emptyList()
            return try {
                withPdfDocument(context, book, filesRoot, password) { doc ->
                    outline = PdfOutlineReader.read(doc).map { entry ->
                        OutlineEntry(
                            title = entry.title,
                            targetJson = LocatorJson.pdfPageTarget(entry.pageIndex)
                        )
                    }
                    // v1.5.7 (Scope C): the document's own printed
                    // pagination (/PageLabels) — best-effort; a
                    // document without labels keeps file pages only.
                    labels = runCatching {
                        doc.documentCatalog?.pageLabels
                            ?.labelsByPageIndices?.toList().orEmpty()
                    }.getOrDefault(emptyList())
                }
                ProbeOutcome.Ok(outline, labels)
            } catch (e: LocalFileMissingException) {
                throw e
            } catch (e: InvalidPasswordException) {
                ProbeOutcome.PasswordFailed
            } catch (e: Exception) {
                ProbeOutcome.Failed(e)
            }
        }

        suspend fun open(
            context: Context,
            book: Book,
            filesRoot: File,
            cacheDir: File,
            password: String? = null
        ): PdfSession = openInternal(
            context = context,
            book = book,
            filesRoot = filesRoot,
            cacheDir = cacheDir,
            password = password,
            platformCanHonourPassword = Build.VERSION.SDK_INT >= 35,
            tier = tierForContext(context),
            platformFactory = { ctx, b, root, pw ->
                PdfRendererPageProvider.create(ctx, b, root, pw)
            },
            fallbackFactory = { ctx, b, root, pw ->
                PdfBoxPageProvider.create(ctx, b, root, pw)
            }
        )

        /**
         * v1.5.8 (H10): the open seam [open] delegates to, split
         * at the provider factories because the platform
         * PdfRenderer cannot run in the JVM harness at all
         * (V156's contract note, re-measured at the H10 gate:
         * its constructor throws NoSuchMethodError under
         * Robolectric) — the harness drives THIS function with
         * the production fallback factory and a platform factory
         * standing in for the device, and the routing it pins is
         * the routing production runs.
         *
         * Routing law:
         *  - Locked-ness is PdfBox's verdict: a no-password load
         *    answering [InvalidPasswordException] means the file
         *    demands a user password. (Owner-password files open
         *    on the empty password and are NOT locked.)
         *  - A supplied password PdfBox rejects is a WRONG
         *    password → [LocalPasswordRequiredException], the
         *    retryable honest state — never a crash, never a
         *    fallback attempt with a password already disproven.
         *  - Locked + password accepted + platform cannot honour
         *    passwords (below API 35) → the fallback directly;
         *    the platform is never asked a question it cannot
         *    answer.
         *  - Otherwise the platform road is tried FIRST; only
         *    its failure on a locked, password-accepted file
         *    engages the fallback. Unlocked files NEVER reach
         *    the fallback.
         *  - No password yet on a locked file → the platform's
         *    refusal maps to [LocalPasswordRequiredException]
         *    (the prompt), as before.
         *
         * v1.5.27 (programme item 1): the ORDER changed, the law
         * did not. The provider road now runs FIRST and the
         * probe runs only when that road fails — its verdicts
         * feed the same routing, with the platform's
         * already-captured failure standing where the shipped
         * code re-derives it — or, on the success road, deferred
         * behind the first pager publication purely for outline
         * + labels. A successful open no longer waits on a
         * full-document parse before its first page.
         */
        internal suspend fun openInternal(
            context: Context,
            book: Book,
            filesRoot: File,
            cacheDir: File,
            password: String?,
            platformCanHonourPassword: Boolean,
            tier: PerfTier = PerfTier.FULL,
            platformFactory: (Context, Book, File, String?) -> PdfPageProvider,
            fallbackFactory: (Context, Book, File, String) -> PdfPageProvider
        ): PdfSession = withContext(Dispatchers.IO) {
            // ── Page provider FIRST (v1.5.27, programme item 1) ──
            // The v1.5.8–v1.5.26 open ran the PdfBox probe (a
            // full PDDocument.load) SERIALLY before the provider
            // existed, so Ready waited on a whole-document parse
            // whose only products — outline + printed labels —
            // serve post-open surfaces (v1.5.8 F4 had already
            // taken the text strip out of this wait; the probe
            // was what remained of it). Provider construction is
            // cheap (a descriptor + a renderer, no page opened),
            // and its failures carry the routing signals: a gone
            // file throws LocalFileMissingException itself, a
            // locked file throws SecurityException. So the
            // provider road goes first. The probe now runs ONLY
            // when that road fails — the locked/password/corrupt
            // taxonomy below is PdfBox's verdict and has no
            // cheaper source, and a book sitting at a password
            // prompt or an error state is not waiting on a first
            // page — or deferred behind the first pager
            // publication on the success road (the session's
            // probeLoader, below), landing outline + labels the
            // way the strip lands text.
            fun openFallback(): PdfPageProvider = try {
                fallbackFactory(context, book, filesRoot, password!!)
            } catch (e: InvalidPasswordException) {
                throw LocalPasswordRequiredException()
            }

            var platformError: Exception? = null
            val platform = try {
                platformFactory(context, book, filesRoot, password)
            } catch (e: Exception) {
                platformError = e
                null
            }
            var probedOutline: List<OutlineEntry> = emptyList()
            var probedLabels: List<String?> = emptyList()
            // True on the provider-first success road: the probe
            // has not run and the session defers it.
            var probeDeferred = false
            val provider: PdfPageProvider = when {
                platform != null -> {
                    probeDeferred = true
                    platform
                }
                platformError is LocalFileMissingException ->
                    throw platformError
                else -> {
                    // ── The probe, on the failure road only ────
                    // The H10 routing law, unchanged: outline +
                    // labels come along for the ride, but the
                    // products this road actually routes on are
                    // the locked-ness and password-acceptance
                    // verdicts.
                    val suppliedProbe =
                        probeDocument(context, book, filesRoot, password)
                    probedOutline = (suppliedProbe as? ProbeOutcome.Ok)
                        ?.outline ?: emptyList()
                    probedLabels = (suppliedProbe as? ProbeOutcome.Ok)
                        ?.labels ?: emptyList()
                    // Locked = the EMPTY password cannot open the
                    // file. With a session password in hand, that
                    // verdict needs its own probe; without one,
                    // the supplied probe IS the empty-password
                    // attempt.
                    val locked = if (password.isNullOrEmpty()) {
                        suppliedProbe is ProbeOutcome.PasswordFailed
                    } else {
                        probeDocument(context, book, filesRoot, null) is
                            ProbeOutcome.PasswordFailed
                    }
                    val passwordAccepted = !password.isNullOrEmpty() &&
                        suppliedProbe is ProbeOutcome.Ok
                    if (locked && !password.isNullOrEmpty() &&
                        suppliedProbe is ProbeOutcome.PasswordFailed
                    ) {
                        // The entered password is wrong: the
                        // honest, retryable password state — never
                        // a crash, never a fallback attempt with a
                        // password already disproven.
                        throw LocalPasswordRequiredException()
                    }
                    // The platform attempt already happened (its
                    // failure is why this road is running); the
                    // shipped routing's platform branch is spent,
                    // and its remaining branches decide: fallback
                    // for a locked, password-accepted file the
                    // platform could not open, the password prompt
                    // for locked / security-refused / probe-failed
                    // files, and the missing state otherwise.
                    // (Below API 35 the shipped code sent a
                    // locked, accepted file to the fallback
                    // without asking the platform at all; here the
                    // platform has been asked and has refused —
                    // the fallback outcome is identical.)
                    when {
                        locked && passwordAccepted -> openFallback()
                        locked || platformError is SecurityException ||
                            suppliedProbe !is ProbeOutcome.Ok ->
                            throw LocalPasswordRequiredException()
                        else -> throw LocalFileMissingException("file not found")
                    }
                }
            }
            // ── The background strip (F4) ───────────────────────
            // Keyed by the file's fingerprint (the reading-memory
            // fingerprint); legacy rows without one fall back to
            // the book id + page count, which still invalidates
            // when the file is replaced by a different-length one.
            // H10: for a locked book the loader re-opens the
            // document with the SESSION password (in memory only,
            // captured here) — the strip pipeline runs over the
            // password-opened document, so search / TTS / text
            // view work for locked books exactly as for unlocked
            // ones; if that load ever fails the strip settles
            // Unavailable and those surfaces say so honestly.
            val bookDir = File(cacheDir, "local-pdf/${book.id}")
            val stripFingerprint = book.fileFingerprint
                ?: "book:${book.id}|pages:${provider.pageCount}"
            // v1.5.9 (A-5): the strip runs on a single-thread
            // IO lane and PAUSES behind the session's gate
            // (pager scrolling or P0/P1 tickets outstanding).
            val stripScope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() +
                    Dispatchers.IO.limitedParallelism(1)
            )
            val stripGate = MutableStateFlow(true)
            // ── v1.5.11 (step 7, D7): per-page text ─────────
            // The strip session's fast path extracts ONE page
            // through a dedicated short-lived PdfBox document
            // instead of awaiting the whole-book strip; the
            // strip below remains the background completer for
            // TTS/full search. The extractor is session-owned
            // and closed by PdfSession.close().
            val textExtractor = PdfLazyTextExtractor(
                context = context,
                book = book,
                filesRoot = filesRoot,
                password = password
            )
            val strip = PdfStripSession(
                store = PdfStripStore(bookDir),
                fingerprint = stripFingerprint,
                loader = {
                    withPdfDocument(context, book, filesRoot, password) { doc ->
                        PdfStripperLoop.stripWithBounds(doc)
                    }
                },
                // v1.5.13 (C-P2): the production strip road —
                // the gate is re-checked per page for the
                // WHOLE book (not once at start), the loop
                // yields the shared lane between chunks, and
                // cancellation stops it mid-book. Output is
                // the plain strip's: same pass, same result,
                // same store write (in PdfStripSession).
                gatedLoader = { awaitGate ->
                    withPdfDocument(context, book, filesRoot, password) { doc ->
                        PdfStripperLoop.stripWithBoundsGated(doc, awaitGate)
                    }
                },
                scope = stripScope,
                gate = stripGate,
                pageLoader = { page -> textExtractor.extractPage(page)?.first }
            )
            // ── v1.5.11 (step 6, D8): the LAZY page-size table ──
            // Open pays ZERO page-size round trips (the v1.5.9
            // F-4 walk read every page's size here — 227 native
            // calls on the owner's book before first paint).
            // The table fills per page on first need, plus the
            // session's background completer once the pager
            // first publishes a page.
            val sizeTable = PdfPageSizeTable(provider, provider.pageCount)
            val session = PdfSession(
                provider = provider,
                initialOutline = probedOutline,
                strip = strip,
                stripScope = stripScope,
                cacheRoot = bookDir,
                passwordRequired = false,
                initialLabels = probedLabels,
                sizeTable = sizeTable,
                textExtractor = textExtractor,
                tier = tier,
                stripGate = stripGate,
                // v1.5.27: on the provider-first road the probe
                // has not run — the session runs it behind the
                // first pager publication and lands outline +
                // labels then. On the failure road the probe
                // already ran synchronously above, so its payload
                // is in hand and nothing is deferred.
                probeLoader = if (probeDeferred) {
                    val appContext = context.applicationContext
                    {
                        val outcome = probeDocument(
                            appContext, book, filesRoot, password
                        )
                        (outcome as? ProbeOutcome.Ok)?.let {
                            it.outline to it.labels
                        }
                    }
                } else {
                    null
                }
            )
            // Ready is returned NOW. v1.5.27: the strip no
            // longer starts here either — the session starts it
            // (and the deferred probe) at the first pager
            // publication, or at the first text need, whichever
            // comes first; both land behind the reading.
            session
        }
    }
}
