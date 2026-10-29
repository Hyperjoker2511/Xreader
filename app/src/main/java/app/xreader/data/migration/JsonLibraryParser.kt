package app.xreader.data.migration

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The JSON + CSV side of the Migration Studio (PLAN §8): Mihon's
 * legacy JSON backups, and best-effort library lists from LNReader /
 * Tsukiyomi / Kototoro-style exports and plain spreadsheets. These
 * formats carry no schema contract, so every field is read through
 * alias lists and anything unrecognised is skipped — the import
 * report states what arrived, and unmatched entries are listed,
 * never silently dropped.
 */
object JsonLibraryParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): AppResult<MigratedLibrary> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: return AppResult.Err(AppError.BadFile("that file isn't readable JSON"))
        return when {
            root is JsonObject && root["mangas"] is JsonArray -> parseLegacyMihon(root)
            root is JsonArray -> genericBooks(root)?.let { AppResult.Ok(it) }
                ?: AppResult.Err(noBooks())
            root is JsonObject -> {
                val array = listOf("novels", "library", "books", "items", "entries")
                    .firstNotNullOfOrNull { key -> root[key] as? JsonArray }
                when {
                    array != null -> genericBooks(array)?.let { AppResult.Ok(it) }
                        ?: AppResult.Err(noBooks())
                    else -> AppResult.Err(noBooks())
                }
            }
            else -> AppResult.Err(noBooks())
        }
    }

    // -- Legacy Mihon / Tachiyomi JSON (version 2 shape) -------------------------

    private fun parseLegacyMihon(root: JsonObject): AppResult<MigratedLibrary> {
        val categories = (root["categories"] as? JsonArray).orEmpty().mapNotNull { element ->
            (element as? JsonObject)?.string("name")?.takeIf { it.isNotBlank() }
        }
        val books = (root["mangas"] as? JsonArray).orEmpty().mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val manga = (entry["manga"] as? JsonObject) ?: entry
            val title = manga.string("title")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val chapters = (entry["chapters"] as? JsonArray).orEmpty().mapNotNull { ch ->
                val chapter = ch as? JsonObject ?: return@mapNotNull null
                MigratedChapter(
                    name = chapter.string("name") ?: "",
                    url = chapter.string("url") ?: "",
                    read = chapter.bool("read"),
                    number = chapter.float("chapterNumber", "chapter_number"),
                    sourceOrder = chapter.long("sourceOrder", "source_order")
                )
            }
            val categoryNames = (entry["categories"] as? JsonArray).orEmpty()
                .mapNotNull { index ->
                    index.jsonPrimitive.longOrNull?.let { categories.getOrNull(it.toInt()) }
                }.distinct()
            MigratedBook(
                title = title,
                author = manga.string("author") ?: "",
                description = manga.string("description") ?: "",
                sourceName = manga.string("sourceName", "source_name") ?: "",
                sourceId = manga.long("source"),
                url = manga.string("url") ?: "",
                thumbnailUrl = manga.string("thumbnailUrl", "thumbnail_url"),
                status = manga.long("status")?.toInt(),
                chapters = chapters,
                categoryNames = categoryNames
            )
        }
        if (books.isEmpty()) return AppResult.Err(noBooks())
        return AppResult.Ok(
            MigratedLibrary(
                formatName = "Mihon / Tachiyomi JSON backup",
                books = books,
                categories = categories
            )
        )
    }

    // -- Generic library lists ------------------------------------------------------

    private fun genericBooks(array: JsonArray): MigratedLibrary? {
        val books = array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val title = obj.string("title", "name", "novelName", "bookName", "bookTitle")
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MigratedBook(
                title = title,
                author = obj.string("author", "authorName") ?: "",
                description = obj.string("description", "summary", "synopsis") ?: "",
                sourceName = obj.string(
                    "source", "sourceName", "pluginId", "provider", "site"
                ) ?: "",
                url = obj.string("url", "novelUrl", "bookUrl", "path", "link") ?: "",
                thumbnailUrl = obj.string("cover", "coverUrl", "thumbnailUrl", "image"),
                chapters = emptyList(),
                categoryNames = emptyList()
            )
        }
        if (books.isEmpty()) return null
        return MigratedLibrary(
            formatName = "Library list (JSON)",
            books = books,
            categories = emptyList()
        )
    }

    private fun noBooks() = AppError.BadFile(
        "no library entries could be read from that file — it may be a " +
            "format the Migration Studio doesn't know yet"
    )

    // -- tolerant field readers ------------------------------------------------------

    private fun JsonObject.string(vararg keys: String): String? {
        for (key in keys) {
            val value = (this[key] as? kotlinx.serialization.json.JsonPrimitive)
                ?.contentOrNull
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun JsonObject.long(vararg keys: String): Long? {
        for (key in keys) {
            val value = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull
            if (value != null) return value
        }
        return null
    }

    private fun JsonObject.float(vararg keys: String): Float? {
        for (key in keys) {
            val value = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.floatOrNull
            if (value != null) return value
        }
        return null
    }

    private fun JsonObject.bool(key: String): Boolean {
        val primitive = this[key] as? kotlinx.serialization.json.JsonPrimitive
            ?: return false
        return primitive.contentOrNull == "true" || primitive.contentOrNull == "1"
    }

    private fun List<JsonElement>?.orEmpty(): List<JsonElement> = this ?: emptyList()
}

/** A small CSV reader (RFC-4180-ish: quotes, escaped quotes,
 * commas, CRLF) for spreadsheet library exports. */
object CsvLibraryParser {

    fun parse(text: String): AppResult<MigratedLibrary> {
        val rows = parseRows(text)
        if (rows.size < 2) {
            return AppResult.Err(
                AppError.BadFile("that CSV doesn't have a header and any rows to read")
            )
        }
        val header = rows.first().map { it.trim().lowercase() }
        fun column(vararg names: String): Int =
            names.firstNotNullOfOrNull { name ->
                header.indexOf(name).takeIf { it >= 0 }
            } ?: -1
        val titleCol = column("title", "name", "book", "novel")
        if (titleCol < 0) {
            return AppResult.Err(
                AppError.BadFile("that CSV has no title column the studio can find")
            )
        }
        val authorCol = column("author", "authorname", "author name")
        val urlCol = column("url", "link", "novelurl", "bookurl")
        val sourceCol = column("source", "sourcename", "site", "provider", "pluginid")
        val books = rows.drop(1).mapNotNull { row ->
            val title = row.getOrNull(titleCol)?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            MigratedBook(
                title = title,
                author = if (authorCol >= 0) row.getOrNull(authorCol)?.trim() ?: "" else "",
                url = if (urlCol >= 0) row.getOrNull(urlCol)?.trim() ?: "" else "",
                sourceName = if (sourceCol >= 0) {
                    row.getOrNull(sourceCol)?.trim() ?: ""
                } else {
                    ""
                }
            )
        }
        if (books.isEmpty()) {
            return AppResult.Err(
                AppError.BadFile("no library entries could be read from that CSV")
            )
        }
        return AppResult.Ok(
            MigratedLibrary(
                formatName = "Library list (CSV)",
                books = books,
                categories = emptyList()
            )
        )
    }

    /** Splits CSV text into rows of fields. */
    fun parseRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        var field = StringBuilder()
        var inQuotes = false
        var i = 0
        fun endField() {
            row += field.toString()
            field = StringBuilder()
        }
        fun endRow() {
            endField()
            rows += row
            row = mutableListOf()
        }
        while (i < text.length) {
            val char = text[i]
            when {
                inQuotes -> {
                    if (char == '"') {
                        if (i + 1 < text.length && text[i + 1] == '"') {
                            field.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        field.append(char)
                    }
                }
                char == '"' && field.isEmpty() -> inQuotes = true
                char == ',' -> endField()
                char == '\n' -> endRow()
                char == '\r' -> Unit
                else -> field.append(char)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows.filter { candidate -> candidate.any { it.isNotBlank() } }
    }
}

/**
 * The studio's front door: sniff the bytes and route to the right
 * parser — gzip magic → `.tachibk`; `{`/`[` → JSON family; anything
 * else textual → CSV.
 */
object MigrationParsers {

    const val MAX_BACKUP_BYTES = 64 * 1024 * 1024

    fun parse(bytes: ByteArray): AppResult<MigratedLibrary> {
        if (bytes.isEmpty()) {
            return AppResult.Err(AppError.BadFile("that file is empty"))
        }
        if (bytes.size > MAX_BACKUP_BYTES) {
            return AppResult.Err(
                AppError.BadFile("that file is too large to be a library backup")
            )
        }
        val isGzip = bytes.size >= 2 &&
            bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        if (isGzip) return TachibkParser.parse(bytes)
        val text = bytes.toString(Charsets.UTF_8)
        val first = text.trimStart().firstOrNull()
        return when (first) {
            '{', '[' -> JsonLibraryParser.parse(text)
            else -> {
                // A raw (ungzipped) protobuf backup starts with a
                // tag byte, not text — try it before giving up to CSV.
                if (looksBinary(text)) {
                    TachibkParser.parse(bytes)
                } else {
                    CsvLibraryParser.parse(text)
                }
            }
        }
    }

    private fun looksBinary(text: String): Boolean {
        val sample = text.take(512)
        return sample.any { it.code < 0x09 }
    }
}
