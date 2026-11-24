package app.xreader.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.xreader.ui.theme.FrauncesSerif
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.core.motion.pressSquish
import app.xreader.di.AppContainer
import app.xreader.ui.common.EmptyState
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.RollingNumberText
import app.xreader.ui.common.ScreenHeader
import app.xreader.ui.common.SectionHeader
import app.xreader.ui.common.rememberHoverEmptyState
import app.xreader.ui.theme.primaryTextColor
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.xreader.ui.theme.LocalDockClearance
import app.xreader.ui.theme.LocalUiMetrics
import app.xreader.ui.theme.effectiveBottomPadding

/**
 * v1.1.0 (§5.13 + Q11): the History Reading Pulse — today's real
 * aggregates, read from the SAME store Profile reads:
 * `daily_activity` (one row per local day, written by the session
 * recorder and paid into by [app.xreader.data.game.ExpService]).
 * minutesRead / chaptersFinished / expEarned are that row's own
 * fields — the exact values Profile's "today" surfaces use.
 * Books-today is counted in the screen from the Today group's
 * entries (ReadingProgress rows updated today) — no other book
 * count for "today" exists in the app, and none is invented.
 */
data class ReadingPulse(
    val minutesToday: Int = 0,
    val chaptersToday: Int = 0,
    val expToday: Long = 0
)

/**
 * History: everything the reader has been reading, newest first.
 * v1.5.2 (FIX-9, §15): the read path is the history_records store
 * (one record per book, flat most-recent-first — the QuickNovel
 * copy); remove deletes the record, clear wipes the store only,
 * so neither touches progress, bookmarks or downloads.
 */
/** One flat History row (§15): the record's own fields plus the
 * joined resume position and library state. */
data class HistoryEntryUi(
    val bookKey: String,
    val bookId: Long?,
    val title: String,
    val sourceName: String,
    val coverPath: String?,
    val coverUrl: String?,
    val chapterIndex: Int,
    val chapterTitle: String?,
    val fraction: Float,
    val recordedAt: Long,
    val inLibrary: Boolean,
    val bookExists: Boolean,
    /** v1.5.34 (item 2): the book row itself, when it still
     * exists — the delete sheet (BookDeleteDialogs) and its
     * handlers work on the row, exactly as the Library's do. */
    val book: app.xreader.data.db.Book? = null
)

class HistoryViewModel(private val container: AppContainer) : ViewModel() {

    /** Chapter titles resolved off the combine path (DAO reads). */
    private val chapterTitles =
        kotlinx.coroutines.flow.MutableStateFlow<Map<Pair<Long, Int>, String>>(emptyMap())

    init {
        viewModelScope.launch {
            container.database.readingProgressDao().observeRecent().collect { rows ->
                val resolved = mutableMapOf<Pair<Long, Int>, String>()
                for (row in rows) {
                    val chapter = runCatching {
                        container.database.chapterDao()
                            .getByIndex(row.bookId, row.chapterIndex)
                    }.getOrNull()
                    if (chapter != null) {
                        resolved[row.bookId to row.chapterIndex] = chapter.title
                    }
                }
                chapterTitles.value = resolved
            }
        }
    }

