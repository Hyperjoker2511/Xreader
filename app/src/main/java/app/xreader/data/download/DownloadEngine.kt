package app.xreader.data.download

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.core.error.ErrorText
import app.xreader.data.db.BookDao
import app.xreader.data.db.ChapterDao
import app.xreader.data.db.ChapterSourceMapDao
import app.xreader.data.db.DownloadKind
import app.xreader.data.db.DownloadQueueItem
import app.xreader.data.db.DownloadState
import app.xreader.data.db.QueueState
import app.xreader.data.files.BookStorage
import app.xreader.data.net.HttpTransport
import app.xreader.data.parse.TxtParser
import app.xreader.data.parse.XhtmlConverter
import app.xreader.domain.content.ChapterContent
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Fetches a chapter from a URL (Phase 2 alternates): HTML pages go
 * through the shared converter (scripts/styles never reach the
 * content model), anything else is treated as plain text.
 *
 * v1.0.6.2 (Gemini fix round, G4): HTML chapters keep their images.
 * When [storage] is set and the candidate names its book, the
 * page's images are downloaded through the same transport (same
 * auth as the text fetch) into the book's content dir — the same
 * `img-<name>` layout the local HTML import produces and the
 * reader renders — and the converter resolves each <img> to its
 * stored file. Every image failure is per-image: skip that image,
 * keep the text. Without storage (older constructions, most
 * tests) the fetcher behaves exactly as before.
 */
