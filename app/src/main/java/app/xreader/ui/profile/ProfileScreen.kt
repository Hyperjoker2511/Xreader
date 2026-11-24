package app.xreader.ui.profile
import app.xreader.ui.theme.sageTextColor
import app.xreader.ui.theme.aiAccentColor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.core.motion.animateProgressAsState
import app.xreader.di.AppContainer
import app.xreader.domain.game.Achievements
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.LoadingState
import app.xreader.ui.common.RowItem
import app.xreader.ui.common.SectionHeader
import app.xreader.ui.common.XreaderPrimaryButton
import coil.compose.AsyncImage
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import app.xreader.ui.theme.LocalDockClearance
import app.xreader.ui.theme.LocalUiMetrics
import app.xreader.ui.theme.effectiveBottomPadding
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.xreader.data.prefs.settingsDataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Code 122 (R31): the persisted displayed-badge pick's key
 * on the settings DataStore (see ProfileScreen). */
private val DISPLAYED_BADGE_KEY =
    stringPreferencesKey("profile.displayed_badge_id")

/**
 * Profile & progress (Stitch "Xreader Profile", PLAN §8): identity
 * card, the level ring, the streak heatmap, lifetime stats, the
 * daily-goal setting, and the achievement shelf. Tablet (≥840 dp)
 * arranges the cards in two columns per DESIGN.md.
 */
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    container: AppContainer,
    onBack: () -> Unit,
    /** v1.0.1 Me showpiece wiring. */
    reduceMotion: Boolean = false,
    lowRam: Boolean = false,
    onOpenHistory: () -> Unit = {},
    onOpenSync: () -> Unit = {},
    onOpenCustomise: () -> Unit = {},
    /** v1.1.0 (§5.14): the tools list's Vocab door, with the real
     * word count as its badge (read live from VocabDao below).
     * Optional so older call sites keep compiling; the shell wires
     * it to the vocab route. */
    onOpenVocab: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Code 120 completion (§2.5 R31 + §2.11 Profile): under
    // Material 3 the identity is the design's stock assembly
    // (avatar on a shape-library field, name emphasized) in
    // the header knob's Card or Plain form, and the Nocturne
    // showpiece layers (breathing glow, drift strip, the 3D
    // card) do not render. Under Nocturne nothing here is
    // read and the page is exactly as shipped.
    val designState = app.xreader.ui.theme.LocalDesignState.current
    val isM3Profile = designState.design ==
        app.xreader.data.prefs.AppDesign.MATERIAL3
    val context = LocalContext.current
    var editingName by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }

    val avatarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment ?: ""
            val ext = name.substringAfterLast('.', "jpg")
            viewModel.setAvatar(
                openStream = {
                    runCatching { context.contentResolver.openInputStream(uri) }
                        .getOrNull()
                },
                extensionHint = ext
            )
        }
    }

    // Code 122 (R31, audit item #28): the displayed-badge pick
    // is PERSISTED — a string key on the settings DataStore,
    // the GamePrefs pattern (GamePrefs itself is not this
    // screen's to extend; the key lives with its only reader).
    // Read as a flow so the M3 page always shows the stored
    // pick; writing goes through the same store.
    val badgeStore = remember(container) {
        container.applicationContext.settingsDataStore
    }
    val persistedBadgeId by remember(badgeStore) {
        badgeStore.data.map { prefs ->
            prefs[DISPLAYED_BADGE_KEY]
        }
    }.collectAsStateWithLifecycle(initialValue = null)
    val badgeScope = androidx.compose.runtime.rememberCoroutineScope()

    // Code 121 (R31): under Material 3 the Profile page is the
    // spec's uncarded, type-led document (M3ProfilePage at the
    // end of this file) fed by the same real state. Nocturne
    // renders the shipped showpiece page below, untouched.
    if (isM3Profile) {
        val m3Profile = state.profile
        if (state.loading || m3Profile == null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(24.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                    Text(
                        "Profile",
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
                LoadingState(
                    whatIsHappening = "Loading your progress — " +
                        "your books, streak and reading time…",
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
            return
        }
        M3ProfilePage(
            state = state,
            profile = m3Profile,
            avatarFile = m3Profile.avatarPath?.let {
                File(container.bookStorage.resolve(it).absolutePath)
            },
            avatarColour = avatarColourNamed(state.avatarColour)
                ?: MaterialTheme.colorScheme.primary,
            onAvatarClick = {
                avatarLauncher.launch(arrayOf("image/*"))
            },
            onSaveName = { viewModel.setDisplayName(it) },
            persistedBadgeId = persistedBadgeId,
            onPickBadge = { badgeId ->
                badgeScope.launch {
                    badgeStore.edit { prefs ->
                        prefs[DISPLAYED_BADGE_KEY] = badgeId
                    }
                }
            },
            onBack = onBack,
            onOpenHistory = onOpenHistory,
            onOpenSync = onOpenSync,
            onOpenCustomise = onOpenCustomise,
            onOpenVocab = onOpenVocab
        )
        return
    }

    Box(Modifier.fillMaxSize()) {
        // v1.0.4 (D12): the breathing glow — a Me-scoped overlay
        // layer behind this screen's content (never a haze source,
        // never an effect; static under reduce-motion / low RAM).
        // Code 120 completion: a Nocturne showpiece layer — it
        // does not render inside the Material 3 design.
        if (!isM3Profile) {
            MeBreathingGlow(
                reduceMotion = reduceMotion,
                lowRam = lowRam,
                modifier = Modifier.fillMaxSize()
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(start = 24.dp, end = 24.dp, top = LocalUiMetrics.current.topGap, bottom = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    "Profile",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            val profile = state.profile
            if (state.loading || profile == null) {
                // v1.5.8 (J6/J15): the state kit — spinner + what
                // is happening + "still loading", not a bare line.
                LoadingState(
                    whatIsHappening = "Loading your progress — " +
                        "your books, streak and reading time…",
                    modifier = Modifier.padding(top = 16.dp)
                )
                return@Column
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                // v1.2.0 (WS4): cards breathe a little more —
                // 16dp between cards instead of 12dp, now that
                // several in-card redundancies are gone.
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                state.message?.let { message ->
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = aiAccentColor(),
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.dismissMessage() }) {
                                Text("OK")
                            }
                        }
                    }
                }

                if (isM3Profile) {
                    // Code 120 completion (R31): the M3 identity
                    // — stock parts only, the header knob's form.
                    // Edit name, avatar colour and everything
                    // below stay shared with Nocturne.
                    M3ProfileIdentity(
                        name = profile.displayName,
                        title = state.title,
                        level = profile.level,
                        streakDays = state.stats.currentStreak,
                        avatarFile = profile.avatarPath?.let {
                            File(
                                container.bookStorage.resolve(it)
                                    .absolutePath
                            )
                        },
                        avatarColour = avatarColourNamed(
                            state.avatarColour
                        ) ?: MaterialTheme.colorScheme.primary,
                        cardForm = designState.profileHeaderCard,
                        onAvatarClick = {
                            avatarLauncher.launch(arrayOf("image/*"))
                        }
                    )
                } else {
                // v1.0.1 Me showpiece: scheme-colour drift strip —
                // the screen's expressive signature (Celebration
                // family; static under Reduce Motion / low RAM).
                MeGradientHeader(reduceMotion = reduceMotion, lowRam = lowRam)
                // v1.2.0 (WS4 declutter): the "Me" headline that
                // used to sit here was the screen's SECOND title —
                // the header above already says "Profile". One
                // title per screen; the gradient strip flows
                // straight into the identity card.

                // v1.0.2 (R29/R30): the Identity Card — Ember
                // Stage behind, 3D tilt + glare, flip for stats.
                val appearanceForCard by container.appearancePrefs.state
                    .collectAsStateWithLifecycle(
                        initialValue = app.xreader.data.prefs.AppearanceState()
                    )
                IdentityCard3D(
                    name = profile.displayName,
                    title = state.title,
                    level = profile.level,
                    levelFraction = state.progress.fraction,
                    streakDays = state.stats.currentStreak,
                    avatarFile = profile.avatarPath?.let {
                        File(container.bookStorage.resolve(it).absolutePath)
                    },
                    avatarColour = avatarColourNamed(state.avatarColour)
                        ?: MaterialTheme.colorScheme.primary,
                    backStats = listOf(
                        "Books finished" to "%,d".format(state.stats.booksFinished),
                        "Chapters read" to "%,d".format(state.stats.chaptersFinished),
                        "Hours reading" to "%,d".format(
                            state.stats.totalActiveMinutes / 60
                        ),
                        "Words read" to "%,d".format(state.stats.totalWordsRead),
                        "Best streak" to "${state.stats.bestStreak} days",
                        "In your library" to "%,d".format(state.stats.booksTotal)
                    ),
                    reduceMotion = reduceMotion,
                    lowRam = lowRam,
                    motionTiltEnabled = appearanceForCard.meCardMotionTilt,
                    onAvatarClick = { avatarLauncher.launch(arrayOf("image/*")) }
                )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = {
                            nameDraft = profile.displayName
                            editingName = true
                        }
                    ) { Text("Edit name") }
                    Text(
                        "Member since " + DateFormat.getDateInstance()
                            .format(Date(profile.createdAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (profile.avatarPath == null) {
                    Text(
                        "Avatar colour",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AvatarColours.forEach { (name, colour) ->
                            Surface(
                                onClick = { viewModel.setAvatarColour(name) },
                                shape = CircleShape,
                                color = colour,
                                modifier = Modifier
                                    .size(30.dp)
                                    .border(
                                        if (state.avatarColour == name) 2.dp else 0.dp,
                                        MaterialTheme.colorScheme.onSurface,
                                        CircleShape
                                    )
                            ) {}
                        }
                    }
                }

                // Level ring card (design centrepiece).
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LevelRing(
                            fraction = state.progress.fraction,
                            level = profile.level
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (state.progress.atMax) {
                                "Top level — The Apex Literati. Every page still counts."
                            } else {
                                "${formatNumber(state.expToNext)} EXP to Level " +
                                    "${profile.level + 1}"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "${formatNumber(profile.totalExp)} EXP in total",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // v1.2.0 (WS4 declutter): the kit pill bar
                        // that used to sit under the ring showed
                        // the ring's own fraction a second time —
                        // removed. The ring IS the progress view;
                        // the two EXP lines keep the numbers.
                    }
                }

                // Streak card + heatmap.
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StreakFlame(
                            streakDays = state.stats.currentStreak,
                            reduceMotion = reduceMotion,
                            lowRam = lowRam,
                            modifier = Modifier.size(52.dp)
                        )
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(
                                "${state.stats.currentStreak} day streak",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Best: ${state.stats.bestStreak} days · " +
                                    "${state.stats.goalDaysMet} goal days met",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Heatmap(state = state)
                    Text(
                        "The last 26 weeks — brighter means more reading; " +
                            "the fullest squares are goal days.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                // Today's goal.
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Today's goal", style = MaterialTheme.typography.titleMedium)
                        if (state.todayGoalMet) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    " Goal met",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = sageTextColor()
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${state.todayMinutes} of ${profile.dailyGoalMinutes} minutes read today",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // v1.0.4 (D13 extras): the mock's fluid goal
                    // bar — fills to today's real fraction through
                    // the engine's progress adapter. The slider
                    // below still sets the goal itself.
                    val goalAnimated = animateProgressAsState(
                        dailyGoalFraction(
                            state.todayMinutes, profile.dailyGoalMinutes
                        ),
                        label = "todaysGoal"
                    )
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { goalAnimated },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        strokeCap = StrokeCap.Round
                    )
                    var goalDraft by remember(profile.dailyGoalMinutes) {
                        mutableFloatStateOf(profile.dailyGoalMinutes.toFloat())
                    }
                    Slider(
                        value = goalDraft,
                        onValueChange = { goalDraft = it },
                        onValueChangeFinished = {
                            viewModel.setDailyGoal(goalDraft.toInt())
                        },
                        valueRange = 5f..180f,
                        steps = 34
                    )
                    // v1.2.0 (WS4 declutter): the "Daily goal: N
                    // minutes" caption under the slider is gone —
                    // the line above the bar already states the
                    // goal ("X of Y minutes read today"), and the
                    // slider still sets it on release, exactly as
                    // before.
                }

                // Preferences & tools (v1.0.1 "Shortcuts", v1.1.0
                // §5.14 RowItem form): LINK to the real screens —
                // never duplicates of their content. The Vocab
                // badge is the REAL saved-word count, observed
                // from the same VocabDao the Vocab screen reads.
                // (§5.14 drops the drawing's "Manage Account &
                // Privacy" row: the app has no account, so the
                // button would be a lie — it is not added.)
                val vocabWords by container.database.vocabDao().observeAll()
                    .collectAsStateWithLifecycle(initialValue = emptyList())
                SectionHeader(title = "Preferences & tools", modifier = Modifier.fillMaxWidth())
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        RowItem(
                            icon = Icons.Filled.Sync,
                            label = "Sync & Backup",
                            modifier = Modifier.fillMaxWidth(),
                            value = null,
                            badge = null,
                            onClick = onOpenSync
                        )
                        RowItem(
                            icon = Icons.Filled.Palette,
                            label = "Customise",
                            modifier = Modifier.fillMaxWidth(),
                            value = null,
                            badge = null,
                            onClick = onOpenCustomise
                        )
                        RowItem(
                            icon = Icons.AutoMirrored.Filled.MenuBook,
                            label = "Words I'm Learning",
                            modifier = Modifier.fillMaxWidth(),
                            value = null,
                            badge = if (vocabWords.isNotEmpty()) {
                                "${vocabWords.size}"
                            } else {
                                null
                            },
                            onClick = onOpenVocab
                        )
                        RowItem(
                            icon = Icons.Filled.History,
                            label = "History",
                            modifier = Modifier.fillMaxWidth(),
                            value = null,
                            badge = null,
                            onClick = onOpenHistory
                        )
                    }
                }

                // Summary metrics (design §5.14: 2×2 tiles over
                // the REAL stats — Time Spent, Completed, Progress
                // chapters, Pace). v1.0.4 (D12): the numbers ROLL
                // UP on entry (the mock's animated counters) via
                // the showpiece's CountUpText, bound to the same
                // real stats; static under reduce-motion. Pace
                // keeps its "—" for no-pace-yet and its trend
                // arrow in the label. (Books-in-library still
                // shows on the identity card's back.)
                SectionHeader(title = "Your reading", modifier = Modifier.fillMaxWidth())
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            StatCountCell(
                                value = state.stats.totalActiveMinutes / 60,
                                suffix = "h",
                                label = "Time spent",
                                reduceMotion = reduceMotion,
                                modifier = Modifier.weight(1f)
                            )
                            StatCountCell(
                                value = state.stats.booksFinished,
                                label = "Completed",
                                reduceMotion = reduceMotion,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(Modifier.fillMaxWidth()) {
                            StatCountCell(
                                value = state.stats.chaptersFinished,
                                label = "Progress chapters",
                                reduceMotion = reduceMotion,
                                modifier = Modifier.weight(1f)
                            )
                            if (state.wpmNow > 0) {
                                StatCountCell(
                                    value = state.wpmNow,
                                    label = "Pace · words/min" + when {
                                        state.wpmDelta > 0 -> " ↑${state.wpmDelta}"
                                        state.wpmDelta < 0 -> " ↓${-state.wpmDelta}"
                                        else -> ""
                                    },
                                    reduceMotion = reduceMotion,
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                StatCell(
                                    value = "—",
                                    label = "Pace · words/min",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // Milestones (§5.14): the achievements grid.
                // (A plain header row, not the kit SectionHeader:
                // the kit renders its right-hand text only as a
                // tappable action, and this count is a readout,
                // not a door — a dead button would be a lie.)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Milestones", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${state.unlockedCount} of ${Achievements.ALL.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                state.achievements.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { achievement ->
                            AchievementCard(
                                achievement = achievement,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                // v1.1.0: 88dp bottom clearance (dock + the
                // phone's dead bottom strip).
                Spacer(
                    Modifier.height(
                        effectiveBottomPadding(88.dp, LocalDockClearance.current)
                    )
                )
            }
        }

        if (state.celebrateGoal) {
            ParticleBurst(onFinished = { viewModel.onCelebrationShown() })
        }
    }

    if (editingName) {
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text("Your reader name") },
            text = {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    label = { Text("Display name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setDisplayName(nameDraft)
                        editingName = false
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingName = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun LevelRing(fraction: Float, level: Int) {
    val animated = animateProgressAsState(fraction, label = "levelRing")
    val track = MaterialTheme.colorScheme.surfaceVariant
    // v1.5.1 (UI audit F1): the progress arc sweep is scheme-driven
    // — primary → tertiary → primary, exactly as the kit's
    // ProgressPillBar — so the ring follows the active theme
    // (Nocturne Violet by default) instead of the hardcoded
    // v1.0.4 cyan/electric-purple palette that rendered BLUE.
    val sweep = androidx.compose.ui.graphics.Brush.sweepGradient(
        listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.primary
        )
    )
    Box(
        Modifier.size(144.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke)
            )
            drawArc(
                brush = sweep,
                startAngle = -90f,
                sweepAngle = 360f * animated.coerceIn(0f, 1f),
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Level",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("Lv $level", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun Heatmap(state: ProfileUiState) {
    val tertiary = MaterialTheme.colorScheme.tertiary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val today = java.time.LocalDate.now()
    // Columns share the card's width (26 weeks must fit a phone):
    // each column is weighted, each cell is a square inside it.
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        state.heatmap.forEach { week ->
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                week.forEach { day ->
                    val color = when {
                        day.inFuture -> track.copy(alpha = 0.3f)
                        day.level == 0 -> track
                        day.level == 1 -> tertiary.copy(alpha = 0.35f)
                        day.level == 2 -> tertiary.copy(alpha = 0.65f)
                        else -> tertiary
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                            .background(color)
                            .then(
                                if (day.date == today) {
                                    Modifier.border(
                                        1.dp,
                                        MaterialTheme.colorScheme.primary,
                                        androidx.compose.foundation.shape
                                            .RoundedCornerShape(3.dp)
                                    )
                                } else {
                                    Modifier
                                }
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** A lifetime-stat cell whose number counts up on entry (D12),
 * with an optional unit suffix kept in the same type style. */
@Composable
private fun StatCountCell(
    value: Int,
    label: String,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    suffix: String = ""
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CountUpText(value = value, reduceMotion = reduceMotion)
            if (suffix.isNotEmpty()) {
                Text(suffix, style = MaterialTheme.typography.titleLarge)
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AchievementCard(
    achievement: AchievementUi,
    modifier: Modifier = Modifier
) {
    val def = achievement.def
    val accent = if (achievement.unlocked) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    // v1.0.4 (D12): the mock's active/locked treatment — unlocked
    // cards at full strength, locked ones quieter (its dimmer-glass
    // alphas, expressed as a layer alpha over our one GlassCard so
    // no second surface system appears).
    GlassCard(
        modifier = modifier.then(
            if (achievement.unlocked) {
                Modifier
            } else {
                Modifier.graphicsLayer { alpha = 0.78f }
            }
        )
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    achievementIcon(def.id),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(22.dp)
                )
                if (achievement.isNew) {
                    Text(
                        "  NEW",
                        style = MaterialTheme.typography.labelSmall,
                        color = aiAccentColor()
                    )
                }
            }
            Text(def.title, style = MaterialTheme.typography.titleSmall)
            Text(
                def.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                when {
                    achievement.unlocked && achievement.unlockedAt != null ->
                        "Unlocked " + DateFormat.getDateInstance()
                            .format(Date(achievement.unlockedAt))
                    achievement.unlocked -> "Unlocked"
                    else -> "${achievement.progress} of ${def.target}"
                },
                style = MaterialTheme.typography.labelMedium,
                color = accent
            )
        }
    }
}

private fun achievementIcon(id: String): ImageVector = when (id) {
    "first-book" -> Icons.AutoMirrored.Filled.MenuBook
    "library-10" -> Icons.Filled.LibraryBooks
    "first-finish" -> Icons.Filled.DoneAll
    "chapters-10", "chapters-100", "chapters-1000" -> Icons.Filled.AutoStories
    "streak-7", "streak-30" -> Icons.Filled.LocalFireDepartment
    "focus-first", "focus-10" -> Icons.Filled.SelfImprovement
    "level-5", "level-10", "level-25", "level-50" -> Icons.Filled.EmojiEvents
    "first-source" -> Icons.Filled.Explore
    "first-lore-chat" -> Icons.Filled.Chat
    "marathon-600" -> Icons.Filled.DirectionsRun
    else -> Icons.Filled.Star
}

/**
 * The goal-completion burst (PLAN §8): spring-era particles on the
 * engine's progress motion — dots fly out from the centre and fade.
 * Reduce-motion is honoured by the caller (the Profile state only
 * celebrates when a goal lands; Appearance's reduce-motion setting
 * collapses the driving progress spec like everywhere else).
 */
@Composable
private fun ParticleBurst(onFinished: () -> Unit) {
    val animatable = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        animatable.animateTo(
            1f,
            animationSpec = app.xreader.core.motion.progressTween()
        )
        onFinished()
    }
    val progress = animatable.value
    if (progress <= 0f || progress >= 1f) return
    val ember = MaterialTheme.colorScheme.primary
    val violet = MaterialTheme.colorScheme.tertiary
    val sage = MaterialTheme.colorScheme.secondary
    val random = remember { Random(20261028) }
    val particles = remember {
        List(28) {
            Triple(
                random.nextFloat() * 360f,
                90f + random.nextFloat() * 260f,
                random.nextFloat()
            )
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val colors = listOf(ember, violet, sage)
        particles.forEachIndexed { index, (angle, distance, seed) ->
            val radians = Math.toRadians(angle.toDouble())
            val travelled = distance * progress
            val position = Offset(
                center.x + (cos(radians) * travelled).toFloat(),
                center.y + (sin(radians) * travelled).toFloat() +
                    120f * progress * progress
            )
            drawCircle(
                color = colors[index % colors.size].copy(
                    alpha = (1f - progress) * 0.9f
                ),
                radius = 5f + seed * 7f,
                center = position
            )
        }
    }
}

private fun formatNumber(value: Long): String =
    java.text.NumberFormat.getIntegerInstance().format(value)

/**
 * Pure: today's reading as a 0..1 fraction of the daily goal
 * (v1.0.4, D13 extras). A non-positive goal reads as no progress
 * rather than dividing by zero; over-goal days clamp at full.
 * Unit-tested — the goal bar and any future goal surface share it.
 */
fun dailyGoalFraction(todayMinutes: Int, goalMinutes: Int): Float =
    if (goalMinutes <= 0) {
        0f
    } else {
        (todayMinutes.toFloat() / goalMinutes.toFloat()).coerceIn(0f, 1f)
    }

/**
 * Code 120 completion (R31): the Profile identity in the
 * Material 3 design — assembled from stock parts only: the
 * avatar on a shape-library marker field (§1.3's Profile
 * spend: the Cookie shape in primaryContainer, the photo or
 * the initial over it, tappable to change the avatar exactly
 * as the 3D card's avatar is), the name in headlineSmall
 * emphasized, level · title and the streak beneath. The
 * header knob picks the form: inside a Card (default) or as
 * a plain row. Nocturne never renders this — its identity is
 * IdentityCard3D.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun M3ProfileIdentity(
    name: String,
    title: String,
    level: Int,
    streakDays: Int,
    avatarFile: File?,
    avatarColour: androidx.compose.ui.graphics.Color,
    cardForm: Boolean,
    onAvatarClick: () -> Unit
) {
    val fieldShape = androidx.compose.material3.MaterialShapes
        .Cookie9Sided.toShape()
    val content: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(fieldShape)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer
                    )
                    .clickable(onClick = onAvatarClick),
                contentAlignment = Alignment.Center
            ) {
                if (avatarFile != null && avatarFile.exists()) {
                    AsyncImage(
                        model = avatarFile,
                        contentDescription = "Your avatar",
                        contentScale = androidx.compose.ui.layout
                            .ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(fieldShape)
                    )
                } else {
                    Text(
                        name.trim().firstOrNull()?.uppercase() ?: "M",
                        style = MaterialTheme.typography.titleLarge,
                        color = avatarColour
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    name.ifBlank { "Me" },
                    style = MaterialTheme.typography
                        .headlineSmallEmphasized,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style
                        .TextOverflow.Ellipsis
                )
                Text(
                    "Level $level · $title",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (streakDays > 0) {
                    Text(
                        "$streakDays-day streak",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
    if (cardForm) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor =
                    MaterialTheme.colorScheme.surfaceContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(Modifier.padding(16.dp)) {
                content()
            }
        }
    } else {
        content()
    }
}

/**
 * Code 121 (R31): the Profile page in the Material 3 design —
 * an uncarded, type-led document fed by the same real state
 * as the Nocturne page: centred shape-cropped avatar,
 * emphasized headlineMedium name, title, member-since; the
 * level row with a linear progress and the EXP line; three
 * filled tonal stat cards; the trophies grid (earned markers
 * shape-cropped; locked ones hidden — only the next-up
 * trophies show, each with a CircularWavyProgressIndicator
 * ring and its progress line); the displayed-badge row with
 * its Pick-a-badge sheet of earned badges; doors as a divider
 * list. Real data only — every number below is the VM's.
 *
 * Displayed badge: code 122 (audit item #28) persists the
 * pick — ProfileScreen reads/writes it on the settings
 * DataStore and passes it in; with no pick stored the
 * displayed badge defaults to the most recently earned
 * trophy, as before.
 */
@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)
@Composable
private fun M3ProfilePage(
    state: ProfileUiState,
    profile: app.xreader.data.db.Profile,
    avatarFile: File?,
    avatarColour: androidx.compose.ui.graphics.Color,
    onAvatarClick: () -> Unit,
    onSaveName: (String) -> Unit,
    /** Code 122 (R31): the persisted displayed-badge pick —
     * the stored badge id (null = none picked yet) and the
     * writer the pick sheet calls. */
    persistedBadgeId: String?,
    onPickBadge: (String) -> Unit,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenCustomise: () -> Unit,
    onOpenVocab: () -> Unit
) {
    val avatarShape = androidx.compose.material3.MaterialShapes
        .Cookie9Sided.toShape()
    var editingName by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }
    var badgeSheetOpen by remember { mutableStateOf(false) }
    val earned = state.achievements.filter { it.unlocked }
    // Code 122 (R31): the displayed badge is the PERSISTED
    // pick (it survives leaving the page); with no pick
    // stored it defaults to the most recently earned trophy,
    // as before. A stored pick whose badge is somehow no
    // longer earned falls back the same way.
    val displayedBadge = earned.firstOrNull {
        it.def.id == persistedBadgeId
    } ?: earned.maxByOrNull { it.unlockedAt ?: 0L }
    val nextUp = state.achievements
        .filter { !it.unlocked }
        .sortedByDescending {
            if (it.def.target > 0) {
                it.progress.toFloat() / it.def.target
            } else {
                0f
            }
        }
        .take(3)
    Column(Modifier.fillMaxSize()) {
        androidx.compose.material3.TopAppBar(
            title = { Text("Profile") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            }
        )
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Identity block — centred, uncarded.
            Box(
                Modifier
                    .size(96.dp)
                    .clip(avatarShape)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer
                    )
                    .clickable(onClick = onAvatarClick),
                contentAlignment = Alignment.Center
            ) {
                if (avatarFile != null && avatarFile.exists()) {
                    AsyncImage(
                        model = avatarFile,
                        contentDescription = "Your avatar",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(avatarShape)
                    )
                } else {
                    Text(
                        profile.displayName.trim().firstOrNull()
                            ?.uppercase() ?: "M",
                        style = MaterialTheme.typography.headlineMedium,
                        color = avatarColour
                    )
                }
            }
            Text(
                profile.displayName.ifBlank { "Me" },
                style = MaterialTheme.typography.headlineMediumEmphasized,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style
                    .TextOverflow.Ellipsis
            )
            Text(
                state.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Member since " + DateFormat.getDateInstance()
                    .format(Date(profile.createdAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = {
                nameDraft = profile.displayName
                editingName = true
            }) { Text("Edit name") }

            // Level row + linear progress + EXP line.
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Level ${state.progress.level}",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        if (state.progress.atMax) {
                            "Top level"
                        } else {
                            "${state.expToNext} EXP to level " +
                                "${state.progress.level + 1}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { state.progress.fraction },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${state.progress.intoLevel} of " +
                        "${state.progress.spanToNext} EXP in this level",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Three filled tonal stat cards.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                M3StatCard(
                    value = "${state.stats.currentStreak}",
                    label = "Day streak",
                    modifier = Modifier.weight(1f)
                )
                M3StatCard(
                    value = "%,d".format(state.stats.booksFinished),
                    label = "Books finished",
                    modifier = Modifier.weight(1f)
                )
                M3StatCard(
                    value = "${state.stats.totalActiveMinutes / 60} h",
                    label = "Reading time",
                    modifier = Modifier.weight(1f)
                )
            }

            // Trophies.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Trophies",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "${state.unlockedCount} of ${Achievements.ALL.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (earned.isEmpty() && nextUp.isEmpty()) {
                Text(
                    "Trophies arrive as you read — finish a book " +
                        "to earn the first one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            earned.chunked(3).forEach { rowItems ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowItems.forEach { achievement ->
                        M3TrophyMarker(
                            achievement = achievement,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(3 - rowItems.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
            nextUp.forEach { achievement ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        androidx.compose.material3
                            .CircularWavyProgressIndicator(
                                progress = {
                                    if (achievement.def.target > 0) {
                                        (achievement.progress.toFloat() /
                                            achievement.def.target)
                                            .coerceIn(0f, 1f)
                                    } else {
                                        0f
                                    }
                                },
                                modifier = Modifier.size(48.dp)
                            )
                        Icon(
                            achievementIcon(achievement.def.id),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            achievement.def.title,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            "${achievement.progress} of " +
                                "${achievement.def.target} — next up",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme
                                .onSurfaceVariant
                        )
                    }
                }
            }

            // Displayed badge row.
            if (displayedBadge != null) {
                androidx.compose.material3.ListItem(
                    headlineContent = {
                        Text(displayedBadge.def.title)
                    },
                    supportingContent = {
                        Text("Your displayed badge")
                    },
                    leadingContent = {
                        Icon(
                            achievementIcon(displayedBadge.def.id),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingContent = {
                        TextButton(onClick = { badgeSheetOpen = true }) {
                            Text("Pick a badge")
                        }
                    },
                    colors = androidx.compose.material3.ListItemDefaults
                        .colors(
                            containerColor = androidx.compose.ui
                                .graphics.Color.Transparent
                        ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Doors as a divider list.
            Column(Modifier.fillMaxWidth()) {
                androidx.compose.material3.HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                val doors = listOf(
                    Triple(
                        Icons.Filled.History, "History",
                        onOpenHistory
                    ),
                    Triple(Icons.Filled.Sync, "Sync & backup", onOpenSync),
                    Triple(
                        Icons.AutoMirrored.Filled.MenuBook, "Vocabulary",
                        onOpenVocab
                    ),
                    Triple(
                        Icons.Filled.Palette, "Customise",
                        onOpenCustomise
                    )
                )
                doors.forEachIndexed { index, (icon, label, action) ->
                    androidx.compose.material3.ListItem(
                        headlineContent = { Text(label) },
                        leadingContent = {
                            Icon(
                                icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        colors = androidx.compose.material3
                            .ListItemDefaults.colors(
                                containerColor = androidx.compose.ui
                                    .graphics.Color.Transparent
                            ),
                        modifier = Modifier.clickable(onClick = action)
                    )
                    if (index < doors.lastIndex) {
                        androidx.compose.material3.HorizontalDivider(
                            color = MaterialTheme.colorScheme
                                .outlineVariant,
                            modifier = Modifier.padding(start = 56.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (editingName) {
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text("Your reader name") },
            text = {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    label = { Text("Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    editingName = false
                    onSaveName(nameDraft)
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingName = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    if (badgeSheetOpen) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { badgeSheetOpen = false }
        ) {
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Pick a badge",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Only badges you have earned can be displayed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                earned.forEach { achievement ->
                    androidx.compose.material3.ListItem(
                        headlineContent = {
                            Text(achievement.def.title)
                        },
                        supportingContent = {
                            Text(achievement.def.description)
                        },
                        leadingContent = {
                            Icon(
                                achievementIcon(achievement.def.id),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingContent = {
                            if (achievement.def.id ==
                                displayedBadge?.def?.id
                            ) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = "Displayed",
                                    tint = MaterialTheme.colorScheme
                                        .primary
                                )
                            }
                        },
                        colors = androidx.compose.material3
                            .ListItemDefaults.colors(
                                containerColor = androidx.compose.ui
                                    .graphics.Color.Transparent
                            ),
                        modifier = Modifier.clickable {
                            onPickBadge(achievement.def.id)
                            badgeSheetOpen = false
                        }
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun M3StatCard(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor =
                MaterialTheme.colorScheme.secondaryContainer
        ),
        modifier = modifier
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                value,
                style = MaterialTheme.typography.headlineSmallEmphasized,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)
@Composable
private fun M3TrophyMarker(
    achievement: AchievementUi,
    modifier: Modifier = Modifier
) {
    val shapes = listOf(
        androidx.compose.material3.MaterialShapes.Cookie7Sided,
        androidx.compose.material3.MaterialShapes.Gem,
        androidx.compose.material3.MaterialShapes.Cookie9Sided,
        androidx.compose.material3.MaterialShapes.Sunny,
        androidx.compose.material3.MaterialShapes.Clover4Leaf,
        androidx.compose.material3.MaterialShapes.Pentagon
    )
    val shape = shapes[
        (achievement.def.id.hashCode() and Int.MAX_VALUE) %
            shapes.size
    ].toShape()
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(shape)
                .background(
                    MaterialTheme.colorScheme.primaryContainer
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                achievementIcon(achievement.def.id),
                contentDescription = achievement.def.title,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            achievement.def.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
