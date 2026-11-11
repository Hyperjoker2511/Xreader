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
 * (eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader +
 * HttpPageLoader + DownloadPageLoader). The orchestration —
 * ready check, state transitions, requestedPage rule, loader
 * selection — is the donor's. The selection's inputs are
 * Xreader's shapes: a chapter counts as downloaded when its
 * stored ChapterPages carry a local file for every page (the M3
 * download pass's write-back), and a book counts as local when it
 * has no extension (an imported file). Those select
 * DownloadPageLoader; everything else selects PipelinePageLoader,
 * the HttpPageLoader analogue, whose page list arrives through
 * Xreader's ChapterTextLoader/BookStorage pipeline (which is also
 * how Keiyoushi-player chapters reach the reader: their content
 * is stored in the same ChapterContent shape).
 *
 * v1.5.7 (Komikku full): PipelinePageLoader now carries the donor
 * HttpPageLoader's actual loading mechanism, not just its shape:
 * a PriorityBlockingQueue drained by readerThreads workers, the
 * requested page at priority 1, the next preloadSize pages behind
 * it at priority 0, retries at priority 2 (or an instant Boost to
 * the front when readerInstantRetry is on), failed pages
 * re-queued when revisited, Ready pages whose image left the
 * cache re-queued (donor ChapterCache eviction check), and
 * aggressive mode queueing every page at chapter open. Fetched
 * bytes land in the KomikkuPageCache (donor ChapterCache
 * analogue) and the page's stream is the cache file — the tiling
 * page view decodes from that stream, never from a Coil bitmap.
 * The byte fetch itself is the pipeline's: through the owning
 * Keiyoushi source's own image request (MihonSourceAdapter
 * .pageBytes == the donor's HttpSource.getImage) wherever the
 * bridge owns the URL. Port contract kept from v1.5.6 (§5.4, M4):
 * loadPage ARMS a page (LoadPage); Ready/Error for a fetched page
 * are stamped by the view's real decode outcome via
 * onImageReady/onImageError — a worker fetch failure stamps
 * Error itself, exactly as the donor's internalLoadPage does.
 *
 * v1.5.11 (transplant, Verdict 1 — owner wholesale-copy
 * directive): the M4 Ready deferral above is CONVICTED on the
 * Keiyoushi road (the loader never stamped Ready; the only
 * stamp waited on a status emission the silent stream
 * assignment never produced — a circular wait, spinner
 * forever). That road now belongs to the transplanted
 * [HttpPageLoader] (this package): donor ChapterLoader
 * selection gives every chapter whose source is a live
 * Keiyoushi HttpSource the donor loader — own queue, lazy
 * per-page getImageUrl, DIRECT source.getImage on the Page
 * object, loader-stamped Ready at stream assignment (donor
 * HttpPageLoader.kt:224-225). The stored-record bridge chain
 * (ensuredPages → fetcher → pagesOf record → PlayerImageBridge
 * → retained-Pages) no longer serves Keiyoushi reader pages
 * at all. PipelinePageLoader remains ONLY for sources with
 * no Mihon HttpSource object to bind (declarative / JS
 * manga), and its internalLoadPage now stamps Ready the
 * donor way too; the M4 contract survives only where it was
 * ever sound — the providesPageBytes() == false road, where
 * no worker runs and the view's decode outcome is the only
 * loader there is.
 */
package app.xreader.komikku.loader

import android.graphics.BitmapFactory
import app.xreader.data.extension.player.MihonSourceAdapter
import app.xreader.domain.content.ChapterPages
import app.xreader.extensionapi.SourceChapter
import app.xreader.komikku.model.ReaderChapter
import app.xreader.komikku.model.ReaderPage
import app.xreader.komikku.setting.ReaderPreferences
import eu.kanade.tachiyomi.source.model.Page
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/** The page-source pipeline the loaders bind to. Supplied by the
 * ViewModel from AppContainer; faked in JVM proofs. */
interface ChapterPagePipeline {
    /** The stored chapter content's pages, or null if not stored. */
    suspend fun storedPages(bookId: Long, chapterId: Long): ChapterPages?

