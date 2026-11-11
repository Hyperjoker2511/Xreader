package app.xreader.data.parse

import android.content.Context
import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.BookFormat
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/**
 * v1.5.5 (§4): what ONE probe load of a PDF learns — page count,
 * the Info dictionary's title/author/subject and the outline —
 * without stripping a single page's text. The importer's
 * file-first registration files the book from this alone.
 */
data class PdfProbe(
    val pageCount: Int,
    val title: String?,
    val author: String?,
    val description: String?,
    val outline: List<PdfOutlineEntry> = emptyList()
)

/** v1.5.5 (§4): one outline/bookmark entry — a chapter title
 * and the 0-based page it points at. */
data class PdfOutlineEntry(val title: String, val pageIndex: Int)

/**
 * v1.5.5 (§4): everything the completion job needs, read from
 * ONE document load — per-page texts, the outline, the embedded
 * images (already deduped by SOURCE hash in the production
 * extractor: a repeated plate is decoded and re-encoded once,
 * however many pages draw it) and the description. A null from
 * [PdfTextExtractor.extractAll] means "no single-load path"
 * (test fakes), and the parser falls back to the per-method
 * calls it has always made.
 */
data class PdfDocumentData(
    val pages: List<String>,
    val outline: List<PdfOutlineEntry>,
    val images: List<PdfPageImage>,
    val unreadableImages: Int,
    val description: String?
)

/**
 * The seam between the PDF engine and the rest of the app: production
 * uses PDFBox-Android (which needs an Android [Context] and cannot run
 * in JVM unit tests), tests use a fake. The parser logic — reflow,
 * chapter splitting, low-text honesty — is all JVM-side.
 */
interface PdfTextExtractor {
    /** Extracted plain text per page, in page order. */
    fun extractPages(file: File): AppResult<List<String>>

    /**
     * v1.0.6.2 (F6a): the PDF's own description metadata (the Info
     * dictionary's Subject — the PDF equivalent of EPUB's
     * dc:description), or null when the file carries none.
     */
    fun extractDescription(file: File): String? = null

    /**
     * v1.5.2 (FIX-21): the PDF's embedded images, one entry per
     * OCCURRENCE (page order, then resource order within the
     * page), already encoded as PNG bytes. The parser dedupes
     * by content hash — a repeated logo is stored once and
     * referenced at every occurrence. Default: no images (test
     * fakes that don't model images keep the old behaviour).
     */
    fun extractImages(file: File): List<PdfPageImage> = emptyList()

    /**
     * v1.5.2 (FIX-24): a rendered image of the first page (JPEG
     * bytes), used as the cover fallback for TEXT PDFs whose first
     * page carries no dominant embedded image. Null when the
     * extractor cannot render one. Default: no cover — test fakes
     * that don't model rendering keep the old behaviour, and any
     * failure on this leg must degrade to null, never fail an
     * import.
     */
    fun renderFirstPageCover(file: File): ByteArray? = null

    /**
     * v1.5.4 (WS-7): [extractImages] plus the number of embedded
     * images that could not be read — the import honesty count
     * the parser reports on [ParsedBook.droppedImages]. Default:
     * extraction only, nothing counted (test fakes that don't
     * model unreadable images keep the old behaviour).
     */
    fun extractImagesWithLoss(file: File): Pair<List<PdfPageImage>, Int> =
        extractImages(file) to 0

    /**
     * v1.5.4 (WS-7, addendum C): an extractor bound to a
     * user-supplied password for a locked file, or null when this
     * extractor cannot unlock one. The password lives only in the
     * returned instance for the one parse — entered once, used
     * once, never stored (the owner's one-time sign-in pattern).
     */
    fun withPassword(password: String): PdfTextExtractor? = null

    /**
     * v1.5.5 (§4): the probe — encryption classification, page
     * count and metadata from ONE load, no text stripping. The
     * default derives it from [extractPages] (correct, just not
     * fast — test fakes keep working unchanged); production
     * overrides it with a metadata-only load, which is what
     * makes file-first registration a seconds-fast step. The
     * classification contract is identical either way: a file
     * the empty password opens probes fine, a user-password
     * file answers [AppError.DrmProtected].
     */
    fun probe(file: File): AppResult<PdfProbe> = when (val pages = extractPages(file)) {
        is AppResult.Err -> pages
        is AppResult.Ok -> AppResult.Ok(
            PdfProbe(
                pageCount = pages.value.size,
                title = null,
                author = null,
                description = extractDescription(file),
                outline = extractOutline(file)
            )
        )
    }

    /**
     * v1.5.5 (§4): the document outline/bookmarks, in page
     * order. Default: none (test fakes that don't model an
     * outline keep the heading-split behaviour).
     */
    fun extractOutline(file: File): List<PdfOutlineEntry> = emptyList()

    /**
     * v1.5.5 (§4): the single-load completion read. Default:
     * null — the parser falls back to the per-method calls
     * ([extractPages] + [extractImagesWithLoss] +
     * [extractDescription]), which is what every test fake
     * models. Production overrides it with ONE load total.
     */
    fun extractAll(file: File): PdfDocumentData? = null
}

/**
 * v1.5.4 (WS-7): the PDF load classification — the ONLY place a
 * load outcome becomes an [AppError]. A document the empty
 * password opens (no password, or an owner-password-only file —
 * the commercial class Moon+ reads transparently) proceeds to
 * extraction, whatever its `/Encrypt` dictionary says: encryption
 * presence is not a reading lock. [InvalidPasswordException] —
 * the file genuinely demands a user password (or the supplied
 * one was wrong) — is the honest [AppError.DrmProtected]; every
 * other failure is a plain parse failure. Extracted as a seam so
 * the classification is JVM-testable; the Android-bound half
 * (resource-loader init inside [PdfBoxTextExtractor]) stays
 * outside it.
 */
internal fun <T> loadPdfDocument(
    file: File,
    password: String? = null,
    block: (PDDocument) -> T
): AppResult<T> = try {
    val document = if (password == null) {
        PDDocument.load(file)
    } else {
        PDDocument.load(file, password)
    }
    document.use { AppResult.Ok(block(it)) }
} catch (e: InvalidPasswordException) {
    AppResult.Err(AppError.DrmProtected)
} catch (e: Exception) {
    AppResult.Err(
        AppError.ParseFailed("PDF", "the file couldn't be read", e.javaClass.simpleName)
    )
}

/**
 * v1.5.5 (§4, reality check d): the production stripper loop,
 * lifted out of [PdfBoxTextExtractor] so it works on an ALREADY
 * OPEN [PDDocument] — the shape a JVM/Robolectric harness can
 * drive directly. Before v1.5.5 this loop lived inside the
 * Android-bound extractor and had never executed anywhere but
 * the owner's phone (the V154 finding: heading lines survived
 * it, prose did not, and no harness could see it happen). The
 * Android-bound part of the extractor is only the resource
 * loader init; everything after the load is this object.
 */
