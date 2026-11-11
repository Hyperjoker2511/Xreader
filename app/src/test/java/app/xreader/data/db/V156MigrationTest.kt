package app.xreader.data.db

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.5.6 (§3.5): the 9 → 10 migration over a PRE-CHANGE-format
 * database — built by executing the exported 9.json createSql
 * verbatim, seeded with a local EPUB book (chapters + progress +
 * bookmark + highlight) and a local PDF book (a bookmark that must
 * be PRESERVED with its chapter anchor, never force-mapped to a
 * page). Room opens it through MIGRATION_9_10; Room's own schema
 * validation is part of the proof.
 *
 * v1.5.10 (I6b): the database is at v11 since v1.5.8, so the open
 * drives the production chain to the current version
 * (MIGRATION_9_10 + MIGRATION_10_11 — the tail of the list
 * AppContainer.buildXreaderDatabase registers). The v10
 * assertions below must survive the additive 10→11 step too.
 *
 * v1.5.11: the database is at v12 (chapters.memo), so the chain
 * carries MIGRATION_11_12 as its new tail; the assertions must
 * survive that additive step too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V156MigrationTest {

    private fun schemaFile(version: Int): File {
        val candidates = listOf(
            File("schemas/app.xreader.data.db.XreaderDatabase/$version.json"),
            File("app/schemas/app.xreader.data.db.XreaderDatabase/$version.json")
        )
        return candidates.first { it.isFile }
    }

    private fun createV9Database(context: Context, name: String) {
        context.deleteDatabase(name)
        val root = Json.parseToJsonElement(schemaFile(9).readText()).jsonObject
        val database = root.getValue("database").jsonObject
        val entities = database.getValue("entities").jsonArray
        val helper = object : SQLiteOpenHelper(context, name, null, 9) {
            override fun onCreate(db: SQLiteDatabase) {
                for (entity in entities.map { it.jsonObject }) {
                    val table = entity.getValue("tableName").jsonPrimitive.content
                    db.execSQL(
                        entity.getValue("createSql").jsonPrimitive.content
                            .replace("`\${TABLE_NAME}`", "`$table`")
                    )
                    for (index in entity["indices"]?.jsonArray ?: JsonArray(emptyList())) {
                        val io = index.jsonObject
                        val createSql = io["createSql"]?.jsonPrimitive?.content ?: continue
                        db.execSQL(createSql.replace("\${TABLE_NAME}", table))
                    }
                }
                for (q in database["setupQueries"]?.jsonArray ?: JsonArray(emptyList())) {
                    db.execSQL(q.jsonPrimitive.content)
                }
            }
            override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}
        }
        helper.writableDatabase.apply {
            // Local EPUB book (id 1): 3 chapters, wordCounts 100/200/100.
            execSQL(
                "INSERT INTO books (id, title, author, description, sourceType, " +
                    "localFormat, filePath, totalChapters, totalWords, addedAt, updatedAt, " +
                    "finished, chapterListComplete, chapterWalkNextPage, " +
                    "chapterWalkTotalPages, chapterWalkPageSize, inLibrary, syncedEpoch) " +
                    "VALUES (1, 'Local Novel', 'A', '', 'LOCAL_FILE', 'EPUB', " +
                    "'books/1/book.epub', 3, 400, 1, 1, 0, 1, 0, 0, 0, 1, 0)"
            )
            execSQL(
                "INSERT INTO chapters (id, bookId, `index`, title, wordCount, " +
                    "downloadState, read, updatedAt) VALUES " +
                    "(1, 1, 0, 'Start', 100, 'READY', 0, 1), " +
                    "(2, 1, 1, 'Middle', 200, 'READY', 0, 1), " +
                    "(3, 1, 2, 'End', 100, 'NONE', 0, 1)"
            )
            // Half-way through chapter 1 (index 1), by scroll.
            execSQL(
                "INSERT INTO reading_progress (bookId, chapterId, chapterIndex, " +
                    "charOffset, fraction, scrollFraction, updatedAt, deviceId) " +
                    "VALUES (1, 2, 1, 0, 0.375, 0.5, 1, '')"
            )
            execSQL(
                "INSERT INTO bookmarks (id, bookId, chapterId, chapterIndex, " +
                    "charOffset, label, createdAt, updatedAt) " +
                    "VALUES (1, 1, 3, 2, 42, 'mark', 1, 1)"
            )
            execSQL(
                "INSERT INTO highlights (id, bookId, chapterId, startOffset, " +
                    "endOffset, quotedText, color, createdAt, updatedAt) " +
                    "VALUES (1, 1, 3, 10, 25, 'quoted', 'amber', 1, 1)"
            )
            // Local PDF book (id 2): its bookmark is the §3.5
            // PRESERVE case — kept with a chapter anchor.
            execSQL(
                "INSERT INTO books (id, title, author, description, sourceType, " +
                    "localFormat, filePath, totalChapters, totalWords, addedAt, updatedAt, " +
                    "finished, chapterListComplete, chapterWalkNextPage, " +
                    "chapterWalkTotalPages, chapterWalkPageSize, inLibrary, syncedEpoch) " +
                    "VALUES (2, 'Local PDF', 'B', '', 'LOCAL_FILE', 'PDF', " +
                    "'books/2/book.pdf', 1, 50, 1, 1, 0, 1, 0, 0, 0, 1, 0)"
            )
            execSQL(
                "INSERT INTO chapters (id, bookId, `index`, title, wordCount, " +
                    "downloadState, read, updatedAt) VALUES " +
                    "(4, 2, 0, 'Pages 1-10', 50, 'READY', 0, 1)"
            )
            execSQL(
                "INSERT INTO bookmarks (id, bookId, chapterId, chapterIndex, " +
                    "charOffset, label, createdAt, updatedAt) " +
                    "VALUES (2, 2, 4, 0, 5, 'pdf mark', 1, 1)"
            )
            close()
        }
        helper.close()
    }

    @Test
    fun `v9 local books migrate to v10 with outline locators and preserved records`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val name = "v156-migration.db"
        createV9Database(context, name)

        val db = Room.databaseBuilder(context, XreaderDatabase::class.java, name)
            .addMigrations(MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
            .build()
        try {
            runBlocking {
                val book = db.bookDao().getById(1)
                assertNotNull(book)
                // Additive columns default to null — nothing rewritten.
                assertNull(book?.documentUri)
                assertNull(book?.fileFingerprint)
                // Population (a): the retained filePath is untouched.
                assertEquals("books/1/book.epub", book?.filePath)

                // The chain's v11 tail landed too: the new
                // scanlator column reads null on migrated rows.
                assertTrue(
                    db.chapterDao().forBook(1).all { it.scanlator == null }
                )

                // And the chain's v12 tail: the new memo
                // column starts empty on migrated rows —
                // nothing lost, nothing invented.
                assertTrue(
                    db.chapterDao().forBook(1).all { it.memo == null }
                )

                // Outline filled from the chapter rows.
                val outline = db.localOutlineDao().forBook(1)
                assertEquals(3, outline.size)
                assertEquals(listOf("Start", "Middle", "End"), outline.map { it.title })
                assertEquals(
                    listOf(
                        "{\"legacyChapterIndex\":0}",
                        "{\"legacyChapterIndex\":1}",
                        "{\"legacyChapterIndex\":2}"
                    ),
                    outline.map { it.targetJson }
                )

                // Progress: prefix 100 + 200 × 0.5 (scroll) over
                // total 400 = bookFraction 0.5; anchor = the title.
                val progress = db.readingProgressDao().getForBook(1)
                assertNotNull(progress?.locatorJson)
                assertTrue(
                    "locator: ${progress?.locatorJson}",
                    progress!!.locatorJson!!.contains("\"chapterIndex\":1")
                )
                assertTrue(
                    "locator: ${progress.locatorJson}",
                    progress.locatorJson!!.contains("\"bookFraction\":0.5")
                )
                assertEquals("Middle", progress.anchorText)

                // Bookmark preserved with its legacy locator.
                val marks = db.bookmarkDao().forBook(1)
                assertEquals(1, marks.size)
                assertTrue(
                    "bookmark locator: ${marks[0].locatorJson}",
                    marks[0].locatorJson!!.contains("\"chapterIndex\":2") &&
                        marks[0].locatorJson!!.contains("\"charOffset\":42")
                )

                // Highlight preserved, chapter resolved via chapterId.
                val lights = db.highlightDao().forBook(1)
                assertEquals(1, lights.size)
                assertTrue(
                    "highlight locator: ${lights[0].locatorJson}",
                    lights[0].locatorJson!!.contains("\"chapterIndex\":2") &&
                        lights[0].locatorJson!!.contains("\"startOffset\":10") &&
                        lights[0].locatorJson!!.contains("\"endOffset\":25")
                )

                // The PDF bookmark survives as a legacy record —
                // preserved, never force-mapped to a page.
                val pdfMarks = db.bookmarkDao().forBook(2)
                assertEquals(1, pdfMarks.size)
                assertTrue(
                    "pdf bookmark locator: ${pdfMarks[0].locatorJson}",
                    pdfMarks[0].locatorJson!!.contains("\"kind\":\"legacyChapter\"")
                )
                val pdfOutline = db.localOutlineDao().forBook(2)
                assertEquals(1, pdfOutline.size)

                // reading_memory exists and round-trips.
                assertTrue(db.readingMemoryDao().all().isEmpty())
                db.readingMemoryDao().upsert(
                    ReadingMemory(
                        fingerprint = "x.epub|3|abc",
                        displayName = "x.epub",
                        sizeBytes = 3,
                        format = "EPUB",
                        fraction = 0.25f,
                        updatedAt = 7
                    )
                )
                assertEquals(
                    0.25f,
                    db.readingMemoryDao().get("x.epub|3|abc")?.fraction
                )
            }
        } finally {
            db.close()
        }
    }
}