    /** Fetch-and-store through the app's real pipeline, then the
     * stored pages (ChapterTextLoader.ensureStored's contract). */
    suspend fun ensuredPages(bookId: Long, chapterId: Long): ChapterPages?

    /** Resolves a page's downloaded file name to its File. */
    fun assetFile(bookId: Long, fileName: String): File

    /**
     * v1.5.6 (§5.3, M3): a STORED chapter is about to be
     * served. On a cold process its pages' image registrations
     * (the bridge entries that route each image through its
     * owning source's request) died with the old process —
     * refresh them through the owning adapter, keyed by the
     * chapter's source identity, BEFORE the pages reach the
     * loader. The stored pages themselves are not
     * re-downloaded. Default: this pipeline has no
     * registrations to refresh.
     */
    suspend fun refreshRegistrations(bookId: Long, chapterId: Long) {}

    /**
     * v1.5.7 (Komikku full): whether this pipeline can serve raw
     * page-image bytes (the production ViewModel pipeline can;
     * JVM fakes cannot). The priority-queue workers only run
     * when bytes can actually be fetched — without a byte source
     * the v1.5.6 M4 contract stands untouched (loadPage arms,
     * the viewer's outcome stamps).
     */
    fun providesPageBytes(): Boolean = false

    /**
     * v1.5.7: one page image's bytes, fetched the donor way —
     * through the owning source's own image request when the
     * URL is bridge-owned (the donor's HttpSource.getImage),
     * otherwise through the app's session-carrying image client
     * (the same client Coil uses for covers). Null = the bytes
     * could not be served; the page takes the Error state.
     */
    suspend fun fetchImageBytes(url: String, referer: String?): ByteArray? = null

    /** v1.5.7: the directory the page-image cache lives in
     * (donor ChapterCache's disk home). Null = no disk cache;
     * fetched bytes are then streamed from memory. */
    fun pageCacheDir(): File? = null

    /**
     * v1.5.11 (transplant, Verdict 1): the donor ChapterLoader
     * binding — the LIVE Keiyoushi source for this chapter,
     * when the chapter's source is one (a MihonSourceAdapter
     * over an HttpSource, kind MANGA), together with the
     * facade chapter rebuilt from the chapter's stored row
     * (title / number / dateText / scanlator / memo — the
     * adapter restores the donor SChapter from it). When this
     * returns a binding, [ChapterLoader] selects the
     * transplanted [HttpPageLoader] and the chapter's pages
     * travel the donor road (source.getPageList →
     * source.getImage) — the stored-record pipeline is not
     * consulted for the page list at all. Default: no live
     * source binding (declarative / JS / local chapters, and
     * every JVM fake) — the pipeline road stands.
     */
    suspend fun httpSourceBinding(
        bookId: Long,
        chapterId: Long
    ): HttpSourceBinding? = null
}

/**
 * v1.5.11 (transplant): one chapter's donor binding — the
 * adapter standing in the donor HttpSource's place, and the
 * facade chapter the adapter converts back to the donor
 * SChapter at page-list time (see
 * [ChapterPagePipeline.httpSourceBinding]).
 */
class HttpSourceBinding(
    val source: MihonSourceAdapter,
    val chapter: SourceChapter
)

enum class LoaderKind { DOWNLOAD, PIPELINE, HTTP }

/** The donor's getPageLoader `when`, in Xreader inputs. */
fun selectLoaderKind(isDownloaded: Boolean, isLocalBook: Boolean): LoaderKind =
    if (isDownloaded || isLocalBook) LoaderKind.DOWNLOAD else LoaderKind.PIPELINE

/** A ChapterPages record counts as downloaded when it has pages
 * and every page already carries its downloaded file. */
fun ChapterPages.isFullyDownloaded(): Boolean =
    pages.isNotEmpty() && pages.all { it.localFile != null }

/** Maps the pipeline's ChapterPages onto the donor's ReaderPage
 * list (the seam mapping — see KomikkuPageAdapter). */
fun ChapterPages.toReaderPages(): List<ReaderPage> =
    pages.mapIndexed { index, page ->
        ReaderPage(index = index, url = page.url, imageUrl = page.url).also {
            it.localFile = page.localFile
            it.referer = referer
        }
    }

