package app.xreader.data.sync

import app.xreader.core.error.AppResult
import app.xreader.data.db.Book
import app.xreader.data.db.BookDao
import app.xreader.data.db.ChapterDao
import app.xreader.data.db.DownloadState
import app.xreader.data.files.BookStorage
import app.xreader.domain.content.ChapterContent
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The opt-in book-file channel (Phase 6, closing the Phase 5 gap):
 * when the user turns "Also sync book files" on, a sync carries —
 * beside the metadata payload — each book's source file, its cover,
 * and its downloaded chapter content (originals, translations,
 * images). Off (the default) sync stays metadata-only, exactly as
 * Phase 5 shipped.
 *
 * Files are addressed by DEVICE-INDEPENDENT sync paths
 * (`book-file/<name>`, `cover/<name>`, `content/ch-<index>.json`,
 * `content/tr-ch-<index>.json`, `content/img-<name>`): Room ids
 * differ per device, chapter *indexes* don't. A manifest
 * (`xreader-files.json` beside the sync payload, or inside the
 * backup archive) records size + SHA-256 per file, so unchanged
 * files never move twice.
 *
 * Known limits, stated plainly: files move as single blobs through
 * the [SyncBackend] seam (a book file is loaded once per transfer),
 * and deletions don't propagate — a removed book's remote blobs
 * become orphans the manifest no longer lists.
 */
