package app.xreader.ui.localreader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * v1.5.10 (F2, build-map Group F): Book information INSIDE the
 * reader — Moon+'s in-reader dialog (plan F2), for both local
 * readers. This file is the component: the display model + its
 * honest builder, the reading-stats accumulation (the measured
 * -WPM logic ported from the Gemini re-run store's SHAPE into
 * the tree's own store class, with the addendum's per-day
 * `wordsRead` defect FIXED — days are actually credited), and
 * the sheet surface. Wiring (entry points, session feeding)
 * lives in the readers: the PDF reader wires it here; the EPUB
 * reader's wiring is L-DE's step against these same APIs.
 *
 * Honesty rules (plan F2, load-bearing):
 *  - HAS fields render the true value or are absent — never a
 *    placeholder number.
 *  - Reading time/speed/history accumulate FROM THIS BUILD
 *    forward; before any data exists the sheet says so
 *    ("counted from this update" / "Not enough reading yet").
 *  - EPUB total pages are an ESTIMATE (Q4 → A) and are
 *    labelled "estimated" wherever they appear.
 *  - Chars/Words show "counting…" while uncomputed, never a
 *    wrong number.
 *
 * Storage (per book, DataStore — Room schema stays v11): the
 * keys are the F-data keys specified for LocalReaderPrefs;
 * [BookStatsStore] is their implementation over the shared
 * "settings" store:
 *  - `localreader.<id>.stats.total_reading_millis` (Long)
 *  - `localreader.<id>.stats.total_words_advanced` (Long)
 *  - `localreader.<id>.stats.daily_history_json` (String)
 *  - `localreader.<id>.stats.text_counts_json` (String)
 *  - `localreader.<id>.common.star_rating` (Int, 0..5)
 */

// ── Stats model ──────────────────────────────────────────────────

/** One dated reading-history row (F2's "reading history in
 * days"). [wordsRead] is genuinely credited per day — the
 * re-run store's merge dropped it (sift addendum A4); this
 * port fixes that, and the gate test pins it. */
@Serializable
data class DailyReadingEntry(
    val dateIso: String,
    val durationMillis: Long,
    val wordsRead: Long,
    val endBookPercent: Float
) {
    /** This day's own measured speed, or null when the day
     * carries no credited words yet. */
    val wordsPerMinute: Int?
        get() {
            if (durationMillis < 1_000L || wordsRead <= 0L) return null
            return (wordsRead.toDouble() / (durationMillis / 60_000.0))
                .roundToInt().coerceAtLeast(1)
        }
}

/** Cached whole-book text counts (F2's Chars/Words): computed
 * once per book (PDF from the persisted strip; EPUB from a
 * one-time background sweep), then served from here. */
@Serializable
data class TextCounts(
    val chars: Long,
    val words: Long,
    val complete: Boolean
)

/** Everything accumulated for one book. */
data class BookStats(
    val totalReadingMillis: Long = 0L,
    val totalWordsAdvanced: Long = 0L,
    val dailyHistory: List<DailyReadingEntry> = emptyList(),
    val textCounts: TextCounts? = null,
    val starRating: Int = 0
) {
    val measuredWpm: Int? get() = measuredWpm(totalReadingMillis, totalWordsAdvanced)
}

/**
 * The measured reading speed (the re-run's logic, ported):
 * totals-based — accumulated advanced words over accumulated
 * reading minutes, with a 15 s floor below which no speed is
 * claimed. Null = not measured yet (the sheet's honest state),
 * never a fabricated constant.
 */
fun measuredWpm(totalReadingMillis: Long, totalWordsAdvanced: Long): Int? {
    if (totalReadingMillis < 15_000L || totalWordsAdvanced <= 0L) return null
    val minutes = totalReadingMillis / 60_000.0
    return (totalWordsAdvanced / minutes).roundToInt().coerceIn(20, 1500)
}

/** Q4 → A: Moon+-class estimated page count — total words at
 * 250 words per page, labelled estimated at every surface. */
