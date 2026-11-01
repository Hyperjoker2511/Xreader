package app.xreader.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.xreader.BuildConfig
import app.xreader.ui.common.GhostPill
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.SectionHeader

/** About & licences (PLAN §3): what this build is, and the open-source
 * licences of everything bundled in it. */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    /** v1.0.6.2 (M1): supplies the sources + health for
     * "Copy app details"; null in previews/tests. */
    container: app.xreader.di.AppContainer? = null
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("About & licences", style = MaterialTheme.typography.headlineMedium)
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
                if (container != null) {
                    CopyAppDetailsButton(container)
                }
            }
        }

        // R28: What's new — the honest, plain-words changelog for
        // this version, kept with the app (no network, no feed).
        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionHeader(title = "What's new in ${BuildConfig.VERSION_NAME}")
                listOf(
                    // v1.0.6.2 (full chapter lists, live reading,
                    // meaning-checked site rules).
                    "Books now show their FULL chapter list: " +
                        "on sites that reveal chapters page by " +
                        "page, Xreader fetches every page — a " +
                        "700-chapter book shows all 700.",
                    "Browsing and searching a source keeps " +
                        "going: scroll and more books load page " +
                        "after page, the way the site itself " +
                        "pages.",
                    "Tap a chapter and just read it: chapters " +
                        "open live, without downloading first — " +
                        "and the copy is saved quietly, so it's " +
                        "there offline next time.",
                    "Pasting a site's home page works now: " +
                        "Xreader walks in and learns the site " +
                        "from one of its books.",
                    "Site checks now judge meaning, not just " +
                        "shape: a site's social feed can no " +
                        "longer be saved as if it were books, " +
                        "and your AI key can be asked to confirm " +
                        "a list it isn't sure about.",
                    // v1.0.6.2 phase 2 (library look, reader
                    // chrome, downloads resume, Ask AI page).
                    "A fresh library look: covers are posters " +
                        "now — full-size art, the title underneath, " +
                        "one quiet line of details. Search is a " +
                        "small icon in the corner.",
                    "Reading is calmer: the bars never cover " +
                        "your words, tapping the middle of the " +
                        "page always brings them back, paragraphs " +
                        "breathe again, and the chapter number at " +
                        "the bottom matches the chapter you're " +
                        "actually reading.",
                    "Interrupted downloads pick up where they " +
                        "stopped — finished chapters are never " +
                        "fetched twice — and Downloads can pause " +
                        "or resume everything at once.",
                    "Ask AI has its own full page now, with " +
                        "room to read; summaries, catch-up and " +
                        "lore chat all work from the same chapter " +
                        "text you're reading, on local books too.",
                    "Three colour themes — Electric violet, " +
                        "Deep indigo, Magenta violet — and the " +
                        "reader's page themes are three too: " +
                        "AMOLED, Sepia, Paper.",
                    // v1.0.6.1 (honest verdicts, forgiving links,
                    // self-evidencing problem reports).
                    "Adding a site from a link is more forgiving: " +
                        "an address copied with extra text around " +
                        "it, or with hidden marks from a share " +
                        "sheet, is understood instead of refused.",
                    "After a Repair, the Health card clears its " +
                        "old verdict and shows it's checking " +
                        "instead of showing results for rules that " +
                        "no longer exist.",
                    "Repair picks its test book by looking at " +
                        "the pages themselves, so broken old rules " +
                        "can't mislead it into testing the wrong " +
                        "book.",
                    "Test-drives can no longer mistake page " +
                        "clutter for chapter text — and when a " +
                        "site won't show which chapters are " +
                        "locked, the result says so plainly.",
                    "Copy details now includes a short history " +
                        "of recent problems from this session, so " +
                        "a fault report carries its own evidence.",
                    // v1.0.6 (repair records, the browser view,
                    // the Library look).
                    "Repair now picks a fair test book from the " +
                        "site's own list — and leaves a short " +
                        "written note of what it did on the " +
                        "source's page, so nothing important " +
                        "disappears with a passing message.",
                    "A source no longer fails just because its " +
                        "test book's chapters are all paid: good " +
                        "rules are kept, and the source simply " +
                        "works \u201cwith limits\u201d.",
                    "Sites that build their pages with scripts " +
                        "can now be read through the built-in " +
                        "browser view. It never looks like a " +
                        "browser — you only see Xreader's own " +
                        "screens, plus a sign-in page when a site " +
                        "asks for one. Its switch is in Customise.",
                    "The source health check reads the little " +
                        "lock marks on chapter lists properly " +
                        "now, so paid chapters aren't mistaken " +
                        "for broken ones.",
                    "If your connection is too slow to finish " +
                        "checking a site, Repair now says that " +
                        "plainly instead of blaming the site.",
                    "Your library looks better: books without " +
                        "covers get calm violet tiles instead of " +
                        "clashing colours, long titles wrap over " +
                        "two full lines instead of being cut off " +
                        "mid-word, and the header pill now reads " +
                        "\u201cLevel\u201d with your level number.",
                    // v1.0.5.1 (the hotfix).
                    "Fixed: a known site's proven setup could be " +
                        "silently skipped when its book page " +
                        "carries a long synopsis — the known shape " +
                        "is now always tried first, so Repair can " +
                        "rebuild sites like Novelight the way it " +
                        "was meant to.",
                    "Fixed: checking and repairing sources no " +
                        "longer stutters the app — that reading " +
                        "now happens off the main screen thread, " +
                        "and if the app is closed mid-check, the " +
                        "check simply runs again on a later " +
                        "launch instead of giving up for good.",
                    "When Repair can't fix a source, it now says " +
                        "what it tried — and if the AI detector " +
                        "sat it out because there's no key yet, " +
                        "it says that too, with where to add one.",
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
                SectionHeader(title = "Open-source licences")
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
        Spacer(Modifier.height(88.dp))
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
    "LNReader multisrc family templates (madara / lightnovelwp / readnovelfull, " +
        "ported reading logic)" to
        "MIT Licence — Copyright (c) 2021 Rajarshee Chatterjee (LNReader)",
    "Mangayomi source index (site-reading recipes for Wordrain69 and " +
        "Web Novel Translations, re-implemented natively and verified live)" to
        "Apache Licence 2.0 — Copyright the Mangayomi contributors",
    "Plus Jakarta Sans" to "SIL Open Font Licence 1.1",
    "Inter" to "SIL Open Font Licence 1.1",
    "Literata (TypeTogether)" to "SIL Open Font Licence 1.1"
)

