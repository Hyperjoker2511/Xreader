package app.xreader.data.ai

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.Book
import app.xreader.data.db.Chapter
import app.xreader.data.db.LoreEntity
import app.xreader.data.db.LoreEntityDao
import app.xreader.data.db.LoreRelation
import app.xreader.data.db.LoreRelationDao
import app.xreader.data.db.LoreType
import app.xreader.data.files.BookStorage
import app.xreader.data.prefs.AiFeature
import app.xreader.data.prefs.AiSettings
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** One entity as the model reports it from a single chapter. */
data class ExtractedEntity(
    val type: LoreType,
    val name: String,
    val aliases: List<String> = emptyList(),
    val description: String = "",
    val relations: List<ExtractedRelation> = emptyList()
)

data class ExtractedRelation(val toName: String, val label: String)

/**
 * The merge rules (PLAN §7) as a pure function, so the knowledge base
 * behaves identically in tests and in the app:
 *  - match by normalised name or any alias, in either direction;
 *  - duplicates inside one extraction batch merge with each other;
 *  - a user-locked entity is NEVER modified by extraction (the user's
 *    edits win — §1.3), though relations may still point at it;
 *  - for unlocked entities the richer (longer) description wins and
 *    aliases union;
 *  - relations dedupe on (from, to, label), all normalised.
 */
object LoreMerger {

    data class NewRelation(val fromKey: String, val toKey: String, val label: String)

    data class Plan(
        val inserts: List<LoreEntity>,
        val updates: List<LoreEntity>,
        val newRelations: List<NewRelation>,
        /** Normalised name/alias → the entity key (normalised canonical
         * name) it resolved to, for relation wiring after inserts. */
        val keyByAlias: Map<String, String>
    )

    fun normalise(name: String): String =
        name.trim().lowercase().replace(Regex("\\s+"), " ")

    fun merge(
        bookId: Long,
        existing: List<LoreEntity>,
        existingRelations: List<LoreRelation>,
        extracted: List<ExtractedEntity>,
        chapterIndex: Int,
        now: Long
    ): Plan {
        // Resolution index: normalised name/alias -> canonical key.
        val keyByAlias = mutableMapOf<String, String>()
        val byKey = mutableMapOf<String, LoreEntity>()
        for (entity in existing) {
            val key = normalise(entity.name)
            byKey[key] = entity
            keyByAlias[key] = key
            decodeAliases(entity.aliasesJson).forEach { alias ->
                keyByAlias.putIfAbsent(normalise(alias), key)
            }
        }
        val inserts = LinkedHashMap<String, LoreEntity>()
        val updates = LinkedHashMap<String, LoreEntity>()

        fun working(key: String): LoreEntity? =
            updates[key] ?: inserts[key] ?: byKey[key]

        for (item in extracted) {
            val cleanName = item.name.trim()
            if (cleanName.isBlank()) continue
            val matchKey = keyByAlias[normalise(cleanName)]
                ?: item.aliases.firstNotNullOfOrNull { keyByAlias[normalise(it)] }
            if (matchKey == null) {
                val key = normalise(cleanName)
                val entity = LoreEntity(
                    bookId = bookId,
                    type = item.type,
                    name = cleanName,
                    aliasesJson = encodeAliases(item.aliases),
                    description = item.description.trim(),
                    firstChapterIndex = chapterIndex,
                    userLocked = false,
                    updatedAt = now
                )
                inserts[key] = entity
                keyByAlias[key] = key
                item.aliases.forEach { keyByAlias.putIfAbsent(normalise(it), key) }
                continue
            }
            val current = working(matchKey) ?: continue
            // Register every name this item answers to.
            keyByAlias.putIfAbsent(normalise(cleanName), matchKey)
            item.aliases.forEach { keyByAlias.putIfAbsent(normalise(it), matchKey) }
            if (current.userLocked) continue
            val aliasUnion = (decodeAliases(current.aliasesJson) + item.aliases +
                listOf(cleanName))
                .map { it.trim() }
                .filter { it.isNotBlank() && normalise(it) != normalise(current.name) }
                .distinctBy { normalise(it) }
            val description =
                if (item.description.trim().length > current.description.length) {
                    item.description.trim()
                } else {
                    current.description
                }
            val merged = current.copy(
                aliasesJson = encodeAliases(aliasUnion),
                description = description,
                firstChapterIndex = minOf(current.firstChapterIndex, chapterIndex),
                updatedAt = now
            )
            if (merged != current) {
                if (inserts.containsKey(matchKey)) {
                    inserts[matchKey] = merged.copy(id = inserts.getValue(matchKey).id)
                } else {
                    updates[matchKey] = merged
                }
            }
        }

        // Relations: resolve names through the final index; dedupe
        // against what the book already knows.
        val existingRelKeys = existingRelations.map {
            Triple(it.fromEntityId, it.toEntityId, normalise(it.relationLabel))
        }.toSet()
        val idByKey = mutableMapOf<String, Long>()
        for ((key, entity) in byKey) idByKey[key] = entity.id
        val seen = mutableSetOf<Triple<String, String, String>>()
        val newRelations = mutableListOf<NewRelation>()
        for (item in extracted) {
            val fromKey = keyByAlias[normalise(item.name)] ?: continue
            for (relation in item.relations) {
                val toKey = keyByAlias[normalise(relation.toName)] ?: continue
                if (toKey == fromKey) continue
                val label = relation.label.trim()
                if (label.isBlank()) continue
                val triple = Triple(fromKey, toKey, normalise(label))
                if (!seen.add(triple)) continue
                val fromId = idByKey[fromKey]
                val toId = idByKey[toKey]
                if (fromId != null && toId != null &&
                    Triple(fromId, toId, normalise(label)) in existingRelKeys
                ) {
                    continue
                }
                newRelations += NewRelation(fromKey, toKey, label)
            }
        }

        return Plan(
            inserts = inserts.values.toList(),
            updates = updates.values.toList(),
            newRelations = newRelations,
            keyByAlias = keyByAlias
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun encodeAliases(aliases: List<String>): String =
        json.encodeToString(ListSerializer(String.serializer()), aliases.distinct())

    fun decodeAliases(raw: String): List<String> =
        runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), raw)
        }.getOrDefault(emptyList())
}

