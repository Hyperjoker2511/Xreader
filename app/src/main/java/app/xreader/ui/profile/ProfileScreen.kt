package app.xreader.ui.profile
import app.xreader.ui.theme.sageTextColor
import app.xreader.ui.theme.aiAccentColor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

    Box(Modifier.fillMaxSize()) {
        // v1.0.4 (D12): the breathing glow — a Me-scoped overlay
        // layer behind this screen's content (never a haze source,
        // never an effect; static under reduce-motion / low RAM).
        MeBreathingGlow(
            reduceMotion = reduceMotion,
            lowRam = lowRam,
            modifier = Modifier.fillMaxSize()
        )
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
                    "Profile",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            val profile = state.profile
            if (state.loading || profile == null) {
                Text(
                    "Loading your progress…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
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
                Spacer(Modifier.height(88.dp))
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
    // v1.0.4 (D12): the progress arc is the mock's sweep gradient —
    // cyan → electric purple → cyan (the user's palette tokens),
    // still animated to the real fraction by the progress adapter.
    val sweep = androidx.compose.ui.graphics.Brush.sweepGradient(
        listOf(
            app.xreader.ui.theme.VibrantCyan,
            app.xreader.ui.theme.ElectricPurple,
            app.xreader.ui.theme.VibrantCyan
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
