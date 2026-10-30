package app.xreader.data

import app.xreader.core.error.AppResult
import app.xreader.data.db.Book
import app.xreader.data.db.BookDao
import app.xreader.data.db.BookDownloadCount
import app.xreader.data.db.Chapter
import app.xreader.data.db.ChapterDao
import app.xreader.data.db.ChapterSourceMap
import app.xreader.data.db.ChapterSourceMapDao
import app.xreader.data.db.DownloadQueueDao
import app.xreader.data.db.DownloadQueueItem
import app.xreader.data.db.DownloadState
import app.xreader.data.db.ExtensionRepo
import app.xreader.data.db.ExtensionRepoDao
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.InstalledExtensionDao
import app.xreader.data.db.OpdsServer
import app.xreader.data.db.OpdsServerDao
import app.xreader.data.db.QueueState
import app.xreader.data.net.HttpTransport
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory DAO fakes for the Phase 2 engine tests. They implement the
 * Room interfaces directly (DAOs are interfaces) with the same
 * observable semantics the engine relies on: state filtering, ordering
 * by enqueuedAt, update-by-id.
 */
class FakeBookDao : BookDao {
    private val ids = AtomicLong(1)
    val books = MutableStateFlow<List<Book>>(emptyList())

    override suspend fun insert(book: Book): Long {
        val id = ids.getAndIncrement()
        books.value = books.value + book.copy(id = id)
        return id
    }

    override suspend fun update(book: Book) {
        books.value = books.value.map { if (it.id == book.id) book else it }
    }

    override suspend fun delete(book: Book) {
        books.value = books.value.filterNot { it.id == book.id }
    }

    override suspend fun getById(id: Long): Book? = books.value.firstOrNull { it.id == id }

    override suspend fun findByExtensionRemote(extensionId: Long, remoteId: String): Book? =
        books.value.firstOrNull { it.extensionId == extensionId && it.remoteId == remoteId }

    override fun observeById(id: Long): Flow<Book?> = books.map { list -> list.firstOrNull { it.id == id } }

    override fun observeShelf(): Flow<List<Book>> = books
    override fun observeAllBooks(): Flow<List<Book>> = books

    override suspend fun count(): Int = books.value.size

    override suspend fun allBooks(): List<Book> = books.value.sortedBy { it.id }

    override suspend fun findByTitleAndChapters(title: String, chapters: Int): Book? =
        books.value.firstOrNull { it.title == title && it.totalChapters == chapters }

    override suspend fun finishedCount(): Int = books.value.count { it.finished }

    override suspend fun finishedBooks(): List<Book> = books.value.filter { it.finished }
}

class FakeChapterDao : ChapterDao {
    private val ids = AtomicLong(1)
    val chapters = MutableStateFlow<List<Chapter>>(emptyList())

    override fun observeDownloadCounts(): Flow<List<BookDownloadCount>> =
        chapters.map { list ->
            list.groupBy { it.bookId }.map { (bookId, bookChapters) ->
                BookDownloadCount(
                    bookId = bookId,
                    downloaded = bookChapters.count {
                        it.downloadState == DownloadState.READY
                    },
                    readDownloaded = bookChapters.count {
                        it.downloadState == DownloadState.READY && it.read
                    }
                )
            }
        }

    override suspend fun insert(chapter: Chapter): Long {
        val id = ids.getAndIncrement()
        chapters.value = chapters.value + chapter.copy(id = id)
        return id
    }

    override suspend fun insertAll(newChapters: List<Chapter>) {
        newChapters.forEach { insert(it) }
    }

    override suspend fun update(chapter: Chapter) {
        chapters.value = chapters.value.map { if (it.id == chapter.id) chapter else it }
    }

    override suspend fun delete(chapter: Chapter) {
        chapters.value = chapters.value.filterNot { it.id == chapter.id }
    }

    override suspend fun getById(id: Long): Chapter? = chapters.value.firstOrNull { it.id == id }

    override fun observeForBook(bookId: Long): Flow<List<Chapter>> =
        chapters.map { list -> list.filter { it.bookId == bookId }.sortedBy { it.index } }

    override suspend fun forBook(bookId: Long): List<Chapter> =
        chapters.value.filter { it.bookId == bookId }.sortedBy { it.index }

    override suspend fun setDownloadState(id: Long, state: DownloadState) {
        chapters.value = chapters.value.map {
            if (it.id == id) it.copy(downloadState = state) else it
        }
    }