/**
 * The HttpPageLoader analogue, now with the donor's mechanism:
 * priority queue + worker threads + chapter cache + streams.
 * (Donor: eu.kanade.tachiyomi.ui.reader.loader.HttpPageLoader.)
 */
class PipelinePageLoader(
    private val bookId: Long,
    private val chapterId: Long,
    private val pipeline: ChapterPagePipeline,
    private val scope: CoroutineScope,
    private val readerPrefs: ReaderPreferences? = null
) : PageLoader() {

    override var isLocal: Boolean = false

    private val queue = PriorityBlockingQueue<PriorityPage>()
    private val queuedIndexes: MutableSet<Int> =
        Collections.synchronizedSet(mutableSetOf())
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val cache: KomikkuPageCache? by lazy {
        val dir = pipeline.pageCacheDir() ?: return@lazy null
        val sizeMb = readerPrefs?.cacheSize()?.get()?.toLongOrNull() ?: 75L
        KomikkuPageCache(dir, sizeMb * 1024 * 1024)
    }

    @Volatile
    private var chapterPages: List<ReaderPage> = emptyList()

    init {
        if (pipeline.providesPageBytes()) {
            val threads = (readerPrefs?.readerThreads()?.get() ?: 2)
                .coerceIn(1, 4)
            repeat(threads) {
                workerScope.launch {
                    while (true) {
                        val priorityPage = runInterruptible { queue.take() }
                        val page = priorityPage.page
                        if (page.status == Page.State.Queue ||
                            page.status == Page.State.LoadPage
                        ) {
                            internalLoadPage(page)
                        } else {
                            queuedIndexes.remove(page.index)
                        }
                    }
                }
            }
        }
    }

    override suspend fun getPages(): List<ReaderPage> {
        // v1.5.6 (§5.3, M3): stored-first serving refreshes
        // the chapter's page registrations through its source
        // first — on a cold process the bridge is empty and
        // the stored pages would otherwise lose their
        // source-authenticated image path.
        val stored = pipeline.storedPages(bookId, chapterId)
        val pages = if (stored != null) {
            pipeline.refreshRegistrations(bookId, chapterId)
            stored.toReaderPages()
        } else {
            pipeline.ensuredPages(bookId, chapterId)?.toReaderPages()
                ?: emptyList()
        }
        chapterPages = pages
        // Donor SY aggressive mode: queue every page at open.
        if (readerPrefs?.aggressivePageLoading()?.get() == true) {
            pages.forEach { page ->
                if (page.status == Page.State.Queue) offer(page, priority = 0)
            }
        }
        return pages
    }

    override suspend fun loadPage(page: ReaderPage) {
        // v1.5.6 (§5.4, M4): a non-blank load key is NOT a
        // loaded page. A page with nothing to load is an Error
        // at once; any other page is ARMED (LoadPage) and Ready
        // arrives only with the actual image outcome.
        if (page.loadKey.isBlank()) {
            page.status = Page.State.Error(
                IllegalStateException("Page has no image to load")
            )
            return
        }
        // Downloaded pages inside a pipeline chapter (partial
        // download write-back): the stream is the file itself.
        page.localFile?.let { fileName ->
            val file = pipeline.assetFile(bookId, fileName)
            if (file.isFile) {
                page.stream = { file.inputStream() }
                page.status = Page.State.LoadPage
                return
            }
        }
        // Donor: a Ready page whose image was evicted from the
        // cache is re-queued; a failed page re-queues on revisit.
        if (page.status == Page.State.Ready && !isImageAvailable(page)) {
            page.status = Page.State.Queue
        }
        if (page.status is Page.State.Error) {
            page.status = Page.State.Queue
        }
        if (page.status == Page.State.Queue) {
            page.status = Page.State.LoadPage
            offer(page, priority = 1)
        }
        preloadNextPages(page)
    }

    override fun retryPage(page: ReaderPage) {
        if (page.status is Page.State.Error) {
            page.status = Page.State.Queue
        }
        page.stream = null
        if (readerPrefs?.readerInstantRetry()?.get() != false &&
            pipeline.providesPageBytes()
        ) {
            // Donor EXH instant retry = Boost: front of the queue.
            offer(page, priority = 3)
        } else {
            offer(page, priority = 2)
        }
        // Port contract (§5.4 test seam): a retry re-arms the page.
        if (page.status == Page.State.Queue) {
            page.status = Page.State.LoadPage
        }
    }

    /** Donor EXH Boost: prioritise [page] in the loader queue. */
    override fun boostPage(page: ReaderPage) {
        if (page.status is Page.State.Error) {
            page.status = Page.State.Queue
        }
        if (page.status == Page.State.Queue || page.status == Page.State.LoadPage) {
            offer(page, priority = 3)
        }
    }

    /** ExhUtils Retry all: re-queue every errored page of the
     * chapter. Returns how many pages were re-queued. */
    override fun retryAllErrored(): Int {
        val errored = chapterPages.filter { it.status is Page.State.Error }
        errored.forEach { retryPage(it) }
        return errored.size
    }

    /** Whether [page]'s image can be served without a fetch:
     * downloaded file present, stream already set, or the image
     * is in the page cache (donor isImageInCache check). */
    fun isImageAvailable(page: ReaderPage): Boolean {
        page.localFile?.let {
            if (pipeline.assetFile(bookId, it).isFile) return true
        }
        if (page.stream != null) return true
        val url = page.imageUrl ?: page.url
        return cache?.isImageInCache(url) == true
    }

    /** The on-disk file backing [page]'s image, when one exists
     * (downloaded file or page-cache entry) — page actions
     * (share / save / set-as-cover) read their bytes from here. */
    fun imageFile(page: ReaderPage): File? {
        page.localFile?.let {
            val file = pipeline.assetFile(bookId, it)
            if (file.isFile) return file
        }
        val url = page.imageUrl ?: page.url
        return cache?.getImageFile(url)
    }

    private fun offer(page: ReaderPage, priority: Int) {
        if (!pipeline.providesPageBytes()) return
        if (!queuedIndexes.add(page.index)) return
        queue.offer(PriorityPage(page, priority))
    }

    /** Donor preloadNextPages: the next [preloadSize] pages
     * behind the requested one, at the lower priority 0. */
    private fun preloadNextPages(currentPage: ReaderPage) {
        val preloadSize = readerPrefs?.preloadSize()?.get() ?: 10
        if (preloadSize <= 0) return
        val pages = chapterPages
        if (pages.isEmpty()) return
        val from = currentPage.index + 1
        if (from > pages.lastIndex) return
        val to = minOf(from + preloadSize, pages.size)
        for (page in pages.subList(from, to)) {
            if (page.status == Page.State.Queue) offer(page, priority = 0)
        }
    }

    /** Donor internalLoadPage: resolve the stream (cache first,
     * source fetch second) and stamp the outcome.
     * v1.5.11 (transplant, Verdict 1 — Gemini hunk 3A's stamp,
     * kept on this non-Keiyoushi road): Ready IS stamped here,
     * immediately after the stream is assigned — the donor
     * HttpPageLoader.kt:224-225 contract. The pre-v1.5.11 M4
     * deferral (Ready waits for the view's decode outcome)
     * was a circular wait: Page.status is a StateFlow setter
     * and `stream` a plain var, so the assignment emitted
     * nothing, the viewer's status collector never re-ran,
     * and onImageReady could never fire. The M4 contract
     * stands ONLY on the providesPageBytes() == false road,
     * where this worker never runs and loadPage's arming +
     * the view's outcome stamps are the whole loader (the
     * JVM-fake road the M4 proofs bind). A fetch failure
     * stamps Error, as the donor's does. */
    private suspend fun internalLoadPage(page: ReaderPage) {
        try {
            val url = page.imageUrl ?: page.url
            val pageCache = cache
            val cachedFile = pageCache?.getImageFile(url)
            if (cachedFile != null) {
                page.stream = { cachedFile.inputStream() }
                page.status = Page.State.Ready
                stampPageDimensions(page, cachedFile)
                return
            }
            page.status = Page.State.DownloadImage
            val bytes = pipeline.fetchImageBytes(url, page.referer)
                ?: throw IOException("the page image couldn't be fetched")
            if (bytes.isEmpty()) {
                throw IOException("the page image came back empty")
            }
            page.stream = if (pageCache != null) {
                val file = pageCache.putImageToCache(url, bytes)
                ({ file.inputStream() })
            } else {
                ({ bytes.inputStream() })
            }
            page.status = Page.State.Ready
            stampPageDimensions(page, bytes)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            page.status = Page.State.Error(e)
        } finally {
            queuedIndexes.remove(page.index)
        }
    }

    override fun recycle() {
        super.recycle()
        workerScope.cancel()
        queue.clear()
        queuedIndexes.clear()
    }
}

