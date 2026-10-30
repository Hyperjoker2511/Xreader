package app.xreader.data.ai

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.Book
import app.xreader.data.db.Chapter
import app.xreader.data.db.LoreArt
import app.xreader.data.db.LoreArtDao
import app.xreader.data.db.LoreEntity
import app.xreader.data.files.BookStorage
import app.xreader.data.prefs.AiFeature
import app.xreader.data.prefs.AiSettings
import java.io.File

/**
 * Concept art (PLAN §7): pictures for the book's people and places,
 * generated from LoreKeeper descriptions (or a quoted passage) and
 * saved into the book's lore-art folder + a [LoreArt] row.
 *
 * Honest failure is a designed outcome here (assumption A5): free
 * Gemini keys may not include any image model. When every candidate
 * refuses, the feature says so in plain words with the model's own
 * message in the Details line — it never blocks anything else.
 */
class ConceptArt(
    private val client: AiClient,
    private val artDao: LoreArtDao,
    private val storage: BookStorage,
    private val transcoder: ImageTranscoder,
    private val settingsProvider: suspend () -> AiSettings,
    private val clock: () -> Long = System::currentTimeMillis
) {

    suspend fun generateForEntity(book: Book, entity: LoreEntity): AppResult<LoreArt> {
        val prompt = buildString {
            append("Painterly fantasy novel concept art, rich colour, no text, no words. ")
            append("Subject: ")
            append(entity.name)
            append(" — a ")
            append(entity.type.name.lowercase())
            append(" from the novel \"")
            append(book.title)
            append("\". ")
            if (entity.description.isNotBlank()) {
                append("What the book says: ")
                append(entity.description)
            }
        }
        return generate(
            book = book,
            entityId = entity.id,
            chapterId = null,
            passageQuote = entity.description.take(280),
            prompt = prompt
        )
    }

    suspend fun generateForPassage(
        book: Book,
        chapter: Chapter,
        passage: String
    ): AppResult<LoreArt> {
        val prompt = buildString {
            append("Painterly fantasy novel illustration of this exact moment, ")
            append("rich colour, no text, no words. From the novel \"")
            append(book.title)
            append("\", chapter \"")
            append(chapter.title)
            append("\": ")
            append(passage.take(600))
        }
        return generate(
            book = book,
            entityId = null,
            chapterId = chapter.id,
            passageQuote = passage.take(280),
            prompt = prompt
        )
    }

    private suspend fun generate(
        book: Book,
        entityId: Long?,
        chapterId: Long?,
        passageQuote: String,
        prompt: String
    ): AppResult<LoreArt> {
        if (!settingsProvider().enabled(AiFeature.CONCEPT_ART)) {
            return AppResult.Err(
                AppError.NeutralVerdict("Concept art is turned off — switch it on in Providers & keys.")
            )
        }
        val image = when (val result = client.generateImage(prompt)) {
            // The client's error is already in the app's vocabulary
            // (bad key → AuthFailed; model refusal → ProviderDown with
            // Google's own words in the Details line).
            is AppResult.Err -> return result
            is AppResult.Ok -> result.value
        }
        val now = clock()
        val converted = transcoder.toWebP(image.bytes, image.mimeType)
        val (bytes, extension) = if (converted != null) {
            converted.first to converted.second
        } else {
            image.bytes to extensionFor(image.mimeType)
        }
        val fileName = "art-$now.$extension"
        return try {
            val target = File(storage.loreArtDir(book.id), fileName)
            target.writeBytes(bytes)
            val row = LoreArt(
                bookId = book.id,
                entityId = entityId,
                chapterId = chapterId,
                passageQuote = passageQuote,
                prompt = prompt,
                imagePath = storage.loreArtRelative(book.id, fileName),
                createdAt = now
            )
            val id = artDao.insert(row)
            AppResult.Ok(row.copy(id = id))
        } catch (e: Exception) {
            AppResult.Err(AppError.StorageFailed("saving the picture", e.message?.take(160)))
        }
    }

    companion object {
        fun extensionFor(mimeType: String): String = when {
            mimeType.contains("webp") -> "webp"
            mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
            else -> "png"
        }
    }
}
