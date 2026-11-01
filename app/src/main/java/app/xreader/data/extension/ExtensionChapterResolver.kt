package app.xreader.data.extension

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.BookDao
import app.xreader.data.db.Chapter
import app.xreader.data.db.ChapterSourceMapDao
import app.xreader.data.db.SourceType
import app.xreader.data.download.ChapterCandidate
import app.xreader.data.download.ChapterFetcher
import app.xreader.data.download.ChapterSourceResolver
import app.xreader.data.download.FetchOutcome
import app.xreader.data.download.ResolvedSource
import app.xreader.data.download.UrlChapterFetcher
import app.xreader.data.files.BookStorage
import app.xreader.data.parse.ZipPaths
import app.xreader.domain.content.ChapterContent
import app.xreader.extensionapi.SourceChapter
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * How extension locators are encoded in existing columns (no schema
 * change, DB stays v1):
 *
 * - Book.remoteId for EXTENSION books = "<sourceId>|<bookRemoteId>"
 *   (the [FailoverOrder]-style join the codebase already uses for
 *   locators — see [SourceBookIds]).
 * - ChapterSourceMap.altRemoteId for extension alternates =
 *   "<Extension display name>|<sourceId>::<chapterRemoteId>".
 */
object SourceBookIds {
    fun encode(sourceId: String, remoteId: String): String = "$sourceId|$remoteId"

    fun decode(encoded: String?): Pair<String, String>? {
        if (encoded.isNullOrBlank()) return null
        val pipe = encoded.indexOf('|')
        if (pipe <= 0) return null
        return encoded.substring(0, pipe) to encoded.substring(pipe + 1)
    }

    fun encodeAltLocator(sourceId: String, chapterRemoteId: String): String =
        "$sourceId::$chapterRemoteId"

    fun decodeAltLocator(locator: String): Pair<String, String>? {
        val sep = locator.indexOf("::")
        if (sep <= 0) return null
        return locator.substring(0, sep) to locator.substring(sep + 2)
    }
}

/**
 * Fetches one chapter from an extension source, through the runtime's
 * guarded call path, normalises to [ChapterContent] (the declarative
 * engine's own conversion when it produced one), and applies the
 * cleaner stack (§5). This is where downloads meet sources.
 *
 * v1.0.6.2: when [storage] is set, chapter images are kept — the
 * fragment's images are downloaded through the source's own
 * session (see [ExtensionService.fetchBytes]) into the book's
 * content dir, in the same `img-<name>` layout G4 gave web
 * chapters, and the conversion here resolves each <img> to its
 * stored file. Without storage the behaviour is exactly the
 * pre-image one.
 */