/** Donor PriorityPage: higher priority first, FIFO within one. */
private class PriorityPage(
    val page: ReaderPage,
    val priority: Int
) : Comparable<PriorityPage> {

    private val identifier = idGenerator.incrementAndGet()

    override fun compareTo(other: PriorityPage): Int {
        val byPriority = other.priority.compareTo(priority)
        return if (byPriority != 0) byPriority else identifier.compareTo(other.identifier)
    }

    companion object {
        private val idGenerator = AtomicInteger(0)
    }
}

/**
 * v1.5.35 (item 3): record a page's pixel size on the page
 * itself the moment its bytes exist — a bounds-only decode
 * (header read, no pixel decode). Until now the ONLY writer
 * of ReaderPage.imageWidth/imageHeight was the page view's
 * onImageLoaded, so a page the reader hadn't reached yet had
 * no dimensions at all: the chapter-shape verdict (auto
 * webtoon) starved on exactly the online chapters it exists
 * for — its probe reads cache files, and unviewed pages
 * weren't in the cache yet when it ran — and webtoon items
 * couldn't reserve their true height until after they had
 * already been displayed. Best-effort by construction: a
 * failed sniff leaves the fields unknown and never fails
 * the load it rides on.
 */
internal fun stampPageDimensions(page: ReaderPage, file: File) {
    if (page.imageWidth > 0) return
    runCatching {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth > 0 && options.outHeight > 0) {
            page.imageWidth = options.outWidth
            page.imageHeight = options.outHeight
        }
    }
}

