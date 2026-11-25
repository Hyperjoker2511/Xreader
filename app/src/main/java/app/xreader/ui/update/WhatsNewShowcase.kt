package app.xreader.ui.update

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.xreader.data.prefs.DesignState
import kotlinx.coroutines.launch

/**
 * Code 121 (R39): this file carries TWO showcases, kept apart
 * by design:
 *
 * 1. [WhatsNewRevealEditorial] — the reveal for a
 *    DESIGN-CARRYING update (code 123's design, renamed
 *    Material 3 in code 124). It wears the NEW design
 *    itself — composed inside
 *    [app.xreader.ui.theme.EditorialThemeRoot] regardless of
 *    the active design — it is TRULY MODAL (a [Dialog];
 *    touches cannot reach the shell beneath it), and its
 *    seen-record is [RevealPrefs], written only by its own
 *    terminal actions (Try / Keep mine / Skip / finishing
 *    the last card) and read only by its gate in
 *    MainActivity. (The code-121 M3 reveal that stood here
 *    was removed with its design in code 124.)
 *
 * 2. [WhatsNewShowcase] — the legacy showcase for updates
 *    that carry NO new design. It wears the ACTIVE design, as
 *    before, and keeps its OWN seen-store ([ShowcasePrefs];
 *    code 125 split it off the GamePrefs store it used to
 *    share with the Me note). It is gated separately in
 *    MainActivity and never touches the reveal's record.
 */

// ── The reveal (R39) ─────────────────────────────────────────────

/**
 * Code 123 (R39, re-pointed): the reveal for THIS design-
 * carrying update — the Editorial design. Same mechanics as
 * [WhatsNewReveal]: truly modal (a [Dialog]), its seen-record
 * is [RevealPrefs] written only by its terminal actions, Skip
 * on every card, and it never switches anyone automatically.
 *
 * It presents the design AS ITSELF — composed inside
 * [app.xreader.ui.theme.EditorialThemeRoot] with the design
 * forced to Editorial and the mode forced to LIGHT for this
 * subtree only: the light paper face is the design as the
 * owner approved it, page by page, and the reveal is where
 * existing users meet it. (The stored design and mode are
 * untouched unless Try is tapped; a user who tries Editorial
 * gets its dark form too, from their own mode setting.)
 *
 * [onTryDesign] must switch the design to Editorial
 * (DesignPrefs) and then finish; [onFinish] records the
 * reveal seen and dismisses.
 */
