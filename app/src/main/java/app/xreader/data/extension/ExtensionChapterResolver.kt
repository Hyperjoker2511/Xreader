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
import app.xreader.data.extension.player.MangaMemoHeal
import app.xreader.data.extension.player.MihonSourceAdapter
import app.xreader.data.files.BookStorage
import app.xreader.data.parse.ZipPaths
import app.xreader.domain.content.ChapterContent
import app.xreader.extensionapi.SourceChapter
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
 * v1.5.6 (§1.1 hardening): the missing-page-record guard for
 * player (Mihon) chapters. Returns the retryable failure a manga
 * chapter fetch must end with when the book's resolved kind is
 * MANGA and the adapter's page record ([MihonSourceAdapter
 * .pagesOf]) is null/empty — or null when the fetch may proceed
 * (a record exists, or the source is not a player source, or
 * its kind is not MANGA).
 *
 * The kind read is the seam's own: [ExtensionService.mediaKindOf]
 * — §2's priority-1 read, under which a Mihon adapter is MANGA
 * by construction (the stored-book-kind inputs of §2 priority 2
 * govern declarative/both-kinds sources, whose page records run
 * through DeclarativeSource.pagesOf and are deliberately NOT
 * hardened here: a declarative manga source's prose fallback is
 * its own both-kinds behaviour, not this defect).
 *
 * The error is [AppError.ProviderDown] with retryable = true:
 * the download engine treats a non-retryable ProviderDown as
 * terminal, and this state is the opposite of terminal — a
 * re-read re-runs the adapter's chapterContent, which re-stashes
 * the record.
 */
internal fun missingMangaPageRecordError(
    handle: SourceHandle,
    service: ExtensionService,
    pages: app.xreader.domain.content.ChapterPages?
): AppError? {
    if (pages != null && !pages.isEmpty) return null
    if (handle.source !is MihonSourceAdapter) return null
    if (service.mediaKindOf(handle) != MediaKind.MANGA) return null
    return AppError.ProviderDown(
        serviceId = handle.source.id,
        retryable = true,
        detail = "the chapter's page list couldn't be loaded"
    )
}

/**
 * v1.5.17 (F1): true when [error] is the extension's own
 * memo-missing verdict — Keiyoushi extensions whose
 * `getChapterUrl` needs state minted only at chapter-list
 * time (Asura's `mangaSlug`) throw
 * `Exception("Refresh Chapter List")` before any network
 * call when it is absent, and the guarded path carries the
 * extension's message verbatim in
 * [AppError.ExtensionFailed.detail]. This is the pipeline
 * road's second heal trigger: the memo predicate reads the
 * chapter reference, but a memo that is non-empty yet lacks
 * the key the extension needs is visible only to the
 * extension — its thrown words are the evidence. Matching
 * the literal keeps every other failure (network, parse,
 * empty list) on its honest no-heal path.
 */
internal fun memoMissingFailure(error: AppError): Boolean {
    val detail = (error as? AppError.ExtensionFailed)?.detail
        ?: return false
    return detail.contains("Refresh Chapter List", ignoreCase = true)
}

/**
 * v1.5.8 (B2 / F1 M3): the DECLARATIVE twin of
 * [missingMangaPageRecordError] — the §1.1 guard extended to
 * the declarative leg as the ONE fetcher change B2 makes.
 * When the book's resolved kind is MANGA
 * ([ExtensionService.mediaKindOf] with the book, the §2
 * per-book resolution; the source-only read when no book row
 * was handed in) and the source is a [DeclarativeSource]
 * whose page record is null/empty after `chapterContent`
 * (and after B2's image-chapter evidence pass), the fetch
 * ends with the SAME retryable [AppError.ProviderDown] the
 * adapter guard returns — never the HTML-conversion
 * fall-through that stored prose poison. A manga-routed
 * book's chapter either stores a page record or fails
 * honestly. The adapter guard above is untouched.
 */