fun estimatedTotalPages(totalWords: Long?): Int? {
    val words = totalWords ?: return null
    if (words <= 0L) return null
    return (words / 250L).toInt().coerceAtLeast(1)
}

/** Words in a text: whitespace-separated tokens. */
fun countWords(text: String): Long =
    text.split(Regex("\\s+")).count { it.isNotEmpty() }.toLong()

/** Merges one session delta into a day list — the FIXED merge:
 * an existing day gains BOTH its duration and its words; a new
 * day is created carrying both. Capped at 90 days, newest
 * first. */
fun mergeDailyHistory(
    existing: List<DailyReadingEntry>,
    dateIso: String,
    deltaMillis: Long,
    wordsAdvanced: Long,
    endBookPercent: Float
): List<DailyReadingEntry> {
    val safeMillis = deltaMillis.coerceAtLeast(0L)
    val safeWords = wordsAdvanced.coerceAtLeast(0L)
    val mutable = existing.toMutableList()
    val index = mutable.indexOfFirst { it.dateIso == dateIso }
    if (index >= 0) {
        val current = mutable[index]
        mutable[index] = current.copy(
            durationMillis = current.durationMillis + safeMillis,
            wordsRead = current.wordsRead + safeWords,
            endBookPercent = endBookPercent.coerceIn(0f, 100f)
        )
    } else {
        mutable += DailyReadingEntry(
            dateIso = dateIso,
            durationMillis = safeMillis,
            wordsRead = safeWords,
            endBookPercent = endBookPercent.coerceIn(0f, 100f)
        )
    }
    return mutable.sortedByDescending { it.dateIso }.take(90)
}

/**
 * Turns a reading session's progression movement into advanced
 * words: words advanced = progression delta × the book's total
 * words (the re-run's measurement basis). Backward movement
 * never subtracts (re-reading is still reading time, but the
 * totals-based WPM only credits forward progress — stated so
 * the number's meaning is never a surprise).
 */
class ReadingSessionAccumulator {
    private var lastProgression: Float? = null

    fun begin(progression: Float) {
        lastProgression = progression.coerceIn(0f, 1f)
    }

    /** Words advanced since the last sample (or [begin]). */
    fun sample(progression: Float, totalWords: Long?): Long {
        val now = progression.coerceIn(0f, 1f)
        val last = lastProgression
        lastProgression = now
        if (last == null || totalWords == null || totalWords <= 0L) return 0L
        val delta = (now - last).coerceAtLeast(0f)
        return (delta * totalWords).roundToInt().toLong()
    }
}

/** "yyyy-MM-dd" for [epochMs] — the daily rows' key. */
fun readingDayIso(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(epochMs))

// ── The stats store ──────────────────────────────────────────────

class BookStatsStore(private val store: DataStore<Preferences>) {

    fun stats(bookId: Long): Flow<BookStats> = store.data.map { prefs ->
        BookStats(
            totalReadingMillis = prefs[totalMillisKey(bookId)] ?: 0L,
            totalWordsAdvanced = prefs[totalWordsKey(bookId)] ?: 0L,
            dailyHistory = decodeDailyHistory(prefs[dailyHistoryKey(bookId)]),
            textCounts = decodeTextCounts(prefs[textCountsKey(bookId)]),
            starRating = (prefs[starRatingKey(bookId)] ?: 0).coerceIn(0, 5)
        )
    }

    /** Records one finished session slice: totals + the day
     * row (words credited — see [mergeDailyHistory]). A slice
     * with no time and no words records nothing. */
    suspend fun recordSession(
        bookId: Long,
        dateIso: String,
        deltaMillis: Long,
        wordsAdvanced: Long,
        endBookPercent: Float
    ) {
        if (deltaMillis <= 0L && wordsAdvanced <= 0L) return
        store.edit { prefs ->
            prefs[totalMillisKey(bookId)] =
                (prefs[totalMillisKey(bookId)] ?: 0L) + deltaMillis.coerceAtLeast(0L)
            prefs[totalWordsKey(bookId)] =
                (prefs[totalWordsKey(bookId)] ?: 0L) + wordsAdvanced.coerceAtLeast(0L)
            val merged = mergeDailyHistory(
                existing = decodeDailyHistory(prefs[dailyHistoryKey(bookId)]),
                dateIso = dateIso,
                deltaMillis = deltaMillis,
                wordsAdvanced = wordsAdvanced,
                endBookPercent = endBookPercent
            )
            prefs[dailyHistoryKey(bookId)] = encodeDailyHistory(merged)
        }
    }

