package app.xreader.ui.more

import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.core.motion.pressSquish
import app.xreader.di.AppContainer
import app.xreader.ui.common.EmptyState
import app.xreader.ui.common.GlassCard
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
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
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
                contentPadding = PaddingValues(bottom = 88.dp)
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
                items(entriesNow, key = { "hist-${it.bookKey}" }) { entry ->
                    val coverFileNow = coverFile(entry.coverPath)
                        ?.takeIf { it.exists() }
                    val coverRequest =
                        rememberSizedCover(coverFileNow, 40, 58)
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
                        onRemove = { confirmRemove = entry },
                        onDeleteBook = { deleteTarget = entry.book }
                    )
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
@Composable
private fun HistoryEntryRow(
    entry: HistoryEntryUi,
    coverRequest: ImageRequest?,
    coverFileNow: File?,
    onOpen: () -> Unit,
    onAddBack: () -> Unit,
    onRemove: () -> Unit,
    onDeleteBook: () -> Unit
) {
    GlassCard(
        onClick = if (entry.bookExists) onOpen else null,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
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
                    .size(width = 40.dp, height = 58.dp)
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
                    maxLines = 1
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
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
