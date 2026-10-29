package app.xreader.data.ai

import app.xreader.data.db.LoreEntity
import app.xreader.data.db.LoreRelation
import app.xreader.data.db.LoreType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoreMerger rules (PLAN §7 gate): alias matching, batch dedupe,
 * longer description wins, relations deduped — and a user-locked
 * entity is NEVER modified by extraction.
 */
class LoreMergerTest {

    private fun entity(
        name: String,
        description: String = "",
        aliases: List<String> = emptyList(),
        locked: Boolean = false,
        id: Long = 0
    ) = LoreEntity(
        id = id,
        bookId = 1,
        type = LoreType.CHARACTER,
        name = name,
        aliasesJson = LoreMerger.encodeAliases(aliases),
        description = description,
        firstChapterIndex = 0,
        userLocked = locked,
        updatedAt = 0
    )

    @Test
    fun `alias match merges instead of duplicating`() {
        val existing = listOf(
            entity("Jin Woo", description = "Short.", aliases = listOf("JW"), id = 5)
        )
        val plan = LoreMerger.merge(
            bookId = 1,
            existing = existing,
            existingRelations = emptyList(),
            extracted = listOf(
                ExtractedEntity(
                    LoreType.CHARACTER, "JW",
                    description = "A much longer and richer description of the hunter."
                )
            ),
            chapterIndex = 3,
            now = 100
        )
        assertTrue("no insert for an alias match", plan.inserts.isEmpty())
        assertEquals(1, plan.updates.size)
        assertEquals(5L, plan.updates.single().id)
        assertEquals(
            "longer description wins",
            "A much longer and richer description of the hunter.",
            plan.updates.single().description
        )
    }

    @Test
    fun `duplicates inside one batch merge with each other`() {
        val plan = LoreMerger.merge(
            bookId = 1,
            existing = emptyList(),
            existingRelations = emptyList(),
            extracted = listOf(
                ExtractedEntity(LoreType.CHARACTER, "Ara", description = "One."),
                ExtractedEntity(
                    LoreType.CHARACTER, "ara",
                    aliases = listOf("Princess"),
                    description = "A longer telling of Ara."
                )
            ),
            chapterIndex = 0,
            now = 1
        )
        assertEquals(1, plan.inserts.size)
        assertEquals("A longer telling of Ara.", plan.inserts.single().description)
        assertTrue(
            LoreMerger.decodeAliases(plan.inserts.single().aliasesJson)
                .contains("Princess")
        )
    }

    @Test
    fun `user locked entity is never modified`() {
        val existing = listOf(
            entity("Master Ryn", description = "The user's own words.", locked = true, id = 9)
        )
        val plan = LoreMerger.merge(
            bookId = 1,
            existing = existing,
            existingRelations = emptyList(),
            extracted = listOf(
                ExtractedEntity(
                    LoreType.CHARACTER, "Master Ryn",
                    description = "An AI description that must not overwrite the user's."
                )
            ),
            chapterIndex = 2,
            now = 50
        )
        assertTrue(plan.inserts.isEmpty())
        assertTrue("locked entity untouched", plan.updates.isEmpty())
    }

    @Test
    fun `relations dedupe on normalised endpoints and label`() {
        val existingRelations = listOf(
            LoreRelation(
                id = 1, bookId = 1, fromEntityId = 5, toEntityId = 6,
                relationLabel = "serves"
            )
        )
        val existing = listOf(
            entity("Jin Woo", id = 5),
            entity("The Guild", id = 6).copy(type = LoreType.FACTION)
        )
        val plan = LoreMerger.merge(
            bookId = 1,
            existing = existing,
            existingRelations = existingRelations,
            extracted = listOf(
                ExtractedEntity(
                    LoreType.CHARACTER, "Jin Woo",
                    relations = listOf(
                        ExtractedRelation("The Guild", "Serves"),
                        ExtractedRelation("The Guild", "leads")
                    )
                )
            ),
            chapterIndex = 1,
            now = 10
        )
        assertEquals(
            "only the genuinely new relation is planned",
            listOf("leads"),
            plan.newRelations.map { it.label }
        )
        assertEquals("jin woo", plan.newRelations.single().fromKey)
        assertEquals("the guild", plan.newRelations.single().toKey)
    }

    @Test
    fun `extraction json parses entities and relations`() {
        val parsed = LoreKeeper.parseExtraction(
            """
            {"entities":[
              {"type":"CHARACTER","name":"Ara","aliases":["Princess"],
               "description":"A runaway royal.",
               "relations":[{"to":"The Guild","label":"hides with"}]},
              {"type":"LOCATION","name":"Ember City","description":""}
            ]}
            """.trimIndent()
        )
        assertEquals(2, parsed?.size)
        val ara = parsed!!.first { it.name == "Ara" }
        assertEquals(listOf("Princess"), ara.aliases)
        assertEquals("hides with", ara.relations.single().label)
        assertEquals(LoreType.LOCATION, parsed.first { it.name == "Ember City" }.type)
    }

    @Test
    fun `garbage extraction is rejected`() {
        assertEquals(null, LoreKeeper.parseExtraction("not json at all"))
        assertEquals(null, LoreKeeper.parseExtraction("""{"wrong":true}"""))
    }
}
