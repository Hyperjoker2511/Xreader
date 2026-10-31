package app.xreader.ui.sources
import app.xreader.ui.theme.errorTextColor

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.data.db.TrustState
import app.xreader.data.extension.ExtensionKind
import app.xreader.data.extension.healthDot
import app.xreader.ui.common.EmptyState
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.GhostPill
import app.xreader.ui.common.NocturneChip
import app.xreader.ui.common.NocturneSwitch
import app.xreader.ui.common.PrimaryPill
import app.xreader.ui.common.SectionHeader
import app.xreader.ui.common.StatusPill

/**
 * A source's page (PLAN §5/§9.1): what it is, what changed (when it's
 * frozen), which sites it may open, its saved sign-in, its cleaner
 * rules — and the way to remove it. Every control here writes straight
 * to the trust engine's data; nothing is decorative.
 */
@Composable
fun ExtensionDetailScreen(
    viewModel: ExtensionDetailViewModel,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onOpenSource: (sourceId: String) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val engineLabel by viewModel.engineLabel.collectAsStateWithLifecycle()
    var showDexConsent by remember { mutableStateOf(false) }
    var showUninstallConfirm by remember { mutableStateOf(false) }
    var repairConfirm by remember { mutableStateOf(false) }
    // v1.0.5 (E5): "Copy details" confirmation on the Health card.
    var detailsCopied by remember { mutableStateOf(false) }
    var newPattern by remember { mutableStateOf("") }
    var newReplacement by remember { mutableStateOf("") }
    var testSample by remember { mutableStateOf("") }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshSession() }
    LaunchedEffect(viewModel) {
        viewModel.gone.collect { onBack() }
    }

    val ext = state.extension
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // ── v1.0.4 (D14): the parser-rules editor takes over the
        // page while it's open (a labelled destination from the
        // Parser rules card below, next to Health).
        if (ext != null && state.parserEditorOpen && state.linkConfig != null) {
            ParserRulesEditorScreen(
                config = state.linkConfig!!,
                saving = state.parserSaving,
                message = state.parserMessage,
                onSave = { viewModel.saveParserRules(it) },
                onBack = { viewModel.closeParserEditor() },
                // v1.1.0 (§5.10): read-only with the honest
                // line for kinds whose rules can't be rebuilt.
                editable = app.xreader.data.extension
                    .SourceRebuildability
                    .hasEditableRules(state.rebuildKind),
                readOnlyNote = app.xreader.data.extension
                    .SourceRebuildability
                    .parserRulesLine(state.rebuildKind)
            )
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                ext?.displayName ?: "Source",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
            if (ext != null) {
                NocturneSwitch(
                    checked = ext.enabled,
                    onCheckedChange = { viewModel.setEnabled(it) }
                )
            }
        }
        if (ext == null) {
            EmptyState(
                title = "Source not found",
                message = "It may have been uninstalled.",
                actionLabel = "Back",
                onAction = onBack
            )
            return@Column
        }
        val permissions = state.permissions
        val kind = kindOf(ext.kind)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── trust state ─────────────────────────────────────────
            if (ext.trustState == TrustState.CHANGED_REQUIRES_RETRUST &&
                state.pending == null
            ) {
                GlassCard {
                    Text(
                        "This source changed",
                        style = MaterialTheme.typography.titleMedium,
                        color = errorTextColor()
                    )
                    Text(
                        "Its file no longer matches the fingerprint from when you " +
                            "trusted it, so Xreader froze it. If you didn't expect a " +
                            "change, uninstall it instead.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryPill(
                            text = "Re-trust it",
                            onClick = { viewModel.retrust() }
                        )
                        TextButton(onClick = { showUninstallConfirm = true }) {
                            Text("Uninstall")
                        }
                    }
                }
            }
            state.pending?.let { pending ->
                GlassCard {
                    Text(
                        "Update waiting: v${pending.versionName}",
                        style = MaterialTheme.typography.titleMedium,
                        color = errorTextColor()
                    )
                    Text(
                        "This update is paused because " +
                            pending.reasons.joinToString("; ") +
                            ". The version you trusted keeps working once you decide — " +
                            "accepting pins the new fingerprint.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryPill(
                            text = "Accept update",
                            onClick = { viewModel.acceptPending() }
                        )
                        TextButton(onClick = { viewModel.rejectPending() }) {
                            Text("Discard update")
                        }
                    }
                }
            }

            // ── what it is ──────────────────────────────────────────
            GlassCard {
                InfoRow("Kind", kind?.label ?: ext.kind.name)
                InfoRow("Version", "v${ext.versionName}")
                InfoRow(
                    "Languages",
                    ext.languages.split(",").filter { it.isNotBlank() }
                        .joinToString(", ") { it.uppercase() }.ifBlank { "—" }
                )
                Text(
                    "Fingerprint",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    ext.sha256,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(8.dp))
                val sourceIds = ext.sourceIds.split(",").map { it.trim() }.filter { it.isNotBlank() }
                if (sourceIds.size == 1 && ext.trustState == TrustState.TRUSTED) {
                    PrimaryPill(
                        text = "Open source",
                        onClick = { onOpenSource(sourceIds.first()) },
                        enabled = ext.enabled,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (sourceIds.size > 1) {
                    sourceIds.forEach { sourceId ->
                        TextButton(
                            onClick = { onOpenSource(sourceId) },
                            enabled = ext.enabled && ext.trustState == TrustState.TRUSTED,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Open $sourceId") }
                    }
                }
            }

            // ── v1.0.2 (R10): health — the test-drive verdict with
            // its proof, and Repair. Visible right under identity:
            // a source's trustworthiness is a first-class fact.
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionHeader(title = "Health", eyebrow = "Test-drive")
                    val health = state.health
                    if (health == null) {
                        Text(
                            "Not checked yet. A test-drive opens a real book " +
                                "on this source and reads its first chapter — " +
                                "the proof is shown, not just claimed.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            health.summaryLine(),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        NocturneChip(
                            label = health.shortStateWord(),
                            dotColor = when (
                                health.state.healthDot()
                            ) {
                                app.xreader.data.extension.HealthDot.GREEN ->
                                    MaterialTheme.colorScheme.secondary
                                app.xreader.data.extension.HealthDot.AMBER ->
                                    MaterialTheme.colorScheme.tertiary
                                app.xreader.data.extension.HealthDot.RED ->
                                    MaterialTheme.colorScheme.error
                                app.xreader.data.extension.HealthDot.GREY ->
                                    MaterialTheme.colorScheme
                                        .onSurfaceVariant
                            }
                        )
                        if (health.proofBookTitle.isNotBlank()) {
                            Text(
                                "Proof: opened “${health.proofBookTitle}”" +
                                    if (health.proofChapterTitle.isNotBlank()) {
                                        " · ${health.proofChapterTitle}"
                                    } else {
                                        ""
                                    },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (health.proofFirstLine.isNotBlank()) {
                            Text(
                                "“${health.proofFirstLine}”",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // v1.0.5 (E2): "Last checked" in the
                        // reader's terms — the same record the
                        // Browse health dot reads.
                        Text(
                            app.xreader.data.extension.lastCheckedLine(
                                health.checkedAtMillis,
                                System.currentTimeMillis()
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    state.healthResults.forEach { result ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                when (result.capability) {
                                    app.xreader.data.extension.SourceCapability.LIST ->
                                        "Book list"
                                    app.xreader.data.extension.SourceCapability.SEARCH ->
                                        "Search"
                                    app.xreader.data.extension.SourceCapability.DETAILS ->
                                        "Book pages"
                                    app.xreader.data.extension.SourceCapability.CHAPTERS ->
                                        "Chapter lists"
                                    app.xreader.data.extension.SourceCapability.TEXT ->
                                        "Chapter text"
                                    app.xreader.data.extension.SourceCapability.REVIEWS ->
                                        "Reviews"
                                    app.xreader.data.extension.SourceCapability.COMMENTS ->
                                        "Comments"
                                    app.xreader.data.extension.SourceCapability.RELATED ->
                                        "More like this"
                                    else -> result.capability.name
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                // v1.1.0 (§6.5b): a by-design
                                // absence reads as a neutral
                                // fact, never a failure.
                                if (result.notApplicable) {
                                    "— ${result.detail}"
                                } else if (result.ok) {
                                    "✓ ${result.detail}"
                                } else {
                                    "✗ ${result.detail}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (result.ok || result.notApplicable) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    errorTextColor()
                                }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryPill(
                            text = if (state.testing) "Testing…" else "Run test-drive",
                            onClick = { viewModel.runTestDrive() },
                            enabled = !state.testing && ext.enabled
                        )
                        GhostPill(
                            text = if (state.repairing) "Repairing…" else "Repair",
                            onClick = { repairConfirm = true },
                            enabled = !state.repairing
                        )
                    }
                    Text(
                        // v1.1.0 (Fix 5): the true per-kind
                        // Repair behaviour (§6.4), one helper.
                        app.xreader.data.extension.SourceRebuildability
                            .repairLine(state.rebuildKind),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // v1.0.5 (E5): one-tap problem details — a
                    // failing verdict becomes a compact plain-text
                    // summary on the clipboard (site, failing step,
                    // reason, app version), ready to paste anywhere.
                    if (health != null && (
                            health.state ==
                                app.xreader.data.extension.SourceHealthState.NEEDS_SETUP ||
                                health.state ==
                                app.xreader.data.extension.SourceHealthState.BROKEN
                            )
                    ) {
                        // Non-null snapshot for the click lambda
                        // below: a smart cast on `health` does not
                        // carry into the lambda, a plain val does.
                        val healthNow = health
                        val clipboard = androidx.compose.ui.platform
                            .LocalClipboardManager.current
                        TextButton(onClick = {
                            val failedResult = state.healthResults.firstOrNull {
                                !it.ok && it.capability in
                                    app.xreader.data.extension.ContractVerdict.READING_CORE
                            }
                            val failedCapability = failedResult?.capability
                                ?: healthNow.failedCore.firstOrNull()?.let { raw ->
                                    runCatching {
                                        app.xreader.data.extension
                                            .SourceCapability.valueOf(raw)
                                    }.getOrNull()
                                }
                            val failingStep = when (failedCapability) {
                                app.xreader.data.extension.SourceCapability.LIST ->
                                    "Book list"
                                app.xreader.data.extension.SourceCapability.SEARCH ->
                                    "Search"
                                app.xreader.data.extension.SourceCapability.DETAILS ->
                                    "Book pages"
                                app.xreader.data.extension.SourceCapability.CHAPTERS ->
                                    "Chapter lists"
                                app.xreader.data.extension.SourceCapability.TEXT ->
                                    "Chapter text"
                                else -> failedCapability?.name
                            }
                            clipboard.setText(
                                androidx.compose.ui.text.AnnotatedString(
                                    app.xreader.data.extension.ProblemDetails.format(
                                        host = state.linkConfig?.baseUrl?.let {
                                            app.xreader.data.extension.CookieParsing
                                                .hostOf(it)
                                        },
                                        failingStep = failingStep,
                                        reason = failedResult?.detail
                                            ?: healthNow.summaryLine(),
                                        appVersion = app.xreader.BuildConfig.VERSION_NAME,
                                        // v1.0.6: the last
                                        // Repair's record rides
                                        // along when one exists.
                                        repair = state.lastRepair,
                                        // v1.0.6 (W3.5): the
                                        // payload says when this
                                        // source reads through
                                        // the browser view.
                                        browserView =
                                            state.linkConfig?.needsRendering == true,
                                        // v1.0.6.1 (P6): the
                                        // session's problem
                                        // history rides along.
                                        recentProblems =
                                            app.xreader.data.extension
                                                .DiagnosticLog.entries()
                                    )
                                )
                            )
                            detailsCopied = true
                        }) {
                            Text(if (detailsCopied) "Copied ✓" else "Copy details")
                        }
                    }
                    // v1.0.6: the last Repair, persisted — what
                    // it did and what each proposer did, in
                    // plain words. A Repair's outcome used to
                    // live only in a message that vanished; now
                    // it stays here and rides along in Copy
                    // details.
                    val lastRepair = state.lastRepair
                    if (lastRepair != null) {
                        Text(
                            "Last repair",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            app.xreader.data.extension.lastCheckedLine(
                                lastRepair.repairedAtMillis,
                                System.currentTimeMillis()
                            ).replace("Last checked", "Ran") +
                                if (lastRepair.saved) {
                                    " — the rules were rebuilt and saved."
                                } else {
                                    " — the rules weren't changed."
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (lastRepair.note.isNotBlank()) {
                            Text(
                                lastRepair.note,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        lastRepair.stageLines.forEach { line ->
                            Text(
                                "· $line",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme
                                    .colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ── v1.0.4 (D14): parser rules — the escape hatch when
            // a site changes its layout. Editable for link/AI-built
            // sources (their rules live on this device); repo
            // sources say plainly why theirs can't be edited here —
            // the same distinction Repair already makes.
            if (
                kind == ExtensionKind.DECLARATIVE ||
                state.rebuildKind ==
                app.xreader.data.extension.RebuildKind.LIVE
            ) {
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionHeader(title = "Parser rules")
                        if (
                            app.xreader.data.extension
                                .SourceRebuildability.hasEditableRules(
                                    state.rebuildKind
                                )
                        ) {
                            Text(
                                app.xreader.data.extension
                                    .SourceRebuildability.parserRulesLine(
                                        state.rebuildKind
                                    ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme
                                    .onSurfaceVariant
                            )
                            // v1.0.6 (W3.5/Q5): the reading mode,
                            // stated plainly on the page.
                            when {
                                state.linkConfig?.needsRendering == true ->
                                    Text(
                                        "This site builds its pages as you " +
                                            "look at them, so Xreader reads " +
                                            "them with its built-in browser " +
                                            "view. Everything looks the same — " +
                                            "pages may just take a few " +
                                            "seconds longer.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme
                                            .onSurfaceVariant
                                    )
                                state.browserViewNeeded ->
                                    Text(
                                        "This site has started building its " +
                                            "pages with JavaScript — run " +
                                            "Repair above and Xreader will set " +
                                            "up the browser view for it.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme
                                            .onSurfaceVariant
                                    )
                            }
                            // v1.0.6.2 (RE): how this source's
                            // chapter lists are fetched, in plain
                            // words.
                            Text(
                                if (state.linkConfig?.details?.chapters
                                        ?.pagination != null
                                ) {
                                    "Chapter lists: full — Xreader fetches " +
                                        "the whole list page by page, not " +
                                        "just the chapters a book page shows."
                                } else {
                                    "Chapter lists: as the site shows " +
                                        "them — a book shows the chapters " +
                                        "its own page lists."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme
                                    .onSurfaceVariant
                            )
                            PrimaryPill(
                                text = "Edit parser rules",
                                onClick = { viewModel.openParserEditor() },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(
                                // v1.1.0 (Fix 5): the honest
                                // per-kind line — read-only while
                                // LIVE, package-bound for code and
                                // multi-config rows (§6.4).
                                app.xreader.data.extension
                                    .SourceRebuildability.parserRulesLine(
                                        state.rebuildKind
                                    ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme
                                    .onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ── v1.0.2 (R24): Bring my library ───────────────────
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionHeader(title = "Bring my library")
                    if (!state.hasBookshelf) {
                        Text(
                            "This source doesn't know its account bookshelf " +
                                "yet, so there's nothing to bring over. When " +
                                "its rules learn the bookshelf page, this " +
                                "card will offer the import.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "Reads your bookshelf on this site (you must " +
                                "be logged in) and adds the books that " +
                                "aren't in your Library yet. Nothing is " +
                                "downloaded — just the books themselves.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        state.bringPreview?.let { preview ->
                            Text(
                                preview.summaryLine(),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            // ── v1.0.3: AI match proposals ──────────
                            if (preview.newBooks > 0) {
                                Row(
                                    verticalAlignment =
                                        Alignment.CenterVertically,
                                    horizontalArrangement =
                                        Arrangement.spacedBy(8.dp)
                                ) {
                                    TextButton(
                                        onClick = { viewModel.checkAiMatches() },
                                        enabled = !state.aiMatchBusy &&
                                            !state.bringBusy
                                    ) {
                                        Text(
                                            if (state.aiMatchBusy) {
                                                "AI is checking…"
                                            } else {
                                                "Ask AI to spot matches"
                                            }
                                        )
                                    }
                                    app.xreader.ui.ai.EngineChip(
                                        label = engineLabel
                                    )
                                }
                                Text(
                                    "Some \"new\" books may already be in your " +
                                        "Library under a slightly different " +
                                        "title. The AI can propose matches — " +
                                        "nothing counts as a match until you " +
                                        "confirm it.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            state.aiMatchNote?.let { note ->
                                Text(
                                    note,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            state.aiProposals.forEach { (remoteKey, proposal) ->
                                val confirmed =
                                    remoteKey in state.aiConfirmedMatches
                                Column(
                                    verticalArrangement =
                                        Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        "AI thinks “${proposal.account.title}” " +
                                            "is your copy of " +
                                            "“${proposal.existing.title}”.",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Row(
                                        horizontalArrangement =
                                            Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (confirmed) {
                                            Text(
                                                "✓ Confirmed — it won't be added again",
                                                style = MaterialTheme
                                                    .typography.bodyMedium,
                                                color = MaterialTheme
                                                    .colorScheme.secondary
                                            )
                                        } else {
                                            TextButton(
                                                onClick = {
                                                    viewModel.confirmAiMatch(remoteKey)
                                                },
                                                enabled = !state.bringBusy
                                            ) { Text("Yes, same book") }
                                            TextButton(
                                                onClick = {
                                                    viewModel.rejectAiMatch(remoteKey)
                                                },
                                                enabled = !state.bringBusy
                                            ) { Text("No, different book") }
                                        }
                                    }
                                }
                            }
                            Row(
                                horizontalArrangement =
                                    Arrangement.spacedBy(8.dp)
                            ) {
                                PrimaryPill(
                                    text = if (state.bringBusy) {
                                        "Bringing over…"
                                    } else {
                                        "Bring them over"
                                    },
                                    onClick = { viewModel.importBookshelf() },
                                    enabled = !state.bringBusy &&
                                        preview.newBooks > 0
                                )
                                TextButton(
                                    onClick = { viewModel.dismissBringPreview() },
                                    enabled = !state.bringBusy
                                ) { Text("Not now") }
                            }
                        } ?: run {
                            PrimaryPill(
                                text = if (state.bringBusy) {
                                    "Looking…"
                                } else {
                                    "Scan my bookshelf"
                                },
                                onClick = { viewModel.scanBookshelf() },
                                enabled = !state.bringBusy && ext.enabled
                            )
                        }
                        state.bringMessage?.let { message ->
                            Text(
                                message,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            // ── permissions ─────────────────────────────────────────
            GlassCard {
                SectionHeader(title = "Sites it may open")
                Text(
                    "Requests to any other site are blocked inside Xreader — " +
                        "the source can't reach them at all.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                if (permissions.domains.isEmpty()) {
                    Text(
                        "No sites declared.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                permissions.domains.forEach { domain ->
                    val allowed = domain !in permissions.blockedDomains
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(domain, Modifier.weight(1f))
                        NocturneSwitch(
                            checked = allowed,
                            onCheckedChange = { viewModel.toggleDomain(domain, it) }
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Notifications")
                        Text(
                            "Declared by the source; off unless you allow it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    NocturneSwitch(
                        checked = permissions.canNotify,
                        onCheckedChange = { viewModel.setCanNotify(it) }
                    )
                }
                if (kind == ExtensionKind.DEX) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Run app code")
                            Text(
                                "This source runs real program code inside Xreader. " +
                                    "It still can't open sites you haven't allowed, " +
                                    "but only enable this for sources you fully trust.",
                                style = MaterialTheme.typography.bodySmall,
                                color = errorTextColor()
                            )
                        }
                        NocturneSwitch(
                            checked = permissions.dexOptIn,
                            onCheckedChange = { wanted ->
                                if (wanted) showDexConsent = true
                                else viewModel.setDexOptIn(false)
                            }
                        )
                    }
                }
            }

            // ── session ─────────────────────────────────────────────
            GlassCard {
                SectionHeader(title = "Sign-in")
                if (state.sessionSaved) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatusPill(
                            text = "Signed in",
                            color = MaterialTheme.colorScheme.secondary
                        )
                        TextButton(onClick = { viewModel.forgetSession() }) {
                            Text("Forget sign-in")
                        }
                    }
                }
                Text(
                    "Sign in inside Xreader's browser — you type your password and " +
                        "pass any checks yourself. Xreader saves only this source's " +
                        "session cookies, encrypted on this device, and never sees " +
                        "your password.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                PrimaryPill(
                    text = if (state.sessionSaved) "Sign in again" else "Log in",
                    onClick = onLogin,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ── cleaner rules ───────────────────────────────────────
            GlassCard {
                SectionHeader(title = "Text cleaners")
                Text(
                    "Search-and-replace rules applied to this source's chapters " +
                        "as they download — for site footers, watermarks and " +
                        "repeated announcements. Rules that run too long are " +
                        "switched off automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                state.cleanerRules.forEachIndexed { index, rule ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                rule.pattern,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                if (rule.replacement.isEmpty()) "→ removed"
                                else "→ ${rule.replacement}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        NocturneSwitch(
                            checked = rule.enabled,
                            onCheckedChange = { viewModel.toggleRule(index, it) }
                        )
                        TextButton(onClick = { viewModel.deleteRule(index) }) {
                            Text("Delete")
                        }
                    }
                }
                OutlinedTextField(
                    value = newPattern,
                    onValueChange = { newPattern = it },
                    label = { Text("Find (pattern)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = newReplacement,
                    onValueChange = { newReplacement = it },
                    label = { Text("Replace with (blank removes)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = {
                        viewModel.addRule(newPattern, newReplacement)
                        newPattern = ""
                        newReplacement = ""
                    },
                    enabled = newPattern.isNotBlank()
                ) { Text("Add rule") }
                Spacer(Modifier.height(10.dp))
                Text("Try your rules", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = testSample,
                    onValueChange = { testSample = it },
                    label = { Text("Sample chapter text") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = { viewModel.testRules(testSample) },
                    enabled = testSample.isNotBlank()
                ) { Text("Test rules") }
                state.testOutput?.let { output ->
                    Text(
                        output,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth()
                            .padding(top = 6.dp)
                    )
                }
                state.testNotes.forEach { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = errorTextColor()
                    )
                }
            }

            // ── search setup (v1.0.1) ───────────────────────────────
            state.linkConfig?.let { config ->
                GlassCard {
                    SectionHeader(title = "Search")
                    Spacer(Modifier.height(4.dp))
                    if (config.search == null || config.searchNeedsSetup) {
                        Text(
                            "Search couldn't be set up for this site yet. " +
                                "Browsing and reading work — only searching this " +
                                "source from inside Xreader is missing.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        PrimaryPill(
                            text = "Set up search",
                            onClick = { viewModel.setupSearch() },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "Xreader tries the site again. If you have added a " +
                                "Gemini key, the AI detector can help too — open " +
                                "Add from link for this site.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "Search is set up and was test-proven when this source was added.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── danger ──────────────────────────────────────────────
            TextButton(
                onClick = { showUninstallConfirm = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Uninstall this source", color = errorTextColor())
            }
            // v1.1.0: 88dp bottom clearance — clears the
            // floating dock AND the phone's dead bottom strip.
            Spacer(Modifier.height(88.dp))
        }
    }

    if (repairConfirm) {
        AlertDialog(
            onDismissRequest = { repairConfirm = false },
            title = { Text("Repair this source?") },
            text = {
                Text(
                    // v1.1.0 (Fix 5): the dialog promises
                    // exactly what Repair will do for THIS
                    // row's kind (§6.4) — no more, no less.
                    app.xreader.data.extension.SourceRebuildability
                        .repairLine(state.rebuildKind)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    repairConfirm = false
                    viewModel.repair()
                }) { Text("Repair") }
            },
            dismissButton = {
                TextButton(onClick = { repairConfirm = false }) { Text("Cancel") }
            }
        )
    }
    if (showDexConsent) {
        AlertDialog(
            onDismissRequest = { showDexConsent = false },
            title = { Text("Allow app code?") },
            text = {
                Text(
                    "This source will run real program code inside Xreader — the same " +
                        "trust as installing another app. Its network is still limited " +
                        "to the sites above, and its fingerprint is checked every time " +
                        "it starts. Only do this for a source you completely trust."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDexConsent = false
                    viewModel.setDexOptIn(true)
                }) { Text("I understand — allow it") }
            },
            dismissButton = {
                TextButton(onClick = { showDexConsent = false }) { Text("Cancel") }
            }
        )
    }
    if (showUninstallConfirm) {
        AlertDialog(
            onDismissRequest = { showUninstallConfirm = false },
            title = { Text("Uninstall “${ext?.displayName ?: "this source"}”?") },
            text = {
                Text("Books already in your library stay, but chapters that haven't downloaded yet won't be able to load.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showUninstallConfirm = false
                    viewModel.uninstall()
                }) { Text("Uninstall") }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun kindOf(dbKind: app.xreader.data.db.ExtensionKind): ExtensionKind? =
    when (dbKind) {
        app.xreader.data.db.ExtensionKind.DECLARATIVE -> ExtensionKind.DECLARATIVE
        app.xreader.data.db.ExtensionKind.JS -> ExtensionKind.JS
        app.xreader.data.db.ExtensionKind.DEX -> ExtensionKind.DEX
        // v1.0.9: a Live Source has no package-API kind — the
        // detail screen shows its generic rows (the Kind row
        // falls back to the stored name, "LIVE") and none of
        // the kind-specific sections apply.
        app.xreader.data.db.ExtensionKind.LIVE -> null
    }