/**
 * The LoreKeeper service (PLAN §7): per-chapter extraction into the
 * per-book knowledge base. Each chapter is extracted at most once per
 * content version (a marker file records the extracted hash per
 * chapter — no schema change), so the incremental triggers (chapter
 * read / chapter downloaded) never re-burn quota on the same text.
 */
class LoreKeeper(
    private val client: AiClient,
    private val entityDao: LoreEntityDao,
    private val relationDao: LoreRelationDao,
    private val storage: BookStorage,
    private val settingsProvider: suspend () -> AiSettings,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** Extract lore from one chapter's text. Returns how many entities
     * were inserted or updated (0 when already extracted / disabled). */
    suspend fun extractForChapter(
        book: Book,
        chapter: Chapter,
        chapterText: String,
        force: Boolean = false
    ): AppResult<Int> {
        if (!settingsProvider().enabled(AiFeature.LORE_KEEPER)) {
            return AppResult.Err(
                AppError.BadFile("LoreKeeper is turned off — switch it on in Providers & keys.")
            )
        }
        val hash = app.xreader.data.extension.Hashes.sha256Hex(chapterText)
        if (!force && extractedHash(book.id, chapter.id) == hash) {
            return AppResult.Ok(0)
        }
        val existing = entityDao.forBook(book.id)
        val extracted = when (
            val result = extract(book.title, chapter.title, chapterText, existing)
        ) {
            is AppResult.Err -> return result
            is AppResult.Ok -> result.value
        }
        val relations = relationDao.forBook(book.id)
        val plan = LoreMerger.merge(
            bookId = book.id,
            existing = existing,
            existingRelations = relations,
            extracted = extracted,
            chapterIndex = chapter.index,
            now = clock()
        )
        val idByKey = mutableMapOf<String, Long>()
        existing.forEach { idByKey[LoreMerger.normalise(it.name)] = it.id }
        for (insert in plan.inserts) {
            val id = entityDao.insert(insert)
            idByKey[LoreMerger.normalise(insert.name)] = id
        }
        plan.updates.forEach { entityDao.update(it) }
        for (relation in plan.newRelations) {
            val fromId = idByKey[relation.fromKey] ?: continue
            val toId = idByKey[relation.toKey] ?: continue
            relationDao.insert(
                LoreRelation(
                    bookId = book.id,
                    fromEntityId = fromId,
                    toEntityId = toId,
                    relationLabel = relation.label
                )
            )
        }
        markExtracted(book.id, chapter.id, hash)
        return AppResult.Ok(plan.inserts.size + plan.updates.size)
    }

    suspend fun wasExtracted(bookId: Long, chapter: Chapter, chapterText: String): Boolean =
        extractedHash(bookId, chapter.id) ==
            app.xreader.data.extension.Hashes.sha256Hex(chapterText)

    // -- extraction call --------------------------------------------------------

    private suspend fun extract(
        bookTitle: String,
        chapterTitle: String,
        chapterText: String,
        existing: List<LoreEntity>
    ): AppResult<List<ExtractedEntity>> {
        val known = existing.take(120).joinToString(", ") { it.name }
        val prompt = buildString {
            append("You maintain the lore glossary of the novel \"")
            append(bookTitle)
            append("\". Read this chapter (\"")
            append(chapterTitle)
            append("\") and list every named character, faction, location, item, and ")
            append("important term that appears, as ONE JSON object:\n")
            append("{\"entities\":[{\"type\":\"CHARACTER|FACTION|LOCATION|ITEM|TERM\",")
            append("\"name\":\"...\",\"aliases\":[\"...\"],")
            append("\"description\":\"1-2 plain sentences\",")
            append("\"relations\":[{\"to\":\"other entity name\",\"label\":\"e.g. serves, rival of\"}]}]}\n")
            if (known.isNotBlank()) {
                append("Entities already known (reuse these exact names when it's the ")
                append("same entity): ")
                append(known)
                append("\n")
            }
            append("Only entities that actually appear in this chapter. ")
            append("Answer with ONLY the JSON object.\n\n")
            append(chapterText.take(MAX_TEXT_CHARS))
        }
        val first = client.generateText(prompt, jsonMode = true)
        val text = when (first) {
            is AppResult.Err -> return first
            is AppResult.Ok -> first.value.text
        }
        parseExtraction(text)?.let { return AppResult.Ok(it) }
        // One repair retry (§7): models occasionally wrap or mangle JSON.
        val repair = client.generateText(
            "Your previous answer was not valid JSON. Reply with ONLY the JSON " +
                "object, nothing else.\n\nPrevious answer:\n" + text.take(6000),
            jsonMode = true
        )
        val repaired = when (repair) {
            is AppResult.Err -> return repair
            is AppResult.Ok -> repair.value.text
        }
        return parseExtraction(repaired)?.let { AppResult.Ok(it) }
            ?: AppResult.Err(
                AppError.ProviderDown(
                    GeminiClient.SERVICE_ID, retryable = false,
                    detail = "the model's lore answer wasn't readable JSON"
                )
            )
    }

    companion object {
        const val MAX_TEXT_CHARS = 16_000

        /** Model JSON → extracted entities (null = unreadable answer). */
        fun parseExtraction(text: String): List<ExtractedEntity>? {
            val root = AiJson.extractObject(text) ?: return null
            val items = root.objectListField("entities")
            if (items.isEmpty() && !root.containsKey("entities")) return null
            return items.mapNotNull { item ->
                val name = item.stringField("name") ?: return@mapNotNull null
                val type = item.stringField("type")
                    ?.let { raw ->
                        runCatching { LoreType.valueOf(raw.uppercase()) }.getOrNull()
                    } ?: LoreType.TERM
                ExtractedEntity(
                    type = type,
                    name = name,
                    aliases = item.stringListField("aliases"),
                    description = item.stringField("description") ?: "",
                    relations = item.objectListField("relations").mapNotNull { relation ->
                        val to = relation.stringField("to")
                            ?: relation.stringField("target")
                        val label = relation.stringField("label")
                            ?: relation.stringField("relation")
                        if (to != null && label != null) {
                            ExtractedRelation(to, label)
                        } else {
                            null
                        }
                    }
                )
            }
        }
    }

    // -- extraction markers (per book, on disk — no schema change) -------------

    private fun markerFile(bookId: Long) =
        storage.resolve("lore-state/$bookId.json")

    private fun readMarkers(bookId: Long): MutableMap<String, String> {
        val file = markerFile(bookId)
        if (!file.isFile) return mutableMapOf()
        return runCatching {
            Json.decodeFromString(
                kotlinx.serialization.builtins.MapSerializer(
                    String.serializer(), String.serializer()
                ),
                file.readText()
            ).toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    private fun extractedHash(bookId: Long, chapterId: Long): String? =
        readMarkers(bookId)[chapterId.toString()]

    private fun markExtracted(bookId: Long, chapterId: Long, hash: String) {
        val markers = readMarkers(bookId)
        markers[chapterId.toString()] = hash
        runCatching {
            storage.writeAtomic(
                markerFile(bookId),
                Json.encodeToString(
                    kotlinx.serialization.builtins.MapSerializer(
                        String.serializer(), String.serializer()
                    ),
                    markers
                )
            )
        }
    }
}