/** The in-memory twin of [stampPageDimensions] (File). */
internal fun stampPageDimensions(page: ReaderPage, bytes: ByteArray) {
    if (page.imageWidth > 0) return
    runCatching {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth > 0 && options.outHeight > 0) {
            page.imageWidth = options.outWidth
            page.imageHeight = options.outHeight
        }
    }
}

/**
 * The DownloadPageLoader analogue: a chapter whose pages are all
 * on disk. Never touches the network — a page without its file is
 * an error, not a fetch.
 */
class DownloadPageLoader(
    private val bookId: Long,
    private val chapterId: Long,
    private val pipeline: ChapterPagePipeline
) : PageLoader() {

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> =
        pipeline.storedPages(bookId, chapterId)?.toReaderPages() ?: emptyList()

    override suspend fun loadPage(page: ReaderPage) {
        page.status = Page.State.LoadPage
        val fileName = page.localFile
        val file = fileName?.let { pipeline.assetFile(bookId, it) }
        page.status = if (file != null && file.exists()) {
            page.stream = { file.inputStream() }
            Page.State.Ready
        } else {
            Page.State.Error(IllegalStateException("Downloaded page file is missing"))
        }
        if (page.status == Page.State.Ready && file != null) {
            stampPageDimensions(page, file)
        }
    }

    /** The downloaded file backing [page], for page actions. */
    fun imageFile(page: ReaderPage): File? {
        val fileName = page.localFile ?: return null
        val file = pipeline.assetFile(bookId, fileName)
        return if (file.isFile) file else null
    }
}

/**
 * Loader used to retrieve the [PageLoader] for a given chapter.
 * (Donor: ChapterLoader — orchestration ported, selection above.)
 */
