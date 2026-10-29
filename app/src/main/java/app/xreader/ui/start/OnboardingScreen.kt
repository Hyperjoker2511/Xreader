package app.xreader.ui.start

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.unit.dp
import app.xreader.core.motion.sharedAxisXEnter
import app.xreader.core.motion.sharedAxisXExit
import app.xreader.ui.common.ScreenHeader
import app.xreader.ui.common.XreaderPrimaryButton

/**
 * First-run onboarding (PLAN §3): three plain pages, all skippable.
 * Page changes ride the engine's Shared Axis (single motion source,
 * Amendment 5) — no local animation constants.
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
            .padding(horizontal = 24.dp, vertical = 16.dp)
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
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onDone) { Text("Skip") }
            Text(
                "${page + 1} of ${PAGES.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            XreaderPrimaryButton(
                text = if (page == last) "Get started" else "Next",
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
