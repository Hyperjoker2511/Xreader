package app.xreader.data.migration

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult

/**
 * Mihon `.tachibk` import (PLAN §8): gzip + protobuf, schema pinned
 * to Mihon's open-source backup format as of Mihon 0.16/0.17
 * (`Backup` / `BackupManga` / `BackupChapter` / `BackupCategory` /
 * `BackupSource`). Only the fields Xreader can honour are read;
 * tracking, preferences and viewer settings are skipped by the wire
 * reader and named in the import report as not carried.
 *
 * Field map (protobuf numbers):
 *  Backup:        1 = manga[], 2 = categories[], 101 = sources[]
 *  BackupManga:   1 source, 2 url, 3 title, 4 artist, 5 author,
 *                 6 description, 7 genre[], 8 status, 9 thumbnail,
 *                 13 dateAdded, 16 chapters[], 17 category indexes
 *  BackupChapter: 1 url, 2 name, 4 read, 9 number (fixed32 float),
 *                 10 sourceOrder
 *  BackupCategory: 1 name, 2 order
 *  BackupSource:  1 name, 2 sourceId
 */
object TachibkParser {

    fun parse(raw: ByteArray): AppResult<MigratedLibrary> {
        val payload = ProtoMessage.gunzipIfNeeded(raw)
            ?: return AppResult.Err(
                AppError.BadFile("that backup couldn't be unpacked (it may be damaged)")
            )
        val root = ProtoMessage.parse(payload)
            ?: return AppResult.Err(
                AppError.BadFile("that doesn't look like a Mihon .tachibk backup")
            )
        val manga = root.messages(1)
        if (manga.isEmpty()) {
            return AppResult.Err(
                AppError.BadFile(
                    "no library entries were found in that backup — " +
                        "if it's from a newer Mihon, the format may have moved on"
                )
            )
        }
        val categories = root.messages(2).mapNotNull { category ->
            val name = category.firstString(1)?.takeIf { it.isNotBlank() }
            val order = category.firstVarint(2) ?: 0L
            name?.let { it to order }
        }.sortedBy { it.second }.map { it.first }
        val sourceNames = root.messages(101).mapNotNull { source ->
            val id = source.firstVarint(2)
            val name = source.firstString(1)
            if (id != null && name != null) id to name else null
        }.toMap()

        val books = manga.map { entry ->
            val sourceId = entry.firstVarint(1)
            val categoryNames = entry.varints(17).mapNotNull { index ->
                categories.getOrNull(index.toInt())
            }.distinct()
            MigratedBook(
                title = entry.firstString(3)?.takeIf { it.isNotBlank() } ?: "Untitled",
                author = entry.firstString(5) ?: "",
                description = entry.firstString(6) ?: "",
                sourceName = sourceId?.let { sourceNames[it] } ?: "",
                sourceId = sourceId,
                url = entry.firstString(2) ?: "",
                thumbnailUrl = entry.firstString(9),
                status = entry.firstVarint(8)?.toInt(),
                chapters = entry.messages(16).map { chapter ->
                    MigratedChapter(
                        name = chapter.firstString(2) ?: "",
                        url = chapter.firstString(1) ?: "",
                        read = chapter.bool(4),
                        number = chapter.firstFloat(9),
                        sourceOrder = chapter.firstVarint(10)
                    )
                },
                categoryNames = categoryNames
            )
        }
        return AppResult.Ok(
            MigratedLibrary(
                formatName = "Mihon backup (.tachibk)",
                books = books,
                categories = categories
            )
        )
    }
}