    suspend fun saveTextCounts(bookId: Long, counts: TextCounts) {
        store.edit { prefs ->
            prefs[textCountsKey(bookId)] = encodeTextCounts(counts)
        }
    }

    suspend fun saveStarRating(bookId: Long, rating: Int) {
        store.edit { prefs ->
            prefs[starRatingKey(bookId)] = rating.coerceIn(0, 5)
        }
    }

    companion object {
        fun totalMillisKey(bookId: Long) =
            longPreferencesKey("localreader.$bookId.stats.total_reading_millis")
        fun totalWordsKey(bookId: Long) =
            longPreferencesKey("localreader.$bookId.stats.total_words_advanced")
        fun dailyHistoryKey(bookId: Long) =
            stringPreferencesKey("localreader.$bookId.stats.daily_history_json")
        fun textCountsKey(bookId: Long) =
            stringPreferencesKey("localreader.$bookId.stats.text_counts_json")
        fun starRatingKey(bookId: Long) =
            intPreferencesKey("localreader.$bookId.common.star_rating")
    }
}

private val statsJson = Json { ignoreUnknownKeys = true }

fun encodeDailyHistory(entries: List<DailyReadingEntry>): String =
    statsJson.encodeToString(ListSerializer(DailyReadingEntry.serializer()), entries)

fun decodeDailyHistory(raw: String?): List<DailyReadingEntry> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        statsJson.decodeFromString(ListSerializer(DailyReadingEntry.serializer()), raw)
    }.getOrDefault(emptyList())
}

fun encodeTextCounts(counts: TextCounts): String =
    statsJson.encodeToString(TextCounts.serializer(), counts)

fun decodeTextCounts(raw: String?): TextCounts? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        statsJson.decodeFromString(TextCounts.serializer(), raw)
    }.getOrNull()
}

// ── The display model + its honest builder ───────────────────────

/** The sheet's whole input, pre-digested: every field is
 * either a true value or null (absent / not-yet-known) — the
 * sheet never invents a number. */
data class BookInfoModel(
    val title: String,
    val contributors: List<String>,
    val fileName: String?,
    val location: String?,
    val fileSizeBytes: Long?,
    val format: ReaderFormat,
    /** PDF only: the file's real page count. */
    val totalPages: Int?,
    /** EPUB only: chapter (spine/outline) count. */
    val totalChapters: Int?,
    val currentChapterOneBased: Int?,
    val currentChapterTitle: String?,
    val currentPageOneBased: Int?,
    /** EPUB: total words / 250, LABELLED estimated. */
    val estimatedTotalPages: Int?,
    val charsInBook: Long?,
    val wordsInBook: Long?,
    /** False while a count is still being computed. */
    val countsComplete: Boolean,
    val totalReadingMillis: Long,
    val measuredWpm: Int?,
    val dailyHistory: List<DailyReadingEntry>,
    val wordsRemainingInChapter: Long?,
    val wordsRemainingInBook: Long?,
    val starRating: Int
) {
    /** Minutes left in chapter / book at the measured speed;
     * null until a speed exists (never estimated from a
     * constant). */
    val timeLeftChapterMinutes: Long?
        get() = timeLeftMinutes(wordsRemainingInChapter, measuredWpm)
    val timeLeftBookMinutes: Long?
        get() = timeLeftMinutes(wordsRemainingInBook, measuredWpm)
}