class BookFileSync(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val storage: BookStorage,
    private val keyProvider: suspend (Book) -> String,
    private val clock: () -> Long = System::currentTimeMillis
) {

    @Serializable
    data class FileEntry(
        val bookKey: String,
        val syncPath: String,
        val size: Long,
        val sha256: String
    )

    @Serializable
    data class FileManifest(
        val format: Int = FORMAT,
        val entries: List<FileEntry> = emptyList()
    ) {
        fun find(bookKey: String, syncPath: String): FileEntry? =
            entries.firstOrNull { it.bookKey == bookKey && it.syncPath == syncPath }

        companion object {
            const val FORMAT = 1
        }
    }

    data class FileReport(
        val uploaded: Int = 0,
        val downloaded: Int = 0,
        val skippedUnmatched: Int = 0,
        val bytesMoved: Long = 0
    ) {
        operator fun plus(other: FileReport): FileReport = FileReport(
            uploaded = uploaded + other.uploaded,
            downloaded = downloaded + other.downloaded,
            skippedUnmatched = skippedUnmatched + other.skippedUnmatched,
            bytesMoved = bytesMoved + other.bytesMoved
        )
    }

    /** One local file that belongs to the channel. */
    private data class LocalFile(
        val book: Book,
        val bookKey: String,
        val syncPath: String,
        val file: File
    )

    /** A fresh staging directory for an in-flight backup restore. */
    fun newStagingDir(): File = storage.importStagingDir("file-sync")

    // -- enumeration ------------------------------------------------------------

    private suspend fun localFiles(): List<LocalFile> {
        val result = mutableListOf<LocalFile>()
        for (book in bookDao.allBooks()) {
            val key = keyProvider(book)
            book.filePath?.let { rel ->
                val file = storage.resolve(rel)
                if (file.isFile) {
                    result += LocalFile(book, key, "book-file/${file.name}", file)
                }
            }
            book.coverPath?.let { rel ->
                val file = storage.resolve(rel)
                if (file.isFile) {
                    result += LocalFile(book, key, "cover/${file.name}", file)
                }
            }
            val chapters = chapterDao.forBook(book.id)
            val indexByChapterId = chapters.associate { it.id to it.index }
            val contentDir = File(storage.filesDir, "content/${book.id}")
            contentDir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                val syncPath = when {
                    file.name.startsWith("tr-") -> {
                        val chapterId = file.name.removePrefix("tr-")
                            .removeSuffix(".json").toLongOrNull()
                        val index = chapterId?.let { indexByChapterId[it] }
                        if (index != null) "content/tr-ch-$index.json" else null
                    }
                    file.name.startsWith("img-") -> "content/${file.name}"
                    file.name.endsWith(".json") -> {
                        val chapterId = file.name.removeSuffix(".json").toLongOrNull()
                        val index = chapterId?.let { indexByChapterId[it] }
                        if (index != null) "content/ch-$index.json" else null
                    }
                    else -> null
                }
                if (syncPath != null) result += LocalFile(book, key, syncPath, file)
            }
        }
        return result
    }

    private suspend fun localManifest(): FileManifest {
        val entries = localFiles().map { local ->
            FileEntry(
                bookKey = local.bookKey,
                syncPath = local.syncPath,
                size = local.file.length(),
                sha256 = sha256(local.file)
            )
        }
        return FileManifest(entries = entries)
    }

    // -- backend channel (Drive / WebDAV) ----------------------------------------

    /** Uploads every local file the remote manifest doesn't already
     * hold (by hash), then rewrites the remote manifest. */
    suspend fun push(backend: SyncBackend): AppResult<FileReport> {
        val remote = readManifest(backend) ?: FileManifest()
        var uploaded = 0
        var bytes = 0L
        val merged = remote.entries.toMutableList()
        for (local in localFiles()) {
            val hash = sha256(local.file)
            val existing = remote.find(local.bookKey, local.syncPath)
            if (existing != null && existing.sha256 == hash) continue
            val data = local.file.readBytes()
            when (val written = backend.write(remoteName(local.bookKey, local.syncPath), data)) {
                is AppResult.Err -> return written
                is AppResult.Ok -> Unit
            }
            uploaded++
            bytes += data.size
            merged.removeAll { it.bookKey == local.bookKey && it.syncPath == local.syncPath }
            merged += FileEntry(local.bookKey, local.syncPath, local.file.length(), hash)
        }
        // The manifest always reflects THIS device's library for the
        // books it holds; entries for books it doesn't hold stay.
        val finalManifest = FileManifest(entries = merged)
        if (finalManifest != remote) {
            when (val written = backend.write(
                MANIFEST_NAME, encodeManifest(finalManifest).toByteArray(Charsets.UTF_8)
            )) {
                is AppResult.Err -> return written
                is AppResult.Ok -> Unit
            }
        }
        return AppResult.Ok(FileReport(uploaded = uploaded, bytesMoved = bytes))
    }

    /** Downloads every manifested file whose book exists here and
     * whose local copy is missing or different (by hash). */
    suspend fun pull(backend: SyncBackend): AppResult<FileReport> {
        val remote = readManifest(backend) ?: return AppResult.Ok(FileReport())
        val booksByKey = booksByKey()
        var downloaded = 0
        var skipped = 0
        var bytes = 0L
        for (entry in remote.entries) {
            val book = booksByKey[entry.bookKey]
            if (book == null) {
                skipped++
                continue
            }
            val target = localTarget(book, entry.syncPath)
            if (target != null && target.isFile && sha256(target) == entry.sha256) {
                continue
            }
            val data = when (val read = backend.read(remoteName(entry.bookKey, entry.syncPath))) {
                is AppResult.Err -> return read
                is AppResult.Ok -> read.value
            } ?: continue
            placeFile(book, entry.syncPath, data)
            downloaded++
            bytes += data.size
        }
        return AppResult.Ok(
            FileReport(downloaded = downloaded, skippedUnmatched = skipped, bytesMoved = bytes)
        )
    }

    private suspend fun readManifest(backend: SyncBackend): FileManifest? {
        val bytes = when (val read = backend.read(MANIFEST_NAME)) {
            is AppResult.Err -> return null
            is AppResult.Ok -> read.value
        } ?: return null
        return decodeManifest(bytes.toString(Charsets.UTF_8))
    }

    // -- backup channel -----------------------------------------------------------

    /** Files for the backup archive: the manifest + (entry name →
     * file) pairs [BackupArchive] streams into the zip. */
    suspend fun collectForBackup(): Pair<FileManifest, List<Pair<String, File>>> {
        val locals = localFiles()
        val entries = locals.map {
            FileEntry(it.bookKey, it.syncPath, it.file.length(), sha256(it.file))
        }
        val files = locals.map { backupEntryName(it.bookKey, it.syncPath) to it.file }
        return FileManifest(entries = entries) to files
    }

    /** Places files extracted from a backup (entry name → staged
     * file) onto matched local books. */
    suspend fun applyFromBackup(
        manifest: FileManifest,
        extracted: Map<String, File>
    ): FileReport {
        val booksByKey = booksByKey()
        var downloaded = 0
        var skipped = 0
        var bytes = 0L
        for (entry in manifest.entries) {
            val book = booksByKey[entry.bookKey]
            val staged = extracted[backupEntryName(entry.bookKey, entry.syncPath)]
            if (book == null) {
                skipped++
                continue
            }
            if (staged == null || !staged.isFile) continue
            val target = localTarget(book, entry.syncPath)
            if (target != null && target.isFile && sha256(target) == entry.sha256) {
                continue
            }
            placeFile(book, entry.syncPath, staged.readBytes())
            downloaded++
            bytes += staged.length()
        }
        return FileReport(
            downloaded = downloaded, skippedUnmatched = skipped, bytesMoved = bytes
        )
    }

    // -- placement ------------------------------------------------------------------

    private suspend fun booksByKey(): Map<String, Book> =
        bookDao.allBooks().associateBy { keyProvider(it) }

    /** Where a sync path lands on THIS device (ids rewritten to the
     * local book/chapter rows), or null for an unknown path shape. */
    private suspend fun localTarget(book: Book, syncPath: String): File? {
        val rel = localRelativePath(book, syncPath) ?: return null
        return storage.resolve(rel)
    }

    private suspend fun localRelativePath(book: Book, syncPath: String): String? = when {
        syncPath.startsWith("book-file/") ->
            storage.bookFileRelative(book.id, syncPath.removePrefix("book-file/"))
        syncPath.startsWith("cover/") -> {
            val ext = syncPath.substringAfterLast('.', "")
            if (ext.isBlank()) null else storage.coverRelative(book.id, ext)
        }
        syncPath.startsWith("content/ch-") -> {
            val index = syncPath.removePrefix("content/ch-")
                .removeSuffix(".json").toIntOrNull()
            val chapter = index?.let { chapterDao.getByIndex(book.id, it) }
            chapter?.let { storage.chapterContentRelative(book.id, it.id) }
        }
        syncPath.startsWith("content/tr-ch-") -> {
            val index = syncPath.removePrefix("content/tr-ch-")
                .removeSuffix(".json").toIntOrNull()
            val chapter = index?.let { chapterDao.getByIndex(book.id, it) }
            chapter?.let { storage.translatedContentRelative(book.id, it.id) }
        }
        syncPath.startsWith("content/img-") ->
            "content/${book.id}/${syncPath.removePrefix("content/")}"
        else -> null
    }

    /** Writes the bytes and fixes the rows that point at the file,
     * so a pulled book file/cover/chapter is actually usable. */
    private suspend fun placeFile(book: Book, syncPath: String, data: ByteArray) {
        val rel = localRelativePath(book, syncPath) ?: return
        val target = storage.resolve(rel)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeBytes(data)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        // Re-read the row before fixing pointers: one pull places
        // several files for the SAME book (file, cover, chapters),
        // and updating from the caller's snapshot would clobber the
        // pointer a previous placement just wrote.
        val current = bookDao.getById(book.id) ?: book
        when {
            syncPath.startsWith("book-file/") && current.filePath == null ->
                bookDao.update(current.copy(filePath = rel, updatedAt = clock()))
            syncPath.startsWith("cover/") && current.coverPath != rel ->
                bookDao.update(current.copy(coverPath = rel, updatedAt = clock()))
            syncPath.startsWith("content/ch-") -> {
                val index = syncPath.removePrefix("content/ch-")
                    .removeSuffix(".json").toIntOrNull() ?: return
                val chapter = chapterDao.getByIndex(book.id, index) ?: return
                if (chapter.contentPath == null) {
                    val words = runCatching {
                        val content = Json { ignoreUnknownKeys = true }
                            .decodeFromString(
                                ChapterContent.serializer(),
                                data.toString(Charsets.UTF_8)
                            )
                        ChapterContent.countWords(content.plainText())
                    }.getOrDefault(chapter.wordCount)
                    chapterDao.setContent(chapter.id, rel, words, DownloadState.READY)
                }
            }
        }
    }

    // -- names + hashes ---------------------------------------------------------------

    /** Flat backend blob name (Drive is flat; WebDAV stays one
     * folder deep — no collections to create). */
    fun remoteName(bookKey: String, syncPath: String): String =
        "xreader-file-" + shortHash(bookKey) + "-" +
            syncPath.replace('/', '_').replace(' ', '_')

    fun backupEntryName(bookKey: String, syncPath: String): String =
        "files/" + shortHash(bookKey) + "/" + syncPath

    private fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MANIFEST_NAME = "xreader-files.json"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun encodeManifest(manifest: FileManifest): String =
            json.encodeToString(FileManifest.serializer(), manifest)

        fun decodeManifest(raw: String): FileManifest? =
            runCatching { json.decodeFromString(FileManifest.serializer(), raw) }
                .getOrNull()?.takeIf { it.format == FileManifest.FORMAT }
    }
}
