package app.xreader.data.download

import app.xreader.core.error.AppError
import app.xreader.data.db.Chapter
import app.xreader.data.db.ChapterSourceMap
import app.xreader.data.db.ChapterSourceMapDao
import app.xreader.domain.content.ChapterContent

/**
 * Multi-source failover (PLAN §4/§9.1): a chapter's content can come
 * from several candidate sources. Phase 2 ships the engine-side
 * contract and the Room-backed candidate list (chapter_source_map was
 * poured in Phase 1); Phase 3's extension runtime registers richer
 * fetchers. The engine tries candidates in order and marks the row
 * that actually served the chapter as verified — that's how a missing
 * chapter gets *healed* from an alternate source.
 */
data class ChapterCandidate(
    /** ChapterSourceMap id when this candidate came from Room (0 = primary/inline). */
    val mapId: Long = 0,
    /** Human name shown in errors ("the mirror copy"). */
    val label: String = "primary source",
    /** Where the candidate's content lives (URL in Phase 2). */
    val locator: String = "",
    /** v1.0.6.2 (Gemini fix round, G4): the book this chapter
     * belongs to, stamped by the resolvers — fetchers that store
     * chapter assets (web chapter images) need the book's content
     * dir. 0 = unknown (assets are skipped, text still served). */
    val bookId: Long = 0
)

/** What one fetch attempt produced. */
sealed interface FetchOutcome {
    data class Success(val content: ChapterContent) : FetchOutcome
    data class Failure(
        val error: AppError,
        /** Server-provided Retry-After hint, when there was one. */
        val retryAfterSeconds: Long? = null
    ) : FetchOutcome
}

fun interface ChapterFetcher {
    suspend fun fetch(candidate: ChapterCandidate): FetchOutcome
}

data class ResolvedSource(val candidate: ChapterCandidate, val fetcher: ChapterFetcher)

interface ChapterSourceResolver {
    suspend fun candidatesFor(chapter: Chapter): List<ResolvedSource>
}

/**
 * Candidate ordering, pure and unit-tested: primary first, then
 * verified alternates, then by match confidence. The alternate's
 * locator lives in [ChapterSourceMap.altRemoteId] (the schema poured
 * in Phase 1), optionally prefixed "Label|" for error messages.
 */
object FailoverOrder {
    fun order(
        primary: ChapterCandidate,
        alternates: List<ChapterSourceMap>
    ): List<ChapterCandidate> {
        val sorted = alternates.sortedWith(
            compareByDescending<ChapterSourceMap> { it.verified }
                .thenByDescending { it.matchConfidence }
        )
        return listOf(primary) + sorted.map { map ->
            val (label, locator) = splitLocator(map.altRemoteId)
            ChapterCandidate(mapId = map.id, label = label, locator = locator)
        }
    }

    /** altRemoteId encoding: "Label|https://…" (label optional). */
    fun splitLocator(locator: String): Pair<String, String> {
        val pipe = locator.indexOf('|')
        return if (pipe >= 0) {
            locator.substring(0, pipe).ifBlank { "alternate source" } to
                locator.substring(pipe + 1)
        } else {
            "alternate source" to locator
        }
    }

    fun joinLocator(label: String, url: String): String = "$label|$url"
}

/**
 * Phase 2 resolver: the chapter's own remoteId is the primary source;
 * alternates come from chapter_source_map and are fetched as web
 * pages by [fetcherForLocator] (Phase 3 replaces this for
 * extension-backed sources).
 */
class RoomChapterSourceResolver(
    private val sourceMapDao: ChapterSourceMapDao,
    private val fetcherForLocator: (String) -> ChapterFetcher
) : ChapterSourceResolver {

    override suspend fun candidatesFor(chapter: Chapter): List<ResolvedSource> {
        val alternates = sourceMapDao.alternatesFor(chapter.id)
        val primary = ChapterCandidate(
            mapId = 0, label = "primary source", locator = chapter.remoteId ?: ""
        )
        return FailoverOrder.order(primary, alternates)
            .filter { it.locator.isNotBlank() }
            .map { candidate ->
                ResolvedSource(
                    candidate.copy(bookId = chapter.bookId),
                    fetcherForLocator(candidate.locator)
                )
            }
    }
}