class ExtensionChapterFetcher(
    private val service: ExtensionService,
    private val cleanerStore: CleanerRuleStore,
    private val extensionId: Long,
    private val sourceId: String,
    private val bookId: Long,
    private val storage: BookStorage? = null
) : ChapterFetcher {

    override suspend fun fetch(candidate: ChapterCandidate): FetchOutcome {
        val handle = when (val result = service.source(extensionId, sourceId)) {
            is app.xreader.core.error.AppResult.Err ->
                return FetchOutcome.Failure(result.error)
            is app.xreader.core.error.AppResult.Ok -> result.value
        }
        val chapterRef = SourceChapter(
            id = candidate.locator,
            name = candidate.label,
            number = null
        )
        val fetched = withContext(Dispatchers.IO) {
            service.guarded(handle) { source -> source.chapterContent(chapterRef) }
        }
        return when (fetched) {
            is app.xreader.core.error.AppResult.Err -> FetchOutcome.Failure(fetched.error)
            is app.xreader.core.error.AppResult.Ok -> {
                val content = contentOf(handle, candidate, fetched.value.html)
                // v1.3.4 (T2): the not-prose gate — text that
                // is overwhelmingly base64-shaped blob (the
                // FenrirRealm encoded-shell scrape) is NOT a
                // chapter: it fails honestly here, so it is
                // never stored and never rendered as prose,
                // on ANY extension road that reaches this
                // fetcher.
                val plainText = content.blocks
                    .filterIsInstance<app.xreader.domain.content.ChapterBlock.Paragraph>()
                    .joinToString("\n") { it.text }
                val refusal = ChapterServeGate.refusalFor(plainText)
                if (refusal != null) {
                    FetchOutcome.Failure(
                        AppError.ParseFailed("chapter", refusal)
                    )
                } else if (content.blocks.isEmpty()) {
                    FetchOutcome.Failure(
                        AppError.ParseFailed("chapter", "the page had no readable text")
                    )
                } else {
                    FetchOutcome.Success(applyCleaners(handle, content))
                }
            }
        }
    }

    private suspend fun contentOf(
        handle: SourceHandle,
        candidate: ChapterCandidate,
        html: String
    ): ChapterContent {
        val store = storage
        if (store == null || bookId <= 0) {
            // The declarative engine converts chapter HTML itself (it
            // had the parsed document); other kinds go through the
            // fragment converter here. (Order preserved from the
            // pre-image code: the engine's conversion wins first.)
            (handle.source as? DeclarativeSource)?.contentOf(candidate.locator)
                ?.let { return it }
            val looksHtml = html.trimStart().take(64).contains('<')
            return if (looksHtml) {
                HtmlToContent.fromFragment(html, handle.source.baseUrl)
            } else {
                HtmlToContent.plainTextFallback(html)
            }
        }
        val looksHtml = html.trimStart().take(64).contains('<')
        if (!looksHtml) return HtmlToContent.plainTextFallback(html)
        // Convert here, with a real image resolver — the same pass
        // UrlChapterFetcher runs (G4). The declarative engine's
        // cached conversion is this same pipeline over the same
        // returned HTML with images dropped, so converting here
        // loses nothing and gives every source kind one image-aware
        // path. Relative srcs resolve against the chapter URL when
        // the locator is one (the common shape, declarative
        // included), else against the source's base URL — the base
        // the fragment conversion has always used.
        val base = candidate.locator.takeIf {
            it.startsWith("http://") || it.startsWith("https://")
        } ?: handle.source.baseUrl
        // v1.2.0 (C2): when the declarative engine produced this
        // chapter, it still holds the parsed document — the
        // image pass takes its URLs from there instead of
        // re-parsing the returned HTML. Other source kinds (and
        // an evicted cache entry) fall back to the re-parse
        // inside downloadImages, exactly as before. Conversion
        // OUTPUT is unchanged either way: the same resolver
        // below runs over the same fragment.
        val parsedDoc = (handle.source as? DeclarativeSource)
            ?.documentOf(candidate.locator)
        val imageNames = downloadImages(handle, html, base, store, parsedDoc)
        return HtmlToContent.fromFragment(html, base) { src ->
            resolveImageKey(src, base)?.let { key -> imageNames[key] }
        }
    }

    /**
     * Downloads the fragment's images into the book's content dir;
     * returns resolved-URL → stored file name (the bare name the
     * content model carries; the reader prefixes `img-`). Mirrors
     * UrlChapterFetcher's pass (G4): srcs resolved against the
     * chapter URL, `data:` and non-web schemes skipped, the count
     * capped. The bytes travel through the source's own session
     * ([ExtensionService.fetchBytes]) — whitelist, login session
     * and rate limit apply, exactly as for the chapter page. Every
     * failure is per-image: skip that image, keep the text.
     */
    private suspend fun downloadImages(
        handle: SourceHandle,
        html: String,
        base: String,
        store: BookStorage,
        parsedDoc: Document? = null
    ): Map<String, String> {
        val doc = parsedDoc ?: runCatching {
            Jsoup.parseBodyFragment(html, base)
        }.getOrNull() ?: return emptyMap()
        val urls = doc.select("img[src]").mapNotNull { element ->
            element.absUrl("src").takeIf { it.isNotBlank() }
        }.distinct().take(UrlChapterFetcher.MAX_CHAPTER_IMAGES)
        if (urls.isEmpty()) return emptyMap()
        // v1.2.0 (B1/B2): names are assigned UP FRONT, in page
        // order (the used-names set is order-sensitive), so the
        // fetches below can run in parallel without the result
        // ever depending on completion order. An image whose
        // file already exists non-empty is NOT fetched again —
        // it still lands in the returned map, so the content
        // resolves to it exactly as if it had just downloaded.
        val usedNames = mutableSetOf<String>()
        val contentDir = store.contentDir(bookId)
        val planned = mutableListOf<PlannedImage>()
        val stored = LinkedHashMap<String, String>()
        for (url in urls) {
            val uri = runCatching { URI(url) }.getOrNull() ?: continue
            if (uri.scheme != "http" && uri.scheme != "https") continue
            val name = ZipPaths.assetFileName(uri.path ?: url, usedNames)
            val target = File(contentDir, "img-$name")
            if (target.isFile && target.length() > 0L) {
                stored[url] = name
            } else {
                planned += PlannedImage(url, name, target)
            }
        }
        if (planned.isNotEmpty()) {
            // Bounded-parallel fetch (B1): at most
            // MAX_PARALLEL_IMAGE_FETCHES in flight; the byte
            // limiter inside the client (60 ms) still paces the
            // starts. Every failure stays per-image.
            val semaphore = Semaphore(MAX_PARALLEL_IMAGE_FETCHES)
            val fetched: List<Pair<String, String>?> = coroutineScope {
                planned.map { image ->
                    async {
                        semaphore.withPermit {
                            val bytes = runCatching {
                                when (
                                    val result =
                                        service.fetchBytes(handle, image.url)
                                ) {
                                    is AppResult.Err -> null
                                    is AppResult.Ok -> result.value
                                }
                            }.getOrElse { error ->
                                if (error is kotlinx.coroutines
                                        .CancellationException
                                ) {
                                    throw error
                                }
                                null
                            }
                            val okBytes = bytes?.takeIf { it.isNotEmpty() }
                            if (okBytes == null) {
                                null
                            } else if (
                                writeBytesAtomic(image.target, okBytes)
                            ) {
                                image.url to image.name
                            } else {
                                null
                            }
                        }
                    }
                }.map { it.await() }
            }
            // Deterministic result: page order, not the order
            // the parallel fetches happened to finish in.
            for (entry in fetched) {
                if (entry != null) stored[entry.first] = entry.second
            }
        }
        return stored
    }

    /** One image to fetch: its resolved URL, its assigned
     * stored name and the file it lands in. */
    private data class PlannedImage(
        val url: String,
        val name: String,
        val target: File
    )

    /** The converter hands over the raw src attribute; resolve it
     * against the chapter URL exactly as the download pass's jsoup
     * resolution did, so the stored-name lookup hits. (Same helper
     * as UrlChapterFetcher's, G4.) */
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

    private fun applyCleaners(
        handle: SourceHandle,
        content: ChapterContent
    ): ChapterContent {
        val configRules =
            (handle.source as? DeclarativeSource)?.config?.cleaners ?: emptyList()
        val stack = cleanerStore.stackFor(
            configRules = configRules,
            packageId = handle.extension.packageId,
            bookId = bookId
        )
        if (stack.none { it.rule.enabled }) return content
        val (cleaned, outcome) = CleanerEngine.cleanContent(content, stack)
        if (outcome.disabledRules.isNotEmpty()) {
            cleanerStore.applyDisables(
                outcome.disabledRules, handle.extension.packageId, bookId
            )
        }
        return cleaned
    }

    private companion object {
        /** v1.2.0 (B1): image fetches in flight at once —
         * enough to hide transfer latency, few enough to stay
         * polite to a small site (the byte limiter paces the
         * starts on top of this). */
        const val MAX_PARALLEL_IMAGE_FETCHES = 4
    }
}