    override suspend fun setDownloadStateForIds(
        ids: List<Long>, state: DownloadState
    ) {
        val wanted = ids.toSet()
        chapters.value = chapters.value.map {
            if (it.id in wanted) it.copy(downloadState = state) else it
        }
    }

    override suspend fun setContent(id: Long, path: String, words: Int, state: DownloadState) {
        chapters.value = chapters.value.map {
            if (it.id == id) it.copy(contentPath = path, wordCount = words, downloadState = state) else it
        }
    }

    override suspend fun readyCount(bookId: Long): Int =
        chapters.value.count { it.bookId == bookId && it.downloadState == DownloadState.READY }

    override suspend fun getDownloadedChapters(limit: Int): List<Chapter> =
        chapters.value
            .filter { it.downloadState == DownloadState.READY }
            .sortedWith(compareBy({ it.bookId }, { it.index }))
            .take(limit)

    override suspend fun setRead(id: Long, read: Boolean) {
        chapters.value = chapters.value.map {
            if (it.id == id) it.copy(read = read) else it
        }
    }

    override suspend fun getByIndex(bookId: Long, index: Int): Chapter? =
        chapters.value.firstOrNull { it.bookId == bookId && it.index == index }

    override suspend fun readCount(): Int = chapters.value.count { it.read }
}

class FakeChapterSourceMapDao : ChapterSourceMapDao {
    private val ids = AtomicLong(1)
    val maps = MutableStateFlow<List<ChapterSourceMap>>(emptyList())

    override suspend fun upsert(map: ChapterSourceMap): Long {
        val existing = maps.value.firstOrNull { it.id == map.id && map.id != 0L }
        return if (existing != null) {
            maps.value = maps.value.map { if (it.id == map.id) map else it }
            map.id
        } else {
            val id = ids.getAndIncrement()
            maps.value = maps.value + map.copy(id = id)
            id
        }
    }

    override suspend fun delete(map: ChapterSourceMap) {
        maps.value = maps.value.filterNot { it.id == map.id }
    }

    override suspend fun forChapter(chapterId: Long): List<ChapterSourceMap> =
        maps.value.filter { it.chapterId == chapterId }

    override suspend fun alternatesFor(chapterId: Long): List<ChapterSourceMap> =
        maps.value.filter { it.chapterId == chapterId }
            .sortedWith(compareByDescending<ChapterSourceMap> { it.verified }.thenByDescending { it.matchConfidence })

    override suspend fun markVerified(id: Long) {
        maps.value = maps.value.map { if (it.id == id) it.copy(verified = true) else it }
    }
}

class FakeDownloadQueueDao : DownloadQueueDao {
    private val ids = AtomicLong(1)
    val items = MutableStateFlow<List<DownloadQueueItem>>(emptyList())

    override suspend fun insert(item: DownloadQueueItem): Long {
        val id = ids.getAndIncrement()
        items.value = items.value + item.copy(id = id)
        return id
    }

    override suspend fun insertAll(items: List<DownloadQueueItem>) {
        for (item in items) insert(item)
    }

    override suspend fun update(item: DownloadQueueItem) {
        items.value = items.value.map { if (it.id == item.id) item else it }
    }

    override suspend fun delete(item: DownloadQueueItem) {
        items.value = items.value.filterNot { it.id == item.id }
    }

    override fun observeAll(): Flow<List<DownloadQueueItem>> =
        items.map { list -> list.sortedByDescending { it.enqueuedAt } }

    override suspend fun active(): List<DownloadQueueItem> =
        items.value.filter { it.state == QueueState.QUEUED || it.state == QueueState.RUNNING }
            .sortedBy { it.enqueuedAt }

    override suspend fun getById(id: Long): DownloadQueueItem? =
        items.value.firstOrNull { it.id == id }

    override suspend fun nextQueued(limit: Int): List<DownloadQueueItem> =
        items.value.filter { it.state == QueueState.QUEUED }
            .sortedBy { it.enqueuedAt }.take(limit)

    override fun observeForBook(bookId: Long): Flow<List<DownloadQueueItem>> =
        items.map { list -> list.filter { it.bookId == bookId }.sortedBy { it.enqueuedAt } }

    override suspend fun activeForBook(bookId: Long): List<DownloadQueueItem> =
        items.value.filter {
            it.bookId == bookId && it.state in setOf(
                QueueState.QUEUED, QueueState.RUNNING, QueueState.PAUSED
            )
        }

