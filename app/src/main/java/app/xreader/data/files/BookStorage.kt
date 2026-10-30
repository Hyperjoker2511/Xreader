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

    /** Atomic write of one chapter's normalised content. */
    suspend fun writeChapterContent(
        bookId: Long,
        chapterId: Long,
        content: ChapterContent
    ): AppResult<String> = runCatchingAppSuspend {
        val relative = chapterContentRelative(bookId, chapterId)
        val target = resolve(relative)
        writeAtomic(target, json.encodeToString(ChapterContent.serializer(), content))
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
    }

    /** Lore art directory for one book: `lore-art/<bookId>/`. */
    fun loreArtDir(bookId: Long): File = File(filesDir, "lore-art/$bookId").apply { mkdirs() }

    fun loreArtRelative(bookId: Long, fileName: String): String = "lore-art/$bookId/$fileName"

    /** Total size of a book's app-side files (v1.0.1: the number
     * the remove/cleanup confirmations show before deleting). */
    fun bookFilesSizeBytes(book: app.xreader.data.db.Book): Long {
        var total = 0L
        fun add(file: File?) {
            if (file == null) return
            if (file.isFile) total += file.length()
            else if (file.isDirectory) file.walkTopDown().forEach { if (it.isFile) total += it.length() }
        }
        add(File(filesDir, "content/${book.id}"))
        book.filePath?.let { add(resolve(it)) }
        book.coverPath?.let { add(resolve(it)) }
        return total
    }

    /** Deletes only the downloaded/parsed chapter content for a
     * book (v1.0.1 cleanup): the book's own file and cover stay. */
    fun deleteDownloadedContent(book: app.xreader.data.db.Book) {
        File(filesDir, "content/${book.id}").deleteRecursively()
    }

    /** Deletes every file belonging to a book (Remove book, §4). */
    fun deleteBookFiles(book: app.xreader.data.db.Book) {
        book.filePath?.let { resolve(it).parentFile?.deleteRecursively() }
        File(filesDir, "content/${book.id}").deleteRecursively()
        book.coverPath?.let { resolve(it).delete() }
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