/**
 * The downloads resolver with real sources (PLAN §5 wiring): for
 * EXTENSION books, the primary candidate is the book's own source and
 * alternates come from chapter_source_map pointing at other
 * extensions; every other book keeps the Phase 2 resolver untouched.
 */
class ExtensionChapterResolver(
    private val bookDao: BookDao,
    private val sourceMapDao: ChapterSourceMapDao,
    private val service: ExtensionService,
    private val cleanerStore: CleanerRuleStore,
    private val fallback: ChapterSourceResolver,
    /** v1.0.6.2: chapter-image storage for the fetchers built here
     * (null = the pre-image behaviour, used by older constructions). */
    private val storage: BookStorage? = null
) : ChapterSourceResolver {

    override suspend fun candidatesFor(chapter: Chapter): List<ResolvedSource> {
        val book = bookDao.getById(chapter.bookId)
        if (book == null || book.sourceType != SourceType.EXTENSION ||
            book.extensionId == null
        ) {
            return fallback.candidatesFor(chapter)
        }
        val (sourceId, _) = SourceBookIds.decode(book.remoteId)
            ?: return fallback.candidatesFor(chapter)
        val resolved = mutableListOf<ResolvedSource>()
        val primaryRemoteId = chapter.remoteId
        if (!primaryRemoteId.isNullOrBlank()) {
            resolved += ResolvedSource(
                ChapterCandidate(
                    mapId = 0,
                    label = "primary source",
                    locator = primaryRemoteId,
                    bookId = book.id
                ),
                ExtensionChapterFetcher(
                    service, cleanerStore, book.extensionId, sourceId, book.id,
                    storage
                )
            )
        }
        val alternates = sourceMapDao.alternatesFor(chapter.id)
            .sortedWith(
                compareByDescending<app.xreader.data.db.ChapterSourceMap> { it.verified }
                    .thenByDescending { it.matchConfidence }
            )
        for (map in alternates) {
            val (label, locator) =
                app.xreader.data.download.FailoverOrder.splitLocator(map.altRemoteId)
            val (altSourceId, altChapterId) =
                SourceBookIds.decodeAltLocator(locator) ?: continue
            resolved += ResolvedSource(
                ChapterCandidate(
                    mapId = map.id, label = label, locator = altChapterId,
                    bookId = book.id
                ),
                ExtensionChapterFetcher(
                    service, cleanerStore, map.altExtensionId, altSourceId, book.id,
                    storage
                )
            )
        }
        return resolved.ifEmpty { fallback.candidatesFor(chapter) }
    }
}
