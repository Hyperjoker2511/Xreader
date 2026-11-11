package app.xreader.data.download

import app.xreader.data.db.BookDao
import app.xreader.data.db.ChapterDao
import app.xreader.data.db.InstalledExtensionDao
import app.xreader.data.files.BookStorage
import java.io.File

/**
 * v1.5.2 fix (FIX-6b, §12.2/§12.7): the PRODUCTION eviction caller
 * for [BodyOrigin152.evictionPlan] — before this class the plan was
 * caller-less and reading accumulated AUTOMATIC bodies unbounded.
 *
 * Enforcement is a row+file transaction per victim: the body file
 * is deleted AND the chapter row's content path/state/origin are
 * cleared together, so eviction never manufactures a zombie
 * ("can't open this chapter"). Bodies of books whose provider is
 * absent or needs-repair are EXEMPT (§8.3/§12.7) until the user
 * deletes the book. The open book's current+next chapters are
 * pinned. USER downloads are never in the plan (AUTOMATIC only).
 */
class CacheEviction152(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val extensionDao: InstalledExtensionDao,
    private val storage: BookStorage
) {
    data class Result(val evictedChapterIds: List<Long>, val bytesFreed: Long)

    suspend fun enforce(openBookId: Long? = null, openChapterId: Long? = null): Result {
        val books = bookDao.allBooks()
        val exemptBookIds = HashSet<Long>()
        for (book in books) {
            val extId = book.extensionId
            if (book.sourceType == app.xreader.data.db.SourceType.EXTENSION) {
                val row = extId?.let { extensionDao.getById(it) }
                if (row == null || row.needsRepair) exemptBookIds += book.id
            }
        }
        // Pinned: the open book's current + next chapter.
        val pinned = HashSet<Long>()
        if (openBookId != null && openChapterId != null) {
            val chapters = chapterDao.forBook(openBookId)
            val idx = chapters.indexOfFirst { it.id == openChapterId }
            if (idx >= 0) {
                pinned += chapters[idx].id
                chapters.getOrNull(idx + 1)?.let { pinned += it.id }
            }
        }
        var freed = 0L
        val evicted = mutableListOf<Long>()
        // Per-kind caps: a book is image-kind when its stored kind is
        // MANGA; everything else rides the text cap. (Per-book
        // grouping keeps the accounting honest without a join.)
        val byKind = books.groupBy { it.kind == "MANGA" }
        for ((isImage, kindBooks) in byKind) {
            val entries = mutableListOf<BodyOrigin152.BodyEntry>()
            val chapterById = HashMap<Long, app.xreader.data.db.Chapter>()
            for (book in kindBooks) {
                for (chapter in chapterDao.forBook(book.id)) {
                    if (!BodyOrigin152.isCachedBody(chapter)) continue
                    val path = chapter.contentPath ?: continue
                    val bytes = runCatching { storage.resolve(path).length() }
                        .getOrDefault(0L)
                    entries += BodyOrigin152.BodyEntry(
                        chapterId = chapter.id,
                        bytes = bytes,
                        lastReadAt = chapter.updatedAt,
                        pinned = chapter.id in pinned,
                        evictionExempt = book.id in exemptBookIds
                    )
                    chapterById[chapter.id] = chapter
                }
            }
            val cap = if (isImage) BodyOrigin152.IMAGE_CAP_BYTES
                else BodyOrigin152.TEXT_CAP_BYTES
            for (victimId in BodyOrigin152.evictionPlan(entries, cap)) {
                val chapter = chapterById[victimId] ?: continue
                val file = chapter.contentPath?.let { storage.resolve(it) }
                val size = file?.length() ?: 0L
                runCatching { file?.delete() }
                chapterDao.clearBody(victimId)
                evicted += victimId
                freed += size
            }
        }
        return Result(evicted, freed)
    }
}