class ChapterLoader(
    private val bookId: Long,
    private val isLocalBook: Boolean,
    private val pipeline: ChapterPagePipeline,
    private val readerPrefs: ReaderPreferences,
    private val scope: CoroutineScope
) {

    /**
     * Assigns the chapter's page loader and loads its pages.
     * Returns immediately if the chapter is already loaded.
     */
    suspend fun loadChapter(chapter: ReaderChapter, page: Int? = null) {
        if (chapterIsReady(chapter)) {
            return
        }

        chapter.state = ReaderChapter.State.Loading
        try {
            val loader = getPageLoader(chapter)
            chapter.pageLoader = loader

            val pages = loader.getPages()
                .onEach { it.chapter = chapter }

            if (pages.isEmpty()) {
                throw Exception("The page list is empty")
            }

            // If the chapter is partially read, set the starting page to the last the user read
            // otherwise use the requested page. (Donor rule, incl. the
            // preserveReadingPosition preference.)
            if (!chapter.read || readerPrefs.preserveReadingPosition().get() || page != null) {
                chapter.requestedPage = page ?: chapter.lastPageRead
            }

            chapter.state = ReaderChapter.State.Loaded(pages)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // v1.5.6 (§5.1, M1): cancellation is NOT an error
            // state — a load the caller abandoned (quick
            // chapter change) propagates without stamping
            // Error on the chapter.
            throw e
        } catch (e: Throwable) {
            chapter.state = ReaderChapter.State.Error(e)
            throw e
        }
    }

    /** Checks [chapter] to be loaded based on present pages and loader in addition to state. */
    private fun chapterIsReady(chapter: ReaderChapter): Boolean =
        chapter.state is ReaderChapter.State.Loaded && chapter.pageLoader != null

    /**
     * Returns the page loader to use for this [chapter] — the
     * donor ChapterLoader.getPageLoader `when`, in Xreader
     * inputs:
     *  - downloaded (every page's file on disk) or a local
     *    book → [DownloadPageLoader] (the donor's
     *    isChapterDownloaded / LocalSource arms);
     *  - v1.5.11 (transplant): a chapter whose source is a
     *    LIVE Keiyoushi HttpSource → the transplanted
     *    [HttpPageLoader] bound to that source (the donor's
     *    `source is HttpSource -> HttpPageLoader(chapter,
     *    source)` arm). The stored record is NOT consulted
     *    for its page list and the bridge registration
     *    refresh does not run — the donor road has no bridge;
     *  - anything else (declarative / JS sources — no Mihon
     *    HttpSource object exists to bind) →
     *    [PipelinePageLoader] over the stored-record pipeline.
     */
    private suspend fun getPageLoader(chapter: ReaderChapter): PageLoader {
        val stored = pipeline.storedPages(bookId, chapter.chapterId)
        val isDownloaded = stored?.isFullyDownloaded() == true
        if (selectLoaderKind(isDownloaded, isLocalBook) == LoaderKind.DOWNLOAD) {
            return DownloadPageLoader(bookId, chapter.chapterId, pipeline)
        }
        // v1.5.11 (transplant): the donor's HttpSource arm.
        val binding = pipeline.httpSourceBinding(bookId, chapter.chapterId)
        if (binding != null) {
            return HttpPageLoader(
                chapter = chapter,
                source = binding.source,
                sourceChapter = binding.chapter,
                chapterCache = pageCache(),
                readerPreferences = readerPrefs
            )
        }
        // v1.5.6 (§5.3, M3): the stored-first path refreshes
        // registrations here too (idempotent with the
        // PipelinePageLoader.getPages refresh — the pipeline
        // performs the adapter round-trip at most once per
        // chapter per process).
        if (stored != null) {
            pipeline.refreshRegistrations(bookId, chapter.chapterId)
        }
        return PipelinePageLoader(
            bookId, chapter.chapterId, pipeline, scope, readerPrefs
        )
    }

    /** The page-image cache a transplanted [HttpPageLoader]
     * binds (the same construction [PipelinePageLoader]
     * lazily builds for itself: the pipeline's cache dir at
     * the reader's cache-size budget, default 75 MB). Null
     * when the pipeline has no cache dir — fetched bytes are
     * then streamed from memory. */
    private fun pageCache(): KomikkuPageCache? {
        val dir = pipeline.pageCacheDir() ?: return null
        val sizeMb = readerPrefs.cacheSize().get()?.toLongOrNull() ?: 75L
        return KomikkuPageCache(dir, sizeMb * 1024 * 1024)
    }
}
