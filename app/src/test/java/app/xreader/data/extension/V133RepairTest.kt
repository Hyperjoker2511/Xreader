package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeBookDao
import app.xreader.data.FakeChapterDao
import app.xreader.data.FakeChapterSourceMapDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.data.db.Book
import app.xreader.data.db.Chapter
import app.xreader.data.db.DownloadState
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.SourceType
import app.xreader.data.files.BookStorage
import app.xreader.data.prefs.SourcePrefs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v1.3.3 (V132-CHECK 11/14, scope S6 — the headline):
 * STORED-LIST REPAIR.
 *
 * The user's real Shadow Slave store is the v1.3.1-polluted
 * set: 3,253 rows where the source's own API states 3,229
 * (live-verified twice by the parent), including a phantom
 * chapter-1 row pointing at a dead address while the real
 * chapter 1 ("Nightmare Begins") sits in the API list. The
 * book also has NO cover — the stored record never healed.
 *
 * This fixture rebuilds that store synthetically over the
 * CAPTURED NovelBuddy payload (the source's own truth) and
 * proves the repair: a full canonical reconcile replaces
 * the stored rows with exactly the canonical set, read /
 * download state rides across by identity, the phantom is
 * gone, chapter 1 is the real one, and the missing cover
 * is restored from the book's details.
 */
class V133RepairTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun captureDir(name: String): File? {
        var dir: File? =
            File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val base = dir ?: return null
            val candidate = File(base, "fixtures-capture/$name")
            if (candidate.isDirectory) return candidate
            dir = base.parentFile
        }
        return null
    }

    private class World(
        val repair: StoredBookRepair,
        val bookDao: FakeBookDao,
        val chapterDao: FakeChapterDao,
        val storage: BookStorage,
        val bookId: Long,
        val canonicalIds: Map<Int, String>
    )

    private suspend fun world(): World {
        val dir = captureDir("novelbuddy.me")
        assumeTrue("captured NovelBuddy payload present", dir != null)
        val payload = File(dir!!, "api/chapters-shadow-slave.json")
            .readText()
        val bookHtml = File(dir, "book.html").readText()

        val records = Json.parseToJsonElement(payload)
            .jsonObject.getValue("data")
            .jsonObject.getValue("chapters")
            .jsonArray
        assertEquals(3229, records.size)
        val canonicalIds = records.associate { record ->
            val number = record.jsonObject
                .getValue("number").jsonPrimitive.content.toInt()
            val path = record.jsonObject
                .getValue("url").jsonPrimitive.content
            number to "https://novelbuddy.me$path"
        }

        val transport = FakeTransport { request ->
            when {
                request.url ==
                    "https://api.novelbuddy.me/titles/VYPGVZ8z/chapters" ->
                    FakeTransport.ok(payload)
                request.url == "https://novelbuddy.me/shadow-slave" ->
                    FakeTransport.ok(bookHtml)
                request.url.startsWith("https://rs.novelbuddy.me/") ->
                    AppResult.Ok(FakeTransport.response(200, "cover-bytes"))
                else -> AppResult.Ok(FakeTransport.response(404, "no"))
            }
        }

        val bookDao = FakeBookDao()
        val chapterDao = FakeChapterDao()
        val mapDao = FakeChapterSourceMapDao()
        val extDao = FakeInstalledExtensionDao()
        val store = ExtensionStore(tmp.newFolder())
        val config = BundledSingles.novelBuddy("novelbuddy", "NovelBuddy")
        store.writeText(
            store.definitionFile("bundled.novel"),
            DeclarativeFingerprints.encode(listOf(config))
        )
        val extId = extDao.insert(
            InstalledExtension(
                repoId = 1,
                displayName = "NovelBuddy",
                packageId = "bundled.novel",
                versionName = "1.0",
                versionCode = 1,
                kind = app.xreader.data.db.ExtensionKind.DECLARATIVE,
                artifactPath = null,
                sha256 = DeclarativeFingerprints.of(listOf(config)),
                apiLevel = 1,
                languages = "en",
                sourceIds = "novelbuddy",
                installedAt = 0,
                updatedAt = 0,
                permissionsJson = kotlinx.serialization.json.Json
                    .encodeToString(
                        ExtensionPermissions.serializer(),
                        ExtensionPermissions(
                            domains = listOf(
                                "novelbuddy.me",
                                "api.novelbuddy.me",
                                "rs.novelbuddy.me"
                            )
                        )
                    )
            )
        )
        val service = ExtensionService(
            extDao, store,
            HostContextFactory(store, testSessionStore(), transport),
            FakeDexLoader()
        ) { FakeJsRuntime() }
        val storage = BookStorage(tmp.newFolder())
        val sync = SourceBookSync(
            bookDao = bookDao,
            chapterDao = chapterDao,
            sourceMapDao = mapDao,
            extensionDao = extDao,
            service = service,
            storage = storage,
            transport = transport,
            repairScheduler = {},
            nowMs = { 1_000L }
        )
        val prefs = SourcePrefs(FakePreferencesStore())
        val repair = StoredBookRepair(
            bookDao, chapterDao, sync, prefs, storage
        ) { true }

        val bookId = bookDao.insert(
            Book(
                title = "Shadow Slave",
                author = "Guiltythree",
                coverPath = null,
                sourceType = SourceType.EXTENSION,
                extensionId = extId,
                remoteId = SourceBookIds.encode(
                    "novelbuddy", "https://novelbuddy.me/shadow-slave"
                ),
                totalChapters = 3253,
                addedAt = 0,
                updatedAt = 0,
                inLibrary = true
            )
        )

        // ── The polluted store: the canonical 3,229, plus 23
        // canonical-key duplicates (trailing-slash variants),
        // plus one phantom chapter-1 row at a dead address.
        val polluted = mutableListOf<Chapter>()
        var index = 0
        for (record in records) {
            val obj = record.jsonObject
            val number = obj.getValue("number")
                .jsonPrimitive.content.toInt()
            val name = obj.getValue("name").jsonPrimitive.content
            val canonical = canonicalIds.getValue(number)
            polluted += Chapter(
                bookId = bookId,
                index = index++,
                title = name,
                remoteId = canonical,
                updatedAt = 0,
                read = number == 5,
                downloadState = if (number == 10) {
                    DownloadState.READY
                } else {
                    DownloadState.NONE
                },
                contentPath = if (number == 10) {
                    "content/10.html"
                } else {
                    null
                }
            )
            if (number in 100..122) {
                polluted += Chapter(
                    bookId = bookId,
                    index = index++,
                    title = name,
                    remoteId = "$canonical/",
                    updatedAt = 0
                )
            }
        }
        polluted += Chapter(
            bookId = bookId,
            index = index++,
            title = "Chapter 1",
            remoteId = "https://novelbuddy.me/shadow-slave/chapter-1",
            updatedAt = 0
        )
        assertEquals(3253, polluted.size)
        chapterDao.insertAll(polluted)

        return World(repair, bookDao, chapterDao, storage, bookId, canonicalIds)
    }

    @Test
    fun `the polluted shadow slave store repairs to the canonical set`() =
        runBlocking {
            val w = world()
            val book = w.bookDao.getById(w.bookId)!!
            assertTrue(
                "the repair policy must see this book",
                StoredRepairPolicy.needsRepair(book, 3253)
            )
            val result = w.repair.repairBook(book)
            assertTrue("repair must succeed: $result", result)

            val rows = w.chapterDao.forBook(w.bookId)
            assertEquals(
                "exactly the source's canonical set", 3229, rows.size
            )
            // Chapter 1 is the REAL one.
            val first = rows.firstOrNull {
                it.remoteId ==
                    "https://novelbuddy.me/shadow-slave/chapter-1-nightmare-begins"
            }
            assertNotNull("real chapter 1 present", first)
            assertTrue(first!!.title.contains("Nightmare Begins"))
            // The phantom is gone.
            assertFalse(
                "phantom chapter-1 row dropped",
                rows.any {
                    it.remoteId ==
                        "https://novelbuddy.me/shadow-slave/chapter-1"
                }
            )
            // No canonical duplicates survive.
            assertEquals(
                rows.size,
                rows.map {
                    ChapterIdentity.canonicalKey(it.remoteId ?: "")
                }.distinct().size
            )
            // State rode across by identity.
            val five = rows.first {
                it.remoteId == w.canonicalIds.getValue(5)
            }
            assertTrue("chapter 5 stays read", five.read)
            val ten = rows.first {
                it.remoteId == w.canonicalIds.getValue(10)
            }
            assertEquals(DownloadState.READY, ten.downloadState)
            assertNotNull(ten.contentPath)
            // The book's stated total is the truth now.
            assertEquals(
                3229, w.bookDao.getById(w.bookId)!!.totalChapters
            )
            Unit
        }

    @Test
    fun `repair restores the missing cover from the book details`() =
        runBlocking {
            val w = world()
            val book = w.bookDao.getById(w.bookId)!!
            assertEquals(null, book.coverPath)
            assertTrue(w.repair.repairBook(book))
            val healed = w.bookDao.getById(w.bookId)!!
            assertNotNull(
                "the cover is restored (item 14)", healed.coverPath
            )
            assertTrue(
                "and the cover file actually exists",
                w.storage.resolve(healed.coverPath!!).exists()
            )
            Unit
        }

    @Test
    fun `the upgrade sweep repairs library books once per version`() =
        runBlocking {
            val w = world()
            assertTrue(w.repair.runUpgradeSweep(35))
            assertEquals(3229, w.chapterDao.forBook(w.bookId).size)
            // The version flag is burned: a second sweep for
            // the same build does nothing.
            assertFalse(w.repair.runUpgradeSweep(35))
            Unit
        }

    @Test
    fun `the repair policy only fires on real disagreement`() {
        val healthy = Book(
            title = "Healthy",
            sourceType = SourceType.EXTENSION,
            extensionId = 9,
            remoteId = "x",
            totalChapters = 12,
            coverPath = "covers/9.jpg",
            addedAt = 0,
            updatedAt = 0,
            inLibrary = true
        )
        assertFalse(StoredRepairPolicy.needsRepair(healthy, 12))
        assertTrue(StoredRepairPolicy.needsRepair(healthy, 11))
        assertTrue(
            StoredRepairPolicy.needsRepair(
                healthy.copy(coverPath = null), 12
            )
        )
        assertFalse(
            StoredRepairPolicy.needsRepair(
                healthy.copy(
                    sourceType = SourceType.LOCAL_FILE,
                    extensionId = null,
                    coverPath = null
                ),
                0
            )
        )
    }
}