@Composable
fun WhatsNewRevealEditorial(
    designState: DesignState,
    onTryDesign: () -> Unit,
    onFinish: () -> Unit
) {
    val previewState = remember(designState) {
        designState.copy(
            design = app.xreader.data.prefs.AppDesign.EDITORIAL,
            themeMode = app.xreader.data.prefs.M3ThemeMode.LIGHT
        )
    }
    Dialog(
        onDismissRequest = onFinish,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        app.xreader.ui.theme.EditorialThemeRoot(
            design = previewState,
            reduceMotion = false
        ) {
            EditorialRevealPager(
                onTryDesign = onTryDesign,
                onFinish = onFinish
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EditorialRevealPager(
    onTryDesign: () -> Unit,
    onFinish: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val lastPage = 2
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "What's new",
                    style = MaterialTheme.typography.headlineSmall
                )
                // Skip on EVERY card, from the first (R39).
                TextButton(onClick = onFinish) { Text("Skip") }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout
                    .PaddingValues(vertical = 16.dp),
                pageSpacing = 16.dp
            ) { page ->
                Card(
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(
                        containerColor =
                            MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier.fillMaxSize()
                ) {
                    when (page) {
                        0 -> EditorialRevealFeature(
                            title = "Paper & sage",
                            body = "A new design for Xreader — warm " +
                                "paper, a sage accent and a serif " +
                                "voice, drawn around your books. " +
                                "You are looking at it now.",
                            demo = { EditorialLibraryDemo() }
                        )
                        1 -> EditorialRevealFeature(
                            title = "Your books, front and centre",
                            body = "In-progress books lead the " +
                                "Library with your place on every " +
                                "cover, and the book page carries " +
                                "the book's own Contents.",
                            demo = { EditorialBookDemo() }
                        )
                        else -> Column(
                            Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(12.dp),
                            horizontalAlignment =
                                Alignment.CenterHorizontally
                        ) {
                            Spacer(Modifier.weight(1f))
                            Box(
                                Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(
                                        MaterialTheme.colorScheme
                                            .primaryContainer
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme
                                        .onPrimaryContainer
                                )
                            }
                            Text(
                                "This is the new Material 3 design.",
                                style = MaterialTheme.typography
                                    .headlineSmallEmphasized
                            )
                            Text(
                                "Try it now, or keep the design " +
                                    "you have. You can switch any " +
                                    "time in Customise → Design — " +
                                    "nothing changes unless you tap.",
                                style = MaterialTheme.typography
                                    .bodyMedium,
                                color = MaterialTheme.colorScheme
                                    .onSurfaceVariant
                            )
                            Spacer(Modifier.weight(1f))
                            Button(
                                onClick = onTryDesign,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Try Material 3")
                            }
                            FilledTonalButton(
                                onClick = onFinish,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Keep mine")
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0..lastPage).forEach { index ->
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(
                                    if (index ==
                                        pagerState.currentPage
                                    ) {
                                        MaterialTheme.colorScheme
                                            .primary
                                    } else {
                                        MaterialTheme.colorScheme
                                            .surfaceContainerHigh
                                    },
                                    CircleShape
                                )
                        )
                    }
                }
                if (pagerState.currentPage < lastPage) {
                    Button(
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(
                                    pagerState.currentPage + 1
                                )
                            }
                        }
                    ) { Text("Next") }
                } else {
                    TextButton(onClick = onFinish) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun EditorialRevealFeature(
    title: String,
    body: String,
    demo: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(
                    MaterialTheme.colorScheme.secondaryContainer
                ),
            contentAlignment = Alignment.Center
        ) {
            demo()
        }
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmallEmphasized
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** The reveal's Library miniature — the copied page's own
 * atoms (search pill, serif headline, a Continue card) in
 * miniature. A demo arrangement only; it navigates nothing. */
@Composable
private fun EditorialLibraryDemo() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    Icons.Filled.Menu, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    "Search your library",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            "Library",
            style = MaterialTheme.typography.headlineMedium
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    Modifier
                        .size(width = 40.dp, height = 56.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(
                            MaterialTheme.colorScheme.tertiaryContainer
                        )
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        "Continue reading",
                        style = MaterialTheme.typography.titleSmall
                    )
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { 0.63f },
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme
                            .secondaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "63% · Chapter 214",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme
                            .onSurfaceVariant
                    )
                }
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.PlayArrow, null,
                        tint = MaterialTheme.colorScheme
                            .onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/** The reveal's book-page miniature — the design's progress
 * ring and its two tiles, in miniature. */
@Composable
private fun EditorialBookDemo() {
    Row(
        Modifier.padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            Modifier.size(92.dp),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                progress = { 0.63f },
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme
                    .secondaryContainer,
                strokeWidth = 9.dp,
                modifier = Modifier.size(92.dp)
            )
            Text(
                "63%",
                style = MaterialTheme.typography.titleLarge
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.size(width = 120.dp, height = 64.dp)
            ) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Catch me up",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme
                            .onPrimaryContainer
                    )
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.size(width = 120.dp, height = 64.dp)
            ) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Lore",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme
                            .onTertiaryContainer
                    )
                }
            }
        }
    }
}

// ── The legacy showcase (non-design-carrying updates) ────────────

