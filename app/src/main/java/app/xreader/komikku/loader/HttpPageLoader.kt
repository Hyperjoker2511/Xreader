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
 * v1.5.11 (transplant, Verdict 1 — owner wholesale-copy
 * directive): transplanted from Komikku
 * (eu.kanade.tachiyomi.ui.reader.loader.HttpPageLoader), the
 * donor loader the v1.5.2–v1.5.10 PipelinePageLoader only
 * imitated. The mechanism is the donor's: a
 * PriorityBlockingQueue drained by readerThreads workers on
 * an IO scope, the requested page at priority 1, the next
 * preloadSize pages behind it at priority 0, retries at
 * priority 2 (or an instant Boost to the front), failed pages
 * re-queued when revisited, Ready pages whose image left the
 * cache re-queued, aggressive mode queueing every page at
 * chapter open — and internalLoadPage resolving a missing
 * imageUrl LAZILY per page, fetching the bytes with a DIRECT
 * source.getImage(page) call, and stamping Page.State.Ready
 * itself the moment the stream is set (donor
 * HttpPageLoader.kt:224-225).
 *
 * Xreader seam adaptations (the donor's inputs, our shapes):
 *  - The donor's `source: HttpSource` is the live Mihon
 *    source's adapter ([MihonSourceAdapter]): its
 *    getPageList / getImageUrl / getImage are the donor
 *    HttpSource calls of the same names, run bracketed on
 *    the caller's IO context. There is NO stored-record
 *    round-trip, NO PlayerImageBridge registry lookup and
 *    NO retained-Pages indirection on this road — the Page
 *    object the loader holds is the object the source's
 *    image request is built from, exactly as in the donor.
 *  - The donor's `chapter: ReaderChapter` carries its
 *    SChapter; ours carries Room ids, so the facade chapter
 *    rebuilt from the stored row rides beside it
 *    ([sourceChapter]) and the adapter restores the donor
 *    SChapter (memo included) from it.
 *  - The donor ChapterCache is [KomikkuPageCache] (its
 *    Xreader analogue, image files only). The donor's
 *    page-LIST cache (getPageListFromCache / the recycle-time
 *    putPageListToCache) is NOT reproduced: KomikkuPageCache
 *    documents that omission from its first version, and a
 *    page list here is one source call at chapter open.
 *  - loadPage ARMS and returns (the Xreader viewer's
 *    contract: DonorReaderView launches it per holder bind),
 *    where the donor's loadPage parks its caller until
 *    cancellation to dequeue. Queue discipline, priorities
 *    and outcomes are the donor's either way.
 *  - The donor's EXH/DataSaver image variants and its
 *    EH-source retry URL regrab have no Xreader counterpart
 *    and are not reproduced.
 */
package app.xreader.komikku.loader

import app.xreader.data.extension.player.MihonSourceAdapter
import app.xreader.extensionapi.SourceChapter
import app.xreader.komikku.model.ReaderChapter
import app.xreader.komikku.model.ReaderPage
import app.xreader.komikku.setting.ReaderPreferences
import eu.kanade.tachiyomi.source.model.Page
import java.io.File
import java.util.Collections
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/**
 * Loader used to load chapters from an online source — the
 * donor HttpPageLoader, bound to a live Keiyoushi source
 * through its adapter (see the file header for the seam
 * adaptations).
 */
class HttpPageLoader(
    private val chapter: ReaderChapter,
    private val source: MihonSourceAdapter,
    private val sourceChapter: SourceChapter,
    private val chapterCache: KomikkuPageCache? = null,
    private val readerPreferences: ReaderPreferences? = null
) : PageLoader() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * A queue used to manage requests one by one while allowing priorities.
     */
    private val queue = PriorityBlockingQueue<PriorityPage>()
    private val queuedIndexes: MutableSet<Int> =
        Collections.synchronizedSet(mutableSetOf())

    private val preloadSize: Int
        get() = readerPreferences?.preloadSize()?.get() ?: 10

    init {
        // Donor: readerThreads workers drain the queue on the
        // loader's IO scope (the count is Xreader-clamped to
        // the range PipelinePageLoader already proved on the
        // owner's devices).
        val threads = (readerPreferences?.readerThreads()?.get() ?: 2)
            .coerceIn(1, 4)
        repeat(threads) {
            scope.launch {
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

    override var isLocal: Boolean = false

    /**
     * Returns the page list for a chapter, from the source
     * directly (the donor's network arm — Xreader reproduces
     * no page-list cache; see the file header).
     */
    override suspend fun getPages(): List<ReaderPage> {
        val pages = source.getPageList(sourceChapter)
        // Donor: don't trust sources and use our own indexing.
        // v1.5.12 (M3, Lane 6 AT-RISK 1): page.uri rides the
        // re-creation — pre-v1.5.12 this mapping dropped it
        // (ReaderPage passed uri=null), so an extension whose
        // getImageUrl/getImage reads page.uri lost it on this
        // road.
        val rp = pages.mapIndexed { index, page ->
            ReaderPage(index, page.url, page.imageUrl, page.uri).also {
                it.referer = referer()
            }
        }
        // SY aggressive mode: queue every page at chapter open.
        if (readerPreferences?.aggressivePageLoading()?.get() == true) {
            rp.forEach {
                if (it.status == Page.State.Queue) {
                    offer(it, priority = 0)
                }
            }
        }
        return rp
    }

    /** The Referer the chapter's page images expect — the
     * source's own base URL, the same stamp the adapter puts
     * on its ChapterPages records (K6). */
    private fun referer(): String? =
        source.baseUrl.takeIf { it.isNotBlank() }?.let {
            if (it.endsWith("/")) it else "$it/"
        }

    /**
     * Loads a page through the queue. Handles re-enqueueing
     * pages if they were evicted from the cache. (Xreader
     * contract: this arms the page and returns; the donor's
     * caller-parking variant is not reproduced — see the file
     * header.)
     */
    override suspend fun loadPage(page: ReaderPage) {
        // Check if the image has been deleted (donor: a Ready
        // page whose image left the cache is re-queued).
        if (page.status == Page.State.Ready && !isImageAvailable(page)) {
            page.status = Page.State.Queue
        }

        // Automatically retry failed pages when subscribed to
        // this page (donor).
        if (page.status is Page.State.Error) {
            page.status = Page.State.Queue
        }

        if (page.status == Page.State.Queue) {
            page.status = Page.State.LoadPage
            offer(page, priority = 1)
        }
        preloadNextPages(page, preloadSize)
    }

    /**
     * Retries a page. This method is only called from user interaction on the viewer.
     */
    override fun retryPage(page: ReaderPage) {
        if (page.status is Page.State.Error) {
            page.status = Page.State.Queue
        }
        page.stream = null
        if (readerPreferences?.readerInstantRetry()?.get() != false) {
            // Donor EXH instant retry = Boost: front of the queue.
            boostPage(page)
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
        if (page.status == Page.State.Queue ||
            page.status == Page.State.LoadPage
        ) {
            offer(page, priority = 3)
        }
    }

    /** ExhUtils Retry all: re-queue every errored page of the
     * chapter. Returns how many pages were re-queued. */
    override fun retryAllErrored(): Int {
        val errored = chapter.pages?.filter { it.status is Page.State.Error }
            ?: emptyList()
        errored.forEach { retryPage(it) }
        return errored.size
    }

    override fun recycle() {
        super.recycle()
        scope.cancel()
        queue.clear()
        queuedIndexes.clear()
    }

    /** Whether [page]'s image can be served without a fetch:
     * stream already set, or the image is in the page cache
     * (donor isImageInCache check). */
    fun isImageAvailable(page: ReaderPage): Boolean {
        if (page.stream != null) return true
        val url = page.imageUrl ?: return false
        return chapterCache?.isImageInCache(url) == true
    }

    /** The on-disk file backing [page]'s image, when one
     * exists in the page cache — page actions (share / save /
     * set-as-cover) read their bytes from here. */
    fun imageFile(page: ReaderPage): File? {
        val url = page.imageUrl ?: page.url
        return chapterCache?.getImageFile(url)
    }

    private fun offer(page: ReaderPage, priority: Int) {
        if (!queuedIndexes.add(page.index)) return
        queue.offer(PriorityPage(page, priority))
    }

    /**
     * Preloads the given [amount] of pages after the
     * [currentPage] with a lower priority.
     */
    private fun preloadNextPages(currentPage: ReaderPage, amount: Int) {
        if (amount <= 0) return
        val pageIndex = currentPage.index
        // Donor reads currentPage.chapter.pages; the chapter
        // back-reference is lateinit in Xreader's ReaderPage,
        // so an unbound page (a direct loader proof) reads as
        // "no pages to preload" instead of throwing.
        val pages = runCatching { currentPage.chapter.pages }
            .getOrNull() ?: return
        if (pageIndex == pages.lastIndex) return
        pages.subList(
            pageIndex + 1,
            minOf(pageIndex + 1 + amount, pages.size)
        ).forEach {
            if (it.status == Page.State.Queue) {
                offer(it, priority = 0)
            }
        }
    }

    /**
     * Loads the page, retrieving the image URL and
     * downloading the image if necessary. Downloaded images
     * are stored in the chapter cache. (Donor internalLoadPage,
     * verbatim in shape: lazy imageUrl, direct getImage,
     * stream from the cache file, Ready stamped by the
     * LOADER at stream assignment.)
     *
     * @param page the page whose source image has to be downloaded.
     */
    private suspend fun internalLoadPage(page: ReaderPage) {
        try {
            // A page whose image address is already known AND
            // whose bytes are already in the cache needs no
            // source call at all — and pays no session bracket
            // (the pre-v1.5.12 shape of this path, preserved).
            val knownUrl = page.imageUrl?.takeIf { it.isNotEmpty() }
            val knownCached = knownUrl?.let { chapterCache?.getImageFile(it) }
            if (knownCached != null) {
                page.stream = { knownCached.inputStream() }
                page.status = Page.State.Ready
                // v1.5.35 (item 3): dimensions ride the load
                // (see ChapterLoader.stampPageDimensions).
                stampPageDimensions(page, knownCached)
                return
            }
            // v1.5.12 (M5, Lane 6 AT-RISK 3): the lazy
            // getImageUrl + getImage pair for this page runs
            // inside ONE page-load bracket — one
            // PlayerWebViewSync seed/harvest for the page,
            // not one per call (the adapter's per-call
            // brackets detect the bracket in force and no-op).
            source.withPageLoadBracket {
                if (page.imageUrl.isNullOrEmpty()) {
                    page.status = Page.State.LoadPage
                    page.imageUrl = source.getImageUrl(page)
                }
                val imageUrl = page.imageUrl!!

                val pageCache = chapterCache
                val cachedFile = pageCache?.getImageFile(imageUrl)
                if (cachedFile != null) {
                    page.stream = { cachedFile.inputStream() }
                    page.status = Page.State.Ready
                    // v1.5.35 (item 3): dimensions ride the load.
                    stampPageDimensions(page, cachedFile)
                } else {
                    page.status = Page.State.DownloadImage
                    val bytes = source.getImage(page)
                    page.stream = if (pageCache != null) {
                        val file = pageCache.putImageToCache(imageUrl, bytes)
                        ({ file.inputStream() })
                    } else {
                        // No disk cache (JVM proofs): the donor streams
                        // from its cache file; ours streams the bytes
                        // it just fetched — same Ready contract.
                        ({ bytes.inputStream() })
                    }
                    page.status = Page.State.Ready
                    // v1.5.35 (item 3): dimensions ride the load.
                    stampPageDimensions(page, bytes)
                }
            }
        } catch (e: Throwable) {
            page.status = Page.State.Error(e)
            if (e is kotlinx.coroutines.CancellationException) {
                throw e
            }
        } finally {
            queuedIndexes.remove(page.index)
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
            return if (byPriority != 0) {
                byPriority
            } else {
                identifier.compareTo(other.identifier)
            }
        }

        companion object {
            private val idGenerator = AtomicInteger(0)
        }
    }
}