/**
 * v1.0.6.2 (M1): "Copy app details" — version, every source's
 * plain health word and the session's recent problems, in one
 * paste (ProblemDetails.formatAppDetails).
 */
@Composable
private fun CopyAppDetailsButton(container: app.xreader.di.AppContainer) {
    val extensions by container.database.installedExtensionDao().observeAll()
        .collectAsStateWithLifecycle(emptyList())
    val health by container.sourcePrefs.sourceHealth
        .collectAsStateWithLifecycle(emptyMap())
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    GhostPill(
        text = if (copied) "Copied" else "Copy app details",
        enabled = true,
        onClick = {
            val sourceLines = extensions.flatMap { ext ->
                val ids = ext.sourceIds.split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                if (ids.isEmpty()) {
                    listOf("${ext.displayName} — Not checked yet")
                } else {
                    ids.map { sourceId ->
                        val record = health[
                            app.xreader.data.extension.SourceHealthRecord
                                .key(ext.id, sourceId)
                        ]
                        "${ext.displayName} — " +
                            (record?.summaryLine() ?: "Not checked yet")
                    }
                }
            }
            clipboard.setText(
                androidx.compose.ui.text.AnnotatedString(
                    app.xreader.data.extension.ProblemDetails.formatAppDetails(
                        appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        sourceLines = sourceLines,
                        recentProblems =
                            app.xreader.data.extension.DiagnosticLog.entries()
                    )
                )
            )
            copied = true
        }
    )
}