internal fun declarativeMangaPageRecordError(
    handle: SourceHandle,
    service: ExtensionService,
    book: app.xreader.data.db.Book?,
    pages: app.xreader.domain.content.ChapterPages?
): AppError? {
    if (pages != null && !pages.isEmpty) return null
    if (handle.source !is DeclarativeSource) return null
    val kind = if (book != null) {
        service.mediaKindOf(handle, book)
    } else {
        service.mediaKindOf(handle)
    }
    if (kind != MediaKind.MANGA) return null
    return AppError.ProviderDown(
        serviceId = handle.source.id,
        retryable = true,
        detail = "the chapter's page list couldn't be loaded"
    )
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
    private val storage: BookStorage? = null,
    /**
     * Manga programme (M3): true only on the DOWNLOAD road
     * (the engine's resolver) — a manga chapter's page files
     * are fetched and recorded, so a finished download is a
     * chapter that works offline. The READ road keeps this
     * false: reading online is not downloading (the v1.0.7.1
     * rule) — a read returns the page URLs and the reader
     * streams them through the image pipeline.
     */
    private val fetchPageFiles: Boolean = false,
    /**
     * v1.5.8 (B2): the book row this fetcher serves, when the
     * resolver built it for a known book — the per-book kind
     * resolution ([ExtensionService.mediaKindOf] with the
     * book) the declarative manga guard reads. Null (older
     * constructions, tests) = the guard reads the source-only
     * kind, exactly the pre-B2 seam behaviour for the adapter.
     */
    private val book: app.xreader.data.db.Book? = null,
    /**
     * v1.5.11 (transplant, Verdict 1 — hunks 2A/2B): the
     * chapter ROW this fetcher serves, when the resolver built
     * it for a known chapter ([ExtensionChapterResolver
     * .candidatesFor] passes it for the primary candidate).
     * The fetch's chapter reference is built from the row's
     * full stored field set — title, parsed number, dateText,
     * scanlator, memo — instead of the degraded
     * (locator, label, null) the pre-v1.5.11 fetcher rebuilt.
     * Null (older constructions, alternate-source fetchers,
     * tests) = the candidate-derived reference, exactly the
     * pre-v1.5.11 shape for those roads.
     */
    private val chapter: Chapter? = null,
    /**
     * v1.5.17 (F1 — heal-on-open, V1513-LANE-A A-1): the
     * book's chapter-list refresh through the sync apply
     * path (production wires `SourceBookSync.refresh`), run
     * ONCE per book per process when this fetcher's chapter
     * reference carries a missing/empty effective memo (a
     * legacy pre-v12 row) on a Keiyoushi/player source — the
     * memo the extension's `getPageList` demands is minted
     * only by a chapter-list fetch, and this road never
     * re-minted it. The healed memo reaches this very fetch
     * through the adapter's retained snapshot (the refresh's
     * mapChapters restamps it), and reaches later opens
     * through the persisted rows. Null (older constructions,
     * alternate-source fetchers, tests) = no heal: the fetch
     * proceeds exactly as before and the extension's real
     * exception surfaces.
     */
    private val chapterListHealer: (suspend (book: app.xreader.data.db.Book) -> Unit)? = null
) : ChapterFetcher {

    override suspend fun fetch(candidate: ChapterCandidate): FetchOutcome {
        // v1.5.10 (B5): when this fetcher serves a known book,
        // resolve through the book-aware seam — a stale stored
        // row id heals by stable identity instead of failing
        // every chapter open with "no longer installed" (the
        // Komikku road reaches this fetcher via
        // ChapterTextLoader.ensureStored → candidatesFor).
        // Fetcher constructions without a book keep the
        // row-id road exactly as before.
        val handle = when (
            val result = if (book != null) {
                service.resolveBookSource(book)
            } else {
                service.source(extensionId, sourceId)
            }
        ) {
            is app.xreader.core.error.AppResult.Err ->
                return FetchOutcome.Failure(result.error)
            is app.xreader.core.error.AppResult.Ok -> result.value
        }
        // v1.5.11 (transplant, Verdict 1 — hunks 2A/2B): the
        // chapter reference carries the chapter ROW's full
        // stored field set when the resolver handed the row
        // in — the real title (never the "primary source"
        // label), the number parsed from that title by the
        // in-scope house parser ([ChapterNumbers.parse]; the
        // row carries no number column), and the stored
        // dateText / scanlator / memo (schema v12). This is
        // the reference the adapter's schapterOf restores
        // into the SChapter the extension's getPageList
        // receives — the (id, name, null) rebuild was D2's
        // convicted degradation. Without a row, the reference
        // falls back to the candidate's own fields, as before.
        val chapterRef = if (chapter != null) {
            SourceChapter(
                id = candidate.locator,
                name = chapter.title,
                // v1.5.12 (M4): the persisted number (inside
                // the row's memo payload, written by
                // MihonSourceAdapter.mapChapters) beats the
                // title parse — an unnumbered title parses to
                // null, but the extension's true number was
                // persisted at chapter-list time. (For a
                // declarative chapter the payload carries no
                // such key and the parse stands, unchanged.)
                number = MihonSourceAdapter
                    .persistedChapterNumber(chapter.memo)
                    ?: ChapterNumbers.parse(chapter.title),
                dateText = chapter.dateText,
                scanlator = chapter.scanlator,
                memo = chapter.memo
            )
        } else {
            SourceChapter(
                id = candidate.locator,
                name = candidate.label,
                number = null
            )
        }
        // v1.5.17 (F1 — heal-on-open): BEFORE the page-list
        // call, a Keiyoushi chapter whose effective memo is
        // missing/empty gets ONE chapter-list refresh for its
        // book (bounded per book per process by
        // [MangaMemoHeal]; a source that never mints memos —
        // the MangaFire shape — is unaffected beyond that one
        // bounded attempt, and the fetch proceeds regardless
        // of the heal's outcome). Only the PRIMARY fetcher
        // heals: it is the one built with the book + chapter
        // row + healer; alternate-source fetchers carry a
        // foreign source's chapter, whose memo state is not
        // this book's stored rows to refresh.
        val healAdapter = handle.source as? MihonSourceAdapter
        val healer = chapterListHealer
        val healBook = book
        var healRan = false
        if (healAdapter != null && healer != null &&
            healBook != null && chapter != null
        ) {
            healRan = MangaMemoHeal.healOnceIfMemoMissing(
                key = MangaMemoHeal.key(
                    healAdapter.namespacedId, healBook.id.toString()
                ),
                memoMissing = healAdapter.effectiveMemoMissing(chapterRef),
                refreshChapterList = { healer(healBook) }
            )
        }
        var fetched = withContext(Dispatchers.IO) {
            service.guarded(handle) { source -> source.chapterContent(chapterRef) }
        }
        // v1.5.17 (F1): the second trigger — the memo read
        // non-empty yet the extension still threw its
        // memo-missing verdict (the exact state it needs is
        // visible only to it). One heal + exactly one retry:
        // the refresh restamps the adapter's retained
        // snapshot, which schapterOf consults first, so the
        // retry hands the extension its minted state and is
        // the spec's re-resolution of the same chapter from
        // the refreshed list. A heal already spent on this
        // open (above, or claimed by an earlier open this
        // process) never re-runs — the failure then surfaces
        // as-is, honestly.
        if (fetched is AppResult.Err && !healRan &&
            healAdapter != null && healer != null &&
            healBook != null && chapter != null &&
            memoMissingFailure(fetched.error)
        ) {
            val healed = MangaMemoHeal.healOnceIfMemoMissing(
                key = MangaMemoHeal.key(
                    healAdapter.namespacedId, healBook.id.toString()
                ),
                memoMissing = true,
                refreshChapterList = { healer(healBook) }
            )
            if (healed) {
                fetched = withContext(Dispatchers.IO) {
                    service.guarded(handle) { source ->
                        source.chapterContent(chapterRef)
                    }
                }
            }
        }
        return when (fetched) {
            is app.xreader.core.error.AppResult.Err -> FetchOutcome.Failure(fetched.error)
            is app.xreader.core.error.AppResult.Ok -> {
                // Manga programme (M1): an image chapter's
                // content is its page record — the declarative
                // engine stashed it during the read above. It
                // bypasses the prose gates below entirely
                // (there is no prose to sanity-check, and the
                // not-prose gate would refuse it), and it
                // persists with the chapter through the
                // ordinary content write — remote URLs first;
                // downloaded page files join the record in M3.
                // Keiyoushi player (K4): a player source's
                // adapter stashes its record the same way —
                // same seam, same bypass, same persistence.
                val pages = when (val source = handle.source) {
                    is DeclarativeSource ->
                        source.pagesOf(candidate.locator)
                    is MihonSourceAdapter ->
                        source.pagesOf(candidate.locator)
                    else -> null
                }
                // v1.5.8 (B2 / F3 Fix 2): CHAPTER-SHAPE EVIDENCE
                // ON EVERY ROAD. The A6 image-chapter sniff used
                // to live only in the DOM prose road; a chapter
                // that arrived by AJAX or embedded JSON (the
                // Fenrir manhwa shape) never produced a pages
                // record, so tier-2 testified TEXT for it and
                // the fail-closed guard never armed. Now EVERY
                // HTML result passes imageChapterFrom's exact
                // rule HERE, before storage: prose under the
                // floor + a real page-image set stores the
                // pages record alongside the content. A prose
                // chapter never qualifies (the novel fence).
                val effectivePages = if (
                    (pages == null || pages.isEmpty) &&
                    handle.source is DeclarativeSource
                ) {
                    (handle.source as DeclarativeSource)
                        .sniffImageChapterEvidence(
                            chapterRef, fetched.value.html
                        ) ?: pages
                } else {
                    pages
                }
                // v1.5.6 (§1.1 hardening): a MANGA book whose
                // page record is missing must FAIL — retryably —
                // never fall through to the HTML-to-content
                // conversion below. That fallthrough is the
                // §1.1 defect: the adapter's page record lives
                // only in an in-memory cache, so its absence is
                // a normal, reachable state, and converting the
                // adapter's <img>-fragment HTML produced a fake
                // "text" chapter of blurry in-text pictures in
                // the novel reader. An honest retryable error
                // lets the caller try again (a re-read re-runs
                // chapterContent and re-stashes the record).
                missingMangaPageRecordError(handle, service, pages)
                    ?.let { return FetchOutcome.Failure(it) }
                // v1.5.8 (B2 / F1 M3): the same guard for the
                // DECLARATIVE leg — a manga-resolved book whose
                // declarative fetch produced no page record
                // (even after the evidence pass above) fails
                // retryably here; prose poison is unwritable
                // from every door.
                declarativeMangaPageRecordError(
                    handle, service, book, effectivePages
                )?.let { return FetchOutcome.Failure(it) }
                if (effectivePages != null && !effectivePages.isEmpty) {
                    // M3: on the download road, the page FILES
                    // land too — each page's bytes through the
                    // source's own session (fetchBytes: the
                    // saved login, the whitelist and the rate
                    // limiter all apply, exactly as for chapter
                    // images), stored beside the chapter record
                    // and named in it via withLocalFile. Pages
                    // already on disk from an earlier partial
                    // pass are kept, never re-fetched — a retry
                    // fetches only the missing pages. A page
                    // that fails stays unfilled in the record;
                    // the engine reads the record's fill count
                    // and calls the chapter PARTIAL, never
                    // downloaded.
                    val store = storage
                    val record = if (fetchPageFiles && store != null) {
                        fillPageFiles(
                            handle, candidate, effectivePages, store
                        )
                    } else {
                        effectivePages
                    }
                    return FetchOutcome.Success(
                        applyCleaners(handle, ChapterContent(pages = record))
                    )
                }
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
                // v1.5.0 (steer A8): the login wall first —
                // a short text wearing sign-in phrasing is
                // positive evidence this leg is gated, and
                // its failure must classify as a sign-in
                // need, not a parse failure.
                val loginWall = ChapterServeGate.loginWallRefusal(plainText)
                if (loginWall != null) {
                    FetchOutcome.Failure(
                        AppError.ParseFailed("chapter", loginWall)
                    )
                } else {
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
        // v1.5.3 (finding #4, item 16): LAZY CHAPTER IMAGES.
        // A chapter illustration may exist ONLY in a lazy
        // attribute (data-src and kin) while src carries a
        // placeholder — the download pass collected only
        // img[src], and the converter keys its lookup on the
        // raw src, so such an image was never fetched and
        // never mapped. Work on a clone of the fragment: each
        // img's src is promoted to its ladder-resolved URL
        // (data-src first, then the other lazy attributes,
        // then src; blank and data: values skipped), so the
        // download pass and the converter see the same real
        // URL. The engine's cached document is never mutated.
        val workDoc = (parsedDoc?.clone() ?: runCatching {
            Jsoup.parseBodyFragment(html, base)
        }.getOrNull()) ?: return HtmlToContent.fromFragment(html, base)
        promoteLazyImageSrcs(workDoc)
        // v1.5.4 (WS-5, items 21–22): FAILED-IMAGE HONESTY + the
        // failure record. Every image the pass collected but did
        // not store now maps to the shared placeholder file
        // instead of resolving to nothing, so the converter emits
        // a real Image block for the slot — labelled in the
        // reader (ui/reader/ZoomableImage.kt carries the pinned
        // words) and carrying the tag's alt text — on EVERY
        // source, for every failure cause (dead host, 530,
        // timeout, refused bytes alike).
        //
        // The failure record (item 22) is the economy: the
        // streamed road reads it before the pass and does not
        // re-attempt recorded URLs (a known-dead host is not
        // re-hammered on every re-open); the download road
        // (fetchPageFiles) RETRIES — it ignores the record and
        // deletes it up front, so a revived host heals the
        // chapter with real images. Either road rewrites the
        // record from the pass's own outcome: failures remain →
        // record holds exactly them; a clean pass → no record.
        val failureFile = imageFailureRecordFile(store, candidate.locator)
        if (fetchPageFiles) {
            runCatching { failureFile.delete() }
        }
        val recordedFailures = if (fetchPageFiles) {
            emptySet()
        } else {
            readImageFailureRecord(failureFile)
        }
        val pass = downloadImages(
            handle, workDoc.html(), base, store, workDoc,
            skipUrls = recordedFailures
        )
        val imageNames = pass.stored.toMutableMap()
        if (pass.failed.isNotEmpty()) {
            ensurePlaceholderImage(store)
            for (url in pass.failed) {
                imageNames[url] = PlaceholderImage.FILE_NAME
            }
            writeImageFailureRecord(failureFile, candidate.locator, pass.failed)
        } else if (failureFile.exists()) {
            runCatching { failureFile.delete() }
        }
        return HtmlToContent.fromDocument(workDoc, base) { src ->
            resolveImageKey(src, base)?.let { key -> imageNames[key] }
        }
    }

    /** v1.5.4 (WS-5, item 22): the per-chapter failure record's
     * home — the book's content dir, beside the chapter's stored
     * content and its images, keyed by the chapter locator's
     * hash (the fetch layer knows the locator, not the DB id).
     * It dies with the book's files ([BookStorage.deleteBookFiles]
     * wipes the whole dir) and with any clean pass. */
    private fun imageFailureRecordFile(
        store: BookStorage,
        locator: String
    ): File = File(
        store.contentDir(bookId),
        "imgfail-" + Hashes.sha256Hex(locator).take(8) + ".json"
    )

    /** The recorded failed-URL set for one chapter, or empty
     * when no (readable) record exists. A corrupt record is
     * ignored, never fatal: the pass simply retries. */
    private fun readImageFailureRecord(file: File): Set<String> {
        if (!file.isFile) return emptySet()
        val record = runCatching {
            IMAGE_FAILURE_JSON.decodeFromString<ImageFailureRecord>(
                file.readText()
            )
        }.getOrNull() ?: return emptySet()
        return record.failedUrls.toSet()
    }

    private fun writeImageFailureRecord(
        file: File,
        locator: String,
        failed: List<String>
    ) {
        runCatching {
            val record = ImageFailureRecord(
                locator = locator,
                failedUrls = failed,
                recordedAtMs = System.currentTimeMillis()
            )
            writeBytesAtomic(
                file,
                IMAGE_FAILURE_JSON.encodeToString(record)
                    .toByteArray(Charsets.UTF_8)
            )
        }
    }

    /** v1.5.4 (WS-5, item 21): the placeholder's stored file,
     * written once per book (same already-on-disk law as a real
     * image) from the generated bytes — the fetcher holds no
     * Context, so the bundled asset's drawing program is
     * mirrored in code by [PlaceholderImage]. */
    private fun ensurePlaceholderImage(store: BookStorage) {
        val target = File(
            store.contentDir(bookId), PlaceholderImage.STORED_FILE_NAME
        )
        if (target.isFile && target.length() > 0L) return
        runCatching { writeBytesAtomic(target, PlaceholderImage.bytes()) }
    }

    /** v1.5.3 (item 16): each img's ladder-resolved URL —
     * the lazy attributes in the order sites populate them,
     * then src itself; values that are blank or `data:` URIs
     * (placeholders) never win. Null when the img carries no
     * real URL anywhere. */
    private fun lazyImageUrl(element: org.jsoup.nodes.Element): String? {
        for (attr in LAZY_IMAGE_ATTRS + "src") {
            val resolved = element.absUrl(attr).ifBlank { null }
                ?: continue
            if (resolved.startsWith("data:", ignoreCase = true)) {
                continue
            }
            return resolved
        }
        return null
    }

    /** v1.5.3 (item 16): rewrite each img's src to its
     * ladder-resolved URL, so downstream passes keyed on src
     * (the download pass, the content converter) resolve the
     * image the page actually means. An img whose src already
     * IS its ladder URL is left untouched. */
    private fun promoteLazyImageSrcs(doc: Document) {
        for (img in doc.select("img")) {
            val resolved = lazyImageUrl(img) ?: continue
            if (img.absUrl("src") != resolved) {
                img.attr("src", resolved)
            }
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
     *
     * v1.5.4 (WS-5, items 21–22): the result also carries the
     * FAILED set — every collected URL that did not land in the
     * stored map (fetch failed, bytes refused, or the URL was in
     * [skipUrls]: a recorded failure the streamed road must not
     * re-attempt) — so the caller can bind those slots to the
     * placeholder and refresh the failure record.
     */
    private suspend fun downloadImages(
        handle: SourceHandle,
        html: String,
        base: String,
        store: BookStorage,
        parsedDoc: Document? = null,
        skipUrls: Set<String> = emptySet()
    ): ImagePassResult {
        val doc = parsedDoc ?: runCatching {
            Jsoup.parseBodyFragment(html, base)
        }.getOrNull() ?: return ImagePassResult(emptyMap(), emptyList())
        // v1.5.3 (item 16): every img is a candidate, and its
        // URL resolves through the lazy ladder — an image that
        // exists only in data-src (its src a placeholder, or
        // absent entirely) is collected here, not just the
        // ones already wearing a real src.
        val urls = doc.select("img").mapNotNull { element ->
            lazyImageUrl(element)
        }.distinct().take(UrlChapterFetcher.MAX_CHAPTER_IMAGES)
        if (urls.isEmpty()) return ImagePassResult(emptyMap(), emptyList())
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
            // v1.5.4 (WS-5, item 22): a recorded failure is not
            // planned, not fetched and not name-assigned on the
            // streamed road — it falls into the failed set below
            // and binds to the placeholder from the record.
            if (url in skipUrls) continue
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
        // v1.5.4 (WS-5, item 21): whatever was collected but not
        // stored FAILED — the caller binds it to the placeholder
        // and records it; nothing is dropped silently any more.
        val failed = urls.filter { it !in stored }
        return ImagePassResult(stored, failed)
    }

    /**
     * M3: the download pass for one manga chapter's pages.
     * File names are assigned FIRST, in page order (the same
     * B1 law as the image pass below), so parallel fetches can
     * never rename a page; each name is chapter-scoped — the
     * chapter locator's hash rides in it — because every
     * chapter of a manga names its pages alike (001.jpg,
     * 002.jpg…) while the content directory is shared by the
     * whole book. A page whose file already exists non-empty
     * is kept as-is (a resumed download fetches only the
     * missing pages); every failure is per-page and leaves
     * that page unfilled in the returned record.
     */
    private suspend fun fillPageFiles(
        handle: SourceHandle,
        candidate: ChapterCandidate,
        pages: app.xreader.domain.content.ChapterPages,
        store: BookStorage
    ): app.xreader.domain.content.ChapterPages {
        val contentDir = store.contentDir(bookId)
        val usedNames = mutableSetOf<String>()
        data class PageJob(
            val index: Int,
            val url: String,
            val fileName: String,
            val target: File
        )
        val jobs = pages.pages.mapIndexed { index, page ->
            val fileName = pageFileName(candidate.locator, page.url, usedNames)
            PageJob(index, page.url, fileName, File(contentDir, fileName))
        }
        val semaphore = Semaphore(MAX_PARALLEL_IMAGE_FETCHES)
        val fetched: List<Pair<Int, String>?> = coroutineScope {
            jobs.map { job ->
                async {
                    if (job.target.isFile && job.target.length() > 0L) {
                        job.index to job.fileName
                    } else {
                        semaphore.withPermit {
                            val bytes = runCatching {
                                when (
                                    val result =
                                        service.fetchBytes(handle, job.url)
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
                            if (okBytes != null &&
                                writeBytesAtomic(job.target, okBytes)
                            ) {
                                job.index to job.fileName
                            } else {
                                null
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        var filled = pages
        for (entry in fetched) {
            if (entry != null) {
                filled = filled.withLocalFile(entry.first, entry.second)
            }
        }
        return filled
    }

    /**
     * M3: a page's stored file name — the `img-<name>` layout
     * the content directory already uses, with the chapter
     * locator's hash scoping it to this chapter (see
     * [fillPageFiles]). Deterministic for a given chapter +
     * page list, which is what makes resume-by-skip work.
     */
    private fun pageFileName(
        chapterLocator: String,
        pageUrl: String,
        usedNames: MutableSet<String>
    ): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(chapterLocator.toByteArray(Charsets.UTF_8))
        val scope = digest.take(4).joinToString("") { "%02x".format(it) }
        val uri = runCatching { URI(pageUrl) }.getOrNull()
        val base = ZipPaths.assetFileName(uri?.path ?: pageUrl, usedNames)
        return "img-p$scope-$base"
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

        /** v1.5.3 (item 16): the lazy-image attributes, in
         * resolution order — the same set the declarative
         * engine's cover ladder falls through. */
        val LAZY_IMAGE_ATTRS = listOf(
            "data-src", "data-original", "data-lazy-src", "data-url"
        )

        /** v1.5.4 (WS-5, item 22): the failure-record codec. */
        val IMAGE_FAILURE_JSON = Json { ignoreUnknownKeys = true }
    }
}

/** v1.5.4 (WS-5, items 21–22): one image pass's outcome — the
 * stored map (resolved URL → bare file name) plus every
 * collected URL that did NOT land in it. */
internal class ImagePassResult(
    val stored: Map<String, String>,
    val failed: List<String>
)

/**
 * v1.5.4 (WS-5, item 22): the per-chapter image-failure record,
 * stored as `imgfail-<locator hash>.json` in the book's content
 * dir when a download pass completes with failures. [failedUrls]
 * is the exact set the pass could not store; [recordedAtMs] is
 * when that pass ran. The streamed road honours the record (no
 * re-attempt); the download road ignores and replaces it (a
 * revived host heals). Nothing outside the book's content dir
 * ever sees it, and it is deleted by any clean pass.
 */
@Serializable
internal data class ImageFailureRecord(
    val locator: String,
    val failedUrls: List<String>,
    val recordedAtMs: Long
)

/**
 * v1.5.4 (WS-5, item 21): the failed-image placeholder's bytes.
 *
 * The bundled asset `assets/picture-unavailable.png` is the
 * canonical graphic; the fetch layer holds no Context and so
 * cannot read assets, so this generator mirrors the asset's
 * drawing program exactly (same canvas, same shapes, same
 * colours — the asset is produced by the same program) and the
 * fetcher writes the generated bytes as the slot's stored file,
 * so a failed image renders through the identical stored-file
 * path as a real chapter image. The pinned words are NOT baked
 * into the picture: the reader's slot renders them as text
 * (ui/reader/ZoomableImage.kt), where they stay crisp at any
 * size and the alt text can ride along.
 */
internal object PlaceholderImage {

    /** The bare file name chapter content carries for a failed
     * image (the reader resolves it as `content/<bookId>/img-…`,
     * exactly like a real image's name). */
    const val FILE_NAME = "picture-unavailable.png"

    /** The placeholder's on-disk name inside a book's content
     * dir (the content model's `img-` prefix applied). */
    const val STORED_FILE_NAME = "img-picture-unavailable.png"

    const val WIDTH = 1200
    const val HEIGHT = 800

    // The drawing program — identical to the asset generator's:
    // a dark card, a framed landscape glyph (two mountain
    // triangles, a violet sun) in Nocturne-Violet-family tones.
    private const val BG = 0x262233
    private const val FRAME = 0x57506F
    private const val FAR = 0x453F5E
    private const val NEAR = 0x60587C
    private const val SUN = 0x8B5CF6

    private val cached: ByteArray by lazy { render() }

    /** The placeholder PNG's bytes (rendered once, then cached). */
    fun bytes(): ByteArray = cached

    private fun render(): ByteArray {
        val rgb = ByteArray(WIDTH * HEIGHT * 3)
        fun set(x: Int, y: Int, colour: Int) {
            if (x < 0 || y < 0 || x >= WIDTH || y >= HEIGHT) return
            val i = (y * WIDTH + x) * 3
            rgb[i] = (colour shr 16 and 0xFF).toByte()
            rgb[i + 1] = (colour shr 8 and 0xFF).toByte()
            rgb[i + 2] = (colour and 0xFF).toByte()
        }
        fun fillRect(x0: Int, y0: Int, x1: Int, y1: Int, colour: Int) {
            for (y in y0 until y1) for (x in x0 until x1) set(x, y, colour)
        }
        fun cross(
            ax: Int, ay: Int, bx: Int, by: Int, px: Int, py: Int
        ): Long =
            (bx - ax).toLong() * (py - ay) - (by - ay).toLong() * (px - ax)
        fun fillTriangle(
            a: Pair<Int, Int>, b: Pair<Int, Int>, c: Pair<Int, Int>,
            colour: Int
        ) {
            val xs = intArrayOf(a.first, b.first, c.first)
            val ys = intArrayOf(a.second, b.second, c.second)
            for (y in ys.min()..ys.max()) {
                for (x in xs.min()..xs.max()) {
                    val d1 = cross(a.first, a.second, b.first, b.second, x, y)
                    val d2 = cross(b.first, b.second, c.first, c.second, x, y)
                    val d3 = cross(c.first, c.second, a.first, a.second, x, y)
                    if (
                        (d1 >= 0 && d2 >= 0 && d3 >= 0) ||
                        (d1 <= 0 && d2 <= 0 && d3 <= 0)
                    ) {
                        set(x, y, colour)
                    }
                }
            }
        }
        fun fillCircle(cx: Int, cy: Int, r: Int, colour: Int) {
            for (y in cy - r..cy + r) {
                for (x in cx - r..cx + r) {
                    val dx = (x - cx).toLong()
                    val dy = (y - cy).toLong()
                    if (dx * dx + dy * dy <= r.toLong() * r) {
                        set(x, y, colour)
                    }
                }
            }
        }
        for (i in rgb.indices step 3) {
            rgb[i] = (BG shr 16 and 0xFF).toByte()
            rgb[i + 1] = (BG shr 8 and 0xFF).toByte()
            rgb[i + 2] = (BG and 0xFF).toByte()
        }
        fillTriangle(220 to 640, 560 to 330, 900 to 640, FAR)
        fillTriangle(480 to 640, 820 to 390, 1160 to 640, NEAR)
        fillCircle(800, 290, 64, SUN)
        // Frame: inset 48, thickness 12.
        fillRect(48, 48, WIDTH - 48, 60, FRAME)
        fillRect(48, HEIGHT - 60, WIDTH - 48, HEIGHT - 48, FRAME)
        fillRect(48, 48, 60, HEIGHT - 48, FRAME)
        fillRect(WIDTH - 60, 48, WIDTH - 48, HEIGHT - 48, FRAME)
        return encodePng(rgb)
    }

    /** Minimal PNG encoder: filter-0 scanlines, one IDAT. */
    private fun encodePng(rgb: ByteArray): ByteArray {
        val rowBytes = WIDTH * 3
        val raw = ByteArray((rowBytes + 1) * HEIGHT)
        var src = 0
        var dst = 0
        repeat(HEIGHT) {
            raw[dst++] = 0
            System.arraycopy(rgb, src, raw, dst, rowBytes)
            src += rowBytes
            dst += rowBytes
        }
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(raw)
        deflater.finish()
        val idat = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            if (count > 0) idat.write(buffer, 0, count)
        }
        deflater.end()
        val ihdr = ByteArrayOutputStream().apply {
            write(WIDTH ushr 24); write(WIDTH ushr 16)
            write(WIDTH ushr 8); write(WIDTH)
            write(HEIGHT ushr 24); write(HEIGHT ushr 16)
            write(HEIGHT ushr 8); write(HEIGHT)
            write(8) // bit depth
            write(2) // colour type: truecolour RGB
            write(0) // compression
            write(0) // filter
            write(0) // interlace
        }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(
            byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
            )
        )
        writeChunk(out, "IHDR", ihdr)
        writeChunk(out, "IDAT", idat.toByteArray())
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun writeChunk(
        out: ByteArrayOutputStream,
        type: String,
        data: ByteArray
    ) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(data.size ushr 24); out.write(data.size ushr 16)
        out.write(data.size ushr 8); out.write(data.size)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        val value = crc.value
        out.write((value ushr 24).toInt()); out.write((value ushr 16).toInt())
        out.write((value ushr 8).toInt()); out.write(value.toInt())
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
    private val storage: BookStorage? = null,
    /** Manga programme (M3): true for the DOWNLOAD engine's
     * resolver — its fetchers also land manga page files (see
     * [ExtensionChapterFetcher.fetchPageFiles]). The reader's
     * resolver keeps the default: a read never downloads. */
    private val fetchPageFiles: Boolean = false,
    /** v1.5.17 (F1): the chapter-list heal the PRIMARY
     * fetcher runs for a memo-less Keiyoushi chapter (see
     * [ExtensionChapterFetcher.chapterListHealer]) —
     * production wires `SourceBookSync.refresh`. */
    private val chapterListHealer: (suspend (book: app.xreader.data.db.Book) -> Unit)? = null
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
        // v1.5.10 (B5): resolve the book's source by stable
        // identity ONCE here — on a row-id miss the found
        // row's id replaces the stale stored one in the
        // primary fetcher AND the book's stored pointer is
        // healed through this resolver's book DAO (the
        // service heals too when it holds a DAO; this write
        // is idempotent and covers constructions without
        // one). A genuinely-absent source keeps the stored
        // id here — the fetcher's own resolve then produces
        // the honest NAMED error at fetch time.
        val primaryExtensionId = when (
            val healed = service.resolveBookSource(book)
        ) {
            is app.xreader.core.error.AppResult.Ok -> {
                val foundId = healed.value.extension.id
                if (foundId != book.extensionId) {
                    runCatching {
                        bookDao.update(book.copy(extensionId = foundId))
                    }
                }
                foundId
            }
            is app.xreader.core.error.AppResult.Err ->
                book.extensionId
        }
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
                    service, cleanerStore, primaryExtensionId, sourceId, book.id,
                    storage, fetchPageFiles, book = book,
                    // v1.5.11 (hunk 2B): the primary fetcher
                    // serves THIS chapter row — its fetch
                    // reference is built from the row (see
                    // ExtensionChapterFetcher.chapter). The
                    // alternate fetchers below keep the
                    // candidate-derived reference: the row's
                    // memo is the PRIMARY source's own minted
                    // state, and handing it to a different
                    // source would be a foreign chapter's
                    // private state, not this one's.
                    chapter = chapter,
                    // v1.5.17 (F1): the primary fetcher is
                    // the heal road (see
                    // ExtensionChapterFetcher.chapterListHealer).
                    chapterListHealer = chapterListHealer
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
                    storage, fetchPageFiles, book = book
                )
            )
        }
        return resolved.ifEmpty { fallback.candidatesFor(chapter) }
    }
}