class UrlChapterFetcher(
    private val transport: HttpTransport,
    private val basicAuth: Pair<String, String>? = null,
    private val storage: BookStorage? = null
) : ChapterFetcher {

    override suspend fun fetch(candidate: ChapterCandidate): FetchOutcome {
        val response = when (val result = transport.execute(
            HttpTransport.Request(url = candidate.locator, basicAuth = basicAuth)
        )) {
            is AppResult.Err -> return FetchOutcome.Failure(result.error)
            is AppResult.Ok -> result.value
        }
        val retryAfter = response.retryAfterSeconds
        if (response.code !in 200..299) {
            response.body?.close()
            return FetchOutcome.Failure(HttpTransport.statusError(response.code), retryAfter)
        }
        val bytes = response.body?.use { it.readBytes() }
            ?: return FetchOutcome.Failure(AppError.Unknown(IllegalStateException("empty body")))
        val text = bytes.toString(Charsets.UTF_8)
        val content = if (looksLikeHtml(text)) {
            val imageNames = downloadImages(text, candidate)
            val converted = XhtmlConverter { src ->
                resolveImageKey(src, candidate.locator)
                    ?.let { key -> imageNames[key] }
            }.convert(text.byteInputStream(Charsets.UTF_8))
            ChapterContent(converted.blocks)
        } else {
            TxtParser.textToContent(text)
        }
        return if (content.blocks.isEmpty()) {
            FetchOutcome.Failure(AppError.ParseFailed("chapter", "the page had no readable text"))
        } else {
            FetchOutcome.Success(content)
        }
    }

    private fun looksLikeHtml(text: String): Boolean {
        val head = text.trimStart().take(512).lowercase()
        return head.startsWith("<!doctype html") || head.startsWith("<html") ||
            head.contains("<body") || head.contains("<p>") || head.contains("<div")
    }

    /**
     * Downloads the page's images into the book's content dir;
     * returns resolved-URL → stored file name (the bare name the
     * content model carries; the reader prefixes `img-`). Mirrors
     * the local HTML import's resolution: srcs resolved against
     * the chapter URL, `data:` and non-web schemes skipped. Every
     * failure is per-image — a dead image never sinks the text.
     */
    private suspend fun downloadImages(
        html: String, candidate: ChapterCandidate
    ): Map<String, String> {
        val store = storage ?: return emptyMap()
        if (candidate.bookId <= 0) return emptyMap()
        val doc = runCatching {
            org.jsoup.Jsoup.parse(html, candidate.locator)
        }.getOrNull() ?: return emptyMap()
        val urls = doc.select("img[src]").mapNotNull { element ->
            element.absUrl("src").takeIf { it.isNotBlank() }
        }.distinct().take(MAX_CHAPTER_IMAGES)
        if (urls.isEmpty()) return emptyMap()
        val contentDir = store.contentDir(candidate.bookId)
        // v1.2.0 (mirrors the extension resolver's image fix):
        // stored names are assigned FIRST, in page order, on this
        // thread — assetFileName dedupes against usedNames, so
        // every image's name is fixed before any fetch starts and
        // cannot depend on the order parallel fetches finish in.
        val usedNames = mutableSetOf<String>()
        data class ImageJob(val url: String, val name: String, val target: File)
        val jobs = urls.mapNotNull { url ->
            val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
            if (uri.scheme != "http" && uri.scheme != "https") return@mapNotNull null
            val name = app.xreader.data.parse.ZipPaths.assetFileName(
                uri.path ?: url, usedNames
            )
            ImageJob(url = url, name = name, target = File(contentDir, "img-$name"))
        }
        if (jobs.isEmpty()) return emptyMap()
        // (a) An image already on disk (a re-read / resumed
        // chapter) is kept and never re-fetched. (b) The rest
        // fetch bounded-parallel — 4 in flight, the same shape as
        // the extension path — not one sequential round-trip per
        // image. (c) Every failure is per-image: skip that image,
        // keep the others and the text. There is no lighter local
        // fetch path in this class (the extension fetcher's
        // session bytes have no equivalent here), so fetches keep
        // going through the same transport as the chapter text.
        val gate = Semaphore(MAX_PARALLEL_IMAGES)
        val fetched: List<Pair<String, String>?> = coroutineScope {
            jobs.map { job ->
                async {
                    if (job.target.isFile && job.target.length() > 0) {
                        job.url to job.name
                    } else {
                        gate.withPermit {
                            try {
                                val bytes = fetchImageBytes(job.url)
                                if (bytes != null && writeBytesAtomic(job.target, bytes)) {
                                    job.url to job.name
                                } else {
                                    null
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        // (d) Results merge back in page order, not completion
        // order, so the map the converter reads is deterministic.
        val stored = LinkedHashMap<String, String>()
        for (result in fetched) {
            if (result != null) stored[result.first] = result.second
        }
        return stored
    }

    /** One image through the same transport (and auth) as the
     * chapter text; null on any failure — the caller skips it. */
    private suspend fun fetchImageBytes(url: String): ByteArray? {
        val response = when (
            val result = transport.execute(
                HttpTransport.Request(url = url, basicAuth = basicAuth)
            )
        ) {
            is AppResult.Err -> return null
            is AppResult.Ok -> result.value
        }
        if (response.code !in 200..299) {
            response.body?.close()
            return null
        }
        return runCatching { response.body?.use { it.readBytes() } }.getOrNull()
    }

    /** The converter hands over the raw src attribute; resolve it
     * against the chapter URL exactly as the download pass's
     * jsoup resolution did, so the stored-name lookup hits. */
    private fun resolveImageKey(src: String, base: String): String? {
        val clean = src.substringBefore('#')
        if (clean.startsWith("data:", ignoreCase = true)) return null
        return runCatching { URI(base).resolve(clean).toString() }.getOrNull()
    }

    private fun writeBytesAtomic(target: File, bytes: ByteArray): Boolean =
        runCatching {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            true
        }.getOrDefault(false)

    companion object {
        /** A chapter page's images, capped — a gallery page must
         * not turn one chapter fetch into an unbounded download. */
        const val MAX_CHAPTER_IMAGES = 50

        /** v1.2.0: image fetches in flight at once within one
         * chapter (bounded-parallel, mirrors the extension path). */
        const val MAX_PARALLEL_IMAGES = 4
    }
}

/**
 * v1.2.0 (audit finding 1 / D17): decides when a streaming
 * download's progress is worth a queue-row touch. Raw read ticks
 * arrive per buffer (tens of thousands per file); a queue-row
 * write re-emits the whole-queue Flow into the Library and
 * Downloads combines, so progress is persisted only on a
 * whole-percent change or after ~500 ms, whichever comes first —
 * and ALWAYS at 100%, so the terminal state the UI reads is
 * exact. Pure and clock-injected: unit-tested without delays.
 */
class DownloadProgressCoalescer(
    private val clock: () -> Long = System::currentTimeMillis,
    private val minIntervalMs: Long = 500L
) {
    private var lastPercent = -1
    private var lastPersistAtMs: Long? = null

    /** True (once) per persist-worthy progress point. */
    fun shouldPersist(downloaded: Long, total: Long): Boolean {
        val now = clock()
        if (total > 0 && downloaded >= total) {
            lastPercent = 100
            lastPersistAtMs = now
            return true
        }
        val percent = if (total > 0) {
            ((downloaded.coerceAtLeast(0) * 100) / total).toInt()
        } else {
            -1
        }
        val percentChanged = percent >= 0 && percent != lastPercent
        val lastAt = lastPersistAtMs
        val intervalElapsed = lastAt == null || now - lastAt >= minIntervalMs
        if (!percentChanged && !intervalElapsed) return false
        if (percent >= 0) lastPercent = percent
        lastPersistAtMs = now
        return true
    }
}

/**
 * The QuickNovel bulk-download engine (PLAN §4): drains the Room
 * queue with bounded concurrency (global + per-host), exponential
 * backoff with jitter (Retry-After honoured), Range-resume for
 * whole-book file downloads, multi-source failover for chapters, and
 * a Wi-Fi gate for prefetch work. All side effects go through
 * injected interfaces, so the whole engine is unit-tested against
 * fakes — no real network, no real clock, no real delays in tests.
 *
 * Queue rows are the single source of truth: the Downloads screen
 * renders them, pause/cancel write them, the engine reads them
 * between attempts and between file chunks. Where a download comes
 * FROM lives on the rows it serves (a chapter's `remoteId`, a book's
 * `remoteId` for OPDS acquisitions) — the schema poured in Phase 1.
 *
 * Item shapes:
 *  - kind CHAPTER + chapterId → one chapter (failover applies)
 *  - kind CHAPTER + no chapterId → whole-book file (OPDS acquisition):
 *    streamed to a part file, then handed to [fileDownloaded] (import)
 *  - kind PREFETCH → fans out into CHAPTER items for the next
 *    undownloaded chapters of the book
 */
class DownloadEngine(
    private val queueDao: app.xreader.data.db.DownloadQueueDao,
    private val chapterDao: ChapterDao,
    private val bookDao: BookDao,
    private val sourceMapDao: ChapterSourceMapDao,
    private val storage: BookStorage,
    private val transport: HttpTransport,
    private val resolverForItem: (DownloadQueueItem) -> ChapterSourceResolver,
    private val networkGate: NetworkGate,
    private val settingsProvider: suspend () -> DownloadSettings,
    private val fileDownloaded: suspend (DownloadQueueItem, File) -> AppError?,
    private val basicAuthForBook: suspend (Long) -> Pair<String, String>? = { null },
    /** Phase 5: fired after a chapter's content lands (LoreKeeper's
     * incremental extraction trigger). Default no-op; best-effort —
     * a failing hook never fails a download. */
    private val onChapterDownloaded: suspend (bookId: Long, chapterId: Long) -> Unit =
        { _, _ -> },
    /**
     * v1.0.6 (Q4/W5.2): the browser-view download gate. Returns a
     * plain-words note when this item must WAIT (its source reads
     * through the browser view and the app isn't open — rendering
     * never happens in the background), null when it may proceed.
     * A gated item stays QUEUED with the note on it, exactly like
     * the Wi-Fi gate below; the container wires the real check.
     */
    private val browserDownloadDeferral: suspend (DownloadQueueItem) -> String? =
        { null },
    private val delayer: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val jitter: () -> Double = { 0.8 + Math.random() * 0.4 },
    private val maxConcurrent: Int = 4,
    private val maxPerHost: Int = 2,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val hostSemaphores = mutableMapOf<String, Semaphore>()
    private val hostMutex = Mutex()

    /** Backoff for [failedAttempts] (1-based): 1s, 2s, 4s… capped 30s, jittered. */
    fun backoffMillis(failedAttempts: Int, retryAfterSeconds: Long? = null): Long {
        val base = minOf(MAX_BACKOFF_MS, BASE_BACKOFF_MS shl (failedAttempts - 1).coerceIn(0, 5))
        val jittered = (base * jitter()).toLong()
        val hinted = (retryAfterSeconds ?: 0) * 1000
        return maxOf(jittered, minOf(hinted, MAX_RETRY_AFTER_MS))
    }

    /** Failures waiting cannot heal (gone, refused, unparsable, DRM). */
    fun isPermanent(error: AppError): Boolean = when (error) {
        is AppError.AuthFailed, AppError.DrmProtected,
        is AppError.BadFile, is AppError.ParseFailed -> true
        is AppError.ProviderDown -> !error.retryable
        else -> false
    }

    /** Drains the queue until no QUEUED items remain. Safe to call
     * repeatedly; a crashed run's RUNNING rows are re-queued first.
     * Items the Wi-Fi gate defers are remembered for this run — a
     * gated item must never spin the drain loop (it waits for the
     * next enqueue / network change instead). */
    suspend fun runQueue() {
        // v1.2.0 (audit D5): prune DONE rows at drain START. The
        // queue was append-only — one permanent DONE row per
        // chapter ever downloaded, re-loaded whole on every
        // emission. Start-of-drain is the safer point: rows this
        // drain completes stay visible on the Downloads screen
        // (its Finished grouping reads them) until the NEXT drain
        // begins, and a drain that never runs again leaves at
        // most one drain's worth of DONE rows behind. FAILED /
        // PAUSED / CANCELLED rows are never pruned here — they
        // carry the retry affordance and the error text.
        try {
            queueDao.observeAll().first()
                .filter { it.state == QueueState.DONE }
                .forEach { queueDao.delete(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Pruning is housekeeping: a failure here must never
            // stop the drain the user is waiting for.
        }
        queueDao.resetRunningToQueued()
        val global = Semaphore(maxConcurrent)
        val deferred = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
        while (true) {
            val batch = queueDao.nextQueued(BATCH_SIZE).filter { it.id !in deferred }
            if (batch.isEmpty()) return
            coroutineScope {
                batch.map { item ->
                    async {
                        global.withPermit {
                            val host = hostOf(locatorHint(item))
                            val handled = if (host != null) {
                                hostSemaphore(host).withPermit { process(item.id) }
                            } else {
                                process(item.id)
                            }
                            if (!handled) deferred += item.id
                        }
                    }
                }.awaitAll()
            }
        }
    }

    /** Best-effort host for the per-host cap: the book's remoteId for
     * acquisitions; chapter items resolve inside [process]. */
    private suspend fun locatorHint(item: DownloadQueueItem): String? {
        if (item.chapterId == null && item.kind == DownloadKind.CHAPTER) {
            return bookDao.getById(item.bookId)?.remoteId
        }
        return null
    }

    private suspend fun hostSemaphore(host: String): Semaphore = hostMutex.withLock {
        hostSemaphores.getOrPut(host) { Semaphore(maxPerHost) }
    }

    private fun hostOf(url: String?): String? =
        url?.let { runCatching { URI(it).host }.getOrNull() }

    /** Returns false when the item was deferred untouched (Wi-Fi gate). */
    private suspend fun process(itemId: Long): Boolean {
        var item = queueDao.getById(itemId) ?: return true
        if (item.state != QueueState.QUEUED) return true

        // Wi-Fi gate: prefetch waits for an unmetered network (PLAN §4).
        if (item.kind == DownloadKind.PREFETCH &&
            settingsProvider().wifiOnlyPrefetch && !networkGate.onUnmeteredNetwork()
        ) {
            return false // stays QUEUED; re-run happens on the next enqueue
        }

        // Browser-view gate (v1.0.6, Q4): a browser source's
        // downloads run only while the app is open. The item stays
        // QUEUED and carries the reason on its row, so Downloads
        // shows why it's waiting instead of a silent stall.
        val deferralNote = browserDownloadDeferral(item)
        if (deferralNote != null) {
            queueDao.update(
                item.copy(lastErrorDetail = deferralNote, updatedAt = clock())
            )
            return false
        }

        item = item.copy(state = QueueState.RUNNING, lastErrorDetail = null, updatedAt = clock())
        queueDao.update(item)

        try {
            when (item.kind) {
                DownloadKind.PREFETCH -> expandPrefetch(item)
                DownloadKind.CHAPTER ->
                    if (item.chapterId != null) processChapterItem(item) else processFileItem(item)
                DownloadKind.EXPORT -> markFailed(
                    item, AppError.BadFile("exports run from the book page, not the queue")
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            markFailed(item, AppError.Unknown(e))
        }
        return true
    }

    // -- chapters -------------------------------------------------------------

    private suspend fun processChapterItem(item: DownloadQueueItem) {
        val chapterId = item.chapterId ?: return
        val chapter = chapterDao.getById(chapterId)
            ?: return markFailed(item, AppError.BadFile("this chapter is no longer in the book"))
        // v1.0.6.2 (D3): a chapter already on the phone is never
        // fetched again — a duplicate or resumed item for a
        // finished chapter completes where it stands. This is
        // what makes Download-all and Resume strictly
        // continue-where-it-stopped at the engine level, not
        // just at the enqueue level.
        // v1.0.7.1 (the user's rule): the same holds for a
        // chapter held as a READING CACHE (read online, never
        // downloaded) — tapping Download PROMOTES it where it
        // stands: state becomes READY, no fetch. Downloaded
        // means the user asked, and only that.
        if (chapter.contentPath != null) {
            if (chapter.downloadState != DownloadState.READY) {
                chapterDao.setDownloadState(
                    chapterId, DownloadState.READY
                )
            }
            queueDao.update(
                item.copy(
                    state = QueueState.DONE,
                    lastErrorDetail = null,
                    updatedAt = clock()
                )
            )
            return
        }
        chapterDao.setDownloadState(chapterId, DownloadState.DOWNLOADING)

        val candidates = resolverForItem(item).candidatesFor(chapter)
        if (candidates.isEmpty()) {
            chapterDao.setDownloadState(chapterId, DownloadState.FAILED)
            return markFailed(item, AppError.BadFile("no source is recorded for this chapter"))
        }

        // v1.2.0 (audit D14): chapter downloads DO fan out (the
        // runQueue batch runs items concurrently under the global
        // semaphore), so the per-host cap applies here too — the
        // host comes from the first candidate's locator, the host
        // the attempts below will actually hit first. A locator
        // that isn't a URL (an extension source id) has no host to
        // cap by; the global cap still applies to those.
        val host = hostOf(urlPart(candidates.first().candidate.locator))
        if (host != null) {
            hostSemaphore(host).withPermit {
                runChapterAttempts(item, chapterId, candidates)
            }
        } else {
            runChapterAttempts(item, chapterId, candidates)
        }
    }

    /** Alternate-source locators may carry a "Label|" prefix
     * (see FailoverOrder); the URL is the part after it. A locator
     * without a prefix passes through unchanged. */
    private fun urlPart(locator: String): String =
        if (locator.contains("://")) {
            locator.substringAfterLast('|')
        } else {
            locator
        }

    private suspend fun runChapterAttempts(
        item: DownloadQueueItem,
        chapterId: Long,
        candidates: List<ResolvedSource>
    ) {
        var current = item
        var lastError: AppError = AppError.ProviderDown("server")
        var attempt = current.attempts
        var retryAfter: Long? = null
        while (attempt < MAX_ATTEMPTS) {
            val rowState = queueDao.getById(current.id)?.state
            if (rowState != QueueState.RUNNING) {
                // Paused by the user: put the chapter back to a clean
                // state. Cancel cleans up after itself in the controller.
                if (rowState == QueueState.PAUSED) {
                    chapterDao.setDownloadState(chapterId, DownloadState.NONE)
                }
                return
            }
            var roundFailedPermanently = false
            for (source in candidates) {
                if (attempt >= MAX_ATTEMPTS) break
                attempt++
                // v1.2.0 (audit finding 1 / D17): the attempt
                // counter is NOT written per attempt any more —
                // each write re-emits the whole-queue Flow into
                // every Downloads/Library combine. The count is
                // carried in memory and persisted at round
                // boundaries (below, before the backoff delay)
                // and in every terminal write (DONE / FAILED),
                // so the final row is exact while a multi-
                // candidate round costs one write, not N.
                current = current.copy(attempts = attempt, updatedAt = clock())
                when (val outcome = source.fetcher.fetch(source.candidate)) {
                    is FetchOutcome.Success -> {
                        // A pause landing mid-flight wins over this
                        // success: leave the item PAUSED (the content
                        // fetch is idempotent; resume refetches).
                        if (queueDao.getById(current.id)?.state != QueueState.RUNNING) {
                            chapterDao.setDownloadState(chapterId, DownloadState.NONE)
                            return
                        }
                        val saved = storage.writeChapterContent(
                            item.bookId, chapterId, outcome.content
                        )
                        val relativePath = when (saved) {
                            is AppResult.Err -> {
                                chapterDao.setDownloadState(chapterId, DownloadState.FAILED)
                                return markFailed(current, saved.error)
                            }
                            is AppResult.Ok -> saved.value
                        }
                        chapterDao.setContent(
                            chapterId, relativePath,
                            outcome.content.wordCount, DownloadState.READY
                        )
                        try {
                            onChapterDownloaded(item.bookId, chapterId)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Best-effort hook (Phase 5 lore extraction):
                            // its failure is never the download's failure.
                        }
                        if (source.candidate.mapId > 0) {
                            runCatching { sourceMapDao.markVerified(source.candidate.mapId) }
                        }
                        queueDao.update(
                            current.copy(
                                state = QueueState.DONE,
                                lastErrorDetail = null, updatedAt = clock()
                            )
                        )
                        return
                    }
                    is FetchOutcome.Failure -> {
                        lastError = outcome.error
                        retryAfter = outcome.retryAfterSeconds
                            ?: (outcome.error as? AppError.RateLimited)?.retryAfterSeconds
                        if (isPermanent(outcome.error)) roundFailedPermanently = true
                    }
                }
            }
            if (roundFailedPermanently && candidates.size == 1) break
            if (attempt < MAX_ATTEMPTS) {
                // Round-boundary persist (see above): a process
                // death during the backoff loses at most the
                // in-flight round's count, never the download.
                // Guarded by a fresh row read: a pause/cancel that
                // landed mid-round must NOT be overwritten by this
                // stale RUNNING copy — when the row is no longer
                // RUNNING we skip the write and the delay, and the
                // loop-top check on the next pass handles it.
                if (queueDao.getById(current.id)?.state == QueueState.RUNNING) {
                    queueDao.update(current)
                    delayer(backoffMillis(attempt, retryAfter))
                }
            }
        }
        chapterDao.setDownloadState(chapterId, DownloadState.FAILED)
        markFailed(current, lastError)
    }

    /** A prefetch item fans out into per-chapter items for the next
     * undownloaded chapters that actually have a source, then completes. */
    private suspend fun expandPrefetch(item: DownloadQueueItem) {
        val chapters = chapterDao.forBook(item.bookId)
        val wanted = chapters
            .filter {
                it.downloadState == DownloadState.NONE && !it.remoteId.isNullOrBlank()
            }
            .take(PREFETCH_COUNT)
        val now = clock()
        // v1.2.0 (audit D5/D6): one batch insert, not a per-chapter
        // loop — same shape as DownloadController.enqueueBook.
        if (wanted.isNotEmpty()) {
            queueDao.insertAll(wanted.map { chapter ->
                DownloadQueueItem(
                    bookId = item.bookId,
                    chapterId = chapter.id,
                    kind = DownloadKind.CHAPTER,
                    enqueuedAt = now,
                    updatedAt = now
                )
            })
        }
        queueDao.update(item.copy(state = QueueState.DONE, updatedAt = clock()))
    }

    // -- whole-book files (OPDS acquisitions) -----------------------------------

    private suspend fun processFileItem(item: DownloadQueueItem) {
        val book = bookDao.getById(item.bookId)
        val url = book?.remoteId
        if (url.isNullOrBlank()) {
            return markFailed(item, AppError.BadFile("this download has no address anymore"))
        }
        val dest = File(storage.downloadsTempDir(), "queue-${item.id}.part")
        var current = item
        var attempt = current.attempts
        var lastError: AppError = AppError.ProviderDown("server")
        // v1.2.0 (audit finding 1 / D17): same coalescing as the
        // chapter path — the attempt counter is carried in memory
        // and persisted ONCE at each backoff boundary (and in the
        // terminal writes), not per attempt.
        val persistAndBackoff: suspend (Long?) -> Unit = { retryAfter ->
            // Same pause guard as the chapter path: only persist
            // while the row is still RUNNING in the database, so
            // a mid-flight pause/cancel is never overwritten by
            // this stale copy; the loop-top check then exits.
            if (queueDao.getById(current.id)?.state == QueueState.RUNNING) {
                queueDao.update(current)
                delayer(backoffMillis(attempt, retryAfter))
            }
        }
        while (attempt < MAX_ATTEMPTS) {
            if (queueDao.getById(current.id)?.state != QueueState.RUNNING) return
            attempt++
            current = current.copy(attempts = attempt, updatedAt = clock())

            val resumeFrom = if (dest.isFile) dest.length() else 0L
            val response = when (val result = transport.execute(
                HttpTransport.Request(
                    url = url, rangeStart = resumeFrom,
                    basicAuth = basicAuthForBook(item.bookId)
                )
            )) {
                is AppResult.Err -> {
                    lastError = result.error
                    if (lastError is AppError.NoNetwork) {
                        // Offline isn't failure — requeue and stop.
                        queueDao.update(
                            current.copy(state = QueueState.QUEUED, updatedAt = clock())
                        )
                        return
                    }
                    null
                }
                is AppResult.Ok -> result.value
            }
            if (response == null) {
                if (attempt < MAX_ATTEMPTS) persistAndBackoff(null)
                continue
            }
            if (response.code !in 200..299) {
                val error = HttpTransport.statusError(response.code)
                lastError = error
                if (isPermanent(error)) {
                    response.body?.close()
                    break
                }
                val retryAfter = response.retryAfterSeconds
                response.body?.close()
                if (attempt < MAX_ATTEMPTS) persistAndBackoff(retryAfter)
                continue
            }
            // Stream the body to disk, honouring pause between chunks.
            val append = response.isPartialContent && resumeFrom > 0
            if (!append && dest.exists()) dest.delete()
            var downloaded = if (append) resumeFrom else 0L
            val total = when {
                response.contentLength < 0 -> -1L
                append -> resumeFrom + response.contentLength
                else -> response.contentLength
            }
            var streamFailed = false
            try {
                FileOutputStream(dest, append).use { out ->
                    val body = response.body
                    if (body == null) {
                        streamFailed = true
                    } else {
                        body.use { input ->
                            val buffer = ByteArray(64 * 1024)
                            // v1.2.0 (audit finding 1): the
                            // pause-check queue-row poll is
                            // progress-coalesced — whole-percent
                            // steps or ~500 ms, and always at
                            // 100% (a pause landing at the last
                            // bytes still wins) — instead of a
                            // fixed per-512 KB poll whose cadence
                            // ignored both file size and time.
                            val coalescer = DownloadProgressCoalescer(clock)
                            var n = input.read(buffer)
                            while (n >= 0) {
                                out.write(buffer, 0, n)
                                downloaded += n
                                if (coalescer.shouldPersist(downloaded, total)) {
                                    if (queueDao.getById(current.id)?.state !=
                                        QueueState.RUNNING
                                    ) {
                                        return
                                    }
                                }
                                n = input.read(buffer)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                streamFailed = true
                lastError = HttpTransport.ioError(e)
            }
            if (streamFailed) {
                if (attempt < MAX_ATTEMPTS) persistAndBackoff(null)
                continue
            }
            if (total > 0 && downloaded < total) {
                // Truncated body — retry keeps the part file (resume).
                lastError = AppError.ProviderDown("server", detail = "download stopped partway")
                if (attempt < MAX_ATTEMPTS) persistAndBackoff(null)
                continue
            }
            // Complete: hand the file to its owner (OPDS import).
            val completionError = fileDownloaded(current, dest)
            if (completionError == null) {
                dest.delete()
                queueDao.update(
                    current.copy(
                        state = QueueState.DONE,
                        lastErrorDetail = null, updatedAt = clock()
                    )
                )
            } else {
                markFailed(current, completionError)
            }
            return
        }
        markFailed(current, lastError)
    }

    // -- helpers ------------------------------------------------------------------

    private suspend fun markFailed(item: DownloadQueueItem, error: AppError) {
        queueDao.update(
            item.copy(
                state = QueueState.FAILED,
                lastErrorDetail = ErrorText.displayText(error),
                updatedAt = clock()
            )
        )
        // v1.0.6.2 (F3): chapter download failures join the
        // session's problem history, so Copy details can say
        // WHY a chapter never arrived (the draw-check's silent
        // download failures were undiagnosable without this).
        if (item.chapterId != null) {
            app.xreader.data.extension.DiagnosticLog.record(
                "download",
                ErrorText.displayText(error),
                "chapter ${item.chapterId} · book ${item.bookId}"
            )
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val MAX_RETRY_AFTER_MS = 60_000L
        const val BATCH_SIZE = 16
        const val PREFETCH_COUNT = 5
    }
}
