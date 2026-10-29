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
                    // v1.0.5 Stage 1 (the honest-engine round).
                    "Sources are proven honestly now: the test read " +
                        "fails when a book page lists no chapters, " +
                        "instead of blaming the wrong step — and it " +
                        "says exactly what it proved.",
                    "Locked chapters are skipped, not misread: the " +
                        "test read samples a free chapter, and when " +
                        "every chapter is paid or locked it says so " +
                        "plainly.",
                    "Repair works harder and explains itself: it " +
                        "tries every way of reading the site (and the " +
                        "AI detector, if you have a key), keeps the " +
                        "best one that passes, and tells you which " +
                        "step failed when none can.",
                    "Search is proven, not assumed: a source's " +
                        "search only counts as working when a real " +
                        "word finds books and a nonsense word " +
                        "doesn't — a search that just lists " +
                        "everything is called out.",
                    "Covers bind properly: books get their real " +
                        "cover from the book page, and when a site " +
                        "truly has none, you're told at add time — " +
                        "no more silent letter tiles.",
                    "Known sites are faster: big novel sites are " +
                        "recognised and read with their known shape " +
                        "first — still proven by the same test read.",
                    "Sites that only exist as JavaScript apps are " +
                        "refused plainly at add time instead of " +
                        "being installed broken.",
                    // v1.0.5 Stage 1.5 (the ajax text mode).
                    "Sites that load their chapter text in the " +
                        "background — Novelight is one — now read " +
                        "properly: Xreader fetches the text the way " +
                        "the site itself does, and locked chapters " +
                        "are still called out as locked.",
                    // v1.0.5 Stage 2 (the book-page round).
                    "Every book page has a proper Chapters section " +
                        "now — with the chapter count when there is " +
                        "a list, and when a source returns none, real " +
                        "next steps instead of a dead end: Re-check " +
                        "now (which also test-runs the source), open " +
                        "the book's own page on the site, or go to " +
                        "Repair.",
                    "Downloading sits right beside Start reading on " +
                        "the book page, with the choices in one " +
                        "sheet: the whole book, the next 10 chapters, " +
                        "or everything up to the latest chapter you " +
                        "haven't read — each with its count.",
                    "Long-pressing a book in the Library opens a " +
                        "glass action sheet with icons, and the " +
                        "Remove dialogs stack their choices clearly " +
                        "instead of squeezing them into one row.",
                    "Fixed: chips and buttons can no longer be " +
                        "crushed into vertical letter stacks on " +
                        "narrow screens — they wrap or scroll " +
                        "instead (the book page's “Dropped” chip was " +
                        "the worst case).",
                    // v1.0.5 Stage 3 (Library, Browse, healing).
                    "The Library is calmer: History and Updates left " +
                        "its toolbar (they're tabs already), and the " +
                        "shelf row is the only chip row now. Status, " +
                        "sorting and Check for updates live behind " +
                        "one Filter chip.",
                    "Browse is a tidy list: your sources — link " +
                        "sources and library servers together — are " +
                        "compact rows with a small health dot (green " +
                        "working, amber needs setup, red broken, grey " +
                        "never checked). Details shows when a source " +
                        "was last checked.",
                    "Adding has its own screen — repository, site " +
                        "link or Legado file, each with its form — " +
                        "and repositories live in a Source store " +
                        "screen of their own, so Browse never grows " +
                        "a form in the middle again.",
                    "Your older link sources were quietly re-checked " +
                        "with the new test read: any that failed were " +
                        "repaired automatically and their books " +
                        "refreshed. If anything was fixed, the Me " +
                        "page says so — once, not as a nag.",
                    "When a source fails its test read, “Copy " +
                        "details” puts a short plain summary — the " +
                        "site, the failing step, the reason — on " +
                        "your clipboard, so a problem can be shared " +
                        "without a screenshot.",
                    "True black now says what it does: it turns " +
                        "the frosted-glass glow off on every screen, " +
                        "Library included — pure black is the point " +
                        "of the setting.",
                    // v1.0.4 Stage 1 (the Liquid round). Stages 2–3
                    // extend this list before the version ships.
                    "Real frosted glass: with Frosted on, cards and the " +
                        "navigation bar now truly blur the glowing mesh " +
                        "behind them — no more flat tint pretending.",
                    "A new colour scheme, “Electric violet”, built on " +
                        "electric purple, cyan and pink.",
                    "Buttons, cards and the Continue Reading card bounce " +
                        "with a springy Apple-style press.",
                    "The Library toolbar scrolls sideways now — “Add a " +
                        "list” and Sort can never be crushed or pushed " +
                        "off screen again.",
                    "Fixed: an empty library shows its proper “No books " +
                        "yet” screen instead of bare chrome.",
                    "The download switches moved to Settings → Customise " +
                        "→ Downloads, together, with their full names.",
                    // v1.0.4 Stage 2 (Browse / Me / History /
                    // Downloads + screen transitions).
                    "Browse, History, Downloads and Me now wear the " +
                        "same liquid glass as the Library — search pill, " +
                        "source cards and settings rows included.",
                    "Moving between screens slides the Apple way: a " +
                        "new page slides in fully while the page beneath " +
                        "drifts back just a little. Tabs still crossfade " +
                        "calmly — they never slide.",
                    "Your Profile breathes: the glow behind your card " +
                        "drifts slowly, the level ring sweeps in cyan " +
                        "and purple, your stats count up, and today's " +
                        "goal fills as a smooth bar.",
                    "Empty History and Downloads screens float gently " +
                        "instead of sitting flat.",
                    // v1.0.4 Stage 3 (sources: D14 + D15).
                    "Add from link got smarter: it can now work out " +
                        "a site's layout from the shape of its pages " +
                        "alone — no AI key needed — and it still proves " +
                        "itself with a test read before saving.",
                    "You can fix a link source yourself: on its " +
                        "Details page, “Edit parser rules” lets you " +
                        "adjust how Xreader reads the site, with a " +
                        "test read before anything is saved — and " +
                        "rules can now carry fallbacks, so one broken " +
                        "selector no longer breaks the source.",
                    "A book whose chapter list comes back empty now " +
                        "says so plainly on its page, instead of " +
                        "failing later in the reader."
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
