package app.xreader.ui.update

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Code 120 completion (design §2.9 + the owner's standing
 * What's-New rule, 2026-11-24): after an update, EVERY user
 * is shown what the update brought — one card per feature,
 * each with its own picture or small animation composed here
 * from the app's own components and colours (never a plain
 * text list, never external media) — and the showcase is
 * skippable from the first card. It appears once per version:
 * MainActivity shows it only when the stored last-seen code
 * is behind the installed one, and finishing or skipping
 * records the version seen.
 *
 * It wears the ACTIVE design: most existing users will see
 * it in Nocturne, which is correct — it announces the new
 * design, it does not switch anyone. The one shape-library
 * marker (§1.3's showcase spend) rides the headline card.
 *
 * This update's features: the Material 3 design itself,
 * PDF text selection fixed (works zoomed), and selection
 * colours that follow the theme.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WhatsNewShowcase(onFinish: () -> Unit) {
    val features = remember {
        listOf(
            ShowcaseFeature(
                title = "A whole new design",
                body = "Xreader now wears Material 3 — Google's " +
                    "design, in your phone's colours or any " +
                    "colour you pick. Switch any time in " +
                    "Customise → Design.",
                demo = ShowcaseDemo.DESIGN
            ),
            ShowcaseFeature(
                title = "PDF selection, fixed",
                body = "You can select and copy words in a PDF " +
                    "again — even zoomed right in, the way you " +
                    "actually read.",
                demo = ShowcaseDemo.PDF_SELECTION
            ),
            ShowcaseFeature(
                title = "Selection follows your colours",
                body = "The highlight colour now comes from " +
                    "your design's colours, and the colour row " +
                    "on the selection bar tints with it.",
                demo = ShowcaseDemo.SELECTION_COLOURS
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
                                ShowcaseDemo.DESIGN ->
                                    DesignDemo(headline = page == 0)
                                ShowcaseDemo.PDF_SELECTION ->
                                    PdfSelectionDemo()
                                ShowcaseDemo.SELECTION_COLOURS ->
                                    SelectionColoursDemo()
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
                // Skip is visible from the first card — the
                // whole interaction contract (§2.9).
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
    DESIGN, PDF_SELECTION, SELECTION_COLOURS
}

/** Demo (a): the Material 3 design — a miniature flexible
 * bar whose pill slides between tabs while the scheme
 * swatches cycle beneath it; on the headline card, the
 * showcase's one shape-library marker stands behind. The
 * colours are the active scheme's own roles, so the demo
 * wears the design it announces. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DesignDemo(headline: Boolean) {
    val transition = rememberInfiniteTransition(label = "designDemo")
    val slide by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "designDemoSlide"
    )
    val scheme = MaterialTheme.colorScheme
    val swatches = listOf(
        scheme.primary, scheme.secondary, scheme.tertiary
    )
    Box(contentAlignment = Alignment.Center) {
        if (headline) {
            Box(
                Modifier
                    .size(190.dp)
                    .clip(MaterialShapes.Cookie9Sided.toShape())
                    .background(
                        MaterialTheme.colorScheme.primaryContainer
                            .copy(alpha = 0.45f)
                    )
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // The mini flexible bar: five stops, the pill
            // sliding between the first and the last.
            Box(
                Modifier
                    .width(230.dp)
                    .height(52.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerLow
                    )
                    .padding(6.dp)
            ) {
                Box(
                    Modifier
                        .offset(x = (slide * 148).dp)
                        .width(52.dp)
                        .height(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement =
                        Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(5) {
                        Box(
                            Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(
                                    MaterialTheme.colorScheme
                                        .onSurfaceVariant
                                        .copy(alpha = 0.35f)
                                )
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                swatches.forEachIndexed { index, colour ->
                    val active =
                        (slide * 3).toInt().coerceIn(0, 2) == index
                    Box(
                        Modifier
                            .size(if (active) 34.dp else 26.dp)
                            .clip(CircleShape)
                            .background(colour)
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 18.dp, vertical = 9.dp)
                ) {
                    Text(
                        "Read",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            MaterialTheme.colorScheme
                                .secondaryContainer
                        )
                        .padding(horizontal = 18.dp, vertical = 9.dp)
                ) {
                    Text(
                        "Download",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme
                            .onSecondaryContainer
                    )
                }
            }
        }
    }
}

/** Demo (b): PDF selection while zoomed — a page drawn at
 * reading zoom (scaled up, clipped by the card), its lines
 * as quiet bars, and a selection wash that grows across one
 * word with a handle at each end, on a loop. The wash is
 * the scheme's primary at the selection spec alpha — the
 * same binding the fixed selection itself uses. */
@Composable
private fun PdfSelectionDemo() {
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
    val wash = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
    val line = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
    Box(
        Modifier
            .size(width = 250.dp, height = 210.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        // The page at ~1.6× zoom: its content runs off the
        // edges, as a zoomed page does.
        Column(
            Modifier
                .scale(1.6f)
                .width(210.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            listOf(150, 168, 132).forEach { widthDp ->
                Box(
                    Modifier
                        .width(widthDp.dp)
                        .height(7.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(line)
                )
            }
            // The line being selected: three word bars; the
            // wash grows over the middle one.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .width(38.dp)
                        .height(7.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(line)
                )
                Box {
                    Box(
                        Modifier
                            .width(64.dp)
                            .height(7.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(line)
                    )
                    Box(
                        Modifier
                            .width((64 * grow).dp)
                            .height(13.dp)
                            .offset(y = (-3).dp)
                            .background(wash)
                    )
                    if (grow > 0.05f) {
                        Box(
                            Modifier
                                .offset(x = (-4).dp, y = 8.dp)
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    MaterialTheme.colorScheme.primary
                                )
                        )
                        Box(
                            Modifier
                                .offset(
                                    x = (64 * grow - 3).dp,
                                    y = 8.dp
                                )
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    MaterialTheme.colorScheme.primary
                                )
                        )
                    }
                }
                Box(
                    Modifier
                        .width(46.dp)
                        .height(7.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(line)
                )
            }
            listOf(160, 140, 166).forEach { widthDp ->
                Box(
                    Modifier
                        .width(widthDp.dp)
                        .height(7.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(line)
                )
            }
        }
    }
}

/** Demo (c): selection colours follow the theme — a line of
 * "selected" text whose wash cycles through three colours,
 * and a mock selection bar above it whose colour row tints
 * to match, step for step. */
@Composable
private fun SelectionColoursDemo() {
    val scheme = MaterialTheme.colorScheme
    val colours = remember(scheme) {
        listOf(scheme.primary, scheme.tertiary, scheme.secondary)
    }
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1400)
            step = (step + 1) % colours.size
        }
    }
    val tint by animateColorAsState(
        targetValue = colours[step],
        animationSpec = tween(durationMillis = 500),
        label = "selectionTint"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // The mock selection bar: a pill carrying the colour
        // row — the picked colour ringed, the bar's own tint
        // following it.
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(tint.copy(alpha = 0.18f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            colours.forEachIndexed { index, colour ->
                Box(
                    Modifier
                        .size(if (index == step) 24.dp else 18.dp)
                        .clip(CircleShape)
                        .background(colour)
                )
            }
            Spacer(Modifier.width(2.dp))
            Box(
                Modifier
                    .size(width = 30.dp, height = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        MaterialTheme.colorScheme.onSurface
                            .copy(alpha = 0.4f)
                    )
            )
            Box(
                Modifier
                    .size(width = 22.dp, height = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        MaterialTheme.colorScheme.onSurface
                            .copy(alpha = 0.4f)
                    )
            )
        }
        // The selected line: quiet word bars under the wash.
        Box(contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(52, 74, 44, 62).forEach { widthDp ->
                    Box(
                        Modifier
                            .width(widthDp.dp)
                            .height(10.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                MaterialTheme.colorScheme.onSurface
                                    .copy(alpha = 0.28f)
                            )
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .background(tint.copy(alpha = 0.38f))
            )
        }
        Text(
            "One tap recolours the highlight",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
