package app.xreader.data.extension

import app.xreader.core.error.AppError
import app.xreader.data.db.BookDao
import app.xreader.data.db.Chapter
import app.xreader.data.db.ChapterSourceMapDao
import app.xreader.data.db.SourceType
import app.xreader.data.download.ChapterCandidate
import app.xreader.data.download.ChapterFetcher
import app.xreader.data.download.ChapterSourceResolver
import app.xreader.data.download.FetchOutcome
import app.xreader.data.download.ResolvedSource
import app.xreader.domain.content.ChapterContent
import app.xreader.extensionapi.SourceChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 */
class ExtensionChapterFetcher(
    private val service: ExtensionService,
    private val cleanerStore: CleanerRuleStore,
    private val extensionId: Long,
    private val sourceId: String,
    private val bookId: Long
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
                val content = contentOf(handle, chapterRef.id, fetched.value.html)
                if (content.blocks.isEmpty()) {
                    FetchOutcome.Failure(
                        AppError.ParseFailed("chapter", "the page had no readable text")
                    )
                } else {
                    FetchOutcome.Success(applyCleaners(handle, content))
                }
            }
        }
    }

    private fun contentOf(
        handle: SourceHandle,
        chapterId: String,
        html: String
    ): ChapterContent {
        // The declarative engine converts chapter HTML itself (it had
        // the parsed document); other kinds go through the fragment
        // converter here.
        (handle.source as? DeclarativeSource)?.contentOf(chapterId)?.let { return it }
        val looksHtml = html.trimStart().take(64).contains('<')
        return if (looksHtml) {
            HtmlToContent.fromFragment(html, handle.source.baseUrl)
        } else {
            HtmlToContent.plainTextFallback(html)
        }
    }

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
    private val fallback: ChapterSourceResolver
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
                    locator = primaryRemoteId
                ),
                ExtensionChapterFetcher(
                    service, cleanerStore, book.extensionId, sourceId, book.id
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
                ChapterCandidate(mapId = map.id, label = label, locator = altChapterId),
                ExtensionChapterFetcher(
                    service, cleanerStore, map.altExtensionId, altSourceId, book.id
                )
            )
        }
        return resolved.ifEmpty { fallback.candidatesFor(chapter) }
    }
}
