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
import androidx.compose.material3.Switch
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
import app.xreader.ui.common.EmptyState
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.StatusPill
import app.xreader.ui.common.XreaderPrimaryButton

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
                Switch(
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
                        XreaderPrimaryButton(
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
                        XreaderPrimaryButton(
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
                    XreaderPrimaryButton(
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
                    Text("Health", style = MaterialTheme.typography.titleMedium)
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
                        Text(
                            "Checked " + java.text.DateFormat.getDateTimeInstance(
                                java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
                            ).format(java.util.Date(health.checkedAtMillis)),
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
                                if (result.ok) "✓ ${result.detail}" else "✗ ${result.detail}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (result.ok) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    errorTextColor()
                                }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        XreaderPrimaryButton(
                            text = if (state.testing) "Testing…" else "Run test-drive",
                            onClick = { viewModel.runTestDrive() },
                            enabled = !state.testing && ext.enabled
                        )
                        TextButton(
                            onClick = { repairConfirm = true },
                            enabled = !state.repairing
                        ) {
                            Text(if (state.repairing) "Repairing…" else "Repair")
                        }
                    }
                    Text(
                        if (ext.packageId.startsWith("link.")) {
                            "Repair re-reads the site and rebuilds its rules, " +
                                "then tests again. Your current rules are kept " +
                                "if the rebuild can't be saved."
                        } else {
                            "This source came from a repository — if a step " +
                                "fails, its repository needs to update it."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── v1.0.2 (R24): Bring my library ───────────────────
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Bring my library",
                        style = MaterialTheme.typography.titleMedium
                    )
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
                                XreaderPrimaryButton(
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
                            XreaderPrimaryButton(
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
                Text("Sites it may open", style = MaterialTheme.typography.titleMedium)
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
                        Switch(
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
                    Switch(
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
                        Switch(
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
                Text("Sign-in", style = MaterialTheme.typography.titleMedium)
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
                XreaderPrimaryButton(
                    text = if (state.sessionSaved) "Sign in again" else "Log in",
                    onClick = onLogin,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ── cleaner rules ───────────────────────────────────────
            GlassCard {
                Text("Text cleaners", style = MaterialTheme.typography.titleMedium)
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
                        Switch(
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
                    Text("Search", style = MaterialTheme.typography.titleMedium)
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
                        XreaderPrimaryButton(
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
            Spacer(Modifier.height(12.dp))
        }
    }

    if (repairConfirm) {
        AlertDialog(
            onDismissRequest = { repairConfirm = false },
            title = { Text("Repair this source?") },
            text = {
                Text(
                    if (ext?.packageId?.startsWith("link.") == true) {
                        "Xreader will re-read the site's pages and rebuild " +
                            "this source's rules, then run the test-drive " +
                            "again. Your current rules are backed up first " +
                            "and restored if the rebuild can't be saved."
                    } else {
                        "This source came from a repository, so its rules " +
                            "can't be rebuilt here. Repair will run the " +
                            "test-drive and show exactly which step fails."
                    }
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
    }
