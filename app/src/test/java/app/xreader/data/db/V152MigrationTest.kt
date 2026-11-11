package app.xreader.data.db

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.5.2 (§16/§10 item 2 bound set): the v7 → v8 MANUAL migration
 * over a PRE-CHANGE-format database — built by executing the
 * exported 7.json createSql verbatim (the real v7 shape, not the
 * shape the change expects), seeded with a fossil "Home" live
 * source, a book, and an unstamped READY body. Room then opens it
 * through the production migration list; Room's own schema
 * validation is part of the proof.
 *
 * v1.5.10 (I6b): the database is at v11 since v1.5.8, so the open
 * below drives the REAL production chain end to end (the exact
 * list AppContainer.buildXreaderDatabase registers:
 * 1→2 … 10→11) instead of stopping at 7→8 — Room requires a
 * complete path to the current version and the §16 artefacts
 * must survive the WHOLE chain, not just their own step.
 *
 * v1.5.11: the database is at v12 (chapters.memo), so the chain
 * below carries the 11→12 step too — same rule, new tail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V152MigrationTest {

    private fun schemaFile(version: Int): File {
        val candidates = listOf(
            File("schemas/app.xreader.data.db.XreaderDatabase/$version.json"),
            File("app/schemas/app.xreader.data.db.XreaderDatabase/$version.json")
        )
        return candidates.first { it.isFile }
    }

    @Test
    fun `v7 database migrates to v12 with exactly the section 16 total`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val name = "v152-migration.db"
        context.deleteDatabase(name)
        val root = Json.parseToJsonElement(schemaFile(7).readText()).jsonObject
        val database = root.getValue("database").jsonObject
        val entities = database.getValue("entities").jsonArray
        val helper = object : SQLiteOpenHelper(context, name, null, 7) {
            override fun onCreate(db: SQLiteDatabase) {
                for (entity in entities.map { it.jsonObject }) {
                    val table = entity.getValue("tableName").jsonPrimitive.content
                    db.execSQL(
                        entity.getValue("createSql").jsonPrimitive.content
                            .replace("`\${TABLE_NAME}`", "`$table`")
                    )
                    for (index in entity["indices"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())) {
                        val io = index.jsonObject
                        val createSql = io["createSql"]?.jsonPrimitive?.content ?: continue
                        db.execSQL(createSql.replace("\${TABLE_NAME}", table))
                    }
                }
                for (q in database["setupQueries"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())) {
                    db.execSQL(q.jsonPrimitive.content)
                }
            }
            override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}
        }
        helper.writableDatabase.apply {
            execSQL(
                "INSERT INTO extension_repos (id, name, indexUrl, enabled) " +
                    "VALUES (1, 'My link sources', '', 1)"
            )
            execSQL(
                "INSERT INTO installed_extensions (id, repoId, displayName, packageId, " +
                    "versionName, versionCode, kind, sha256, apiLevel, languages, sourceIds, " +
                    "enabled, trustState, permissionsJson, installedAt, updatedAt) VALUES " +
                    "(1, 1, 'Home', 'live.botitranslation.com', '1.0', 1, 'LIVE', 'abc', 1, " +
                    "'en', 'live.botitranslation-com', 1, 'TRUSTED', '{}', 1, 1)"
            )
            execSQL(
                "INSERT INTO books (id, title, author, description, sourceType, extensionId, " +
                    "remoteId, localFormat, totalChapters, totalWords, addedAt, updatedAt, " +
                    "finished, chapterListComplete, chapterWalkNextPage, chapterWalkTotalPages, " +
                    "chapterWalkPageSize, inLibrary) VALUES (1, 'Some Novel', 'A', '', " +
                    "'EXTENSION', 1, 'ext:1', 'UNKNOWN', 10, 100, 1, 1, 0, 1, 0, 0, 0, 1)"
            )
            execSQL(
                "INSERT INTO chapters (id, bookId, `index`, title, remoteId, contentPath, " +
                    "wordCount, downloadState, read, updatedAt) VALUES " +
                    "(1, 1, 0, 'Ch 1', 'c1', 'content/1/1.json', 5000, 'READY', 1, 1)"
            )
            close()
        }
        helper.close()

        val db = Room.databaseBuilder(context, XreaderDatabase::class.java, name)
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                MIGRATION_11_12
            )
            .build()
        try {
            runBlocking {
                val book = db.bookDao().getById(1)
                assertEquals("Some Novel", book?.title)
                assertNull(book?.kind) // seeds UNKNOWN — never a source default
                assertNull(book?.coverUrl)
                val chapter = db.chapterDao().getById(1)
                assertNull(chapter?.bodyOrigin) // unstamped — §12.6 handles it in the pass
                assertNull(chapter?.scanlator) // v11 tail of the chain: honest absence
                assertNull(chapter?.memo) // v12 tail: the new column starts empty
                val ext = db.installedExtensionDao().getById(1)
                assertEquals("Home", ext?.displayName) // fossil survives migration; §8 pass renames
                assertEquals(false, ext?.needsRepair)
                assertEquals(0, db.historyRecordDao().count())
                assertTrue(db.updatePassJournalDao().forVersion(44).isEmpty())
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `pending operation claim is exactly once and promotion is event free`() {
        val context: Context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("v152-ops")
        val db = Room.databaseBuilder(context, XreaderDatabase::class.java, "v152-ops")
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                MIGRATION_11_12
            )
            .build()
        try {
            runBlocking {
                val store = PendingOperationStore(db.pendingOperationDao())
                store.register("linkadd_example.com", "LINK_ADD", "example.com", "none", "SIGN_IN", now = 1_000)
                val promoted = store.evaluate("linkadd_example.com", "hash2", now = 2_000)
                assertEquals("READY", promoted?.state)
                assertTrue(store.claim("linkadd_example.com", "t1"))
                assertEquals(false, store.claim("linkadd_example.com", "t2"))
                store.finish("linkadd_example.com", success = true)
                assertEquals(
                    "SUCCEEDED",
                    db.pendingOperationDao().getByKey("linkadd_example.com")?.state
                )
                store.register("linkadd_old.com", "LINK_ADD", "old.com", "none", "NONE", now = 0)
                val expired = store.evaluate(
                    "linkadd_old.com", "none",
                    now = PendingOperationStore.EXPIRY_MS + 1
                )
                assertEquals("EXPIRED", expired?.state)
                val dao = db.historyRecordDao()
                dao.upsert(HistoryRecord(bookKey = "k1", title = "T", recordedAt = 10))
                dao.upsert(HistoryRecord(bookKey = "k1", title = "T", recordedAt = 20))
                assertEquals(1, dao.count())
                assertEquals(20L, dao.getByKey("k1")?.recordedAt)
                dao.deleteByKey("k1")
                assertNull(dao.getByKey("k1"))
            }
        } finally {
            db.close()
        }
    }
}
