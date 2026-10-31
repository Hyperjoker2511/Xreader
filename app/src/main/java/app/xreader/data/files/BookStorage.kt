package app.xreader.data.files

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.core.error.runCatchingAppSuspend
import app.xreader.domain.content.ChapterContent
import java.io.File
import kotlinx.serialization.json.Json

/**
 * The on-disk layout (PLAN §1.4) in one place. Everything lives under
 * the app-private files dir; paths stored in Room are RELATIVE to it,
 * so the whole library survives a files-dir move and stays out of any
 * backup of the database alone.
 *
 * ```
 * books/<bookId>/<original file>          imported local files
 * content/<bookId>/<chapterId>.json       normalised chapter content
 * content/<bookId>/img-<name>             chapter images / overlay audio
 * covers/<bookId>.<ext>                   extracted covers
 * downloads-temp/                         in-flight downloads (atomic rename)
 * ```
 *
 * Writes are atomic (temp file + rename) so a killed process never
 * leaves a half-written chapter that the reader would trip over.
 */
class BookStorage(val filesDir: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun booksDir(bookId: Long): File = File(filesDir, "books/$bookId").apply { mkdirs() }
    fun contentDir(bookId: Long): File = File(filesDir, "content/$bookId").apply { mkdirs() }
    fun coversDir(): File = File(filesDir, "covers").apply { mkdirs() }
    fun downloadsTempDir(): File = File(filesDir, "downloads-temp").apply { mkdirs() }

    /** The import-staging root; per-import subdirs live under it.
     * Exposed for the storage cleanup flow (G6). */
    fun importStagingRoot(): File = File(filesDir, "import-staging")

    /** A fresh staging directory for one import (caller cleans up). */
    fun importStagingDir(tag: String): File =
        File(importStagingRoot(), "$tag-${System.nanoTime()}").apply { mkdirs() }

    fun resolve(relativePath: String): File = File(filesDir, relativePath)

    fun bookFileRelative(bookId: Long, fileName: String): String = "books/$bookId/$fileName"
    fun chapterContentRelative(bookId: Long, chapterId: Long): String =
        "content/$bookId/$chapterId.json"

    fun chapterImageRelative(bookId: Long, fileName: String): String =
        "content/$bookId/img-$fileName"

    fun coverRelative(bookId: Long, extension: String): String =
        "covers/$bookId.${extension.lowercase()}"

    // -- v1.2.0 (audit finding 1 / D2): per-book disk-usage cache --
    // The Downloads projection used to run a recursive walk of a
    // book's whole file tree on EVERY queue emission (and a queue
    // row changes per chapter during a bulk download). Callers
    // that only need the number use the cached variant; the cache
    // is invalidated by every write/delete in this class that can
    // change a book's bytes (chapter content below, translated
    // content, copied assets, and the delete paths), and callers
    // can invalidate explicitly after out-of-band changes (a
    // download completing through another path, a cache clear).
    private val bookSizeCache =
        java.util.concurrent.ConcurrentHashMap<Long, Long>()
    @Volatile private var coversSizeCache: Long? = null

    /** Cached twin of [bookFilesSizeBytes]: walks the disk only
     * on a cache miss for this book. */
    fun bookFilesSizeBytesCached(book: app.xreader.data.db.Book): Long =
        bookSizeCache.getOrPut(book.id) { bookFilesSizeBytes(book) }

    fun invalidateBookFilesSize(bookId: Long) {
        bookSizeCache.remove(bookId)
    }

    fun invalidateAllBookFileSizes() {
        bookSizeCache.clear()
        coversSizeCache = null
    }

    /** Total bytes of the covers directory (a walk). */
    fun coversDirSizeBytes(): Long = runCatching {
        coversDir().walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    /** Cached twin of [coversDirSizeBytes]. Cover writes in this
     * codebase land via direct file copies in import/sync code,
     * so callers clear caches explicitly (Downloads' Clean Cache
     * path calls [invalidateAllBookFileSizes]). */
    fun coversDirSizeBytesCached(): Long =
        coversSizeCache ?: coversDirSizeBytes().also { coversSizeCache = it }

    /** Atomic write of one chapter's normalised content. */
    suspend fun writeChapterContent(
        bookId: Long,
        chapterId: Long,
        content: ChapterContent
    ): AppResult<String> = runCatchingAppSuspend {
        val relative = chapterContentRelative(bookId, chapterId)
        val target = resolve(relative)
        writeAtomic(target, json.encodeToString(ChapterContent.serializer(), content))
        invalidateBookFilesSize(bookId)
        relative
    }.mapStorageError("saving the chapter")

    suspend fun readChapterContent(bookId: Long, chapterId: Long): AppResult<ChapterContent> =
        runCatchingAppSuspend {
            val file = resolve(chapterContentRelative(bookId, chapterId))
            if (!file.isFile) throw java.io.FileNotFoundException(file.name)
            json.decodeFromString(ChapterContent.serializer(), file.readText())
        }.mapStorageError("reading the chapter")

    /** Copies a staged asset (image/audio) into the book's content dir. */
    fun copyIntoContentDir(bookId: Long, staged: File, fileName: String): String {
        val dest = File(contentDir(bookId), "img-$fileName")
        staged.copyTo(dest, overwrite = true)
        invalidateBookFilesSize(bookId)
        return "content/$bookId/img-$fileName"
    }

    // -- Phase 5: RAW translation cache + lore art ------------------------

    /** A chapter's AI translation lives BESIDE its original content
     * (never overwriting it): `content/<bookId>/tr-<chapterId>.json`. */
    fun translatedContentRelative(bookId: Long, chapterId: Long): String =
        "content/$bookId/tr-$chapterId.json"

    fun hasTranslatedContent(bookId: Long, chapterId: Long): Boolean =
        resolve(translatedContentRelative(bookId, chapterId)).isFile

    suspend fun writeTranslatedContent(
        bookId: Long,
        chapterId: Long,
        content: ChapterContent
    ): AppResult<String> = runCatchingAppSuspend {
        val relative = translatedContentRelative(bookId, chapterId)
        writeAtomic(resolve(relative), json.encodeToString(ChapterContent.serializer(), content))
        invalidateBookFilesSize(bookId)
        relative
    }.mapStorageError("saving the translation")

    suspend fun readTranslatedContent(bookId: Long, chapterId: Long): AppResult<ChapterContent> =
        runCatchingAppSuspend {
            val file = resolve(translatedContentRelative(bookId, chapterId))
            if (!file.isFile) throw java.io.FileNotFoundException(file.name)
            json.decodeFromString(ChapterContent.serializer(), file.readText())
        }.mapStorageError("reading the translation")

    fun deleteTranslatedContent(bookId: Long, chapterId: Long) {
        resolve(translatedContentRelative(bookId, chapterId)).delete()
        invalidateBookFilesSize(bookId)
    }

    /** Lore art directory for one book: `lore-art/<bookId>/`. */
    fun loreArtDir(bookId: Long): File = File(filesDir, "lore-art/$bookId").apply { mkdirs() }

    fun loreArtRelative(bookId: Long, fileName: String): String = "lore-art/$bookId/$fileName"

    /** Total size of a book's app-side files (v1.0.1: the number
     * the remove/cleanup confirmations show before deleting).
     * v1.2.1 (Defect 3): lore art belongs to the book too — it
     * was written under lore-art/<id> but counted nowhere, so
     * the quoted size (and every total built on it) understated
     * a book that carries generated art. */
    fun bookFilesSizeBytes(book: app.xreader.data.db.Book): Long {
        var total = 0L
        fun add(file: File?) {
            if (file == null) return
            if (file.isFile) total += file.length()
            else if (file.isDirectory) file.walkTopDown().forEach { if (it.isFile) total += it.length() }
        }
        add(File(filesDir, "content/${book.id}"))
        add(File(filesDir, "lore-art/${book.id}"))
        book.filePath?.let { add(resolve(it)) }
        book.coverPath?.let { add(resolve(it)) }
        return total
    }

    /** Deletes only the downloaded/parsed chapter content for a
     * book (v1.0.1 cleanup): the book's own file and cover stay. */
    fun deleteDownloadedContent(book: app.xreader.data.db.Book) {
        File(filesDir, "content/${book.id}").deleteRecursively()
        invalidateBookFilesSize(book.id)
    }

    /** Deletes every file belonging to a book (Remove book, §4).
     * v1.2.1 (Defect 3): lore art is deleted with the rest —
     * it used to survive removal as an invisible orphan. */
    fun deleteBookFiles(book: app.xreader.data.db.Book) {
        book.filePath?.let { resolve(it).parentFile?.deleteRecursively() }
        File(filesDir, "content/${book.id}").deleteRecursively()
        File(filesDir, "lore-art/${book.id}").deleteRecursively()
        book.coverPath?.let { resolve(it).delete() }
        invalidateBookFilesSize(book.id)
        coversSizeCache = null
    }

    // -- v1.2.1 (Defect 3): the orphan sweep ------------------
    // Removing a book offers "Remove book only" (files kept on
    // purpose, so a re-add restores its downloads) — but nothing
    // ever reaped the files of books whose rows are GONE for
    // good (a cleared library, junk rows deleted in an earlier
    // version, a row removed while its files were kept and the
    // book never re-added). Those bytes sat under content/,
    // books/, lore-art/ and covers/ forever, counted by every
    // storage total but owned by no book — the "extra storage"
    // the user could see but no screen could explain. The
    // sweep below is the reap: book-id-shaped storage whose id
    // has no row is deleted. It runs at app start (AppContainer)
    // when no writer is active yet.

    /** Every book id that owns a directory under the per-book
     * roots (content/, books/, lore-art/). */
    fun bookIdsOnDisk(): Set<Long> {
        val ids = mutableSetOf<Long>()
        for (root in listOf("content", "books", "lore-art")) {
            File(filesDir, root).listFiles()?.forEach { child ->
                if (child.isDirectory) {
                    child.name.toLongOrNull()?.let { ids += it }
                }
            }
        }
        return ids
    }

    /** Every book id that owns a cover file (covers/<id>.<ext>). */
    fun coverBookIdsOnDisk(): Set<Long> {
        val ids = mutableSetOf<Long>()
        coversDir().listFiles()?.forEach { child ->
            if (child.isFile) {
                child.name.substringBefore('.').toLongOrNull()
                    ?.let { ids += it }
            }
        }
        return ids
    }

    /** Deletes every file owned by the given book ids — the
     * same set [deleteBookFiles] removes for a known book,
     * addressed by id alone (an orphan has no row left to
     * read file/cover paths from; the per-id layout makes the
     * paths derivable). */
    fun deleteFilesForBookIds(ids: Collection<Long>) {
        for (id in ids) {
            File(filesDir, "content/$id").deleteRecursively()
            File(filesDir, "books/$id").deleteRecursively()
            File(filesDir, "lore-art/$id").deleteRecursively()
            coversDir().listFiles()?.forEach { child ->
                if (child.isFile &&
                    child.name.substringBefore('.').toLongOrNull() == id
                ) {
                    child.delete()
                }
            }
            invalidateBookFilesSize(id)
        }
        if (ids.isNotEmpty()) coversSizeCache = null
    }

    /** Reaps the files of books that no longer exist: every
     * on-disk book id outside [validBookIds] is deleted.
     * Returns the reaped ids. */
    fun sweepOrphanedBookFiles(validBookIds: Set<Long>): Set<Long> {
        val orphans = (bookIdsOnDisk() + coverBookIdsOnDisk()) - validBookIds
        if (orphans.isNotEmpty()) deleteFilesForBookIds(orphans)
        return orphans
    }

    /** Writes [text] to [target] via a temp sibling + rename. */
    fun writeAtomic(target: File, text: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    /** Stream-copies [input] to [target] via temp + rename, hashing on
     * the way. Returns (bytes, sha256 hex). Never buffers the file. */
    fun copyStreamAtomic(
        input: java.io.InputStream,
        target: File
    ): Pair<Long, String> {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        input.use { source ->
            temp.outputStream().buffered().use { sink ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    bytes += read
                }
            }
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        return bytes to hash
    }
}

/** Maps a failed storage operation to the typed error (PLAN §1.5). */
fun <T> AppResult<T>.mapStorageError(operation: String): AppResult<T> = when (this) {
    is AppResult.Ok -> this
    is AppResult.Err -> when (val e = error) {
        is AppError.Unknown -> {
            val detail = e.causeClass + (e.cause.message?.let { ": $it" } ?: "")
            if (detail.contains("ENOSPC") || detail.contains("No space")) {
                AppResult.Err(AppError.StorageFull)
            } else {
                AppResult.Err(AppError.StorageFailed(operation, detail.take(200)))
            }
        }
        else -> this
    }
}
