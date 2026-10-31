package app.xreader.ui.start

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.xreader.core.motion.sharedAxisXEnter
import app.xreader.core.motion.sharedAxisXExit
import app.xreader.ui.common.PrimaryPill
import app.xreader.ui.common.ScreenHeader

/**
 * First-run onboarding (PLAN §3): three plain pages, all skippable.
 * Page changes ride the engine's Shared Axis (single motion source,
 * Amendment 5) — no local animation constants.
 *
 * Nocturne reskin (§5.26): each page's hero sits on the HaloStage
 * recipe from the book page (320dp primary-16% radial top-centre +
 * 240dp tertiary-13%), page dots replace the "1 of 3" counter, and
 * there is no dock (as today). Colours come from Material roles
 * only.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    val last = PAGES.lastIndex
    // v1.0.2 (R01): themed Surface root — before, this bare Column had
    // no LocalContentColor provider, so colour-less text rendered in
    // the default black over the dark window (the Recovery defect).
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxSize()
    ) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        ScreenHeader(title = "Welcome to Xreader", eyebrow = "First run")
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                ContentTransform(
                    sharedAxisXEnter(reduceMotion = false),
                    sharedAxisXExit(reduceMotion = false)
                )
            },
            label = "onboardingPage",
            modifier = Modifier.weight(1f)
        ) { current ->
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = (-48).dp)
                        .size(320.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary
                                        .copy(alpha = 0.16f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(x = (-64).dp, y = 150.dp)
                        .size(240.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    MaterialTheme.colorScheme.tertiary
                                        .copy(alpha = 0.13f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        PAGES[current].first,
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        PAGES[current].second,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onDone) { Text("Skip") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PAGES.indices.forEach { index ->
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(
                                if (index == page) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                CircleShape
                            )
                    )
                }
            }
            PrimaryPill(
                text = if (page == last) "Get started" else "Next",
                enabled = true,
                onClick = { if (page == last) onDone() else page++ }
            )
        }
    }
    }
}

private val PAGES = listOf(
    "Your books, your files" to
        "Bring the novels and e-books you already have — EPUB, PDF, TXT " +
        "and more — and read them in one calm, beautiful reader.",
    "Your sources, your library" to
        "Add the reading sources and library servers you use, download " +
        "chapters for offline reading, and pick up on any device where " +
        "you left off.",
    "A reading companion, when you want it" to
        "With a free Gemini key, Xreader can summarise chapters, keep " +
        "track of characters and terms, and translate raw chapters. " +
        "Entirely optional — add the key any time in More."
)