    /**
     * v1.5.2 fix (FIX-9, §15): the History read path IS the
     * history_records store — one record per book, flat,
     * most-recent-first, NO day grouping, no HistoryPrefs sidecar.
     * Resume position (chapter + fraction) still comes from
     * ReadingProgress, joined by book id; the record itself never
     * stores a chapter (QuickNovel copy).
     */
    val entries: StateFlow<List<HistoryEntryUi>> = combine(
        container.database.historyRecordDao().observeAll(),
        container.database.bookDao().observeAllBooks(),
        container.database.readingProgressDao().observeRecent(),
        chapterTitles
    ) { records, books, progress, titles ->
        val booksById = books.associateBy { it.id }
        val progressByBook = progress.associateBy { it.bookId }
        records.map { record ->
            val book = record.bookId?.let { booksById[it] }
            val prog = record.bookId?.let { progressByBook[it] }
            val chapterIndex = prog?.chapterIndex ?: 0
            HistoryEntryUi(
                bookKey = record.bookKey,
                bookId = record.bookId,
                title = record.title,
                sourceName = record.sourceName,
                coverPath = book?.coverPath,
                coverUrl = record.coverUrl ?: book?.coverUrl,
                chapterIndex = chapterIndex,
                chapterTitle = record.bookId?.let {
                    titles[it to chapterIndex]
                },
                fraction = prog?.fraction ?: 0f,
                recordedAt = record.recordedAt,
                inLibrary = book?.inLibrary ?: false,
                bookExists = book != null,
                book = book
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The Pulse's numbers: today's `daily_activity` row, observed
     * live — the same DAO + row ProfileViewModel reads for its
     * today stats (`dailyActivityDao().get(today)` there,
     * `observeRange(today, today)` here so the card stays current
     * while the screen is open).
     */
    val pulse: StateFlow<ReadingPulse> = run {
        val today = LocalDate.now(ZoneId.systemDefault()).toString()
        container.database.dailyActivityDao().observeRange(today, today)
            .map { rows ->
                val row = rows.firstOrNull()
                if (row == null) {
                    ReadingPulse()
                } else {
                    ReadingPulse(
                        minutesToday = row.minutesRead,
                        chaptersToday = row.chaptersFinished,
                        expToday = row.expEarned
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingPulse())
    }

    /** §15: removing from History DELETES the record — the book,
     * its downloads and the reader's place are untouched (History
     * and downloads are separate domains). Re-reading the book
     * writes a FRESH record (recordHistoryStart upserts by key). */
    fun removeEntry(bookKey: String) {
        viewModelScope.launch {
            container.database.historyRecordDao().deleteByKey(bookKey)
        }
    }

    /** Code 121 (R3): the M3 list's swipe-remove — the record
     * is read out BEFORE the delete so the snackbar's Undo can
     * restore exactly it (upsert by its own key). Same §15 line:
     * only the history record moves, in both directions. */
    fun removeEntryWithUndo(
        bookKey: String,
        onRemoved: (app.xreader.data.db.HistoryRecord?) -> Unit
    ) {
        viewModelScope.launch {
            val record = container.database.historyRecordDao()
                .getByKey(bookKey)
            container.database.historyRecordDao().deleteByKey(bookKey)
            onRemoved(record)
        }
    }

    /** Code 121 (R3): Undo for [removeEntryWithUndo]. */
    fun restoreEntry(record: app.xreader.data.db.HistoryRecord) {
        viewModelScope.launch {
            container.database.historyRecordDao().upsert(record)
        }
    }

    /** §15: Clear history wipes the history store ONLY. */
    fun clearAll() {
        viewModelScope.launch {
            container.database.historyRecordDao().clearAll()
        }
    }

    /** §15.1 flip: a book removed from the Library (soft) is added
     * back from its History entry — the pure inLibrary flip, with
     * none of recordHistoryStart's tombstone side effects. */
    fun addBackToLibrary(bookId: Long) {
        viewModelScope.launch {
            val book = container.database.bookDao().getById(bookId)
                ?: return@launch
            if (!book.inLibrary) {
                container.database.bookDao()
                    .update(book.copy(inLibrary = true))
            }
        }
    }

    // ── v1.5.34 (item 2): the delete-sheet handlers ──────────
    // The SAME container operations the Library's handlers call
    // (LibraryViewModel.removeBook / removeLocalBook) — History
    // only gains the entry point and this thin delegation; the
    // deletion logic itself is not forked. Note the §15 line
    // stays intact: these are FULL book deletes (a different,
    // heavier act than the record-only Remove above), reached
    // only through the sheet's own explicit choices.

    private val _messages = kotlinx.coroutines.flow.MutableSharedFlow<String>(
        extraBufferCapacity = 4
    )
    val messages: kotlinx.coroutines.flow.SharedFlow<String> =
        _messages

    /** The delete sheet's size line — the same cached storage
     * read the Library's sheet uses. */
    suspend fun downloadedSizeBytes(
        book: app.xreader.data.db.Book
    ): Long = container.bookStorage.bookFilesSizeBytesCached(book)

    fun removeBook(book: app.xreader.data.db.Book, deleteFiles: Boolean) {
        viewModelScope.launch {
            if (deleteFiles) {
                container.deleteBookFully(book)
            } else {
                container.removeFromLibrarySoft(book)
            }
            _messages.emit("Removed “${book.title}” from your library.")
        }
    }

    fun removeLocalBook(
        book: app.xreader.data.db.Book,
        mode: app.xreader.data.library.LocalBookDeleteMode,
        context: android.content.Context?
    ) {
        viewModelScope.launch {
            when (container.localBookDeletion.deleteLocalBook(book, mode, context)) {
                is app.xreader.core.error.AppResult.Ok ->
                    _messages.emit("Removed “${book.title}”.")
                is app.xreader.core.error.AppResult.Err ->
                    _messages.emit(
                        "That delete didn't finish — the book is still here."
                    )
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HistoryViewModel(container) as T
            }
    }
}

/** Plain relative time for a history entry — real field only
 * (HistorySource.updatedAtMillis). */
private fun timeAgo(updatedAtMillis: Long, nowMillis: Long): String {
    val minutes = ((nowMillis - updatedAtMillis) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 1L -> "Just now"
        minutes < 60L -> "$minutes min ago"
        minutes < 60L * 24L -> "${minutes / 60L} h ago"
        else -> "${minutes / (60L * 24L)} d ago"
    }
}

private fun formatMinutes(totalMinutes: Int): String = when {
    totalMinutes < 60 -> "$totalMinutes min"
    totalMinutes % 60 == 0 -> "${totalMinutes / 60} h"
    else -> "${totalMinutes / 60} h ${totalMinutes % 60} min"
}

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    coverFile: (String?) -> File?,
    onBack: () -> Unit,
    onOpenBook: (bookId: Long, chapterIndex: Int) -> Unit,
    /** v1.0.4 (D13): gates the empty-state hover (shell passes it). */
    reduceMotion: Boolean = false
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val pulse by viewModel.pulse.collectAsStateWithLifecycle()
    // Code 120 (§2.11 History): the Material 3 design's
    // grouping + density knobs. Under Nocturne both are inert
    // and the list is the settled flat list, byte-identical.
    val designState = app.xreader.ui.theme.LocalDesignState.current
    val m3History = designState.design ==
        app.xreader.data.prefs.AppDesign.MATERIAL3
    val historyCompact = m3History &&
        designState.historyDensity ==
            app.xreader.data.prefs.M3DensityChoice.COMPACT
    val historyByDay = m3History &&
        designState.historyGrouping ==
            app.xreader.data.prefs.M3HistoryGrouping.BY_DAY
    // Hoisted locals (run #54's lesson).
    val entriesNow = entries
    val pulseNow = pulse
    var confirmClear by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<HistoryEntryUi?>(null) }
    // v1.5.34 (item 2): the book whose full delete sheet is
    // open (the shared BookDeleteDialogs — the Library's sheet).
    var deleteTarget by remember {
        mutableStateOf<app.xreader.data.db.Book?>(null)
    }
    // Delete outcomes surface as a plain toast (History has no
    // snackbar host of its own; the Library's twin messages ride
    // the shell's snackbar there).
    val historyContext = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            android.widget.Toast.makeText(
                historyContext, message, android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    // v1.5.2 fix (FIX-9): books-today counts history_records
    // recorded today — the same store the list reads.
    val startOfToday = LocalDate.now(ZoneId.systemDefault())
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val booksToday = entriesNow.count { it.recordedAt >= startOfToday }
    // Code 121 (R3): the Material 3 History — medium flexible
    // bar, day-grouped card-less list with sticky date headers,
    // swipe-remove + snackbar Undo. The Nocturne composition
    // below is unchanged.
    if (m3History) {
        M3HistoryContent(
            entries = entriesNow,
            viewModel = viewModel,
            coverFile = coverFile,
            compact = historyCompact,
            byDay = historyByDay,
            onBack = onBack,
            onOpenBook = onOpenBook
        )
        return
    }
    // Code 123: the Editorial History — the approved extension
    // (DESIGN-M3-HISTORY-DOWNLOADS-V6-EXTENSION): the Pulse
    // kept, "Clear history" visible in the header, day-grouped
    // rows when the same grouping knob says BY_DAY (the flat
    // §15 form one knob-step away), both remove mechanics
    // (button → the confirm dialog; swipe → snackbar Undo).
    if (designState.design ==
        app.xreader.data.prefs.AppDesign.EDITORIAL
    ) {
        EditorialHistoryContent(
            entries = entriesNow,
            pulse = pulseNow,
            booksToday = booksToday,
            viewModel = viewModel,
            coverFile = coverFile,
            compact = designState.historyDensity ==
                app.xreader.data.prefs.M3DensityChoice.COMPACT,
            byDay = designState.historyGrouping ==
                app.xreader.data.prefs.M3HistoryGrouping.BY_DAY,
            onBack = onBack,
            onOpenBook = onOpenBook
        )
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(start = 24.dp, end = 24.dp, top = LocalUiMetrics.current.topGap, bottom = 16.dp)
    ) {
        // v1.0.4 (D13): History is a bottom TAB — no back arrow
        // (the D13 mock's arrow is dropped, same ruling as Me).
        // The shared ScreenHeader carries the title; Clear history
        // stays visible in its action slot. [onBack] remains in the
        // signature for the shell's wiring compatibility.
        ScreenHeader(
            title = "History",
            eyebrow = "Xreader",
            action = {
                if (entriesNow.isNotEmpty()) {
                    TextButton(onClick = { confirmClear = true }) {
                        Text("Clear history")
                    }
                }
            }
        )
        if (entriesNow.isEmpty()) {
            EmptyState(
                title = "Nothing here yet",
                message = "Books you read will show up here, newest first.",
                modifier = Modifier.weight(1f),
                hover = rememberHoverEmptyState(reduceMotion)
            )
        } else {
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(
                    bottom = effectiveBottomPadding(88.dp, LocalDockClearance.current)
                )
            ) {
                // ── Reading Pulse (Q11): real aggregates only —
                // see ReadingPulse's doc for the exact sources.
                item(key = "pulse") {
                    GlassCard(Modifier.fillMaxWidth()) {
                        SectionHeader(
                            title = "Reading pulse",
                            modifier = Modifier.fillMaxWidth(),
                            eyebrow = "Today"
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth()) {
                            PulseCell(
                                value = formatMinutes(pulseNow.minutesToday),
                                label = "reading today",
                                modifier = Modifier.weight(1f)
                            )
                            PulseCell(
                                value = "$booksToday",
                                label = if (booksToday == 1) "book today" else "books today",
                                modifier = Modifier.weight(1f)
                            )
                            PulseCell(
                                value = "${pulseNow.chaptersToday}",
                                label = "chapters today",
                                modifier = Modifier.weight(1f)
                            )
                            PulseCell(
                                value = "${pulseNow.expToday}",
                                label = "EXP today",
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                // v1.5.2 fix (FIX-9, §15): ONE flat list,
                // most-recent-first — no day groups, no filters.
                // v1.5.4 (item 24): entries render as compact
                // rows — see HistoryEntryRow below.
                if (historyByDay) {
                    // The By-day form (M3 knob): day headers
                    // over the same most-recent-first entries —
                    // grouping changes presentation only, never
                    // the order or the rows' actions.
                    val nowMs = System.currentTimeMillis()
                    val grouped = entriesNow.groupBy {
                        historyDayLabel(it.recordedAt, nowMs)
                    }
                    grouped.forEach { (label, dayEntries) ->
                        item(key = "hist-day-$label") {
                            Text(
                                label,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme
                                    .onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = 4.dp, top = 8.dp, bottom = 2.dp
                                )
                            )
                        }
                        items(dayEntries, key = { "hist-${it.bookKey}" }) { entry ->
                            HistoryEntryBlock(
                                entry = entry,
                                coverFile = coverFile,
                                compact = historyCompact,
                                onOpenBook = onOpenBook,
                                viewModel = viewModel,
                                onRemove = { confirmRemove = entry },
                                onDeleteBook = { deleteTarget = entry.book }
                            )
                        }
                    }
                } else {
                    items(entriesNow, key = { "hist-${it.bookKey}" }) { entry ->
                        HistoryEntryBlock(
                            entry = entry,
                            coverFile = coverFile,
                            compact = historyCompact,
                            onOpenBook = onOpenBook,
                            viewModel = viewModel,
                            onRemove = { confirmRemove = entry },
                            onDeleteBook = { deleteTarget = entry.book }
                        )
                    }
                }
            }
        }
    }
    // v1.5.34 (item 2): the full delete sheet for a History
    // row's book — the Library's own sheet (BookDeleteDialogs),
    // the Library's own deletion operations behind it.
    app.xreader.ui.common.BookDeleteDialogs(
        book = deleteTarget,
        downloadedSizeBytes = { viewModel.downloadedSizeBytes(it) },
        onDismiss = { deleteTarget = null },
        onRemoveBook = { book, deleteFiles ->
            viewModel.removeBook(book, deleteFiles)
        },
        onRemoveLocalBook = { book, mode, context ->
            viewModel.removeLocalBook(book, mode, context)
        }
    )
    val removeTarget = confirmRemove
    if (removeTarget != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove from History?") },
            text = {
                Text(
                    "Remove “${removeTarget.title}” from History? " +
                        "This deletes only its History entry — the " +
                        "book, your place in it and your downloads " +
                        "stay exactly as they are."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    viewModel.removeEntry(removeTarget.bookKey)
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) {
                    Text("Keep it")
                }
            }
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = {
                Text(
                    "This clears the list on this screen only. Your place in " +
                        "every book, your bookmarks and your downloads stay " +
                        "exactly as they are."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearAll()
                }) { Text("Clear history") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Keep it") }
            }
        )
    }
}

/**
 * v1.5.4 (item 24, owner finding #6): ONE History entry as a
 * COMPACT ROW. The old entry stacked a second full actions row
 * under the text inside 16 dp card padding and ran ≈170 dp
 * tall — ~2 entries per screen with dead space inside the
 * card, on the screen whose whole job is scanning what you
 * read. The row is now a single horizontal band inside a
 * tighter-padded GlassCard: cover thumb (40×58, as before),
 * the title / chapter / time text column, and a right-hand
 * action rail — Continue as the row's one primary pill, Add
 * back to Library and Remove as quiet visible text buttons
 * under it, never in a menu. Worst case ≈104 dp (two-line
 * title, all three actions, progress bar) — ≥5 entries share
 * a viewport with the Reading Pulse card on top.
 * Presentation only: the §15 model (per-book, flat,
 * most-recent-first), every action's effect, and the card's
 * own tap-to-open are unchanged.
 */
/**
 * One History entry's rendering, shared by the flat list and
 * the By-day grouping so both forms render identical rows.
 */
@Composable
private fun HistoryEntryBlock(
    entry: HistoryEntryUi,
    coverFile: (String?) -> File?,
    compact: Boolean,
    onOpenBook: (bookId: Long, chapterIndex: Int) -> Unit,
    viewModel: HistoryViewModel,
    onRemove: () -> Unit,
    onDeleteBook: () -> Unit
) {
    val coverFileNow = coverFile(entry.coverPath)
        ?.takeIf { it.exists() }
    val coverRequest = if (compact) {
        rememberSizedCover(coverFileNow, 32, 46)
    } else {
        rememberSizedCover(coverFileNow, 40, 58)
    }
    HistoryEntryRow(
        entry = entry,
        coverRequest = coverRequest,
        coverFileNow = coverFileNow,
        onOpen = {
            entry.bookId?.let {
                onOpenBook(it, entry.chapterIndex)
            }
        },
        onAddBack = {
            entry.bookId?.let {
                viewModel.addBackToLibrary(it)
            }
        },
        onRemove = onRemove,
        onDeleteBook = onDeleteBook,
        compact = compact
    )
}

/** The By-day header for an entry: Today / Yesterday / the
 * date, by the entry's own recorded time. */
private fun historyDayLabel(recordedAt: Long, nowMillis: Long): String {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = recordedAt
    val entryDay = cal.get(java.util.Calendar.DAY_OF_YEAR) to
        cal.get(java.util.Calendar.YEAR)
    cal.timeInMillis = nowMillis
    val today = cal.get(java.util.Calendar.DAY_OF_YEAR) to
        cal.get(java.util.Calendar.YEAR)
    cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
    val yesterday = cal.get(java.util.Calendar.DAY_OF_YEAR) to
        cal.get(java.util.Calendar.YEAR)
    return when (entryDay) {
        today -> "Today"
        yesterday -> "Yesterday"
        else -> java.text.SimpleDateFormat(
            "d MMM yyyy", java.util.Locale.US
        ).format(java.util.Date(recordedAt))
    }
}

@Composable
private fun HistoryEntryRow(
    entry: HistoryEntryUi,
    coverRequest: ImageRequest?,
    coverFileNow: File?,
    onOpen: () -> Unit,
    onAddBack: () -> Unit,
    onRemove: () -> Unit,
    onDeleteBook: () -> Unit,
    /** Code 120: the M3 Compact density — tighter padding and
     * a smaller cover; Comfortable (and Nocturne) render the
     * shipped geometry. */
    compact: Boolean = false
) {
    GlassCard(
        onClick = if (entry.bookExists) onOpen else null,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            horizontal = 12.dp,
            vertical = if (compact) 3.dp else 8.dp
        ),
        bouncePress = true
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AsyncImage(
                model = coverRequest ?: coverFileNow
                    ?: entry.coverUrl?.takeIf { it.isNotBlank() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(
                        width = if (compact) 32.dp else 40.dp,
                        height = if (compact) 46.dp else 58.dp
                    )
                    .clip(RoundedCornerShape(12.dp))
            )
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val chapterTitleNow = entry.chapterTitle
                Text(
                    if (chapterTitleNow.isNullOrBlank()) {
                        "Chapter ${entry.chapterIndex + 1}"
                    } else {
                        chapterTitleNow
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${timeAgo(entry.recordedAt, System.currentTimeMillis())} · " +
                        "${(entry.fraction * 100).toInt()}% through the book",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Patch 17 (combined small build): this line
                    // was maxLines = 1 with NO overflow mode, so
                    // in the text column — narrowed by the cover
                    // and the action rail — it hard-clipped
                    // mid-sentence ("0% through the", owner's
                    // 2026-11-14 video). The Patch 7 house pattern
                    // applies: give the line the lines it needs
                    // (two), never shrink the text.
                    maxLines = 2
                )
                if (entry.fraction > 0f) {
                    HistorySlimProgressBar(
                        fraction = entry.fraction.coerceIn(0f, 1f)
                    )
                }
            }
            // The action rail: Continue is the row's ONE accent
            // (primary pill + glow — slop tell 2: glow means the
            // primary action, nothing else); Add back and
            // Remove stay visible plain-word text buttons under
            // it, under the same conditions as the old layout.
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (entry.bookExists) {
                    HistoryContinuePill(
                        text = "Continue · Ch. ${entry.chapterIndex + 1}",
                        onClick = onOpen
                    )
                }
                // §15.1 flip: a book taken out of the Library
                // comes back from here.
                if (entry.bookExists && !entry.inLibrary) {
                    TextButton(
                        onClick = onAddBack,
                        contentPadding = PaddingValues(
                            horizontal = 6.dp,
                            vertical = 0.dp
                        ),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text(
                            "Add back to Library",
                            style = MaterialTheme.typography.labelMedium,
                            color = primaryTextColor(),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                TextButton(
                    onClick = onRemove,
                    contentPadding = PaddingValues(
                        horizontal = 6.dp,
                        vertical = 0.dp
                    ),
                    modifier = Modifier.height(26.dp)
                ) {
                    Text(
                        "Remove",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                // v1.5.34 (item 2): the road to the FULL delete
                // sheet — a heavier act than Remove (which
                // deletes only the History record), so it wears
                // its own plain label and opens the Library's
                // sheet rather than acting directly. Only for a
                // book that still exists.
                if (entry.bookExists) {
                    TextButton(
                        onClick = onDeleteBook,
                        contentPadding = PaddingValues(
                            horizontal = 6.dp,
                            vertical = 0.dp
                        ),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text(
                            "Delete book…",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

/**
 * The compact twin of the app's PrimaryPill, sized for dense
 * rows: same stadium shape, `primary` fill, `onPrimary` label,
 * 40%-primary soft glow behind the pill and the ~0.98 press
 * squish — at row padding (16×8 dp) and the labelMedium rung.
 * PrimaryPill itself carries hero/sheet padding and cannot
 * shrink; the tokens here are copied, not forked.
 */
@Composable
private fun HistoryContinuePill(
    text: String,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val primary = MaterialTheme.colorScheme.primary
    Button(
        onClick = onClick,
        modifier = Modifier
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(
                            primary.copy(alpha = 0.40f),
                            Color.Transparent
                        ),
                        radius = size.maxDimension * 0.8f
                    ),
                    radius = size.maxDimension * 0.8f
                )
            }
            .pressSquish(
                interaction,
                enabled = true,
                pressedScale = 0.98f
            ),
        interactionSource = interaction,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * The row's progress bar: ProgressPillBar's exact tokens
 * (`surfaceContainerHigh` track, `primary` → `tertiary`
 * stadium fill) at half height (4 dp) — item 24's slimmer bar.
 */
@Composable
private fun HistorySlimProgressBar(fraction: Float) {
    val clamped = fraction.coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        if (clamped > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(clamped)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        )
                    )
            )
        }
    }
}

/**
 * v1.2.0 (WS4, sized Coil requests): decode a history cover near
 * its drawn size (40×58dp here) instead of the full stored image.
 * Same pattern as the Library's helper.
 */
@Composable
private fun rememberSizedCover(file: File?, widthDp: Int, heightDp: Int): ImageRequest? {
    val context = LocalContext.current
    return remember(file, widthDp, heightDp) {
        if (file == null) {
            null
        } else {
            val density = context.resources.displayMetrics.density
            ImageRequest.Builder(context)
                .data(file)
                .size(
                    (widthDp * density).toInt().coerceAtLeast(1),
                    (heightDp * density).toInt().coerceAtLeast(1)
                )
                .build()
        }
    }
}

@Composable
private fun PulseCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // Patch 15 (Counter): the pulse counts roll odometer-
        // style when they change (instant swap under reduced
        // motion, via RollingNumberText itself).
        RollingNumberText(text = value, style = MaterialTheme.typography.titleMedium)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── Code 121 (R3): the Material 3 History ──────────────────
// Card-less list items, day-grouped with sticky date headers
// when the DesignState historyGrouping knob says BY_DAY —
// code 122 (audit item #13) passes the knob in, so the flat
// form (its stored default) exists under M3 too
// (D-121-W2-06). Density follows the knob.
@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
@Composable
private fun M3HistoryContent(
    entries: List<HistoryEntryUi>,
    viewModel: HistoryViewModel,
    coverFile: (String?) -> File?,
    compact: Boolean,
    /** Code 122 (R3, audit item #13): the stored grouping knob —
     * true = day groups with sticky date headers; false = the
     * flat form (one list, newest first, no headers). */
    byDay: Boolean,
    onBack: () -> Unit,
    onOpenBook: (bookId: Long, chapterIndex: Int) -> Unit
) {
    val scrollBehavior = androidx.compose.material3.TopAppBarDefaults
        .exitUntilCollapsedScrollBehavior()
    val snackbarHostState = remember {
        androidx.compose.material3.SnackbarHostState()
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var overflowOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var deleteTarget by remember {
        mutableStateOf<app.xreader.data.db.Book?>(null)
    }

    fun removeWithUndo(entry: HistoryEntryUi) {
        viewModel.removeEntryWithUndo(entry.bookKey) { record ->
            if (record != null) {
                scope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = "Removed “${entry.title}” from history",
                        actionLabel = "Undo",
                        duration = androidx.compose.material3
                            .SnackbarDuration.Short
                    )
                    if (result == androidx.compose.material3
                            .SnackbarResult.ActionPerformed
                    ) {
                        viewModel.restoreEntry(record)
                    }
                }
            }
        }
    }

    androidx.compose.material3.Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            androidx.compose.material3.MediumFlexibleTopAppBar(
                title = {
                    Text(
                        "History",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                scrollBehavior = scrollBehavior,
                actions = {
                    Box {
                        androidx.compose.material3.IconButton(
                            onClick = { overflowOpen = true }
                        ) {
                            androidx.compose.material3.Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "History options"
                            )
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = overflowOpen,
                            onDismissRequest = { overflowOpen = false }
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Clear all") },
                                enabled = entries.isNotEmpty(),
                                onClick = {
                                    overflowOpen = false
                                    confirmClear = true
                                }
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = {
            androidx.compose.material3.SnackbarHost(snackbarHostState)
        }
    ) { innerPadding ->
        if (entries.isEmpty()) {
            // S40 empty state (R3's copy + its Library door;
            // onBack is wired by the shell to the Library tab).
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Nothing read yet",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    "Books you read will show up here, newest first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.material3.Button(onClick = onBack) {
                    Text("Library")
                }
            }
            return@Scaffold
        }
        val nowMs = System.currentTimeMillis()
        // Day groups in encounter order (entries are already
        // most-recent-first; groupBy preserves it).
        val grouped = entries.groupBy { historyDayLabel(it.recordedAt, nowMs) }
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
            if (!byDay) {
                // The flat form (the knob's stored default):
                // one list, newest first, no date headers.
                items(entries, key = { "m3-hist-flat-${it.bookKey}" }) { entry ->
                    M3HistoryRow(
                        entry = entry,
                        coverFile = coverFile,
                        compact = compact,
                        onOpen = {
                            entry.bookId?.let {
                                onOpenBook(it, entry.chapterIndex)
                            }
                        },
                        onAddBack = {
                            entry.bookId?.let {
                                viewModel.addBackToLibrary(it)
                            }
                        },
                        onRemove = { removeWithUndo(entry) },
                        onDeleteBook = { deleteTarget = entry.book }
                    )
                    androidx.compose.material3.HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }
            }
            if (byDay) grouped.forEach { (label, dayEntries) ->
                stickyHeader(key = "m3-hist-day-$label") {
                    androidx.compose.material3.Surface(
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                start = 16.dp, end = 16.dp,
                                top = 12.dp, bottom = 6.dp
                            )
                        )
                    }
                }
                items(dayEntries, key = { "m3-hist-${it.bookKey}" }) { entry ->
                    M3HistoryRow(
                        entry = entry,
                        coverFile = coverFile,
                        compact = compact,
                        onOpen = {
                            entry.bookId?.let {
                                onOpenBook(it, entry.chapterIndex)
                            }
                        },
                        onAddBack = {
                            entry.bookId?.let {
                                viewModel.addBackToLibrary(it)
                            }
                        },
                        onRemove = { removeWithUndo(entry) },
                        onDeleteBook = { deleteTarget = entry.book }
                    )
                    androidx.compose.material3.HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }
            }
        }
    }
    if (confirmClear) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = {
                Text(
                    "This clears the list on this screen only. " +
                        "Clearing history never removes downloads " +
                        "or books — your place in every book and " +
                        "your bookmarks stay exactly as they are."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearAll()
                }) { Text("Clear history") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("Keep it")
                }
            }
        )
    }
    // S5's one delete dialog, shared with Library + Nocturne.
    app.xreader.ui.common.BookDeleteDialogs(
        book = deleteTarget,
        downloadedSizeBytes = { viewModel.downloadedSizeBytes(it) },
        onDismiss = { deleteTarget = null },
        onRemoveBook = { book, deleteFiles ->
            viewModel.removeBook(book, deleteFiles)
        },
        onRemoveLocalBook = { book, mode, context ->
            viewModel.removeLocalBook(book, mode, context)
        }
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun M3HistoryRow(
    entry: HistoryEntryUi,
    coverFile: (String?) -> File?,
    compact: Boolean,
    onOpen: () -> Unit,
    onAddBack: () -> Unit,
    onRemove: () -> Unit,
    onDeleteBook: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val dismissState = androidx.compose.material3
        .rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value == androidx.compose.material3
                        .SwipeToDismissBoxValue.EndToStart
                ) {
                    onRemove()
                    true
                } else {
                    false
                }
            }
        )
    androidx.compose.material3.SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                androidx.compose.material3.Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Remove from history",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    ) {
        androidx.compose.material3.ListItem(
            headlineContent = {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            supportingContent = {
                Column {
                    Text(
                        listOfNotNull(
                            entry.chapterTitle?.takeIf { it.isNotBlank() }
                                ?: "Chapter ${entry.chapterIndex + 1}",
                            "${(entry.fraction * 100).toInt()}%"
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        timeAgo(entry.recordedAt, System.currentTimeMillis()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            leadingContent = {
                app.xreader.ui.common.M3CoverThumbnail(
                    title = entry.title,
                    author = null,
                    coverModel = coverFile(entry.coverPath)
                        ?.takeIf { it.exists() }
                        ?: entry.coverUrl?.takeIf { it.isNotBlank() },
                    modifier = Modifier.size(
                        width = if (compact) 32.dp else 40.dp,
                        height = if (compact) 46.dp else 58.dp
                    )
                )
            },
            trailingContent = {
                Box {
                    androidx.compose.material3.IconButton(
                        onClick = { menuOpen = true }
                    ) {
                        androidx.compose.material3.Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "Actions for ${entry.title}"
                        )
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        if (entry.bookExists) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Open book page") },
                                onClick = {
                                    menuOpen = false
                                    onOpen()
                                }
                            )
                        }
                        if (entry.bookExists && !entry.inLibrary) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Add back to Library") },
                                onClick = {
                                    menuOpen = false
                                    onAddBack()
                                }
                            )
                        }
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Remove from history") },
                            onClick = {
                                menuOpen = false
                                onRemove()
                            }
                        )
                        if (entry.bookExists) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = {
                                    Text(
                                        "Delete book…",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    onDeleteBook()
                                }
                            )
                        }
                    }
                }
            },
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .clickable(enabled = entry.bookExists, onClick = onOpen)
        )
    }
}


// ── Code 123: the Editorial History ────────────────────────
// The approved extension alive: kicker + serif headline with
// "Clear history" visible in the header, the Reading Pulse
// card, day-grouped rows when the grouping knob says BY_DAY
// (flat otherwise — presentation only, §15 order and actions
// unchanged), the full action rail on every row, both remove
// mechanics (the Remove button opens the §15 confirm dialog;
// a swipe removes with a snackbar Undo), and the shared
// book-delete sheet behind "Delete book…".
@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
@Composable
private fun EditorialHistoryContent(
    entries: List<HistoryEntryUi>,
    pulse: ReadingPulse,
    booksToday: Int,
    viewModel: HistoryViewModel,
    coverFile: (String?) -> File?,
    compact: Boolean,
    byDay: Boolean,
    onBack: () -> Unit,
    onOpenBook: (bookId: Long, chapterIndex: Int) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val snackbarHostState = remember {
        androidx.compose.material3.SnackbarHostState()
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<HistoryEntryUi?>(null) }
    var deleteTarget by remember {
        mutableStateOf<app.xreader.data.db.Book?>(null)
    }

    fun removeWithUndo(entry: HistoryEntryUi) {
        viewModel.removeEntryWithUndo(entry.bookKey) { record ->
            if (record != null) {
                scope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = "Removed “${entry.title}” from history",
                        actionLabel = "Undo",
                        duration = androidx.compose.material3
                            .SnackbarDuration.Short
                    )
                    if (result == androidx.compose.material3
                            .SnackbarResult.ActionPerformed
                    ) {
                        viewModel.restoreEntry(record)
                    }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (entries.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Spacer(Modifier.height(48.dp))
                Text(
                    "YOUR READING",
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.6.sp,
                    color = colors.primary
                )
                Text(
                    "Nothing read yet",
                    fontFamily = FrauncesSerif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 28.sp,
                    color = colors.onSurface
                )
                Text(
                    "Books you read will show up here, newest first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Surface(
                    color = colors.primary,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.clickable(onClick = onBack)
                ) {
                    Text(
                        "Library",
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onPrimary,
                        modifier = Modifier.padding(
                            horizontal = 22.dp, vertical = 12.dp
                        )
                    )
                }
            }
        } else {
            val nowMs = System.currentTimeMillis()
            val grouped = entries.groupBy {
                historyDayLabel(it.recordedAt, nowMs)
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 112.dp)
            ) {
                item(key = "ed-hist-head") {
                    Column(
                        Modifier.padding(
                            start = 20.dp, end = 20.dp, top = 16.dp
                        )
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "NEWEST FIRST",
                                    style = MaterialTheme.typography
                                        .labelSmall,
                                    letterSpacing = 1.6.sp,
                                    color = colors.primary
                                )
                                Text(
                                    "History",
                                    fontFamily = FrauncesSerif,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 34.sp,
                                    color = colors.onSurface
                                )
                            }
                            Surface(
                                color = colors.surfaceContainerHigh,
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.clickable {
                                    confirmClear = true
                                }
                            ) {
                                Text(
                                    "Clear history",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.onSurface,
                                    modifier = Modifier.padding(
                                        horizontal = 14.dp, vertical = 9.dp
                                    )
                                )
                            }
                        }
                        // The Reading Pulse.
                        Surface(
                            color = colors.surfaceContainerLow,
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp)
                        ) {
                            Column(Modifier.padding(18.dp)) {
                                Text(
                                    "TODAY",
                                    style = MaterialTheme.typography
                                        .labelSmall,
                                    letterSpacing = 1.6.sp,
                                    color = colors.primary
                                )
                                Text(
                                    "Reading pulse",
                                    fontFamily = FrauncesSerif,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 19.sp,
                                    color = colors.onSurface
                                )
                                Spacer(Modifier.height(12.dp))
                                Row(Modifier.fillMaxWidth()) {
                                    PulseCell(
                                        value = formatMinutes(
                                            pulse.minutesToday
                                        ),
                                        label = "read today",
                                        modifier = Modifier.weight(1f)
                                    )
                                    PulseCell(
                                        value = "$booksToday",
                                        label = "books today",
                                        modifier = Modifier.weight(1f)
                                    )
                                    PulseCell(
                                        value = "${pulse.chaptersToday}",
                                        label = "chapters",
                                        modifier = Modifier.weight(1f)
                                    )
                                    PulseCell(
                                        value = "${pulse.expToday}",
                                        label = "EXP today",
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
                if (!byDay) {
                    items(entries, key = { "ed-hist-${it.bookKey}" }) { entry ->
                        EditorialHistoryRow(
                            entry = entry,
                            coverFile = coverFile,
                            compact = compact,
                            onOpen = {
                                entry.bookId?.let {
                                    onOpenBook(it, entry.chapterIndex)
                                }
                            },
                            onAddBack = {
                                entry.bookId?.let {
                                    viewModel.addBackToLibrary(it)
                                }
                            },
                            onRemoveAsk = { confirmRemove = entry },
                            onRemoveSwipe = { removeWithUndo(entry) },
                            onDeleteBook = { deleteTarget = entry.book }
                        )
                    }
                } else {
                    grouped.forEach { (label, dayEntries) ->
                        stickyHeader(key = "ed-hist-day-$label") {
                            Surface(
                                color = colors.surface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    label,
                                    fontFamily = FrauncesSerif,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp,
                                    color = colors.onSurface,
                                    modifier = Modifier.padding(
                                        start = 20.dp, end = 20.dp,
                                        top = 14.dp, bottom = 6.dp
                                    )
                                )
                            }
                        }
                        items(
                            dayEntries,
                            key = { "ed-hist-${it.bookKey}" }
                        ) { entry ->
                            EditorialHistoryRow(
                                entry = entry,
                                coverFile = coverFile,
                                compact = compact,
                                onOpen = {
                                    entry.bookId?.let {
                                        onOpenBook(it, entry.chapterIndex)
                                    }
                                },
                                onAddBack = {
                                    entry.bookId?.let {
                                        viewModel.addBackToLibrary(it)
                                    }
                                },
                                onRemoveAsk = { confirmRemove = entry },
                                onRemoveSwipe = { removeWithUndo(entry) },
                                onDeleteBook = {
                                    deleteTarget = entry.book
                                }
                            )
                        }
                    }
                }
            }
        }
        androidx.compose.material3.SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 104.dp)
        )
    }

    val removeTarget = confirmRemove
    if (removeTarget != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove from History?") },
            text = {
                Text(
                    "Remove “${removeTarget.title}” from History? " +
                        "This deletes only its History entry — the " +
                        "book, your place in it and your downloads " +
                        "stay exactly as they are."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    viewModel.removeEntry(removeTarget.bookKey)
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) {
                    Text("Keep it")
                }
            }
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = {
                Text(
                    "This clears the list on this screen only. " +
                        "Clearing history never removes downloads " +
                        "or books — your place in every book and " +
                        "your bookmarks stay exactly as they are."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearAll()
                }) { Text("Clear history") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("Keep it")
                }
            }
        )
    }
    app.xreader.ui.common.BookDeleteDialogs(
        book = deleteTarget,
        downloadedSizeBytes = { viewModel.downloadedSizeBytes(it) },
        onDismiss = { deleteTarget = null },
        onRemoveBook = { book, deleteFiles ->
            viewModel.removeBook(book, deleteFiles)
        },
        onRemoveLocalBook = { book, mode, context ->
            viewModel.removeLocalBook(book, mode, context)
        }
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EditorialHistoryRow(
    entry: HistoryEntryUi,
    coverFile: (String?) -> File?,
    compact: Boolean,
    onOpen: () -> Unit,
    onAddBack: () -> Unit,
    onRemoveAsk: () -> Unit,
    onRemoveSwipe: () -> Unit,
    onDeleteBook: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val dismissState = androidx.compose.material3
        .rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value == androidx.compose.material3
                        .SwipeToDismissBoxValue.EndToStart
                ) {
                    onRemoveSwipe()
                    true
                } else {
                    false
                }
            }
        )
    androidx.compose.material3.SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.errorContainer)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Remove from history",
                    tint = colors.onErrorContainer
                )
            }
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .clickable(enabled = entry.bookExists, onClick = onOpen)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                app.xreader.ui.common.M3CoverThumbnail(
                    title = entry.title,
                    author = null,
                    coverModel = coverFile(entry.coverPath)
                        ?.takeIf { it.exists() }
                        ?: entry.coverUrl?.takeIf { it.isNotBlank() },
                    modifier = Modifier.size(
                        width = if (compact) 32.dp else 40.dp,
                        height = if (compact) 46.dp else 58.dp
                    )
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        entry.chapterTitle?.takeIf { it.isNotBlank() }
                            ?: "Chapter ${entry.chapterIndex + 1}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        timeAgo(
                            entry.recordedAt,
                            System.currentTimeMillis()
                        ) + " · ${(entry.fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                    if (entry.fraction > 0f) {
                        Spacer(Modifier.height(6.dp))
                        androidx.compose.material3
                            .LinearProgressIndicator(
                                progress = {
                                    entry.fraction.coerceIn(0f, 1f)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = colors.primary,
                                trackColor = colors.surfaceContainerHighest
                            )
                    }
                }
            }
            // The action rail — every door visible (F-H2/F-H6).
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (entry.bookExists) {
                    Surface(
                        color = colors.primary,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.clickable(onClick = onOpen)
                    ) {
                        Text(
                            "Continue · Ch. ${entry.chapterIndex + 1}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onPrimary,
                            modifier = Modifier.padding(
                                horizontal = 12.dp, vertical = 7.dp
                            )
                        )
                    }
                }
                if (entry.bookExists && !entry.inLibrary) {
                    Surface(
                        color = colors.secondaryContainer,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.clickable(onClick = onAddBack)
                    ) {
                        Text(
                            "Add back to Library",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSecondaryContainer,
                            modifier = Modifier.padding(
                                horizontal = 12.dp, vertical = 7.dp
                            )
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRemoveAsk) {
                    Text("Remove", fontSize = 13.sp)
                }
                if (entry.bookExists) {
                    TextButton(onClick = onDeleteBook) {
                        Text(
                            "Delete book…",
                            fontSize = 13.sp,
                            color = colors.error
                        )
                    }
                }
            }
        }
    }
}
