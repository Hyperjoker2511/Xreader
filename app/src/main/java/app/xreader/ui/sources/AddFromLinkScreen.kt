package app.xreader.ui.sources
import app.xreader.ui.theme.aiAccentColor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.XreaderPrimaryButton

/**
 * "Add source from link" (Amendment 7): paste a book page link →
 * Xreader looks at the site, works out how to read it, TEST-DRIVES
 * itself on that book, and shows the proof (title, chapter count,
 * first lines) before offering Save. Plain words at every step,
 * including when it can't manage — with the door left open for the
 * smarter AI-assisted detector coming later.
 */
@Composable
fun AddFromLinkScreen(
    viewModel: LinkAddViewModel,
    sessionSaved: kotlinx.coroutines.flow.SharedFlow<String>,
    onRequestLogin: (LoginRequest) -> Unit,
    onOpenBook: (Long) -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val aiAvailable by viewModel.aiAvailable.collectAsStateWithLifecycle()
    val engineLabel by viewModel.engineLabel.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf("") }
    // v1.0.5 (E5): "Copy details" confirmation on the failure card.
    var detailsCopied by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        sessionSaved.collect { key -> viewModel.onSessionSaved(key) }
    }

    // v1.0.7 gate-fix round: this column SCROLLS. It did not —
    // a long proof card laid "Save this source" out below the
    // bottom of the screen with no way to reach it (found
    // on-device 2026-10-30: "It works" proof, no Add option).
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Add from link", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Paste the link of a book page from a site you read. Xreader will look " +
                "at the page, work out where the chapters and text live, and test " +
                "itself on the book before saving anything.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Book page link") },
            placeholder = { Text("https://site.example/novel/my-book") },
            singleLine = true,
            enabled = state is LinkAddUiState.Idle || state is LinkAddUiState.Failed,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        when (val current = state) {
            is LinkAddUiState.Idle -> {
                XreaderPrimaryButton(
                    text = "Look at this site",
                    onClick = { viewModel.probe(url) },
                    enabled = url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Or skip the test read entirely:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = { viewModel.installLive(url) },
                    enabled = url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Install as a Live Source — works for any site")
                }
                Text(
                    "A Live Source is read fresh from the site every time you open " +
                        "it, in Xreader's own screens — nothing is saved that can go " +
                        "stale, and it can be pinned to your home screen like an app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is LinkAddUiState.Probing -> {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        "Looking at the site and test-reading a chapter…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            is LinkAddUiState.AiProbing -> {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        color = aiAccentColor()
                    )
                    Text(
                        "The AI is studying the page — then Xreader test-reads " +
                            "a chapter with what it proposes. Nothing is saved " +
                            "unless the test passes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            is LinkAddUiState.LoginRequired -> {
                GlassCard {
                    Text(
                        if (current.siteCheck) {
                            "This site needs a quick check first"
                        } else {
                            "This site needs a sign-in first"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        if (current.siteCheck) {
                            "Open the site inside Xreader and do the check yourself, " +
                                "then Xreader will look at the site again with your session."
                        } else {
                            "Log in inside Xreader's browser (you'll pass any checks yourself), " +
                                "then Xreader will look at the site again with your session."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    XreaderPrimaryButton(
                        text = if (current.siteCheck) {
                            "Open ${current.host}"
                        } else {
                            "Log in to ${current.host}"
                        },
                        onClick = {
                            viewModel.loginRequest()?.let(onRequestLogin)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(
                        onClick = { viewModel.reset() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Use a different link") }
                }
            }
            is LinkAddUiState.Ready -> {
                val drive = current.draft.testDrive
                GlassCard {
                    Text("It works — here's the proof", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Book found: “${drive.bookTitle}”",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "${drive.chapterCount} chapters found • first chapter " +
                            "“${drive.firstChapterTitle}”",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    // v1.0.1: search is part of the proof — verified
                    // in plain words, or honestly flagged.
                    if (drive.searchVerified && drive.searchProof != null) {
                        Text(
                            drive.searchProof!!,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text(
                            // v1.0.5 (A5): name WHY search failed
                            // its proof when the drive knows.
                            drive.searchFailure?.let {
                                "Search isn't proven yet: $it. " +
                                    "You can still save it — browsing and reading work, " +
                                    "and the source's page has a “Set up search” button."
                            } ?: (
                                "Search couldn't be set up for this site yet. " +
                                    "You can still save it — browsing and reading work, " +
                                    "and the source's page has a “Set up search” button."
                                ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (drive.lockedChapterCount > 0) {
                        Text(
                            "${drive.lockedChapterCount} of the chapters are paid or " +
                                "locked on the site — those can't be read.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // v1.0.5 (A6): a missing cover is flagged
                    // here, never a silent surprise later.
                    if (drive.coverWarning != null) {
                        Text(
                            drive.coverWarning!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (drive.reviewsFound) {
                        Text(
                            "Reader reviews were found on this site — they'll show on book pages.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The chapter starts:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "“${drive.chapterTextPreview}…”",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = current.sourceName,
                        onValueChange = { viewModel.setSourceName(it) },
                        label = { Text("Source name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    XreaderPrimaryButton(
                        text = "Save this source",
                        onClick = { viewModel.save() },
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(
                        onClick = { viewModel.reset() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Don't save") }
                }
            }
            is LinkAddUiState.Failed -> {
                GlassCard {
                    Text("That didn't work", style = MaterialTheme.typography.titleMedium)
                    Text(
                        current.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    if (aiAvailable) {
                        XreaderPrimaryButton(
                            text = "Try the AI detector",
                            onClick = { viewModel.probeWithAi() },
                            modifier = Modifier.fillMaxWidth()
                        )
                        androidx.compose.foundation.layout.Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            app.xreader.ui.ai.EngineChip(label = engineLabel)
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    XreaderPrimaryButton(
                        text = "Install as a Live Source instead",
                        onClick = { viewModel.installLive(url) },
                        enabled = url.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "A Live Source skips the test read: it's installed as-is " +
                            "and read fresh from the site each time, in Xreader's " +
                            "own screens.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { viewModel.probe(url) },
                        enabled = url.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Try again") }
                    TextButton(
                        onClick = { viewModel.reset() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Start over") }
                    // v1.0.5 (E5): one-tap problem details —
                    // the failure as compact plain text on the
                    // clipboard (site, reason, app version).
                    val clipboard = androidx.compose.ui.platform
                        .LocalClipboardManager.current
                    TextButton(
                        onClick = {
                            clipboard.setText(
                                androidx.compose.ui.text.AnnotatedString(
                                    app.xreader.data.extension.ProblemDetails.format(
                                        // v1.0.7 draw-check fix:
                                        // the host of the NORMALISED
                                        // probed link (hostOf on the
                                        // raw field text returns
                                        // nothing for a bare domain,
                                        // so the paste lost its
                                        // Site line).
                                        host = app.xreader.data.extension
                                            .CookieParsing.hostOf(
                                                app.xreader.data.extension
                                                    .LinkProber
                                                    .normaliseUrl(
                                                        viewModel
                                                            .lastProbedRawUrl
                                                            .ifBlank { url }
                                                    ) ?: url
                                            ),
                                        failingStep = null,
                                        reason = current.message,
                                        appVersion = app.xreader.BuildConfig.VERSION_NAME,
                                        // v1.0.6.1 (P6): the
                                        // session's problem
                                        // history rides along.
                                        recentProblems =
                                            app.xreader.data.extension
                                                .DiagnosticLog.entries(),
                                        // v1.0.7 draw-check fix:
                                        // what the probe tried.
                                        steps = current.steps
                                    )
                                )
                            )
                            detailsCopied = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (detailsCopied) "Copied ✓" else "Copy details") }
                }
            }
            is LinkAddUiState.Saving -> {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        "Saving the source and adding the book…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            is LinkAddUiState.Saved -> {
                GlassCard {
                    Text("Source saved", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "“${current.bookTitle}” is ready in your library, and the source " +
                            "is in Sources like any other — with the same site permissions " +
                            "and trust settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    if (current.bookId != null) {
                        XreaderPrimaryButton(
                            text = "Open the book",
                            onClick = { onOpenBook(current.bookId) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                        Text("Back to Sources")
                    }
                }
            }
            is LinkAddUiState.InstallingLive -> {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        "Installing the Live Source…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            is LinkAddUiState.LiveInstalled -> {
                val context = androidx.compose.ui.platform.LocalContext.current
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                var pinFailed by remember { mutableStateOf(false) }
                GlassCard {
                    Text("Live Source installed", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "“${current.name}” is in Browse like any other source, and it " +
                            "reads the site fresh every time — in Xreader's own screens. " +
                            "You can also pin it to your home screen, where it opens " +
                            "in its own window like an app.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    if (current.bookId != null) {
                        XreaderPrimaryButton(
                            text = "Open the book",
                            onClick = { onOpenBook(current.bookId) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                val bytes = viewModel.liveIconBytes(current.iconUrl)
                                pinFailed = !SiteShortcut.requestPin(
                                    context = context,
                                    extensionId = current.extensionId,
                                    sourceId = current.sourceId,
                                    name = current.name,
                                    iconBytes = bytes
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Add to home screen") }
                    if (pinFailed) {
                        Text(
                            "Your launcher doesn't allow pinning from apps — " +
                                "the source is still in Browse.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                        Text("Back to Sources")
                    }
                }
            }
        }
        // Room to scroll the last actions clear of the
        // floating bottom bar (and of screens whose lowest
        // strip is hard to reach).
        Spacer(Modifier.height(96.dp))
    }
}
