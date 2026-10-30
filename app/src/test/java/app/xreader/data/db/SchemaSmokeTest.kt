package app.xreader.data.db

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Schema smoke test (PLAN §3 gate): the database declares exactly
 * the tables PLAN §1.3 promises (plus Phase 6's two game tables).
 * Read from the schema JSON KSP exports
 * to `app/schemas` (the @Database annotation itself is BINARY-retention
 * and invisible to runtime reflection), so the test fails the moment an
 * entity is dropped or a table renamed. A true in-memory Room run
 * needs Robolectric/device — stated honestly in the Phase 1 report.
 */
class SchemaSmokeTest {

    private fun schemaJson(): String {
        // v1.0.2: the pin moves to schema 3 with the organisation
        // tables (R21/R22/vocab) — same exact-set discipline, the
        // expected tables still come from XreaderDatabase itself.
        // v1.0.6.3: the pin moves to schema 4 (chapters.dateText) —
        // the table SET is unchanged, only the version moves.
        // v1.0.8: the pin moves to schema 5 (books carry the
        // progressive chapter-list walk state) — same discipline.
        val candidates = listOf(
            File("schemas/app.xreader.data.db.XreaderDatabase/5.json"),
            File("app/schemas/app.xreader.data.db.XreaderDatabase/5.json")
        )
        val file = candidates.firstOrNull { it.isFile }
        assertTrue(
            "Exported Room schema not found (run a build first): ${candidates.map { it.absolutePath }}",
            file != null
        )
        return file!!.readText()
    }

    @Test
    fun `database declares every planned table`() {
        val root = Json.parseToJsonElement(schemaJson()).jsonObject
        val database = root.getValue("database").jsonObject
        assertEquals(5, database.getValue("version").jsonPrimitive.content.toInt())
        val tables = database.getValue("entities").jsonArray
            .map { it.jsonObject.getValue("tableName").jsonPrimitive.content }
            .toSet()
        assertEquals(XreaderDatabase.EXPECTED_TABLES, tables)
    }

    @Test
    fun `every dao is exposed by the database`() {
        val daoMethods = XreaderDatabase::class.java.methods
            .map { it.name }
            .filter { it.endsWith("Dao") }
        assertEquals(24, daoMethods.size)
    }

    @Test
    fun `converters degrade unknown stored values to safe defaults`() {
        val converters = Converters()
        assertEquals(SourceType.LOCAL_FILE, converters.stringToSourceType("FROM_THE_FUTURE"))
        assertEquals(BookFormat.UNKNOWN, converters.stringToBookFormat("???"))
        assertEquals(DownloadState.NONE, converters.stringToDownloadState(""))
        assertEquals(
            TrustState.CHANGED_REQUIRES_RETRUST,
            converters.stringToTrustState("garbage")
        )
        assertEquals(ExtensionKind.DECLARATIVE, converters.stringToExtensionKind("garbage"))
        assertEquals(LoreType.TERM, converters.stringToLoreType("garbage"))
        // And the known values round-trip.
        assertEquals("EXTENSION", converters.sourceTypeToString(SourceType.EXTENSION))
        assertEquals(QueueState.RUNNING, converters.stringToQueueState("RUNNING"))
    }
}