    override suspend fun resetRunningToQueued(): Int {
        var count = 0
        items.value = items.value.map {
            if (it.state == QueueState.RUNNING) {
                count++
                it.copy(state = QueueState.QUEUED, lastErrorDetail = null)
            } else {
                it
            }
        }
        return count
    }
}

class FakeOpdsServerDao : OpdsServerDao {
    private val ids = AtomicLong(1)
    val servers = MutableStateFlow<List<OpdsServer>>(emptyList())

    override suspend fun insert(server: OpdsServer): Long {
        val id = ids.getAndIncrement()
        servers.value = servers.value + server.copy(id = id)
        return id
    }

    override suspend fun update(server: OpdsServer) {
        servers.value = servers.value.map { if (it.id == server.id) server else it }
    }

    override suspend fun delete(server: OpdsServer) {
        servers.value = servers.value.filterNot { it.id == server.id }
    }

    override fun observeAll(): Flow<List<OpdsServer>> =
        servers.map { list -> list.sortedBy { it.name } }

    override suspend fun getById(id: Long): OpdsServer? = servers.value.firstOrNull { it.id == id }

    override fun observeById(id: Long): Flow<OpdsServer?> =
        servers.map { list -> list.firstOrNull { it.id == id } }
}

class FakeExtensionRepoDao : ExtensionRepoDao {
    private val ids = AtomicLong(1)
    val repos = MutableStateFlow<List<ExtensionRepo>>(emptyList())

    override suspend fun insert(repo: ExtensionRepo): Long {
        val id = ids.getAndIncrement()
        repos.value = repos.value + repo.copy(id = id)
        return id
    }

    override suspend fun update(repo: ExtensionRepo) {
        repos.value = repos.value.map { if (it.id == repo.id) repo else it }
    }

    override suspend fun delete(repo: ExtensionRepo) {
        repos.value = repos.value.filterNot { it.id == repo.id }
    }

    override suspend fun getById(id: Long): ExtensionRepo? =
        repos.value.firstOrNull { it.id == id }

    override fun observeAll(): Flow<List<ExtensionRepo>> =
        repos.map { list -> list.sortedBy { it.name } }
}

class FakeInstalledExtensionDao : InstalledExtensionDao {
    private val ids = AtomicLong(1)
    val extensions = MutableStateFlow<List<InstalledExtension>>(emptyList())

    override suspend fun insert(extension: InstalledExtension): Long {
        val id = ids.getAndIncrement()
        extensions.value = extensions.value + extension.copy(id = id)
        return id
    }

    override suspend fun update(extension: InstalledExtension) {
        extensions.value = extensions.value.map {
            if (it.id == extension.id) extension else it
        }
    }

    override suspend fun delete(extension: InstalledExtension) {
        extensions.value = extensions.value.filterNot { it.id == extension.id }
    }

    override suspend fun getByPackageId(packageId: String): InstalledExtension? =
        extensions.value.firstOrNull { it.packageId == packageId }

    override suspend fun getById(id: Long): InstalledExtension? =
        extensions.value.firstOrNull { it.id == id }

    override fun observeById(id: Long): Flow<InstalledExtension?> =
        extensions.map { list -> list.firstOrNull { it.id == id } }

    override fun observeAll(): Flow<List<InstalledExtension>> =
        extensions.map { list -> list.sortedBy { it.displayName } }

    override suspend fun countAll(): Int = extensions.value.size

    override suspend fun allNow(): List<InstalledExtension> =
        extensions.value.sortedBy { it.displayName }
}

/**
 * Scripted [HttpTransport]: the handler decides each response; every
 * request is recorded for assertions (URLs, Range headers, auth).
 */
class FakeTransport(
    private val handler: suspend (HttpTransport.Request) -> AppResult<HttpTransport.Response>
) : HttpTransport {

    val requests = mutableListOf<HttpTransport.Request>()

    override suspend fun execute(request: HttpTransport.Request): AppResult<HttpTransport.Response> {
        requests += request
        return handler(request)
    }

    companion object {
        fun response(
            code: Int,
            body: String = "",
            headers: Map<String, String> = emptyMap(),
            partial: Boolean = false
        ): HttpTransport.Response = HttpTransport.Response(
            code = code,
            headers = headers + mapOf("Content-Length" to body.toByteArray().size.toString()),
            body = body.byteInputStream(),
            isPartialContent = partial
        )

        fun ok(body: String) = AppResult.Ok(response(200, body))
    }
}
