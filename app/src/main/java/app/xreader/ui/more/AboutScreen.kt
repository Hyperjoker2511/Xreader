package app.xreader.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.xreader.BuildConfig
import app.xreader.ui.common.GlassCard

/** About & licences (PLAN §3): what this build is, and the open-source
 * licences of everything bundled in it. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("About & licences", style = MaterialTheme.typography.headlineSmall)
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Xreader", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "A novel & e-book reader: your files, your sources, " +
                        "your library — on your devices only.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // R28: What's new — the honest, plain-words changelog for
        // this version, kept with the app (no network, no feed).
        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "What's new in ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.titleMedium
                )
                listOf(
                    "Shelves for your library — plus Favourites, Downloaded " +
                        "and Behind-on built in, and smart shelves that fill " +
                        "themselves.",
                    "Tags on every book (Male MC and friends) — tap one to " +
                        "see everything with it, follow the ones you love.",
                    "Pull down in the Library to check for new chapters, " +
                        "silently; results live on the new Updates page.",
                    "Sources check themselves: a test-drive proves a source " +
                        "can list, open, and load a chapter before you trust " +
                        "it, and repairs are offered when one breaks.",
                    "“Bring my library” — after signing in to a source, " +
                        "import the bookshelf from your account there.",
                    "Move a book to another source without losing your " +
                        "place, and merge duplicate copies reversibly.",
                    "The reader flows from chapter to chapter, and a tap " +
                        "in the middle brings up everything in one sheet.",
                    "A new Me card with your reading stats on its back, " +
                        "cover-colour themes, and a make-your-own theme " +
                        "editor.",
                    "“Words I'm learning” — save words while reading and " +
                        "review them on a gentle spaced ladder.",
                    "Fixed: the frosted-glass crash, and dim text on the " +
                        "recovery and welcome screens."
                ).forEach { line ->
                    Text(
                        "• $line",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Open-source licences", style = MaterialTheme.typography.titleMedium)
                LICENCES.forEach { (name, licence) ->
                    Column {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            licence,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private val LICENCES = listOf(
    "Kotlin & kotlinx.coroutines" to "Apache Licence 2.0",
    "AndroidX (Compose, Room, DataStore, WorkManager, Navigation)" to "Apache Licence 2.0",
    "OkHttp & Okio (Square)" to "Apache Licence 2.0",
    "PDFBox-Android (Apache PDFBox)" to "Apache Licence 2.0",
    "kxml2 (XML pull parsing)" to "BSD-style / Apache Licence 2.0",
    "Junrar (RAR extraction, reading only)" to "UnRAR restriction honoured: extraction only, never creation",
    "Coil (image loading)" to "Apache Licence 2.0",
    "jsoup (HTML parsing)" to "MIT Licence",
    "QuickJS via Cash App's quickjs-android" to "Apache Licence 2.0",
    "ML Kit Face Detection (Google)" to "ML Kit Terms of Service — on-device model",
    "Play Services Auth (Google sign-in)" to "Apache Licence 2.0",
    "Gemini & Google Drive (your own Google account)" to
        "Google APIs Terms of Service — used only with your key / sign-in, never bundled credentials",
    "CameraX (AndroidX)" to "Apache Licence 2.0",
    "Focus soundscapes (Rain / Fireplace / Forest)" to "Generated for Xreader — public domain (CC0)",
    "Plus Jakarta Sans" to "SIL Open Font Licence 1.1",
    "Inter" to "SIL Open Font Licence 1.1",
    "Literata (TypeTogether)" to "SIL Open Font Licence 1.1"
)