/**
 * The showcase for an update that brings NO new design: it
 * wears the ACTIVE design, as it always has. Code 125 (F4):
 * finishing or skipping records the version in the
 * showcase's OWN store ([ShowcasePrefs]) — never the Me
 * note's GamePrefs record any more — and Back counts as a
 * dismissal too (the BackHandler below routes it through
 * the same [onFinish]). It never reads or writes the
 * reveal's [RevealPrefs] record.
 *
 * Code 125's cards present THIS update's fixes (History,
 * the book page, the PDF wash + scrubber, Ask AI as a
 * page), each with a REAL picture of its feature — real
 * words, real numbers, the feature's own atoms — never the
 * grey placeholder lines the code-124 cards drew.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WhatsNewShowcase(onFinish: () -> Unit) {
    // Code 125 (F4): Back dismisses the showcase AND writes
    // the seen record through onFinish. Before this, Back
    // left the showcase (the app exited) without any write,
    // so the showcase returned on the next launch — the
    // repeat the owner saw.
    androidx.activity.compose.BackHandler { onFinish() }
    val features = remember {
        listOf(
            ShowcaseFeature(
                title = "Highlights you can read through",
                body = "A PDF highlight is a see-through wash " +
                    "now — the words stay readable through " +
                    "the colour, never covered by a solid " +
                    "block, at any zoom.",
                demo = ShowcaseDemo.PDF_WASH
            ),
            ShowcaseFeature(
                title = "Every page in the scrubber",
                body = "The filmstrip shows the real page on " +
                    "every thumbnail now, even on stretches " +
                    "you haven't read yet — not a dark card " +
                    "with a number.",
                demo = ShowcaseDemo.FILMSTRIP
            ),
            ShowcaseFeature(
                title = "Ask AI is a page now",
                body = "Ask AI opens as its own full page, " +
                    "with Back taking you exactly where you " +
                    "were — from the reader and from the " +
                    "book page.",
                demo = ShowcaseDemo.AI_PAGE
            ),
            ShowcaseFeature(
                title = "History, fixed",
                body = "No more red behind the cards, the " +
                    "reading pulse shows its numbers again, " +
                    "and every card carries its progress bar " +
                    "and its actions whole.",
                demo = ShowcaseDemo.HISTORY
            ),
            ShowcaseFeature(
                title = "The book page, tidied",
                body = "Chapters show their release dates, a " +
                    "pull down from the top refreshes the " +
                    "list, and Refresh, Download all and " +
                    "Save as EPUB all fit on one row.",
                demo = ShowcaseDemo.BOOK_PAGE
            )
        )
    }
    val pagerState = rememberPagerState(pageCount = { features.size })
    val scope = rememberCoroutineScope()
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text(
                "What's new",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 8.dp)
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout
                    .PaddingValues(vertical = 16.dp),
                pageSpacing = 16.dp
            ) { page ->
                val feature = features[page]
                Card(
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(
                        containerColor =
                            MaterialTheme.colorScheme
                                .surfaceContainer
                    ),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(
                                    MaterialTheme.colorScheme
                                        .surfaceContainerHigh
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            when (feature.demo) {
                                ShowcaseDemo.PDF_WASH ->
                                    PdfHighlightWashDemo()
                                ShowcaseDemo.FILMSTRIP ->
                                    FilmstripDemo()
                                ShowcaseDemo.AI_PAGE ->
                                    AskAiPageDemo()
                                ShowcaseDemo.HISTORY ->
                                    HistoryFixedDemo()
                                ShowcaseDemo.BOOK_PAGE ->
                                    BookPageDemo()
                            }
                        }
                        Column(
                            Modifier.padding(20.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                feature.title,
                                style = MaterialTheme.typography
                                    .titleLargeEmphasized
                            )
                            Text(
                                feature.body,
                                style = MaterialTheme.typography
                                    .bodyMedium,
                                color = MaterialTheme.colorScheme
                                    .onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onFinish) { Text("Skip") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    features.indices.forEach { index ->
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(
                                    if (index ==
                                        pagerState.currentPage
                                    ) {
                                        MaterialTheme.colorScheme
                                            .primary
                                    } else {
                                        MaterialTheme.colorScheme
                                            .surfaceContainerHigh
                                    },
                                    CircleShape
                                )
                        )
                    }
                }
                Button(
                    onClick = {
                        if (pagerState.currentPage <
                            features.lastIndex
                        ) {
                            scope.launch {
                                pagerState.animateScrollToPage(
                                    pagerState.currentPage + 1
                                )
                            }
                        } else {
                            onFinish()
                        }
                    }
                ) {
                    Text(
                        if (pagerState.currentPage <
                            features.lastIndex
                        ) {
                            "Next"
                        } else {
                            "Done"
                        }
                    )
                }
            }
        }
    }
}

private data class ShowcaseFeature(
    val title: String,
    val body: String,
    val demo: ShowcaseDemo
)

private enum class ShowcaseDemo {
    PDF_WASH, FILMSTRIP, AI_PAGE, HISTORY, BOOK_PAGE
}

/**
 * Demo: a PDF highlight as a wash — a real page of real
 * words, with the translucent wash growing across one
 * phrase and a handle at each end, on a loop. The wash is
 * the scheme's primary at the highlight alpha (40%): the
 * words read straight through it.
 */