fun timeLeftMinutes(wordsRemaining: Long?, wpm: Int?): Long? {
    val words = wordsRemaining ?: return null
    val speed = wpm ?: return null
    if (speed <= 0) return null
    if (words <= 0L) return 0L
    return (words.toDouble() / speed).roundToInt().toLong().coerceAtLeast(1L)
}

/** Inputs the readers gather for [buildBookInfoModel]. Live
 * counts (the PDF strip's, when landed) win over the cached
 * [BookStats.textCounts]; EPUB passes its sweep's cache. */
data class BookInfoInputs(
    val title: String,
    val contributors: List<String>,
    val fileName: String?,
    val location: String?,
    val fileSizeBytes: Long?,
    val format: ReaderFormat,
    val totalPages: Int? = null,
    val totalChapters: Int? = null,
    val currentChapterOneBased: Int? = null,
    val currentChapterTitle: String? = null,
    val currentPageOneBased: Int? = null,
    val liveCounts: TextCounts? = null,
    val wordsRemainingInChapter: Long? = null,
    val bookProgression: Float = 0f
)

fun buildBookInfoModel(inputs: BookInfoInputs, stats: BookStats): BookInfoModel {
    val counts = inputs.liveCounts ?: stats.textCounts
    val words = counts?.words
    val remainingBook = words?.let { total ->
        (total - (inputs.bookProgression.coerceIn(0f, 1f) * total))
            .roundToInt().toLong().coerceIn(0L, total)
    }
    return BookInfoModel(
        title = inputs.title,
        contributors = inputs.contributors,
        fileName = inputs.fileName,
        location = inputs.location,
        fileSizeBytes = inputs.fileSizeBytes,
        format = inputs.format,
        totalPages = if (inputs.format == ReaderFormat.PDF) inputs.totalPages else null,
        totalChapters = if (inputs.format == ReaderFormat.EPUB) inputs.totalChapters else null,
        currentChapterOneBased = inputs.currentChapterOneBased,
        currentChapterTitle = inputs.currentChapterTitle,
        currentPageOneBased = inputs.currentPageOneBased,
        estimatedTotalPages = if (inputs.format == ReaderFormat.EPUB) {
            estimatedTotalPages(words)
        } else {
            null
        },
        charsInBook = counts?.chars,
        wordsInBook = words,
        countsComplete = counts?.complete ?: false,
        totalReadingMillis = stats.totalReadingMillis,
        measuredWpm = stats.measuredWpm,
        dailyHistory = stats.dailyHistory,
        wordsRemainingInChapter = inputs.wordsRemainingInChapter,
        wordsRemainingInBook = remainingBook,
        starRating = stats.starRating
    )
}

// ── Formatting (the return's estimator shapes, tree numbers) ────

fun formatBookFileSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
}

fun formatReadingDuration(millis: Long): String {
    val totalMinutes = (millis.coerceAtLeast(0L) / 60_000L)
    val hours = totalMinutes / 60L
    val mins = totalMinutes % 60L
    return if (hours > 0L) "$hours hrs $mins mins" else "$mins mins"
}

fun formatTimeLeft(minutes: Long?): String =
    minutes?.let { formatReadingDuration(it * 60_000L) } ?: "Not enough reading yet"

// ── The sheet ────────────────────────────────────────────────────

/**
 * The Book information sheet's CONTENT (the caller hosts it
 * in the readers' sheet chrome — tier-1 container, 24 dp top
 * radius — like every other reader sheet). [onOpenBookPage]
 * and [onShare] are nullable doors: a reader that cannot
 * provide the door passes null and the row is ABSENT, never a
 * dead button.
 */