/**
 * v1.5.8 (F4): the union of one page's text bounds, in page
 * points (display space: origin top-left, the same space
 * [com.tom_roush.pdfbox.text.TextPosition]'s direction-adjusted
 * coordinates live in). H4's margin crop reads these; a page
 * with no text has null bounds — never a synthesised rect.
 */
data class PdfPageBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun union(other: PdfPageBounds): PdfPageBounds = PdfPageBounds(
        left = minOf(left, other.left),
        top = minOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom)
    )
}

/**
 * v1.5.8 (F4): one full-document strip pass's product — the
 * per-page texts AND the parallel per-page text bounds, recorded
 * by the SAME pass (one stripper invocation produces both).
 * [bounds] is parallel to [texts]; null where a page carries
 * no text.
 */
data class PdfStripResult(
    val texts: List<String>,
    val bounds: List<PdfPageBounds?>
)

object PdfStripperLoop {

    /** Plain text per page, in page order, from [document]. */
    fun strip(document: PDDocument): List<String> = stripWithBounds(document).texts

    /**
     * v1.5.8 (F4): the strip pass the PDF session's background
     * strip runs — per-page text plus the union of that page's
     * [com.tom_roush.pdfbox.text.TextPosition] bounds, accumulated
     * by a [PDFTextStripper] subclass in the same pass (the
     * stripper already walks page by page with per-page
     * start/end set; the subclass only OBSERVES writeString).
     */
    fun stripWithBounds(document: PDDocument): PdfStripResult {
        val stripper = BoundsStripper()
        val texts = ArrayList<String>(document.numberOfPages)
        val bounds = ArrayList<PdfPageBounds?>(document.numberOfPages)
        for (page in 1..document.numberOfPages) {
            val (text, pageBounds) = stripOnePage(stripper, document, page)
            texts += text
            bounds += pageBounds
        }
        return PdfStripResult(texts, bounds)
    }

    /** Pages stripped between lane yields in the gated road. */
    const val STRIP_CHUNK_PAGES = 2

    /**
     * v1.5.13 (C-P2): the gated, chunked strip — the SAME
     * per-page pass [stripWithBounds] runs (same stripper,
     * same texts, same bounds, same order), restructured so
     * the loop can be paused and cancelled. Before EVERY page
     * it awaits [awaitGate] (the owning session's continuous
     * gate: closed while a pager turn / high-priority render
     * is pending), checks coroutine cancellation, and every
     * [STRIP_CHUNK_PAGES] pages it [yield]s so the shared
     * single IO lane's other tenants (size fills, the gate
     * bridge itself) are never starved for a whole book.
     * [onPageStripped] reports each finished 0-based page —
     * the observability seam the gate tests drive.
     */
    suspend fun stripWithBoundsGated(
        document: PDDocument,
        awaitGate: suspend () -> Unit,
        onPageStripped: ((Int) -> Unit)? = null
    ): PdfStripResult {
        val stripper = BoundsStripper()
        val pageCount = document.numberOfPages
        val texts = ArrayList<String>(pageCount)
        val bounds = ArrayList<PdfPageBounds?>(pageCount)
        for (page in 1..pageCount) {
            awaitGate()
            currentCoroutineContext().ensureActive()
            val (text, pageBounds) = stripOnePage(stripper, document, page)
            texts += text
            bounds += pageBounds
            onPageStripped?.invoke(page - 1)
            if (page % STRIP_CHUNK_PAGES == 0 && page < pageCount) yield()
        }
        return PdfStripResult(texts, bounds)
    }

    /** One page (1-based) of the strip pass, shared by the
     * plain and gated loops so their output cannot drift. */
    private fun stripOnePage(
        stripper: BoundsStripper,
        document: PDDocument,
        page: Int
    ): Pair<String, PdfPageBounds?> {
        stripper.startPage = page
        stripper.endPage = page
        stripper.resetPageBounds()
        val text = stripper.getText(document)
        return text to stripper.pageBounds
    }

    /**
     * v1.5.11 (PDF fix step 7, research D7): ONE page's
     * (text, bounds) — the same stripper pass [stripWithBounds]
     * runs, scoped to [pageIndex] (0-based) alone. Text view
     * and search hits extract the pages they actually show
     * through this road (via the session's dedicated
     * short-lived document) instead of waiting for a
     * whole-book strip; the full strip remains the background
     * completer for TTS/full search. The caller owns the
     * document's lifetime and serialisation (PdfBox documents
     * are not thread-safe).
     */
    fun stripPage(document: PDDocument, pageIndex: Int): Pair<String, PdfPageBounds?> {
        val stripper = BoundsStripper()
        stripper.startPage = pageIndex + 1
        stripper.endPage = pageIndex + 1
        stripper.resetPageBounds()
        val text = stripper.getText(document)
        return text to stripper.pageBounds
    }

    /** Accumulates the union of TextPosition bounds for the page
     * currently being stripped. Text output is the superclass's,
     * untouched — this only watches it go by. */
    private class BoundsStripper : PDFTextStripper() {
        var pageBounds: PdfPageBounds? = null
            private set

        fun resetPageBounds() {
            pageBounds = null
        }

        override fun writeString(
            text: String?,
            textPositions: List<com.tom_roush.pdfbox.text.TextPosition>
        ) {
            for (position in textPositions) {
                val w = position.widthDirAdj
                val h = position.heightDir
                if (w <= 0f || h <= 0f) continue
                val piece = PdfPageBounds(
                    left = position.xDirAdj,
                    top = position.yDirAdj,
                    right = position.xDirAdj + w,
                    bottom = position.yDirAdj + h
                )
                pageBounds = pageBounds?.union(piece) ?: piece
            }
            super.writeString(text, textPositions)
        }
    }
}

/**
 * v1.5.5 (§4): the document outline read — chapter boundaries
 * from the book's own bookmarks when it carries them. Works on
 * an already-open [PDDocument], like [PdfStripperLoop], so the
 * same harness can drive it. Entries come back in page order;
 * an outline item whose destination can't be resolved is
 * skipped, never fatal.
 */
object PdfOutlineReader {

    fun read(document: PDDocument): List<PdfOutlineEntry> {
        val outline = runCatching {
            document.documentCatalog?.documentOutline
        }.getOrNull() ?: return emptyList()
        val entries = mutableListOf<PdfOutlineEntry>()
        fun walk(
            item: com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem?
        ) {
            var current = item
            while (current != null) {
                val title = runCatching { current.title }.getOrNull()
                    ?.trim()?.takeIf { it.isNotEmpty() }
                val page = runCatching {
                    current.findDestinationPage(document)
                }.getOrNull()
                if (title != null && page != null) {
                    val index = pageIndex(document, page)
                    if (index >= 0) entries += PdfOutlineEntry(title, index)
                }
                walk(runCatching { current.firstChild }.getOrNull())
                current = runCatching { current.nextSibling }.getOrNull()
            }
        }
        walk(runCatching { outline.firstChild }.getOrNull())
        return entries.sortedBy { it.pageIndex }
    }