@Composable
private fun PdfHighlightWashDemo() {
    val transition = rememberInfiniteTransition(label = "pdfDemo")
    val grow by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pdfDemoGrow"
    )
    val wash = MaterialTheme.colorScheme.primary.copy(alpha = 0.40f)
    Column(
        Modifier
            .width(260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "The sword master stepped into the rain and did not look back.",
            style = MaterialTheme.typography.bodyMedium
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "He carried ",
                style = MaterialTheme.typography.bodyMedium
            )
            // The highlighted phrase: real words, the wash
            // over them, handles at the wash's ends. The
            // wash is drawn over the words at 40% — they
            // read through it, which is the whole point.
            Box {
                Text(
                    "the rain",
                    style = MaterialTheme.typography.bodyMedium
                )
                Box(
                    Modifier
                        .fillMaxWidth(grow.coerceAtLeast(0.02f))
                        .fillMaxHeight()
                        .background(wash, RoundedCornerShape(3.dp))
                ) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 3.dp, y = 7.dp)
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                MaterialTheme.colorScheme.primary
                            )
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = (-4).dp, y = 7.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.primary
                        )
                )
            }
            Text(
                " on his shoulders.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Text(
            "Behind him the town kept its lights on, waiting for a name it would never hear again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Demo: the scrubber filmstrip — five real thumbnails, each
 * a tiny page of real words with its page number, the
 * current one outlined in the accent. No dark number-only
 * cards.
 */
@Composable
private fun FilmstripDemo() {
    val scheme = MaterialTheme.colorScheme
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(
            212 to "The night was cold.",
            213 to "He lit the last lamp.",
            214 to "Rain on the roof.",
            215 to "A knock, then silence.",
            216 to "Morning came grey."
        ).forEachIndexed { index, (number, line) ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    Modifier
                        .size(width = 46.dp, height = 64.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(scheme.surface)
                        .then(
                            if (index == 2) {
                                Modifier.border(
                                    2.dp, scheme.primary,
                                    RoundedCornerShape(4.dp)
                                )
                            } else {
                                Modifier
                            }
                        )
                        .padding(5.dp)
                ) {
                    Column(
                        verticalArrangement =
                            Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            "Ch. $number",
                            fontSize = 7.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onSurface,
                            maxLines = 1
                        )
                        Text(
                            line,
                            fontSize = 6.sp,
                            lineHeight = 8.sp,
                            color = scheme.onSurfaceVariant,
                            maxLines = 3
                        )
                    }
                }
                Text(
                    "$number",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Demo: Ask AI as a page — the real page furniture in
 * miniature: back arrow, title, the five feature tiles by
 * their real names, and the ask field with its send.
 */
@Composable
private fun AskAiPageDemo() {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .width(250.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = scheme.onSurface,
                modifier = Modifier.size(18.dp)
            )
            Text(
                "Ask AI",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "Summarize", "Catch me up", "Translate",
                "Illustrate", "Lore chat"
            ).forEach { label ->
                Surface(
                    color = scheme.secondaryContainer,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSecondaryContainer,
                        modifier = Modifier.padding(
                            horizontal = 10.dp, vertical = 6.dp
                        )
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = scheme.surfaceContainer,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    "Ask about the story…",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = 12.dp, vertical = 9.dp
                    )
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(scheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    tint = scheme.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Demo: the History card fixed — the pulse with its real
 * numbers above the labels, and one card with its progress
 * bar, its Continue pill and its Remove, whole.
 */
@Composable
private fun HistoryFixedDemo() {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .width(280.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            listOf(
                "42 min" to "read today",
                "3" to "books today",
                "12" to "chapters",
                "180" to "EXP today"
            ).forEach { (value, label) ->
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        value,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
        Surface(
            color = scheme.surfaceContainerLow,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Nano Machine",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Chapter 214 · 0% through the book",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = scheme.primary,
                    trackColor = scheme.surfaceContainerHighest
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = scheme.primary,
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text(
                            "Continue · Ch. 214",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onPrimary,
                            modifier = Modifier.padding(
                                horizontal = 12.dp, vertical = 7.dp
                            )
                        )
                    }
                    Text(
                        "Remove",
                        fontSize = 13.sp,
                        color = scheme.onSurface
                    )
                }
            }
        }
    }
}

/**
 * Demo: the book page tidied — Contents rows with their
 * real release dates under the titles, and the three
 * actions whole on one row.
 */
@Composable
private fun BookPageDemo() {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .width(280.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        listOf(
            "Chapter 214" to "3 days ago",
            "Chapter 215" to "Yesterday",
            "Chapter 216" to "12 Nov 2026"
        ).forEach { (title, date) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(scheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        title.takeLast(3),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurfaceVariant
                    )
                }
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        date,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Refresh", "Download all", "Save as EPUB")
                .forEach { label ->
                    Surface(
                        color = scheme.secondaryContainer,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onSecondaryContainer,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(
                                horizontal = 10.dp, vertical = 7.dp
                            )
                        )
                    }
                }
        }
    }
}