@Composable
internal fun BookInfoSheetContent(
    model: BookInfoModel,
    isFavourite: Boolean,
    onToggleFavourite: () -> Unit,
    onStarRatingChanged: (Int) -> Unit,
    onOpenBookPage: (() -> Unit)?,
    onShare: (() -> Unit)?
) {
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            model.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        if (model.contributors.isNotEmpty()) {
            Text(
                model.contributors.joinToString(", "),
                style = MaterialTheme.typography.bodyMedium,
                color = ReaderViolet
            )
        }
        Spacer(Modifier.height(4.dp))

        model.fileName?.let { BookInfoRow("Filename", it) }
        model.location?.let { BookInfoRow("Location", it) }
        model.fileSizeBytes?.let {
            BookInfoRow("File size", formatBookFileSize(it))
        }
        if (model.format == ReaderFormat.PDF) {
            model.totalPages?.let { BookInfoRow("Total pages", "$it") }
            model.currentPageOneBased?.let { current ->
                BookInfoRow(
                    "Current page",
                    if (model.totalPages != null) {
                        "$current / ${model.totalPages}"
                    } else {
                        "$current"
                    }
                )
            }
        } else {
            model.totalChapters?.let { BookInfoRow("Total chapters", "$it") }
            model.estimatedTotalPages?.let {
                BookInfoRow("Total pages (estimated)", "$it")
            }
            if (model.currentChapterOneBased != null) {
                val chapterText = buildString {
                    append("${model.currentChapterOneBased}")
                    model.totalChapters?.let { append(" / $it") }
                    model.currentChapterTitle?.takeIf { t -> t.isNotBlank() }
                        ?.let { append(" — $it") }
                }
                BookInfoRow("Current chapter", chapterText)
            }
        }

        if (model.charsInBook == null || model.wordsInBook == null) {
            BookInfoRow("Chars / words", "Counting…")
        } else {
            val suffix = if (model.countsComplete) "" else " (counting…)"
            BookInfoRow("Chars in book", "${model.charsInBook}$suffix")
            BookInfoRow("Words in book", "${model.wordsInBook}$suffix")
        }

        if (model.totalReadingMillis <= 0L) {
            BookInfoRow("Reading time", "Counted from this update")
        } else {
            BookInfoRow(
                "Reading time",
                formatReadingDuration(model.totalReadingMillis)
            )
        }
        BookInfoRow(
            "Reading speed",
            model.measuredWpm?.let { "$it words/min" } ?: "Not enough reading yet"
        )
        BookInfoRow(
            "Time left in chapter",
            formatTimeLeft(model.timeLeftChapterMinutes)
        )
        BookInfoRow(
            "Time left in book",
            formatTimeLeft(model.timeLeftBookMinutes)
        )

        // Star rating (per-book DataStore — schema stays v11).
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Star rating",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row {
                for (star in 1..5) {
                    IconButton(onClick = { onStarRatingChanged(star) }) {
                        Icon(
                            if (star <= model.starRating) {
                                Icons.Filled.Star
                            } else {
                                Icons.Filled.StarBorder
                            },
                            contentDescription = "Rate $star stars",
                            tint = if (star <= model.starRating) {
                                ReaderViolet
                            } else {
                                ReaderMutedText
                            }
                        )
                    }
                }
            }
        }

        // Favourite (HAS — the existing favourite toggle).
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("Favourite", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Shown on the Library's Favourites shelf.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = isFavourite,
                onCheckedChange = { onToggleFavourite() },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = ReaderViolet,
                    checkedThumbColor = Color.White,
                    uncheckedTrackColor = ReaderTier2Surface,
                    uncheckedThumbColor = ReaderMutedText
                )
            )
        }

        if (model.dailyHistory.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Reading history",
                style = MaterialTheme.typography.titleSmall,
                color = ReaderViolet
            )
            model.dailyHistory.forEach { entry ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(entry.dateIso, style = MaterialTheme.typography.bodyMedium)
                    val speed = entry.wordsPerMinute?.let { " · $it w/m" } ?: ""
                    Text(
                        "${formatReadingDuration(entry.durationMillis)}$speed" +
                            " · ${entry.endBookPercent.toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (onOpenBookPage != null) {
            TextButton(onClick = onOpenBookPage) { Text("Book page") }
        }
        if (onShare != null) {
            TextButton(onClick = onShare) { Text("Share book") }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun BookInfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.58f)
        )
    }
}
