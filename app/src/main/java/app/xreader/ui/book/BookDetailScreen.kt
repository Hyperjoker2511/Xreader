package app.xreader.ui.book
import app.xreader.ui.theme.errorTextColor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.core.motion.listPlacementSpec
import app.xreader.data.db.Chapter
import app.xreader.data.db.DownloadState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.xreader.data.library.ReadingStatus
import app.xreader.ui.common.EmptyState
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.StatusPill
import app.xreader.ui.common.XreaderPrimaryButton
import coil.compose.AsyncImage
import java.io.File

/**
 * Book Detail (Stitch design): header with the cover, rating pill,
 * "Start reading" + "Download" + "Export EPUB" actions, synopsis,
 * and the chapter list with per-chapter download state icons.
 */
@Composable
fun BookDetailScreen(
    viewModel: BookDetailViewModel,
    coverFile: (String?) -> File?,
    assetFile: (bookId: Long, fileName: String) -> File,
    onBack: () -> Unit,
    onReadChapter: (chapterIndex: Int) -> Unit,
    onExportEpub: () -> Unit,
    onExportCbz: () -> Unit,
    onOpenLore: () -> Unit = {},
    onOpenRelated: (Long) -> Unit = {},
    onOpenTag: (String) -> Unit = {},
    /** v1.0.2 (R26): find an AI-suggested title across sources. */
    onFindTitle: (String) -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val engineLabel by viewModel.engineLabel.collectAsStateWithLifecycle()
    val book = state.book
    val context = androidx.compose.ui.platform.LocalContext.current
    // Phase 6: highlights → Markdown, saved where the user picks.
    val markdownLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts
            .CreateDocument("text/markdown")
    ) { uri ->
        if (uri != null) {
            val stream = runCatching {
                context.contentResolver.openOutputStream(uri)
            }.getOrNull()
            if (stream != null) {
                viewModel.exportHighlightsMarkdown(stream) { message ->
                    viewModel.notifyMessage(message)
                }
            }
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Book details",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        if (book == null) {
            // v1.0.1: never flash "not found" while the row is still
            // loading — the genuine state is only for a book that was
            // actually deleted, and it offers the way back.
            if (state.loading) {
                Column(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                }
            } else {
                EmptyState(
                    title = "Book not found",
                    message = "This book isn't in your library anymore.",
                    modifier = Modifier.weight(1f),
                    actionLabel = "Back to library",
                    onAction = onBack
                )
            }
            return@Column
        }
        LaunchedEffect(book.id) {
            viewModel.loadEnrichment()
            viewModel.loadPalette()
        }
        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "header") {
                // v1.0.2 (R05): cover-colour ambience — the cover's
                // own palette tints the header; STANDARD/FULL tiers
                // get the pre-blurred cover backdrop, MINIMAL the
                // static tint (PerformancePolicy decided upstream).
                val palette = state.coverPalette
                val backdropBase = palette?.darkVibrant ?: palette?.dominant
                Box {
                    if (state.backdropEnabled && backdropBase != null) {
                        if (state.backdropBlurred &&
                            android.os.Build.VERSION.SDK_INT >= 31
                        ) {
                            AsyncImage(
                                model = coverFile(book.coverPath),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .matchParentSize()
                                    .blur(28.dp)
                                    .background(
                                        backdropBase.copy(alpha = 0.35f)
                                    )
                            )
                        } else {
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .background(backdropBase.copy(alpha = 0.22f))
                            )
                        }
                    }
                Column(
                    Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(Modifier.fillMaxWidth()) {
                        val headerCover = coverFile(book.coverPath)
                        if (headerCover != null && headerCover.exists()) {
                            AsyncImage(
                                model = headerCover,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(width = 104.dp, height = 150.dp)
                            )
                        } else {
                            app.xreader.ui.library.PlaceholderCover(
                                title = book.title,
                                modifier = Modifier.size(width = 104.dp, height = 150.dp)
                            )
                        }
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(start = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(book.title, style = MaterialTheme.typography.headlineSmall)
                            if (book.author.isNotBlank()) {
                                Text(
                                    book.author,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = { viewModel.setFavourite(!state.favourite) }
                            ) {
                                Text(
                                    if (state.favourite) {
                                        "♥ Favourite — tap to remove"
                                    } else {
                                        "♡ Add to Favourites"
                                    }
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusPill(
                                    text = book.localFormat.name,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                book.userRating?.let { rating ->
                                    StatusPill(
                                        text = "★ $rating",
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                StatusPill(
                                    text = "${state.downloadedCount}/${state.chapters.size} downloaded",
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            // Phase 6: time-to-read for the rest of
                            // the book at the reader's own pace.
                            val remainingWords = state.chapters
                                .filter { !it.read }
                                .sumOf { it.wordCount }
                            if (remainingWords > 0) {
                                Text(
                                    app.xreader.domain.reader.WpmEstimator
                                        .formatMinutes(
                                            (remainingWords / state.wpm)
                                                .toInt().coerceAtLeast(1)
                                        ) + " left in this book",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                            // Sweep addendum: the rhythm line —
                            // shown only when the cadence is
                            // actually knowable (UpdateRhythm).
                            state.expectedNextLabel?.let { label ->
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        XreaderPrimaryButton(
                            text = if (state.progress == null) "Start reading" else "Continue reading",
                            onClick = { onReadChapter(state.continueChapterIndex) },
                            modifier = Modifier.weight(1f)
                        )
                        if (state.hasUndownloadedChapters) {
                            XreaderPrimaryButton(
                                text = "Download",
                                onClick = { viewModel.downloadAllChapters() },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (state.hasUndownloadedChapters) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { viewModel.downloadNextTen() }) {
                                Text("Next 10")
                            }
                            TextButton(onClick = { viewModel.downloadAllUnread() }) {
                                Text("All unread")
                            }
                        }
                    }
                    // v1.0.1: reading status + Track (feeds the
                    // library filter and the new-chapter badges).
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(
                            ReadingStatus.READING to "Reading",
                            ReadingStatus.COMPLETED to "Completed",
                            ReadingStatus.ON_HOLD to "On hold",
                            ReadingStatus.DROPPED to "Dropped"
                        ).forEach { (status, label) ->
                            FilterChip(
                                selected = state.bookMeta.status == status,
                                onClick = {
                                    viewModel.setReadingStatus(
                                        if (state.bookMeta.status == status) {
                                            ReadingStatus.NONE
                                        } else {
                                            status
                                        }
                                    )
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        androidx.compose.material3.Switch(
                            checked = state.bookMeta.tracked,
                            onCheckedChange = { viewModel.setTracked(it) }
                        )
                        Text(
                            "Track this book — tell me when new chapters land",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onExportEpub) { Text("Export EPUB") }
                        TextButton(onClick = onExportCbz) { Text("Export CBZ") }
                        TextButton(
                            onClick = {
                                markdownLauncher.launch(
                                    (book.title.replace(Regex("[^A-Za-z0-9]+"), "-")
                                        .trim('-') + "-highlights.md")
                                )
                            }
                        ) { Text("Highlights (.md)") }
                        if (book.sourceType == app.xreader.data.db.SourceType.EXTENSION) {
                            TextButton(onClick = { viewModel.refreshChapters() }) {
                                Text("Refresh chapters")
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { viewModel.storySoFar() }) {
                            Text("Catch me up")
                        }
                        TextButton(onClick = onOpenLore) {
                            Text("Lore & glossary")
                        }
                    }
                    if (book.description.isNotBlank()) {
                        Text("Synopsis", style = MaterialTheme.typography.titleMedium)
                        Text(
                            book.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        // R12/R16: the designed no-synopsis state —
                        // never a silent gap; a source book can look
                        // for its details on the user's other sources.
                        Text("Synopsis", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "No synopsis yet for this book.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (book.sourceType ==
                            app.xreader.data.db.SourceType.EXTENSION
                        ) {
                            TextButton(onClick = { viewModel.findDetailsFromSources() }) {
                                Text("Find details from a source")
                            }
                        }
                    }
                    // Next unread (R16): one tap to the first
                    // chapter you haven't finished.
                    val nextUnread = state.chapters.firstOrNull { !it.read }
                    if (nextUnread != null && state.progress != null) {
                        TextButton(onClick = { onReadChapter(nextUnread.index) }) {
                            Text("Next unread: ${nextUnread.title}")
                        }
                    }
                    Text("Chapters", style = MaterialTheme.typography.titleMedium)
                }
                }
            }
            if (state.chapters.isEmpty()) {
                item(key = "chapters-state") {
                    when {
                        state.chaptersLoading -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                "Loading the chapter list…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        state.chaptersError != null -> Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "The chapter list couldn't be loaded. " +
                                    (state.chaptersError ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                color = app.xreader.ui.theme.errorTextColor()
                            )
                            TextButton(onClick = { viewModel.refreshChapters() }) {
                                Text("Retry")
                            }
                        }
                        book.sourceType == app.xreader.data.db.SourceType.EXTENSION ->
                            Text(
                                "No chapters yet — pull the list from the source.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        else -> Text(
                            "This book has no chapters.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            items(state.chapters, key = { "chapter-${it.id}" }) { chapter ->
                ChapterRow(
                    chapter = chapter,
                    isCurrent = chapter.index == state.continueChapterIndex && state.progress != null,
                    minutesLeft = if (chapter.wordCount > 0) {
                        (chapter.wordCount / state.wpm).toInt().coerceAtLeast(1)
                    } else {
                        null
                    },
                    onClick = { onReadChapter(chapter.index) },
                    onDownload = { viewModel.downloadChapter(chapter) },
                    modifier = Modifier.animateItem(placementSpec = listPlacementSpec())
                )
            }
            item(key = "tags") {
                TagsSection(
                    state = state,
                    engineLabel = engineLabel,
                    onOpenTag = onOpenTag,
                    onAddTag = { viewModel.addTag(it) },
                    onRemoveTag = { viewModel.removeTag(it) },
                    onSuggest = { viewModel.suggestTagsFromSynopsis() },
                    onSuggestAi = { viewModel.suggestTagsWithAi() },
                    onAcceptSuggestion = { viewModel.acceptTagSuggestion(it) },
                    onDismissSuggestion = { viewModel.dismissTagSuggestion(it) }
                )
            }
            item(key = "sources-merge") {
                SourcesMergeSection(
                    state = state,
                    onLoadOtherSources = { viewModel.loadOtherSources() },
                    onOpenCopy = { id -> onOpenRelated(id) },
                    onPreviewMove = { viewModel.previewSwitch(it) },
                    onMerge = { viewModel.mergeDuplicate(it) },
                    onUnmerge = { viewModel.unmerge(it) }
                )
            }
            item(key = "reviews") {
                EnrichmentSections(
                    state = state,
                    onOpenRelated = { related ->
                        viewModel.openRelated(related) { id -> onOpenRelated(id) }
                    }
                )
            }
            item(key = "ai-suggestions") {
                AiSuggestionsSection(
                    state = state,
                    engineLabel = engineLabel,
                    onAsk = { viewModel.askAiSuggestions() },
                    onDismiss = { viewModel.dismissAiSuggestion(it) },
                    onFind = onFindTitle
                )
            }
            item(key = "book-actions") {
                BookCleanupActions(viewModel = viewModel, bookTitle = book.title, onRemoved = onBack)
            }
            item(key = "bottom-space") { Spacer(Modifier.height(8.dp)) }
        }
    }

    // Phase 5: the story-so-far recap dialog.
    if (state.storyOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissStory() },
            title = { Text("Catch me up — the story so far") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when {
                        state.storyBusy -> Text("Reading your summaries…")
                        state.storyError != null -> Text(
                            state.storyError ?: "",
                            color = errorTextColor()
                        )
                        else -> state.storyBullets?.forEach { bullet ->
                            Text("• $bullet", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissStory() }) { Text("Done") }
            }
        )
    }

    // R11: the deliberate-switch preview — nothing has changed yet;
    // the numbers here are exactly what Confirm will do.
    val preview = state.switchPreview
    val switchCandidate = state.switchCandidate
    if (preview != null && switchCandidate != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissSwitch() },
            title = { Text("Move to ${switchCandidate.sourceName}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${preview.matchedChapters} of your chapters match " +
                            "on the new source (${preview.newSourceChapters} there).",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (preview.progressChapterIndex >= 0) {
                        Text(
                            "Your place lands at chapter " +
                                "${preview.progressChapterIndex + 1}" +
                                if (preview.progressApproximate) {
                                    " (the nearest earlier match)."
                                } else {
                                    "."
                                },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        "Downloaded chapters stay with the old copy — " +
                            "you can download again on the new source. " +
                            "The old copy is kept, merged, and can be " +
                            "brought back any time.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!preview.canSwitch) {
                        Text(
                            "Too few chapters match to move safely — " +
                                "this move isn't available.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = errorTextColor()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.confirmSwitch()
                        onBack()
                    },
                    enabled = preview.canSwitch
                ) { Text("Move this book") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissSwitch() }) {
                    Text("Stay here")
                }
            }
        )
    }
}

/** R22 tags: tappable chips (open the tag page), × removes with a
 * correction tombstone, plus add-your-own and synopsis suggestions.
 * INFERRED tags are labelled as suggestions. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TagsSection(
    state: BookDetailUiState,
    engineLabel: String,
    onOpenTag: (String) -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onSuggest: () -> Unit,
    onSuggestAi: () -> Unit,
    onAcceptSuggestion: (app.xreader.data.ai.TagSuggestion) -> Unit,
    onDismissSuggestion: (app.xreader.data.ai.TagSuggestion) -> Unit
) {
    var draft by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Tags", style = MaterialTheme.typography.titleMedium)
        if (state.tags.isEmpty()) {
            Text(
                "No tags yet. Tags help you find books like this one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.tags.forEach { tag ->
                    FilterChip(
                        selected = false,
                        onClick = { onOpenTag(tag.slug) },
                        label = {
                            Text(
                                app.xreader.data.library.TagRegistry
                                    .displayFor(tag.slug) +
                                    if (tag.origin ==
                                        app.xreader.data.db.TagOrigin.INFERRED
                                    ) {
                                        " · suggested"
                                    } else {
                                        ""
                                    }
                            )
                        },
                        trailingIcon = {
                            TextButton(onClick = { onRemoveTag(tag.slug) }) {
                                Text("×")
                            }
                        }
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Add a tag") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    onAddTag(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank()
            ) { Text("Add") }
        }
        TextButton(onClick = onSuggest) { Text("Suggest tags from the synopsis") }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(
                onClick = onSuggestAi,
                enabled = !state.tagSuggestBusy
            ) {
                Text(
                    if (state.tagSuggestBusy) {
                        "Asking AI…"
                    } else {
                        "AI tag suggestions"
                    }
                )
            }
            app.xreader.ui.ai.EngineChip(label = engineLabel)
        }
        state.tagSuggestNote?.let { note ->
            Text(
                note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.tagSuggestions.isNotEmpty()) {
            Text(
                "The AI suggests — tap ✓ to add a tag, × to dismiss it. " +
                    "Nothing is added until you tap ✓.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.tagSuggestions.forEach { suggestion ->
                    FilterChip(
                        selected = false,
                        onClick = { onAcceptSuggestion(suggestion) },
                        label = { Text(suggestion.displayName) },
                        trailingIcon = {
                            Row {
                                TextButton(
                                    onClick = { onAcceptSuggestion(suggestion) }
                                ) { Text("✓") }
                                TextButton(
                                    onClick = { onDismissSuggestion(suggestion) }
                                ) { Text("×") }
                            }
                        }
                    )
                }
            }
        }
    }
}

/** R11 + sweep: other sources for this work, duplicates in the
 * library, and copies this book has absorbed — all with visible,
 * reversible actions. */
@Composable
private fun SourcesMergeSection(
    state: BookDetailUiState,
    onLoadOtherSources: () -> Unit,
    onOpenCopy: (Long) -> Unit,
    onPreviewMove: (OtherSourceCandidate) -> Unit,
    onMerge: (Long) -> Unit,
    onUnmerge: (Long) -> Unit
) {
    val book = state.book ?: return
    if (book.sourceType != app.xreader.data.db.SourceType.EXTENSION &&
        state.duplicates.isEmpty() && state.mergedCopies.isEmpty()
    ) {
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (book.sourceType == app.xreader.data.db.SourceType.EXTENSION) {
            Text("Other sources", style = MaterialTheme.typography.titleMedium)
            when (state.otherSourcesState) {
                OtherSourcesState.NOT_LOADED -> TextButton(
                    onClick = onLoadOtherSources
                ) { Text("Find this book on my other sources") }
                OtherSourcesState.LOADING -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp))
                    Text("Looking…", style = MaterialTheme.typography.bodyMedium)
                }
                OtherSourcesState.FAILED -> TextButton(
                    onClick = onLoadOtherSources
                ) { Text("That didn't work — try again") }
                OtherSourcesState.LOADED -> {
                    if (state.otherSources.isEmpty()) {
                        Text(
                            "No other source has this book.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    state.otherSources.forEach { candidate ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    candidate.sourceName,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    if (candidate.inLibraryBookId != null) {
                                        "Already in your library" +
                                            if (candidate.downloadedCount > 0) {
                                                " · ${candidate.downloadedCount} downloaded"
                                            } else {
                                                ""
                                            }
                                    } else {
                                        "Not in your library yet"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (candidate.inLibraryBookId != null) {
                                TextButton(
                                    onClick = { onOpenCopy(candidate.inLibraryBookId) }
                                ) { Text("Open") }
                            }
                            TextButton(onClick = { onPreviewMove(candidate) }) {
                                Text("Move here…")
                            }
                        }
                    }
                }
            }
        }
        if (state.duplicates.isNotEmpty()) {
            Text(
                "Same book, another copy",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Merging keeps this book as the one you see; the other " +
                    "copy is hidden, not deleted, and can be unmerged.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.duplicates.forEach { duplicate ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        duplicate.title,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onMerge(duplicate.id) }) {
                        Text("Merge into this book")
                    }
                }
            }
        }
        if (state.mergedCopies.isNotEmpty()) {
            Text("Merged copies", style = MaterialTheme.typography.titleMedium)
            state.mergedCopies.forEach { copy ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        copy.title,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onUnmerge(copy.id) }) { Text("Unmerge") }
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: Chapter,
    isCurrent: Boolean,
    minutesLeft: Int?,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(chapter.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    when {
                        chapter.read -> "Read"
                        isCurrent -> "Continue here" +
                            (minutesLeft?.let { " · ≈$it min left" } ?: "")
                        chapter.wordCount > 0 -> "${chapter.wordCount} words" +
                            (minutesLeft?.let { " · ≈$it min" } ?: "")
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when (chapter.downloadState) {
                DownloadState.READY -> Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Downloaded",
                    tint = MaterialTheme.colorScheme.secondary
                )
                DownloadState.DOWNLOADING, DownloadState.QUEUED -> Icon(
                    Icons.Filled.Schedule,
                    contentDescription = "Queued",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DownloadState.FAILED -> IconButton(onClick = onDownload) {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = "Download failed — retry",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                DownloadState.NONE -> {
                    if (!chapter.remoteId.isNullOrBlank()) {
                        IconButton(onClick = onDownload) {
                            Icon(Icons.Filled.Download, contentDescription = "Download chapter")
                        }
                    }
                }
            }
        }
    }
}

/** v1.0.1 provider enrichment sections, in the decluttered order:
 * Reviews → Comments → More like this. Honest states only: a source
 * that doesn't offer a section gets one quiet line; a source that
 * offers it but has none says that instead. Local books hide the
 * sections entirely (they were never offered). */
@Composable
private fun EnrichmentSections(
    state: BookDetailUiState,
    onOpenRelated: (app.xreader.extensionapi.SourceBook) -> Unit
) {
    val book = state.book ?: return
    if (book.sourceType != app.xreader.data.db.SourceType.EXTENSION) return
    if (!state.enrichmentLoaded) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Reviews
        Text("Reviews", style = MaterialTheme.typography.titleMedium)
        when {
            state.reviews == null -> Text(
                "This source doesn't share reviews.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.reviews.isEmpty() -> Text(
                "No reviews yet for this book on this source.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> {
                val rated = state.reviews.mapNotNull { it.rating }
                if (rated.isNotEmpty()) {
                    Text(
                        "★ %.1f average from ${state.reviews.size} review(s)".format(
                            rated.sum() / rated.size
                        ),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                state.reviews.take(10).forEach { review ->
                    Column {
                        Text(
                            review.author +
                                (review.rating?.let { " · ★ $it" } ?: "") +
                                (review.dateText?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            review.text,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
        // Comments
        Text("Comments", style = MaterialTheme.typography.titleMedium)
        when {
            state.comments == null -> Text(
                "This source doesn't share comments.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.comments.isEmpty() -> Text(
                "No comments yet for this book on this source.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> state.comments.take(10).forEach { comment ->
                Column {
                    Text(
                        comment.author +
                            (comment.dateText?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(comment.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        // More like this
        val related = state.related
        if (!related.isNullOrEmpty()) {
            Text("More like this", style = MaterialTheme.typography.titleMedium)
            related.take(10).forEach { other ->
                TextButton(onClick = { onOpenRelated(other) }) {
                    Text(other.title)
                }
            }
        }
    }
}

/**
 * v1.0.2 (R26): AI "More like this" — labelled suggestions from
 * the user's own Gemini key. No key → a Set-up line instead of a
 * dead card. Asking is always a deliberate tap (the key is never
 * spent silently), results are cached per book, dismissals are
 * remembered, and nothing is auto-added: Find hands the title to
 * global search, where adding works like any other result.
 */
@Composable
private fun AiSuggestionsSection(
    state: BookDetailUiState,
    engineLabel: String,
    onAsk: () -> Unit,
    onDismiss: (String) -> Unit,
    onFind: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "More like this — AI suggestions",
                style = MaterialTheme.typography.titleMedium
            )
            app.xreader.ui.ai.EngineChip(label = engineLabel)
        }
        when (state.aiSuggestionsState) {
            AiSuggestionsState.NO_KEY -> Text(
                "Set up an AI provider in Providers & keys to get AI " +
                    "suggestions for books like this one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AiSuggestionsState.NOT_ASKED -> {
                Text(
                    "Ask your AI engine for books similar to this one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onAsk) { Text("Ask AI") }
            }
            AiSuggestionsState.LOADING -> Text(
                "Asking…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AiSuggestionsState.FAILED -> {
                Text(
                    "The AI couldn't answer just now.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onAsk) { Text("Try again") }
            }
            AiSuggestionsState.READY -> {
                if (state.aiSuggestions.isEmpty()) {
                    Text(
                        "No suggestions left — you've dismissed them all.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                state.aiSuggestions.forEach { title ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { onFind(title) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("🔍  $title")
                        }
                        TextButton(onClick = { onDismiss(title) }) {
                            Text("Dismiss")
                        }
                    }
                }
                Text(
                    "Suggestions from your AI engine, based on this book. " +
                        "Tap one to find it on your sources.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** v1.0.1 cleanup + removal: visible, labelled, with the size shown
 * BEFORE confirming and honest consequences in plain words. */
@Composable
private fun BookCleanupActions(
    viewModel: BookDetailViewModel,
    bookTitle: String,
    onRemoved: () -> Unit
) {
    var confirmCleanup by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var sizeBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(confirmCleanup, confirmRemove) {
        if (confirmCleanup || confirmRemove) {
            sizeBytes = viewModel.downloadedSizeBytes()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (sizeBytes > 0L || true) {
            TextButton(onClick = { confirmCleanup = true }) {
                Text("Delete downloaded chapters")
            }
        }
        TextButton(onClick = { confirmRemove = true }) {
            Text(
                "Remove from library",
                color = app.xreader.ui.theme.errorTextColor()
            )
        }
    }
    if (confirmCleanup) {
        AlertDialog(
            onDismissRequest = { confirmCleanup = false },
            title = { Text("Delete downloaded chapters?") },
            text = {
                Text(
                    "This frees ${formatMb(sizeBytes)} on this phone. The book, " +
                        "your place in it and your bookmarks are all kept — " +
                        "chapters can be downloaded again."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmCleanup = false
                    viewModel.deleteDownloadedChapters()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmCleanup = false }) { Text("Keep them") }
            }
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove “$bookTitle”?") },
            text = {
                Text(
                    "Removing the book also removes its reading progress and " +
                        "bookmarks. Your original file (if you imported one) " +
                        "stays exactly where it is — only Xreader's own copies " +
                        "can be deleted, and only if you choose that below."
                )
            },
            confirmButton = {
                Column {
                    TextButton(onClick = {
                        confirmRemove = false
                        viewModel.removeFromLibrary(deleteFiles = false) { onRemoved() }
                    }) { Text("Remove book only") }
                    TextButton(onClick = {
                        confirmRemove = false
                        viewModel.removeFromLibrary(deleteFiles = true) { onRemoved() }
                    }) {
                        Text("Remove book and downloaded chapters (${formatMb(sizeBytes)})")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("Keep the book") }
            }
        )
    }
}

private fun formatMb(bytes: Long): String =
    if (bytes >= 1024L * 1024L) "${bytes / (1024L * 1024L)} MB"
    else "${(bytes / 1024L).coerceAtLeast(1)} KB"