    private fun pageIndex(document: PDDocument, page: com.tom_roush.pdfbox.pdmodel.PDPage): Int {
        var index = -1
        var found = -1
        document.pages.forEach { candidate ->
            index++
            if (found < 0 && (candidate === page ||
                    candidate.cosObject === page.cosObject)
            ) {
                found = index
            }
        }
        return found
    }
}

/** v1.5.2 (FIX-21): one embedded-image occurrence in a PDF:
 * the 0-based [pageIndex] it was drawn on and its PNG bytes
 * (the production extractor renders via PDImageXObject.getImage()
 * — pdfbox-android 2.0.27 has no rawBytes() — so a full-page
 * illustration plate survives exactly as any other image).
 * v1.5.5 (§4): [sourceHash] is the SHA-256 of the image's own
 * stored stream bytes, computed BEFORE any decode/re-encode —
 * the dedupe key that lets the production walk skip decoding a
 * repeated plate entirely. Null from extractors that don't hash
 * (test fakes); the parser then hashes the encoded bytes, as
 * before. */
class PdfPageImage(
    val pageIndex: Int,
    val pngBytes: ByteArray,
    val sourceHash: String? = null
)

class PdfBoxTextExtractor(
    context: Context,
    /** v1.5.4 (WS-7, addendum C): the one-parse unlock password,
     * set only on the instance [withPassword] returns. Never
     * persisted, never logged. */
    private val password: String? = null
) : PdfTextExtractor {

    companion object {
        /** FIX-24 render-leg bounds: a cover never renders larger
         * than this, and never upscales a page past this factor. */
        const val COVER_RENDER_MAX_WIDTH = 1200
        const val COVER_RENDER_MAX_HEIGHT = 1600
        const val COVER_RENDER_MAX_SCALE = 3f

        /** v1.5.5 (§4): embedded images wider than this are
         * downsampled before re-encode — a 4000px plate costs
         * the reader nothing it can see and the import minutes
         * it can't afford. */
        const val MAX_IMAGE_WIDTH = 1600
    }

    private val appContext = context.applicationContext

    @Volatile
    private var initialised = false

    /** Loads with the bound unlock password when there is one,
     * the empty password otherwise (PDFBox's default). */
    private fun loadDocument(file: File): PDDocument =
        if (password == null) {
            PDDocument.load(file)
        } else {
            PDDocument.load(file, password)
        }

    /** The Android-bound half of the extractor: PDFBox's font /
     * resource init. Isolated here (and only here) so every
     * other leg can run on an opened document anywhere. */
    private fun ensureInitialised(): Boolean = try {
        if (!initialised) {
            PDFBoxResourceLoader.init(appContext)
            initialised = true
        }
        true
    } catch (e: Exception) {
        false
    }

    override fun withPassword(password: String): PdfTextExtractor =
        PdfBoxTextExtractor(appContext, password)

    override fun extractPages(file: File): AppResult<List<String>> {
        if (!ensureInitialised()) {
            return AppResult.Err(
                AppError.ParseFailed("PDF", "the file couldn't be read", "init")
            )
        }
        // v1.5.4 (WS-7): NO isEncrypted gate — it refused every
        // standard-security PDF AFTER the empty password had
        // already opened it, owner-password files included (the
        // owner's Sol Press PDF, which Moon+ reads fine). The
        // classification now lives in loadPdfDocument: a file
        // that opens is extracted; only a real password demand
        // (or a wrong supplied password) is DrmProtected.
        // v1.5.5 (§4): the loop itself is PdfStripperLoop's, so
        // the harness exercises the production code, not a copy.
        return loadPdfDocument(file, password) { document ->
            PdfStripperLoop.strip(document)
        }
    }

    override fun probe(file: File): AppResult<PdfProbe> {
        if (!ensureInitialised()) {
            return AppResult.Err(
                AppError.ParseFailed("PDF", "the file couldn't be read", "init")
            )
        }
        // ONE load: classification (loadPdfDocument) + page
        // count + Info metadata + outline. No page is stripped.
        return loadPdfDocument(file, password) { document ->
            val info = document.documentInformation
            PdfProbe(
                pageCount = document.numberOfPages,
                title = info?.title?.trim()?.takeIf { it.isNotEmpty() },
                author = info?.author?.trim()?.takeIf { it.isNotEmpty() },
                description = info?.subject?.trim()?.takeIf { it.isNotEmpty() },
                outline = PdfOutlineReader.read(document)
            )
        }
    }

    override fun extractOutline(file: File): List<PdfOutlineEntry> {
        if (!ensureInitialised()) return emptyList()
        return runCatching {
            loadDocument(file).use { document -> PdfOutlineReader.read(document) }
        }.getOrDefault(emptyList())
    }

    /**
     * v1.5.5 (§4): the completion job's ONE load — strip,
     * outline, metadata and the deduped image walk all read the
     * same open document. (Before v1.5.5 the parse loaded the
     * file three-plus times: strip, image walk, a separate load
     * for one metadata string, and the cover render.) Any
     * failure returns null and the parser falls back to the
     * per-method calls, whose classification (incl.
     * DrmProtected) is the proven one.
     */
    override fun extractAll(file: File): PdfDocumentData? {
        if (!ensureInitialised()) return null
        return runCatching {
            loadDocument(file).use { document ->
                val pages = PdfStripperLoop.strip(document)
                val found = mutableListOf<PdfPageImage>()
                var unreadable = 0
                val seenBySourceHash = HashMap<String, ByteArray>()
                document.pages.forEachIndexed { pageIndex, page ->
                    unreadable += collectImages(
                        page.resources, pageIndex, found,
                        seenBySourceHash, depth = 0
                    )
                }
                PdfDocumentData(
                    pages = pages,
                    outline = PdfOutlineReader.read(document),
                    images = found,
                    unreadableImages = unreadable,
                    description = document.documentInformation?.subject
                        ?.trim()?.takeIf { it.isNotEmpty() }
                )
            }
        }.getOrNull()
    }

    override fun extractDescription(file: File): String? = try {
        if (!initialised) {
            PDFBoxResourceLoader.init(appContext)
            initialised = true
        }
        loadDocument(file).use { document ->
            document.documentInformation?.subject
                ?.trim()?.takeIf { it.isNotEmpty() }
        }
    } catch (e: Exception) {
        null
    }

    /** v1.5.2 (FIX-21): embedded images via the page resources'
     * XObjects (form XObjects recursed one level — illustration
     * plates are commonly wrapped in one). Each image renders
     * through PDImageXObject.getImage() to a Bitmap and encodes
     * as PNG; any single bad image is skipped, never fatal —
     * the text road already proved the book readable.
     * v1.5.4 (WS-7): the loss-aware twin counts the skips, so a
     * book that lost pictures says so at import. */
    override fun extractImages(file: File): List<PdfPageImage> =
        extractImagesWithLoss(file).first

    override fun extractImagesWithLoss(file: File): Pair<List<PdfPageImage>, Int> {
        return try {
            if (!initialised) {
                PDFBoxResourceLoader.init(appContext)
                initialised = true
            }
            loadDocument(file).use { document ->
                val found = mutableListOf<PdfPageImage>()
                var unreadable = 0
                val seenBySourceHash = HashMap<String, ByteArray>()
                document.pages.forEachIndexed { pageIndex, page ->
                    unreadable += collectImages(
                        page.resources, pageIndex, found,
                        seenBySourceHash, depth = 0
                    )
                }
                found to unreadable
            }
        } catch (e: Exception) {
            emptyList<PdfPageImage>() to 0
        }
    }

    /** v1.5.2 (FIX-24): the render leg of the PDF cover — page 1
     * rasterised with the framework PDFRenderer at bounded
     * dimensions (never larger than [COVER_RENDER_MAX_WIDTH] ×
     * [COVER_RENDER_MAX_HEIGHT] px) and encoded as JPEG. Any
     * failure returns null: a missing cover never blocks an
     * import. Android-only (android.graphics.pdf), like the rest
     * of this class — device-proven, not JVM-proven. */
    override fun renderFirstPageCover(file: File): ByteArray? = try {
        android.os.ParcelFileDescriptor.open(
            file, android.os.ParcelFileDescriptor.MODE_READ_ONLY
        ).use { fd ->
            android.graphics.pdf.PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount == 0) {
                    null
                } else {
                    renderer.openPage(0).use { page ->
                        val scale = minOf(
                            COVER_RENDER_MAX_WIDTH.toFloat() / page.width,
                            COVER_RENDER_MAX_HEIGHT.toFloat() / page.height,
                            COVER_RENDER_MAX_SCALE
                        ).coerceAtLeast(0.01f)
                        val width = (page.width * scale).toInt().coerceAtLeast(1)
                        val height = (page.height * scale).toInt().coerceAtLeast(1)
                        val bitmap = android.graphics.Bitmap.createBitmap(
                            width, height,
                            android.graphics.Bitmap.Config.ARGB_8888
                        )
                        try {
                            // Pages may have a transparent background;
                            // a cover needs the paper colour behind it.
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            // Without a transform the page renders at
                            // its natural point size in the corner; the
                            // matrix scales it to fill the bitmap.
                            val transform = android.graphics.Matrix().apply {
                                setScale(scale, scale)
                            }
                            page.render(
                                bitmap, null, transform,
                                android.graphics.pdf.PdfRenderer.Page
                                    .RENDER_MODE_FOR_DISPLAY
                            )
                            val stream = java.io.ByteArrayOutputStream()
                            bitmap.compress(
                                android.graphics.Bitmap.CompressFormat.JPEG,
                                85, stream
                            )
                            stream.toByteArray().takeIf { it.isNotEmpty() }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Returns how many embedded images could not be read
     * (v1.5.4, WS-7 — the honesty count). An XObject that cannot
     * even be fetched is not counted: its kind is unknowable, so
     * calling it a lost picture would be a guess.
     *
     * v1.5.5 (§4): dedupe happens BEFORE any decode/re-encode —
     * each XObject's own stored stream bytes are hashed first,
     * and an occurrence whose hash is already in [seenBySourceHash]
     * reuses the first occurrence's encoded bytes without ever
     * touching the decoder. (The pre-v1.5.5 walk decoded and
     * PNG-encoded EVERY occurrence and deduped afterwards —
     * the dominant cost of the old import.) Decoded bitmaps are
     * downsampled past [MAX_IMAGE_WIDTH] and recycled in
     * finally, on every path.
     */
    private fun collectImages(
        resources: com.tom_roush.pdfbox.pdmodel.PDResources?,
        pageIndex: Int,
        out: MutableList<PdfPageImage>,
        seenBySourceHash: MutableMap<String, ByteArray>,
        depth: Int
    ): Int {
        if (resources == null) return 0
        var unreadable = 0
        for (name in resources.xObjectNames) {
            val xObject = runCatching { resources.getXObject(name) }.getOrNull()
            when (xObject) {
                is com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject -> {
                    val sourceHash = runCatching {
                        val stream = xObject.cosObject as?
                            com.tom_roush.pdfbox.cos.COSStream
                        stream?.createInputStream()?.use { input ->
                            java.security.MessageDigest.getInstance("SHA-256")
                                .digest(input.readBytes())
                                .joinToString("") { "%02x".format(it) }
                        }
                    }.getOrNull()
                    val alreadyEncoded = sourceHash?.let { seenBySourceHash[it] }
                    if (alreadyEncoded != null) {
                        // A repeat of a plate already encoded on
                        // an earlier page: reference it, decode
                        // nothing.
                        out += PdfPageImage(pageIndex, alreadyEncoded, sourceHash)
                        continue
                    }
                    val bytes = runCatching {
                        val bitmap = xObject.image ?: return@runCatching null
                        var scaled: android.graphics.Bitmap? = null
                        try {
                            val source = if (bitmap.width > MAX_IMAGE_WIDTH) {
                                val targetHeight = (
                                    bitmap.height.toLong() * MAX_IMAGE_WIDTH /
                                        bitmap.width
                                    ).toInt().coerceAtLeast(1)
                                scaled = android.graphics.Bitmap.createScaledBitmap(
                                    bitmap, MAX_IMAGE_WIDTH, targetHeight, true
                                )
                                scaled
                            } else {
                                bitmap
                            }
                            val stream = java.io.ByteArrayOutputStream()
                            source.compress(
                                android.graphics.Bitmap.CompressFormat.PNG,
                                100, stream
                            )
                            stream.toByteArray().takeIf { it.isNotEmpty() }
                        } finally {
                            scaled?.recycle()
                            bitmap.recycle()
                        }
                    }.getOrNull()
                    if (bytes != null) {
                        if (sourceHash != null) seenBySourceHash[sourceHash] = bytes
                        out += PdfPageImage(pageIndex, bytes, sourceHash)
                    } else {
                        unreadable++
                    }
                }
                is com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject -> {
                    if (depth < 2) {
                        unreadable += collectImages(
                            xObject.resources, pageIndex, out,
                            seenBySourceHash, depth + 1
                        )
                    }
                }
                else -> Unit
            }
        }
        return unreadable
    }
}

/**
 * PDF text reflow (PLAN §4): extracted PDF text is hard-wrapped at
 * column width with hyphenated line breaks. Reflow joins lines back
 * into paragraphs:
 *  - a line ending in "-" joins the next line without a space when the
 *    next line starts lowercase (dehyphenation);
 *  - other single newlines become spaces;
 *  - blank lines are paragraph breaks;
 *  - lines that look like chapter headings ("Chapter 12", "제3장", …)
 *    are kept as boundaries by the caller.
 * Pure Kotlin — the goldens in the test suite pin this behaviour.
 */
object PdfReflow {

    private val headingRegex = Regex(
        """^\s*(chapter|episode|part|volume|book|prologue|epilogue|interlude|afterword)\b.*$|^\s*第.{1,12}[章节回卷篇]\s*.*$""",
        RegexOption.IGNORE_CASE
    )

    fun looksLikeHeading(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.length > 90 || trimmed.isEmpty()) return false
        return headingRegex.matches(trimmed)
    }

    /**
     * Joins one extracted page/chunk of hard-wrapped text into flowing
     * paragraphs. Returns paragraphs (headings included as their own
     * entries, flagged by [looksLikeHeading] at the caller).
     */
    fun reflow(text: String): List<String> {
        val paragraphs = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            val value = current.toString().replace(Regex("\\s+"), " ").trim()
            if (value.isNotEmpty()) paragraphs += value
            current.setLength(0)
        }
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        for (rawLine in lines) {
            val line = rawLine.trim()
            when {
                line.isEmpty() -> flush()
                looksLikeHeading(line) -> {
                    flush()
                    paragraphs += line
                }
                current.isEmpty() -> current.append(line)
                current.endsWith("-") && line.firstOrNull()?.isLowerCase() == true -> {
                    current.setLength(current.length - 1)
                    current.append(line)
                }
                current.endsWith("-") && line.firstOrNull()?.isDigit() == true -> {
                    current.setLength(current.length - 1)
                    current.append(line)
                }
                else -> current.append(' ').append(line)
            }
        }
        flush()
        return paragraphs
    }
}

/**
 * v1.5.5 (§4): the prose floor's character rule. A character is
 * PRINTABLE PROSE when it is not whitespace, not a control, not
 * a replacement character (U+FFFD — the mark of a glyph the
 * extractor couldn't decode) and not in the Private Use Area
 * (U+E000–U+F8FF — where subset fonts park body glyphs that
 * extract as meaningless codepoints). The V154 hollow chapters
 * were invisible to the old average because front matter + TOC
 * mass averaged over dead body pages; counting only these
 * characters, per chapter, is what makes a hollow chapter
 * visible as hollow.
 */
object PdfProse {

    fun printableCount(text: String): Int = text.count { c ->
        c.code !in 0xE000..0xF8FF &&
            c != '\uFFFD' &&
            !c.isWhitespace() &&
            !c.isISOControl()
    }

    /** A line of a table of contents: dot leaders (or their
     * ellipsis/middle-dot kin) ending in a page number. */
    private val TOC_LINE = Regex(
        """(?:\.{3,}|…+|·{3,})\s*\d{1,4}\s*$"""
    )

    fun isTocLine(text: String): Boolean = TOC_LINE.containsMatchIn(text.trim())
}

/**
 * v1.5.5 (§4): one chapter under assembly — the parser's draft
 * shape, lifted out of the parse so the outline splitter, the
 * heading splitter and the TOC coalescer all build the same
 * thing (and the harness can assert on it).
 *
 * [hasProse] is the v1.5.5 rule: an actual Paragraph. Before
 * v1.5.5 any non-Image block counted — a lone Heading satisfied
 * it, which is exactly how heading-only chapters were stored
 * as if they were prose.
 */
internal data class PdfDraftChapter(
    val title: String,
    val blocks: MutableList<ChapterBlock>,
    val firstPage: Int,
    var lastPage: Int,
    /** A coalesced front-matter / table-of-contents section:
     * real readable content that is not story prose, exempt
     * from the under-floor count. */
    val frontMatterSection: Boolean = false
) {
    val hasProse: Boolean
        get() = blocks.any { it is ChapterBlock.Paragraph }

    val hasImage: Boolean
        get() = blocks.any { it is ChapterBlock.Image }

    /** Printable prose characters (Paragraph blocks only —
     * a chapter's own title never counts toward its body). */
    val proseChars: Int
        get() = blocks.filterIsInstance<ChapterBlock.Paragraph>()
            .sumOf { PdfProse.printableCount(it.text) }

    /** The v1.5.5 prose floor: enough printable prose, OR an
     * image to carry the chapter (Amendments: scanned /
     * illustrated chapters are valid). */
    fun meetsFloor(): Boolean =
        proseChars >= PdfBookParser.MIN_PROSE_CHARS_PER_CHAPTER || hasImage

    /** Any content at all except page images — the SPLIT
     * decision's notion of "started" (a Heading counts here,
     * exactly as the pre-v1.5.5 splitter behaved; the floor's
     * stricter rule lives in [hasProse] alone). */
    val hasBodyContent: Boolean
        get() = blocks.any { it !is ChapterBlock.Image }
}

/**
 * v1.5.5 (§4): chapter assembly — outline first, heading
 * detection as the fallback, TOC coalescing in the heading
 * path. Pure JVM (pages in, drafts out), so the harness pins
 * all three behaviours without an extractor.
 */
internal object PdfChapterAssembly {

    /**
     * Builds the chapter drafts for a book. [outline] wins when
     * it carries at least two usable entries; otherwise the
     * heading splitter runs over the reflowed pages and its
     * output is TOC-coalesced.
     */
    fun assemble(
        pages: List<String>,
        outline: List<PdfOutlineEntry>,
        imagesByPage: Map<Int, List<String>>
    ): List<PdfDraftChapter> {
        val reflowed = pages.map { PdfReflow.reflow(it) }
        outlineChapters(reflowed, outline, imagesByPage)?.let { return it }
        return coalesceToc(headingChapters(reflowed, imagesByPage))
    }

    /** Outline/bookmark boundaries: each entry's chapter runs
     * from its page to the next entry's page. Pages before the
     * first entry become one Front Matter (or Table of
     * Contents) section when they carry anything at all. */
    private fun outlineChapters(
        reflowed: List<List<String>>,
        outline: List<PdfOutlineEntry>,
        imagesByPage: Map<Int, List<String>>
    ): List<PdfDraftChapter>? {
        val entries = outline
            .filter { it.pageIndex in reflowed.indices && it.title.isNotBlank() }
            .sortedBy { it.pageIndex }
            .distinctBy { it.pageIndex }
        if (entries.size < 2) return null
        val drafts = mutableListOf<PdfDraftChapter>()
        val firstEntryPage = entries.first().pageIndex
        if (firstEntryPage > 0) {
            val blocks = mutableListOf<ChapterBlock>()
            for (page in 0 until firstEntryPage) {
                for (fileName in imagesByPage[page].orEmpty()) {
                    blocks += ChapterBlock.Image(fileName, alt = "")
                }
                for (paragraph in reflowed[page]) {
                    blocks += ChapterBlock.Paragraph.plain(paragraph)
                }
            }
            if (blocks.isNotEmpty()) {
                val tocHeavy = blocks
                    .filterIsInstance<ChapterBlock.Paragraph>()
                    .count { PdfProse.isTocLine(it.text) }
                val paragraphCount = blocks.count { it is ChapterBlock.Paragraph }
                drafts += PdfDraftChapter(
                    title = if (paragraphCount > 0 && tocHeavy * 2 >= paragraphCount) {
                        "Table of Contents"
                    } else {
                        "Front Matter"
                    },
                    blocks = blocks,
                    firstPage = 0,
                    lastPage = firstEntryPage - 1,
                    frontMatterSection = true
                )
            }
        }
        entries.forEachIndexed { index, entry ->
            val end = entries.getOrNull(index + 1)?.pageIndex ?: reflowed.size
            val blocks = mutableListOf<ChapterBlock>()
            blocks += ChapterBlock.Heading(2, entry.title)
            for (page in entry.pageIndex until end) {
                for (fileName in imagesByPage[page].orEmpty()) {
                    blocks += ChapterBlock.Image(fileName, alt = "")
                }
                for (paragraph in reflowed[page]) {
                    // The chapter's own title line, extracted as
                    // text on its first page, would duplicate the
                    // Heading block the outline just gave it.
                    if (page == entry.pageIndex &&
                        paragraph.trim().equals(entry.title.trim(), ignoreCase = true)
                    ) {
                        continue
                    }
                    blocks += ChapterBlock.Paragraph.plain(paragraph)
                }
            }
            drafts += PdfDraftChapter(
                title = entry.title,
                blocks = blocks,
                firstPage = entry.pageIndex,
                lastPage = end - 1
            )
        }
        return drafts
    }

    /** The pre-v1.5.5 splitter, behaviour preserved: pages are
     * grouped at heading paragraphs, a heading found before any
     * content retitles the open chapter, illustrations anchor
     * at their page's position. */
    private fun headingChapters(
        reflowed: List<List<String>>,
        imagesByPage: Map<Int, List<String>>
    ): List<PdfDraftChapter> {
        val chapters = mutableListOf(
            PdfDraftChapter("Part 1", mutableListOf(), firstPage = 0, lastPage = 0)
        )
        reflowed.forEachIndexed { index, paragraphs ->
            for (fileName in imagesByPage[index].orEmpty()) {
                chapters.last().blocks += ChapterBlock.Image(fileName, alt = "")
                chapters.last().lastPage = index
            }
            for (paragraph in paragraphs) {
                if (PdfReflow.looksLikeHeading(paragraph) &&
                    chapters.last().hasBodyContent
                ) {
                    chapters += PdfDraftChapter(
                        paragraph,
                        mutableListOf(ChapterBlock.Heading(2, paragraph)),
                        firstPage = index, lastPage = index
                    )
                } else if (!chapters.last().hasBodyContent &&
                    PdfReflow.looksLikeHeading(paragraph)
                ) {
                    val last = chapters.last()
                    chapters[chapters.lastIndex] = PdfDraftChapter(
                        paragraph,
                        last.blocks.toMutableList().apply {
                            add(ChapterBlock.Heading(2, paragraph))
                        },
                        firstPage = last.firstPage, lastPage = index
                    )
                } else {
                    chapters.last().blocks += ChapterBlock.Paragraph.plain(paragraph)
                    chapters.last().lastPage = index
                }
            }
        }
        return chapters
    }

    /**
     * TOC coalescing (v1.5.5 §4, Amendments 2): a table of
     * contents page shatters under heading detection — every
     * "Chapter N …… 12" line looks like a chapter heading, so
     * the old splitter spawned a hollow pseudo-chapter per TOC
     * line (the owner's 42). Consecutive under-floor drafts
     * whose lines are mostly TOC lines, on the same or adjacent
     * pages, merge back into ONE "Table of Contents" section.
     * A lone TOC-shaped draft is left alone — one short chapter
     * is not evidence of a TOC.
     */
    private fun coalesceToc(
        drafts: List<PdfDraftChapter>
    ): List<PdfDraftChapter> {
        fun isTocDraft(draft: PdfDraftChapter): Boolean {
            if (draft.proseChars >= PdfBookParser.MIN_PROSE_CHARS_PER_CHAPTER) {
                return false
            }
            val lines = draft.blocks.mapNotNull { block ->
                when (block) {
                    is ChapterBlock.Heading -> block.text
                    is ChapterBlock.Paragraph -> block.text
                    else -> null
                }
            }
            if (lines.isEmpty()) return false
            val tocLines = lines.count { PdfProse.isTocLine(it) }
            return tocLines >= 1 && tocLines * 2 >= lines.size
        }
        val result = mutableListOf<PdfDraftChapter>()
        var index = 0
        while (index < drafts.size) {
            val draft = drafts[index]
            if (!isTocDraft(draft)) {
                result += draft
                index++
                continue
            }
            // Gather the run of adjacent TOC drafts.
            val run = mutableListOf(draft)
            var next = index + 1
            while (next < drafts.size && isTocDraft(drafts[next]) &&
                drafts[next].firstPage - run.last().lastPage <= 1
            ) {
                run += drafts[next]
                next++
            }
            if (run.size < 2) {
                result += draft
                index++
                continue
            }
            val blocks = mutableListOf<ChapterBlock>()
            // The TOC page's own header (a lone "Contents" over
            // the entries) assembles as a tiny draft of its own
            // on the same page — it belongs to the section, not
            // to a one-line pseudo-chapter of its own.
            val previous = result.lastOrNull()
            if (previous != null &&
                previous.firstPage == run.first().firstPage &&
                previous.proseChars < PdfBookParser.MIN_PROSE_CHARS_PER_CHAPTER &&
                !previous.frontMatterSection
            ) {
                result.removeAt(result.lastIndex)
                blocks += previous.blocks
            }
            for (member in run) {
                for (block in member.blocks) {
                    blocks += when (block) {
                        is ChapterBlock.Heading ->
                            ChapterBlock.Paragraph.plain(block.text)
                        else -> block
                    }
                }
            }
            result += PdfDraftChapter(
                title = "Table of Contents",
                blocks = blocks,
                firstPage = run.first().firstPage,
                lastPage = run.last().lastPage,
                frontMatterSection = true
            )
            index = next
        }
        return result
    }
}

/**
 * v1.5.5 (§4): what the PDF probe hands the importer — the
 * metadata the Library row is filed with, the rendered cover,
 * and the provisional chapter titles (outline titles when the
 * book carries an outline, page-range sections otherwise) the
 * provisional chapter rows are created from.
 */
data class PdfProbeResult(
    val title: String?,
    val author: String?,
    val description: String?,
    val pageCount: Int,
    val coverFile: File?,
    val provisionalTitles: List<String>,
    /** v1.5.6 (§3.4(d)): the probe's own outline with page
     * targets, carried through verbatim so in-place registration
     * can file Contents as {"page":p} targets instead of falling
     * back to section-style targets from [provisionalTitles].
     * Additive with a default: existing constructions untouched. */
    val outline: List<PdfOutlineEntry> = emptyList()
)

/**
 * PDF book parser (PLAN §4): page texts → reflowed chapters. A PDF
 * with almost no extractable text is a scan — the parser says so
 * plainly (image mode is the Phase 4 reader's job) instead of
 * importing an empty book.
 */
class PdfBookParser(private val extractor: PdfTextExtractor) : BookParser {

    override val format: BookFormat = BookFormat.PDF

    /**
     * v1.5.5 (§4): fired as each chapter file is written during
     * [parse] — the importer's progressive-commit hook. Set by
     * the completion job only; null for every direct parse.
     */
    var chapterWrittenListener: ((ParsedChapterMeta) -> Unit)? = null

    /**
     * v1.5.4 (WS-7, addendum C): the unlock retry — the same
     * parse, run through an extractor bound to the password the
     * user just entered. The password is never stored (it lives
     * only in the bound extractor for this call); a wrong one
     * comes back as the same [AppError.DrmProtected] the locked
     * file produced, so the caller can offer another try instead
     * of a dead end. An extractor that cannot unlock (a test
     * fake) also answers DrmProtected — no fake success.
     */
    fun parseWithPassword(
        staged: File,
        workDir: File,
        password: String,
        onProgress: (Float?) -> Unit = {}
    ): AppResult<ParsedBook> {
        val unlocked = extractor.withPassword(password)
            ?: return AppResult.Err(AppError.DrmProtected)
        val bound = PdfBookParser(unlocked)
        // The progressive-commit hook belongs to the parse, not
        // the instance the caller happened to hold.
        bound.chapterWrittenListener = chapterWrittenListener
        return bound.parse(staged, workDir, onProgress)
    }

    /** v1.5.5 (§4): the probe through a password-bound
     * extractor — the file-first registration of a locked book,
     * once the user has entered its password. */
    fun probeWithPassword(
        staged: File,
        workDir: File,
        password: String
    ): AppResult<PdfProbeResult> {
        val unlocked = extractor.withPassword(password)
            ?: return AppResult.Err(AppError.DrmProtected)
        return PdfBookParser(unlocked).probe(staged, workDir)
    }

    /**
     * v1.5.5 (§4): the file-first probe. ONE extractor probe
     * load (classification + page count + metadata + outline —
     * every WS-7 guarantee preserved: an owner-password file
     * probes as readable, a user-password file answers
     * [AppError.DrmProtected] and NO book is filed) plus the
     * rendered first-page cover, the cheap leg that already
     * existed. No page text is stripped here.
     */
    fun probe(staged: File, workDir: File): AppResult<PdfProbeResult> {
        val probed = when (val result = extractor.probe(staged)) {
            is AppResult.Err -> return AppResult.Err(result.error)
            is AppResult.Ok -> result.value
        }
        if (probed.pageCount <= 0) {
            return AppResult.Err(AppError.ParseFailed("PDF", "the file has no pages"))
        }
        val coverFile = runCatching { extractor.renderFirstPageCover(staged) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() && it.size <= MAX_COVER_BYTES }
            ?.let { bytes ->
                runCatching {
                    File(workDir, "cover.jpg").also { it.writeBytes(bytes) }
                }.getOrNull()
            }
        val provisionalTitles = if (probed.outline.isNotEmpty()) {
            probed.outline
                .filter { it.title.isNotBlank() }
                .sortedBy { it.pageIndex }
                .map { it.title }
                .ifEmpty { pageRangeTitles(probed.pageCount) }
        } else {
            pageRangeTitles(probed.pageCount)
        }
        return AppResult.Ok(
            PdfProbeResult(
                title = probed.title,
                author = probed.author,
                description = probed.description,
                pageCount = probed.pageCount,
                coverFile = coverFile,
                provisionalTitles = provisionalTitles,
                outline = probed.outline
            )
        )
    }

    /** Provisional structure for an outline-less book: fixed
     * page-range sections (the QuickNovel shape) — replaced by
     * the real split when the completion job lands. */
    private fun pageRangeTitles(pageCount: Int): List<String> {
        val titles = mutableListOf<String>()
        var start = 1
        while (start <= pageCount) {
            val end = minOf(start + PAGES_PER_SECTION - 1, pageCount)
            titles += if (start == end) "Page $start" else "Pages $start–$end"
            start = end + 1
        }
        return titles.ifEmpty { listOf("Part 1") }
    }

    override fun parse(
        staged: File,
        workDir: File,
        onProgress: (Float?) -> Unit
    ): AppResult<ParsedBook> {
        // v1.5.5 (§4): the completion read. Production takes the
        // extractor's ONE-load path; fakes (extractAll == null)
        // keep the per-method calls they have always modelled.
        val data = runCatching { extractor.extractAll(staged) }.getOrNull()
        val pages: List<String>
        val outline: List<PdfOutlineEntry>
        val extractedImages: List<PdfPageImage>
        val unreadableImages: Int
        val description: String?
        if (data != null) {
            pages = data.pages
            outline = data.outline
            extractedImages = data.images
            unreadableImages = data.unreadableImages
            description = data.description
        } else {
            pages = when (val extracted = extractor.extractPages(staged)) {
                is AppResult.Err -> return extracted
                is AppResult.Ok -> extracted.value
            }
            outline = runCatching { extractor.extractOutline(staged) }
                .getOrDefault(emptyList())
            // The production extractor already swallows its own
            // failures; the runCatching keeps that never-fatal
            // contract at the parser seam too (a broken image
            // walk degrades to a text-only import, never a
            // failed one).
            val imagesResult = runCatching {
                extractor.extractImagesWithLoss(staged)
            }.getOrDefault(emptyList<PdfPageImage>() to 0)
            extractedImages = imagesResult.first
            unreadableImages = imagesResult.second
            description = extractor.extractDescription(staged)
        }
        if (pages.isEmpty()) {
            return AppResult.Err(AppError.ParseFailed("PDF", "the file has no pages"))
        }

        // v1.5.2 (FIX-21): embedded illustrations, staged into
        // the book's assets dir (which the importer copies into
        // the book's content dir under the img- prefix); every
        // occurrence still gets its own block reference.
        // Placement is PAGE-ANCHORED: a page's images enter the
        // reading flow at that page's position (exact paragraph
        // anchoring is not recoverable from a PDF).
        // v1.5.5 (§4): dedupe by SOURCE hash when the extractor
        // provides one (production — the bytes were encoded
        // once, at the walk); the encoded-bytes hash remains the
        // fallback for extractors that don't.
        val assetsDir = File(workDir, "assets")
        val imagesByPage = HashMap<Int, MutableList<String>>()
        run {
            val nameByHash = HashMap<String, String>()
            for (image in extractedImages) {
                val hash = image.sourceHash ?: java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(image.pngBytes)
                    .joinToString("") { "%02x".format(it) }
                val fileName = nameByHash.getOrPut(hash) {
                    assetsDir.mkdirs()
                    val name = "pdfimg-${hash.take(16)}.png"
                    File(assetsDir, name).writeBytes(image.pngBytes)
                    name
                }
                imagesByPage.getOrPut(image.pageIndex) { mutableListOf() } += fileName
            }
        }

        // v1.5.5 (§4): chapter assembly — outline first,
        // heading detection as fallback, TOC coalescing in the
        // heading path (PdfChapterAssembly).
        val chapters = PdfChapterAssembly.assemble(pages, outline, imagesByPage)

        // v1.5.5 (§4): the scanned verdict, REPLACED. The old
        // rule averaged characters over all pages — a book
        // whose front matter, TOC and chapter titles extract
        // cleanly passed it while every body page contributed
        // nothing, and its hollow chapters imported silently.
        // Now: if NO chapter carries a floor's worth of
        // printable prose, the book's text isn't readable text
        // (a scan, or an extraction collapse) and the honest
        // scanned state fires. When some chapters DO carry
        // prose, the under-floor ones are stored, flagged and
        // COUNTED below instead of sinking the whole book.
        if (chapters.none { it.proseChars >= MIN_PROSE_CHARS_PER_CHAPTER }) {
            return AppResult.Err(
                AppError.ParseFailed(
                    "PDF",
                    "this PDF looks like scanned images — there's no text Xreader can read yet"
                )
            )
        }
        val droppedChapters = chapters.count {
            !it.meetsFloor() && !it.frontMatterSection
        }

        val writer = ChapterFileWriter(File(workDir, "chapters"))
        writer.onChapterWritten = chapterWrittenListener
        chapters.forEachIndexed { index, chapter ->
            // A draft with no blocks at all (blank pages) is not
            // a chapter; everything else is stored — including
            // under-floor chapters, whose stored heading-only
            // state the reader can now distinguish and label
            // honestly instead of the old silent skip-or-serve.
            if (chapter.blocks.isEmpty()) return@forEachIndexed
            writer.add(chapter.title, ChapterContent(chapter.blocks))
            onProgress((index + 1).toFloat() / chapters.size)
        }
        if (writer.metas.isEmpty()) {
            return AppResult.Err(AppError.ParseFailed("PDF", "no readable text was found"))
        }
        return AppResult.Ok(
            ParsedBook(
                title = null,
                author = null,
                description = description,
                language = null,
                format = BookFormat.PDF,
                coverFile = deriveCoverFile(staged, workDir, extractedImages),
                chapters = writer.metas,
                chaptersDir = File(workDir, "chapters"),
                assetsDir = assetsDir.takeIf {
                    it.list()?.isNotEmpty() == true
                },
                droppedImages = unreadableImages,
                droppedChapters = droppedChapters
            )
        )
    }

    /**
     * v1.5.2 (FIX-24): the PDF's first-page cover, hybrid
     * extract-first then bounded render:
     *  1. the DOMINANT embedded image on page 1, taken from
     *     FIX-21's already-extracted list (no second decode) —
     *     written out as the cover PNG;
     *  2. failing that, the extractor's rendered first page
     *     (Android PDFRenderer leg; fakes default to none) —
     *     written out as the cover JPEG.
     * Both legs enforce the [MAX_COVER_BYTES] bound. HONEST
     * STATES: any failure anywhere on this path returns null —
     * the book imports exactly as it did before FIX-24, just
     * without a cover. Scanned PDFs never reach here (the
     * low-text check above returns first).
     */
    private fun deriveCoverFile(
        staged: File,
        workDir: File,
        images: List<PdfPageImage>
    ): File? = runCatching {
        val extracted = dominantFirstPageImage(images)
        if (extracted != null) {
            File(workDir, "cover.png").also { it.writeBytes(extracted) }
        } else {
            val rendered = runCatching { extractor.renderFirstPageCover(staged) }
                .getOrNull()
            if (rendered != null && rendered.isNotEmpty() &&
                rendered.size <= MAX_COVER_BYTES
            ) {
                File(workDir, "cover.jpg").also { it.writeBytes(rendered) }
            } else {
                null
            }
        }
    }.getOrNull()

    companion object {
        /** Below this average the "book" is pictures, not text.
         * Kept for the record: v1.5.5 replaced the global-average
         * scanned verdict with the per-chapter floor below. */
        const val MIN_CHARS_PER_PAGE = 24

        /**
         * v1.5.5 (§4): the per-chapter PROSE FLOOR — printable
         * non-replacement characters (see [PdfProse]) in a
         * chapter's Paragraph blocks. A real chapter carries
         * thousands; the floor only asks for a paragraph's
         * worth, so short-but-real chapters (a one-paragraph
         * interlude) pass while a heading over dead pages
         * cannot.
         */
        const val MIN_PROSE_CHARS_PER_CHAPTER = 48

        /** v1.5.5 (§4): pages per provisional section for an
         * outline-less book's file-first registration. */
        const val PAGES_PER_SECTION = 10

        /** FIX-24: covers elsewhere in the app refuse anything
         * over 4 MB (SourceBookSync's cover fetch does the same);
         * a page-1 plate over the bound falls back instead of
         * being stored oversized. */
        const val MAX_COVER_BYTES = 4 * 1024 * 1024

        /** FIX-24: dominance floor — page-1 images smaller than
         * this are ornaments/logos, not a cover plate; rejecting
         * them lets the render leg draw the real first page. */
        const val MIN_COVER_BYTES = 16 * 1024

        /**
         * FIX-24 cover choice (pure, JVM-testable): among the
         * embedded images drawn on page 1 (pageIndex 0), the
         * largest by PNG byte size whose size sits inside
         * [MIN_COVER_BYTES]..[MAX_COVER_BYTES]; null when no
         * candidate qualifies (all tiny, all oversized, or page 1
         * carries no image at all). Byte size is the dominance
         * signal available at this seam — placement analysis is
         * deliberately not attempted (round-13 sift, correction 6).
         */
        fun dominantFirstPageImage(images: List<PdfPageImage>): ByteArray? =
            images.asSequence()
                .filter { it.pageIndex == 0 }
                .filter { it.pngBytes.size in MIN_COVER_BYTES..MAX_COVER_BYTES }
                .maxByOrNull { it.pngBytes.size }
                ?.pngBytes
    }
}
