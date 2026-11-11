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
 * v1.5.8 (E1): the 10 → 11 migration over a PRE-CHANGE-format
 * database — built by executing the exported 10.json createSql
 * verbatim, seeded with real chapter rows. Room opens it
 * through MIGRATION_10_11 (explicit migration, NO destructive
 * fallback); rows must be intact and `scanlator` null
 * everywhere, and the new column must round-trip new writes.
 * Room's own schema validation is part of the proof.
 *
 * v1.5.11: the database is at v12 (chapters.memo), so the open
 * carries MIGRATION_11_12 as the chain's new tail — the v11
 * assertions below must survive it, and the migrated rows'
 * `memo` must read null (the column starts empty).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V158MigrationV10ToV11Test {

    private fun schemaFile(version: Int): File {
        val candidates = listOf(
            File("schemas/app.xreader.data.db.XreaderDatabase/$version.json"),
            File("app/schemas/app.xreader.data.db.XreaderDatabase/$version.json")
        )
        return candidates.first { it.isFile }
    }

    private fun createV10Database(context: Context, name: String) {
        context.deleteDatabase(name)
        val root = Json.parseToJsonElement(schemaFile(10).readText()).jsonObject
        val database = root.getValue("database").jsonObject
        val entities = database.getValue("entities").jsonArray
        val helper = object : SQLiteOpenHelper(context, name, null, 10) {
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
            execSQL(
                "INSERT INTO books (id, title, author, description, sourceType, " +
                    "localFormat, totalChapters, totalWords, addedAt, updatedAt, " +
                    "finished, chapterListComplete, chapterWalkNextPage, " +
                    "chapterWalkTotalPages, chapterWalkPageSize, inLibrary, syncedEpoch) " +
                    "VALUES (1, 'Scanlated Saga', 'A', '', 'EXTENSION', 'UNKNOWN', " +
                    "2, 0, 1, 1, 0, 1, 0, 0, 0, 1, 0)"
            )
            execSQL(
                "INSERT INTO chapters (id, bookId, `index`, title, wordCount, " +
                    "downloadState, read, updatedAt, dateText) VALUES " +
                    "(1, 1, 0, 'Chapter 1', 100, 'READY', 1, 1, '01.01.2026'), " +
                    "(2, 1, 1, 'Chapter 2', 120, 'NONE', 0, 1, NULL)"
            )
            close()
        }
        helper.close()
    }

    @Test
    fun `v10 chapters migrate to v12 intact with scanlator null`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val name = "v158-migration.db"
        createV10Database(context, name)

        val db = Room.databaseBuilder(context, XreaderDatabase::class.java, name)
            .addMigrations(MIGRATION_10_11, MIGRATION_11_12)
            .build()
        try {
            runBlocking {
                val chapters = db.chapterDao().forBook(1)
                assertEquals(2, chapters.size)
                assertEquals(
                    listOf("Chapter 1", "Chapter 2"), chapters.map { it.title }
                )
                // The v10 data survived untouched…
                assertEquals("01.01.2026", chapters[0].dateText)
                assertEquals(100, chapters[0].wordCount)
                assertNull(chapters[1].dateText)
                // …and the new column reads null everywhere.
                assertTrue(chapters.all { it.scanlator == null })
                // The chain's v12 tail: the memo column the
                // 11→12 step adds starts empty on migrated
                // rows — nothing lost, nothing invented.
                assertTrue(chapters.all { it.memo == null })

                // The column round-trips new writes.
                db.chapterDao().update(
                    chapters[0].copy(scanlator = "Gengar")
                )
                val reread = db.chapterDao().forBook(1)
                assertEquals("Gengar", reread[0].scanlator)
                assertNull(reread[1].scanlator)
                assertNotNull(db.bookDao().getById(1))
            }
        } finally {
            db.close()
        }
    }
}
